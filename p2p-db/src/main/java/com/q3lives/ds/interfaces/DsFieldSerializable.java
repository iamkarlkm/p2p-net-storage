
package com.q3lives.ds.interfaces;

/**
 *
 * @author Administrator
 */
public interface DsFieldSerializable {
    
    byte[] toBytes();
    void load(byte[] data);
}
