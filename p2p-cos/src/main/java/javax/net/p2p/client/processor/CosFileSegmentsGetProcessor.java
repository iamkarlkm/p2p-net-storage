
package javax.net.p2p.client.processor;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.p2p.model.FileSegmentsDataModel;
import javax.net.p2p.utils.FileUtil;
import javax.net.p2p.utils.P2PCosUtils;
import javax.net.p2p.utils.P2PUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;

/**
 * COS 分片下载处理器：负责下载单个分片并写入目标文件。
 *
 * <p>作为 Runnable 在多线程下载时被调度执行。</p>
 */
public class CosFileSegmentsGetProcessor implements Runnable {


	private final FileSegmentsDataModel model;

	private final CountDownLatch countDownLatch;

	private final CountDownLatch errorCountDown;

	private final AtomicBoolean errorTag;

	private final File data;

	private final RandomAccessFile infoFile;

	public CosFileSegmentsGetProcessor( FileSegmentsDataModel model, File data,RandomAccessFile infoFile, 
			CountDownLatch countDownLatch, CountDownLatch errorCountDown, AtomicBoolean errorTag) {
		this.model = model;
		this.countDownLatch = countDownLatch;
		this.errorCountDown = errorCountDown;
		this.errorTag = errorTag;
		this.data = data;
		this.infoFile = infoFile;
	}

	public static Pair<File, Set<Integer>> getDownInfo(String path) {
		File file = new File(System.getProperty("java.io.tmpdir"), path + ".down.idx");
		Set<Integer> indexes = null;
		if (file.exists()) {
			try {
				List<String> lines = Files.readAllLines(file.toPath());
				if(lines.isEmpty()){
					indexes = new HashSet();
					for (String index : lines) {
					indexes.add(Integer.valueOf(index));
				}
				return Pair.of(file, indexes);
				}
				
			} catch (IOException ex) {
				throw new RuntimeException(ex);
			}
		}
		return Pair.of(file, indexes);
	}
	
	public static Triple<File, File, Set<Integer>> getDownInfo(File f) {
		//File f = path.toFile();
		File file = new File(f.getAbsolutePath() + ".down.idx");
		Set<Integer> indexes = null;
		if (file.exists()) {
			try {
				List<String> lines = Files.readAllLines(file.toPath());
				if(lines.isEmpty()){
					indexes = new HashSet();
					for (String index : lines) {
					indexes.add(Integer.valueOf(index));
				}
				return Triple.of(f,file, indexes);
				}
				
			} catch (IOException ex) {
				throw new RuntimeException(ex);
			}
		}
		return Triple.of(f,file, indexes);
	}

	@Override
	public void run() {
		try {
			FileSegmentsDataModel segments = P2PCosUtils.getFileSegment(model);
			FileUtil.storeFile(data, segments.start, segments.blockData.length, segments.blockData);
			FileUtil.concurentAppend(infoFile, (segments.blockIndex+"\n").getBytes());
		} catch (Exception ex) {
			Logger.getLogger(CosFileSegmentsGetProcessor.class.getName()).log(Level.SEVERE, null, ex);
			errorTag.set(true);
		} finally {
			// 子线程中，业务处理完成后，利用countDown的特性，计数器减一操作
			countDownLatch.countDown();
		}
		

		// 子阻塞，直到其他子线程完成操作
		try {
			errorCountDown.await();
		} catch (Exception e) {
			errorTag.set(true);
		}
		//log.info("handleTestTwo-子线程执行完成");
		if (errorTag.get()) {
			// 抛出异常，回滚数据
			throw new RuntimeException("子线程业务执行异常 ->" + model);
		}
	}

}
