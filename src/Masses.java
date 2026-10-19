import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.geometry.Point3D;
import javafx.scene.AmbientLight;
import javafx.scene.Group;
import javafx.scene.PerspectiveCamera;
import javafx.scene.PointLight;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SubScene;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Cylinder;
import javafx.scene.shape.Sphere;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.transform.Affine;
import javafx.scene.transform.Rotate;

import java.util.ArrayList;
import java.util.List;

public class Masses extends StackPane {

    static final double CAMERA_DISTANCE = 1000;
    /** Pitch that turns Earth's spin axis (tilted by the 23.44 degree obliquity) to point up on screen. */
    static final double NORTH_UP_PITCH = 90 + 23.4393;
    // Start of the simulation: the JPL Horizons epoch of the initial state vectors (2026-10-18 00:00 UTC).
    static final long EPOCH_SECONDS = java.time.LocalDateTime.of(2026, 10, 18, 0, 0).toEpochSecond(java.time.ZoneOffset.UTC);

    List<Mass> massList = new ArrayList<>();
    long lastDraw = 0;
    double startTime = 0;

    int focus = 3;
    boolean drawTable = true;
    boolean drawInfo = true;
    boolean drawDist = false;
    boolean drawOrbits = true;
    double scale = 0.00402F;
    double delta = 1;

    private final Group world = new Group();
    private final Group lines = new Group();
    private final Group orbits = new Group();
    private final Group trailGroup = new Group();
    private final List<Cylinder> orbitSegments = new ArrayList<>();
    private final PhongMaterial orbitMaterial = new PhongMaterial(Color.gray(0.55));
    private final Group bodies = new Group();
    private final PointLight sunLight = new PointLight(Color.WHITE);
    private final Rotate rotateX = new Rotate(0, Rotate.X_AXIS);
    private final Rotate rotateY = new Rotate(0, Rotate.Y_AXIS);
    private final Rotate rotateAuto = new Rotate(0, Rotate.Y_AXIS);
    private final SubScene subScene;
    private final Pane labels = new Pane();
    private final Text hud = new Text();
    /** Extra HUD line, e.g. the launch countdown. */
    String countdown = "";
    private final Text table = new Text();
    private final List<Label> nameLabels = new ArrayList<>();
    final List<String> timelineNames = new ArrayList<>();
    final List<Double> timelineTimes = new ArrayList<>();
    double timelineNow;
    private final javafx.scene.canvas.Canvas timelineCanvas = new javafx.scene.canvas.Canvas();
    private final javafx.scene.canvas.Canvas gaugeCanvas = new javafx.scene.canvas.Canvas(180, 46);
    private final javafx.scene.canvas.Canvas zoomCanvas = new javafx.scene.canvas.Canvas(180, 40);
    /** Measured simulated seconds per real second, shown on the gauge. */
    double timeScale;
    private final List<Mass> nodeMasses = new ArrayList<>();
    private double dragX, dragY;

