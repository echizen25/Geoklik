package ph.gov.geocamera.core.utils;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Offline reverse-location fallback for GeoKlik.
 *
 * Uses representative city/municipality coordinates bundled with the APK.
 * It is intentionally a fallback: Android Geocoder remains preferred when it
 * returns a usable result. Representative points are not administrative
 * boundaries, so a confidence radius is applied before a municipality name is
 * returned.
 */
public final class OfflineLocationLookup {

    private static final String ASSET_NAME = "ph_city_coords.csv";
    private static final double MAX_CITY_DISTANCE_KM = 45.0;
    private static final Object LOCK = new Object();
    private static volatile List<Point> cachedPoints;

    private OfflineLocationLookup() {}

    public static final class Result {
        public final String province;
        public final String cityMunicipality;
        public final double distanceKm;
        public final boolean cityConfident;

        Result(String province, String cityMunicipality, double distanceKm, boolean cityConfident) {
            this.province = province == null ? "" : province.trim();
            this.cityMunicipality = cityMunicipality == null ? "" : cityMunicipality.trim();
            this.distanceKm = distanceKm;
            this.cityConfident = cityConfident;
        }

        public String displayName() {
            if (cityConfident && !province.isEmpty() && !cityMunicipality.isEmpty()) {
                return province + ", " + cityMunicipality;
            }
            if (!province.isEmpty()) return province;
            if (!cityMunicipality.isEmpty()) return cityMunicipality;
            return "";
        }
    }

    public static Result find(Context context, double lat, double lng) {
        if (context == null || !validPhilippineCoordinate(lat, lng)) return null;

        List<Point> points = ensureLoaded(context.getApplicationContext());
        Point nearest = null;
        double bestKm = Double.MAX_VALUE;

        for (Point p : points) {
            double km = haversineKm(lat, lng, p.lat, p.lng);
            if (km < bestKm) {
                bestKm = km;
                nearest = p;
            }
        }

        String fallbackProvince = ProvinceLookup.getNearestProvince(lat, lng);
        if (nearest == null) {
            return fallbackProvince == null ? null
                    : new Result(fallbackProvince, "", Double.NaN, false);
        }

        // Guard against bad/outlier representative coordinates and border guesses.
        boolean confident = bestKm <= MAX_CITY_DISTANCE_KM;
        String province = nearest.province;
        if (!confident && fallbackProvince != null && !fallbackProvince.trim().isEmpty()) {
            province = fallbackProvince;
        }

        return new Result(
                province,
                confident ? nearest.city : "",
                bestKm,
                confident
        );
    }

    public static String getDisplayName(Context context, double lat, double lng) {
        Result r = find(context, lat, lng);
        return r == null ? null : r.displayName();
    }

    private static List<Point> ensureLoaded(Context context) {
        List<Point> local = cachedPoints;
        if (local != null) return local;

        synchronized (LOCK) {
            if (cachedPoints != null) return cachedPoints;

            ArrayList<Point> out = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    context.getAssets().open(ASSET_NAME), StandardCharsets.UTF_8))) {
                String line;
                boolean first = true;
                while ((line = reader.readLine()) != null) {
                    if (first) {
                        first = false;
                        if (line.toLowerCase(Locale.US).startsWith("province,")) continue;
                    }
                    parseLine(line, out);
                }
            } catch (Exception ignored) {}

            cachedPoints = Collections.unmodifiableList(out);
            return cachedPoints;
        }
    }

    private static void parseLine(String raw, List<Point> out) {
        if (raw == null) return;
        String line = raw.trim();
        if (line.isEmpty()) return;

        int firstComma = line.indexOf(',');
        if (firstComma <= 0) return;
        int secondComma = line.indexOf(',', firstComma + 1);
        if (secondComma <= firstComma + 1) return;

        String province = clean(line.substring(0, firstComma));
        String city = clean(line.substring(firstComma + 1, secondComma));
        String coord = clean(line.substring(secondComma + 1));
        if (province.isEmpty() || city.isEmpty() || coord.isEmpty()) return;

        int semi = coord.indexOf(';');
        if (semi <= 0 || semi >= coord.length() - 1) return;

        try {
            double lat = Double.parseDouble(coord.substring(0, semi).trim());
            double lng = Double.parseDouble(coord.substring(semi + 1).trim());
            if (!validPhilippineCoordinate(lat, lng)) return;
            out.add(new Point(province, city, lat, lng));
        } catch (Exception ignored) {}
    }

    private static String clean(String s) {
        if (s == null) return "";
        s = s.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1).trim();
        }
        return s;
    }

    private static boolean validPhilippineCoordinate(double lat, double lng) {
        // Broad Philippine envelope; also rejects known foreign/outlier rows.
        return lat >= 4.0 && lat <= 22.5 && lng >= 114.0 && lng <= 127.5;
    }

    private static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        final double earthKm = 6371.0088;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return earthKm * 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));
    }

    private static final class Point {
        final String province;
        final String city;
        final double lat;
        final double lng;

        Point(String province, String city, double lat, double lng) {
            this.province = province;
            this.city = city;
            this.lat = lat;
            this.lng = lng;
        }
    }
}
