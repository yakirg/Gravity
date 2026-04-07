import javafx.geometry.Point3D;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.util.ArrayList;

public class Main extends JFrame implements KeyListener {

    boolean pause = false;
    Masses planets;
    double startTime = 0;

    public Main() {
        addKeyListener(this);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(610, 620);
        planets = new Masses();
        restart();
        add(planets, BorderLayout.CENTER);

    }

    void restart() {
        pause = true;
        while (planets.massList.size() > 0)
            planets.massList.remove(0);
        planets.massList.add(new Mass(new Point3D(0, 0, 0), 10e6F, 2e30F, new Point3D(0, 0, 0), 0, "Sun"));
        planets.massList.add(new Mass(new Point3D(57.9e6F, 0, 0), 4879F, 0.330e24F, new Point3D(0, 47.4F, 0), 0.000001239580435, "Mercury"));
        planets.massList.add(new Mass(new Point3D(108.2e6F, 0, 0), 12104F, 4.867e24F, new Point3D(0, 35F, 0), 0.0000002992454696, "Venus"));
        planets.massList.add(new Mass(new Point3D(149.6e6F, 0, 0), 12756F, 5.97e24F, new Point3D(0, 29.8F, 0), 0.00007292462056, "Earth"));
        planets.massList.add(new Mass(new Point3D(149.984e6F, 0, 0), 3475F, .07346e24F, new Point3D(0, 30.822F, 0), -0.0000027132, "Moon"));
        planets.massList.add(new Mass(new Point3D(228e6F, 0, 0), 6792F, 0.642e24F, new Point3D(0, 24.1F, 0), 0.00007094834358, "Mars"));
        planets.massList.add(new Mass(new Point3D(778.5e6F, 0, 0), 142984F, 1898e24F, new Point3D(0, 13.1F, 0), 0.0001759995884, "Jupiter"));
        planets.massList.add(new Mass(new Point3D(1432e6F, 0, 0), 120536F, 568e24F, new Point3D(0, 9.7, 0), 0, "Saturn"));

        planets.lastDraw = 0;
        startTime = 0;
        planets.delta = .0001;
    }

    public static void main(String[] args) throws InterruptedException {
        Main f = new Main();
        f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        f.setSize(610, 620);
        f.setVisible(true);

        while (true) {
            if (!f.pause)
                f.planets.update();
            Thread.sleep(0);
            f.repaint();
        }
    }

    @Override
    public void keyTyped(KeyEvent e) {

    }

    @Override
    public void keyPressed(KeyEvent e) {
        switch (e.getKeyCode()) {
            case KeyEvent.VK_SPACE:
                pause = !pause;
                break;
            case KeyEvent.VK_T:
                planets.drawTable = !planets.drawTable;
                break;
            case KeyEvent.VK_I:
                planets.drawInfo = !planets.drawInfo;
                break;
            case KeyEvent.VK_D:
                planets.drawDist = !planets.drawDist;
                break;
            case KeyEvent.VK_0:
                planets.focus = 0;
                break;
            case KeyEvent.VK_1:
                planets.focus = 1;
                break;
            case KeyEvent.VK_2:
                planets.focus = 2;
                break;
            case KeyEvent.VK_3:
                planets.focus = 3;
                break;
            case KeyEvent.VK_4:
                planets.focus = 4;
                break;
            case KeyEvent.VK_8:
                planets.focus = 8;
                break;
            case KeyEvent.VK_PLUS:
            case KeyEvent.VK_UP:
                planets.scale *= 2;
                break;
            case KeyEvent.VK_MINUS:
            case KeyEvent.VK_DOWN:
                planets.scale /= 2;
                break;
            case KeyEvent.VK_RIGHT:
                planets.delta *= 2;
                break;
            case KeyEvent.VK_LEFT:
                planets.delta /= 2;
                break;
            case KeyEvent.VK_R:
                restart();
                break;
            case KeyEvent.VK_L:
                Rocket rocket = new Rocket(new Point3D(149.6e6F + 12756, 0, 0), 1000, 100, new Point3D(0, 29.8F, 0), 0, "Rocket",
                        3);
                rocket.engineMaxTrust = 6.5;
                rocket.fuelLevel = 1000;
                rocket.fuelConsumption = 1;
                rocket.direction = new Point3D(0, 1, 0);
                rocket.launchStepList = new ArrayList<>();
                rocket.launchStepList.add(new LaunchStep(80, 1, new Point3D(0, 1, 0)));
                rocket.launchStepList.add(new LaunchStep(300, 1, new Point3D(0, 1, 0)));
                rocket.launchStepList.add(new LaunchStep(800, 1, new Point3D(0, 1, 0)));
                rocket.launchStepList.add(new LaunchStep(7400, 0, new Point3D(0, 1, 0)));
                rocket.launchStepList.add(new LaunchStep(7470, .8, new Point3D(0, 1, 0)));
                planets.massList.add(rocket);
                rocket.Launch(planets.startTime, planets);
                break;
        }
    }

    @Override
    public void keyReleased(KeyEvent e) {

    }
}