    public Masses() {
        world.getChildren().addAll(new AmbientLight(Color.gray(0.25)), sunLight, bodies, lines, orbits, trailGroup);
        world.getTransforms().addAll(rotateAuto, rotateX, rotateY);
        setView(0, NORTH_UP_PITCH);
        Group root = new Group(world);

        PerspectiveCamera camera = new PerspectiveCamera(true);
        camera.setTranslateZ(-CAMERA_DISTANCE);
        camera.setNearClip(10);
        camera.setFarClip(1e9);
        camera.setFieldOfView(35);

        subScene = new SubScene(root, 610, 620, true, SceneAntialiasing.BALANCED);
        subScene.setFill(Color.BLACK);
        subScene.setCamera(camera);
        subScene.widthProperty().bind(widthProperty());
        subScene.heightProperty().bind(heightProperty());

        Font mono = Font.font("Monospaced", 12);
        hud.setFont(mono);
        hud.setFill(Color.WHITE);
        hud.setTranslateX(10);
        hud.setTranslateY(15);
        table.setFont(mono);
        table.setFill(Color.WHITE);
        table.setTranslateX(10);
        table.setTranslateY(85);
        labels.getChildren().addAll(hud, table, timelineCanvas, gaugeCanvas, zoomCanvas);
        zoomCanvas.translateXProperty().bind(widthProperty().subtract(190));
        zoomCanvas.translateYProperty().bind(heightProperty().subtract(80 + 52 + 46));
        gaugeCanvas.translateXProperty().bind(widthProperty().subtract(190));
        gaugeCanvas.translateYProperty().bind(heightProperty().subtract(80 + 52));
        timelineCanvas.setHeight(80);
        timelineCanvas.widthProperty().bind(widthProperty());
        timelineCanvas.translateYProperty().bind(heightProperty().subtract(80));
        labels.setMouseTransparent(true);

        getChildren().addAll(subScene, labels);
        setStyle("-fx-background-color: black;");

        // Drag to orbit the view around the focused mass.
        setOnMousePressed(e -> {
            dragX = e.getSceneX();
            dragY = e.getSceneY();
        });
        setOnMouseDragged(e -> {
            rotateY.setAngle(rotateY.getAngle() - (e.getSceneX() - dragX) * 0.3);
            rotateX.setAngle(rotateX.getAngle() + (e.getSceneY() - dragY) * 0.3);
            dragX = e.getSceneX();
            dragY = e.getSceneY();
        });
        setOnMouseClicked(e -> {
            if (!e.isStillSincePress() || massList.isEmpty())
                return;
            Bounds b = table.getBoundsInParent();
            if (!b.contains(e.getX(), e.getY()))
                return;
            int lines = massList.size() + 1;
            int row = (int) ((e.getY() - b.getMinY()) / (b.getHeight() / lines)) - 1;
            if (row >= 0 && row < tableOrder.size())
                focus = massList.indexOf(tableOrder.get(row));
        });
        setOnScroll(e -> {
            double f = e.getDeltaY() > 0 ? 1.2 : e.getDeltaY() < 0 ? 1 / 1.2 : 1;
            if (launchCam && camFocus != null)
                camZoom *= f;
            else
                scale *= f;
        });
    }

    public void orbit(double yawDegrees, double pitchDegrees) {
        rotateY.setAngle(rotateY.getAngle() + yawDegrees);
        rotateX.setAngle(rotateX.getAngle() + pitchDegrees);
    }

    public void setView(double yawDegrees, double pitchDegrees) {
        rotateY.setAngle(yawDegrees);
        rotateAuto.setAngle(0);
        rotateX.setAngle(pitchDegrees);
    }

    private void syncNodes() {
        if (nodeMasses.equals(massList))
            return;
        bodies.getChildren().clear();
        labels.getChildren().removeAll(nameLabels);
        nameLabels.clear();
        nodeMasses.clear();
        for (Mass mass : massList) {
            mass.createNode(bodies);
            Label label = new Label(mass.name);
            label.setStyle("-fx-background-color: rgba(0,0,0,0.65); -fx-text-fill: white; -fx-padding: 1 4 1 4; -fx-background-radius: 3;");
            nameLabels.add(label);
            nodeMasses.add(mass);
        }
        labels.getChildren().addAll(nameLabels);
    }

