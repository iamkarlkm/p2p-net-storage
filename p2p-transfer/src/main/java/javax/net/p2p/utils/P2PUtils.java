/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package javax.net.p2p.utils;

import com.google.common.collect.Lists;
import java.io.File;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.client.pooled.MyClient;
import javax.net.p2p.client.pooled.P2PNode;
import javax.net.p2p.client.processor.FileSegmentsGetProcessor;
import javax.net.p2p.client.processor.FileSegmentsPutProcessor;
import javax.net.p2p.config.P2PConfig;
import javax.net.p2p.interfaces.P2PFileService;
import javax.net.p2p.model.FileSegmentsDataModel;
import javax.net.p2p.model.FileListEntry;
import javax.net.p2p.model.FileListRequest;
import javax.net.p2p.model.FileListResponse;
import javax.net.p2p.model.FileRenameRequest;
import javax.net.p2p.model.FilesCommandModel;
import javax.net.p2p.model.LssjImageModel;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.model.FileDataModel;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.time.StopWatch;
import org.apache.commons.lang3.tuple.Triple;

/**
 *
 * @author Administrator
 */
@Slf4j
public class P2PUtils  implements P2PFileService{

	

	private final P2PNode node;

	public P2PNode getNode() {
		return node;
	}

	public P2PUtils(P2PNode node) {
		this.node = node;
	}
        
        @Override
        public FileDataModel getFileStream(int storeId, String path) throws Exception {

		P2PWrapper p2p = P2PWrapper.build(P2PCommand.GET_FILE_STREAM, new FileDataModel(storeId, path));
		P2PWrapper response = (P2PWrapper) node.excute(p2p);
		if (response.getCommand().getValue() == P2PCommand.R_OK_GET_FILE_STREAM.getValue()) {
			FileDataModel payload = (FileDataModel) response.getData();
			if (payload.length != payload.data.length) {
				throw new RuntimeException("文件长度记录不一致:expected length=" + payload.data.length + ",actual length=" + payload.length);
			}
			return payload;
		}
            return null;
        }

        @Override
	public FileDataModel getFileData(int storeId, String path) throws Exception {

		P2PWrapper p2p = P2PWrapper.build(P2PCommand.GET_FILE, new FileDataModel(storeId, path));
		P2PWrapper response = (P2PWrapper) node.excute(p2p);
		if (response.getCommand().getValue() == P2PCommand.R_OK_GET_FILE.getValue()) {
			FileDataModel payload = (FileDataModel) response.getData();
			if (payload.length != payload.data.length) {
				throw new RuntimeException("文件长度记录不一致:expected length=" + payload.data.length + ",actual length=" + payload.length);
			}
			return payload;
		} else if (response.getCommand().getValue() == P2PCommand.R_OK_GET_FILE_SEGMENTS.getValue()) {

			FileSegmentsDataModel segments = (FileSegmentsDataModel) response.getData();
			Triple<File, File, Set<Integer>> downInfo = FileUtil.getDownInfoTmp(path);
			Set<Integer> indexes = downInfo.getRight();

			File md5File = new File(downInfo.getLeft().getAbsolutePath() + ".md5");
			if (!md5File.exists() && segments.md5.length() == 32) {
				Files.write(md5File.toPath(), segments.md5.getBytes());
			}
			
			                        //写入第一分段
			if(!downInfo.getLeft().exists()){
//				Files.write(downInfo.getLeft().toPath(), segments.blockData);
				FileUtil.storeFile(downInfo.getLeft(), 0, segments.blockData.length, segments.blockData);
			}
			
			String md5 = segments.md5;
			AtomicBoolean errorTag = new AtomicBoolean(false);

			try (RandomAccessFile indexFile = FileUtil.getLockedFile(downInfo.getMiddle())) {
				//分段下载
				int count = (int) (segments.length % segments.blockSize == 0 ? segments.length / segments.blockSize : segments.length / segments.blockSize + 1);

				List<Integer> execIndexes = new ArrayList();
				//跳过第一分段（已下载）
				for (int i = 1; i < count; i++) {
					if (!indexes.contains(i)) {
						execIndexes.add(i);
					}
				}

				if (execIndexes.isEmpty()) {
					
					byte[] data = Files.readAllBytes(downInfo.getLeft().toPath());
					//clean temp files
					md5File.delete();
					downInfo.getLeft().delete();
					downInfo.getMiddle().delete();
					String md5Check = SecurityUtils.toMD5(data);
					if (!md5Check.equals(md5)) {
						throw new RuntimeException("数据校验异常");
					}
					//log.info("主线程执行完成");
					return new FileDataModel(segments.storeId, md5, data);
				} else if (!md5File.exists() && segments.md5.length() == 32) {
					Files.write(md5File.toPath(), segments.md5.getBytes());
				}

				int sizeExec = execIndexes.size() / 16;//并发16线程执行
				List<List<Integer>> partitionTasks = null;
				if (sizeExec > 16) {
					partitionTasks = Lists.partition(execIndexes, sizeExec);
					log.info("并发16线程执行,并行流任务切分为{}", partitionTasks.size());
				} else {
					partitionTasks = new ArrayList();
					partitionTasks.add(execIndexes);
				}

				for (List<Integer> taskExec : partitionTasks) {
					// 设置countDown大小,与异步执行的业务数量一样，比如2个
					CountDownLatch countDownLatch = new CountDownLatch(taskExec.size());
					// 再创建一个CountDownLatch，大小固定为一，用于子线程相互等待，最后确定是否回滚
					CountDownLatch errorCountDown = new CountDownLatch(1);
					List<Thread> workers = new ArrayList();
					//				int taskExecSize = taskExec.size();
					for (int i : taskExec) {
						Thread t = new Thread(new FileSegmentsGetProcessor(this,
								new FileSegmentsDataModel(segments, i), downInfo.getLeft(), indexFile, countDownLatch, errorCountDown, errorTag));
						workers.add(t);
					}
					//tasks.add(Triple.of(workersExec, countDownLatch, errorCountDown));
					try {
						//运行线程
						workers.forEach(Thread::start);
						//等待线程完成
						countDownLatch.await();
					} catch (Exception e) {
						log.error(e.getMessage());
					} finally {
						// 主线程业务执行完成后，执行errorCountDown计时器减一，使得所有阻塞的子线程，继续执行进入到异常判断中
						errorCountDown.countDown();
					}
				}
			} catch (Exception e) {
				log.error(e.getMessage());
			}

			// 如果出现异常
			if (errorTag.get()) {
				throw new RuntimeException("多线程上传文件执行出现异常");
			}
			
			byte[] data = Files.readAllBytes(downInfo.getLeft().toPath());
			//clean temp files
			md5File.delete();
			downInfo.getLeft().delete();
			downInfo.getMiddle().delete();
			String md5Check = SecurityUtils.toMD5(data);
			if (!md5Check.equals(md5)) {
				throw new RuntimeException("数据校验异常");
			}
			log.info("主线程执行完成");
			return new FileDataModel(segments.storeId, md5, data);
		} else if (response.getCommand().getValue() == P2PCommand.STD_ERROR.getValue()) {

			log.error(response.toString());
			//FileUtils.writeStringToFile(new File("e:/test/error.log"), response.getData().toString());
			throw new RuntimeException(response.getData().toString());
		}
		//}
		throw new RuntimeException("未知回应消息：" + response);
	}

