package com.q3lives.ds.database.config;

import java.io.File;
import java.io.IOException;

import com.q3lives.ds.header.FileStoreIdRegistry;
import com.q3lives.ds.header.StoreIdRegistry;

public class DsDbConfig {

    public enum HeaderTierMode {
        DIRECT,
        MEM_DELTA,
        FILE_DELTA
    }

    public static final String ENV_HEADER_TIER_MODE = "DSDB_HEADER_TIER_MODE";
    public static final String ENV_HEADER_TIER_ROOT = "DSDB_HEADER_TIER_ROOT";
    public static final String ENV_HEADER_MERGE_HOUR = "DSDB_HEADER_MERGE_HOUR";
    public static final String ENV_HEADER_SHARED_LOG = "DSDB_HEADER_SHARED_LOG";
    public static final int DEFAULT_MERGE_HOUR = 3;
    public static final String TIER_SUB_DIR = "_tiers";
    public static final String REGISTRY_SUB_DIR = "_id_registry";

    private static volatile DsDbConfig INSTANCE;
    private static volatile int LAST_SIG = -1;

    private volatile HeaderTierMode headerTierMode;
    private volatile String tierRootDir;
    private volatile int mergeHourOfDay;
    private volatile boolean sharedLogEnabled;

    private final Object registryLock = new Object();
    private final java.util.concurrent.ConcurrentHashMap<String, StoreIdRegistry> registryByDir =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static int sig(HeaderTierMode m, boolean shared, int hour) {
        int a = m == null ? 99 : m.ordinal();
        return a * 1000 + (shared ? 1 : 0) * 100 + (hour & 0x1F);
    }

    private DsDbConfig() {
        this.headerTierMode = resolveModeFromEnv();
        this.tierRootDir = resolveTierRootFromEnv();
        this.mergeHourOfDay = resolveMergeHourFromEnv();
        this.sharedLogEnabled = resolveSharedLogFromEnv();
    }

    public static DsDbConfig getInstance() {
        if (INSTANCE == null) {
            synchronized (DsDbConfig.class) {
                if (INSTANCE == null) {
                    INSTANCE = new DsDbConfig();
                    LAST_SIG = sig(INSTANCE.headerTierMode, INSTANCE.sharedLogEnabled, INSTANCE.mergeHourOfDay);
                }
            }
        } else {
            HeaderTierMode m = resolveModeFromEnv();
            int h = resolveMergeHourFromEnv();
            boolean s = resolveSharedLogFromEnv();
            int cur = sig(m, s, h);
            if (cur != LAST_SIG) {
                synchronized (DsDbConfig.class) {
                    cur = sig(m, s, h);
                    if (cur != LAST_SIG) {
                        String oldTierRoot = INSTANCE == null ? null : INSTANCE.tierRootDir;
                        String newTierRoot = resolveTierRootFromEnv();
                        try { Class.forName("com.q3lives.ds.header.SharedLogFileDeltaHeaderTierStore")
                                .getMethod("forceResetAllContextsForTest", String.class).invoke(null, oldTierRoot); } catch (Throwable ignore) {}
                        try { Class.forName("com.q3lives.ds.header.DailyMergeService")
                                .getMethod("forceResetForTest").invoke(null); } catch (Throwable ignore) {}
                        DsDbConfig fresh = new DsDbConfig();
                        fresh.headerTierMode = m;
                        fresh.mergeHourOfDay = h;
                        fresh.sharedLogEnabled = s;
                        fresh.tierRootDir = newTierRoot;
                        INSTANCE = fresh;
                        LAST_SIG = cur;
                    }
                }
            }
        }
        return INSTANCE;
    }

    public static void forceResetForTest(HeaderTierMode mode, String tierRootDir) {
        forceResetForTest(mode, tierRootDir, false);
    }

    public static void forceResetForTest(HeaderTierMode mode, String tierRootDir, boolean sharedLog) {
        synchronized (DsDbConfig.class) {
            DsDbConfig cfg = new DsDbConfig();
            cfg.headerTierMode = mode == null ? HeaderTierMode.DIRECT : mode;
            cfg.tierRootDir = tierRootDir;
            cfg.mergeHourOfDay = DEFAULT_MERGE_HOUR;
            cfg.sharedLogEnabled = sharedLog;
            for (StoreIdRegistry r : cfg.registryByDir.values()) {
                try { r.close(); } catch (IOException ignore) {}
            }
            cfg.registryByDir.clear();
            try { Class.forName("com.q3lives.ds.header.SharedLogFileDeltaHeaderTierStore")
                    .getMethod("forceResetAllContextsForTest", String.class).invoke(null, tierRootDir); } catch (Throwable ignore) {}
            try { Class.forName("com.q3lives.ds.header.DailyMergeService")
                    .getMethod("forceResetForTest").invoke(null); } catch (Throwable ignore) {}
            INSTANCE = cfg;
        }
    }