    /** When Earth or Moon is focused, zooms out (never in) just enough to keep a live rocket on screen. */
    public void render() {
        if (massList.isEmpty())
            return;
        double userScale = scale;
        Mass focused = massList.get(Math.min(focus, massList.size() - 1));
        if (focus != lastFocus) {
            launchCam = false;
            lastFocus = focus;
        }
        camFocus = null;
        if (launchCam && setupLaunchCam()) {
            try {
                renderView();
            } finally {
                scale = userScale;
            }
            return;
        }
        if (world.getTransforms().size() == 1)
            world.getTransforms().setAll(rotateAuto, rotateX, rotateY);
        orbits.setVisible(true);
        trailGroup.setVisible(true);
        if (focused.name.equals("Earth") || focused.name.equals("Moon")) {
            double halfView = 0.4 * Math.min(getWidth(), getHeight()) * CAMERA_DISTANCE
                    / ((getHeight() / 2) / Math.tan(Math.toRadians(35) / 2));
            for (Mass m : massList)
                if (m instanceof Rocket && m.pos.distance(focused.pos) > 0)
                    scale = Math.min(scale, halfView / m.pos.distance(focused.pos));
        }
        if (focused.name.equals("Earth") || focused.name.equals("Moon"))
            for (Mass m : massList)
                if (m instanceof Rocket)
                    unhideRocket(focused, m);
        try {
            renderView();
        } finally {
            scale = userScale;
        }
    }

    /** Launch camera: starts 4 miles south of the pad looking at the rocket, then follows 200 m behind it. */
    boolean launchCam;
    private int lastFocus = 3;
    private Point3D camFocus;
    private Point3D launchUp;
    private double launchT0;
    private boolean following;
    private static final double SITE_DISTANCE = 1.0, FOLLOW_DISTANCE = 0.2, FOLLOW_AFTER = 80;

    void startLaunchCam() {
        launchCam = true;
        launchUp = null;
        following = false;
        lastFocus = focus;
    }

    private boolean setupLaunchCam() {
        Rocket rocket = null;
        for (Mass m : massList)
            if (m instanceof Rocket && ((Rocket) m).launchBody != null && !((Rocket) m).crash)
                rocket = (Rocket) m;
        if (rocket == null) {
            if (padRocket == null)
                return false;
            Mass earth = massList.get(padRocket.launchFrom);
            Point3D up = padRocket.siteUp(startTime);
            Point3D axis = Rocket.SPIN_AXIS;
            Point3D north = axis.subtract(up.multiply(axis.dotProduct(up))).normalize();
            Point3D target = earth.pos.add(up.multiply(earth.radius + 0.035));
            Point3D cam = target.subtract(north.multiply(SITE_DISTANCE)).add(up.multiply(0.02));
            applyCam(cam, target, up, north);
            return true;
        }
        Mass body = rocket.launchBody;
        Point3D axis = Rocket.SPIN_AXIS;
        if (launchUp == null) {
            launchUp = rocket.pos.subtract(body.pos).normalize();
            launchT0 = startTime;
        }
        Point3D up = new Rotate(Math.toDegrees(body.rotationSpeed * (startTime - launchT0)), axis).transform(launchUp);
        Point3D north = axis.subtract(up.multiply(axis.dotProduct(up))).normalize();
        Point3D site = body.pos.add(up.multiply(body.radius + 0.02));
        Point3D cam = site.subtract(north.multiply(SITE_DISTANCE));
        Point3D vel = rocket.speed.subtract(body.speed);
        Point3D rocketUp = rocket.pos.subtract(body.pos).normalize();
        if (!following && rocket.pos.distance(site) > FOLLOW_AFTER)
            following = true;
        if (following && vel.magnitude() > 1e-6)
            cam = rocket.pos.subtract(vel.normalize().multiply(FOLLOW_DISTANCE));
        return applyCam(cam, rocket.pos, following ? rocketUp : up, north);
    }

    private double camZoom = 1;

    /** Pad rocket template used to aim the camera at the launch site before liftoff. */
    Rocket padRocket;

    private boolean applyCam(Point3D cam, Point3D target, Point3D upRef, Point3D north) {
        Point3D toRocket = target.subtract(cam);
        double d = toRocket.magnitude();
        if (d < 1e-6)
            return false;
        Point3D z = toRocket.multiply(1 / d);
        Point3D y = upRef.subtract(z.multiply(upRef.dotProduct(z)));
        if (y.magnitude() < 1e-9)
            y = north;
        y = y.normalize().multiply(-1);
        Point3D x = y.crossProduct(z);
        world.getTransforms().setAll(new Affine(
                x.getX(), x.getY(), x.getZ(), 0,
                y.getX(), y.getY(), y.getZ(), 0,
                z.getX(), z.getY(), z.getZ(), 0));
        camFocus = target;
        scale = CAMERA_DISTANCE / d * camZoom;
        orbits.setVisible(false);
        trailGroup.setVisible(false);
        return true;
    }

