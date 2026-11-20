import javafx.geometry.Point3D;
import javafx.scene.Group;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Box;
import javafx.scene.shape.Cylinder;
import javafx.scene.shape.MeshView;
import javafx.scene.transform.Rotate;

import java.util.ArrayList;
import java.util.List;

/** A spent stage or booster. Plain stages fall ballistically; recoverable boosters fly boostback, entry and landing burns. */
public class Debris extends Rocket {

    enum Kind {BOOSTER, CORE, UPPER}

    private final Kind kind;
    private final boolean fins;
    private final Point3D heading;
    private Group node3d;
    private PhongMaterial bodyMaterial;
    private MeshView flame;
    double impactSpeed;
    Lander lander;
    boolean landed;
    private boolean grounded;
    private double groundLat, groundLon;
    /** Landing site (degrees) of a core aimed at the drone ship; NaN for boosters landing at the launch site. */
    double siteLat = Double.NaN, siteLon = Double.NaN;

    Debris(Rocket from, String name, double mass, Kind kind, boolean fins, Point3D kick) {
        super(from.pos.add(kick.multiply(3)), 0.005f, (float) mass, from.speed.add(kick), 0, name, from.launchFrom);
        this.kind = kind;
        this.fins = fins;
        this.launchBody = from.launchBody;
        this.launchStepList = new ArrayList<>();
        this.launchTime = from.launchTime;
        this.now = from.now;
        this.j2 = from.j2;
        this.launchLatitude = from.launchLatitude;
        this.launchLongitude = from.launchLongitude;
        this.heading = from.direction.magnitude() > 0 ? from.direction.normalize() : Rotate.Y_AXIS;
        this.direction = heading;
    }

    /**
     * Gives the stage a recovery plan. A booster flies a boostback burn and lands at the launch site; otherwise the
     * landing site is where the stage would come down on its own, held by a drone ship.
     */
    void planLanding(double prop, double engineThrust, int boostEngines, int landEngines, double exhaust, boolean boostback, double entrySpeed) {
        Mass body = launchBody;
        lander = new Lander(body.radius, 6.67e-20 * body.mass, body.rotationSpeed, mass, prop, exhaust,
                engineThrust * boostEngines, engineThrust * landEngines, boostback, entrySpeed, now);
        if (boostback) {
            double lat = launchLatitude, lon = launchLongitude;
            lander.targetUp = t -> siteUp(lat, lon, t);
        } else {
            Point3D r = pos.subtract(body.pos), v = speed.subtract(body.speed);
            Lander.Touchdown td = lander.touchdown(now, r, v);
            double[] site = latLon(td.position(), td.time());
            siteLat = site[0];
            siteLon = site[1];
            lander.targetUp = t -> siteUp(siteLat, siteLon, t);
        }
    }

    /** Largest integration step that keeps the descent burns accurate. */
    double maxDelta() {
        if (lander == null || lander.done() || grounded)
            return Double.MAX_VALUE;
        double alt = pos.distance(launchBody.pos) - launchBody.radius;
        if (lander.phase == Lander.Phase.LANDING || alt < 6)
            return 0.02;
        return lander.firing || alt < 100 ? 0.05 : 0.5;
    }

    @Override
    double timeToBurn(double currentTime) {
        return Double.POSITIVE_INFINITY;
    }

    @Override
    public void accelerate(double currentTime, List<Mass> massList, double delta) {
        now = currentTime;
        if (grounded) {
            Point3D up = siteUp(groundLat, groundLon, currentTime);
            Point3D offset = up.multiply(launchBody.radius);
            pos = launchBody.pos.add(offset);
            speed = launchBody.speed.add(SPIN_AXIS.crossProduct(offset).multiply(launchBody.rotationSpeed));
            firing = false;
            return;
        }
        if (lander != null) {
            Point3D r = pos.subtract(launchBody.pos), v = speed.subtract(launchBody.speed);
            Point3D a = lander.accel(currentTime, r, v, delta);
            speed = speed.add(a.multiply(delta));
            if (lander.attitude.magnitude() > 0)
                direction = lander.attitude;
            if (lander.done()) {
                impactSpeed = v.subtract(SPIN_AXIS.crossProduct(r).multiply(launchBody.rotationSpeed)).magnitude();
                landed = lander.phase == Lander.Phase.LANDED;
                crash = true;
                touchDown(currentTime);
                return;
            }
        }
        boolean was = crash;
        Point3D v = speed;
        super.accelerate(currentTime, massList, delta);
        firing = lander != null && lander.firing;
        if (crash && !was) {
            impactSpeed = v.subtract(launchBody.speed).magnitude();
            touchDown(currentTime);
        }
    }

    private void touchDown(double currentTime) {
        double[] ll = latLon(pos.subtract(launchBody.pos), currentTime);
        groundLat = ll[0];
        groundLon = ll[1];
        grounded = true;
        firing = false;
    }

