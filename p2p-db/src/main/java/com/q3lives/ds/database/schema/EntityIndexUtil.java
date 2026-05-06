package com.q3lives.ds.database.schema;

import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.util.DsPathUtil;
import java.io.File;

public final class EntityIndexUtil {
    private EntityIndexUtil() {
    }

    public static IndexDef indexOf(File dbRoot, Class<? extends DsTableAdapter> clazz) {
        String spacePath = DsPathUtil.dottedToLinuxPath(clazz.getName(), "entityClass");
        File dir = new File(dbRoot, "indexes/" + spacePath);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        File legacyIds = new File(dir, "ids.set");
        EntitySchemaUtil.SchemaDef schema = EntitySchemaUtil.schemaOf(clazz);
        File schemaIds = new File(dir, "ids_" + schema.schemaId + ".set");

        if (legacyIds.exists() && !schemaIds.exists()) {
            return new IndexDef(legacyIds, "rows", schema.rowLength, true);
        }
        return new IndexDef(schemaIds, "rows_" + schema.schemaId, schema.rowLength, false);
    }

    public static final class IndexDef {
        public final File idsFile;
        public final String rowType;
        public final int rowLength;
        public final boolean legacy;

        public IndexDef(File idsFile, String rowType, int rowLength, boolean legacy) {
            this.idsFile = idsFile;
            this.rowType = rowType;
            this.rowLength = rowLength;
            this.legacy = legacy;
        }
    }
}

