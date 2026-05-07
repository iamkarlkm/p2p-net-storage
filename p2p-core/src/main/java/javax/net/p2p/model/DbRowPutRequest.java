package javax.net.p2p.model;

import java.util.ArrayList;
import java.util.List;

public class DbRowPutRequest {
    public String entityClassName;
    public long rowId;
    public boolean upsertRow;
    public List<DbCellValue> values;

    public DbRowPutRequest() {
        this.values = new ArrayList<>();
    }

    public DbRowPutRequest(String entityClassName, long rowId, boolean upsertRow, List<DbCellValue> values) {
        this.entityClassName = entityClassName;
        this.rowId = rowId;
        this.upsertRow = upsertRow;
        this.values = values == null ? new ArrayList<>() : values;
    }
}