    /** Turns the view in small steps whenever the focused body hides the rocket. */
    private void unhideRocket(Mass focused, Mass rocket) {
        Point3D v = rocket.pos.subtract(focused.pos).multiply(scale);
        double needed = Math.min(focused.drawRadius(scale) * 1.15, v.magnitude() * 0.9);
        double angle = rotateAuto.getAngle();
        if (screenOffset(v, angle) >= needed || sceneVector(v, angle).getZ() <= 0) {
            unhideDirection = 0;
            return;
        }
        double step = 2;
        if (unhideDirection == 0)
            unhideDirection = screenOffset(v, angle + step) >= screenOffset(v, angle - step) ? 1 : -1;
        rotateAuto.setAngle(angle + unhideDirection * step);
    }

    private int unhideDirection;

    private Point3D sceneVector(Point3D v, double autoYaw) {
        return new Rotate(autoYaw, Rotate.Y_AXIS).transform(rotateX.transform(rotateY.transform(v)));
    }

    private double screenOffset(Point3D v, double autoYaw) {
        Point3D s = sceneVector(v, autoYaw);
        return Math.hypot(s.getX(), s.getY());
    }

    private void drawGauge() {
        javafx.scene.canvas.GraphicsContext g = gaugeCanvas.getGraphicsContext2D();
        double w = gaugeCanvas.getWidth(), h = gaugeCanvas.getHeight();
        g.clearRect(0, 0, w, h);
        g.setFill(Color.rgb(0, 0, 0, 0.65));
        g.fillRoundRect(0, 0, w, h, 8, 8);
        double max = Math.log10(10000);
        double frac = Math.max(0, Math.min(1, Math.log10(Math.max(1, timeScale)) / max));
        g.setStroke(Color.GRAY);
        g.setLineWidth(1);
        for (int d = 0; d <= 4; d++) {
            double x = 10 + (w - 20) * d / max;
            g.strokeLine(x, 28, x, 33);
        }
        g.setFill(Color.gray(0.25));
        g.fillRect(10, 22, w - 20, 5);
        g.setFill(frac > 0.85 ? Color.ORANGE : Color.DEEPSKYBLUE);
        g.fillRect(10, 22, (w - 20) * frac, 5);
        g.setFill(Color.WHITE);
        g.setFont(Font.font("Monospaced", 13));
        g.fillText(String.format("Time x%,.0f", timeScale), 10, 16);
        g.setFont(Font.font("Monospaced", 9));
        g.setFill(Color.LIGHTGRAY);
        g.fillText("1", 8, 43);
        g.fillText("10", 10 + (w - 20) / max - 5, 43);
        g.fillText("100", 10 + 2 * (w - 20) / max - 8, 43);
        g.fillText("1k", 10 + 3 * (w - 20) / max - 5, 43);
        g.fillText("10k", 10 + 4 * (w - 20) / max - 8, 43);
    }

