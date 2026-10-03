package com.q3lives.ds.cache;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 类 Redis GEO：String key -> (member, lon, lat)，落盘复用 {@link RedisStringCache}。
 *
 * <p>每个 geo 索引存一条编码记录；读写按 key 条带锁。
 * 半径查询用 haversine 千米，闭区间、距离升序、同分 member 字典序。</p>
 */
public final class RedisGeoCache {

    private static final String VERSION = "1";
    private static final double EARTH_RADIUS_KM = 6371.0088;
    private static final int GEOHASH_PRECISION = 11;
    private static final char[] BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz".toCharArray();
    private static final int[] GEOHASH_BITS = {16, 8, 4, 2, 1};

    private final RedisStringCache store;
    private final Object[] locks = new Object[256];

    public RedisGeoCache(String rootDir, int maxEntries) throws IOException {
        this.store = new RedisStringCache(rootDir, maxEntries);
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new Object();
        }
    }

    public int geoadd(String key, double lon, double lat, String member) throws Exception {
        Objects.requireNonNull(member, "member");
        validate(lon, lat);
        synchronized (lock(key)) {
            Map<String, GeoPoint> members = load(key);
            boolean created = !members.containsKey(member);
            members.put(member, new GeoPoint(lon, lat));
            save(key, members);
            return created ? 1 : 0;
        }
    }

    public int georem(String key, String member) throws Exception {
        Objects.requireNonNull(member, "member");
        synchronized (lock(key)) {
            Map<String, GeoPoint> members = load(key);
            if (members.remove(member) == null) {
                return 0;
            }
            save(key, members);
            return 1;
        }
    }

    public void del(String key) throws Exception {
        synchronized (lock(key)) {
            store.del(key);
        }
    }

    public GeoPoint geopos(String key, String member) throws Exception {
        Objects.requireNonNull(member, "member");
        synchronized (lock(key)) {
            return load(key).get(member);
        }
    }

    public Double geodist(String key, String member1, String member2) throws Exception {
        Objects.requireNonNull(member1, "member1");
        Objects.requireNonNull(member2, "member2");
        synchronized (lock(key)) {
            Map<String, GeoPoint> members = load(key);
            GeoPoint a = members.get(member1);
            GeoPoint b = members.get(member2);
            if (a == null || b == null) {
                return null;
            }
            return haversineKm(a.lat, a.lon, b.lat, b.lon);
        }
    }

    public List<GeoMember> georadius(String key, double lon, double lat, double radiusKm) throws Exception {
        return georadius(key, lon, lat, radiusKm, 0);
    }

    public List<GeoMember> georadius(String key, double lon, double lat, double radiusKm, long limit) throws Exception {
        validate(lon, lat);
        if (radiusKm < 0) {
            throw new IllegalArgumentException("radiusKm is invalid");
        }
        List<GeoMember> matched = new ArrayList<>();
        synchronized (lock(key)) {
            for (Map.Entry<String, GeoPoint> entry : load(key).entrySet()) {
                GeoPoint point = entry.getValue();
                double distance = haversineKm(lat, lon, point.lat, point.lon);
                if (distance <= radiusKm) {
                    matched.add(new GeoMember(entry.getKey(), point.lon, point.lat, distance));
                }
            }
        }
        matched.sort(Comparator.comparingDouble((GeoMember item) -> item.distanceKm)
                .thenComparing(item -> item.member));
        if (limit > 0 && matched.size() > limit) {
            return new ArrayList<>(matched.subList(0, (int) Math.min(limit, Integer.MAX_VALUE)));
        }
        return matched;
    }

    public String geohash(String key, String member) throws Exception {
        GeoPoint point = geopos(key, member);
        if (point == null) {
            return null;
        }
        return encodeGeohash(point.lat, point.lon, GEOHASH_PRECISION);
    }

    public long geocard(String key) throws Exception {
        synchronized (lock(key)) {
            return load(key).size();
        }
    }

    private Map<String, GeoPoint> load(String key) throws Exception {
        String record = store.get(key);
        if (record == null || record.isEmpty()) {
            return new LinkedHashMap<>();
        }
        String[] lines = record.split("\n", -1);
        if (lines.length == 0 || !VERSION.equals(lines[0])) {
            throw new IllegalStateException("unsupported geo record: key=" + key);
        }
        Map<String, GeoPoint> members = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) {
                continue;
            }
            int first = line.indexOf(' ');
            int second = first < 0 ? -1 : line.indexOf(' ', first + 1);
            if (first <= 0 || second <= first) {
                throw new IllegalStateException("corrupt geo record: key=" + key);
            }
            String member = new String(Base64.getDecoder().decode(line.substring(0, first)), StandardCharsets.UTF_8);
            double lon = Double.parseDouble(line.substring(first + 1, second));
            double lat = Double.parseDouble(line.substring(second + 1));
            members.put(member, new GeoPoint(lon, lat));
        }
        return members;
    }

    private void save(String key, Map<String, GeoPoint> members) throws Exception {
        if (members.isEmpty()) {
            store.del(key);
            return;
        }
        StringBuilder sb = new StringBuilder(VERSION).append('\n');
        for (Map.Entry<String, GeoPoint> entry : members.entrySet()) {
            GeoPoint point = entry.getValue();
            sb.append(Base64.getEncoder().encodeToString(entry.getKey().getBytes(StandardCharsets.UTF_8)))
                    .append(' ')
                    .append(point.lon)
                    .append(' ')
                    .append(point.lat)
                    .append('\n');
        }
        store.set(key, sb.toString());
    }

    private Object lock(String key) {
        Objects.requireNonNull(key, "key");
        return locks[Math.abs(key.hashCode()) % locks.length];
    }

    static void validate(double lon, double lat) {
        if (lon < -180.0 || lon > 180.0 || lat < -90.0 || lat > 90.0
                || Double.isNaN(lon) || Double.isNaN(lat)
                || Double.isInfinite(lon) || Double.isInfinite(lat)) {
            throw new IllegalArgumentException("lon/lat is invalid");
        }
    }

    static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double dPhi = Math.toRadians(lat2 - lat1);
        double dLambda = Math.toRadians(lon2 - lon1);
        double sinPhi = Math.sin(dPhi / 2.0);
        double sinLambda = Math.sin(dLambda / 2.0);
        double a = sinPhi * sinPhi + Math.cos(phi1) * Math.cos(phi2) * sinLambda * sinLambda;
        return 2.0 * EARTH_RADIUS_KM * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));
    }

    static String encodeGeohash(double lat, double lon, int precision) {
        double[] lonRange = {-180.0, 180.0};
        double[] latRange = {-90.0, 90.0};
        StringBuilder hash = new StringBuilder(precision);
        boolean even = true;
        int bit = 0;
        int ch = 0;
        while (hash.length() < precision) {
            if (even) {
                double mid = (lonRange[0] + lonRange[1]) / 2.0;
                if (lon >= mid) {
                    ch |= GEOHASH_BITS[bit];
                    lonRange[0] = mid;
                } else {
                    lonRange[1] = mid;
                }
            } else {
                double mid = (latRange[0] + latRange[1]) / 2.0;
                if (lat >= mid) {
                    ch |= GEOHASH_BITS[bit];
                    latRange[0] = mid;
                } else {
                    latRange[1] = mid;
                }
            }
            even = !even;
            if (bit < 4) {
                bit++;
            } else {
                hash.append(BASE32[ch]);
                bit = 0;
                ch = 0;
            }
        }
        return hash.toString();
    }

    public static final class GeoPoint {
        public final double lon;
        public final double lat;

        public GeoPoint(double lon, double lat) {
            this.lon = lon;
            this.lat = lat;
        }
    }

    public static final class GeoMember {
        public final String member;
        public final double lon;
        public final double lat;
        public final double distanceKm;

        public GeoMember(String member, double lon, double lat, double distanceKm) {
            this.member = member;
            this.lon = lon;
            this.lat = lat;
            this.distanceKm = distanceKm;
        }
    }
}