	public FileSegmentsDataModel getFileSegment(FileSegmentsDataModel segments) throws Exception {

		P2PWrapper p2p = P2PWrapper.build(P2PCommand.GET_FILE_SEGMENTS, segments);
		P2PWrapper response = (P2PWrapper) node.excute(p2p);

		if (response.getCommand().getValue() == P2PCommand.R_OK_GET_FILE_SEGMENTS.getValue()) {
			FileSegmentsDataModel payload = (FileSegmentsDataModel) response.getData();

			return payload;
		} else if (response.getCommand().getValue() == P2PCommand.STD_ERROR.getValue()) {

			//retry 3 times
			int count = 3;
			while (count-- > 0) {
				response = (P2PWrapper) node.excute(p2p);
				if (response.getCommand().getValue() == P2PCommand.R_OK_GET_FILE_SEGMENTS.getValue()) {
					FileSegmentsDataModel payload = (FileSegmentsDataModel) response.getData();

					return payload;
				}
			}
			throw new RuntimeException(response.getData().toString());
		}
		//}
		throw new RuntimeException("未知回应消息：" + response);
	}

	public void getFileData(int storeId, String path, File localFie) throws Exception {
		getFileData(storeId, path, localFie.toPath());
	}

	public void getFileData(int storeId, String path, Path localFie) throws Exception {
		//启用分块传输、断点续传
		Triple<File, File, Set<Integer>> downInfo = FileUtil.getDownInfo(localFie.toFile());
		System.out.println(downInfo);
		if (!downInfo.getRight().isEmpty()) {//断点续传
			Set<Integer> indexes = downInfo.getRight();
			FileDataModel info = infoFile(storeId, path, null);
			//分段下载
			int count = (int) (info.length % info.blockSize == 0 ? info.length / info.blockSize : info.length / info.blockSize + 1);
			File md5File = new File(downInfo.getLeft().getAbsolutePath() + ".md5");
			FileSegmentsDataModel segments = null;
			for (int i = 0; i < count; i++) {
				if (!indexes.contains(i)) {
					segments = new FileSegmentsDataModel(storeId, path);
					segments.blockSize = info.blockSize;
					segments.md5 = null;
					if (md5File.exists()) {
						segments.md5 = FileUtils.readFileToString(md5File, "UTF-8").trim();
//						segments.md5 = Files.readString(md5File.toPath()).trim();
						if (segments.md5.length() != 32) {
							segments.md5 = null;//重新获取MD5
						}
					}
					segments.initByIndex(i);
					indexes.remove(i);
					break;
				}
			}
			if (segments != null) {//下载第一个缺失分段数据
				segments = getFileSegment(segments);
				FileUtil.storeFile(downInfo.getLeft(), segments.start, segments.blockData.length, segments.blockData);
			}
			
			List<Integer> execIndexes = new ArrayList();
			for (int i = 0; i < count; i++) {
				if (!indexes.contains(i)) {
					execIndexes.add(i);
				}
			}

			if (execIndexes.isEmpty()) {
				//clean temp files
				md5File.delete();
				downInfo.getMiddle().delete();
				System.out.println("cache delete "+downInfo);
				if (segments!=null && segments.md5!=null) {
                                    String md5Check = SecurityUtils.getFileMD5String(downInfo.getLeft());
                                    if (!md5Check.equals(segments.md5)) {
					throw new RuntimeException("数据校验异常");
                                    }
				}
				//log.info("主线程执行完成");
				return;
			} else if (!md5File.exists() && segments.md5!=null && segments.md5.length() == 32) {
				Files.write(md5File.toPath(), segments.md5.getBytes());
			}
                        
			try (RandomAccessFile indexFile = FileUtil.getLockedFile(downInfo.getMiddle())) {
				AtomicBoolean errorTag = new AtomicBoolean(false);

				int sizeExec = execIndexes.size() / 16;//并发16线程执行
				List<List<Integer>> partitionTasks = null;
				if (sizeExec > 16) {
					partitionTasks = Lists.partition(execIndexes, sizeExec);
					log.info("并发16线程执行,并行流任务切分为{}", partitionTasks.size());
				} else {
					partitionTasks = new ArrayList();
					partitionTasks.add(execIndexes);
				}

				for (List<Integer> taskExec : partitionTasks) {
					// 设置countDown大小,与异步执行的业务数量一样，比如2个
					CountDownLatch countDownLatch = new CountDownLatch(taskExec.size());
					// 再创建一个CountDownLatch，大小固定为一，用于子线程相互等待，最后确定是否回滚
					CountDownLatch errorCountDown = new CountDownLatch(1);
					List<Thread> workers = new ArrayList();
					//				int taskExecSize = taskExec.size();
					for (int i : taskExec) {
						Thread t = new Thread(new FileSegmentsGetProcessor(this,
								new FileSegmentsDataModel(segments, i), downInfo.getLeft(), indexFile, countDownLatch, errorCountDown, errorTag));
						workers.add(t);
					}
					//tasks.add(Triple.of(workersExec, countDownLatch, errorCountDown));
					try {
						//运行线程
						workers.forEach(Thread::start);
						//等待线程完成
						countDownLatch.await();
					} catch (Exception e) {
						log.error(e.getMessage());
					} finally {
						// 主线程业务执行完成后，执行errorCountDown计时器减一，使得所有阻塞的子线程，继续执行进入到异常判断中
						errorCountDown.countDown();
					}
				}

				// 如果出现异常
				if (errorTag.get()) {
					throw new RuntimeException("多线程上传文件执行出现异常");
				}
				//clean temp files
				md5File.delete();
				downInfo.getMiddle().delete();
				System.out.println("clean temp files "+downInfo);
				if (segments!=null && segments.md5!=null) {
                                    String md5Check = SecurityUtils.getFileMD5String(downInfo.getLeft());
                                    if (!md5Check.equals(segments.md5)) {
					throw new RuntimeException("数据校验异常");
                                    }
				}
				log.info("主线程执行完成");
				return;
			}

			//FileDataModel m = new FileDataModel(segments, localFie);
		}
		P2PWrapper p2p = P2PWrapper.build(P2PCommand.GET_FILE, new FileDataModel(storeId, path));
		P2PWrapper response = (P2PWrapper) node.excute(p2p);
		
		if (response.getCommand().getValue() == P2PCommand.R_OK_GET_FILE.getValue()) {
			FileDataModel payload = (FileDataModel) response.getData();
			if (payload.length != payload.data.length) {
				throw new RuntimeException("文件长度记录不一致:expected length=" + payload.data.length + ",actual length=" + payload.length);
			}
			Files.write(localFie, payload.data);
			return;
		} else if (response.getCommand().getValue() == P2PCommand.R_OK_GET_FILE_SEGMENTS.getValue()) {
			Set<Integer> indexes = downInfo.getRight();
			FileSegmentsDataModel segments = (FileSegmentsDataModel) response.getData();
			File md5File = new File(downInfo.getLeft().getAbsolutePath() + ".md5");
			Files.write(md5File.toPath(), segments.md5.getBytes());
			//分段下载
			int count = (int) (segments.length % segments.blockSize == 0 ? segments.length / segments.blockSize : segments.length / segments.blockSize + 1);

			AtomicBoolean errorTag = new AtomicBoolean(false);
			FileUtil.storeFile(downInfo.getLeft(), segments.start, segments.blockData.length, segments.blockData);
			FileUtil.append(downInfo.getMiddle(), "0\n".getBytes());
			indexes.add(segments.blockIndex);
			String md5 = segments.md5;
			List<Integer> execIndexes = new ArrayList();
			//跳过第一分段（已下载）
			for (int i = 1; i < count; i++) {
				if (!indexes.contains(i)) {
					execIndexes.add(i);
				}
			}

			if (execIndexes.isEmpty()) {
				//clean temp files
				md5File.delete();
				downInfo.getMiddle().delete();
				String md5Check = SecurityUtils.getFileMD5String(downInfo.getLeft());
				if (!md5Check.equals(md5)) {
					throw new RuntimeException("数据校验异常");
				}
				//log.info("主线程执行完成");
				return;
			} else if (!md5File.exists() && segments.md5.length() == 32) {
				Files.write(md5File.toPath(), segments.md5.getBytes());
			}
			try (RandomAccessFile indexFile = FileUtil.getLockedFile(downInfo.getMiddle())) {

				int sizeExec = execIndexes.size() / 16;//并发16线程执行
				List<List<Integer>> partitionTasks = null;
				if (sizeExec > 16) {
					partitionTasks = Lists.partition(execIndexes, sizeExec);
					log.info("并发16线程执行,并行流任务切分为{}", partitionTasks.size());
				} else {
					partitionTasks = new ArrayList();
					partitionTasks.add(execIndexes);
				}

				for (List<Integer> taskExec : partitionTasks) {
					// 设置countDown大小,与异步执行的业务数量一样，比如2个
					CountDownLatch countDownLatch = new CountDownLatch(taskExec.size());
					// 再创建一个CountDownLatch，大小固定为一，用于子线程相互等待，最后确定是否回滚
					CountDownLatch errorCountDown = new CountDownLatch(1);
					List<Thread> workers = new ArrayList();
					//				int taskExecSize = taskExec.size();
					for (int i : taskExec) {
						Thread t = new Thread(new FileSegmentsGetProcessor(this,
								new FileSegmentsDataModel(segments, i), downInfo.getLeft(), indexFile, countDownLatch, errorCountDown, errorTag));
						workers.add(t);
					}
					//tasks.add(Triple.of(workersExec, countDownLatch, errorCountDown));
					try {
						//运行线程
						workers.forEach(Thread::start);
						//等待线程完成
						countDownLatch.await();
					} catch (Exception e) {
						log.error(e.getMessage());
					} finally {
						// 主线程业务执行完成后，执行errorCountDown计时器减一，使得所有阻塞的子线程，继续执行进入到异常判断中
						errorCountDown.countDown();
					}
				}
				// 如果出现异常
				if (errorTag.get()) {
					throw new RuntimeException("多线程下载文件执行出现异常");
				}
				//clean temp files
				md5File.delete();
				downInfo.getMiddle().delete();
				String md5Check = SecurityUtils.getFileMD5String(downInfo.getLeft());
				if (!md5Check.equals(md5)) {
					throw new RuntimeException("数据校验异常");
				}
				log.info("主线程执行完成");
				return;
			}
			//FileDataModel m = new FileDataModel(segments, localFie);
		} else if (response.getCommand().getValue() == P2PCommand.STD_ERROR.getValue()) {

			System.out.println(response);
			//FileUtils.writeStringToFile(new File("e:/test/error.log"), response.getData().toString());
			throw new RuntimeException(response.getData().toString());
		}
		//}
		throw new RuntimeException("未知回应消息：" + response);
	}