    private void drawZoom() {
        javafx.scene.canvas.GraphicsContext g = zoomCanvas.getGraphicsContext2D();
        double w = zoomCanvas.getWidth(), h = zoomCanvas.getHeight();
        g.clearRect(0, 0, w, h);
        g.setFill(Color.rgb(0, 0, 0, 0.65));
        g.fillRoundRect(0, 0, w, h, 8, 8);
        double focal = (getHeight() / 2) / Math.tan(Math.toRadians(35) / 2);
        double pxPerKm = scale * focal / CAMERA_DISTANCE;
        double km = 1e-3;
        outer:
        for (; km < 1e13; km *= 10)
            for (double s : new double[] {1, 2, 5})
                if (km * s * pxPerKm >= 50) {
                    km *= s;
                    break outer;
                }
        double len = Math.min(w - 20, km * pxPerKm);
        String label = km < 1 ? String.format("%.0f m", km * 1000) : km >= 1e6 ? String.format("%,.0f Mm", km / 1000) : String.format("%,.0f km", km);
        g.setFill(Color.WHITE);
        g.setFont(Font.font("Monospaced", 13));
        g.fillText("Zoom " + label, 10, 16);
        g.setStroke(Color.WHITE);
        g.setLineWidth(2);
        g.strokeLine(10, 30, 10 + len, 30);
        g.strokeLine(10, 25, 10, 35);
        g.strokeLine(10 + len, 25, 10 + len, 35);
    }

    private void renderView() {
        drawZoom();
        drawGauge();
        syncNodes();
        Mass focused = massList.get(Math.min(focus, massList.size() - 1));
        Point3D focusPos = camFocus != null ? camFocus : focused.pos;

        for (Mass mass : massList)
            mass.updateNode(focusPos, scale);

        Mass sun = massList.get(0);
        sunLight.setTranslateX((sun.pos.getX() - focusPos.getX()) * scale);
        sunLight.setTranslateY((sun.pos.getY() - focusPos.getY()) * scale);
        sunLight.setTranslateZ((sun.pos.getZ() - focusPos.getZ()) * scale);

        lines.getChildren().clear();
        if (drawDist)
            for (Mass a : massList)
                for (Mass b : massList)
                    if (a != b)
                        lines.getChildren().add(line(a.node(), b.node()));

        if (camFocus == null)
            updateOrbits(focusPos);

        double focal = (getHeight() / 2) / Math.tan(Math.toRadians(35) / 2);
        double[] sx = new double[massList.size()];
        double[] sy = new double[massList.size()];
        boolean[] shown = new boolean[massList.size()];
        for (int i = 0; i < massList.size(); i++) {
            Mass mass = massList.get(i);
            Point3D inScene = mass.node().localToScene(Point3D.ZERO);
            double depth = inScene.getZ() + CAMERA_DISTANCE;
            shown[i] = depth > 1;
            if (shown[i]) {
                // Perspective projection with the camera centered on the sub-scene (vertical FOV 35 degrees).
                sx[i] = getWidth() / 2 + inScene.getX() * focal / depth;
                sy[i] = getHeight() / 2 + inScene.getY() * focal / depth + mass.drawRadius(scale) * focal / depth + 4;
            }
        }
        // Moons close to their planet on screen are stacked in a column under the planet's label.
        final double labelHeight = 18;
        java.util.Map<Mass, Integer> stacked = new java.util.HashMap<>();
        for (int i = 0; i < massList.size(); i++) {
            Mass mass = massList.get(i);
            Label label = nameLabels.get(i);
            label.setVisible(drawInfo && shown[i]);
            if (!shown[i])
                continue;
            double x = sx[i], y = sy[i];
            Mass primary = primaryOf(mass);
            int pi = primary == null ? -1 : massList.indexOf(primary);
            if (pi >= 0 && primary != massList.get(0) && shown[pi] && Math.hypot(sx[i] - sx[pi], sy[i] - sy[pi]) < 60) {
                int rank = stacked.merge(primary, 1, Integer::sum);
                x = sx[pi];
                y = sy[pi] + rank * labelHeight;
            }
            label.setTranslateX(x - label.prefWidth(-1) / 2);
            label.setTranslateY(y);
        }

        buildTableOrder();
        StringBuilder sb = new StringBuilder();
        if (drawTable)
            sb.append(String.format("  %-12s %-20s %-28s %s%n", "name", "speed vs orbited", "orbit min / max km", "rot"));
        else
            sb.append("  name\n");
        for (Mass mass : tableOrder)
            sb.append(mass == focused ? "> " : "  ")
                    .append(drawTable ? mass.tableRow(primaryOf(mass)) : "  ".repeat(mass.indent) + mass.name).append('\n');
        table.setText(sb.toString());

        hud.setText("Keys: space - pause, R restart, T toggle table, I toggle info, D toggle distance lines, O toggle orbits, 0-9 focus on mass, [ ] or PgUp/PgDn previous/next focus, click a table row to focus, up/down zoom, drag mouse to rotate\n"
                + "Date: " + java.time.LocalDateTime.ofEpochSecond(EPOCH_SECONDS + (long) startTime, 0, java.time.ZoneOffset.UTC)
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " UTC\n"
                + "Delta: " + delta + " Scale: " + scale + (countdown.isEmpty() ? "" : "\n" + countdown));
        lastDraw = System.currentTimeMillis();
        drawTimeline();
    }

