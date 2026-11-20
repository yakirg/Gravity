// Physics of the gravity simulation. Units: km, kg, s, kN. Ecliptic frame (z = ecliptic north).
export const G = 6.67e-20;
export const EPOCH_SECONDS = Date.UTC(2026, 9, 18) / 1000;
const OBLIQUITY = 23.4393 * Math.PI / 180;

export class V {
  constructor(x = 0, y = 0, z = 0) { this.x = x; this.y = y; this.z = z; }
  add(o) { return new V(this.x + o.x, this.y + o.y, this.z + o.z); }
  sub(o) { return new V(this.x - o.x, this.y - o.y, this.z - o.z); }
  mul(k) { return new V(this.x * k, this.y * k, this.z * k); }
  dot(o) { return this.x * o.x + this.y * o.y + this.z * o.z; }
  cross(o) { return new V(this.y * o.z - this.z * o.y, this.z * o.x - this.x * o.z, this.x * o.y - this.y * o.x); }
  mag() { return Math.hypot(this.x, this.y, this.z); }
  norm() { const m = this.mag(); return m > 0 ? this.mul(1 / m) : new V(); }
  dist(o) { return Math.hypot(this.x - o.x, this.y - o.y, this.z - o.z); }
  angleDeg(o) {
    const d = this.mag() * o.mag();
    return d === 0 ? 0 : Math.acos(Math.max(-1, Math.min(1, this.dot(o) / d))) * 180 / Math.PI;
  }
}

export const SPIN_AXIS = new V(0, Math.sin(OBLIQUITY), Math.cos(OBLIQUITY));

export function equatorialToEcliptic(p) {
  return new V(p.x, p.y * Math.cos(OBLIQUITY) + p.z * Math.sin(OBLIQUITY), -p.y * Math.sin(OBLIQUITY) + p.z * Math.cos(OBLIQUITY));
}

export function earthRotationAngle(seconds) {
  const days = (EPOCH_SECONDS - 946728000 + seconds) / 86400;
  const turns = 0.7790572732640 + 1.00273781191135448 * days;
  return 2 * Math.PI * (turns - Math.floor(turns));
}

export function siteUp(lat, lon, t) {
  const la = lat * Math.PI / 180, th = earthRotationAngle(t) + lon * Math.PI / 180;
  return equatorialToEcliptic(new V(Math.cos(la) * Math.cos(th), Math.cos(la) * Math.sin(th), Math.sin(la))).norm();
}

export class Mass {
  constructor(pos, diameter, mass, speed, rotationSpeed, name) {
    this.pos = pos; this.radius = diameter / 2; this.mass = mass; this.speed = speed;
    this.rotationSpeed = rotationSpeed; this.name = name; this.rotationAngle = 0;
  }
  get isRocket() { return false; }
  accelerate(t, list, delta) {
    for (const o of list) {
      if (o === this) continue;
      const dx = o.pos.x - this.pos.x, dy = o.pos.y - this.pos.y, dz = o.pos.z - this.pos.z;
      const d2 = dx * dx + dy * dy + dz * dz, d = Math.sqrt(d2);
      const k = G * o.mass / d2 / d * delta;
      this.speed = new V(this.speed.x + dx * k, this.speed.y + dy * k, this.speed.z + dz * k);
    }
  }
  move(delta) {
    this.pos = new V(this.pos.x + this.speed.x * delta, this.pos.y + this.speed.y * delta, this.pos.z + this.speed.z * delta);
    this.rotationAngle = (this.rotationAngle + this.rotationSpeed * delta) % (2 * Math.PI);
  }
}

export class Rocket extends Mass {
  constructor(spec, launchFrom) {
    super(new V(), 0.01, spec.payload, new V(), 0, spec.name);
    this.launchFrom = launchFrom;
    this.stages = spec.stages.map(s => ({ ...s, consumption: s.thrust / s.exhaust }));
    this.steps = spec.steps.map(s => ({ ...s }));
    this.launchLatitude = spec.latitude; this.launchLongitude = spec.longitude; this.launchAzimuth = spec.azimuth;
    this.captureBody = spec.captureBody; this.captureIndex = -1;
    this.captureApoapsis = spec.captureApoapsis;
    this.j2 = 1.08263e-3;
    this.direction = new V(); this.launchTime = 0; this.crash = false; this.firing = false;
    this.step = 0; this.captureState = 0; this.stageIndex = 0; this.launchBody = null;
    this.fuelLevel = 0; this.thrust = 0; this.consumption = 0;
    this.sStart = 0; this.sPitch = 0; this.sYaw = 0; this.sRel = false; this.lastRadial = 0;
    this.all = null;
  }
  get isRocket() { return true; }

