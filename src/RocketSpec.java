import java.util.ArrayList;
import java.util.List;

/** Editable, savable description of the rocket and its flight plan. Mass in kg, thrust in kN, exhaust speed in km/s. */
public class RocketSpec {
    static class Stage {
        String name;
        double dry, fuel, thrust, exhaust;

        Stage(String name, double dry, double fuel, double thrust, double exhaust) {
            this.name = name;
            this.dry = dry;
            this.fuel = fuel;
            this.thrust = thrust;
            this.exhaust = exhaust;
        }
    }

    String title = "Rocket";
    String name = "Rocket";
    /** Payload mass in kg. */
    double payload = 1000;
    double azimuth = 90, latitude = 0, longitude = 0;
    /** Name of the body to enter orbit around after the last flight step (empty = none). */
    String captureBody = "";
    double captureApoapsis = 20000;
    /** Launch time in seconds after the simulation start. */
    double launchSeconds = 0;
    final List<Stage> stages = new ArrayList<>();
    final List<LaunchStep> steps = new ArrayList<>();

    RocketSpec copy() {
        RocketSpec c = new RocketSpec();
        c.title = title;
        c.name = name;
        c.payload = payload;
        c.azimuth = azimuth;
        c.latitude = latitude;
        c.longitude = longitude;
        c.captureBody = captureBody;
        c.captureApoapsis = captureApoapsis;
        c.launchSeconds = launchSeconds;
        for (Stage s : stages)
            c.stages.add(new Stage(s.name, s.dry, s.fuel, s.thrust, s.exhaust));
        for (LaunchStep s : steps)
            c.steps.add(new LaunchStep(s.end, s.powerLevel, s.pitch, s.yaw, s.relative).named(s.name));
        return c;
    }

    /** Returns a description of the first problem found, or null. */
    String validate() {
        double last = -1;
        for (LaunchStep s : steps) {
            if (s.end <= last)
                return "Flight step end times must be increasing (" + s.name + ").";
            if (s.powerLevel < 0 || s.powerLevel > 1)
                return "Power must be between 0 and 1 (" + s.name + ").";
            last = s.end;
        }
        for (Stage s : stages)
            if (s.exhaust <= 0 || s.thrust < 0 || s.dry < 0 || s.fuel < 0)
                return "Stage values must be positive, exhaust speed above 0 (" + s.name + ").";
        return null;
    }
}