    /** SpaceX-webcast style mission timeline: evenly spaced milestones on a bar that fills as T+ advances. */
    private void drawTimeline() {
        javafx.scene.canvas.GraphicsContext g = timelineCanvas.getGraphicsContext2D();
        double w = timelineCanvas.getWidth(), h = timelineCanvas.getHeight();
        g.clearRect(0, 0, w, h);
        int n = timelineTimes.size();
        if (n == 0 || w < 100)
            return;
        g.setFill(Color.color(0, 0, 0, 0.55));
        g.fillRect(0, 0, w, h);
        double x0 = 60, x1 = w - 60, y = 28;
        double pos = 0;
        for (int i = 0; i < n; i++)
            if (timelineNow >= timelineTimes.get(i))
                pos = i + (i + 1 < n ? Math.min(1, (timelineNow - timelineTimes.get(i)) / (timelineTimes.get(i + 1) - timelineTimes.get(i))) : 0);
        if (timelineNow < timelineTimes.get(0))
            pos = -0.01;
        double step = n > 1 ? (x1 - x0) / (n - 1) : 0;
        g.setLineWidth(3);
        g.setStroke(Color.gray(0.4));
        g.strokeLine(x0, y, x1, y);
        g.setStroke(Color.WHITE);
        g.strokeLine(x0, y, x0 + Math.max(0, pos) * step, y);
        g.setFont(Font.font("Monospaced", 11));
        g.setTextAlign(javafx.scene.text.TextAlignment.CENTER);
        for (int i = 0; i < n; i++) {
            double x = x0 + i * step;
            boolean done = timelineNow >= timelineTimes.get(i);
            g.setFill(done ? Color.WHITE : Color.gray(0.5));
            g.fillOval(x - 5, y - 5, 10, 10);
            long t = Math.round(Math.abs(timelineTimes.get(i)));
            String ts = String.format("T+%02d:%02d:%02d", t / 3600, t / 60 % 60, t % 60);
            g.fillText(ts, x, y + 20);
            g.fillText(timelineNames.get(i), x, y + 34);
        }
        long now = Math.round(Math.abs(timelineNow));
        g.setFill(timelineNow < 0 ? Color.ORANGE : Color.WHITE);
        g.setTextAlign(javafx.scene.text.TextAlignment.LEFT);
        g.setFont(Font.font("Monospaced", 14));
        g.fillText(String.format("T%s%02d:%02d:%02d", timelineNow < 0 ? "-" : "+", now / 3600, now / 60 % 60, now % 60), 10, 16);
    }

    private final List<Mass> tableOrder = new ArrayList<>();

    /** Lists each mass directly under the body it orbits, setting its indent level. */
    private void buildTableOrder() {
        tableOrder.clear();
        for (Mass m : massList)
            if (primaryOf(m) == null)
                addWithSatellites(m, 0);
        for (Mass m : massList)
            if (!tableOrder.contains(m))
                addWithSatellites(m, 0);
    }

    private void addWithSatellites(Mass m, int depth) {
        m.indent = depth;
        tableOrder.add(m);
        for (Mass child : massList)
            if (child != m && !tableOrder.contains(child) && primaryOf(child) == m)
                addWithSatellites(child, depth + 1);
    }

