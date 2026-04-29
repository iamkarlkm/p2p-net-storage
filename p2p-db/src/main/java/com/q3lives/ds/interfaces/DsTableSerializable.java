
package com.q3lives.ds.interfaces;

/**
 *
 * @author Administrator
 */
public interface DsTableSerializable {
    
    Long getId();
    
    void setId(long id);
    
    byte[] toBytes();
    void load(byte[] data);
   
}
