import javafx.geometry.Point3D;
import javafx.scene.Group;
import javafx.scene.paint.Color;
import javafx.scene.paint.Material;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Box;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.Cylinder;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.transform.Rotate;

import java.util.List;

public class Rocket extends Mass {

    int launchFrom;
    double fuelLevel;
    double engineMaxTrust;
    double fuelConsumption;
    List<LaunchStep> launchStepList;
    int currentLaunchStep = 0;

    Point3D direction = Point3D.ZERO;

    double launchTime;

    boolean crash = false;
    /** True while the engine is burning; the app limits simulation speed then. */
    boolean firing = false;

    /** Index in the mass list of a body to enter orbit around after the launch steps (-1 = none). */
    int captureIndex = -1;
    double captureApoapsis = 20000;
    int captureState = 0;
    private static final double CAPTURE_RANGE = 30000;

    /** Vehicle stages, first to fire first. Empty for a single-stage rocket described by the fields above. */
    final java.util.List<RocketStage> stages = new java.util.ArrayList<>();
    int stageIndex = 0;

    Mass launchBody;
    private double stepStartTime = 0;
    private double stepStartPitch = 0;

    private double stepStartYaw = 0;
    private boolean stepStartRelative = false;

    /** Launch site on the launch body (degrees) and launch azimuth clockwise from north (270 = due west). */
    double launchLatitude = 0;
    double launchLongitude = 0;
    double launchAzimuth = 90;

    /** Oblateness of the launch body; the rocket feels the extra pull of its equatorial bulge. */
    double j2 = 0;

    private static final double OBLIQUITY = Math.toRadians(23.4393);
    /** The launch body's spin axis in ecliptic coordinates (Earth's north pole, tilted by the obliquity). */
    static final Point3D SPIN_AXIS = new Point3D(0, Math.sin(OBLIQUITY), Math.cos(OBLIQUITY));

    public Rocket(Point3D pos, float radius, float mass, Point3D speed, double rotationSpeed, String name, int launchFrom) {
        super(pos, radius, mass, speed, rotationSpeed, name);
        this.launchFrom = launchFrom;
    }

    static Point3D equatorialToEcliptic(Point3D p) {
        return new Point3D(p.getX(), p.getY() * Math.cos(OBLIQUITY) + p.getZ() * Math.sin(OBLIQUITY),
                -p.getY() * Math.sin(OBLIQUITY) + p.getZ() * Math.cos(OBLIQUITY));
    }

    /** Earth rotation angle (radians) at the given seconds after the simulation epoch. */
    static double earthRotationAngle(double seconds) {
        double days = (Masses.EPOCH_SECONDS - 946728000L + seconds) / 86400.0;
        double turns = 0.7790572732640 + 1.00273781191135448 * days;
        return 2 * Math.PI * (turns - Math.floor(turns));
    }

    /** Unit vector from the launch body's center to the launch site at the given time. */
    Point3D siteUp(double currentTime) {
        double lat = Math.toRadians(launchLatitude);
        double theta = earthRotationAngle(currentTime) + Math.toRadians(launchLongitude);
        double x = Math.cos(lat) * Math.cos(theta), y = Math.cos(lat) * Math.sin(theta), z = Math.sin(lat);
        return new Point3D(x, y * Math.cos(OBLIQUITY) + z * Math.sin(OBLIQUITY),
                -y * Math.sin(OBLIQUITY) + z * Math.cos(OBLIQUITY)).normalize();
    }