    /** The heaviest non-Sun body whose sphere of influence contains the mass, otherwise the Sun. */
    Mass primaryOf(Mass m) {
        Mass sun = massList.get(0);
        Mass best = null;
        double bestSoi = Double.MAX_VALUE;
        for (Mass p : massList) {
            if (p == m || p == sun || p.mass <= m.mass)
                continue;
            Mass parent = null;
            for (Mass q : massList)
                if (q != p && q.mass > p.mass && (parent == null || p.pos.distance(q.pos) < p.pos.distance(parent.pos)))
                    parent = q;
            if (parent == null)
                parent = sun;
            double soi = p.pos.distance(parent.pos) * Math.pow(p.mass / parent.mass, 0.4);
            if (m.pos.distance(p.pos) < soi && soi < bestSoi) {
                best = p;
                bestSoi = soi;
            }
        }
        return best != null ? best : (m == sun ? null : sun);
    }

    /** Dots left behind each mass, stored relative to the body it orbits so they follow that body. */
    private static class Trail {
        Mass primary;
        Point3D last;
        Point3D lastAbs;
        final java.util.ArrayDeque<Point3D> dots = new java.util.ArrayDeque<>();
    }

    private static final int TRAIL_DOTS = 30;
    private static final double TRAIL_STEP = Math.toRadians(6);
    private final java.util.Map<Mass, Trail> trails = new java.util.IdentityHashMap<>();
    private final List<Sphere> trailSpheres = new ArrayList<>();
    private final PhongMaterial trailMaterial = new PhongMaterial(Color.gray(0.85));

    private void updateTrails(Point3D focusPos) {
        trails.keySet().retainAll(massList);
        int used = 0;
        for (Mass m : massList) {
            Mass primary = primaryOf(m);
            if (primary == null)
                continue;
            Trail trail = trails.computeIfAbsent(m, k -> new Trail());
            Point3D rel = m.pos.subtract(primary.pos);
            boolean fixed = m instanceof Rocket;
            if (trail.primary != primary) {
                trail.primary = primary;
                if (!fixed)
                    trail.dots.clear();
                trail.last = rel;
                trail.lastAbs = m.pos;
            }
            if (rel.magnitude() > 0 && trail.last.magnitude() > 0 && rel.angle(trail.last) > Math.toDegrees(TRAIL_STEP)) {
                trail.dots.addLast(fixed ? trail.lastAbs : trail.last);
                if (trail.dots.size() > TRAIL_DOTS)
                    trail.dots.removeFirst();
                trail.last = rel;
                trail.lastAbs = m.pos;
            }
            if (!drawOrbits)
                continue;
            for (Point3D dot : trail.dots) {
                if (used == trailSpheres.size()) {
                    Sphere s = new Sphere(1.1, 3);
                    s.setMaterial(trailMaterial);
                    trailSpheres.add(s);
                    trailGroup.getChildren().add(s);
                }
                Sphere s = trailSpheres.get(used++);
                Point3D p = (fixed ? dot : primary.pos.add(dot)).subtract(focusPos).multiply(scale);
                s.setTranslateX(p.getX());
                s.setTranslateY(p.getY());
                s.setTranslateZ(p.getZ());
            }
        }
        for (int i = 0; i < trailSpheres.size(); i++) {
            boolean vis = i < used;
            if (trailSpheres.get(i).isVisible() != vis)
                trailSpheres.get(i).setVisible(vis);
        }
    }

