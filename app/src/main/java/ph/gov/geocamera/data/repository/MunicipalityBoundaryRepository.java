package ph.gov.geocamera.data.repository;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * City/Municipality-level boundary validation for INFRA capture.
 *
 * The source files contain all barangay polygons inside one municipality/city.
 * GeoKlik treats the union of those barangay polygons as the allowed municipal
 * area. This is intentionally broader than the earlier barangay-only rule.
 *
 * PROJECT_ACTIVITY and PERSONAL do not use this repository.
 */
public final class MunicipalityBoundaryRepository {

    private static final String SOURCE_REVISION =
            "8eeead560246863c8c820c31ca6fbca81a279477";
    private static final String BASE_URL =
            "https://raw.githubusercontent.com/faeldon/philippines-json-maps/" +
                    SOURCE_REVISION +
                    "/2023/geojson/municities/medres/";

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 20_000;
    private static final int MAX_BOUNDARY_BYTES = 2 * 1024 * 1024;

    private static final ExecutorService DOWNLOAD_EXECUTOR =
            Executors.newSingleThreadExecutor();
    private static final Set<String> DOWNLOADS_IN_FLIGHT =
            ConcurrentHashMap.newKeySet();
    private static final Map<String, Boundary> MEMORY_CACHE =
            new ConcurrentHashMap<>();

    private final File cacheDir;

    public MunicipalityBoundaryRepository(Context context) {
        Context appContext = context.getApplicationContext();
        cacheDir = new File(appContext.getFilesDir(), "geoklik_municipality_boundaries_v1");
        if (!cacheDir.exists()) cacheDir.mkdirs();
    }

    public Decision evaluate(String rawMunicipalityCode,
                             double latitude,
                             double longitude) {
        String municipalityCode =
                BarangayBoundaryRepository.normalizeMunicipalityCode(rawMunicipalityCode);

        if (municipalityCode.isEmpty()) {
            return Decision.invalidCode();
        }

        if (!validPhilippineCoordinate(latitude, longitude)) {
            return Decision.invalidLocation();
        }

        Boundary boundary = MEMORY_CACHE.get(municipalityCode);
        if (boundary == null) {
            File file = boundaryFile(municipalityCode);
            if (file.exists() && file.length() > 20) {
                try {
                    boundary = parseMunicipality(file, municipalityCode);
                    if (boundary != null) {
                        MEMORY_CACHE.put(municipalityCode, boundary);
                    }
                } catch (Exception ignored) {
                    try { file.delete(); } catch (Exception ignoredDelete) {}
                }
            }
        }

        if (boundary == null) {
            requestMunicipalityAsync(municipalityCode);
            return Decision.loading(municipalityCode);
        }

        return boundary.contains(longitude, latitude)
                ? Decision.inside(municipalityCode)
                : Decision.outside(municipalityCode);
    }

    public void requestMunicipalityAsync(String rawMunicipalityCode) {
        final String municipalityCode =
                BarangayBoundaryRepository.normalizeMunicipalityCode(rawMunicipalityCode);
        if (municipalityCode.isEmpty()) return;

        File ready = boundaryFile(municipalityCode);
        if (ready.exists() && ready.length() > 20) return;
        if (!DOWNLOADS_IN_FLIGHT.add(municipalityCode)) return;

        DOWNLOAD_EXECUTOR.execute(() -> {
            try {
                downloadMunicipality(municipalityCode);
            } catch (Exception ignored) {
                // Fail closed. A later GPS evaluation retries the download.
            } finally {
                DOWNLOADS_IN_FLIGHT.remove(municipalityCode);
            }
        });
    }

