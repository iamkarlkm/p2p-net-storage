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
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.client.pooled.MyClient;
import javax.net.p2p.client.pooled.P2PNode;
import javax.net.p2p.client.processor.CosFileSegmentsGetProcessor;
import javax.net.p2p.client.processor.CosFileSegmentsPutProcessor;
import javax.net.p2p.config.P2PConfig;
import javax.net.p2p.model.FileDataModel;
import javax.net.p2p.model.FileSegmentsDataModel;
import javax.net.p2p.model.HdfsCommandModel;
import javax.net.p2p.model.HdfsFileDataModel;
import javax.net.p2p.model.P2PWrapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Triple;

/**
 *
 * @author Administrator
 */
@Slf4j
public class P2PCosUtils {

    /**
     * 支队数据域tencent对象云存储服务器gaw映射,端口6060
     */
	public static String SERVER_IP = "86.84.250.66";//cos image server


	private volatile static P2PNode CLIENT;

	public static P2PNode getInstance() throws UnknownHostException {
		if (CLIENT == null) {
			synchronized (P2PCosUtils.class) {
				if (CLIENT == null) {

					CLIENT = MyClient.getInstance().getNode(new InetSocketAddress(InetAddress.getByName(SERVER_IP), 6060));
					//System.out.println(CLIENT.remoteServer);
					log.info("客户端{}创建成功,服务器节点:{}", CLIENT, CLIENT.getRemoteAddress());
				}
			}
		}
		return CLIENT;
	}