    /** Places the rocket at the launch site, moving with the surface. */
    public void Launch(double currentTime, Masses masses) {
        launchBody = masses.massList.get(launchFrom);
        Point3D up = siteUp(currentTime);
        Point3D offset = up.multiply(launchBody.radius);
        pos = launchBody.pos.add(offset);
        speed = launchBody.speed.add(SPIN_AXIS.crossProduct(offset).multiply(launchBody.rotationSpeed));
        currentLaunchStep = 0;
        stepStartTime = 0;
        stepStartPitch = 0;
        stepStartYaw = 0;
        stepStartRelative = false;
        crash = false;
        captureState = 0;
        lastRadial = 0;
        launchTime = currentTime;
        if (!stages.isEmpty()) {
            stageIndex = 0;
            mass += stages.stream().mapToDouble(st -> st.dryMass).sum();
            for (int i = 1; i < stages.size(); i++)
                mass += stages.get(i).fuel;
            loadStage();
        }
    }

    private void loadStage() {
        RocketStage s = stages.get(stageIndex);
        fuelLevel = s.fuel;
        engineMaxTrust = s.thrust;
        fuelConsumption = s.consumption;
    }

    /** Drops the empty stage and lights the next one. */
    private void separate() {
        mass -= stages.get(stageIndex).dryMass;
        stageIndex++;
        mass -= stages.get(stageIndex).fuel;
        loadStage();
    }

    private Point3D guidance(double t, LaunchStep step) {
        Point3D up = pos.subtract(launchBody.pos).normalize();
        double span = step.end - stepStartTime;
        double f = span > 0 ? Math.min(1, (t - stepStartTime) / span) : 1;
        double pitch = Math.toRadians(stepStartPitch + (step.pitch - stepStartPitch) * f);
        double yaw = Math.toRadians(step.relative == stepStartRelative ? stepStartYaw + (step.yaw - stepStartYaw) * f : step.yaw);
        Point3D north = SPIN_AXIS.subtract(up.multiply(SPIN_AXIS.dotProduct(up))).normalize();
        Point3D east = SPIN_AXIS.crossProduct(up).normalize();
        Point3D heading = null;
        if (step.relative) {
            Point3D v = speed.subtract(launchBody.speed);
            Point3D horizontal = v.subtract(up.multiply(v.dotProduct(up)));
            if (horizontal.magnitude() > 1e-9) {
                Point3D h0 = horizontal.normalize();
                heading = h0.multiply(Math.cos(yaw)).add(up.crossProduct(h0).multiply(Math.sin(yaw)));
            }
        }
        if (heading == null) {
            double azimuth = Math.toRadians(launchAzimuth) + yaw;
            heading = north.multiply(Math.cos(azimuth)).add(east.multiply(Math.sin(azimuth)));
        }
        return up.multiply(Math.cos(pitch)).add(heading.multiply(Math.sin(pitch)));
    }

    private Point3D oblatenessAcceleration() {
        Point3D r = pos.subtract(launchBody.pos);
        double d = r.magnitude();
        Point3D rHat = r.multiply(1 / d);
        double s = rHat.dotProduct(SPIN_AXIS);
        double k = 1.5 * j2 * 6.67e-20 * launchBody.mass * launchBody.radius * launchBody.radius / Math.pow(d, 4);
        return rHat.multiply(k * (5 * s * s - 1)).subtract(SPIN_AXIS.multiply(2 * k * s));
    }

    private Group model;
    private Group firstStage;
    private MeshView flame;
    private Group arrow;
    private PhongMaterial bodyMaterial;

    private static MeshView cone(double radius, double height, Material material) {
        int n = 20;
        TriangleMesh mesh = new TriangleMesh();
        mesh.getTexCoords().addAll(0, 0);
        mesh.getPoints().addAll(0, (float) height, 0);
        for (int i = 0; i < n; i++) {
            double a = 2 * Math.PI * i / n;
            mesh.getPoints().addAll((float) (radius * Math.cos(a)), 0, (float) (radius * Math.sin(a)));
        }
        for (int i = 0; i < n; i++)
            mesh.getFaces().addAll(0, 0, 1 + i, 0, 1 + (i + 1) % n, 0);
        MeshView view = new MeshView(mesh);
        view.setMaterial(material);
        view.setCullFace(CullFace.NONE);
        return view;
    }

