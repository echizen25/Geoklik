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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Resolves an INFRA project's expected municipality + barangay into a real
 * barangay polygon and checks whether the device GPS point is inside it.
 *
 * Security behavior is fail-closed: when an Infrastructure project has codes
 * but its boundary cannot yet be loaded, capture remains blocked. Once a
 * municipality boundary file has been downloaded it is cached in app-private
 * storage and future checks work offline.
 *
 * Boundary source is pinned to a fixed philippines-json-maps revision so a
 * future upstream change cannot silently change a deployed app's behavior.
 */
public final class BarangayBoundaryRepository {

    private static final String SOURCE_REVISION =
            "8eeead560246863c8c820c31ca6fbca81a279477";
    private static final String BASE_URL =
            "https://raw.githubusercontent.com/faeldon/philippines-json-maps/" +
                    SOURCE_REVISION +
                    "/2023/geojson/municities/medres/";

    private static final long CONNECT_TIMEOUT_MS = 15_000L;
    private static final long READ_TIMEOUT_MS = 20_000L;
    private static final int MAX_BOUNDARY_BYTES = 2 * 1024 * 1024;

    private static final ExecutorService DOWNLOAD_EXECUTOR =
            Executors.newSingleThreadExecutor();
    private static final Set<String> DOWNLOADS_IN_FLIGHT =
            ConcurrentHashMap.newKeySet();
    private static final Map<String, Boundary> MEMORY_CACHE =
            new ConcurrentHashMap<>();

    private final Context appContext;
    private final File cacheDir;

    public BarangayBoundaryRepository(Context context) {
        appContext = context.getApplicationContext();
        cacheDir = new File(appContext.getFilesDir(), "geoklik_barangay_boundaries_v1");
        if (!cacheDir.exists()) cacheDir.mkdirs();
    }

    public Decision evaluate(String rawMunicipalityCode,
                             String rawBarangayCode,
                             double latitude,
                             double longitude) {
        String munCode = normalizeMunicipalityCode(rawMunicipalityCode);
        String brgyCode = normalizeBarangayCode(rawBarangayCode, munCode);

        if (munCode.isEmpty() || brgyCode.isEmpty()) {
            return Decision.invalidCodes();
        }

        // A barangay must belong to the selected municipality.
        if (!brgyCode.startsWith(munCode.substring(0, 7))) {
            return Decision.invalidCodes();
        }

        if (!validPhilippineCoordinate(latitude, longitude)) {
            return Decision.locationInvalid();
        }

        String key = munCode + "|" + brgyCode;
        Boundary boundary = MEMORY_CACHE.get(key);
        if (boundary == null) {
            File file = boundaryFile(munCode);
            if (file.exists() && file.length() > 20) {
                try {
                    boundary = parseExpectedBarangay(file, munCode, brgyCode);
                    if (boundary != null) MEMORY_CACHE.put(key, boundary);
                } catch (Exception ignored) {
                    // Corrupt/obsolete file will be replaced by an async refresh.
                    try { file.delete(); } catch (Exception ignoredDelete) {}
                }
            }
        }

        if (boundary == null) {
            requestMunicipalityAsync(munCode);
            return Decision.loading();
        }

        boolean inside = boundary.contains(longitude, latitude);
        return inside
                ? Decision.inside(boundary.barangayName, munCode, brgyCode)
                : Decision.outside(boundary.barangayName, munCode, brgyCode);
    }

    public void requestMunicipalityAsync(String rawMunicipalityCode) {
        final String munCode = normalizeMunicipalityCode(rawMunicipalityCode);
        if (munCode.isEmpty()) return;

        File ready = boundaryFile(munCode);
        if (ready.exists() && ready.length() > 20) return;
        if (!DOWNLOADS_IN_FLIGHT.add(munCode)) return;

        DOWNLOAD_EXECUTOR.execute(() -> {
            try {
                downloadMunicipality(munCode);
            } catch (Exception ignored) {
                // Fail closed. A later GPS evaluation retries the fetch.
            } finally {
                DOWNLOADS_IN_FLIGHT.remove(munCode);
            }
        });
    }

