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
public class FileDataModel {
    // public boolean replaced;
    public int storeId;
    public long length;
    public byte[] data;
    public String path;
    public String md5;
    public int blockSize;

    public FileDataModel(int storeId, String path, byte[] data) {
        this.storeId = storeId;
        this.data = data;
        this.length = data.length;
        this.path = path;
    }

    public FileDataModel(int storeId, String path) {
        this.storeId = storeId;
        this.path = path;
    }
    
    public FileDataModel(int storeId, String path,long length) {
        this.storeId = storeId;
        this.path = path;
        this.length = length;
    }
    
    public FileDataModel(int storeId, String path,long length,String md5) {
        this.storeId = storeId;
        this.path = path;
        this.length = length;
        this.md5 = md5;
    }

    public FileDataModel(FileSegmentsDataModel segments) {
        this.storeId = segments.storeId;
        this.path = segments.path;
        this.length = segments.length;
        this.path = segments.path;
        this.md5 = segments.md5;
        this.blockSize = segments.blockSize;
    }

//    public FileDataModel(boolean replaced, long storeId, String path, byte[] data) {
//        this.replaced = replaced;
//        this.storeId = storeId;
//        this.data = data;
//        this.length = data.length;
//        this.path = path;
//    }
    @Override
    public String toString() {
        return "FileDataModel{" + "storeId=" + storeId + ",length=" + length + ", md5=" + md5 + ", data=" + data + ", path=" + path + '}';
    }

}
