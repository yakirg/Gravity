import * as THREE from 'three';
import { OrbitControls } from './lib/OrbitControls.js';
import { Sim, V, G, SPIN_AXIS, earthRotationAngle, equatorialToEcliptic, siteUp, writeConfig, EPOCH_SECONDS, MAX_SIM_SPEED } from './sim.js';
import { setupEditors } from './editors.js';

const $ = id => document.getElementById(id);
const canvas = $('view');
const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, logarithmicDepthBuffer: true });
renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
const scene = new THREE.Scene();

const NEAR = 1e-4, FAR = 1e10;
const cam = new THREE.PerspectiveCamera(45, 1, NEAR, FAR);
cam.up.set(0, 0, 1);
cam.position.set(20000, -20000, 12000);
const lcam = new THREE.PerspectiveCamera(45, 1, NEAR, FAR);
const controls = new OrbitControls(cam, canvas);
controls.maxDistance = 5e9;
controls.zoomSpeed = 1.2;

const sunLight = new THREE.PointLight(0xffffff, 2.5, 0, 0);
scene.add(sunLight, new THREE.AmbientLight(0xffffff, 1.0));

const stars = (() => {
  const n = 2000, a = new Float32Array(n * 3);
  for (let i = 0; i < n; i++) {
    const z = Math.random() * 2 - 1, t = Math.random() * 2 * Math.PI, r = Math.sqrt(1 - z * z);
    a.set([r * Math.cos(t) * 5e9, r * Math.sin(t) * 5e9, z * 5e9], i * 3);
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.BufferAttribute(a, 3));
  const p = new THREE.Points(g, new THREE.PointsMaterial({ color: 0xffffff, size: 1.5, sizeAttenuation: false, depthWrite: false }));
  p.frustumCulled = false;
  scene.add(p);
  return p;
})();

const COLORS = { Sun: 0xffd700, Mercury: 0xa9a9a9, Venus: 0xdeb887, Earth: 0x1e90ff, Moon: 0xd3d3d3, Mars: 0xff4500, Jupiter: 0xf4a460, Saturn: 0xf0e68c };
const colorOf = m => m.isRocket ? 0xff8800 : (COLORS[m.name] ?? 0xffffff);

const S = { sim: null, focus: null, launchCam: true, camZoom: 1, orbits: true, table: innerWidth > 700, list: [], items: new Map(), trails: new Map(), frameNo: 0, lastTarget: new THREE.Vector3(), viewDist: 1 };
const sizeCache = {};

const earthTex = new THREE.TextureLoader().load('images/earth.jpg');
earthTex.colorSpace = THREE.SRGBColorSpace;
earthTex.anisotropy = 8;

function zSphere(R, lonN, latN) {
  const pos = [], uv = [], nor = [], idx = [];
  for (let j = 0; j <= latN; j++) {
    const la = -Math.PI / 2 + Math.PI * j / latN;
    for (let i = 0; i <= lonN; i++) {
      const lo = -Math.PI + 2 * Math.PI * i / lonN;
      const x = Math.cos(la) * Math.cos(lo), y = Math.cos(la) * Math.sin(lo), z = Math.sin(la);
      pos.push(x * R, y * R, z * R); nor.push(x, y, z); uv.push(i / lonN, j / latN);
    }
  }
  for (let j = 0; j < latN; j++) for (let i = 0; i < lonN; i++) {
    const a = j * (lonN + 1) + i, b = a + 1, c = a + lonN + 1, d = c + 1;
    idx.push(a, b, d, a, d, c);
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
  g.setAttribute('normal', new THREE.Float32BufferAttribute(nor, 3));
  g.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2));
  g.setIndex(idx);
  return g;
}

function rocketModel() {
  const g = new THREE.Group(), r = 0.00183;
  const white = new THREE.MeshLambertMaterial({ color: 0xf0f0f0 }), dark = new THREE.MeshLambertMaterial({ color: 0x555555 });
  const first = new THREE.Mesh(new THREE.CylinderGeometry(r, r, 0.042, 16), white);
  first.position.y = -0.014;
  const second = new THREE.Mesh(new THREE.CylinderGeometry(r, r, 0.014, 16), dark);
  second.position.y = 0.014;
  const nose = new THREE.Mesh(new THREE.ConeGeometry(r, 0.014, 16), white);
  nose.position.y = 0.028;
  const flame = new THREE.Mesh(new THREE.ConeGeometry(0.0015, 0.03, 12), new THREE.MeshBasicMaterial({ color: 0xffa030 }));
  flame.rotation.z = Math.PI;
  flame.position.y = -0.05;
  g.add(first, second, nose, flame);
  g.userData = { first, flame };
  return g;
}

