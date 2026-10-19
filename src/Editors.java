import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Point3D;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Dialogs of the Edit menu. */
final class Editors {
    private Editors() {
    }

    record Col<T>(String title, Function<T, String> get, BiConsumer<T, String> set, double width) {
    }

    static double num(String s, double old) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return old;
        }
    }

    private static String str(double d) {
        return Double.toString(d);
    }

    private static <T> TableView<T> table(ObservableList<T> items, List<Col<T>> cols) {
        TableView<T> t = new TableView<>(items);
        t.setEditable(true);
        for (Col<T> c : cols) {
            TableColumn<T, String> tc = new TableColumn<>(c.title());
            tc.setCellValueFactory(cd -> new SimpleStringProperty(c.get().apply(cd.getValue())));
            tc.setCellFactory(TextFieldTableCell.forTableColumn());
            tc.setOnEditCommit(e -> {
                c.set().accept(e.getRowValue(), e.getNewValue());
                t.refresh();
            });
            tc.setPrefWidth(c.width());
            t.getColumns().add(tc);
        }
        return t;
    }

    private static <T> HBox buttons(TableView<T> t, ObservableList<T> rows, Supplier<T> create) {
        Button add = new Button("Add");
        add.setOnAction(e -> rows.add(create.get()));
        Button remove = new Button("Remove selected");
        remove.setOnAction(e -> rows.removeAll(new ArrayList<>(t.getSelectionModel().getSelectedItems())));
        return new HBox(8, add, remove);
    }

    /** Shows the dialog; returns true if OK was pressed and validate() returned no problem. */
    private static boolean show(Main main, String title, javafx.scene.Node content, Supplier<String> validate) {
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle(title);
        d.setResizable(true);
        if (main.stage != null)
            d.initOwner(main.stage);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        d.getDialogPane().setContent(content);
        d.getDialogPane().lookupButton(ButtonType.OK).addEventFilter(ActionEvent.ACTION, ev -> {
            String problem = validate.get();
            if (problem != null) {
                new Alert(Alert.AlertType.WARNING, problem).showAndWait();
                ev.consume();
            }
        });
        return d.showAndWait().filter(b -> b == ButtonType.OK).isPresent();
    }

    static class MassRow {
        String name = "New mass";
        double diameter = 1, mass = 1, px, py, pz, vx, vy, vz, spin, rot;

        MassRow() {
        }

        MassRow(Mass m) {
            name = m.name;
            diameter = m.radius * 2;
            mass = m.mass;
            px = m.pos.getX();
            py = m.pos.getY();
            pz = m.pos.getZ();
            vx = m.speed.getX();
            vy = m.speed.getY();
            vz = m.speed.getZ();
            spin = m.rotationSpeed;
            rot = m.rotationAngle;
        }

        Mass toMass() {
            Mass m = new Mass(new Point3D(px, py, pz), diameter, mass, new Point3D(vx, vy, vz), spin, name);
            m.rotationAngle = rot;
            return m;
        }
    }

    static void editMasses(Main main) {
        ObservableList<MassRow> rows = FXCollections.observableArrayList();
        for (Mass m : main.planets.massList)
            if (!(m instanceof Rocket))
                rows.add(new MassRow(m));
        List<Col<MassRow>> cols = List.of(
                new Col<>("Name", r -> r.name, (r, s) -> r.name = s.trim(), 100),
                new Col<>("Diameter km", r -> str(r.diameter), (r, s) -> r.diameter = num(s, r.diameter), 90),
                new Col<>("Mass kg", r -> str(r.mass), (r, s) -> r.mass = num(s, r.mass), 90),
                new Col<>("X km", r -> str(r.px), (r, s) -> r.px = num(s, r.px), 100),
                new Col<>("Y km", r -> str(r.py), (r, s) -> r.py = num(s, r.py), 100),
                new Col<>("Z km", r -> str(r.pz), (r, s) -> r.pz = num(s, r.pz), 90),
                new Col<>("Vx km/s", r -> str(r.vx), (r, s) -> r.vx = num(s, r.vx), 90),
                new Col<>("Vy km/s", r -> str(r.vy), (r, s) -> r.vy = num(s, r.vy), 90),
                new Col<>("Vz km/s", r -> str(r.vz), (r, s) -> r.vz = num(s, r.vz), 90),
                new Col<>("Spin rad/s", r -> str(r.spin), (r, s) -> r.spin = num(s, r.spin), 90));
        TableView<MassRow> t = table(rows, cols);
        t.setPrefSize(1000, 420);
        Label hint = new Label("Double-click a cell to edit. Positions and velocities are the current state (heliocentric ecliptic). "
                + "The first row should be the Sun; keep the names Earth and Moon for the rocket plan. "
                + "Applying removes any rocket in flight.");
        hint.setWrapText(true);
        VBox box = new VBox(8, hint, t, buttons(t, rows, MassRow::new));
        box.setPadding(new Insets(4));
        boolean ok = show(main, "Edit masses", box, () -> {
            if (rows.isEmpty())
                return "At least one mass is required.";
            for (MassRow r : rows)
                if (r.name.isEmpty() || r.diameter <= 0 || r.mass <= 0)
                    return "Every mass needs a name, a positive diameter and a positive mass.";
            return null;
        });
        if (ok) {
            List<Mass> masses = new ArrayList<>();
            for (MassRow r : rows)
                masses.add(r.toMass());
            main.applyMasses(masses);
        }
    }

    /** World map dialog: drag to pan, scroll to zoom, click to choose. Returns {lat, lon} or null. */
    static double[] pickLocation(Main main, double lat0, double lon0) {
        java.io.File file = AppFiles.file("images/earth.jpg");
        if (!file.exists()) {
            new Alert(Alert.AlertType.ERROR, "images/earth.jpg not found.").showAndWait();
            return null;
        }
        javafx.scene.image.Image img = new javafx.scene.image.Image(file.toURI().toString());
        double iw = img.getWidth(), ih = img.getHeight();
        javafx.scene.canvas.Canvas canvas = new javafx.scene.canvas.Canvas(900, 500);
        double[] st = {Math.max(900 / iw, 500 / ih) * 2, 0, 0};
        double[] sel = {lat0, lon0};
        st[1] = 450 - (lon0 + 180) / 360 * iw * st[0];
        st[2] = 250 - (90 - lat0) / 180 * ih * st[0];
        Label info = new Label();
        Runnable draw = () -> {
            double min = Math.max(canvas.getWidth() / iw, canvas.getHeight() / ih);
            st[0] = Math.max(min, Math.min(40, st[0]));
            st[1] = Math.min(0, Math.max(canvas.getWidth() - iw * st[0], st[1]));
            st[2] = Math.min(0, Math.max(canvas.getHeight() - ih * st[0], st[2]));
            javafx.scene.canvas.GraphicsContext g = canvas.getGraphicsContext2D();
            g.setFill(Color.BLACK);
            g.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
            g.drawImage(img, 0, 0, iw, ih, st[1], st[2], iw * st[0], ih * st[0]);
            double mx = st[1] + (sel[1] + 180) / 360 * iw * st[0], my = st[2] + (90 - sel[0]) / 180 * ih * st[0];
            g.setStroke(Color.RED);
            g.setLineWidth(2);
            g.strokeOval(mx - 8, my - 8, 16, 16);
            g.strokeLine(mx - 14, my, mx + 14, my);
            g.strokeLine(mx, my - 14, mx, my + 14);
            info.setText(String.format("Latitude %.4f   Longitude %.4f", sel[0], sel[1]));
        };
        double[] press = new double[3];
        canvas.setOnMousePressed(e -> {
            press[0] = e.getX();
            press[1] = e.getY();
            press[2] = 0;
        });
        canvas.setOnMouseDragged(e -> {
            st[1] += e.getX() - press[0];
            st[2] += e.getY() - press[1];
            press[2] += Math.abs(e.getX() - press[0]) + Math.abs(e.getY() - press[1]);
            press[0] = e.getX();
            press[1] = e.getY();
            draw.run();
        });
        canvas.setOnMouseReleased(e -> {
            if (press[2] < 4) {
                sel[1] = (e.getX() - st[1]) / (iw * st[0]) * 360 - 180;
                sel[0] = 90 - (e.getY() - st[2]) / (ih * st[0]) * 180;
                draw.run();
            }
        });
        canvas.setOnScroll(e -> {
            double f = Math.pow(1.002, e.getDeltaY());
            double old = st[0];
            st[0] *= f;
            double applied = Math.max(Math.max(canvas.getWidth() / iw, canvas.getHeight() / ih), Math.min(40, st[0])) / old;
            st[1] = e.getX() - (e.getX() - st[1]) * applied;
            st[2] = e.getY() - (e.getY() - st[2]) * applied;
            draw.run();
        });
        draw.run();
        VBox box = new VBox(6, new Label("Drag to pan, scroll to zoom, click to select the launch site."), canvas, info);
        boolean ok = show(main, "Select launch location", box, () -> null);
        return ok ? new double[]{Math.round(sel[0] * 1e4) / 1e4, Math.round(sel[1] * 1e4) / 1e4} : null;
    }

    static void editRocket(Main main) {
        RocketSpec c = main.spec.copy();
        TextField title = new TextField(c.title), name = new TextField(c.name), payload = new TextField(str(c.payload)),
                azimuth = new TextField(str(c.azimuth)), lat = new TextField(str(c.latitude)), lon = new TextField(str(c.longitude)),
                capture = new TextField(c.captureBody), apo = new TextField(str(c.captureApoapsis)),
                launch = new TextField(str(c.launchSeconds));
        GridPane g = new GridPane();
        g.setHgap(8);
        g.setVgap(4);
        String[] labels = {"Plan title", "Rocket name", "Payload kg", "Launch azimuth deg (90 = east)", "Launch latitude deg",
                "Launch longitude deg", "Capture body (empty = none)", "Capture apoapsis km", "Launch time s after start"};
        TextField[] fields = {title, name, payload, azimuth, lat, lon, capture, apo, launch};
        for (int i = 0; i < fields.length; i++) {
            g.add(new Label(labels[i]), (i / 5) * 2, i % 5);
            g.add(fields[i], (i / 5) * 2 + 1, i % 5);
        }
        Button pick = new Button("Select location on map...");
        pick.setOnAction(e -> {
            double[] p = pickLocation(main, num(lat.getText(), c.latitude), num(lon.getText(), c.longitude));
            if (p != null) {
                lat.setText(str(p[0]));
                lon.setText(str(p[1]));
            }
        });
        g.add(pick, 3, 4);

        ObservableList<RocketSpec.Stage> stageRows = FXCollections.observableArrayList(c.stages);
        TableView<RocketSpec.Stage> stageTable = table(stageRows, List.of(
                new Col<>("Stage", s -> s.name, (s, v) -> s.name = v.trim(), 120),
                new Col<>("Dry mass kg", s -> str(s.dry), (s, v) -> s.dry = num(v, s.dry), 110),
                new Col<>("Fuel kg", s -> str(s.fuel), (s, v) -> s.fuel = num(v, s.fuel), 110),
                new Col<>("Thrust kN", s -> str(s.thrust), (s, v) -> s.thrust = num(v, s.thrust), 110),
                new Col<>("Exhaust km/s", s -> str(s.exhaust), (s, v) -> s.exhaust = num(v, s.exhaust), 110)));
        stageTable.setPrefHeight(130);

        ObservableList<LaunchStep> stepRows = FXCollections.observableArrayList(c.steps);
        TableView<LaunchStep> stepTable = table(stepRows, List.of(
                new Col<>("Flight stage", s -> s.name, (s, v) -> s.name = v.trim(), 160),
                new Col<>("Ends at T+ s", s -> str(s.end), (s, v) -> s.end = num(v, s.end), 100),
                new Col<>("Power 0-1", s -> str(s.powerLevel), (s, v) -> s.powerLevel = num(v, s.powerLevel), 80),
                new Col<>("Pitch deg", s -> str(s.pitch), (s, v) -> s.pitch = num(v, s.pitch), 80),
                new Col<>("Yaw deg", s -> str(s.yaw), (s, v) -> s.yaw = num(v, s.yaw), 80),
                new Col<>("Relative to motion", s -> String.valueOf(s.relative), (s, v) -> s.relative = Boolean.parseBoolean(v.trim()), 130)));
        stepTable.setPrefHeight(200);

        Label hint = new Label("Pitch: degrees from vertical (90 = horizontal). Yaw is added to the azimuth, or, when relative, "
                + "rotates the direction of motion. Steps are flown in order; the rocket stages when a stage's fuel runs out. "
                + "Changes apply to the next launch (restart with R to relaunch).");
        hint.setWrapText(true);
        VBox box = new VBox(8, g, new Label("Stages (first fires first)"), stageTable,
                buttons(stageTable, stageRows, () -> new RocketSpec.Stage("Stage " + (stageRows.size() + 1), 1000, 10000, 100, 3.0)),
                new Label("Flight steps"), stepTable,
                buttons(stepTable, stepRows, () -> {
                    double last = stepRows.isEmpty() ? 0 : stepRows.get(stepRows.size() - 1).end;
                    return new LaunchStep(last + 60, 1, 90).named("New step");
                }), hint);
        box.setPadding(new Insets(4));
        box.setPrefWidth(760);

        boolean ok = show(main, "Edit rocket and flight plan", box, () -> {
            apply(c, title, name, payload, azimuth, lat, lon, capture, apo, launch, stageRows, stepRows);
            return c.validate();
        });
        if (ok) {
            apply(c, title, name, payload, azimuth, lat, lon, capture, apo, launch, stageRows, stepRows);
            main.applySpec(c);
        }
    }

    private static void apply(RocketSpec c, TextField title, TextField name, TextField payload, TextField azimuth, TextField lat,
                              TextField lon, TextField capture, TextField apo, TextField launch,
                              List<RocketSpec.Stage> stages, List<LaunchStep> steps) {
        c.title = title.getText().trim();
        c.name = name.getText().trim().isEmpty() ? "Rocket" : name.getText().trim();
        c.payload = num(payload.getText(), c.payload);
        c.azimuth = num(azimuth.getText(), c.azimuth);
        c.latitude = num(lat.getText(), c.latitude);
        c.longitude = num(lon.getText(), c.longitude);
        c.captureBody = capture.getText().trim();
        c.captureApoapsis = num(apo.getText(), c.captureApoapsis);
        c.launchSeconds = num(launch.getText(), c.launchSeconds);
        c.stages.clear();
        c.stages.addAll(stages);
        c.steps.clear();
        c.steps.addAll(steps);
    }
}
