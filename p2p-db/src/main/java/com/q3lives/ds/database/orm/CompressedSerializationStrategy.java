
package com.q3lives.ds.database.orm;

import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.util.SerializationEnhancer;
import java.nio.ByteBuffer;

public class CompressedSerializationStrategy implements SerializationStrategy {
    @Override
    public ByteBuffer serialize(DsTableAdapter entity) {
        return SerializationEnhancer.serializeWithCompression(entity);
    }
    
    @Override
    public void deserialize(ByteBuffer data, DsTableAdapter entity) {
        try {
            SerializationEnhancer.deserializeWithCompression(data, entity);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}