    /** Draws each mass's current Keplerian path around its primary, starting at the mass and running ahead of it. */
    private void updateOrbits(Point3D focusPos) {
        final int segments = 64;
        int used = 0;
        if (drawOrbits) {
            for (Mass m : massList) {
                Mass primary = primaryOf(m);
                if (primary == null)
                    continue;
                Point3D r = m.pos.subtract(primary.pos);
                Point3D v = m.speed.subtract(primary.speed);
                double d = r.magnitude();
                Point3D h = r.crossProduct(v);
                if (d == 0 || h.magnitude() < 1e-12)
                    continue;
                double mu = 6.67e-20 * (primary.mass + m.mass);
                Point3D eVec = r.multiply(v.dotProduct(v) - mu / d).subtract(v.multiply(r.dotProduct(v))).multiply(1 / mu);
                double e = eVec.magnitude();
                double p = h.dotProduct(h) / mu;
                Point3D eHat = e > 1e-9 ? eVec.multiply(1 / e) : r.multiply(1 / d);
                Point3D qHat = h.normalize().crossProduct(eHat);
                double nu0 = Math.atan2(r.dotProduct(qHat), r.dotProduct(eHat));
                double sweep = 2 * Math.PI;
                if (e >= 1)
                    sweep = Math.max(0, Math.acos(-1 / e) * 0.98 - nu0);
                Point3D center = primary.pos.subtract(focusPos);
                Point3D prev = null;
                for (int k = 0; k <= segments; k++) {
                    double nu = nu0 + sweep * k / segments;
                    double rho = p / (1 + e * Math.cos(nu));
                    if (rho <= 0 || rho > 1e11)
                        break;
                    Point3D pt = center.add(eHat.multiply(rho * Math.cos(nu))).add(qHat.multiply(rho * Math.sin(nu)))
                            .multiply(scale);
                    if (prev != null) {
                        if (used == orbitSegments.size()) {
                            Cylinder c = new Cylinder(0.4, 1, 4);
                            c.setMaterial(orbitMaterial);
                            orbitSegments.add(c);
                            orbits.getChildren().add(c);
                        }
                        placeSegment(orbitSegments.get(used++), prev, pt);
                    }
                    prev = pt;
                }
            }
        }
        for (int i = 0; i < orbitSegments.size(); i++) {
            boolean vis = i < used;
            if (orbitSegments.get(i).isVisible() != vis)
                orbitSegments.get(i).setVisible(vis);
        }
        updateTrails(focusPos);
    }

    private void placeSegment(Cylinder c, Point3D a, Point3D b) {
        Point3D diff = b.subtract(a);
        Point3D mid = a.midpoint(b);
        c.setHeight(diff.magnitude());
        c.setTranslateX(mid.getX());
        c.setTranslateY(mid.getY());
        c.setTranslateZ(mid.getZ());
        Point3D axis = Rotate.Y_AXIS.crossProduct(diff);
        if (c.getTransforms().isEmpty())
            c.getTransforms().add(new Rotate());
        Rotate rot = (Rotate) c.getTransforms().get(0);
        if (axis.magnitude() > 0) {
            rot.setAxis(axis);
            rot.setAngle(Rotate.Y_AXIS.angle(diff));
        } else
            rot.setAngle(0);
    }

    private Cylinder line(javafx.scene.Node from, javafx.scene.Node to) {
        Point3D a = new Point3D(from.getTranslateX(), from.getTranslateY(), from.getTranslateZ());
        Point3D b = new Point3D(to.getTranslateX(), to.getTranslateY(), to.getTranslateZ());
        Point3D diff = b.subtract(a);
        Cylinder cylinder = new Cylinder(0.5, diff.magnitude());
        cylinder.setMaterial(new PhongMaterial(Color.GRAY));
        Point3D mid = a.midpoint(b);
        cylinder.setTranslateX(mid.getX());
        cylinder.setTranslateY(mid.getY());
        cylinder.setTranslateZ(mid.getZ());
        Point3D axis = Rotate.Y_AXIS.crossProduct(diff);
        double angle = Rotate.Y_AXIS.angle(diff);
        if (axis.magnitude() > 0)
            cylinder.getTransforms().add(new Rotate(angle, axis));
        return cylinder;
    }

    public void update() {
        massList.forEach(mass -> mass.accelerate(startTime, massList, delta));
        massList.forEach(mass -> mass.move(delta));
        startTime += delta;
    }
}