	public byte[] getLssjFileData(int storeId, String fileName, String fileName2) throws Exception {

		P2PWrapper p2p = P2PWrapper.build(P2PCommand.GET_FILE, new LssjImageModel(storeId, fileName, fileName2));
		P2PWrapper response = (P2PWrapper) node.excute(p2p);
		//P2PCommandHandler handler = (P2PCommandHandler) registryMap.get(response.getCommand());
		//System.out.println(handler);
		//if(handler!=null){
		System.out.println(fileName);
		if (response.getCommand().getValue() == P2PCommand.R_OK_GET_FILE.getValue()) {
			FileDataModel payload = (FileDataModel) response.getData();
			if (payload.length != payload.data.length) {
				throw new RuntimeException("文件长度记录不一致:expected length=" + payload.data.length + ",actual length=" + payload.length);
			}
			return payload.data;
		} else if (response.getCommand().getValue() == P2PCommand.STD_ERROR.getValue()) {

			System.out.println(response);
			//FileUtils.writeStringToFile(new File("e:/test/error.log"), response.getData().toString());
			throw new RuntimeException(response.getData().toString());
		}
		//}
		throw new RuntimeException("未知回应消息：" + response);
	}

	public void putFileData(int storeId, String path, byte[] data) throws Exception {
		if (data.length <= P2PConfig.DATA_BLOCK_SIZE) {
			P2PWrapper p2p = P2PWrapper.build(P2PCommand.PUT_FILE, new FileDataModel(storeId, path, data));
			P2PWrapper response = (P2PWrapper) node.excute(p2p);
			//P2PCommandHandler handler = (P2PCommandHandler) registryMap.get(response.getCommand());
			//System.out.println(handler);
			//if(handler!=null){
			// System.out.println(fileName);
			if (response.getCommand().getValue() == P2PCommand.STD_OK.getValue()) {
				return;
			} else if (response.getCommand().getValue() == P2PCommand.STD_ERROR.getValue()) {

				System.out.println(response);
				//FileUtils.writeStringToFile(new File("e:/test/error.log"), response.getData().toString());
				throw new RuntimeException(response.getData().toString());
			}
			//}
			throw new RuntimeException("未知回应消息：" + response);
		} else {//分段上传
			int count = data.length % P2PConfig.DATA_BLOCK_SIZE == 0 ? data.length / P2PConfig.DATA_BLOCK_SIZE : data.length / P2PConfig.DATA_BLOCK_SIZE + 1;

			Triple<File, File, Set<Integer>> upInfo = FileUtil.getUpInfoTmp(path);
			Set<Integer> indexes = upInfo.getRight();
			AtomicBoolean errorTag = new AtomicBoolean(false);
			try (RandomAccessFile indexFile = FileUtil.getLockedFile(upInfo.getMiddle())) {
				List<Integer> execIndexes = new ArrayList();
				for (int i = 0; i < count; i++) {
					if (!indexes.contains(i)) {
						execIndexes.add(i);
					}
				}

				if (execIndexes.isEmpty()) {
					//clean temp files
					upInfo.getLeft().delete();
					upInfo.getMiddle().delete();
					String md5 = SecurityUtils.toMD5(data);
					P2PWrapper p2p = P2PWrapper.build(P2PCommand.PUT_FILE_SEGMENTS_COMPLETE, new FileSegmentsDataModel(storeId, path, data.length, md5));
					P2PWrapper response = (P2PWrapper) node.excute(p2p);

					if (response.getCommand().getValue() == P2PCommand.STD_OK.getValue()) {
						return;
					} else if (response.getCommand().getValue() == P2PCommand.STD_ERROR.getValue()) {

						System.out.println(response);
						//FileUtils.writeStringToFile(new File("e:/test/error.log"), response.getData().toString());
						throw new RuntimeException(response.getData().toString());
					}
					//log.info("主线程执行完成");
					return;
				}

				int sizeExec = execIndexes.size() / 16;//并发16线程执行
				List<List<Integer>> partitionTasks = null;
				if (sizeExec > 16) {
					partitionTasks = Lists.partition(execIndexes, sizeExec);
					log.info("并发16线程执行,并行流任务切分为{}", partitionTasks.size());
				} else {
					partitionTasks = new ArrayList();
					partitionTasks.add(execIndexes);
				}

				for (List<Integer> taskExec : partitionTasks) {
					// 设置countDown大小,与异步执行的业务数量一样，比如2个
					CountDownLatch countDownLatch = new CountDownLatch(taskExec.size());
					// 再创建一个CountDownLatch，大小固定为一，用于子线程相互等待，最后确定是否回滚
					CountDownLatch errorCountDown = new CountDownLatch(1);
					List<Thread> workers = new ArrayList();
					//				int taskExecSize = taskExec.size();
					for (int i : taskExec) {
						Thread t = new Thread(new FileSegmentsPutProcessor(this,
								new FileSegmentsDataModel(storeId, path, P2PConfig.DATA_BLOCK_SIZE, i, data), indexFile, countDownLatch, errorCountDown, errorTag));
						workers.add(t);
					}
					//tasks.add(Triple.of(workersExec, countDownLatch, errorCountDown));
					try {
						//运行线程
						workers.forEach(Thread::start);
						//等待线程完成
						countDownLatch.await();
					} catch (Exception e) {
						log.error(e.getMessage());
					} finally {
						// 主线程业务执行完成后，执行errorCountDown计时器减一，使得所有阻塞的子线程，继续执行进入到异常判断中
						errorCountDown.countDown();
					}
				}
			} catch (Exception e) {
				log.error(e.getMessage());
			}

			// 如果出现异常
			if (errorTag.get()) {
				throw new RuntimeException("多线程上传文件执行出现异常");
			}
			upInfo.getLeft().delete();
			upInfo.getMiddle().delete();
			String md5 = SecurityUtils.toMD5(data);
			P2PWrapper p2p = P2PWrapper.build(P2PCommand.PUT_FILE_SEGMENTS_COMPLETE, new FileSegmentsDataModel(storeId, path, data.length, md5));
			P2PWrapper response = (P2PWrapper) node.excute(p2p);

			if (response.getCommand().getValue() == P2PCommand.STD_OK.getValue()) {
				return;
			} else if (response.getCommand().getValue() == P2PCommand.STD_ERROR.getValue()) {

				System.out.println(response);
				//FileUtils.writeStringToFile(new File("e:/test/error.log"), response.getData().toString());
				throw new RuntimeException(response.getData().toString());
			}
			log.info("主线程执行完成");

		}
		//return false;

	}

