/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package javax.net.p2p.interfaces;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import javax.net.p2p.model.FileSegmentsDataModel;
import javax.net.p2p.model.FileDataModel;

/**
 *
 * @author Administrator
 */
public interface P2PFileService {

	
        
        public FileDataModel getFileStream(int storeId, String path) throws Exception;

	public FileDataModel getFileData(int storeId, String path) throws Exception ;
	public FileSegmentsDataModel getFileSegment(FileSegmentsDataModel segments) throws Exception;

	public void getFileData(int storeId, String path, File localFie) throws Exception ;

	public void getFileData(int storeId, String path, Path localFie) throws Exception ;

	public void putFileData(int storeId, String path, byte[] data) throws Exception ;

	public void putFileData(int storeId, String path, Path localfile) throws Exception ;
	public void putFileData(int storeId, String path, File localfile) throws Exception;

	public void putFileSegment(FileSegmentsDataModel model) throws Exception ;
	public void forcePutFileData(int storeId, String path, byte[] data) throws Exception ;

	public boolean remove(int storeId, String path) throws Exception ;

	public boolean check(int storeId, String path, long length) throws Exception ;

	public boolean checkWithMd5(int storeId, String path, long length, String md5) throws Exception ;

	/**
	 * 
	 * @param storeId
	 * @param path
	 * @param md5 null-不返回MD5，""-需返回md5
	 * @return
	 * @throws Exception
	 */
	public FileDataModel infoFile(int storeId, String path, String md5) throws Exception;

	public boolean exists(int storeId, String path) throws Exception ;

	public boolean mkdirs(int storeId, String path) throws Exception ;

	public boolean rename(int storeId, String src, String dst) throws Exception ;

	public List<String> ls(int storeId, String path) throws Exception;

	public String echo(String msg) throws Exception ;



}
