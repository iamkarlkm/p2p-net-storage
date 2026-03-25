/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package javax.net.p2p.model;

import java.util.Arrays;
import javax.net.p2p.utils.SecurityUtils;

/**
 *
 * @author karl
 */
public class FileSegmentsDataModel {
    public int storeId;
    public long length;
    public long start;
	public int blockIndex;
    public int blockSize;
    public byte[] blockData;
	public String blockMd5;
    public String path;
	public String md5;

    public FileSegmentsDataModel(int storeId, String path,int blockSize,int index, byte[] data) {
        this.storeId = storeId;
		this.blockIndex = index;
		this.blockSize = blockSize;
		this.length = data.length;
		this.start = blockSize*index;
		int end = (int) (start+blockSize);
		if(end>length){
			end = (int) length;
			this.blockSize = (int) (end - start);
		}
        this.blockData = Arrays.copyOfRange(data, (int)start, end);
		this.blockMd5 = SecurityUtils.toMD5(this.blockData);
        this.path = path;
    }
	
	public FileSegmentsDataModel(FileSegmentsDataModel m,int index) {
        this.storeId = m.storeId;
		this.blockIndex = index;
        this.blockSize =m.blockSize;
        this.path = m.path;
		this.length = m.length;
		this.start = blockSize*index;
		int end = (int) (start+blockSize);
		if(end>length){
			this.blockSize =  (int) (length-start);
		}
    }

    public FileSegmentsDataModel(int storeId, String path,long length,int blockSize,int index,String md5) {
        this.storeId = storeId;
        this.path = path;
		this.length = length;
		this.blockIndex = index;
        this.blockSize = blockSize;
		this.md5 = md5;
		this.start = blockSize*index;
		int end = (int) (start+blockSize);
		if(end>length){
			this.blockSize =  (int) (length-start);
		}
    }
	
	public FileSegmentsDataModel(int storeId, String path,long length,String md5) {
        this.storeId = storeId;
        this.path = path;
		this.length = length;
		this.md5 = md5;
    }
	
	 public FileSegmentsDataModel(int storeId, String path) {
        this.storeId = storeId;
        this.path = path;
    }
	 
	 public FileSegmentsDataModel(int blockSize) {
        this.blockSize = blockSize;
    }
    
      
    
	public void initByIndex(int index){
		this.blockIndex = index;
		this.start = blockSize*index;
		int end = (int) (start+blockSize);
		if(end>length){
			this.blockSize =  (int) (length-start);
		}
	}
    @Override
    public String toString() {
        return "FileSegmentsDataModel{" + "storeId=" +storeId+ ",length=" + length+ ",start=" + start+
				",blockIndex=" + blockIndex + ", blockSize=" + blockSize + ", path=" + path+ ", blockMd5=" + blockMd5 + ", md5=" + md5 + '}';
    }
	
	
    
}