    /**
     * Falcon 9 style model along the Y axis (nose toward +Y), 3.2 units tall (70 m, scaled 1:22), with a
     * first stage (with fins), a second stage and a payload fairing.
     */
    @Override
    public void createNode(Group world) {
        bodyMaterial = new PhongMaterial(Color.WHITE);
        PhongMaterial red = new PhongMaterial(Color.CRIMSON);
        PhongMaterial dark = new PhongMaterial(Color.DARKGRAY);
        double r = 0.12;
        Cylinder s1 = new Cylinder(r, 1.9);
        s1.setMaterial(bodyMaterial);
        s1.setTranslateY(-0.65);
        firstStage = new Group(s1);
        for (int i = 0; i < 4; i++) {
            Box fin = new Box(0.34, 0.3, 0.03);
            fin.setMaterial(red);
            fin.setTranslateY(-1.4);
            fin.setRotationAxis(Rotate.Y_AXIS);
            fin.setRotate(i * 45);
            firstStage.getChildren().add(fin);
        }
        Cylinder interstage = new Cylinder(r, 0.1);
        interstage.setMaterial(dark);
        interstage.setTranslateY(0.35);
        firstStage.getChildren().add(interstage);
        Cylinder s2 = new Cylinder(r, 0.55);
        s2.setMaterial(bodyMaterial);
        s2.setTranslateY(0.675);
        Cylinder fairing = new Cylinder(r, 0.35);
        fairing.setMaterial(bodyMaterial);
        fairing.setTranslateY(1.125);
        MeshView nose = cone(r, 0.35, red);
        nose.setTranslateY(1.3);
        Cylinder nozzle1 = new Cylinder(0.07, 0.12);
        nozzle1.setMaterial(dark);
        nozzle1.setTranslateY(-1.66);
        firstStage.getChildren().add(nozzle1);
        Cylinder nozzle2 = new Cylinder(0.05, 0.08);
        nozzle2.setMaterial(dark);
        nozzle2.setTranslateY(0.36);
        model = new Group(firstStage, s2, fairing, nose, nozzle2);
        PhongMaterial flameMaterial = new PhongMaterial(Color.ORANGE);
        flameMaterial.setSpecularColor(Color.YELLOW);
        flame = cone(0.16, 1.2, flameMaterial);
        flame.setRotationAxis(Rotate.X_AXIS);
        flame.setRotate(180);
        flame.setVisible(false);
        PhongMaterial arrowMaterial = new PhongMaterial(Color.LIME);
        arrowMaterial.setSpecularColor(Color.WHITE);
        Cylinder shaft = new Cylinder(0.05, 2.0);
        shaft.setMaterial(arrowMaterial);
        shaft.setTranslateY(2.8);
        MeshView head = cone(0.18, 0.7, arrowMaterial);
        head.setTranslateY(3.8);
        arrow = new Group(shaft, head);
        arrow.setVisible(false);
        model.getChildren().addAll(flame, arrow);
        model.setRotationAxis(Rotate.Y_AXIS);
        world.getChildren().add(model);
    }

    @Override
    public javafx.scene.Node node() {
        return model;
    }

