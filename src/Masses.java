

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;

import static java.lang.Math.max;
import static java.lang.Math.min;

public class Masses extends JPanel {

    List<Mass> massList = new ArrayList<>();
    long lastDraw = 0;
    double startTime = 0;

    int focus = 3;
    boolean drawTable = true;
    boolean drawInfo = true;
    boolean drawDist = false;
    Point2D offset = new Point2D.Float(-300, -300);
    double scale = 0.00402F;
    double delta = 1;

    public void paintComponent(Graphics g) {
        Graphics2D g2d = (Graphics2D) g;
        g.setColor(Color.black);
        Point2D min = massList.get(0).getMin();
        Point2D max = massList.get(0).getMax();
        massList.forEach(mass -> {
            Point2D massMin = mass.getMin();
            if (massMin.getX() < min.getX())
                min.setLocation(massMin.getX(), min.getY());
            if (massMin.getY() < min.getY())
                min.setLocation(min.getX(), massMin.getY());
            Point2D massMax = mass.getMax();
            if (massMax.getX() > max.getX())
                max.setLocation(massMax.getX(), max.getY());
            if (massMax.getY() > max.getY())
                max.setLocation(max.getX(), massMax.getY());
        });
//        Point2D offset = new Point2D.Float(-300, -300);
//        float scale = .0000005F;
//        float scale = min((float) (600 / (max.getX() - min.getX())), (float) (600 / (max.getY() - min.getY())));
//        Point2D offset = new Point2D.Float((float) min.getX() * scale , (float) min.getY() * scale );

        offset.setLocation(massList.get(focus).pos.x * scale - g.getClipBounds().getCenterX(), massList.get(focus).pos.y * scale - g.getClipBounds().getCenterY());

        if (drawTable) {
            g2d.drawString("name", 10, 50);

            g2d.drawString("speed", 80, 50);
            g2d.drawString("pos", 200, 50);
            g2d.drawString("dist", 420, 50);
        }
        for (int i = 0; i < massList.size(); i++) {
            Mass mass = massList.get(i);
            mass.draw(g2d, offset, scale, massList, i, drawTable, drawInfo, drawDist);
        }
        long fps = 1000 / (System.currentTimeMillis() - lastDraw);
        //    g2d.drawString("Fps: "+fps, 30, 30);
        g2d.drawString("Keys: space - pause, R restart, T toggle table, I toggle info, D toddle distance lines, 0-4 focus on mass 0-4, up arrow zoom in, down arrow zoom out", 30, 10);
        g2d.drawString(String.format("Time: %1.3f Days: %d ", startTime, (int) ((startTime) / 86400)), 30, 20);
        g2d.drawString(String.format("Years: %d", (int) ((startTime) / 86400 / 365)), 30, 30);
        g2d.drawString("Delta: " + delta + " Scale: " + scale, 30, 40);
        lastDraw = System.currentTimeMillis();

    }

    public void update() {
        massList.forEach(mass -> mass.update(startTime, massList, delta));
        startTime += delta;
    }
}