    private void downloadMunicipality(String municipalityCode) throws Exception {
        String fileName = "bgysubmuns-municity-" + municipalityCode + ".0.01.json";
        HttpURLConnection conn = null;
        InputStream in = null;
        File tmp = new File(cacheDir, "mun_" + municipalityCode + ".tmp");
        File outFile = boundaryFile(municipalityCode);

        try {
            conn = (HttpURLConnection) new URL(BASE_URL + fileName).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/geo+json, application/json");

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("Boundary HTTP " + code);
            }

            in = new BufferedInputStream(conn.getInputStream());
            byte[] bytes = readLimited(in, MAX_BOUNDARY_BYTES);
            String json = new String(bytes, StandardCharsets.UTF_8);

            JSONObject root = new JSONObject(json);
            JSONArray features = root.optJSONArray("features");
            if (features == null || features.length() == 0) {
                throw new IllegalStateException("Boundary file has no features");
            }

            boolean municipalitySeen = false;
            for (int i = 0; i < features.length(); i++) {
                JSONObject feature = features.optJSONObject(i);
                JSONObject props = feature == null ? null : feature.optJSONObject("properties");
                if (props == null) continue;

                String adm3 = leftPad(
                        digitsOnly(String.valueOf(props.opt("adm3_psgc"))),
                        10
                );
                if (municipalityCode.equals(adm3)) {
                    municipalitySeen = true;
                    break;
                }
            }

            if (!municipalitySeen) {
                throw new IllegalStateException("Boundary municipality mismatch");
            }

            if (!cacheDir.exists()) cacheDir.mkdirs();
            try (FileOutputStream output = new FileOutputStream(tmp, false)) {
                output.write(bytes);
                output.flush();
            }

            if (outFile.exists() && !outFile.delete()) {
                throw new IllegalStateException("Unable to replace cached boundary");
            }
            if (!tmp.renameTo(outFile)) {
                copyFile(tmp, outFile);
                tmp.delete();
            }

            MEMORY_CACHE.remove(municipalityCode);
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
            if (conn != null) conn.disconnect();
            if (tmp.exists()) try { tmp.delete(); } catch (Exception ignored) {}
        }
    }

    private Boundary parseMunicipality(File file, String municipalityCode) throws Exception {
        String json;
        try (FileInputStream input = new FileInputStream(file)) {
            json = new String(readLimited(input, MAX_BOUNDARY_BYTES), StandardCharsets.UTF_8);
        }

        JSONObject root = new JSONObject(json);
        JSONArray features = root.optJSONArray("features");
        if (features == null) return null;

        Boundary boundary = new Boundary();

        for (int i = 0; i < features.length(); i++) {
            JSONObject feature = features.optJSONObject(i);
            if (feature == null) continue;

            JSONObject props = feature.optJSONObject("properties");
            JSONObject geometry = feature.optJSONObject("geometry");
            if (props == null || geometry == null) continue;

            String adm3 = leftPad(
                    digitsOnly(String.valueOf(props.opt("adm3_psgc"))),
                    10
            );
            if (!municipalityCode.equals(adm3)) continue;

            String type = geometry.optString("type", "");
            JSONArray coordinates = geometry.optJSONArray("coordinates");
            if (coordinates == null) continue;

            if ("Polygon".equalsIgnoreCase(type)) {
                addPolygon(boundary, coordinates);
            } else if ("MultiPolygon".equalsIgnoreCase(type)) {
                for (int p = 0; p < coordinates.length(); p++) {
                    JSONArray polygon = coordinates.optJSONArray(p);
                    if (polygon != null) addPolygon(boundary, polygon);
                }
            }
        }

        return boundary.rings.length() > 0 ? boundary : null;
    }

    private static void addPolygon(Boundary boundary, JSONArray polygonCoordinates) {
        JSONArray exterior = polygonCoordinates.optJSONArray(0);
        if (exterior != null && exterior.length() >= 3) {
            boundary.rings.put(exterior);
        }
    }

    private File boundaryFile(String municipalityCode) {
        return new File(cacheDir, "mun_" + municipalityCode + ".json");
    }

    private static boolean validPhilippineCoordinate(double lat, double lng) {
        return Double.isFinite(lat) && Double.isFinite(lng)
                && lat >= 4.0 && lat <= 22.5
                && lng >= 114.0 && lng <= 127.5;
    }

    private static boolean pointInRing(double x, double y, JSONArray ring) {
        boolean inside = false;
        int n = ring.length();
        if (n < 3) return false;

        double prevX = Double.NaN;
        double prevY = Double.NaN;
        double firstX = Double.NaN;
        double firstY = Double.NaN;

        for (int i = 0; i < n; i++) {
            JSONArray point = ring.optJSONArray(i);
            if (point == null || point.length() < 2) continue;

            double xi = point.optDouble(0, Double.NaN);
            double yi = point.optDouble(1, Double.NaN);
            if (!Double.isFinite(xi) || !Double.isFinite(yi)) continue;

            if (!Double.isFinite(firstX)) {
                firstX = xi;
                firstY = yi;
            }

            if (Double.isFinite(prevX)) {
                if (pointOnSegment(x, y, prevX, prevY, xi, yi)) return true;
                boolean intersects = ((yi > y) != (prevY > y))
                        && (x < (prevX - xi) * (y - yi) / (prevY - yi) + xi);
                if (intersects) inside = !inside;
            }

            prevX = xi;
            prevY = yi;
        }

        if (Double.isFinite(prevX) && Double.isFinite(firstX)) {
            if (pointOnSegment(x, y, prevX, prevY, firstX, firstY)) return true;
            boolean intersects = ((firstY > y) != (prevY > y))
                    && (x < (prevX - firstX) * (y - firstY) / (prevY - firstY) + firstX);
            if (intersects) inside = !inside;
        }

        return inside;
    }

    private static boolean pointOnSegment(double px, double py,
                                          double ax, double ay,
                                          double bx, double by) {
        double cross = (px - ax) * (by - ay) - (py - ay) * (bx - ax);
        if (Math.abs(cross) > 1e-8) return false;

        double dot = (px - ax) * (bx - ax) + (py - ay) * (by - ay);
        if (dot < 0) return false;

        double lenSq = (bx - ax) * (bx - ax) + (by - ay) * (by - ay);
        return dot <= lenSq;
    }

    private static byte[] readLimited(InputStream in, int maxBytes) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;

        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new IllegalStateException("Boundary file too large");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static void copyFile(File from, File to) throws Exception {
        try (FileInputStream in = new FileInputStream(from);
             FileOutputStream out = new FileOutputStream(to, false)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
        }
    }

    private static String digitsOnly(String raw) {
        if (raw == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= '0' && c <= '9') b.append(c);
        }
        return b.toString();
    }

    private static String leftPad(String value, int length) {
        String v = value == null ? "" : value;
        if (v.length() >= length) return v;
        StringBuilder b = new StringBuilder(length);
        for (int i = v.length(); i < length; i++) b.append('0');
        b.append(v);
        return b.toString();
    }

    private static final class Boundary {
        final JSONArray rings = new JSONArray();

        boolean contains(double longitude, double latitude) {
            for (int i = 0; i < rings.length(); i++) {
                JSONArray ring = rings.optJSONArray(i);
                if (ring != null && pointInRing(longitude, latitude, ring)) {
                    return true;
                }
            }
            return false;
        }
    }

    public enum Status {
        INSIDE,
        OUTSIDE,
        LOADING,
        INVALID_CODE,
        INVALID_LOCATION
    }

    public static final class Decision {
        public final Status status;
        public final String municipalityCode;

        private Decision(Status status, String municipalityCode) {
            this.status = status;
            this.municipalityCode = municipalityCode == null
                    ? ""
                    : municipalityCode.trim();
        }

        static Decision inside(String municipalityCode) {
            return new Decision(Status.INSIDE, municipalityCode);
        }

        static Decision outside(String municipalityCode) {
            return new Decision(Status.OUTSIDE, municipalityCode);
        }

        static Decision loading(String municipalityCode) {
            return new Decision(Status.LOADING, municipalityCode);
        }

        static Decision invalidCode() {
            return new Decision(Status.INVALID_CODE, "");
        }

        static Decision invalidLocation() {
            return new Decision(Status.INVALID_LOCATION, "");
        }
    }
}