    @Override
    public void updateNode(Point3D focusPos, double scale) {
        double s = scale * 0.07 / 3.2;
        model.setScaleX(s);
        model.setScaleY(s);
        model.setScaleZ(s);
        model.setTranslateX((pos.getX() - focusPos.getX()) * scale);
        model.setTranslateY((pos.getY() - focusPos.getY()) * scale);
        model.setTranslateZ((pos.getZ() - focusPos.getZ()) * scale);
        bodyMaterial.setDiffuseColor(crash ? Color.RED : Color.WHITE);
        flame.setVisible(firing);
        boolean first = stageIndex == 0;
        if (firstStage.isVisible() != first)
            firstStage.setVisible(first);
        flame.setTranslateY(first ? -1.72 : 0.32);
        arrow.setVisible(firing);
        Point3D dir = direction.magnitude() > 0 ? direction.normalize() : Rotate.Y_AXIS;
        Point3D axis = Rotate.Y_AXIS.crossProduct(dir);
        double angle = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, Rotate.Y_AXIS.dotProduct(dir)))));
        if (axis.magnitude() < 1e-9)
            axis = Rotate.X_AXIS;
        model.getTransforms().setAll(new Rotate(angle, axis));
    }

    @Override
    protected Color baseColor() {
        return crash ? Color.RED : Color.LIMEGREEN;
    }

    /** Name of the current flight stage, plus the vehicle stage that is attached for multi-stage rockets. */
    String stage() {
        String phase = phase();
        if (stages.size() > 1 && !phase.isEmpty() && !crash)
            phase += "  [" + stages.get(stageIndex).name + "]";
        return phase;
    }

    private String phase() {
        if (launchBody == null)
            return "";
        if (crash)
            return "Crashed";
        if (currentLaunchStep < launchStepList.size()) {
            String n = launchStepList.get(currentLaunchStep).name;
            return n.isEmpty() ? "Step " + currentLaunchStep : n;
        }
        if (captureIndex < 0)
            return "Coast";
        return captureState == 0 ? "Translunar coast" : captureState == 1 ? "Lunar orbit insertion"
                : captureState == 2 ? "Lunar orbit" : captureState == 3 ? "Circularization burn" : "Circular lunar orbit";
    }

    @Override
    public String tableRow(Mass primary) {
        return super.tableRow(primary) + String.format("  dir %s, %s, %s  stage: %s  fuel %s%s",
                df.format(direction.getX()), df.format(direction.getY()), df.format(direction.getZ()),
                stage(), df.format(fuelLevel), crash ? "" : firing ? "  ENGINE ON" : "");
    }

    @Override
    public void accelerate(double currentTime, List<Mass> massList, double delta) {
        if (launchBody == null)
            return;
        firing = false;
        if (!stages.isEmpty() && fuelLevel <= 0 && stageIndex + 1 < stages.size())
            separate();
        if (crash) {
            speed = launchBody.speed;
            return;
        }
        double t = currentTime - launchTime;
        if (currentLaunchStep < launchStepList.size()) {
            LaunchStep step = launchStepList.get(currentLaunchStep);
            direction = guidance(t, step);
            if (fuelLevel > 0 && step.powerLevel > 0) {
                firing = true;
                double acc = engineMaxTrust * step.powerLevel / (mass + fuelLevel);
                speed = speed.add(direction.multiply(acc * delta));
                fuelLevel = Math.max(0, fuelLevel - fuelConsumption * step.powerLevel * delta);
            }
            if (t > step.end) {
                stepStartTime = step.end;
                stepStartPitch = step.pitch;
                stepStartYaw = step.yaw;
                stepStartRelative = step.relative;
                currentLaunchStep++;
            }
        }
        if (j2 > 0)
            speed = speed.add(oblatenessAcceleration().multiply(delta));
        if (currentLaunchStep >= launchStepList.size() && captureIndex >= 0 && captureIndex < massList.size())
            capture(massList.get(captureIndex), delta);
        super.accelerate(currentTime, massList, delta);
        allMasses = massList;
        dist = pos.distance(launchBody.pos);
        if (dist < launchBody.radius - 0.01)
            crash = true;
    }

    private List<Mass> allMasses;

    /** Seconds until the next engine burn (0 while burning, infinity if none is scheduled). */
    double timeToBurn(double currentTime) {
        if (crash || launchBody == null)
            return Double.POSITIVE_INFINITY;
        if (firing)
            return 0;
        if (currentLaunchStep >= launchStepList.size() && captureState == 2 && allMasses != null
                && captureIndex >= 0 && captureIndex < allMasses.size()) {
            Mass body = allMasses.get(captureIndex);
            Point3D r = pos.subtract(body.pos), v = speed.subtract(body.speed);
            double d = r.magnitude(), rv = r.dotProduct(v);
            double decel = 6.67e-20 * (body.mass + mass) / d - v.dotProduct(v);
            return rv <= 0 ? 0 : decel > 0 ? rv / decel : Double.POSITIVE_INFINITY;
        }
        double t = currentTime - launchTime;
        for (int i = currentLaunchStep; i < launchStepList.size(); i++)
            if (launchStepList.get(i).powerLevel > 0)
                return Math.max(0, (i == 0 ? 0 : launchStepList.get(i - 1).end) - t);
        return Double.POSITIVE_INFINITY;
    }

    /** The rocket's primary-relative dynamical timescale in seconds, sqrt(r^3 / mu). */
    double timescale(Mass primary) {
        double r = pos.distance(primary.pos);
        return Math.sqrt(r * r * r / (6.67e-20 * (primary.mass + mass)));
    }

    /**
     * Closed-loop orbit insertion: after the launch steps, once the rocket has passed its closest approach to the
     * capture body (within CAPTURE_RANGE), it burns retrograde until the orbit around that body is bound with an
     * apoapsis no higher than captureApoapsis.
     */
    private void capture(Mass body, double delta) {
        if (captureState == 4)
            return;
        Point3D r = pos.subtract(body.pos), v = speed.subtract(body.speed);
        double d = r.magnitude();
        if (captureState >= 2) {
            circularize(body, r, v, d, delta);
            return;
        }
        if (captureState == 0) {
            if (d < CAPTURE_RANGE && r.dotProduct(v) >= 0)
                captureState = 1;
            else
                return;
        }
        double mu = 6.67e-20 * (body.mass + mass);
        double eps = v.dotProduct(v) / 2 - mu / d;
        if (eps < 0) {
            double a = -mu / (2 * eps);
            double e = Math.sqrt(Math.max(0, 1 + 2 * eps * r.crossProduct(v).dotProduct(r.crossProduct(v)) / (mu * mu)));
            if (a * (1 + e) <= captureApoapsis || fuelLevel <= 0) {
                captureState = 2;
                return;
            }
        }
        if (fuelLevel <= 0) {
            captureState = 2;
            return;
        }
        direction = v.normalize().multiply(-1);
        speed = speed.add(direction.multiply(engineMaxTrust / (mass + fuelLevel) * delta));
        fuelLevel = Math.max(0, fuelLevel - fuelConsumption * delta);
        firing = true;
    }

    /** Coasts to apoapsis, then burns prograde until the orbit is (nearly) circular. */
    private void circularize(Mass body, Point3D r, Point3D v, double d, double delta) {
        double mu = 6.67e-20 * (body.mass + mass);
        if (captureState == 2) {
            if (fuelLevel <= 0) {
                captureState = 4;
                return;
            }
            // Wait for apoapsis, where the radial speed changes from outward to inward.
            if (lastRadial > 0 && r.dotProduct(v) <= 0)
                captureState = 3;
            lastRadial = r.dotProduct(v);
            if (captureState == 2)
                return;
        }
        double eps = v.dotProduct(v) / 2 - mu / d;
        double h2 = r.crossProduct(v).dotProduct(r.crossProduct(v));
        double e = Math.sqrt(Math.max(0, 1 + 2 * eps * h2 / (mu * mu)));
        if (eps >= 0 || e <= 0.02 || fuelLevel <= 0) {
            captureState = 4;
            return;
        }
        direction = v.normalize();
        speed = speed.add(direction.multiply(engineMaxTrust / (mass + fuelLevel) * delta));
        fuelLevel = Math.max(0, fuelLevel - fuelConsumption * delta);
        firing = true;
    }

    private double lastRadial;

    @Override
    public void move(double delta) {
        if (launchBody != null)
            super.move(delta);
    }
}