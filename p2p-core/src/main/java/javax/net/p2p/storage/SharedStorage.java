/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

package javax.net.p2p.storage;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 *
 * @author karl
 */
public class SharedStorage {
     private static final Map<Integer, File> STORAGE_MAP = new ConcurrentHashMap<>();
    static {
        STORAGE_MAP.put(766, new File("E:/VEH_IMAGES"));
		
        STORAGE_MAP.put(11, new File("e:/p2p_test"));
        STORAGE_MAP.put(22, new File("/opt"));
		STORAGE_MAP.put(1001, new File("/home"));
    }

    public final static File getStorageLocation(int storeId){
        
         return STORAGE_MAP.get(storeId);
        
    }
    
    public final static File registerStorageLocation(int storeId,File dir){
        
         return STORAGE_MAP.put(storeId,dir);
        
    }
   
}
