import { Mass, V, RocketSpec } from './sim.js';

const el = (tag, props = {}, ...kids) => {
  const e = Object.assign(document.createElement(tag), props);
  for (const k of kids) e.append(k);
  return e;
};

const num = (v, label) => {
  const x = Number(String(v).trim());
  if (String(v).trim() === '' || !Number.isFinite(x)) throw new Error(`${label} must be a number.`);
  return x;
};

/** Editable table. cols: {key, label, type: 'text'|'num'|'bool'}. */
function tableEditor(cols, rows, blank) {
  const table = el('table'), body = el('tbody');
  table.append(el('thead', {}, el('tr', {}, ...cols.map(c => el('th', { textContent: c.label })), el('th'))), body);
  const add = row => {
    const tr = el('tr');
    for (const c of cols) {
      const input = el('input', c.type === 'bool' ? { type: 'checkbox', checked: !!row[c.key] } : { type: 'text', value: row[c.key] ?? '', className: c.type === 'text' ? 'wide' : '' });
      input.dataset.key = c.key;
      tr.append(el('td', {}, input));
    }
    tr.append(el('td', {}, el('button', { type: 'button', textContent: '\u2715', onclick: () => tr.remove() })));
    body.append(tr);
  };
  rows.forEach(add);
  const wrap = el('div', {}, table, el('button', { type: 'button', textContent: '+ Add', onclick: () => add(blank()) }));
  const read = what => [...body.rows].map((tr, i) => {
    const o = {};
    for (const c of cols) {
      const v = tr.querySelector(`[data-key="${c.key}"]`);
      o[c.key] = c.type === 'bool' ? v.checked : c.type === 'num' ? num(v.value, `${what} ${i + 1} ${c.label}`) : v.value;
    }
    return o;
  });
  return { el: wrap, read };
}