  launch(t, list) {
    this.launchBody = list[this.launchFrom];
    const up = siteUp(this.launchLatitude, this.launchLongitude, t);
    const off = up.mul(this.launchBody.radius);
    this.pos = this.launchBody.pos.add(off);
    this.speed = this.launchBody.speed.add(SPIN_AXIS.cross(off).mul(this.launchBody.rotationSpeed));
    this.step = 0; this.sStart = 0; this.sPitch = 0; this.sYaw = 0; this.sRel = false;
    this.crash = false; this.captureState = 0; this.lastRadial = 0; this.launchTime = t;
    this.stageIndex = 0;
    for (const s of this.stages) this.mass += s.dry;
    for (let i = 1; i < this.stages.length; i++) this.mass += this.stages[i].fuel;
    this.loadStage();
  }
  loadStage() {
    const s = this.stages[this.stageIndex];
    this.fuelLevel = s.fuel; this.thrust = s.thrust; this.consumption = s.consumption;
  }
  separate() {
    this.mass -= this.stages[this.stageIndex].dry;
    this.stageIndex++;
    this.mass -= this.stages[this.stageIndex].fuel;
    this.loadStage();
  }
  guidance(t, step) {
    const up = this.pos.sub(this.launchBody.pos).norm();
    const span = step.end - this.sStart;
    const f = span > 0 ? Math.min(1, (t - this.sStart) / span) : 1;
    const rad = Math.PI / 180;
    const pitch = (this.sPitch + (step.pitch - this.sPitch) * f) * rad;
    const yaw = (step.relative === this.sRel ? this.sYaw + (step.yaw - this.sYaw) * f : step.yaw) * rad;
    const north = SPIN_AXIS.sub(up.mul(SPIN_AXIS.dot(up))).norm();
    const east = SPIN_AXIS.cross(up).norm();
    let heading = null;
    if (step.relative) {
      const v = this.speed.sub(this.launchBody.speed);
      const h = v.sub(up.mul(v.dot(up)));
      if (h.mag() > 1e-9) {
        const h0 = h.norm();
        heading = h0.mul(Math.cos(yaw)).add(up.cross(h0).mul(Math.sin(yaw)));
      }
    }
    if (!heading) {
      const az = this.launchAzimuth * rad + yaw;
      heading = north.mul(Math.cos(az)).add(east.mul(Math.sin(az)));
    }
    return up.mul(Math.cos(pitch)).add(heading.mul(Math.sin(pitch)));
  }
  oblateness() {
    const r = this.pos.sub(this.launchBody.pos), d = r.mag(), rh = r.mul(1 / d), s = rh.dot(SPIN_AXIS);
    const k = 1.5 * this.j2 * G * this.launchBody.mass * this.launchBody.radius ** 2 / d ** 4;
    return rh.mul(k * (5 * s * s - 1)).sub(SPIN_AXIS.mul(2 * k * s));
  }
  stage() {
    let p = this.phase();
    if (this.stages.length > 1 && p && !this.crash) p += `  [${this.stages[this.stageIndex].name}]`;
    return p;
  }
  phase() {
    if (!this.launchBody) return '';
    if (this.crash) return 'Crashed';
    if (this.step < this.steps.length) return this.steps[this.step].name || `Step ${this.step}`;
    if (this.captureIndex < 0) return 'Coast';
    return ['Translunar coast', 'Lunar orbit insertion', 'Lunar orbit', 'Circularization burn', 'Circular lunar orbit'][this.captureState];
  }
  accelerate(t, list, delta) {
    if (!this.launchBody) return;
    this.firing = false;
    if (this.stages.length && this.fuelLevel <= 0 && this.stageIndex + 1 < this.stages.length) this.separate();
    if (this.crash) { this.speed = this.launchBody.speed; return; }
    const lt = t - this.launchTime;
    if (this.step < this.steps.length) {
      const step = this.steps[this.step];
      this.direction = this.guidance(lt, step);
      if (this.fuelLevel > 0 && step.power > 0) {
        this.firing = true;
        const acc = this.thrust * step.power / (this.mass + this.fuelLevel);
        this.speed = this.speed.add(this.direction.mul(acc * delta));
        this.fuelLevel = Math.max(0, this.fuelLevel - this.consumption * step.power * delta);
      }
      if (lt > step.end) {
        this.sStart = step.end; this.sPitch = step.pitch; this.sYaw = step.yaw; this.sRel = step.relative;
        this.step++;
      }
    }
    if (this.j2 > 0) this.speed = this.speed.add(this.oblateness().mul(delta));
    if (this.step >= this.steps.length && this.captureIndex >= 0 && this.captureIndex < list.length)
      this.capture(list[this.captureIndex], delta);
    super.accelerate(t, list, delta);
    this.all = list;
    if (this.pos.dist(this.launchBody.pos) < this.launchBody.radius - 0.01) this.crash = true;
  }
  timeToBurn(t) {
    if (this.crash || !this.launchBody) return Infinity;
    if (this.firing) return 0;
    if (this.step >= this.steps.length && this.captureState === 2 && this.all && this.captureIndex >= 0) {
      const b = this.all[this.captureIndex];
      const r = this.pos.sub(b.pos), v = this.speed.sub(b.speed), d = r.mag(), rv = r.dot(v);
      const decel = G * (b.mass + this.mass) / d - v.dot(v);
      return rv <= 0 ? 0 : decel > 0 ? rv / decel : Infinity;
    }
    const lt = t - this.launchTime;
    for (let i = this.step; i < this.steps.length; i++)
      if (this.steps[i].power > 0) return Math.max(0, (i === 0 ? 0 : this.steps[i - 1].end) - lt);
    return Infinity;
  }
  timescale(p) {
    const r = this.pos.dist(p.pos);
    return Math.sqrt(r * r * r / (G * (p.mass + this.mass)));
  }
  capture(body, delta) {
    if (this.captureState === 4) return;
    const r = this.pos.sub(body.pos), v = this.speed.sub(body.speed), d = r.mag();
    if (this.captureState >= 2) { this.circularize(body, r, v, d, delta); return; }
    if (this.captureState === 0) {
      if (d < 30000 && r.dot(v) >= 0) this.captureState = 1; else return;
    }
    const mu = G * (body.mass + this.mass), eps = v.dot(v) / 2 - mu / d;
    if (eps < 0) {
      const a = -mu / (2 * eps), h = r.cross(v);
      const e = Math.sqrt(Math.max(0, 1 + 2 * eps * h.dot(h) / (mu * mu)));
      if (a * (1 + e) <= this.captureApoapsis || this.fuelLevel <= 0) { this.captureState = 2; return; }
    }
    if (this.fuelLevel <= 0) { this.captureState = 2; return; }
    this.burn(v.norm().mul(-1), delta);
  }
  circularize(body, r, v, d, delta) {
    const mu = G * (body.mass + this.mass);
    if (this.captureState === 2) {
      if (this.fuelLevel <= 0) { this.captureState = 4; return; }
      if (this.lastRadial > 0 && r.dot(v) <= 0) this.captureState = 3;
      this.lastRadial = r.dot(v);
      if (this.captureState === 2) return;
    }
    const eps = v.dot(v) / 2 - mu / d, h = r.cross(v);
    const e = Math.sqrt(Math.max(0, 1 + 2 * eps * h.dot(h) / (mu * mu)));
    if (eps >= 0 || e <= 0.02 || this.fuelLevel <= 0) { this.captureState = 4; return; }
    this.burn(v.norm(), delta);
  }
  burn(dir, delta) {
    this.direction = dir;
    this.speed = this.speed.add(dir.mul(this.thrust / (this.mass + this.fuelLevel) * delta));
    this.fuelLevel = Math.max(0, this.fuelLevel - this.consumption * delta);
    this.firing = true;
  }
  move(delta) { if (this.launchBody) super.move(delta); }
}