function marker(color) {
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.Float32BufferAttribute([0, 0, 0], 3));
  const p = new THREE.Points(g, new THREE.PointsMaterial({ color, size: 5, sizeAttenuation: false }));
  p.frustumCulled = false;
  return p;
}

const ORBIT_POINTS = 180, TRAIL_MAX = 5000;

function lineOf(color) {
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.BufferAttribute(new Float32Array((ORBIT_POINTS + 1) * 3), 3));
  const l = new THREE.Line(g, new THREE.LineBasicMaterial({ color, transparent: true, opacity: 0.6 }));
  l.frustumCulled = false;
  return l;
}

function dispose(o) {
  o.traverse(c => { c.geometry?.dispose(); if (c.material && c.material.map !== earthTex) c.material.dispose?.(); });
  o.parent?.remove(o);
}

function rebuild() {
  const sim = S.sim;
  for (const it of S.items.values()) { for (const o of [it.group, it.marker, it.orbit, it.trail]) o && dispose(o); }
  S.items.clear();
  if (S.pad) { dispose(S.pad); S.pad = null; }
  S.list = sim.world.masses.slice();
  for (const m of S.list) {
    const it = { m, group: new THREE.Group(), marker: marker(colorOf(m)) };
    if (m.isRocket) {
      it.model = rocketModel();
      it.group.add(it.model);
    } else if (m.name === 'Earth') {
      it.rot = new THREE.Group();
      it.sphere = new THREE.Mesh(zSphere(m.radius, 256, 128), new THREE.MeshLambertMaterial({ map: earthTex }));
      it.rot.add(it.sphere);
      it.group.add(it.rot);
    } else if (m.name === 'Sun') {
      it.group.add(new THREE.Mesh(new THREE.SphereGeometry(m.radius, 48, 24), new THREE.MeshBasicMaterial({ color: 0xffd700 })));
    } else {
      it.group.add(new THREE.Mesh(new THREE.SphereGeometry(m.radius, 48, 24), new THREE.MeshLambertMaterial({ color: colorOf(m) })));
    }
    it.group.traverse(c => { c.frustumCulled = false; });
    if (m.name !== 'Sun') { it.orbit = lineOf(colorOf(m)); scene.add(it.orbit); }
    scene.add(it.group, it.marker);
    S.items.set(m, it);
  }
  for (const m of [...S.trails.keys()]) if (!S.list.includes(m)) { dispose(S.trails.get(m).points); S.trails.delete(m); }
  S.pad = rocketModel();
  S.pad.traverse(c => { c.frustumCulled = false; });
  scene.add(S.pad);
  if (!S.focus || !S.list.includes(S.focus)) S.focus = S.list[Math.max(0, S.sim.indexOf('Earth'))];
  buildTable();
}

function earthOf() { return S.sim.world.masses[S.sim.indexOf('Earth')]; }