	public void putFileData(int storeId, String path, Path localfile) throws Exception {
		putFileData(storeId, path, localfile.toFile());
	}

	public void putFileData(int storeId, String path, File localfile) throws Exception {
		if (localfile.length() <= P2PConfig.DATA_BLOCK_SIZE) {
			P2PWrapper p2p = P2PWrapper.build(P2PCommand.PUT_FILE, new FileDataModel(storeId, path, Files.readAllBytes(localfile.toPath())));
			P2PWrapper response = (P2PWrapper) node.excute(p2p);

			if (response.getCommand().getValue() == P2PCommand.STD_OK.getValue()) {
				return;
			} else if (response.getCommand().getValue() == P2PCommand.STD_ERROR.getValue()) {

				System.out.println(response);
				//FileUtils.writeStringToFile(new File("e:/test/error.log"), response.getData().toString());
				throw new RuntimeException(response.getData().toString());
			}
			//}
			throw new RuntimeException("未知回应消息：" + response);
		} else {//分段上传
			String md5 = SecurityUtils.getFileMD5String(localfile);
			long length = localfile.length();
			int blockSize = P2PConfig.DATA_BLOCK_SIZE;
			int count = (int) (length % blockSize == 0 ? length / blockSize : length / blockSize + 1);

			Triple<File, File, Set<Integer>> upInfo = FileUtil.getUpInfoTmp(path);
			Set<Integer> indexes = upInfo.getRight();
			AtomicBoolean errorTag = new AtomicBoolean(false);
			try (RandomAccessFile indexFile = FileUtil.getLockedFile(upInfo.getMiddle())) {
				List<Integer> execIndexes = new ArrayList();
				for (int i = 0; i < count; i++) {
					if (!indexes.contains(i)) {
						execIndexes.add(i);
					}
				}

				if (execIndexes.isEmpty()) {
					//clean temp files
					//upInfo.getLeft().delete();
					upInfo.getMiddle().delete();
					P2PWrapper p2p = P2PWrapper.build(P2PCommand.PUT_FILE_SEGMENTS_COMPLETE, new FileSegmentsDataModel(storeId, path, length, md5));
					P2PWrapper response = (P2PWrapper) node.excute(p2p);

					if (response.getCommand().getValue() == P2PCommand.STD_OK.getValue()) {
						return;
					} else if (response.getCommand().getValue() == P2PCommand.STD_ERROR.getValue()) {

						System.out.println(response);
						//FileUtils.writeStringToFile(new File("e:/test/error.log"), response.getData().toString());
						throw new RuntimeException(response.getData().toString());
					}
					//log.info("主线程执行完成");
					return;
				}

				int sizeExec = execIndexes.size() / 16;//并发16线程执行
				List<List<Integer>> partitionTasks = null;
				if (sizeExec > 16) {
					partitionTasks = Lists.partition(execIndexes, sizeExec);
					log.info("并发16线程执行,并行流任务切分为{}", partitionTasks.size());
				} else {
					partitionTasks = new ArrayList();
					partitionTasks.add(execIndexes);
				}

				for (List<Integer> taskExec : partitionTasks) {
					// 设置countDown大小,与异步执行的业务数量一样，比如2个
					CountDownLatch countDownLatch = new CountDownLatch(taskExec.size());
					// 再创建一个CountDownLatch，大小固定为一，用于子线程相互等待，最后确定是否回滚
					CountDownLatch errorCountDown = new CountDownLatch(1);
					List<Thread> workers = new ArrayList();
					//				int taskExecSize = taskExec.size();
					for (int i : taskExec) {
						Thread t = new Thread(new FileSegmentsPutProcessor(this,
								new FileSegmentsDataModel(storeId, path, length, blockSize, i, md5), localfile, indexFile, countDownLatch, errorCountDown, errorTag));
						workers.add(t);
					}
					//tasks.add(Triple.of(workersExec, countDownLatch, errorCountDown));
					try {
						//运行线程
						workers.forEach(Thread::start);
						//等待线程完成
						countDownLatch.await();
					} catch (Exception e) {
						log.error(e.getMessage());
					} finally {
						// 主线程业务执行完成后，执行errorCountDown计时器减一，使得所有阻塞的子线程，继续执行进入到异常判断中
						errorCountDown.countDown();
					}
				}
			} catch (Exception e) {
				log.error(e.getMessage());
			}

			// 如果出现异常
			if (errorTag.get()) {
				throw new RuntimeException("多线程上传文件执行出现异常");
			}
			
			
	
			//upInfo.getLeft().delete();
			//upInfo.getMiddle().delete();

			P2PWrapper p2p = P2PWrapper.build(P2PCommand.PUT_FILE_SEGMENTS_COMPLETE, new FileSegmentsDataModel(storeId, path, length, md5));
			P2PWrapper response = (P2PWrapper) node.excute(p2p);

			if (response.getCommand().getValue() == P2PCommand.STD_OK.getValue()) {
				return;
			} else if (response.getCommand().getValue() == P2PCommand.STD_ERROR.getValue()) {

				System.out.println(response);
				//FileUtils.writeStringToFile(new File("e:/test/error.log"), response.getData().toString());
				throw new RuntimeException(response.getData().toString());
			}
			log.info("主线程执行完成");

		}
	}

