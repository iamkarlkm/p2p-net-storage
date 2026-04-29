
package com.q3lives.ds.collections;

import com.q3lives.ds.util.DsDataUtil;
import java.util.UUID;

/**
 *
 * @author Administrator
 */
public class NewClass {
    public static void main(String[] args) {
        System.out.println(UUID.randomUUID().toString());
        String nodeId = DsDataUtil.newSha256IdString();
        System.out.println(nodeId);
        byte[] hashes = DsDataUtil.fromHex(nodeId);
        System.out.println(DsDataUtil.toHex(hashes));
    }
    
}