    public HeaderTierMode getHeaderTierMode() {
        return headerTierMode;
    }

    public void setHeaderTierMode(HeaderTierMode mode) {
        this.headerTierMode = mode == null ? HeaderTierMode.DIRECT : mode;
    }

    public String getTierRootDir() {
        return tierRootDir;
    }

    public void setTierRootDir(String dir) {
        this.tierRootDir = dir;
    }

    public int getMergeHourOfDay() {
        return mergeHourOfDay;
    }

    public void setMergeHourOfDay(int hour) {
        if (hour < 0) hour = 0;
        if (hour > 23) hour = 23;
        this.mergeHourOfDay = hour;
    }

    public boolean isSharedLogEnabled() {
        return sharedLogEnabled;
    }

    public void setSharedLogEnabled(boolean v) {
        this.sharedLogEnabled = v;
    }

    public StoreIdRegistry getOrCreateStoreIdRegistry(File tierDirOrNull) {
        File dir;
        if (tierDirOrNull != null) {
            dir = new File(tierDirOrNull.getParentFile(), REGISTRY_SUB_DIR);
        } else if (tierRootDir != null && !tierRootDir.isEmpty()) {
            dir = new File(tierRootDir, REGISTRY_SUB_DIR);
        } else {
            dir = new File(new File("."), REGISTRY_SUB_DIR);
        }
        String key;
        try { key = dir.getCanonicalPath(); } catch (IOException e) { key = dir.getAbsolutePath(); }
        StoreIdRegistry r = registryByDir.get(key);
        if (r != null && r.isReady()) return r;
        synchronized (registryLock) {
            r = registryByDir.get(key);
            if (r != null && r.isReady()) return r;
            try {
                if (!dir.exists()) dir.mkdirs();
                r = new FileStoreIdRegistry(dir);
                registryByDir.put(key, r);
                return r;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to create StoreIdRegistry at " + dir, e);
            }
        }
    }

    public StoreIdRegistry getStoreIdRegistryIfReady() {
        for (StoreIdRegistry r : registryByDir.values()) {
            if (r != null && r.isReady()) return r;
        }
        return null;
    }

    public String resolveTierDirFor(File dataFile) {
        String root = tierRootDir;
        if (root == null || root.isEmpty()) {
            if (dataFile == null) return null;
            File parent = dataFile.getParentFile();
            if (parent == null) return TIER_SUB_DIR;
            return new File(parent, TIER_SUB_DIR).getAbsolutePath();
        }
        return new File(root, TIER_SUB_DIR).getAbsolutePath();
    }

    private static HeaderTierMode resolveModeFromEnv() {
        String v = System.getProperty(ENV_HEADER_TIER_MODE);
        if (v == null || v.isEmpty()) v = System.getenv(ENV_HEADER_TIER_MODE);
        if (v == null || v.isEmpty()) return HeaderTierMode.DIRECT;
        try {
            return HeaderTierMode.valueOf(v.trim().toUpperCase());
        } catch (Exception ignore) {
            return HeaderTierMode.DIRECT;
        }
    }

    private static String resolveTierRootFromEnv() {
        String v = System.getProperty(ENV_HEADER_TIER_ROOT);
        if (v == null || v.isEmpty()) v = System.getenv(ENV_HEADER_TIER_ROOT);
        return (v == null || v.isEmpty()) ? null : v;
    }

    private static int resolveMergeHourFromEnv() {
        String v = System.getProperty(ENV_HEADER_MERGE_HOUR);
        if (v == null || v.isEmpty()) v = System.getenv(ENV_HEADER_MERGE_HOUR);
        if (v == null || v.isEmpty()) return DEFAULT_MERGE_HOUR;
        try {
            int h = Integer.parseInt(v.trim());
            if (h < 0) h = 0;
            if (h > 23) h = 23;
            return h;
        } catch (Exception ignore) {
            return DEFAULT_MERGE_HOUR;
        }
    }

    private static boolean resolveSharedLogFromEnv() {
        String v = System.getProperty(ENV_HEADER_SHARED_LOG);
        if (v == null || v.isEmpty()) v = System.getenv(ENV_HEADER_SHARED_LOG);
        if (v == null || v.isEmpty()) return false;
        String s = v.trim().toLowerCase();
        return s.equals("1") || s.equals("true") || s.equals("yes") || s.equals("on");
    }
}