	public void putFileSegment(FileSegmentsDataModel model) throws Exception {

		P2PWrapper p2p = P2PWrapper.build(P2PCommand.PUT_FILE_SEGMENTS, model);
		P2PWrapper response = (P2PWrapper) node.excute(p2p);

		if (response.getCommand().getValue() == P2PCommand.STD_OK.getValue()) {
			return;
		} else if (response.getCommand().getValue() == P2PCommand.INVALID_DATA.getValue()) {
			//retry 3 times
			log.warn("retry 3 times -> "+p2p);
			int count = 3;
			while (count-- > 0) {
				response = (P2PWrapper) node.excute(p2p);
				if (response.getCommand().getValue() == P2PCommand.STD_OK.getValue()) {

					return;
				}
			}
			throw new RuntimeException(response.getData().toString());
		} else if (response.getCommand().getValue() == P2PCommand.STD_ERROR.getValue()) {

			System.out.println(response);
			//FileUtils.writeStringToFile(new File("e:/test/error.log"), response.getData().toString());
			throw new RuntimeException(response.getData().toString());
		}
		//}
		throw new RuntimeException("未知回应消息：" + response);
	}

	public void forcePutFileData(int storeId, String path, byte[] data) throws Exception {
		P2PWrapper p2p = P2PWrapper.build(P2PCommand.REMOVE_FILE, new FileDataModel(storeId, path));
		P2PWrapper response = (P2PWrapper) node.excute(p2p);

		if (response.getCommand().getValue() == P2PCommand.STD_OK.getValue()) {
			putFileData(storeId, path, data);
			return;
		} else if (response.getCommand().getValue() == P2PCommand.STD_ERROR.getValue()) {

			System.out.println(response);

			throw new RuntimeException(response.getData().toString());
		}
		throw new RuntimeException("未知回应消息：" + response);
	}