// High-resolution launch-site imagery (Esri World Imagery tiles)
async function buildPatch(it, lat, lon) {
  const key = lat + ',' + lon;
  it.patchKey = key;
  if (it.patch) { dispose(it.patch); it.patch = null; }
  const Z = 15, n = 2 ** Z, K = 3, T = 2 * K + 1;
  const la = lat * Math.PI / 180;
  const tx = (lon + 180) / 360 * n, ty = (1 - Math.asinh(Math.tan(la)) / Math.PI) / 2 * n;
  const ix0 = Math.floor(tx) - K, iy0 = Math.floor(ty) - K;
  const cv = document.createElement('canvas');
  cv.width = cv.height = T * 256;
  const ctx = cv.getContext('2d');
  ctx.fillStyle = '#222'; ctx.fillRect(0, 0, cv.width, cv.height);
  const jobs = [];
  for (let i = 0; i < T; i++) for (let j = 0; j < T; j++) jobs.push(new Promise(res => {
    const img = new Image();
    img.crossOrigin = 'anonymous';
    img.onload = () => { ctx.drawImage(img, i * 256, j * 256); res(); };
    img.onerror = res;
    img.src = `https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/${Z}/${iy0 + j}/${ix0 + i}`;
  }));
  await Promise.all(jobs);
  if (it.patchKey !== key || !it.rot) return;
  const R = it.m.radius + 0.005;
  const dir = (X, Y) => {
    const lo = (X / n * 360 - 180) * Math.PI / 180, l = Math.atan(Math.sinh(Math.PI * (1 - 2 * Y / n)));
    return new THREE.Vector3(Math.cos(l) * Math.cos(lo), Math.cos(l) * Math.sin(lo), Math.sin(l));
  };
  const c = dir(tx, ty).multiplyScalar(R);
  const N = 48, pos = [], nor = [], uv = [], idx = [];
  for (let j = 0; j <= N; j++) for (let i = 0; i <= N; i++) {
    const d = dir(ix0 + T * i / N, iy0 + T * j / N);
    nor.push(d.x, d.y, d.z);
    const p = d.multiplyScalar(R).sub(c);
    pos.push(p.x, p.y, p.z); uv.push(i / N, 1 - j / N);
  }
  for (let j = 0; j < N; j++) for (let i = 0; i < N; i++) {
    const a = j * (N + 1) + i, b = a + 1, cc = a + N + 1, d = cc + 1;
    idx.push(a, d, b, a, cc, d);
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
  g.setAttribute('normal', new THREE.Float32BufferAttribute(nor, 3));
  g.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2));
  g.setIndex(idx);
  const tex = new THREE.CanvasTexture(cv);
  tex.colorSpace = THREE.SRGBColorSpace; tex.anisotropy = 8;
  const mesh = new THREE.Mesh(g, new THREE.MeshLambertMaterial({ map: tex, side: THREE.DoubleSide }));
  mesh.position.copy(c);
  mesh.frustumCulled = false;
  it.rot.add(mesh);
  it.patch = mesh;
}

// ----- orbital elements -----
function elements(m) {
  const w = S.sim.world, p = w.primaryOf(m);
  if (!p) return null;
  const r = m.pos.sub(p.pos), v = m.speed.sub(p.speed), d = r.mag(), mu = G * (p.mass + m.mass);
  const eps = v.dot(v) / 2 - mu / d, h = r.cross(v), ev = v.cross(h).mul(1 / mu).sub(r.mul(1 / d)), e = ev.mag();
  const el = { p, r, v, d, eps, h, ev, e, mu };
  if (eps < 0) { el.a = -mu / (2 * eps); el.rp = el.a * (1 - e); el.ra = el.a * (1 + e); }
  return el;
}

const tmp = new THREE.Vector3();
const rel = (p, o) => tmp.set(p.x - o.x, p.y - o.y, p.z - o.z);

function updateOrbit(it, origin) {
  const m = it.m;
  const show = S.orbits && !(m.isRocket && (m.crash || !m.launchBody));
  const el = show ? elements(m) : null;
  if (!el || el.eps >= 0 || el.e >= 1) { it.orbit.visible = false; return; }
  it.orbit.visible = true;
  const eh = el.e > 1e-6 ? el.ev.mul(1 / el.e) : el.r.norm(), hh = el.h.norm(), th = hh.cross(eh);
  const b = el.a * Math.sqrt(1 - el.e * el.e), arr = it.orbit.geometry.attributes.position.array;
  const cx = el.p.pos.x - origin.x, cy = el.p.pos.y - origin.y, cz = el.p.pos.z - origin.z;
  for (let k = 0; k <= ORBIT_POINTS; k++) {
    const E = 2 * Math.PI * k / ORBIT_POINTS, u = el.a * (Math.cos(E) - el.e), w = b * Math.sin(E);
    arr[k * 3] = cx + eh.x * u + th.x * w; arr[k * 3 + 1] = cy + eh.y * u + th.y * w; arr[k * 3 + 2] = cz + eh.z * u + th.z * w;
  }
  it.orbit.geometry.attributes.position.needsUpdate = true;
}

