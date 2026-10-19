import javafx.geometry.Point3D;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON save format of the masses, the simulation time and the rocket plan. */
final class ConfigIO {
    static class Loaded {
        double time;
        final List<Mass> masses = new ArrayList<>();
        RocketSpec spec = new RocketSpec();
    }

    private ConfigIO() {
    }

    private static List<Object> vec(Point3D p) {
        return List.of(p.getX(), p.getY(), p.getZ());
    }

    static String write(Masses planets, RocketSpec spec) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", "gravity");
        root.put("version", 1);
        root.put("time", planets.startTime);
        List<Object> masses = new ArrayList<>();
        for (Mass m : planets.massList)
            if (!(m instanceof Rocket)) {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("name", m.name);
                o.put("diameter", m.radius * 2);
                o.put("mass", m.mass);
                o.put("position", vec(m.pos));
                o.put("velocity", vec(m.speed));
                o.put("rotationSpeed", m.rotationSpeed);
                o.put("rotationAngle", m.rotationAngle);
                masses.add(o);
            }
        root.put("masses", masses);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("title", spec.title);
        r.put("name", spec.name);
        r.put("payload", spec.payload);
        r.put("azimuth", spec.azimuth);
        r.put("latitude", spec.latitude);
        r.put("longitude", spec.longitude);
        r.put("captureBody", spec.captureBody);
        r.put("captureApoapsis", spec.captureApoapsis);
        r.put("launchSeconds", spec.launchSeconds);
        List<Object> stages = new ArrayList<>();
        for (RocketSpec.Stage s : spec.stages) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("name", s.name);
            o.put("dryMass", s.dry);
            o.put("fuel", s.fuel);
            o.put("thrust", s.thrust);
            o.put("exhaustSpeed", s.exhaust);
            stages.add(o);
        }
        r.put("stages", stages);
        List<Object> steps = new ArrayList<>();
        for (LaunchStep s : spec.steps) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("name", s.name);
            o.put("end", s.end);
            o.put("power", s.powerLevel);
            o.put("pitch", s.pitch);
            o.put("yaw", s.yaw);
            o.put("relative", s.relative);
            steps.add(o);
        }
        r.put("steps", steps);
        root.put("rocket", r);
        return Json.write(root);
    }

    static Loaded read(String text) {
        Loaded l = new Loaded();
        try {
            Map<String, Object> root = obj(Json.parse(text));
            if (!"gravity".equals(root.get("format")))
                throw new IllegalArgumentException("Not a Gravity configuration file.");
            l.time = num(root, "time", 0);
            for (Object o : list(root.get("masses"))) {
                Map<String, Object> m = obj(o);
                double[] p = vec(m.get("position")), v = vec(m.get("velocity"));
                Mass mass = new Mass(new Point3D(p[0], p[1], p[2]), req(m, "diameter"), req(m, "mass"), new Point3D(v[0], v[1], v[2]),
                        num(m, "rotationSpeed", 0), String.valueOf(m.get("name")));
                mass.rotationAngle = num(m, "rotationAngle", 0);
                l.masses.add(mass);
            }
            if (root.get("rocket") != null) {
                Map<String, Object> r = obj(root.get("rocket"));
                RocketSpec s = l.spec;
                s.title = str(r, "title");
                s.name = str(r, "name");
                s.payload = req(r, "payload");
                s.azimuth = req(r, "azimuth");
                s.latitude = req(r, "latitude");
                s.longitude = req(r, "longitude");
                s.captureBody = str(r, "captureBody");
                s.captureApoapsis = num(r, "captureApoapsis", 20000);
                s.launchSeconds = req(r, "launchSeconds");
                for (Object o : list(r.get("stages"))) {
                    Map<String, Object> m = obj(o);
                    s.stages.add(new RocketSpec.Stage(str(m, "name"), req(m, "dryMass"), req(m, "fuel"), req(m, "thrust"), req(m, "exhaustSpeed")));
                }
                for (Object o : list(r.get("steps"))) {
                    Map<String, Object> m = obj(o);
                    s.steps.add(new LaunchStep(req(m, "end"), req(m, "power"), req(m, "pitch"), num(m, "yaw", 0),
                            Boolean.TRUE.equals(m.get("relative"))).named(str(m, "name")));
                }
            }
        } catch (ClassCastException | IndexOutOfBoundsException | NumberFormatException e) {
            throw new IllegalArgumentException("Malformed configuration: " + e.getMessage(), e);
        }
        if (l.masses.isEmpty())
            throw new IllegalArgumentException("The file contains no masses.");
        String problem = l.spec.validate();
        if (problem != null)
            throw new IllegalArgumentException(problem);
        return l;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> obj(Object o) {
        if (!(o instanceof Map))
            throw new IllegalArgumentException("Expected a JSON object.");
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o) {
        if (o == null)
            return List.of();
        if (!(o instanceof List))
            throw new IllegalArgumentException("Expected a JSON array.");
        return (List<Object>) o;
    }

    private static double[] vec(Object o) {
        List<Object> l = list(o);
        if (l.size() != 3)
            throw new IllegalArgumentException("Vectors need 3 numbers.");
        return new double[]{((Number) l.get(0)).doubleValue(), ((Number) l.get(1)).doubleValue(), ((Number) l.get(2)).doubleValue()};
    }

    private static double req(Map<String, Object> m, String k) {
        if (!(m.get(k) instanceof Number n))
            throw new IllegalArgumentException("Missing number '" + k + "'.");
        return n.doubleValue();
    }

    private static double num(Map<String, Object> m, String k, double def) {
        return m.get(k) instanceof Number n ? n.doubleValue() : def;
    }

    private static String str(Map<String, Object> m, String k) {
        Object o = m.get(k);
        return o == null ? "" : o.toString();
    }
}
