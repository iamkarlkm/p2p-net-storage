package com.q3lives.ds.header;

import java.io.File;
import java.io.IOException;

import com.q3lives.ds.database.config.DsDbConfig;

public final class HeaderTieredStoreFactory {

    private HeaderTieredStoreFactory() {
    }

    public static HeaderTieredStore create(File dataFile, String storeName) {
        DsDbConfig cfg = DsDbConfig.getInstance();
        DsDbConfig.HeaderTierMode mode = cfg.getHeaderTierMode();
        if (mode == null) mode = DsDbConfig.HeaderTierMode.DIRECT;
        switch (mode) {
            case MEM_DELTA:
                return new MemDeltaHeaderTierStore(storeName);
            case FILE_DELTA:
                String tierDir = cfg.resolveTierDirFor(dataFile);
                if (cfg.isSharedLogEnabled() && dataFile != null && !looksLikeSystemStore(storeName, dataFile)) {
                    try {
                        return new SharedLogFileDeltaHeaderTierStore(storeName, dataFile, tierDir);
                    } catch (IOException e) {
                        return new FileDeltaHeaderTierStore(storeName, tierDir);
                    }
                }
                return new FileDeltaHeaderTierStore(storeName, tierDir);
            case DIRECT:
            default:
                return new DirectHeaderTierStore(storeName);
        }
    }

    private static boolean looksLikeSystemStore(String storeName, File dataFile) {
        if (dataFile == null) return false;
        String path = dataFile.getPath().replace('\\', '/');
        String low = path.toLowerCase();
        if (storeName != null) {
            String ls = storeName.toLowerCase();
            if (ls.contains("store_id") || ls.contains("id_registry")) return true;
        }
        return low.contains("/_id_registry/") || low.contains("/_tiers/") || low.contains("store_id.idx");
    }
}