function updateTrail(it, origin) {
  const m = it.m;
  if (!m.isRocket || !m.launchBody) return;
  let tr = S.trails.get(m);
  if (!tr) {
    const g = new THREE.BufferGeometry();
    g.setAttribute('position', new THREE.BufferAttribute(new Float32Array(TRAIL_MAX * 3), 3));
    const points = new THREE.Points(g, new THREE.PointsMaterial({ color: 0xffcc66, size: 2.5, sizeAttenuation: false }));
    points.frustumCulled = false;
    scene.add(points);
    tr = { pts: [], points };
    S.trails.set(m, tr);
  }
  const p = S.sim.world.primaryOf(m) ?? m.launchBody;
  const r = m.pos.sub(p.pos), last = tr.pts[tr.pts.length - 1];
  if (!m.crash && (!last || last.p !== p || last.r.dist(r) > Math.max(0.05, 0.002 * r.mag()))) {
    tr.pts.push({ p, r });
    if (tr.pts.length > TRAIL_MAX) tr.pts.shift();
  }
  const arr = tr.points.geometry.attributes.position.array;
  tr.pts.forEach((q, i) => {
    arr[i * 3] = q.p.pos.x + q.r.x - origin.x; arr[i * 3 + 1] = q.p.pos.y + q.r.y - origin.y; arr[i * 3 + 2] = q.p.pos.z + q.r.z - origin.z;
  });
  tr.points.geometry.setDrawRange(0, tr.pts.length);
  tr.points.geometry.attributes.position.needsUpdate = true;
}

const Y = new THREE.Vector3(0, 1, 0);

function rocketDir(r) {
  if (r.direction.mag() > 0) return r.direction.norm();
  return r.pos.sub(r.launchBody?.pos ?? r.pos).norm();
}

const SITE_DISTANCE = 1.0, FOLLOW_DISTANCE = 0.2, FOLLOW_AFTER = 80;

function update() {
  const sim = S.sim, w = sim.world, spec = sim.spec, earth = earthOf();
  const rocket = w.rockets()[0];
  let origin, target = null, active = false, camPos = null, upv = null;
  if (S.launchCam && earth && spec.stages.length) {
    active = true;
    const up = siteUp(spec.latitude, spec.longitude, w.time), site = earth.pos.add(up.mul(earth.radius));
    const north = SPIN_AXIS.sub(up.mul(SPIN_AXIS.dot(up))).norm();
    const base = site.add(up.mul(0.02)).sub(north.mul(SITE_DISTANCE));
    if (rocket && rocket.launchBody && rocket.pos.dist(site) > FOLLOW_AFTER) {
      const d = rocket.speed.sub(rocket.launchBody.speed).norm();
      target = rocket.pos;
      camPos = target.sub(d.mul(FOLLOW_DISTANCE * S.camZoom));
      upv = rocket.pos.sub(rocket.launchBody.pos).norm();
    } else {
      target = rocket && rocket.launchBody ? rocket.pos : site.add(up.mul(0.035));
      camPos = target.add(base.sub(target).mul(S.camZoom));
      upv = up;
    }
    origin = camPos;
  } else {
    origin = S.focus.pos;
  }

  for (const it of S.items.values()) {
    const m = it.m;
    it.group.position.copy(rel(m.pos, origin));
    it.marker.position.copy(tmp);
    if (it.rot) {
      const era = earthRotationAngle(0) + m.rotationAngle;
      const ex = equatorialToEcliptic(new V(Math.cos(era), Math.sin(era), 0)), ey = equatorialToEcliptic(new V(-Math.sin(era), Math.cos(era), 0));
      const mat = new THREE.Matrix4().makeBasis(new THREE.Vector3(ex.x, ex.y, ex.z), new THREE.Vector3(ey.x, ey.y, ey.z), new THREE.Vector3(SPIN_AXIS.x, SPIN_AXIS.y, SPIN_AXIS.z));
      it.rot.quaternion.setFromRotationMatrix(mat);
      const key = spec.latitude + ',' + spec.longitude;
      if (it.patchKey !== key && spec.stages.length) buildPatch(it, spec.latitude, spec.longitude).catch(() => {});
    }
    if (it.model) {
      const d = rocketDir(m);
      const off = rel(m.pos, origin).clone();
      it.model.quaternion.setFromUnitVectors(Y, new THREE.Vector3(d.x, d.y, d.z));
      it.model.position.set(off.x + d.x * 0.035, off.y + d.y * 0.035, off.z + d.z * 0.035);
      it.group.position.set(0, 0, 0);
      it.model.userData.first.visible = m.stageIndex === 0;
      it.model.userData.flame.visible = !!m.firing;
      it.model.userData.flame.position.y = m.stageIndex === 0 ? -0.05 : -0.022;
      it.marker.position.set(off.x, off.y, off.z);
    }
    if (it.orbit) updateOrbit(it, origin);
    updateTrail(it, origin);
  }

  // rocket on the pad before launch
  const showPad = !rocket && spec.stages.length && earth;
  S.pad.visible = !!showPad;
  if (showPad) {
    const up = siteUp(spec.latitude, spec.longitude, w.time);
    const base = earth.pos.add(up.mul(earth.radius));
    rel(base, origin);
    S.pad.position.set(tmp.x + up.x * 0.035, tmp.y + up.y * 0.035, tmp.z + up.z * 0.035);
    S.pad.quaternion.setFromUnitVectors(Y, tmp.set(up.x, up.y, up.z));
    S.pad.userData.first.visible = true;
    S.pad.userData.flame.visible = false;
  }

  const sun = w.masses[0];
  sunLight.position.copy(rel(sun.pos, origin));
  let camera;
  if (active) {
    camera = lcam;
    lcam.position.set(0, 0, 0);
    lcam.up.set(upv.x, upv.y, upv.z);
    lcam.lookAt(target.x - origin.x, target.y - origin.y, target.z - origin.z);
    S.viewDist = target.dist(origin);
  } else {
    camera = cam;
    const f = S.focus;
    controls.minDistance = f.isRocket ? 0.01 : f.radius * 1.02;
    controls.enabled = true;
    S.viewDist = cam.position.length();
  }
  controls.enabled = !active;
  stars.position.copy(camera.position);
  camera.updateMatrixWorld();
  renderer.render(scene, camera);
  S.camera = camera;
}

