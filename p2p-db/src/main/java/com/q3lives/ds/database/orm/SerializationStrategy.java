
package com.q3lives.ds.database.orm;

import com.q3lives.ds.database.adapter.DsTableAdapter;
import java.nio.ByteBuffer;

public interface SerializationStrategy {
    ByteBuffer serialize(DsTableAdapter entity);
    void deserialize(ByteBuffer data, DsTableAdapter entity);
}