export function setupEditors(sim, { $, refresh }) {
  const dlg = $('dlg');
  function show(title, content, onOk) {
    $('dlgTitle').textContent = title;
    $('dlgBody').replaceChildren(content);
    $('dlgOk').onclick = () => { try { onOk(); dlg.close(); } catch (e) { alert(e.message); } };
    $('dlgCancel').onclick = () => dlg.close();
    dlg.showModal();
  }

  $('bMasses').onclick = () => {
    const cols = [
      { key: 'name', label: 'Name', type: 'text' }, { key: 'diameter', label: 'Diameter km', type: 'num' }, { key: 'mass', label: 'Mass kg', type: 'num' },
      { key: 'px', label: 'x km', type: 'num' }, { key: 'py', label: 'y km', type: 'num' }, { key: 'pz', label: 'z km', type: 'num' },
      { key: 'vx', label: 'vx km/s', type: 'num' }, { key: 'vy', label: 'vy km/s', type: 'num' }, { key: 'vz', label: 'vz km/s', type: 'num' },
      { key: 'spin', label: 'Spin rad/s', type: 'num' },
    ];
    const old = sim.world.masses.filter(m => !m.isRocket);
    const rows = old.map(m => ({ name: m.name, diameter: m.radius * 2, mass: m.mass, px: m.pos.x, py: m.pos.y, pz: m.pos.z, vx: m.speed.x, vy: m.speed.y, vz: m.speed.z, spin: m.rotationSpeed }));
    const ed = tableEditor(cols, rows, () => ({ name: 'New', diameter: 1, mass: 1, px: 0, py: 0, pz: 0, vx: 0, vy: 0, vz: 0, spin: 0 }));
    const box = el('div', { style: 'overflow:auto;max-height:65vh' }, ed.el);
    show('Masses (km, kg, s) - applying restarts from these values', box, () => {
      const list = ed.read('Mass').map(r => {
        if (!r.name.trim()) throw new Error('Every mass needs a name.');
        if (r.diameter <= 0 || r.mass <= 0) throw new Error(`${r.name}: diameter and mass must be positive.`);
        const m = new Mass(new V(r.px, r.py, r.pz), r.diameter, r.mass, new V(r.vx, r.vy, r.vz), r.spin, r.name.trim());
        m.rotationAngle = old.find(o => o.name === m.name)?.rotationAngle ?? 0;
        return m;
      });
      if (!list.length) throw new Error('Need at least one mass.');
      sim.setMasses(list);
      sim.restart();
      refresh();
    });
  };

  $('bRocket').onclick = () => {
    const s = sim.spec.copy();
    const fields = [
      ['title', 'Title', 'text'], ['name', 'Name', 'text'], ['payload', 'Payload kg', 'num'], ['azimuth', 'Azimuth deg', 'num'],
      ['latitude', 'Latitude', 'num'], ['longitude', 'Longitude', 'num'], ['captureBody', 'Capture body', 'text'], ['captureApoapsis', 'Capture apoapsis km', 'num'],
      ['launchSeconds', 'Launch time (s from start)', 'num'],
    ];
    const form = el('div', { className: 'form' }), inputs = {};
    for (const [k, label] of fields) {
      inputs[k] = el('input', { type: 'text', value: s[k], className: 'wide' });
      form.append(el('label', { textContent: label }), inputs[k], el('span'));
      if (k === 'longitude') {
        const pick = el('button', { type: 'button', textContent: 'Select location\u2026' });
        pick.onclick = async () => {
          const r = await pickLocation(Number(inputs.latitude.value) || 0, Number(inputs.longitude.value) || 0);
          if (r) { inputs.latitude.value = r.lat.toFixed(4); inputs.longitude.value = r.lon.toFixed(4); }
        };
        form.lastChild.replaceWith(pick);
      }
    }
    const stages = tableEditor([
      { key: 'name', label: 'Stage', type: 'text' }, { key: 'dry', label: 'Dry kg', type: 'num' }, { key: 'fuel', label: 'Fuel kg', type: 'num' },
      { key: 'thrust', label: 'Thrust kN', type: 'num' }, { key: 'exhaust', label: 'Exhaust km/s', type: 'num' },
    ], s.stages, () => ({ name: 'Stage', dry: 1000, fuel: 10000, thrust: 1000, exhaust: 3 }));
    const steps = tableEditor([
      { key: 'name', label: 'Step', type: 'text' }, { key: 'end', label: 'End s', type: 'num' }, { key: 'power', label: 'Power 0-1', type: 'num' },
      { key: 'pitch', label: 'Pitch deg', type: 'num' }, { key: 'yaw', label: 'Yaw deg', type: 'num' }, { key: 'relative', label: 'Rel', type: 'bool' },
    ], s.steps, () => ({ name: 'Step', end: 100, power: 1, pitch: 0, yaw: 0, relative: false }));
    const box = el('div', { style: 'overflow:auto;max-height:70vh' }, form, el('b', { textContent: 'Stages' }), stages.el, el('b', { textContent: 'Flight steps (end = seconds after launch)' }), steps.el);
    show('Rocket and flight plan - applying restarts the simulation', box, () => {
      const o = {};
      for (const [k, label, t] of fields) o[k] = t === 'num' ? num(inputs[k].value, label) : inputs[k].value.trim();
      o.stages = stages.read('Stage').map(r => ({ ...r, dryMass: r.dry, exhaustSpeed: r.exhaust }));
      o.steps = steps.read('Step');
      const spec = new RocketSpec(o), problem = spec.validate();
      if (problem) throw new Error(problem);
      sim.setSpec(spec);
      sim.restart();
      refresh();
    });
  };

  // ----- world map location picker -----
  const img = new Image();
  img.src = 'images/earth.jpg';
  const mapDlg = $('mapDlg'), mc = $('mapCanvas');

  function pickLocation(lat0, lon0) {
    return new Promise(resolve => {
      const view = { cx: lon0, cy: lat0, scale: 2 };
      let sel = { lat: lat0, lon: lon0 };
      mapDlg.showModal();
      const W = () => mc.width, H = () => mc.height;
      const fit = () => { mc.width = mc.clientWidth; mc.height = mc.clientHeight; };
      const clamp = () => {
        view.scale = Math.min(200, Math.max(Math.max(W() / 360, H() / 180), view.scale));
        const hh = H() / 2 / view.scale;
        view.cy = Math.max(-90 + hh, Math.min(90 - hh, view.cy));
        view.cx = ((view.cx + 180) % 360 + 360) % 360 - 180;
      };
      const X = lon => W() / 2 + (lon - view.cx) * view.scale, Yp = lat => H() / 2 - (lat - view.cy) * view.scale;
      const draw = () => {
        const g = mc.getContext('2d');
        g.fillStyle = '#123'; g.fillRect(0, 0, W(), H());
        if (img.complete && img.naturalWidth) for (const k of [-360, 0, 360]) g.drawImage(img, X(-180 + k), Yp(90), 360 * view.scale, 180 * view.scale);
        for (const k of [-360, 0, 360]) {
          const x = X(sel.lon + k), y = Yp(sel.lat);
          g.strokeStyle = '#ff4'; g.lineWidth = 2; g.beginPath(); g.arc(x, y, 8, 0, 7); g.moveTo(x - 14, y); g.lineTo(x + 14, y); g.moveTo(x, y - 14); g.lineTo(x, y + 14); g.stroke();
        }
        $('mapText').textContent = `Latitude ${sel.lat.toFixed(4)}, longitude ${sel.lon.toFixed(4)} - drag to pan, scroll or pinch to zoom, tap to select`;
      };
      const redraw = () => { clamp(); draw(); };
      img.onload = redraw;
      const toGeo = (px, py) => ({ lon: view.cx + (px - W() / 2) / view.scale, lat: view.cy - (py - H() / 2) / view.scale });
      const zoomAt = (px, py, f) => {
        const g = toGeo(px, py);
        view.scale *= f; clamp();
        view.cx = g.lon - (px - W() / 2) / view.scale; view.cy = g.lat + (py - H() / 2) / view.scale;
        redraw();
      };
      const ptrs = new Map();
      let moved = 0, pinch = 0;
      mc.onpointerdown = e => { mc.setPointerCapture(e.pointerId); ptrs.set(e.pointerId, e); moved = 0; pinch = 0; };
      mc.onpointermove = e => {
        const prev = ptrs.get(e.pointerId);
        if (!prev) return;
        ptrs.set(e.pointerId, e);
        const r = mc.getBoundingClientRect(), sx = mc.width / r.width;
        if (ptrs.size === 2) {
          const [a, b] = [...ptrs.values()], d = Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY);
          if (pinch) zoomAt(((a.clientX + b.clientX) / 2 - r.left) * sx, ((a.clientY + b.clientY) / 2 - r.top) * sx, d / pinch);
          pinch = d; moved = 99;
        } else {
          const dx = e.clientX - prev.clientX, dy = e.clientY - prev.clientY;
          moved += Math.abs(dx) + Math.abs(dy);
          view.cx -= dx * sx / view.scale; view.cy += dy * sx / view.scale;
          redraw();
        }
      };
      mc.onpointerup = e => {
        if (ptrs.has(e.pointerId) && ptrs.size === 1 && moved < 6) {
          const r = mc.getBoundingClientRect(), sx = mc.width / r.width, g = toGeo((e.clientX - r.left) * sx, (e.clientY - r.top) * sx);
          sel = { lat: Math.max(-90, Math.min(90, g.lat)), lon: ((g.lon + 180) % 360 + 360) % 360 - 180 };
          draw();
        }
        ptrs.delete(e.pointerId); pinch = 0;
      };
      mc.onpointercancel = e => { ptrs.delete(e.pointerId); pinch = 0; };
      mc.onwheel = e => {
        e.preventDefault();
        const r = mc.getBoundingClientRect(), sx = mc.width / r.width;
        zoomAt((e.clientX - r.left) * sx, (e.clientY - r.top) * sx, Math.exp(-e.deltaY * 0.0015));
      };
      let done = false;
      const finish = v => { if (done) return; done = true; mapDlg.close(); resolve(v); };
      $('mapOk').onclick = () => finish(sel);
      $('mapCancel').onclick = () => finish(null);
      mapDlg.onclose = () => finish(null);
      fit();
      view.scale = Math.max(W() / 360, H() / 180) * 2;
      redraw();
    });
  }
}