function setFocus(m) {
  S.focus = m; S.launchCam = false;
  const dir = cam.position.lengthSq() > 0 ? cam.position.clone().normalize() : new THREE.Vector3(1, -1, 0.6).normalize();
  cam.position.copy(dir.multiplyScalar(m.isRocket ? 0.3 : m.radius * 4));
  cam.up.set(0, 0, 1);
  controls.target.set(0, 0, 0);
  controls.update();
  buildTable();
  refreshButtons();
}

function zoomBy(f) {
  if (S.launchCam) S.camZoom = Math.min(5000, Math.max(0.01, S.camZoom * f));
  else { cam.position.multiplyScalar(f); controls.update(); }
}

// ----- HUD -----
const fmtDist = km => {
  const a = Math.abs(km);
  if (a < 1) return (km * 1000).toFixed(0) + ' m';
  if (a < 1e5) return km.toFixed(a < 100 ? 1 : 0) + ' km';
  return (km / 1e6).toFixed(2) + ' Mkm';
};
const hms = s => {
  const a = Math.abs(Math.floor(s)), h = Math.floor(a / 3600), m = Math.floor(a % 3600 / 60), x = a % 60;
  return `${h}:${String(m).padStart(2, '0')}:${String(x).padStart(2, '0')}`;
};

function buildTable() {
  const sim = S.sim;
  let html = '<table><thead><tr><th>Body</th><th>km/s</th><th>min</th><th>max</th></tr></thead><tbody>';
  S.list.forEach((m, i) => { html += `<tr data-i="${i}" class="${m === S.focus ? 'sel' : ''}"><td>${i < 10 ? i + ' ' : ''}${m.name}</td><td></td><td></td><td></td></tr>`; });
  $('bodies').innerHTML = html + '</tbody></table>';
  $('bodies').style.display = S.table ? '' : 'none';
  $('bodies').querySelectorAll('tbody tr').forEach(tr => tr.onclick = () => setFocus(S.list[+tr.dataset.i]));
}

function updateTable() {
  const rows = $('bodies').querySelectorAll('tbody tr');
  S.list.forEach((m, i) => {
    const el = elements(m), c = rows[i]?.children;
    if (!c) return;
    c[1].textContent = el ? el.v.mag().toFixed(2) : '';
    c[2].textContent = el && el.rp !== undefined ? fmtDist(el.rp) : '';
    c[3].textContent = el && el.ra !== undefined ? fmtDist(el.ra) : '';
  });
}

