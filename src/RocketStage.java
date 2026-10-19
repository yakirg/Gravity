/** One stage of a multi-stage rocket. Mass in kg, thrust in kN (kg*km/s^2), consumption in kg/s. */
public class RocketStage {
    final String name;
    final double dryMass;
    final double fuel;
    final double thrust;
    final double consumption;

    RocketStage(String name, double dryMass, double fuel, double thrust, double exhaustSpeed) {
        this.name = name;
        this.dryMass = dryMass;
        this.fuel = fuel;
        this.thrust = thrust;
        this.consumption = thrust / exhaustSpeed;
    }
}
