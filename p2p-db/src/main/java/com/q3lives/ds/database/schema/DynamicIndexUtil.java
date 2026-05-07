package com.q3lives.ds.database.schema;

import com.q3lives.ds.util.DsPathUtil;
import java.io.File;

public final class DynamicIndexUtil {
    private DynamicIndexUtil() {
    }

    public static File idsFile(File dbRoot, String entityClassName) {
        if (entityClassName == null || entityClassName.isBlank()) {
            throw new IllegalArgumentException("entityClassName is blank");
        }
        String spacePath = DsPathUtil.dottedToLinuxPath(entityClassName, "entityClass");
        File dir = new File(dbRoot, "indexes/" + spacePath);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return new File(dir, "ids.set");
    }
}