function updateInfo() {
  const sim = S.sim, w = sim.world, spec = sim.spec, rocket = w.rockets()[0];
  const date = new Date((EPOCH_SECONDS + w.time) * 1000).toISOString().replace('T', ' ').slice(0, 19) + ' UTC';
  let line = '';
  if (rocket) line = `T+${hms(w.time - rocket.launchTime)}  ${rocket.stage()}`;
  else if (sim.launchPending) line = `T-${hms(spec.launchSeconds - w.time)}  ${spec.title}`;
  $('info').innerHTML = `<div class="big">${date}</div><div>${line}</div><div>${S.focus?.name ?? ''}${S.launchCam ? ' (launch cam)' : ''}</div>`;
  $('hint').textContent = sim.pause ? 'Paused - press \u25B6 or Space' : '';
  $('speedText').textContent = `Time \u00D7${Math.round(sim.simSpeed).toLocaleString()}${sim.autoScale ? ' (auto)' : ''}`;
  $('speedFill').style.width = Math.max(0, Math.min(100, Math.log10(Math.max(1, sim.simSpeed)) / Math.log10(MAX_SIM_SPEED) * 100)) + '%';
  const width = 2 * S.viewDist * Math.tan(THREE.MathUtils.degToRad(22.5)) * (innerWidth / innerHeight);
  const barKm = Math.pow(10, Math.floor(Math.log10(width / 2)));
  $('zoomText').textContent = `View ${fmtDist(barKm)}`;
  $('zoombar').style.width = Math.min(160, barKm / width * innerWidth) + 'px';
}

function drawTimeline() {
  const sim = S.sim, spec = sim.spec, c = $('timeline'), g = c.getContext('2d');
  g.clearRect(0, 0, c.width, c.height);
  if (!spec.steps.length) return;
  const total = spec.steps[spec.steps.length - 1].end, rocket = sim.world.rockets()[0];
  const t = rocket ? sim.world.time - rocket.launchTime : 0;
  g.font = '10px sans-serif';
  let s = 0;
  spec.steps.forEach((st, i) => {
    const x0 = s / total * c.width, x1 = st.end / total * c.width;
    g.fillStyle = i % 2 ? 'rgba(80,120,200,.55)' : 'rgba(60,90,160,.55)';
    g.fillRect(x0, 8, x1 - x0, 20);
    if (x1 - x0 > 28) { g.fillStyle = '#fff'; g.fillText(st.name.slice(0, Math.floor((x1 - x0) / 6)), x0 + 2, 22); }
    s = st.end;
  });
  const x = Math.min(1, Math.max(0, t / total)) * c.width;
  g.fillStyle = '#ff5'; g.fillRect(x - 1, 4, 2, 28);
  g.fillStyle = '#fff'; g.fillText(`T+${Math.max(0, Math.floor(t))}s / ${Math.floor(total)}s`, 4, 38);
}

function refreshButtons() {
  const sim = S.sim;
  $('bPlay').innerHTML = sim.pause ? '&#9654;' : '&#10074;&#10074;';
  $('bAuto').classList.toggle('on', sim.autoScale);
  $('bCam').classList.toggle('on', S.launchCam);
  $('bOrbits').classList.toggle('on', S.orbits);
  $('bTable').classList.toggle('on', S.table);
  document.querySelectorAll('#bodies tbody tr').forEach((tr, i) => tr.classList.toggle('sel', S.list[i] === S.focus));
}

function togglePause() { S.sim.pause = !S.sim.pause; refreshButtons(); }
function restart() { S.sim.restart(); S.launchCam = true; S.camZoom = 1; S.trails.forEach(t => { t.pts.length = 0; }); refreshButtons(); }
function toggleCam() {
  if (S.launchCam) { setFocus(S.focus); } else { S.launchCam = true; refreshButtons(); }
}
function cycleFocus(d) {
  const i = S.list.indexOf(S.focus);
  setFocus(S.list[(i + d + S.list.length) % S.list.length]);
}

function save() {
  const text = writeConfig(S.sim.world, S.sim.spec);
  const a = document.createElement('a');
  a.href = URL.createObjectURL(new Blob([text], { type: 'application/json' }));
  a.download = 'gravity.gravity';
  a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 2000);
}

async function load(file) {
  try {
    S.sim.load(await file.text());
    S.launchCam = true; S.camZoom = 1;
    refreshButtons();
  } catch (e) { alert('Could not load: ' + e.message); }
}