	public boolean remove(int storeId, String path) throws Exception {

		P2PWrapper p2p = P2PWrapper.build(P2PCommand.REMOVE_FILE, new FileDataModel(storeId,  path,0));
		P2PWrapper response = (P2PWrapper) node.excute(p2p);
		if (response.getCommand().getValue() == P2PCommand.STD_OK.getValue()) {

			return true;
		} else if (response.getCommand().getValue() == P2PCommand.STD_ERROR.getValue()) {

			return false;
		}
		throw new RuntimeException("未知回应消息：" + response);
	}

	public boolean check(int storeId, String path, long length) throws Exception {

		P2PWrapper p2p = P2PWrapper.build(P2PCommand.CHECK_FILE, new FileDataModel(storeId, path, length));
		P2PWrapper response = (P2PWrapper) node.excute(p2p);
		if (response.getCommand().getValue() == P2PCommand.STD_OK.getValue()) {

			return true;
		} else if (response.getCommand().getValue() == P2PCommand.STD_ERROR.getValue()) {

			return false;
		}
		throw new RuntimeException("未知回应消息：" + response);
	}

	public boolean checkWithMd5(int storeId, String path, long length, String md5) throws Exception {

		P2PWrapper p2p = P2PWrapper.build(P2PCommand.CHECK_FILE, new FileDataModel(storeId, path, length, md5));
		P2PWrapper response = (P2PWrapper) node.excute(p2p);
		if (response.getCommand().getValue() == P2PCommand.STD_OK.getValue()) {

			return true;
		} else if (response.getCommand().getValue() == P2PCommand.STD_ERROR.getValue()) {

			return false;
		}
		throw new RuntimeException("未知回应消息：" + response);
	}

