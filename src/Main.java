import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Point3D;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;

public class Main extends Application {

    boolean pause = false;
    Masses planets;
    double startTime = 0;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        planets = new Masses();
        restart();
        BorderPane root = new BorderPane(planets);
        root.setTop(menuBar());
        Scene scene = new Scene(root, 610, 620);
        scene.addEventHandler(KeyEvent.KEY_PRESSED, this::keyPressed);
        stage.setTitle("Gravity");
        stage.setScene(scene);
        stage.show();

        new AnimationTimer() {
            private long last = 0;
            private long logStart = System.nanoTime();
            private long lastLog = 0;
            private int frames, steps;
            private double logSim;
            private String lastStage = "";
            private boolean lastPause, lastAuto = true, lastPending = true;

            @Override
            public void handle(long now) {
                double realDt = last == 0 ? 0.016 : Math.min(0.5, (now - last) / 1e9);
                double rawDt = last == 0 ? 0.016 : (now - last) / 1e9;
                last = now;
                frames++;
                if (!pause) {
                    long end = System.nanoTime() + (autoScale ? 20_000_000L : 10_000_000L);
                    double simStart = planets.startTime;
                    double budget = autoScale ? autoSpeed(realDt) * realDt : Double.POSITIVE_INFINITY;
                    do {
                        steps++;
                        if (autoScale)
                            planets.delta = autoDelta();
                        planets.update();
                        if (launchPending && planets.startTime >= spec.launchSeconds) {
                            launchPending = false;
                            launchRocket(createRocket());
                        }
                        // While an engine burns, advance at most 10x real time so the integration stays accurate.
                        if (engineFiring() && planets.startTime - simStart >= MAX_BURN_SPEED * realDt)
                            break;
                        if (planets.startTime - simStart >= budget)
                            break;
                    } while (System.nanoTime() < end);
                }
                long renderStart = System.nanoTime();
                planets.countdown = countdownText();
                updateTimeline();
                planets.render();
                renderMs += (System.nanoTime() - renderStart) / 1e6;
                if (rawDt > worstFrame)
                    worstFrame = rawDt;
                double inst = pause ? 0 : (planets.startTime - frameStartSim) / Math.max(rawDt, 1e-3);
                smoothScale += (inst - smoothScale) * Math.min(1, rawDt * 3);
                planets.timeScale = smoothScale;
                frameStartSim = planets.startTime;

                String stageNow = stageText();
                if (!stageNow.equals(lastStage)) {
                    log("STAGE " + stageNow);
                    lastStage = stageNow;
                }
                if (pause != lastPause || autoScale != lastAuto || launchPending != lastPending) {
                    log("STATE pause=" + pause + " autoScale=" + autoScale + " launchPending=" + launchPending);
                    lastPause = pause;
                    lastAuto = autoScale;
                    lastPending = launchPending;
                }
                if (now - lastLog >= 1_000_000_000L) {
                    double secs = lastLog == 0 ? 1 : (now - lastLog) / 1e9;
                    log(String.format("t_real=%.1f fps=%.1f worstFrame=%.0fms render=%.1fms/frame steps/frame=%.0f sim=%.1f actualSpeed=%.0fx simSpeed=%.0f delta=%.5f stage=%s",
                            (now - logStart) / 1e9, frames / secs, worstFrame * 1000, renderMs / Math.max(1, frames), steps / (double) Math.max(1, frames),
                            planets.startTime, (planets.startTime - logSim) / secs, simSpeed, planets.delta, stageNow));
                    lastLog = now;
                    frames = 0;
                    steps = 0;
                    renderMs = 0;
                    worstFrame = 0;
                    logSim = planets.startTime;
                }
            }
        }.start();
    }

    private double renderMs, worstFrame, frameStartSim, smoothScale;
    private java.io.PrintWriter logWriter;

    /** Appends a line to gravity-log.txt (project working directory), flushing immediately. */
    void log(String line) {
        try {
            if (logWriter == null) {
                logWriter = new java.io.PrintWriter(new java.io.FileWriter(AppFiles.file("gravity-log.txt"), false), true);
                logWriter.println("java " + System.getProperty("java.version") + " javafx " + System.getProperty("javafx.runtime.version")
                        + " cpus " + Runtime.getRuntime().availableProcessors() + " maxHeap " + Runtime.getRuntime().maxMemory() / 1048576 + "MB");
                try {
                    logWriter.println("pipeline " + Class.forName("com.sun.prism.GraphicsPipeline").getMethod("getPipeline").invoke(null).getClass().getName());
                } catch (Exception | LinkageError ex) {
                    logWriter.println("pipeline unknown: " + ex);
                }
            }
            logWriter.println(String.format("[%tT.%<tL] %s", new java.util.Date(), line));
        } catch (java.io.IOException ignored) {
        }
    }

    private String stageText() {
        for (Mass m : planets.massList)
            if (m instanceof Rocket)
                return ((Rocket) m).stage();
        return "no rocket";
    }

    /** Rocket and flight plan. Launch time is seconds after the sim epoch (2026-10-18 00:00 UTC). */
    RocketSpec spec = tliSpec(TLI_PARAMS);
    /** Saved/loaded configuration text re-applied by restart(); null = built-in defaults. */
    String loadedConfig;
    Stage stage;
    boolean launchPending = true;

    static final double MAX_BURN_SPEED = 10;
    static final double MIN_DELTA = 1e-3, MAX_DELTA = 60;
    double minDelta = MIN_DELTA;
    boolean autoScale = true;

    private void selectPlan() {
        for (Mass m : planets.massList)
            if (m.name.equals("Earth"))
                m.setLaunchSite(spec.latitude, spec.longitude);
        launchPending = !Double.isNaN(spec.launchSeconds) && planets.startTime < spec.launchSeconds && !spec.stages.isEmpty();
        planets.padRocket = launchPending ? createRocket() : null;
        if (launchPending)
            planets.startLaunchCam();
        planTimeline();
    }

    static final double MAX_SIM_SPEED = 10000;
    double simSpeed = 1;

    /**
     * Target simulated seconds per real second: proportional to the rocket's altitude above the nearest planet or
     * moon (slow in orbit, fast in deep space), and slower before a burn or the launch. Changes gradually: eases down at 3/s and up at 2/s (e-folds per real second).
     */
    double autoSpeed(double realDt) {
        double target = MAX_SIM_SPEED;
        if (launchPending)
            target = Math.min(target, Math.max(10, (spec.launchSeconds - planets.startTime) / 2));
        for (Mass m : planets.massList) {
            if (!(m instanceof Rocket) || ((Rocket) m).crash || ((Rocket) m).launchBody == null)
                continue;
            Rocket rk = (Rocket) m;
            double near = Double.MAX_VALUE;
            for (Mass b : planets.massList)
                if (b != m && !(b instanceof Rocket) && !b.name.equals("Sun") && !b.name.equals("ISS"))
                    near = Math.min(near, rk.pos.distance(b.pos) - b.radius);
            target = Math.min(target, Math.max(10, 2 * near));
            target = Math.min(target, Math.max(10, rk.timeToBurn(planets.startTime) / 3));
        }
        double rate = target < simSpeed ? Math.exp(-3 * realDt) : Math.exp(2 * realDt);
        simSpeed = Math.max(1, simSpeed) * rate;
        simSpeed = target < simSpeed ? Math.min(simSpeed, 3 * target) : Math.min(target, simSpeed);
        return simSpeed;
    }

    /** Picks the integration step: tiny around burns, coarse while coasting. */
    double autoDelta() {
        double d = MAX_DELTA;
        if (launchPending)
            d = Math.min(d, Math.max(1e-3, (spec.launchSeconds - planets.startTime) / 100));
        for (Mass m : planets.massList) {
            if (m.name.equals("ISS"))
                d = Math.min(d, 10);
            if (!(m instanceof Rocket) || ((Rocket) m).crash)
                continue;
            Rocket rk = (Rocket) m;
            Mass primary = planets.primaryOf(rk);
            if (primary == null)
                continue;
            d = Math.min(d, 5e-4 * rk.timescale(primary));
            d = Math.min(d, Math.max(minDelta, rk.timeToBurn(planets.startTime) / 50));
            if (rk.captureIndex >= 0 && rk.captureState < 2 && planets.massList.indexOf(primary) == rk.captureIndex)
                d = Math.min(d, 0.5);
            if (rk.firing)
                d = minDelta;
        }
        return Math.max(minDelta, d);
    }

    private boolean engineFiring() {
        for (Mass m : planets.massList)
            if (m instanceof Rocket && ((Rocket) m).firing)
                return true;
        return false;
    }

    private String countdownText() {
        StringBuilder sb = new StringBuilder();
        for (Mass m : planets.massList)
            if (m instanceof Rocket) {
                Rocket rk = (Rocket) m;
                sb.append(rk.name).append(" stage: ").append(rk.stage());
                if (rk.firing)
                    sb.append("  ENGINE BURNING  fuel ").append((long) rk.fuelLevel).append(" kg");
                sb.append('\n');
            }
        sb.append("Rocket plan: ").        append(spec.title).append("  (L: launch now, Edit menu: change)");
        if (launchPending) {
                    long s = (long) Math.max(0, spec.launchSeconds - planets.startTime);
            sb.append(String.format("%nLaunch T-%dd %02d:%02d:%02d", s / 86400, s / 3600 % 24, s / 60 % 60, s % 60));
        }
        return sb.toString();
    }

    void restart() {
        if (loadedConfig != null) {
            ConfigIO.Loaded l = ConfigIO.read(loadedConfig);
            l.spec = spec;
            applyLoaded(l);
            return;
        }
        pause = true;
        while (planets.massList.size() > 0)
            planets.massList.remove(0);
        planets.massList.add(new Mass(new Point3D(0, 0, 0), 10e6F, 2e30F, new Point3D(0, 0, 0), 0, "Sun"));
        planets.massList.add(new Mass(new Point3D(46400137.533, -39448542.118, -7479548.554), 4879F, 0.330e24F, new Point3D(21.926165, 39.391730, 1.208269), 0.000001239580435, "Mercury"));
        planets.massList.add(new Mass(new Point3D(101442453.354, 38031644.422, -5330480.905), 12104F, 4.867e24F, new Point3D(-12.411037, 32.637559, 1.164581), 0.0000002992454696, "Venus"));
        planets.massList.add(new Mass(new Point3D(135908877.737, 61252556.329, -4276.009), 12756F, 5.97e24F, new Point3D(-12.733557, 27.042779, -0.002396), 0.0000729211585, "Earth"));
        planets.massList.add(new Mass(new Point3D(136026104.041, 60866707.023, -27787.109), 3475F, .07346e24F, new Point3D(-11.813741, 27.334411, 0.063586), -0.0000027132, "Moon"));
        planets.massList.add(new Mass(new Point3D(-15169192.202, 235614538.505, 5309566.550), 6792F, 0.642e24F, new Point3D(-23.261577, 0.500750, 0.580867), 0.00007094834358, "Mars"));
        planets.massList.add(new Mass(new Point3D(-536703009.742, 586048828.237, 9573570.434), 142984F, 1898e24F, new Point3D(-9.796520, -8.220879, 0.253353), 0.0001759995884, "Jupiter"));
        planets.massList.add(new Mass(new Point3D(1381633175.804, 278181551.938, -59842046.049), 120536F, 568e24F, new Point3D(-2.441176, 9.444041, -0.066962), 0, "Saturn"));
        // Moons: JPL Horizons heliocentric vectors (2026-Oct-18), diameter in km, mass in kg, spin = tidally locked orbital rate.
        planets.massList.add(new Mass(new Point3D(-15161207.425, 235617442.134, 5305943.269), 22.2, 1.0659e16, new Point3D(-23.824314, 2.558709, 0.974421), 2.28e-4, "Phobos"));
        planets.massList.add(new Mass(new Point3D(-15153614.046, 235598884.590, 5301637.968), 12.4, 1.4762e15, new Point3D(-22.411757, 1.503886, 0.270468), 5.758e-5, "Deimos"));
        planets.massList.add(new Mass(new Point3D(-536477169.808, 585693577.281, 9563952.868), 3643.2, 8.93e22, new Point3D(4.821786, 1.137187, 0.801307), 4.110e-5, "Io"));
        planets.massList.add(new Mass(new Point3D(-537310506.549, 586321644.067, 9569542.996), 3121.6, 4.80e22, new Point3D(-15.395422, -20.875449, -0.240549), 2.048e-5, "Europa"));
        planets.massList.add(new Mass(new Point3D(-537768718.276, 586175552.780, 9562829.676), 5268.2, 1.482e23, new Point3D(-11.067205, -18.988759, -0.176819), 1.016e-5, "Ganymede"));
        planets.massList.add(new Mass(new Point3D(-538064742.953, 584733540.104, 9514046.993), 4820.6, 1.076e23, new Point3D(-4.105619, -14.061679, 0.146998), 4.357e-6, "Callisto"));
        planets.massList.add(new Mass(new Point3D(1382392264.665, 278980002.234, -60329256.870), 5149.5, 1.3452e23, new Point3D(-6.710729, 12.891207, -1.419702), 4.561e-6, "Titan"));
        planets.massList.add(new Mass(new Point3D(1381967373.106, 277809756.452, -59676442.512), 1527.6, 2.306e21, new Point3D(4.081603, 13.971952, -3.076451), 1.610e-5, "Rhea"));
        planets.massList.add(new Mass(new Point3D(1384960527.272, 277330052.948, -60314535.478), 1468.8, 1.806e21, new Point3D(-1.792878, 12.618598, -0.927368), 9.17e-7, "Iapetus"));
        planets.massList.add(new Mass(new Point3D(1381322881.443, 278004201.587, -59719254.309), 1122.8, 1.095e21, new Point3D(3.197615, 1.899796, 3.337746), 2.657e-5, "Dione"));
        // addIss();  // disabled for now

        planets.lastDraw = 0;
        planets.startTime = 0;
        startTime = 0;
        planets.delta = .0001;
        selectPlan();
    }

    private void removeRockets() {
        planets.massList.removeIf(m -> m instanceof Rocket);
        planets.padRocket = null;
    }

    private void clampFocus() {
        if (planets.focus >= planets.massList.size())
            planets.focus = 0;
    }

    void applyMasses(java.util.List<Mass> masses) {
        removeRockets();
        planets.massList.clear();
        planets.massList.addAll(masses);
        clampFocus();
        loadedConfig = ConfigIO.write(planets, spec);
        selectPlan();
    }

    private void applyLoaded(ConfigIO.Loaded l) {
        removeRockets();
        planets.massList.clear();
        planets.massList.addAll(l.masses);
        spec = l.spec;
        planets.startTime = l.time;
        startTime = l.time;
        planets.lastDraw = 0;
        planets.delta = .0001;
        pause = true;
        clampFocus();
        loadedConfig = ConfigIO.write(planets, spec);
        selectPlan();
    }

    void applySpec(RocketSpec c) {
        spec = c;
        boolean inFlight = planets.massList.stream().anyMatch(m -> m instanceof Rocket);
        if (inFlight)
            planTimeline();
        else
            selectPlan();
    }

    private void alert(String msg) {
        new Alert(Alert.AlertType.ERROR, msg).showAndWait();
    }

    private void save() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Save configuration");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Gravity configuration", "*.gravity"));
        File f = fc.showSaveDialog(stage);
        if (f == null)
            return;
        try {
            Files.writeString(f.toPath(), ConfigIO.write(planets, spec), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            alert("Could not save: " + e.getMessage());
        }
    }

    private void load() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Load configuration");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Gravity configuration", "*.gravity"));
        File f = fc.showOpenDialog(stage);
        if (f == null)
            return;
        try {
            applyLoaded(ConfigIO.read(Files.readString(f.toPath(), StandardCharsets.UTF_8)));
        } catch (IOException | RuntimeException e) {
            alert("Could not load: " + e.getMessage());
        }
    }

    private MenuBar menuBar() {
        MenuItem save = new MenuItem("Save...");
        save.setAccelerator(new KeyCodeCombination(KeyCode.S, KeyCombination.CONTROL_DOWN));
        save.setOnAction(e -> save());
        MenuItem load = new MenuItem("Load...");
        load.setAccelerator(new KeyCodeCombination(KeyCode.O, KeyCombination.CONTROL_DOWN));
        load.setOnAction(e -> load());
        MenuItem exit = new MenuItem("Exit");
        exit.setOnAction(e -> Platform.exit());
        Menu file = new Menu("File", null, save, load, new SeparatorMenuItem(), exit);
        MenuItem masses = new MenuItem("Masses...");
        masses.setOnAction(e -> Editors.editMasses(this));
        MenuItem rocket = new MenuItem("Rocket and flight plan...");
        rocket.setOnAction(e -> Editors.editRocket(this));
        return new MenuBar(file, new Menu("Edit", null, masses, rocket));
    }

    /** Builds the rocket described by the current spec. */
    Rocket createRocket() {
        Rocket rocket = newRocket(spec.azimuth, spec.latitude, spec.longitude);
        rocket.name = spec.name;
        rocket.mass = spec.payload;
        for (RocketSpec.Stage s : spec.stages)
            rocket.stages.add(new RocketStage(s.name, s.dry, s.fuel, s.thrust, s.exhaust));
        for (LaunchStep s : spec.steps)
            rocket.launchStepList.add(new LaunchStep(s.end, s.powerLevel, s.pitch, s.yaw, s.relative).named(s.name));
        boolean found = planets.massList.stream().anyMatch(m -> m.name.equals(spec.captureBody));
        rocket.captureIndex = found ? indexOf(spec.captureBody) : -1;
        rocket.captureApoapsis = spec.captureApoapsis;
        return rocket;
    }

    /** Rebuilds the planned mission timeline (T+ seconds) shown at the bottom of the screen. */
    private void planTimeline() {
        planets.timelineNames.clear();
        planets.timelineTimes.clear();
        timelineStage = "";
        if (spec.steps.isEmpty() || spec.stages.isEmpty())
            return;
        Rocket rk = createRocket();
        for (int i = 0; i < rk.launchStepList.size(); i++)
            addEvent(rk.launchStepList.get(i).name, i == 0 ? 0 : rk.launchStepList.get(i - 1).end);
        if (rk.stages.size() > 1)
            addEvent("Stage separation", rk.stages.get(0).fuel / rk.stages.get(0).consumption);
        addEvent("Final burn cutoff", rk.launchStepList.get(rk.launchStepList.size() - 1).end);
        timelineStage = "";
    }

    private String timelineStage = "";

    private void addEvent(String name, double t) {
        int i = 0;
        while (i < planets.timelineTimes.size() && planets.timelineTimes.get(i) <= t)
            i++;
        planets.timelineNames.add(i, name);
        planets.timelineTimes.add(i, t);
    }

    private void updateTimeline() {
        planets.timelineNow = planets.startTime - spec.launchSeconds;
        for (Mass m : planets.massList)
            if (m instanceof Rocket) {
                Rocket rk = (Rocket) m;
                planets.timelineNow = planets.startTime - rk.launchTime;
                if (rk.currentLaunchStep >= rk.launchStepList.size()) {
                    String s = rk.stage().split(" \\[")[0];
                    if (!s.equals(timelineStage)) {
                        timelineStage = s;
                        if (!planets.timelineNames.contains(s))
                            addEvent(s, planets.timelineNow);
                    }
                }
            }
    }

    static RocketSpec tliSpec(double[] p) {
        RocketSpec s = new RocketSpec();
        s.title = "Falcon 9 to lunar orbit from LC-39A";
        s.name = "Falcon 9";
        s.payload = 3000;
        s.azimuth = 90;
        s.latitude = 28.6084;
        s.longitude = -80.6041;
        s.captureBody = "Moon";
        s.captureApoapsis = 20000;
        s.launchSeconds = 31836.83;
        s.stages.add(new RocketSpec.Stage("Stage 1", 25600, 411000, 7607, 3.0));
        s.stages.add(new RocketSpec.Stage("Stage 2", 4000, 107500, 934, 3.41));
        double meco = p[4], tliStart = meco + p[5];
        s.steps.add(new LaunchStep(8, 1, 0, 0, false).named("Liftoff"));
        s.steps.add(new LaunchStep(60, 1, p[0], 0, false).named("Pitch-over"));
        s.steps.add(new LaunchStep(150, 1, p[1], 0, false).named("First-stage ascent"));
        s.steps.add(new LaunchStep(330, 1, p[2], 0, false).named("Powered ascent"));
        s.steps.add(new LaunchStep(meco, 1, p[3], 0, false).named("Orbit insertion"));
        s.steps.add(new LaunchStep(tliStart, 0, 90, 0, true).named("Parking orbit coast"));
        s.steps.add(new LaunchStep(tliStart + p[6], 1, 90, 0, true).named("Trans-lunar injection"));
        return s;
    }

    /**
     * Ascent/TLI parameters of the LC-39A plan (Falcon 9 Block 5: stage 1 9 Merlins 7,607 kN, 3.0 km/s, 411 t propellant;
     * stage 2 934 kN, 3.41 km/s, 107.5 t), found by numeric search.
     * p = {pitch at 60 s, pitch at 150 s, pitch at 330 s, pitch at MECO, MECO time, coast length, TLI burn length};
     * pitches are degrees from vertical and all times are seconds (after launch, except the last two).
     */
    static double[] TLI_PARAMS = {50.408, 62.492, 80.004, 94.877, 470.074, 1162.363, 66.04};

    /**
     * The ISS from the CelesTrak TLE of epoch 2026-10-18 11:12:47 UTC (mean elements, TEME ~ equatorial frame),
     * propagated back to the sim start (00:00 UTC) with the mean motion plus J2 node and perigee drift.
     */
    private void addIss() {
        Mass earth = planets.massList.get(indexOf("Earth"));
        double mu = 6.67e-20 * (earth.mass + 4.2e5);
        double inc = Math.toRadians(51.6327), ecc = 0.00067835;
        double n = 15.48923373 * 2 * Math.PI / 86400;
        double a = Math.cbrt(mu / (n * n));
        double dt = -(11 * 3600 + 12 * 60 + 47.04);
        double p = a * (1 - ecc * ecc);
        double j2n = 1.5 * 1.08263e-3 * n * Math.pow(earth.radius / p, 2);
        double raan = Math.toRadians(50.0298) - j2n * Math.cos(inc) * dt;
        double argp = Math.toRadians(279.2532) + j2n * (2 - 2.5 * Math.pow(Math.sin(inc), 2)) * dt;
        double meanAnomaly = Math.toRadians(80.7689) + n * dt;
        double ea = meanAnomaly;
        for (int i = 0; i < 30; i++)
            ea -= (ea - ecc * Math.sin(ea) - meanAnomaly) / (1 - ecc * Math.cos(ea));
        double xp = a * (Math.cos(ea) - ecc), yp = a * Math.sqrt(1 - ecc * ecc) * Math.sin(ea);
        double k = Math.sqrt(mu * a) / Math.hypot(xp, yp);
        double vx = -k * Math.sin(ea), vy = k * Math.sqrt(1 - ecc * ecc) * Math.cos(ea);
        Point3D pos = Rocket.equatorialToEcliptic(perifocalToEquatorial(xp, yp, raan, inc, argp));
        Point3D vel = Rocket.equatorialToEcliptic(perifocalToEquatorial(vx, vy, raan, inc, argp));
        planets.massList.add(new Mass(earth.pos.add(pos), 0.109, 4.2e5, earth.speed.add(vel), 0, "ISS"));
    }

    private static Point3D perifocalToEquatorial(double x, double y, double raan, double inc, double argp) {
        double cw = Math.cos(argp), sw = Math.sin(argp), co = Math.cos(raan), so = Math.sin(raan);
        double ci = Math.cos(inc), si = Math.sin(inc);
        double xo = x * cw - y * sw, yo = x * sw + y * cw;
        return new Point3D(xo * co - yo * ci * so, xo * so + yo * ci * co, yo * si);
    }

    private int indexOf(String name) {
        for (int i = 0; i < planets.massList.size(); i++)
            if (planets.massList.get(i).name.equals(name))
                return i;
        return 0;
    }

    private Rocket newRocket(double azimuth, double latitude, double longitude) {
        int earth = indexOf("Earth");
        Rocket rocket = new Rocket(Point3D.ZERO, 10, 1000, Point3D.ZERO, 0, "Rocket", earth);
        rocket.engineMaxTrust = 800;
        rocket.fuelLevel = 40000;
        rocket.fuelConsumption = 80;
        rocket.launchLatitude = latitude;
        rocket.launchLongitude = longitude;
        rocket.launchAzimuth = azimuth;
        rocket.j2 = 1.08263e-3;
        rocket.launchStepList = new ArrayList<>();
        return rocket;
    }

    private void launchRocket(Rocket rocket) {
        planets.massList.add(rocket);
        rocket.Launch(planets.startTime, planets);
        planets.startLaunchCam();
    }

    public static void main(String[] args) {
        launch(args);
    }

    public void keyPressed(KeyEvent e) {
        log("KEY " + e.getCode() + " (autoScale=" + autoScale + " pause=" + pause + ")");
        if (e.isShiftDown()) {
            switch (e.getCode()) {
                case LEFT:
                    planets.orbit(-5, 0);
                    return;
                case RIGHT:
                    planets.orbit(5, 0);
                    return;
                case UP:
                    planets.orbit(0, -5);
                    return;
                case DOWN:
                    planets.orbit(0, 5);
                    return;
                default:
                    break;
            }
        }
        switch (e.getCode()) {
            case F1:
                planets.setView(0, 0);
                break;
            case F2:
                planets.setView(0, -90);
                break;
            case F3:
                planets.setView(0, 90);
                break;
            case F4:
                planets.setView(-35, -35);
                break;
            case F5:
                planets.setView(0, Masses.NORTH_UP_PITCH);
                break;
            case SPACE:
                pause = !pause;
                break;
            case T:
                planets.drawTable = !planets.drawTable;
                break;
            case I:
                planets.drawInfo = !planets.drawInfo;
                break;
            case D:
                planets.drawDist = !planets.drawDist;
                break;
            case O:
                planets.drawOrbits = !planets.drawOrbits;
                break;
            case OPEN_BRACKET:
            case PAGE_UP:
                planets.focus = (planets.focus - 1 + planets.massList.size()) % planets.massList.size();
                break;
            case CLOSE_BRACKET:
            case PAGE_DOWN:
                planets.focus = (planets.focus + 1) % planets.massList.size();
                break;
            case DIGIT0:
                planets.focus = 0;
                break;
            case DIGIT1:
                planets.focus = 1;
                break;
            case DIGIT2:
                planets.focus = 2;
                break;
            case DIGIT3:
                planets.focus = 3;
                break;
            case DIGIT4:
                planets.focus = 4;
                break;
            case DIGIT5:
                planets.focus = 5;
                break;
            case DIGIT6:
                planets.focus = 6;
                break;
            case DIGIT7:
                planets.focus = 7;
                break;
            case DIGIT8:
                planets.focus = 8;
                break;
            case DIGIT9:
                planets.focus = 9;
                break;
            case PLUS:
            case EQUALS:
            case ADD:
            case UP:
                planets.scale *= 2;
                break;
            case MINUS:
            case SUBTRACT:
            case DOWN:
                planets.scale /= 2;
                break;
            case RIGHT:
                autoScale = false;
                planets.delta *= 2;
                break;
            case LEFT:
                autoScale = false;
                planets.delta /= 2;
                break;
            case C:
                if (planets.launchCam)
                    planets.launchCam = false;
                else
                    planets.startLaunchCam();
                break;
            case A:
                autoScale = !autoScale;
                break;
            case R:
                restart();
                break;
            case L:
                launchPending = false;
                launchRocket(createRocket());
                break;
            default:
                break;
        }
    }
}
