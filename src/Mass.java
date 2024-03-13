import lombok.extern.java.Log;

import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.text.DecimalFormat;
import java.util.List;


@Log
public class Mass {
    double radius;
    double mass;
    Double dist;
    Point2D.Double pos;
    Point2D.Double speed;
    String name;

    double rotationSpeed;
    double rotationAngle = 0;

    public Mass(double x, double y, double radius, double mass, double speedx, double speedy, double rotationSpeed, String name) {
        this.radius = radius;
        this.mass = mass;
        this.pos = new Point2D.Double(x, y);
        this.speed = new Point2D.Double(speedx, speedy);
        this.name = name;
        this.dist = 0D;
        this.rotationSpeed = rotationSpeed;
    }

    protected static final DecimalFormat df = new DecimalFormat("0.00");

    public void draw(Graphics2D g2d, Point2D offset, double scale, List<Mass> massList, int i, boolean drawTable, boolean drawInfo, boolean drawDist) {
        double drawRad = Math.max(8, radius * scale);
        Shape circ = new Ellipse2D.Double((pos.getX()) * scale - drawRad - offset.getX(),
                (pos.getY()) * scale - drawRad - offset.getY(),
                2 * drawRad, 2 * drawRad);

        g2d.draw(circ);

        g2d.drawLine((int) (pos.x * scale - offset.getX()), (int) (pos.y * scale - offset.getY()),
                (int) ((pos.x + drawRad / scale * Math.sin(rotationAngle)) * scale - offset.getX()),
                (int) ((pos.y + drawRad / scale * Math.cos(rotationAngle)) * scale - offset.getY()));

        if (drawTable) {
            g2d.drawString(name, 10, 65 + i * 15);
            g2d.drawString(df.format(speed.x) + ", " + df.format(speed.y), 80, 65 + i * 15);
            g2d.drawString(df.format(pos.x) + ", " + df.format(pos.y), 200, 65 + i * 15);
            g2d.drawString(df.format(dist), 420, 65 + i * 15);
            g2d.drawString(df.format(rotationAngle*180/Math.PI), 500, 65 + i * 15);
        }
        if (drawInfo) {
            g2d.drawString(name, (int) (pos.x * scale - offset.getX()), (int) (pos.y * scale - offset.getY()) + 20);
//            g2d.drawString(speed.toString(), (int) (pos.x * scale - offset.getX()), (int) (pos.y * scale - offset.getY()) + 30);
//            g2d.drawString(pos.toString(), (int) (pos.x * scale - offset.getX()), (int) (pos.y * scale - offset.getY()) + 40);
//            g2d.drawString(dist.toString(), (int) (pos.x * scale - offset.getX()), (int) (pos.y * scale - offset.getY()) + 50);
        }
        if (drawDist)
            massList.forEach(mass1 -> {
                if (mass1 != this) {
                    g2d.drawLine((int) (pos.x * scale - offset.getX()), (int) (pos.y * scale - offset.getY()),
                            (int) (mass1.pos.x * scale - offset.getX()), (int) (mass1.pos.y * scale - offset.getY()));
                }
            });
    }

    public void update(double currentTime, List<Mass> massList, double delta) {
        massList.forEach(mass1 -> {
            if (mass1 != this) {
                double dx = -(pos.getX() - mass1.pos.getX());
                double dy = -(pos.getY() - mass1.pos.getY());
                double dist2 = (Math.pow(dx, 2) + Math.pow(dy, 2));
                double dist = Math.sqrt(dist2);
                if (mass1.name.equals("Sun"))
                    this.dist = dist;
                double acc = 6.67e-20 * mass1.mass / dist2;
                double accx = dx * (acc / dist);
                double accy = dy * (acc / dist);

                speed.setLocation(speed.getX() + accx * delta, speed.getY() + accy * delta);

            }
        });
        pos.setLocation(pos.getX() + speed.getX() * delta,
                pos.getY() + speed.getY() * delta);
        rotationAngle += rotationSpeed * delta;
        rotationAngle = rotationAngle % (2*Math.PI);
    }

    public Point2D getMin() {
        return new Point2D.Double(pos.getX() - radius, pos.getY() - radius);
    }

    public Point2D getMax() {
        return new Point2D.Double(pos.getX() + radius, pos.getY() + radius);
    }
}