	public static FileSegmentsDataModel getFileSegment(FileSegmentsDataModel segments) throws Exception {

		P2PWrapper p2p = P2PWrapper.build(P2PCommand.GET_COS_FILE_SEGMENTS, segments);
		log.info("CosFileCheck : {} -> {} start:{}", segments.path,p2p.getCommand(),segments.start);
		P2PWrapper response = (P2PWrapper) getInstance().excute(p2p, 90, TimeUnit.SECONDS);
		log.info("CosFileCheck response: {} -> {} start:{}", segments.path,response.getCommand(),segments.start);

		if (response.getCommand().getValue() == P2PCommand.R_OK_GET_FILE_SEGMENTS.getValue()) {
			FileSegmentsDataModel payload = (FileSegmentsDataModel) response.getData();

			return payload;
		} else if (response.getCommand().getValue() == P2PCommand.STD_ERROR.getValue()) {

			//retry 3 times
			int count = 3;
			while (count-- > 0) {
				response = (P2PWrapper) getInstance().excute(p2p, 30, TimeUnit.SECONDS);
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

	public static byte[] getFileData(String path) throws Exception {

		P2PWrapper p2p = P2PWrapper.build(P2PCommand.GET_COS_FILE, new HdfsFileDataModel(path));
		log.info("CosFileGet : {} -> {}", path,p2p.getCommand());
		P2PWrapper response = (P2PWrapper) getInstance().excute(p2p, 90, TimeUnit.SECONDS);
		log.info("CosFileGet success: {} -> {}", path,response.getCommand());
		if (response.getCommand().getValue() == P2PCommand.R_OK_GET_COS_FILE.getValue()) {
			HdfsFileDataModel payload = (HdfsFileDataModel) response.getData();
			if (payload.length != payload.data.length) {
				throw new RuntimeException("文件长度记录不一致:expected length=" + payload.data.length + ",actual length=" + payload.length);
			}
			return payload.data;
		} else if (response.getCommand().getValue() == P2PCommand.R_OK_GET_FILE_SEGMENTS.getValue()) {

			FileSegmentsDataModel segments = (FileSegmentsDataModel) response.getData();
			Triple<File, File, Set<Integer>> downInfo = FileUtil.getDownInfoTmp(path);
			Set<Integer> indexes = downInfo.getRight();

//			File md5File = new File(downInfo.getLeft().getAbsolutePath() + ".md5");
//			if (!md5File.exists() && segments.md5.length() == 32) {
//				Files.write(md5File.toPath(), segments.md5.getBytes());
//			}
			String md5 = segments.md5;
			AtomicBoolean errorTag = new AtomicBoolean(false);
			
			//写入第一分段
			if(!downInfo.getLeft().exists()){
//				Files.write(downInfo.getLeft().toPath(), segments.blockData);
				FileUtil.storeFile(downInfo.getLeft(), 0, segments.blockData.length, segments.blockData);
			}

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
					//md5File.delete();
					downInfo.getLeft().delete();
					downInfo.getMiddle().delete();
					String md5Check = SecurityUtils.toMD5(data);
					if (md5 != null && !md5Check.equals(md5)) {
						throw new RuntimeException("数据校验异常");
					}
					//log.info("主线程执行完成");
					return data;
				} 
//				else if (!md5File.exists() && segments.md5.length() == 32) {
//					Files.write(md5File.toPath(), segments.md5.getBytes());
//				}

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
						Thread t = new Thread(new CosFileSegmentsGetProcessor(
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
				throw new RuntimeException("多线程下载文件执行出现异常");
			}

			byte[] data = Files.readAllBytes(downInfo.getLeft().toPath());
			//clean temp files
			//md5File.delete();
			downInfo.getLeft().delete();
			downInfo.getMiddle().delete();
			String md5Check = SecurityUtils.toMD5(data);
			if (md5 != null && !md5Check.equals(md5)) {
				throw new RuntimeException("数据校验异常");
			}
			log.info("主线程执行完成");
			return data;
		} else if (response.getCommand().getValue() == P2PCommand.STD_ERROR.getValue()) {

			log.error(response.toString());
			//FileUtils.writeStringToFile(new File("e:/test/error.log"), response.getData().toString());
			throw new RuntimeException(response.getData().toString());
		}
		throw new RuntimeException("未知回应消息：" + response);
	}

	public static boolean putFileData(String path, byte[] data) throws Exception {
		if (path == null) {
			throw new RuntimeException("path is null!");
		}
		if (data == null || data.length == 0) {
			throw new RuntimeException("data is empty!");
		}

		if (data.length <= P2PConfig.DATA_BLOCK_SIZE) {
			P2PWrapper p2p = P2PWrapper.build(P2PCommand.PUT_COS_FILE, new HdfsFileDataModel(path, data));
			log.info("CosFilePut : {} -> {}", path,p2p.getCommand());
			P2PWrapper response = (P2PWrapper) getInstance().excute(p2p, 90
					, TimeUnit.SECONDS);
		log.info("CosFilePut success: {} -> {}", path,response.getCommand());
			if (response.getCommand().getValue() == P2PCommand.STD_OK.getValue()) {
				return true;
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
					P2PWrapper p2p = P2PWrapper.build(P2PCommand.PUT_COS_FILE_SEGMENTS_COMPLETE, new FileSegmentsDataModel(0, path, data.length, md5));
					P2PWrapper response = (P2PWrapper) getInstance().excute(p2p, 30, TimeUnit.SECONDS);

					if (response.getCommand().getValue() == P2PCommand.STD_OK.getValue()) {
						return true;
					} else if (response.getCommand().getValue() == P2PCommand.STD_ERROR.getValue()) {

						System.out.println(response);
						//FileUtils.writeStringToFile(new File("e:/test/error.log"), response.getData().toString());
						throw new RuntimeException(response.getData().toString());
					}
					//log.info("主线程执行完成");
					return true;
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
						Thread t = new Thread(new CosFileSegmentsPutProcessor(
								new FileSegmentsDataModel(0, path, P2PConfig.DATA_BLOCK_SIZE, i, data), indexFile, countDownLatch, errorCountDown, errorTag));
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
			P2PWrapper p2p = P2PWrapper.build(P2PCommand.PUT_COS_FILE_SEGMENTS_COMPLETE, new FileSegmentsDataModel(0, path, data.length, md5));

			P2PWrapper response = (P2PWrapper) getInstance().excute(p2p, 30, TimeUnit.SECONDS);

			if (response.getCommand().getValue() == P2PCommand.STD_OK.getValue()) {
				return true;
			} else if (response.getCommand().getValue() == P2PCommand.STD_ERROR.getValue()) {

				log.error(response.toString());
				//FileUtils.writeStringToFile(new File("e:/test/error.log"), response.getData().toString());
				throw new RuntimeException(response.getData().toString());
			}
			log.info("主线程执行完成");

		}
		return false;

	}

	public static void putFileSegment(FileSegmentsDataModel model) throws Exception {

		P2PWrapper p2p = P2PWrapper.build(P2PCommand.PUT_COS_FILE_SEGMENTS, model);
		P2PWrapper response = (P2PWrapper) getInstance().excute(p2p, 30, TimeUnit.SECONDS);

		if (response.getCommand().getValue() == P2PCommand.STD_OK.getValue()) {
			return;
		} else if (response.getCommand().getValue() == P2PCommand.INVALID_DATA.getValue()) {
			//retry 3 times
			log.warn("retry 3 times -> " + p2p);
			int count = 3;
			while (count-- > 0) {
				response = (P2PWrapper) getInstance().excute(p2p, 30, TimeUnit.SECONDS);
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

	public static boolean moveFileFromHdfs(String path) throws Exception {
		if (path == null) {
			throw new RuntimeException("path is null!");
		}

		P2PWrapper p2p = P2PWrapper.build(P2PCommand.PUT_COS_FILE_FROM_HDFS, new HdfsFileDataModel(path));
		P2PWrapper response = (P2PWrapper) getInstance().excute(p2p, 30, TimeUnit.SECONDS);
		//P2PCommandHandler handler = (P2PCommandHandler) registryMap.get(response.getCommand());
		//System.out.println(handler);
		//if(handler!=null){
		if (response.getCommand().getValue() == P2PCommand.STD_OK.getValue()) {

			return true;
		} else if (response.getCommand().getValue() == P2PCommand.STD_ERROR.getValue()) {

			System.out.println(response);
			//FileUtils.writeStringToFile(new File("e:/test/error.log"), response.getData().toString());
			throw new RuntimeException(response.toString());
		}
		//}
		throw new RuntimeException("未知回应消息：" + response);
	}

	public static boolean remove(String path) throws Exception {

		P2PWrapper p2p = P2PWrapper.build(P2PCommand.COS_COMMAND, new HdfsCommandModel("rm", path));
		P2PWrapper response = (P2PWrapper) getInstance().excute(p2p, 30, TimeUnit.SECONDS);
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
	
	public static boolean setGetBlockSize(int blockSize) throws Exception {

		P2PWrapper p2p = P2PWrapper.build(P2PCommand.SET_FILE_SEGMENTS_GET_BLOCK_SIZE, new FileSegmentsDataModel(blockSize));
		P2PWrapper response = (P2PWrapper) getInstance().excute(p2p, 30, TimeUnit.SECONDS);
		if (response.getCommand().getValue() == P2PCommand.STD_OK.getValue()) {
			P2PConfig.DATA_GET_BLOCK_SIZE = blockSize;
			return true;
		} else if (response.getCommand().getValue() == P2PCommand.STD_ERROR.getValue()) {

			return false;
		}
		//}
		throw new RuntimeException("未知回应消息：" + response);
	}
	
	public static boolean setPutBlockSize(int blockSize) throws Exception {

		P2PWrapper p2p = P2PWrapper.build(P2PCommand.SET_FILE_SEGMENTS_PUT_BLOCK_SIZE, new FileSegmentsDataModel(blockSize));
		P2PWrapper response = (P2PWrapper) getInstance().excute(p2p, 30, TimeUnit.SECONDS);
		if (response.getCommand().getValue() == P2PCommand.STD_OK.getValue()) {
			P2PConfig.DATA_PUT_BLOCK_SIZE = blockSize;
			return true;
		} else if (response.getCommand().getValue() == P2PCommand.STD_ERROR.getValue()) {

			return false;
		}
		//}
		throw new RuntimeException("未知回应消息：" + response);
	}

	public static boolean exists(String path) throws Exception {

		P2PWrapper p2p = P2PWrapper.build(P2PCommand.COS_COMMAND, new HdfsCommandModel("exists", path));
		
		log.info("Cos exists : {} -> {}", path,p2p.getCommand());
		P2PWrapper response = (P2PWrapper) getInstance().excute(p2p, 30, TimeUnit.SECONDS);
		log.info("Cos exists success: {} -> {}", path,response.getCommand());
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

	public static boolean mkdirs(String path) throws Exception {

		P2PWrapper p2p = P2PWrapper.build(P2PCommand.COS_COMMAND, new HdfsCommandModel("mkdirs", path));
		P2PWrapper response = (P2PWrapper) getInstance().excute(p2p, 30, TimeUnit.SECONDS);
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

	public static boolean rename(String src, String dst) throws Exception {

		P2PWrapper p2p = P2PWrapper.build(P2PCommand.COS_COMMAND, new HdfsCommandModel("rename", src, dst));
		P2PWrapper response = (P2PWrapper) getInstance().excute(p2p, 30, TimeUnit.SECONDS);
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

	public static List<String> ls(String path) throws Exception {

		P2PWrapper p2p = P2PWrapper.build(P2PCommand.COS_COMMAND, new HdfsCommandModel("ls", path));
		P2PWrapper response = (P2PWrapper) getInstance().excute(p2p, 30, TimeUnit.SECONDS);
		//P2PCommandHandler handler = (P2PCommandHandler) registryMap.get(response.getCommand());
		//System.out.println(handler);
		//if(handler!=null){
		if (response.getCommand().getValue() == P2PCommand.STD_OK.getValue()) {
			HdfsCommandModel<List<String>> payload = (HdfsCommandModel) response.getData();
			return payload.getData();
		} else if (response.getCommand().getValue() == P2PCommand.STD_ERROR.getValue()) {
			return null;
		}
		//}
		throw new RuntimeException("未知回应消息：" + response);
	}

	public static boolean check(String path, long length) throws Exception {

		P2PWrapper p2p = P2PWrapper.build(P2PCommand.CHECK_COS_FILE, new FileDataModel(0, path, length));
		
		log.info("CosFileCheck : {} -> {}", path,p2p.getCommand());
		P2PWrapper response = (P2PWrapper) getInstance().excute(p2p, 30, TimeUnit.SECONDS);
		log.info("CosFileCheck success: {} -> {}", path,response.getCommand());
		if (response.getCommand().getValue() == P2PCommand.STD_OK.getValue()) {

			return true;
		} else if (response.getCommand().getValue() == P2PCommand.STD_ERROR.getValue()) {

			return false;
		}
		throw new RuntimeException("未知回应消息：" + response);
	}

	public static boolean checkWithMd5(String path, long length, String md5) throws Exception {

		P2PWrapper p2p = P2PWrapper.build(P2PCommand.CHECK_COS_FILE, new FileDataModel(0, path, length, md5));
		
		log.info("CosFileCheckWithMd5 : {} -> {}", path,p2p.getCommand());
		P2PWrapper response = (P2PWrapper) getInstance().excute(p2p, 30, TimeUnit.SECONDS);
		log.info("CosFileCheckWithMd5 success: {} -> {}", path,response.getCommand());
		if (response.getCommand().getValue() == P2PCommand.STD_OK.getValue()) {

			return true;
		} else if (response.getCommand().getValue() == P2PCommand.STD_ERROR.getValue()) {

			return false;
		}
		throw new RuntimeException("未知回应消息：" + response);
	}

	public static void main(String[] args) throws Exception {
		//        byte[] bytes = getImageData(766l, "1.png");
		//        Files.write(Paths.get("E:/VEH_IMAGES/2.png"), bytes);
		if (args.length > 0) {
			SERVER_IP = args[args.length - 1];
		}

		if (args.length == 3 && "ls".equals(args[0])) {
			List<String> list = ls(args[1]);
			System.out.println(args[0] + " " + args[1] + " ->\n" + list);
		}else if (args.length == 3 && "setGetBlockSize".equals(args[0])) {
			setGetBlockSize(Integer.parseInt(args[1]));
			System.out.println(args[0] + " " + args[1] + " -> ok");
		} else if (args.length == 3 && "setPutBlockSize".equals(args[0])) {
			setPutBlockSize(Integer.parseInt(args[1]));
			System.out.println(args[0] + " " + args[1] + " -> ok");
		} else if (args.length == 4 && "get".equals(args[0])) {
			byte[] bytes = getFileData(args[1]);
			Files.write(Paths.get(args[2]), bytes);
			System.out.println(args[0] + " " + args[1] + " " + args[2]);
		} else if (args.length == 4 && "put".equals(args[0])) {
			byte[] bytes = Files.readAllBytes(Paths.get(args[2]));
			putFileData(args[1], bytes);
			System.out.println(args[0] + " " + args[1] + " " + args[2]);
		} else if (args.length == 3 && "exists".equals(args[0])) {
			System.out.println(args[0] + " " + args[1] + " -> " + exists(args[1]));
		} else if (args.length == 3 && "rm".equals(args[0])) {
			System.out.println(args[0] + " " + args[1] + " -> " + remove(args[1]));
		} else if (args.length == 4 && "rename".equals(args[0])) {
			rename(args[1], args[2]);
			System.out.println(args[0] + " " + args[1] + " -> " + " " + args[2]);
		} else if (args.length == 3 && "mkdirs".equals(args[0])) {
			System.out.println(args[0] + " " + args[1] + " -> " + mkdirs(args[1]));
		}
		
		MyClient.getInstance().close();
	}
}
