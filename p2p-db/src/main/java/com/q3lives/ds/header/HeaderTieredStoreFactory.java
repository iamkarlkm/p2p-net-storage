package com.q3lives.ds.header;

import java.io.File;

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
                return new FileDeltaHeaderTierStore(storeName, cfg.resolveTierDirFor(dataFile));
            case DIRECT:
            default:
                return new DirectHeaderTierStore(storeName);
        }
    }
}
