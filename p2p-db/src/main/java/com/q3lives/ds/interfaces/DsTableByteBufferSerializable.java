
package com.q3lives.ds.interfaces;

import java.nio.ByteBuffer;

/**
 *
 * @author Administrator
 */
public interface DsTableByteBufferSerializable {
    
    Long getId();
    
    void setId(long id);
    
    ByteBuffer toBytes();
    
    void load(ByteBuffer data);
   
}
