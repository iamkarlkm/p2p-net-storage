/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package javax.net.p2p.model;

/**
 *
 * @author karl
 */
public class LssjImageModel {
    public int storeId;
    public String fileName;
    public String fileName2;

    public LssjImageModel(int storeId, String fileName, String fileName2) {
        this.storeId = storeId;
        this.fileName = fileName;
        this.fileName2 = fileName2;
    }

    
    @Override
    public String toString() {
        return "LssjImageModel{" + "storeId=" + storeId + ", fileName=" + fileName + ", fileName2=" + fileName2 + '}';
    }
    
}
