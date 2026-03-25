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
public class HdfsFileBlockModel {
	public long start;
    public long length;
	public String path;
	public byte[] data;
	public HdfsFileBlockModel(String path,long start, long length) {
		this.path = path;
		this.start = start;
		this.length = length;
	}
   

	   @Override
    public String toString() {
			return "HdfsFileBlockModel{" + "length=" + length + ", start=" + start + ", path=" + path + '}';
	   }
    
}