	/**
	 * 
	 * @param storeId
	 * @param path
	 * @param md5 null-不返回MD5，""-需返回md5
	 * @return
	 * @throws Exception
	 */
	public FileDataModel infoFile(int storeId, String path, String md5) throws Exception {

		P2PWrapper p2p = P2PWrapper.build(P2PCommand.INFO_FILE, new FileDataModel(storeId, path, 0, md5));
		P2PWrapper response = (P2PWrapper) node.excute(p2p);
		if (response.getCommand().getValue() == P2PCommand.STD_OK.getValue()) {
			FileDataModel payload = (FileDataModel) response.getData();
			return payload;
		} else if (response.getCommand().getValue() == P2PCommand.STD_ERROR.getValue()) {
			return null;
		}
		throw new RuntimeException("未知回应消息：" + response);
	}

	public boolean exists(int storeId, String path) throws Exception {

		P2PWrapper p2p = P2PWrapper.build(P2PCommand.FILE_EXISTS, new FileDataModel(storeId, path, 0, null));
		P2PWrapper response = (P2PWrapper) node.excute(p2p);
		//P2PCommandHandler handler = (P2PCommandHandler) registryMap.get(response.getCommand());
		//System.out.println(handler);
		//if(handler!=null){
		if (response.getCommand().getValue() == P2PCommand.STD_OK.getValue()) {

			return true;
		} else if (response.getCommand().getValue() == P2PCommand.STD_ERROR.getValue()) {
			return false;
		}
		//}
		throw new RuntimeException("未知回应消息：" + response);
	}

	public boolean mkdirs(int storeId, String path) throws Exception {

		P2PWrapper p2p = P2PWrapper.build(P2PCommand.FILE_MKDIRS, new FileDataModel(storeId, path, 0, null));
		P2PWrapper response = (P2PWrapper) node.excute(p2p);
		//P2PCommandHandler handler = (P2PCommandHandler) registryMap.get(response.getCommand());
		//System.out.println(handler);
		//if(handler!=null){
		if (response.getCommand().getValue() == P2PCommand.STD_OK.getValue()) {

			return true;
		} else if (response.getCommand().getValue() == P2PCommand.STD_ERROR.getValue()) {
			return false;
		}
		//}
		throw new RuntimeException("未知回应消息：" + response);
	}

