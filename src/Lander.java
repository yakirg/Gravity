import javafx.geometry.Point3D;

import java.util.function.DoubleFunction;

/**
 * Closed-loop recovery of a spent booster: an optional boostback burn toward the landing site, an entry burn and a
 * suicide landing burn. Positions and velocities are relative to the body's center (km, km/s, inertial axes);
 * time is absolute simulation seconds. {@link #accel} returns the thrust plus drag acceleration; gravity is the caller's.
 */
final class Lander {
    enum Phase {COAST, BOOSTBACK, DESCENT, ENTRY, LANDING, LANDED, CRASHED}

    static final double FLIP_DELAY = 6, BOOSTBACK_PITCH = Math.toRadians(10), ENTRY_ALTITUDE = 65;
    static final double MIN_THROTTLE = 0.1, TOUCHDOWN_SPEED = 0.002, SAFE_SPEED = 0.015, MAX_TILT = Math.toRadians(30);
    static final double PREDICTION_INTERVAL = 0.1;

    final double bodyRadius, mu, spin, dry, exhaust, boostThrust, landThrust, cdA, entrySpeed;
    final boolean boostback;
    double prop;
    Phase phase;
    double startTime;
    /** Unit vector from the body's center to the landing site at a given time; null disables steering. */
    DoubleFunction<Point3D> targetUp;
    Point3D attitude = Point3D.ZERO;
    boolean firing;
    double throttle;
    private boolean entryDone;
    private double lastPrediction = -1e9;
    private boolean cutoff;

    Lander(double bodyRadius, double mu, double spin, double dry, double prop, double exhaust, double boostThrust,
           double landThrust, boolean boostback, double entrySpeed, double startTime) {
        this.bodyRadius = bodyRadius;
        this.mu = mu;
        this.spin = spin;
        this.dry = dry;
        this.prop = prop;
        this.exhaust = exhaust;
        this.boostThrust = boostThrust;
        this.landThrust = landThrust;
        this.boostback = boostback;
        this.entrySpeed = entrySpeed;
        this.cdA = 40;
        this.startTime = startTime;
        this.phase = boostback ? Phase.COAST : Phase.DESCENT;
    }

    private Lander copy() {
        Lander c = new Lander(bodyRadius, mu, spin, dry, prop, exhaust, boostThrust, landThrust, boostback, entrySpeed, startTime);
        c.phase = phase;
        c.entryDone = entryDone;
        c.attitude = attitude;
        return c;
    }

    boolean done() {
        return phase == Phase.LANDED || phase == Phase.CRASHED;
    }

    /** Propellant needed to land from terminal velocity, with margin. */
    double landingReserve() {
        return dry * (Math.exp(1.0 / exhaust) - 1) * 1.3;
    }

    private static double density(double alt) {
        return alt > 120 ? 0 : 1.225 * Math.exp(-alt / 7.4);
    }

