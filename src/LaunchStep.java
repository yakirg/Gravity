import lombok.Data;

/**
 * A flight waypoint: pitch and yaw are interpolated linearly from the previous waypoint.
 * end is seconds since launch, powerLevel the throttle (0-1), and pitch the angle in degrees from local vertical (0)
 * toward the horizon (90) reached at the end of the step.
 * The horizontal heading is the launch azimuth plus yaw when relative is false, otherwise the direction of
 * motion relative to the launch body rotated counter-clockwise (seen from above) by yaw degrees.
 */
@Data
public class LaunchStep {
    double end;

    double powerLevel;
    double pitch;
    double yaw;
    boolean relative;
    /** Flight stage name shown while this step is active. */
    String name = "";

    public LaunchStep(double end, double powerLevel, double pitch, double yaw, boolean relative) {
        this.end = end;
        this.powerLevel = powerLevel;
        this.pitch = pitch;
        this.yaw = yaw;
        this.relative = relative;
    }

    public LaunchStep(double end, double powerLevel, double pitch) {
        this(end, powerLevel, pitch, 0, false);
    }

    public LaunchStep named(String name) {
        this.name = name;
        return this;
    }
}