async function main() {
  const text = await (await fetch('default.json')).text();
  const sim = new Sim(text);
  S.sim = sim;
  sim.onChange = () => { rebuild(); refreshButtons(); };
  sim.onChange();
  S.focus = S.list[Math.max(0, sim.indexOf('Earth'))];

  $('bPlay').onclick = togglePause;
  $('bRestart').onclick = restart;
  $('bLaunch').onclick = () => { if (!sim.world.rockets().length) { sim.launchNow(); sim.pause = false; refreshButtons(); } };
  $('bAuto').onclick = () => { sim.autoScale = !sim.autoScale; refreshButtons(); };
  $('bCam').onclick = toggleCam;
  $('bOrbits').onclick = () => { S.orbits = !S.orbits; refreshButtons(); };
  $('bTable').onclick = () => { S.table = !S.table; $('bodies').style.display = S.table ? '' : 'none'; refreshButtons(); };
  $('bPrev').onclick = () => cycleFocus(-1);
  $('bNext').onclick = () => cycleFocus(1);
  $('bZoomIn').onclick = () => zoomBy(0.7);
  $('bZoomOut').onclick = () => zoomBy(1 / 0.7);
  $('bSave').onclick = save;
  $('bLoad').onclick = () => $('file').click();
  $('file').onchange = e => { if (e.target.files[0]) load(e.target.files[0]); e.target.value = ''; };
  setupEditors(sim, { $, refresh: refreshButtons });

  addEventListener('keydown', e => {
    if (document.querySelector('dialog[open]') || e.target.tagName === 'INPUT' || e.ctrlKey || e.metaKey) return;
    const k = e.key.toLowerCase();
    if (k === ' ') togglePause();
    else if (k === 'r') restart();
    else if (k === 'l') $('bLaunch').click();
    else if (k === 'c') toggleCam();
    else if (k === 'o') $('bOrbits').click();
    else if (k === 't') $('bTable').click();
    else if (k === 'a') $('bAuto').click();
    else if (k === '[') cycleFocus(-1);
    else if (k === ']') cycleFocus(1);
    else if (k === '+' || k === '=') zoomBy(0.7);
    else if (k === '-') zoomBy(1 / 0.7);
    else if (/^[0-9]$/.test(k) && S.list[+k]) setFocus(S.list[+k]);
    else return;
    e.preventDefault();
  });

  // zoom control while the launch camera drives the view
  canvas.addEventListener('wheel', e => { if (S.launchCam) { e.preventDefault(); zoomBy(Math.exp(e.deltaY * 0.001)); } }, { passive: false });
  const ptrs = new Map();
  let pinch = 0;
  canvas.addEventListener('pointerdown', e => ptrs.set(e.pointerId, e));
  canvas.addEventListener('pointerup', e => { ptrs.delete(e.pointerId); pinch = 0; });
  canvas.addEventListener('pointercancel', e => { ptrs.delete(e.pointerId); pinch = 0; });
  canvas.addEventListener('pointermove', e => {
    if (!ptrs.has(e.pointerId)) return;
    ptrs.set(e.pointerId, e);
    if (S.launchCam && ptrs.size === 2) {
      const [a, b] = [...ptrs.values()], d = Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY);
      if (pinch) zoomBy(pinch / d);
      pinch = d;
    }
  });

  const resize = () => {
    renderer.setSize(innerWidth, innerHeight, false);
    for (const c of [cam, lcam]) { c.aspect = innerWidth / innerHeight; c.updateProjectionMatrix(); }
  };
  addEventListener('resize', resize);
  resize();
  refreshButtons();

  let last = performance.now();
  const loop = now => {
    const dt = Math.min(0.5, (now - last) / 1000);
    last = now;
    sim.frame(dt);
    if (sim.world.masses.length !== S.list.length || sim.world.masses.some((m, i) => m !== S.list[i])) { rebuild(); refreshButtons(); }
    update();
    if (S.frameNo++ % 6 === 0) { updateInfo(); drawTimeline(); updateTable(); }
    requestAnimationFrame(loop);
  };
  requestAnimationFrame(loop);
}

main();
if ('serviceWorker' in navigator && location.protocol.startsWith('http')) navigator.serviceWorker.register('sw.js').catch(() => {});