export class RocketSpec {
  constructor(o = {}) {
    this.title = o.title ?? 'Rocket'; this.name = o.name ?? 'Rocket'; this.payload = o.payload ?? 1000;
    this.azimuth = o.azimuth ?? 90; this.latitude = o.latitude ?? 0; this.longitude = o.longitude ?? 0;
    this.captureBody = o.captureBody ?? ''; this.captureApoapsis = o.captureApoapsis ?? 20000;
    this.launchSeconds = o.launchSeconds ?? 0;
    this.stages = (o.stages ?? []).map(s => ({ name: s.name ?? '', dry: s.dryMass ?? s.dry, fuel: s.fuel, thrust: s.thrust, exhaust: s.exhaustSpeed ?? s.exhaust }));
    this.steps = (o.steps ?? []).map(s => ({ name: s.name ?? '', end: s.end, power: s.power, pitch: s.pitch, yaw: s.yaw ?? 0, relative: !!s.relative }));
  }
  copy() { return new RocketSpec(this.toJSON()); }
  toJSON() {
    return {
      title: this.title, name: this.name, payload: this.payload, azimuth: this.azimuth, latitude: this.latitude,
      longitude: this.longitude, captureBody: this.captureBody, captureApoapsis: this.captureApoapsis,
      launchSeconds: this.launchSeconds,
      stages: this.stages.map(s => ({ name: s.name, dryMass: s.dry, fuel: s.fuel, thrust: s.thrust, exhaustSpeed: s.exhaust })),
      steps: this.steps.map(s => ({ name: s.name, end: s.end, power: s.power, pitch: s.pitch, yaw: s.yaw, relative: s.relative })),
    };
  }
  validate() {
    let last = -1;
    for (const s of this.steps) {
      if (!(s.end > last)) return `Flight step end times must be increasing (${s.name}).`;
      if (!(s.power >= 0 && s.power <= 1)) return `Power must be between 0 and 1 (${s.name}).`;
      last = s.end;
    }
    for (const s of this.stages)
      if (!(s.exhaust > 0 && s.thrust >= 0 && s.dry >= 0 && s.fuel >= 0)) return `Stage values must be positive, exhaust speed above 0 (${s.name}).`;
    return null;
  }
}

