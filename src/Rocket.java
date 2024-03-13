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

    double direction;

    double launchTime;

    boolean crash = false;

    public Rocket(float x, float y, float radius, float mass, double speedx, double speedy, double rotationSpeed, String name, int launchFrom) {
        super(x, y, radius, mass, speedx, speedy, rotationSpeed, name);
        this.launchFrom = launchFrom;
    }

    public void Launch(double currentTime, Masses masses) {
        Mass from = masses.massList.get(launchFrom);
        pos.setLocation(from.pos.x + from.radius, from.pos.y);
        speed.setLocation(from.speed);
        speed.y += Math.tan(from.rotationSpeed) * from.radius;
        currentLaunchStep = 0;
        launchTime = currentTime;
    }

    @Override
    public void draw(Graphics2D g2d, Point2D offset, double scale, List<Mass> massList, int i, boolean drawTable, boolean drawInfo, boolean drawDist) {
        double drawRad = Math.max(8, radius * scale);
        g2d.drawLine((int) (pos.x * scale - offset.getX()), (int) (pos.y * scale - offset.getY()),
                (int) ((pos.x + drawRad / scale * Math.sin(direction)) * scale - offset.getX()),
                (int) ((pos.y + drawRad / scale * Math.cos(direction)) * scale - offset.getY()));

        if (currentLaunchStep < launchStepList.size()) {
            LaunchStep currentStep = launchStepList.get(currentLaunchStep);
            if (currentStep.powerLevel > 0)
                g2d.drawString(df.format(currentStep.powerLevel), (int) (pos.x * scale - offset.getX()), (int) (pos.y * scale - offset.getY()));
        }
        if (drawTable) {
            g2d.drawString(name, 10, 65 + i * 15);

            g2d.drawString(df.format(speed.x) + ", " + df.format(speed.y), 80, 65 + i * 15);
            g2d.drawString(df.format(pos.x) + ", " + df.format(pos.y), 200, 65 + i * 15);
            g2d.drawString(df.format(dist - massList.get(launchFrom).radius), 420, 65 + i * 15);
            g2d.drawString(df.format(rotationAngle * 180 / Math.PI), 500, 65 + i * 15);
            g2d.drawString(df.format(direction), 600, 65 + i * 15);
            g2d.drawString(df.format(currentLaunchStep), 650, 65 + i * 15);
            g2d.drawString(df.format(fuelLevel), 680, 65 + i * 15);
            if(crash)g2d.drawString("CRASH", 0, 65 + i * 15);
        }

        //     super.draw(g2d, offset, scale, massList, i, drawTable, drawInfo, drawDist);
    }

    @Override
    public void update(double currentTime, List<Mass> massList, double delta) {

        if (!crash) {
            if (currentLaunchStep < launchStepList.size()) {
                LaunchStep currentStep = launchStepList.get(currentLaunchStep);
                direction += currentStep.direction * delta;
                if (fuelLevel > 0) {
                    double acc = engineMaxTrust * currentStep.powerLevel/(mass+fuelLevel);
                    Point2D accDir = new Point2D.Double(Math.sin(direction) * acc, Math.cos(direction) * acc);
                    speed.setLocation(speed.x + accDir.getX() * delta, speed.y + accDir.getY() * delta);
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