    @Override
    public void move(double delta) {
        if (!grounded)
            super.move(delta);
    }

    @Override
    public void createNode(Group world) {
        bodyMaterial = new PhongMaterial(Color.WHITE);
        PhongMaterial dark = new PhongMaterial(Color.DARKGRAY);
        PhongMaterial red = new PhongMaterial(Color.CRIMSON);
        PhongMaterial flameMaterial = new PhongMaterial(Color.ORANGE);
        flameMaterial.setSpecularColor(Color.YELLOW);
        double r = 0.12;
        node3d = new Group();
        double length = kind == Kind.UPPER ? 0.55 : 1.9;
        flame = cone(0.1, 0.9, flameMaterial);
        flame.setRotationAxis(Rotate.X_AXIS);
        flame.setRotate(180);
        flame.setTranslateY(-length / 2 - 0.1);
        flame.setVisible(false);
        node3d.getChildren().add(flame);

        Cylinder body = new Cylinder(r, length);
        body.setMaterial(bodyMaterial);
        Cylinder nozzle = new Cylinder(kind == Kind.UPPER ? 0.05 : 0.07, 0.12);
        nozzle.setMaterial(dark);
        nozzle.setTranslateY(-length / 2 - 0.05);
        node3d.getChildren().addAll(body, nozzle);
        if (kind == Kind.BOOSTER && !fins) {
            MeshView tip = cone(r, 0.3, dark);
            tip.setTranslateY(length / 2);
            node3d.getChildren().add(tip);
        }
        if (kind == Kind.CORE) {
            Cylinder interstage = new Cylinder(r, 0.1);
            interstage.setMaterial(dark);
            interstage.setTranslateY(length / 2 + 0.05);
            node3d.getChildren().add(interstage);
        }
        if (fins)
            for (int i = 0; i < 4; i++) {
                Box fin = new Box(0.34, 0.3, 0.03);
                fin.setMaterial(red);
                fin.setTranslateY(-0.75);
                fin.setRotationAxis(Rotate.Y_AXIS);
                fin.setRotate(i * 45);
                node3d.getChildren().add(fin);
            }
        if (fins) {
            Cylinder interstage = new Cylinder(r, 0.1);
            interstage.setMaterial(dark);
            interstage.setTranslateY(length / 2 + 0.05);
            node3d.getChildren().add(interstage);
        }
        orient();
        world.getChildren().add(node3d);
    }

    /** Points the model's axis (nose up the +Y axis) along the current attitude. */
    private void orient() {
        Point3D dir = direction.magnitude() > 0 ? direction.normalize() : heading;
        Point3D axis = Rotate.Y_AXIS.crossProduct(dir);
        double angle = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, Rotate.Y_AXIS.dotProduct(dir)))));
        if (axis.magnitude() < 1e-9)
            axis = Rotate.X_AXIS;
        node3d.getTransforms().setAll(new Rotate(angle, axis));
    }

    @Override
    public javafx.scene.Node node() {
        return node3d;
    }

    @Override
    public void updateNode(Point3D focusPos, double scale) {
        double s = scale * 0.07 / 3.2;
        node3d.setScaleX(s);
        node3d.setScaleY(s);
        node3d.setScaleZ(s);
        node3d.setTranslateX((pos.getX() - focusPos.getX()) * scale);
        node3d.setTranslateY((pos.getY() - focusPos.getY()) * scale);
        node3d.setTranslateZ((pos.getZ() - focusPos.getZ()) * scale);
        bodyMaterial.setDiffuseColor(crash && !landed ? Color.ORANGERED : Color.WHITE);
        flame.setVisible(firing);
        if (lander != null)
            orient();
    }

    @Override
    String stage() {
        if (landed)
            return "Landed";
        if (crash)
            return "Impact";
        if (lander == null)
            return "Falling";
        return switch (lander.phase) {
            case COAST -> "Coasting";
            case BOOSTBACK -> "Boostback burn";
            case ENTRY -> "Entry burn";
            case LANDING -> "Landing burn";
            default -> "Descent";
        };
    }

    @Override
    String extraRow() {
        if (launchBody == null)
            return "";
        double alt = pos.distance(launchBody.pos) - launchBody.radius;
        if (landed)
            return String.format("  landed at %.1f m/s", impactSpeed * 1000);
        if (crash)
            return String.format("  impact at %.2f km/s", impactSpeed);
        String burn = lander != null && lander.firing ? "  BURN" : "";
        return String.format("  %s  alt %,.1f km%s", stage(), alt, burn);
    }

    @Override
    protected Color baseColor() {
        return crash && !landed ? Color.ORANGERED : landed ? Color.LIMEGREEN : Color.LIGHTGRAY;
    }
}
