import javafx.geometry.Point3D;

import java.awt.*;
import java.awt.geom.Point2D;
import java.util.List;

public class Rocket extends Mass {

    int launchFrom;
    double fuelLevel;
    double engineMaxTrust;
    double fuelConsumption;
    List<LaunchStep> launchStepList;
    int currentLaunchStep = 0;

    Point3D direction;

    double launchTime;

    boolean crash = false;

    public Rocket(Point3D pos, float radius, float mass, Point3D speed, double rotationSpeed, String name, int launchFrom) {
        super(pos, radius, mass, speed, rotationSpeed, name);
        this.launchFrom = launchFrom;
    }

    public void Launch(double currentTime, Masses masses) {
        Mass from = masses.massList.get(launchFrom);
        pos = new Point3D(from.pos.getX() + from.radius, from.pos.getY(), from.pos.getZ());
        speed = new Point3D(from.speed.getX(), from.speed.getY() + Math.tan(from.rotationSpeed) * from.radius, from.speed.getZ());
        currentLaunchStep = 0;
        launchTime = currentTime;
    }

    @Override
    public void draw(Graphics2D g2d, Point2D offset, double scale, List<Mass> massList, int i, boolean drawTable, boolean drawInfo, boolean drawDist) {
        double drawRad = Math.max(8, radius * scale);
        g2d.drawLine((int) (pos.getX() * scale - offset.getX()), (int) (pos.getY() * scale - offset.getY()),
                (int) ((pos.getX() + direction.getX()) * scale - offset.getX()),
                (int) ((pos.getY() + direction.getY()) * scale - offset.getY()));

        if (currentLaunchStep < launchStepList.size()) {
            LaunchStep currentStep = launchStepList.get(currentLaunchStep);
            if (currentStep.powerLevel > 0)
                g2d.drawString(df.format(currentStep.powerLevel), (int) (pos.getX() * scale - offset.getX()), (int) (pos.getY() * scale - offset.getY()));
        }
        if (drawTable) {
            g2d.drawString(name, 10, 65 + i * 15);

            g2d.drawString(df.format(speed.getX()) + ", " + df.format(speed.getY()) + ", " + df.format(speed.getZ()), 80, 65 + i * 15);
            g2d.drawString(df.format(pos.getX()) + ", " + df.format(pos.getY()) + ", " + df.format(pos.getZ()), 200, 65 + i * 15);
            g2d.drawString(df.format(dist - massList.get(launchFrom).radius), 420, 65 + i * 15);
            g2d.drawString(df.format(rotationAngle * 180 / Math.PI), 500, 65 + i * 15);
            g2d.drawString(df.format(direction.getX()) + ", " + df.format(direction.getY()) + ", " + df.format(direction.getZ()), 600, 65 + i * 15);
            g2d.drawString(df.format(currentLaunchStep), 650, 65 + i * 15);
            g2d.drawString(df.format(fuelLevel), 680, 65 + i * 15);
            if (crash) g2d.drawString("CRASH", 0, 65 + i * 15);
        }

        //     super.draw(g2d, offset, scale, massList, i, drawTable, drawInfo, drawDist);
    }

    @Override
    public void update(double currentTime, List<Mass> massList, double delta) {

        if (!crash) {
            if (currentLaunchStep < launchStepList.size()) {
                LaunchStep currentStep = launchStepList.get(currentLaunchStep);
                direction = direction.add(currentStep.direction.getX() * delta,
                        currentStep.direction.getY() * delta,
                        currentStep.direction.getZ() * delta);
                if (fuelLevel > 0) {
                    double acc = engineMaxTrust * currentStep.powerLevel / (mass + fuelLevel);
                    speed = new Point3D(speed.getX() + direction.getX() * acc * delta,
                            speed.getY() + direction.getY() * acc * delta,
                            speed.getZ() + direction.getZ() * acc * delta);
                    fuelLevel -= fuelConsumption * currentStep.powerLevel * delta;
                }
                if (currentTime - launchTime > currentStep.end)
                    currentLaunchStep++;
            }
            super.update(currentTime, massList, delta);
            dist = pos.distance(massList.get(launchFrom).pos);
            if (dist < massList.get(launchFrom).radius)
                crash = true;

        }
    }
}