	public boolean rename(int storeId, String src, String dst) throws Exception {

		P2PWrapper p2p = P2PWrapper.build(P2PCommand.FILE_RENAME, new FileRenameRequest(storeId, src, dst));
		P2PWrapper response = (P2PWrapper) node.excute(p2p);
		//P2PCommandHandler handler = (P2PCommandHandler) registryMap.get(response.getCommand());
		//System.out.println(handler);
		//if(handler!=null){
		if (response.getCommand().getValue() == P2PCommand.STD_OK.getValue()) {

			return true;
		} else if (response.getCommand().getValue() == P2PCommand.STD_ERROR.getValue()) {
			return false;
		}
		//}
		throw new RuntimeException("未知回应消息：" + response);
	}

	public List<String> ls(int storeId, String path) throws Exception {

		P2PWrapper p2p = P2PWrapper.build(P2PCommand.FILE_LIST, new FileListRequest(storeId, path, 1, 100));
		P2PWrapper response = (P2PWrapper) node.excute(p2p);
		//P2PCommandHandler handler = (P2PCommandHandler) registryMap.get(response.getCommand());
		//System.out.println(handler);
		//if(handler!=null){
		if (response.getCommand().getValue() == P2PCommand.STD_OK.getValue()) {
			FileListResponse payload = (FileListResponse) response.getData();
			if (payload == null || payload.items == null) {
				return null;
			}
			List<String> list = new ArrayList<>();
			for (FileListEntry e : payload.items) {
				list.add(e.path);
			}
			return list;
		} else if (response.getCommand().getValue() == P2PCommand.STD_ERROR.getValue()) {
			return null;
		}
		//}
		throw new RuntimeException("未知回应消息：" + response);
	}

	public String echo(String msg) throws Exception {

		P2PWrapper p2p = P2PWrapper.build(P2PCommand.ECHO, msg);
		P2PWrapper response = (P2PWrapper) node.excute(p2p);
		//P2PCommandHandler handler = (P2PCommandHandler) registryMap.get(response.getCommand());
		//System.out.println(handler);
		//if(handler!=null){
		if (response.getCommand().getValue() == P2PCommand.ECHO.getValue()) {

			return (String) response.getData();
		}
		//}
		throw new RuntimeException("服务器内部错误：" + response.getData());
	}

	public void test(int count) throws Exception {
		StopWatch stopWatch = new StopWatch();
		System.out.println("test执行开始...");
		stopWatch.start();
		for (int i = 0; i < count; i++) {
			//System.out.println("echo -> "+i);
			echo("test-" + i);
		}
		stopWatch.stop();
		// 统计执行时间（秒）
		System.out.println("执行时长：" + stopWatch.getTime() / 1000 + " 秒.");
		// 统计执行时间（毫秒）
		System.out.println("执行时长：" + stopWatch.getTime() + " 毫秒.");
		// 统计执行时间（纳秒）
		System.out.println("执行时长：" + stopWatch.getNanoTime() + " 纳秒.");
	}

//	public void main(String[] args) throws Exception {
//
//		if (args.length > 0) {
//			P2PServer.SERVER_IP = args[args.length - 1];
//		} else {
//			P2PServer.SERVER_IP = "127.0.0.1";
//			test(1000);
//			return;
//		}
//		if (args.length == 4 && "get".equals(args[0])) {
//			getFileData(2l, args[1], Paths.get(args[2]));
//			System.out.println(args[0] + " " + args[1] + " " + args[2]);
//		} else if (args.length == 4 && "put".equals(args[0])) {
//			byte[] bytes = Files.readAllBytes(Paths.get(args[2]));
//			putFileData(2l, args[1], bytes);
//			System.out.println(args[0] + " " + args[1] + " " + args[2]);
//		} else if (args.length == 3 && "exists".equals(args[0])) {
//			System.out.println(args[0] + " " + args[1] + " -> " + exists(2l, args[1]));
//		} else if (args.length == 3 && "rm".equals(args[0])) {
//			System.out.println(args[0] + " " + args[1] + " -> " + remove(2l, args[1]));
//		} else if (args.length == 4 && "rename".equals(args[0])) {
//			rename(2l, args[1], args[2]);
//			System.out.println(args[0] + " " + args[1] + " -> " + " " + args[2]);
//		} else if (args.length == 3 && "mkdirs".equals(args[0])) {
//			System.out.println(args[0] + " " + args[1] + " -> " + mkdirs(2l, args[1]));
//		} else if (args.length == 3 && "test".equals(args[0])) {
//			test(Integer.parseInt(args[1]));
//		}
//	}
	
	public static void main1(String[] args) throws Exception {
		try (MyClient client = MyClient.getInstance();
				P2PNode p2PNode = client.getNode(
				new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 6060))) {
			P2PUtils p2PUtils = new P2PUtils(p2PNode);
			File src = new File("E:\\BaiduNetdiskDownload\\navicat121_premium_cs_x64.exe");
			File dest = new File("e:/p2p_test/test.seg.bin.res");
			System.out.println(SecurityUtils.getFileMD5String(src));
			p2PUtils.putFileData(11, "/test.seg.bin", src);
			p2PUtils.getFileData(11, "/test.seg.bin", dest);
			System.out.println(SecurityUtils.getFileMD5String(dest));
			
			Thread.sleep(300*1000);
		}

// 		byte[] d = FileUtil.loadFile(src, 0, 8);
//		System.out.println(Hex.encodeHexString(d));
		
	}
    
    

}
