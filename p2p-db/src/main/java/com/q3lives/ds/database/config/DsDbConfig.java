package com.q3lives.ds.database.config;

import java.io.File;

public class DsDbConfig {

    public enum HeaderTierMode {
        DIRECT,
        MEM_DELTA,
        FILE_DELTA
    }

    public static final String ENV_HEADER_TIER_MODE = "DSDB_HEADER_TIER_MODE";
    public static final String ENV_HEADER_TIER_ROOT = "DSDB_HEADER_TIER_ROOT";
    public static final String ENV_HEADER_MERGE_HOUR = "DSDB_HEADER_MERGE_HOUR";
    public static final int DEFAULT_MERGE_HOUR = 3;
    public static final String TIER_SUB_DIR = "_tiers";

    private static volatile DsDbConfig INSTANCE;

    private volatile HeaderTierMode headerTierMode;
    private volatile String tierRootDir;
    private volatile int mergeHourOfDay;

    private DsDbConfig() {
        this.headerTierMode = resolveModeFromEnv();
        this.tierRootDir = resolveTierRootFromEnv();
        this.mergeHourOfDay = resolveMergeHourFromEnv();
    }

    public static DsDbConfig getInstance() {
        if (INSTANCE == null) {
            synchronized (DsDbConfig.class) {
                if (INSTANCE == null) {
                    INSTANCE = new DsDbConfig();
                }
            }
        }
        return INSTANCE;
    }

    public static void forceResetForTest(HeaderTierMode mode, String tierRootDir) {
        synchronized (DsDbConfig.class) {
            DsDbConfig cfg = new DsDbConfig();
            cfg.headerTierMode = mode == null ? HeaderTierMode.DIRECT : mode;
            cfg.tierRootDir = tierRootDir;
            cfg.mergeHourOfDay = DEFAULT_MERGE_HOUR;
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
}
