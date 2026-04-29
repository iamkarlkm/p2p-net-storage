package p2pws.sdk.demo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class StorageRegistry {
    private final Map<Integer, Path> locations = new ConcurrentHashMap<>();

    public void register(int storeId, Path dir) {
        if (storeId == 0) {
            throw new IllegalArgumentException("storeId=0 not allowed");
        }
        Path abs = dir.toAbsolutePath().normalize();
        locations.put(storeId, abs);
        try {
            Files.createDirectories(abs);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Path resolveForWrite(int storeId, String relPath) {
        return resolve(storeId, relPath, false);
    }

    public Path resolveForRead(int storeId, String relPath) {
        return resolve(storeId, relPath, true);
    }

    private Path resolve(int storeId, String relPath, boolean mustExist) {
        Path base = locations.get(storeId);
        if (base == null) {
            throw new IllegalArgumentException("storage location not configured for storeId=" + storeId);
        }
        if (relPath == null || relPath.isBlank()) {
            throw new IllegalArgumentException("path required");
        }
        String p = relPath.replace('\\', '/');
        String[] segs = p.split("/");
        Path cur = base;
        for (String s : segs) {
            if (s == null || s.isBlank()) continue;
            if (s.equals(".") || s.equals("..")) {
                throw new IllegalArgumentException("invalid path segment");
            }
            cur = cur.resolve(s);
        }
        Path full = cur.normalize();
        if (!full.startsWith(base)) {
            throw new IllegalArgumentException("path escape blocked");
        }
        if (mustExist && !Files.exists(full)) {
            throw new IllegalArgumentException("file not found");
        }
        return full;
    }
}