/** Same JSON format as the desktop app's .gravity files. */
export function readConfig(text) {
  const root = JSON.parse(text);
  if (root.format !== 'gravity') throw new Error('Not a Gravity configuration file.');
  const v = a => { if (!Array.isArray(a) || a.length !== 3) throw new Error('Vectors need 3 numbers.'); return new V(+a[0], +a[1], +a[2]); };
  const masses = (root.masses ?? []).map(m => {
    for (const k of ['diameter', 'mass']) if (typeof m[k] !== 'number') throw new Error(`Missing number '${k}'.`);
    const x = new Mass(v(m.position), m.diameter, m.mass, v(m.velocity), m.rotationSpeed ?? 0, String(m.name));
    x.rotationAngle = m.rotationAngle ?? 0;
    return x;
  });
  if (!masses.length) throw new Error('The file contains no masses.');
  const spec = new RocketSpec(root.rocket ?? {});
  const problem = spec.validate();
  if (problem) throw new Error(problem);
  return { time: root.time ?? 0, masses, spec };
}

export function writeConfig(world, spec) {
  return JSON.stringify({
    format: 'gravity', version: 1, time: world.time,
    masses: world.masses.filter(m => !m.isRocket).map(m => ({
      name: m.name, diameter: m.radius * 2, mass: m.mass, position: [m.pos.x, m.pos.y, m.pos.z],
      velocity: [m.speed.x, m.speed.y, m.speed.z], rotationSpeed: m.rotationSpeed, rotationAngle: m.rotationAngle,
    })),
    rocket: spec.toJSON(),
  }, null, 2) + '\n';
}

export class World {
  constructor() { this.masses = []; this.time = 0; this.delta = 1; }
  step() {
    for (const m of this.masses) m.accelerate(this.time, this.masses, this.delta);
    for (const m of this.masses) m.move(this.delta);
    this.time += this.delta;
  }
  rockets() { return this.masses.filter(m => m.isRocket); }
  /** The heaviest non-Sun body whose sphere of influence contains the mass, otherwise the Sun. */
  primaryOf(m) {
    const sun = this.masses[0];
    let best = null, bestSoi = Infinity;
    for (const p of this.masses) {
      if (p === m || p === sun || p.mass <= m.mass) continue;
      let parent = null;
      for (const q of this.masses)
        if (q !== p && q.mass > p.mass && (!parent || p.pos.dist(q.pos) < p.pos.dist(parent.pos))) parent = q;
      if (!parent) parent = sun;
      const soi = p.pos.dist(parent.pos) * Math.pow(p.mass / parent.mass, 0.4);
      if (m.pos.dist(p.pos) < soi && soi < bestSoi) { best = p; bestSoi = soi; }
    }
    return best ?? (m === sun ? null : sun);
  }
}

/** Mission controller: launch scheduling, automatic time scale and integration step (ported from the desktop app). */
export const MAX_SIM_SPEED = 10000, MAX_BURN_SPEED = 10, MIN_DELTA = 1e-3, MAX_DELTA = 60;

