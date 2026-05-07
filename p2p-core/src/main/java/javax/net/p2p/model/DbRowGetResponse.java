package javax.net.p2p.model;

import java.util.ArrayList;
import java.util.List;

public class DbRowGetResponse {
    public long rowId;
    public List<DbCellValue> values;

    public DbRowGetResponse() {
        this.values = new ArrayList<>();
    }

    public DbRowGetResponse(long rowId, List<DbCellValue> values) {
        this.rowId = rowId;
        this.values = values == null ? new ArrayList<>() : values;
    }
}