    private void downloadMunicipality(String munCode) throws Exception {
        String fileName = "bgysubmuns-municity-" + munCode + ".0.01.json";
        HttpURLConnection conn = null;
        InputStream in = null;
        File tmp = new File(cacheDir, "mun_" + munCode + ".tmp");
        File outFile = boundaryFile(munCode);

        try {
            conn = (HttpURLConnection) new URL(BASE_URL + fileName).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout((int) CONNECT_TIMEOUT_MS);
            conn.setReadTimeout((int) READ_TIMEOUT_MS);
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

            // Validate that the file really contains this municipality before
            // replacing the cache.
            boolean municipalitySeen = false;
            for (int i = 0; i < features.length(); i++) {
                JSONObject props = features.optJSONObject(i) == null
                        ? null
                        : features.optJSONObject(i).optJSONObject("properties");
                if (props == null) continue;
                String adm3 = digitsOnly(String.valueOf(props.opt("adm3_psgc")));
                if (munCode.equals(leftPad(adm3, 10))) {
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

            // Remove parsed entries for this municipality so the next GPS tick
            // parses the newly downloaded file.
            String prefix = munCode + "|";
            for (String key : MEMORY_CACHE.keySet()) {
                if (key.startsWith(prefix)) MEMORY_CACHE.remove(key);
            }
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
            if (conn != null) conn.disconnect();
            if (tmp.exists()) try { tmp.delete(); } catch (Exception ignored) {}
        }
    }

    private Boundary parseExpectedBarangay(File file,
                                            String municipalityCode,
                                            String barangayCode) throws Exception {
        String json;
        try (FileInputStream input = new FileInputStream(file)) {
            json = new String(readLimited(input, MAX_BOUNDARY_BYTES), StandardCharsets.UTF_8);
        }

        JSONObject root = new JSONObject(json);
        JSONArray features = root.optJSONArray("features");
        if (features == null) return null;

        for (int i = 0; i < features.length(); i++) {
            JSONObject feature = features.optJSONObject(i);
            if (feature == null) continue;
            JSONObject props = feature.optJSONObject("properties");
            JSONObject geometry = feature.optJSONObject("geometry");
            if (props == null || geometry == null) continue;

            String adm3 = leftPad(digitsOnly(String.valueOf(props.opt("adm3_psgc"))), 10);
            String adm4 = leftPad(digitsOnly(String.valueOf(props.opt("adm4_psgc"))), 10);
            if (!municipalityCode.equals(adm3) || !barangayCode.equals(adm4)) continue;

            String name = clean(props.optString("adm4_en", ""));
            if (name.isEmpty()) name = "Project barangay";

            String type = geometry.optString("type", "");
            JSONArray coordinates = geometry.optJSONArray("coordinates");
            if (coordinates == null) return null;

            Boundary boundary = new Boundary(name);
            if ("Polygon".equalsIgnoreCase(type)) {
                addPolygon(boundary, coordinates);
            } else if ("MultiPolygon".equalsIgnoreCase(type)) {
                for (int p = 0; p < coordinates.length(); p++) {
                    JSONArray polygon = coordinates.optJSONArray(p);
                    if (polygon != null) addPolygon(boundary, polygon);
                }
            }

            return boundary.rings.length() > 0 ? boundary : null;
        }

        return null;
    }

    private static void addPolygon(Boundary boundary, JSONArray polygonCoordinates) {
        // GeoJSON polygon[0] is the exterior ring. Administrative holes are not
        // treated as another barangay; a point in a hole will conservatively be
        // handled by the exterior boundary.
        JSONArray exterior = polygonCoordinates.optJSONArray(0);
        if (exterior != null && exterior.length() >= 3) {
            boundary.rings.put(exterior);
        }
    }

    private File boundaryFile(String municipalityCode) {
        return new File(cacheDir, "mun_" + municipalityCode + ".json");
    }

    /**
     * Accept both current 10-digit PSGC municipality codes and legacy 6-digit
     * municipality codes used by older Philippine systems.
     */
    public static String normalizeMunicipalityCode(String raw) {
        String d = digitsOnly(raw);
        if (d.length() == 10 && d.endsWith("000")) return d;
        if (d.length() == 9 && d.endsWith("000")) return "0" + d;

        if (d.length() <= 6 && !d.isEmpty()) {
            d = leftPad(d, 6);
            // Legacy RRPPMM -> current RR0PPMM000.
            return d.substring(0, 2) + "0" + d.substring(2) + "000";
        }
        return "";
    }

    /** Accept current 10-digit or legacy 9-digit barangay PSGC values. */
    public static String normalizeBarangayCode(String raw, String normalizedMunicipalityCode) {
        String d = digitsOnly(raw);
        String mun = normalizeMunicipalityCode(normalizedMunicipalityCode);
        if (mun.isEmpty()) return "";

        if (d.length() == 10) return d;

        if (d.length() == 9) {
            // A current code from regions 01-09 may have lost its leading zero
            // when stored as a number. A legacy code is RRPPMMBBB. Choose the
            // candidate that belongs to the already-normalized municipality.
            String currentCandidate = "0" + d;
            String legacyCandidate = d.substring(0, 2) + "0" + d.substring(2);
            String prefix = mun.substring(0, 7);
            if (currentCandidate.startsWith(prefix)) return currentCandidate;
            if (legacyCandidate.startsWith(prefix)) return legacyCandidate;
            return "";
        }

        if (d.length() > 0 && d.length() <= 8) {
            String legacy = leftPad(d, 9);
            String candidate = legacy.substring(0, 2) + "0" + legacy.substring(2);
            return candidate.startsWith(mun.substring(0, 7)) ? candidate : "";
        }
        return "";
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
            if (total > maxBytes) throw new IllegalStateException("Boundary file too large");
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static void copyFile(File from, File to) throws Exception {
        try (FileInputStream in = new FileInputStream(from);
             FileOutputStream out = new FileOutputStream(to, false)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
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

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class Boundary {
        final String barangayName;
        final JSONArray rings = new JSONArray();

        Boundary(String barangayName) {
            this.barangayName = barangayName;
        }

        boolean contains(double longitude, double latitude) {
            for (int i = 0; i < rings.length(); i++) {
                JSONArray ring = rings.optJSONArray(i);
                if (ring != null && pointInRing(longitude, latitude, ring)) return true;
            }
            return false;
        }
    }

    public enum Status {
        INSIDE,
        OUTSIDE,
        LOADING,
        INVALID_CODES,
        INVALID_LOCATION
    }

    public static final class Decision {
        public final Status status;
        public final String barangayName;
        public final String municipalityCode;
        public final String barangayCode;

        private Decision(Status status,
                         String barangayName,
                         String municipalityCode,
                         String barangayCode) {
            this.status = status;
            this.barangayName = clean(barangayName);
            this.municipalityCode = clean(municipalityCode);
            this.barangayCode = clean(barangayCode);
        }

        static Decision inside(String name, String mun, String brgy) {
            return new Decision(Status.INSIDE, name, mun, brgy);
        }

        static Decision outside(String name, String mun, String brgy) {
            return new Decision(Status.OUTSIDE, name, mun, brgy);
        }

        static Decision loading() {
            return new Decision(Status.LOADING, "", "", "");
        }

        static Decision invalidCodes() {
            return new Decision(Status.INVALID_CODES, "", "", "");
        }

        static Decision locationInvalid() {
            return new Decision(Status.INVALID_LOCATION, "", "", "");
        }
    }
}
