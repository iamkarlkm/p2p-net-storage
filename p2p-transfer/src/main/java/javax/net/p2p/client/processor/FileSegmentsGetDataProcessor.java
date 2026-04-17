
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
import javax.net.p2p.utils.P2PUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;

/**
 * 分片下载处理器（内存数据版）：对单个分片数据进行下载并写入结果。
 *
 * <p>作为 Runnable 在多线程下载时被调度执行。</p>
 */
public class FileSegmentsGetDataProcessor implements Runnable {

	private final P2PUtils node;

	private final FileSegmentsDataModel model;

	private final CountDownLatch countDownLatch;

	private final CountDownLatch errorCountDown;

	private final AtomicBoolean errorTag;

	private final byte[] data;

	private final RandomAccessFile infoFile;

	public FileSegmentsGetDataProcessor(P2PUtils node, FileSegmentsDataModel model, byte[] data,RandomAccessFile infoFile, CountDownLatch countDownLatch, CountDownLatch errorCountDown, AtomicBoolean errorTag) {
		this.node = node;
		this.model = model;
		this.countDownLatch = countDownLatch;
		this.errorCountDown = errorCountDown;
		this.errorTag = errorTag;
		this.data = data;
		this.infoFile = infoFile;
	}

	
	@Override
	public void run() {
		try {
			FileSegmentsDataModel segments = node.getFileSegment(model);
			System.arraycopy(segments.blockData, 0, data, (int) segments.start, segments.blockSize);
			FileUtil.concurentAppend(infoFile, (segments.blockIndex+"\n").getBytes());
		} catch (Exception ex) {
			Logger.getLogger(FileSegmentsGetDataProcessor.class.getName()).log(Level.SEVERE, null, ex);
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
