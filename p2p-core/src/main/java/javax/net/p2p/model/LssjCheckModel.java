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
public class LssjCheckModel {
    public int storeId;
	public String md5;
    public String fileName;
    public String fileName2;

    public LssjCheckModel(int storeId, String md5, String fileName, String fileName2) {
        this.storeId = storeId;
		this.md5 = md5;
        this.fileName = fileName;
        this.fileName2 = fileName2;
    }

    
    @Override
    public String toString() {
        return "LssjCheckModel{" + "storeId=" + storeId+ ", md5=" + md5 + ", fileName=" + fileName + ", fileName2=" + fileName2 + '}';
    }
    
}
