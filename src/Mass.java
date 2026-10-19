import javafx.geometry.Point3D;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Box;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.Sphere;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.transform.Affine;
import javafx.scene.transform.Rotate;
import lombok.extern.java.Log;

import java.text.DecimalFormat;
import java.util.List;


@Log
public class Mass {
    double radius;
    double mass;
    Double dist;
    Point3D pos;
    Point3D speed;
    String name;

    double rotationSpeed;
    double rotationAngle = 0;

    Sphere sphere;

    public Mass(Point3D pos, double radius, double mass, Point3D speed, double rotationSpeed, String name) {
        this.radius = radius / 2; // callers pass the diameter in km
        this.mass = mass;
        this.pos = pos;
        this.speed = speed;
        this.name = name;
        this.dist = 0D;
        this.rotationSpeed = rotationSpeed;
    }

    protected static final DecimalFormat df = new DecimalFormat("0.00");

    protected Color baseColor() {
        switch (name) {
            case "Sun": return Color.GOLD;
            case "Mercury": return Color.DARKGRAY;
            case "Venus": return Color.BURLYWOOD;
            case "Earth": return Color.DODGERBLUE;
            case "Moon": return Color.LIGHTGRAY;
            case "Mars": return Color.ORANGERED;
            case "Jupiter": return Color.SANDYBROWN;
            case "Saturn": return Color.KHAKI;
            default: return Color.WHITE;
        }
    }

