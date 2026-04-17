/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package javax.net.p2p.model;

/**
 * 以netty FileRegion 上传下载文件 
 * @author karl
 */
public class FileRegionDataModel {

    public long storeId;
    public long length;
    public long start;
    public int blockIndex;
    public int blockSize;
    public String blockMd5;
    public String path;
    public String md5;

    public FileRegionDataModel(long storeId, String path, long length, int blockSize, int index, String md5) {
        this.storeId = storeId;
        this.path = path;
        this.length = length;
        this.blockIndex = index;
        this.blockSize = blockSize;
        this.md5 = md5;
        this.start = blockSize * index;
        int end = (int) (start + blockSize);
        if (end > length) {
            this.blockSize = (int) (length - start);
        }
    }

    public FileRegionDataModel(long storeId, String path, long length, String md5) {
        this.storeId = storeId;
        this.path = path;
        this.length = length;
        this.md5 = md5;
    }

    public FileRegionDataModel(long storeId, String path) {
        this.storeId = storeId;
        this.path = path;
    }

    public FileRegionDataModel(int blockSize) {
        this.blockSize = blockSize;
    }

    public void initByIndex(int index) {
        this.blockIndex = index;
        this.start = blockSize * index;
        int end = (int) (start + blockSize);
        if (end > length) {
            this.blockSize = (int) (length - start);
        }
    }

    @Override
    public String toString() {
        return "FileRegionDataModel{" + "storeId=" + storeId + ",length=" + length + ",start=" + start
                + ",blockIndex=" + blockIndex + ", blockSize=" + blockSize + ", path=" + path + ", blockMd5=" + blockMd5 + ", md5=" + md5 + '}';
    }

}
