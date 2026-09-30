package com.q3lives.ds.cache;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 类 Redis Sorted Set：String key -> (member, score)，落盘复用 {@link RedisStringCache}。
 *
 * <p>每个 zset 存一条编码记录；读写按 key 条带锁。排序为 score 升序、同分 member 字典序。</p>
 */
public final class RedisSortedSetCache {

    private static final String VERSION = "1";
    private static final Comparator<ScoredMember> ASC = Comparator
            .comparingDouble((ScoredMember item) -> item.score)
            .thenComparing(item -> item.member);

    private final RedisStringCache store;
    private final Object[] locks = new Object[256];

    public RedisSortedSetCache(String rootDir, int maxEntries) throws IOException {
        this.store = new RedisStringCache(rootDir, maxEntries);
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new Object();
        }
    }

    public int zadd(String key, String member, double score) throws Exception {
        Objects.requireNonNull(member, "member");
        synchronized (lock(key)) {
            Map<String, Double> members = load(key);
            boolean created = !members.containsKey(member);
            members.put(member, score);
            save(key, members);
            return created ? 1 : 0;
        }
    }

    public double zincrby(String key, String member, double delta) throws Exception {
        Objects.requireNonNull(member, "member");
        synchronized (lock(key)) {
            Map<String, Double> members = load(key);
            double next = members.getOrDefault(member, 0.0) + delta;
            members.put(member, next);
            save(key, members);
            return next;
        }
    }

    public int zrem(String key, String member) throws Exception {
        Objects.requireNonNull(member, "member");
        synchronized (lock(key)) {
            Map<String, Double> members = load(key);
            if (members.remove(member) == null) {
                return 0;
            }
            save(key, members);
            return 1;
        }
    }

    public int zremrangeByScore(String key, double min, double max) throws Exception {
        synchronized (lock(key)) {
            Map<String, Double> members = load(key);
            int removed = 0;
            java.util.Iterator<Map.Entry<String, Double>> it = members.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Double> entry = it.next();
                double score = entry.getValue();
                if (score >= min && score <= max) {
                    it.remove();
                    removed++;
                }
            }
            if (removed > 0) {
                save(key, members);
            }
            return removed;
        }
    }

    public void del(String key) throws Exception {
        synchronized (lock(key)) {
            store.del(key);
        }
    }

    public Double zscore(String key, String member) throws Exception {
        Objects.requireNonNull(member, "member");
        synchronized (lock(key)) {
            return load(key).get(member);
        }
    }

    public long zcard(String key) throws Exception {
        synchronized (lock(key)) {
            return load(key).size();
        }
    }

    public List<String> zrange(String key, long start, long end) throws Exception {
        return names(zrangeWithScores(key, start, end));
    }

    public List<String> zrevrange(String key, long start, long end) throws Exception {
        List<ScoredMember> ranked = ranked(key);
        Collections.reverse(ranked);
        return names(slice(ranked, start, end));
    }

    public List<ScoredMember> zrangeWithScores(String key, long start, long end) throws Exception {
        return slice(ranked(key), start, end);
    }

    public List<ScoredMember> zrangeByScore(String key, double min, double max) throws Exception {
        List<ScoredMember> matched = new ArrayList<>();
        for (ScoredMember item : ranked(key)) {
            if (item.score >= min && item.score <= max) {
                matched.add(item);
            }
        }
        return matched;
    }

    private List<ScoredMember> ranked(String key) throws Exception {
        synchronized (lock(key)) {
            List<ScoredMember> ranked = new ArrayList<>();
            for (Map.Entry<String, Double> entry : load(key).entrySet()) {
                ranked.add(new ScoredMember(entry.getKey(), entry.getValue()));
            }
            ranked.sort(ASC);
            return ranked;
        }
    }

    private Map<String, Double> load(String key) throws Exception {
        String record = store.get(key);
        if (record == null || record.isEmpty()) {
            return new LinkedHashMap<>();
        }
        String[] lines = record.split("\n", -1);
        if (lines.length == 0 || !VERSION.equals(lines[0])) {
            throw new IllegalStateException("unsupported zset record: key=" + key);
        }
        Map<String, Double> members = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) {
                continue;
            }
            int sep = line.indexOf(' ');
            if (sep <= 0) {
                throw new IllegalStateException("corrupt zset record: key=" + key);
            }
            String member = new String(Base64.getDecoder().decode(line.substring(0, sep)), StandardCharsets.UTF_8);
            members.put(member, Double.parseDouble(line.substring(sep + 1)));
        }
        return members;
    }

    private void save(String key, Map<String, Double> members) throws Exception {
        if (members.isEmpty()) {
            store.del(key);
            return;
        }
        StringBuilder sb = new StringBuilder(VERSION).append('\n');
        for (Map.Entry<String, Double> entry : members.entrySet()) {
            sb.append(Base64.getEncoder().encodeToString(entry.getKey().getBytes(StandardCharsets.UTF_8)))
                    .append(' ')
                    .append(entry.getValue())
                    .append('\n');
        }
        store.set(key, sb.toString());
    }

    private Object lock(String key) {
        Objects.requireNonNull(key, "key");
        return locks[Math.abs(key.hashCode()) % locks.length];
    }

    private static List<String> names(List<ScoredMember> items) {
        List<String> names = new ArrayList<>(items.size());
        for (ScoredMember item : items) {
            names.add(item.member);
        }
        return names;
    }

    static List<ScoredMember> slice(List<ScoredMember> ranked, long start, long end) {
        int size = ranked.size();
        if (size == 0) {
            return List.of();
        }
        int from = normalize(start, size);
        int to = normalize(end, size);
        if (from < 0 || to < 0 || from > to || from >= size) {
            return List.of();
        }
        int exclusive = Math.min(to + 1, size);
        return new ArrayList<>(ranked.subList(from, exclusive));
    }

    private static int normalize(long index, int size) {
        if (index < 0) {
            index = size + index;
        }
        if (index < 0) {
            return -1;
        }
        if (index >= size) {
            return size - 1;
        }
        return (int) index;
    }

    public static final class ScoredMember {
        public final String member;
        public final double score;

        public ScoredMember(String member, double score) {
            this.member = member;
            this.score = score;
        }
    }
}