    // Procedural surface texture so the rotation of the sphere is visible.
    private WritableImage createTexture() {
        Color base = baseColor();
        Color dark = base.darker().darker();
        int w = 128, h = 64;
        WritableImage img = new WritableImage(w, h);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                boolean spot = Math.hypot(x - w / 4.0, y - h / 2.0) < 7;
                boolean band = y % 16 < 3;
                img.getPixelWriter().setColor(x, y, spot ? Color.WHITE : band ? dark : base);
            }
        return img;
    }

    private Node view;
    private Affine earthOrientation;

    /** Equirectangular map of the Earth on a sphere built so longitude 0 / north pole orientation is explicit. */
    private Group earthGroup;
    private MeshView sitePatch;
    private double siteLat = Double.NaN, siteLon;

    /** Adds a high resolution patch of the ground around this launch site (Earth only). */
    void setLaunchSite(double lat, double lon) {
        if (lat == siteLat && lon == siteLon)
            return;
        siteLat = lat;
        siteLon = lon;
        if (earthGroup != null)
            refreshPatch();
    }

    private void refreshPatch() {
        if (sitePatch != null)
            earthGroup.getChildren().remove(sitePatch);
        sitePatch = null;
        if (!Double.isNaN(siteLat)) {
            sitePatch = SitePatch.build(siteLat, siteLon);
            earthGroup.getChildren().add(sitePatch);
        }
    }

    private Group createEarthView() {
        java.io.File file = AppFiles.file("images/earth.jpg");
        if (!file.exists())
            return null;
        final int lonDiv = 96, latDiv = 48;
        TriangleMesh mesh = new TriangleMesh();
        for (int i = 0; i <= latDiv; i++) {
            double lat = Math.PI / 2 - Math.PI * i / latDiv;
            for (int j = 0; j <= lonDiv; j++) {
                double lon = -Math.PI + 2 * Math.PI * j / lonDiv;
                mesh.getPoints().addAll((float) (Math.cos(lat) * Math.cos(lon)), (float) (Math.cos(lat) * Math.sin(lon)),
                        (float) Math.sin(lat));
                mesh.getTexCoords().addAll((float) j / lonDiv, (float) i / latDiv);
            }
        }
        for (int i = 0; i < latDiv; i++)
            for (int j = 0; j < lonDiv; j++) {
                int p00 = i * (lonDiv + 1) + j, p01 = p00 + 1, p10 = p00 + lonDiv + 1, p11 = p10 + 1;
                mesh.getFaces().addAll(p00, p00, p10, p10, p01, p01, p01, p01, p10, p10, p11, p11);
            }
        MeshView meshView = new MeshView(mesh);
        meshView.setCullFace(CullFace.NONE);
        meshView.setMaterial(new PhongMaterial(Color.WHITE, new Image(file.toURI().toString()), null, null, null));
        earthOrientation = new Affine();
        earthGroup = new Group(meshView);
        earthGroup.getTransforms().add(earthOrientation);
        sitePatch = null;
        refreshPatch();
        return earthGroup;
    }

    /** Orients the map: longitude 0 faces the Greenwich direction at the current rotation, north is the spin axis. */
    private void orientEarth() {
        double era = Rocket.earthRotationAngle(0) + rotationAngle;
        Point3D x = Rocket.equatorialToEcliptic(new Point3D(Math.cos(era), Math.sin(era), 0));
        Point3D y = Rocket.equatorialToEcliptic(new Point3D(-Math.sin(era), Math.cos(era), 0));
        Point3D z = Rocket.SPIN_AXIS;
        earthOrientation.setToTransform(x.getX(), y.getX(), z.getX(), 0, x.getY(), y.getY(), z.getY(), 0,
                x.getZ(), y.getZ(), z.getZ(), 0);
    }

    /** Simple ISS model: main truss along X, solar-array pairs at both ends, pressurised modules and radiators. */
    private Group createIssModel() {
        PhongMaterial hull = new PhongMaterial(Color.GAINSBORO);
        PhongMaterial panel = new PhongMaterial(Color.web("#1d3f8f"));
        panel.setSpecularColor(Color.LIGHTBLUE);
        PhongMaterial radiator = new PhongMaterial(Color.WHITE);
        Group g = new Group();
        Box truss = new Box(2.0, 0.05, 0.05);
        truss.setMaterial(hull);
        g.getChildren().add(truss);
        for (int side : new int[]{-1, 1})
            for (double x : new double[]{0.62, 0.95}) {
                for (int dz : new int[]{-1, 1}) {
                    Box array = new Box(0.28, 0.01, 0.62);
                    array.setMaterial(panel);
                    array.setTranslateX(side * x);
                    array.setTranslateZ(dz * 0.36);
                    g.getChildren().add(array);
                }
            }
        for (int side : new int[]{-1, 1}) {
            Box rad = new Box(0.25, 0.01, 0.4);
            rad.setMaterial(radiator);
            rad.setTranslateX(side * 0.3);
            rad.setTranslateZ(0.3);
            g.getChildren().add(rad);
        }
        javafx.scene.shape.Cylinder module = new javafx.scene.shape.Cylinder(0.07, 0.9);
        module.setMaterial(hull);
        module.setRotationAxis(Rotate.X_AXIS);
        module.setRotate(90);
        module.setTranslateY(0.07);
        g.getChildren().add(module);
        Box node = new Box(0.2, 0.08, 0.08);
        node.setMaterial(hull);
        node.setTranslateY(0.07);
        g.getChildren().add(node);
        g.setScaleX(0.9);
        g.setScaleY(0.9);
        g.setScaleZ(0.9);
        return new Group(g);
    }

    public void createNode(Group world) {
        if ("ISS".equals(name)) {
            view = createIssModel();
            world.getChildren().add(view);
            return;
        }
        if ("Earth".equals(name)) {
            view = createEarthView();
            if (view != null) {
                world.getChildren().add(view);
                return;
            }
        }
        sphere = new Sphere(1, 48);
        PhongMaterial material = new PhongMaterial(Color.WHITE);
        WritableImage texture = createTexture();
        material.setDiffuseMap(texture);
        if ("Sun".equals(name))
            material.setSelfIlluminationMap(texture);
        sphere.setMaterial(material);
        sphere.setRotationAxis(Rotate.Y_AXIS);
        view = sphere;
        world.getChildren().add(sphere);
    }

    public javafx.scene.Node node() {
        return view;
    }

    public double drawRadius(double scale) {
        return Math.max(8, radius * scale);
    }

    /** Positions the 3D node relative to the focused mass, which keeps float precision near the camera. */
    public void updateNode(Point3D focusPos, double scale) {
        double r = drawRadius(scale);
        view.setScaleX(r);
        view.setScaleY(r);
        view.setScaleZ(r);
        view.setTranslateX((pos.getX() - focusPos.getX()) * scale);
        view.setTranslateY((pos.getY() - focusPos.getY()) * scale);
        view.setTranslateZ((pos.getZ() - focusPos.getZ()) * scale);
        if (earthOrientation != null)
            orientEarth();
        else
            view.setRotate(Math.toDegrees(rotationAngle));
    }

    int indent = 0;

    public String tableRow(Mass primary) {
        Point3D v = primary == null ? speed : speed.subtract(primary.speed);
        return String.format("%-12s %-20s %-28s %s", "  ".repeat(indent) + name,
                df.format(v.magnitude()) + " km/s",
                orbitText(primary), df.format(rotationAngle * 180 / Math.PI));
    }

    /** Min/max distance (km) of the osculating orbit around the primary. */
    private String orbitText(Mass primary) {
        if (primary == null)
            return "-";
        Point3D r = pos.subtract(primary.pos), v = speed.subtract(primary.speed);
        double mu = 6.67e-20 * (primary.mass + mass);
        double d = r.magnitude();
        double a = 1 / (2 / d - v.dotProduct(v) / mu);
        if (a <= 0)
            return "escape";
        double e = Math.sqrt(Math.max(0, 1 - r.crossProduct(v).magnitude() * r.crossProduct(v).magnitude() / (mu * a)));
        return String.format("%.0f / %.0f", a * (1 - e), a * (1 + e));
    }

    public void update(double currentTime, List<Mass> massList, double delta) {
        accelerate(currentTime, massList, delta);
        move(delta);
    }

    /** Updates the speed from gravity. Run for all masses before any of them moves, so every body sees the same positions. */
    public void accelerate(double currentTime, List<Mass> massList, double delta) {
        massList.forEach(mass1 -> {
            if (mass1 != this) {
                double dx = -(pos.getX() - mass1.pos.getX());
                double dy = -(pos.getY() - mass1.pos.getY());
                double dz = -(pos.getZ() - mass1.pos.getZ());
                double dist2 = (Math.pow(dx, 2) + Math.pow(dy, 2) + Math.pow(dz, 2));
                double dist = Math.sqrt(dist2);
                if (mass1.name.equals("Sun"))
                    this.dist = dist;
                double acc = 6.67e-20 * mass1.mass / dist2;
                double accx = dx * (acc / dist);
                double accy = dy * (acc / dist);
                double accz = dz * (acc / dist);

                speed = speed.add(accx * delta, accy * delta, accz * delta);

            }
        });
    }

    public void move(double delta) {
        pos = pos.add(speed.getX() * delta, speed.getY() * delta, speed.getZ() * delta);
        rotationAngle += rotationSpeed * delta;
        rotationAngle = rotationAngle % (2 * Math.PI);
    }

    public Point3D getMin() {
        return new Point3D(pos.getX() - radius, pos.getY() - radius, pos.getZ() - radius);
    }

    public Point3D getMax() {
        return new Point3D(pos.getX() + radius, pos.getY() + radius, pos.getZ() + radius);
    }
}