    Point3D accel(double t, Point3D r, Point3D v, double dt) {
        firing = false;
        throttle = 0;
        if (done())
            return Point3D.ZERO;
        double d = r.magnitude();
        Point3D up = r.multiply(1 / d);
        double alt = d - bodyRadius;
        Point3D vAir = v.subtract(Rocket.SPIN_AXIS.crossProduct(r).multiply(spin));
        double speed = vAir.magnitude();
        double vUp = vAir.dotProduct(up);
        if (alt <= 0 && vUp < 0) {
            phase = speed < SAFE_SPEED ? Phase.LANDED : Phase.CRASHED;
            return Point3D.ZERO;
        }
        double m = dry + prop;
        double g = mu / (d * d);
        Point3D a = vAir.multiply(-0.5 * density(alt) * speed * 1000 * cdA / m);
        Point3D retro = speed > 1e-6 ? vAir.multiply(-1 / speed) : up;

        if (phase == Phase.COAST) {
            if (targetUp != null && t - startTime >= FLIP_DELAY)
                phase = Phase.BOOSTBACK;
            else if (targetUp == null)
                phase = Phase.DESCENT;
        }
        if (phase == Phase.BOOSTBACK) {
            Point3D toTarget = targetUp.apply(t).multiply(bodyRadius).subtract(r);
            Point3D horizontal = toTarget.subtract(up.multiply(toTarget.dotProduct(up)));
            Point3D heading = horizontal.magnitude() > 1e-9 ? horizontal.normalize() : up;
            attitude = heading.multiply(Math.cos(BOOSTBACK_PITCH)).add(up.multiply(Math.sin(BOOSTBACK_PITCH))).normalize();
            if (!cutoff && t - lastPrediction >= PREDICTION_INTERVAL) {
                lastPrediction = t;
                if (prop <= landingReserve() * 2 || miss(t, r, v, heading) >= 0)
                    cutoff = true;
            }
            if (cutoff || prop <= 0)
                phase = Phase.DESCENT;
            else {
                a = a.add(attitude.multiply(burn(boostThrust, 1, dt) / m));
                return a;
            }
        }
        attitude = retro;
        if (phase == Phase.DESCENT && vUp < 0 && alt < ENTRY_ALTITUDE) {
            if (!entryDone && speed > entrySpeed + 0.1 && prop > landingReserve() * 1.2)
                phase = Phase.ENTRY;
            else {
                double aMax = landThrust / m - g;
                double stop = vUp * vUp / (2 * Math.max(1e-6, aMax * 0.4));
                if (aMax > 0 && alt <= stop + 0.01)
                    phase = Phase.LANDING;
            }
        }
        if (phase == Phase.ENTRY) {
            if (speed <= entrySpeed || prop <= landingReserve() * 1.2 || alt < 20) {
                entryDone = true;
                phase = Phase.DESCENT;
            } else
                return a.add(retro.multiply(burn(boostThrust, 1, dt) / m));
        }
        if (phase == Phase.LANDING) {
            double vDown = -vUp;
            double h = Math.max(alt, 0.0005);
            double aVert = (vDown * vDown - TOUCHDOWN_SPEED * TOUCHDOWN_SPEED) / (2 * h) + g;
            Point3D lateral = Point3D.ZERO;
            if (targetUp != null) {
                Point3D toTarget = targetUp.apply(t).multiply(bodyRadius).subtract(r);
                Point3D offset = toTarget.subtract(up.multiply(toTarget.dotProduct(up)));
                Point3D desired = offset.multiply(1 / Math.max(2, h / Math.max(vDown, 0.01)));
                if (desired.magnitude() > 0.03)
                    desired = desired.normalize().multiply(0.03);
                desired = desired.multiply(Math.min(1, h / 0.3));
                Point3D horizontalSpeed = vAir.subtract(up.multiply(vUp));
                lateral = desired.subtract(horizontalSpeed).multiply(1 / 1.5);
                double limit = aVert * Math.tan(MAX_TILT);
                if (lateral.magnitude() > limit)
                    lateral = lateral.normalize().multiply(limit);
            }
            Point3D thrust = up.multiply(aVert).add(lateral);
            double need = thrust.magnitude() * m / landThrust;
            if (need >= MIN_THROTTLE && vDown > 0 && prop > 0) {
                double th = Math.min(1, need);
                attitude = thrust.normalize();
                a = a.add(attitude.multiply(burn(landThrust, th, dt) / m));
            }
        }
        return a;
    }

    /** Burns at the given thrust and throttle for dt seconds; returns the thrust force actually delivered (kN). */
    private double burn(double thrust, double th, double dt) {
        double f = thrust * th;
        double used = f / exhaust * dt;
        if (used > prop) {
            f *= prop / used;
            used = prop;
        }
        prop -= used;
        firing = f > 0;
        throttle = th;
        return f;
    }

    /** Ground distance (km) by which a descent from this state lands beyond the site along heading; negative means short. */
    private double miss(double t, Point3D r, Point3D v, Point3D heading) {
        Touchdown td = touchdown(t, r, v);
        Point3D site = targetUp.apply(td.time).multiply(bodyRadius);
        return td.position.subtract(site).dotProduct(heading);
    }

    record Touchdown(Point3D position, double time, Phase phase, double speed) {}

    /** Flies a copy of this lander (no further boostback, no steering) to the ground and reports where it ends up. */
    Touchdown touchdown(double t, Point3D r, Point3D v) {
        Lander s = copy();
        s.phase = Phase.DESCENT;
        s.targetUp = null;
        double time = t;
        for (int i = 0; i < 400000 && !s.done(); i++) {
            double alt = r.magnitude() - bodyRadius;
            double dt = alt > 80 ? 1 : alt > 8 && s.phase != Phase.LANDING ? 0.2 : 0.02;
            Point3D a = s.accel(time, r, v, dt);
            if (s.done())
                break;
            double d = r.magnitude();
            v = v.add(a.multiply(dt)).add(r.multiply(-mu / (d * d * d) * dt));
            r = r.add(v.multiply(dt));
            time += dt;
        }
        Point3D vAir = v.subtract(Rocket.SPIN_AXIS.crossProduct(r).multiply(spin));
        return new Touchdown(r, time, s.phase, vAir.magnitude());
    }
}
