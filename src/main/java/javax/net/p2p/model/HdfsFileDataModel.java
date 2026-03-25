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
public class HdfsFileDataModel {
    public long length;
    public byte[] data;

		public String path;
//		public String md5;

		public HdfsFileDataModel(String path) {
			this.path = path;
		}

		public HdfsFileDataModel(String path,byte[] data) {
			this.data = data;
			this.path = path;
			this.length = data.length;
		}

	   @Override
    public String toString() {
			return "HdfsFileDataModel{" + "length=" + length + ", data=" + data + ", path=" + path + '}';
	   }
    
}