export class Sim {
  constructor(defaults) {
    this.world = new World();
    this.pause = true; this.autoScale = true; this.simSpeed = 1; this.launchPending = true;
    this.load(defaults);
  }
  /** Installs a configuration (file load or defaults). */
  load(text) {
    const l = readConfig(text);
    this.world.masses = l.masses; this.spec = l.spec;
    this.world.time = l.time; this.world.delta = 1e-4; this.pause = true; this.simSpeed = 1;
    this.loaded = writeConfig(this.world, this.spec);
    this.refresh();
  }
  setMasses(masses) {
    this.world.masses = masses;
    this.loaded = writeConfig(this.world, this.spec);
    this.refresh();
  }
  setSpec(spec) {
    this.spec = spec;
    const j = JSON.parse(this.loaded);
    j.rocket = spec.toJSON();
    this.loaded = JSON.stringify(j, null, 2) + '\n';
    if (this.world.rockets().length === 0) this.refresh(); else this.onChange?.();
  }
  /** Restart: back to the last applied/loaded configuration. */
  restart() {
    const l = readConfig(this.loaded);
    this.world.masses = l.masses; this.spec = l.spec;
    this.world.time = l.time; this.world.delta = 1e-4; this.pause = true; this.simSpeed = 1;
    this.refresh();
  }
  refresh() {
    this.launchPending = this.world.time < this.spec.launchSeconds && this.spec.stages.length > 0;
    this.onChange?.();
  }
  indexOf(name) { return this.world.masses.findIndex(m => m.name === name); }
  createRocket() {
    const earth = Math.max(0, this.indexOf('Earth'));
    const r = new Rocket(this.spec, earth);
    r.captureIndex = this.spec.captureBody ? this.indexOf(this.spec.captureBody) : -1;
    return r;
  }
  launchNow() {
    if (!this.spec.stages.length) return;
    this.launchPending = false;
    const r = this.createRocket();
    this.world.masses.push(r);
    r.launch(this.world.time, this.world.masses);
    this.onLaunch?.(r);
  }
  engineFiring() { return this.world.rockets().some(r => r.firing); }
  autoSpeed(realDt) {
    const w = this.world;
    let target = MAX_SIM_SPEED;
    if (this.launchPending) target = Math.min(target, Math.max(10, (this.spec.launchSeconds - w.time) / 2));
    for (const rk of w.rockets()) {
      if (rk.crash || !rk.launchBody) continue;
      let near = Infinity;
      for (const b of w.masses) if (b !== rk && !b.isRocket && b.name !== 'Sun' && b.name !== 'ISS') near = Math.min(near, rk.pos.dist(b.pos) - b.radius);
      target = Math.min(target, Math.max(10, 2 * near));
      target = Math.min(target, Math.max(10, rk.timeToBurn(w.time) / 3));
    }
    const rate = target < this.simSpeed ? Math.exp(-3 * realDt) : Math.exp(2 * realDt);
    this.simSpeed = Math.max(1, this.simSpeed) * rate;
    this.simSpeed = target < this.simSpeed ? Math.min(this.simSpeed, 3 * target) : Math.min(target, this.simSpeed);
    return this.simSpeed;
  }
  autoDelta() {
    const w = this.world;
    let d = MAX_DELTA;
    if (this.launchPending) d = Math.min(d, Math.max(1e-3, (this.spec.launchSeconds - w.time) / 100));
    for (const rk of w.rockets()) {
      if (rk.crash) continue;
      const p = w.primaryOf(rk);
      if (!p) continue;
      d = Math.min(d, 5e-4 * rk.timescale(p));
      d = Math.min(d, Math.max(MIN_DELTA, rk.timeToBurn(w.time) / 50));
      if (rk.captureIndex >= 0 && rk.captureState < 2 && w.masses.indexOf(p) === rk.captureIndex) d = Math.min(d, 0.5);
      if (rk.firing) d = MIN_DELTA;
    }
    return Math.max(MIN_DELTA, d);
  }
  /** Advances the simulation for one display frame. */
  frame(realDt) {
    if (this.pause) return;
    const w = this.world, end = performance.now() + (this.autoScale ? 14 : 8);
    const start = w.time, budget = this.autoScale ? this.autoSpeed(realDt) * realDt : Infinity;
    do {
      if (this.autoScale) w.delta = this.autoDelta();
      w.step();
      if (this.launchPending && w.time >= this.spec.launchSeconds) this.launchNow();
      if (this.engineFiring() && w.time - start >= MAX_BURN_SPEED * realDt) break;
      if (w.time - start >= budget) break;
    } while (performance.now() < end);
  }
}
