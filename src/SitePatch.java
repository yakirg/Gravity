import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;

/**
 * High resolution ground patch around the launch site, a few km each way, laid on the unit Earth sphere.
 * Imagery is Esri World Imagery web-mercator tiles, downloaded once and cached in images/tiles.
 * The patch stays hidden when the tiles cannot be loaded.
 */
final class SitePatch {
    static final double HALF_KM = 3, EARTH_RADIUS = 6371;
    private static final int ZOOM = 15, GRID = 24, TILE = 256;

    private SitePatch() {
    }

    private static double tileX(double lonDeg) {
        return (lonDeg + 180) / 360 * (1 << ZOOM);
    }

    private static double tileY(double latDeg) {
        double r = Math.toRadians(latDeg);
        return (1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * (1 << ZOOM);
    }

    static MeshView build(double latDeg, double lonDeg) {
        double dLat = Math.toDegrees(HALF_KM / EARTH_RADIUS);
        double dLon = dLat / Math.cos(Math.toRadians(latDeg));
        int x0 = (int) Math.floor(tileX(lonDeg - dLon)), x1 = (int) Math.floor(tileX(lonDeg + dLon));
        int y0 = (int) Math.floor(tileY(latDeg + dLat)), y1 = (int) Math.floor(tileY(latDeg - dLat));
        double w = x1 - x0 + 1, h = y1 - y0 + 1;

        TriangleMesh mesh = new TriangleMesh();
        double radius = 1 + 3e-7;
        for (int i = 0; i <= GRID; i++)
            for (int j = 0; j <= GRID; j++) {
                double lat = latDeg + dLat * (1 - 2.0 * i / GRID), lon = lonDeg + dLon * (2.0 * j / GRID - 1);
                double la = Math.toRadians(lat), lo = Math.toRadians(lon);
                mesh.getPoints().addAll((float) (radius * Math.cos(la) * Math.cos(lo)), (float) (radius * Math.cos(la) * Math.sin(lo)),
                        (float) (radius * Math.sin(la)));
                mesh.getTexCoords().addAll((float) ((tileX(lon) - x0) / w), (float) ((tileY(lat) - y0) / h));
            }
        for (int i = 0; i < GRID; i++)
            for (int j = 0; j < GRID; j++) {
                int p00 = i * (GRID + 1) + j, p01 = p00 + 1, p10 = p00 + GRID + 1, p11 = p10 + 1;
                mesh.getFaces().addAll(p00, p00, p10, p10, p01, p01, p01, p01, p10, p10, p11, p11);
            }
        MeshView view = new MeshView(mesh);
        view.setCullFace(CullFace.NONE);
        view.setVisible(false);

        Thread t = new Thread(() -> {
            int[] px = mosaic(x0, y0, x1, y1);
            if (px == null)
                return;
            Platform.runLater(() -> {
                WritableImage img = new WritableImage((int) w * TILE, (int) h * TILE);
                img.getPixelWriter().setPixels(0, 0, (int) w * TILE, (int) h * TILE, PixelFormat.getIntArgbInstance(), px, 0, (int) w * TILE);
                view.setMaterial(new PhongMaterial(Color.WHITE, img, null, null, null));
                view.setVisible(true);
            });
        }, "site-tiles");
        t.setDaemon(true);
        t.start();
        return view;
    }

    private static int[] mosaic(int x0, int y0, int x1, int y1) {
        int tw = x1 - x0 + 1, th = y1 - y0 + 1;
        int width = tw * TILE;
        int[] out = new int[width * th * TILE];
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
        File dir = AppFiles.file("images/tiles");
        dir.mkdirs();
        try {
            for (int ty = 0; ty < th; ty++)
                for (int tx = 0; tx < tw; tx++) {
                    int x = x0 + tx, y = y0 + ty;
                    File f = new File(dir, ZOOM + "_" + x + "_" + y + ".jpg");
                    if (!f.exists()) {
                        HttpRequest rq = HttpRequest.newBuilder(URI.create("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"
                                + ZOOM + "/" + y + "/" + x)).header("User-Agent", "GravitySimulation/1.0").timeout(Duration.ofSeconds(15)).build();
                        HttpResponse<byte[]> rs = client.send(rq, HttpResponse.BodyHandlers.ofByteArray());
                        if (rs.statusCode() != 200)
                            return null;
                        Files.write(f.toPath(), rs.body());
                    }
                    Image img = new Image(new ByteArrayInputStream(Files.readAllBytes(f.toPath())));
                    PixelReader pr = img.getPixelReader();
                    if (pr == null || img.getWidth() != TILE)
                        return null;
                    for (int py = 0; py < TILE; py++)
                        pr.getPixels(0, py, TILE, 1, PixelFormat.getIntArgbInstance(), out, (ty * TILE + py) * width + tx * TILE, TILE);
                }
        } catch (Exception e) {
            return null;
        }
        return out;
    }
}
