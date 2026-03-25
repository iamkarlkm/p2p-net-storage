/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package javax.net.p2p.utils;

import java.nio.file.Files;
import java.nio.file.Paths;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.client.P2PClient;
import javax.net.p2p.model.FileDataModel;
import javax.net.p2p.model.FilesCommandModel;
import javax.net.p2p.model.LssjCheckModel;
import javax.net.p2p.model.LssjImageModel;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.server.P2PServerTcp;
import org.apache.commons.lang3.time.StopWatch;

/**
 *
 * @author Administrator
 */
public class P2PLssjUtils {

    public final static String SERVER_IP = "86.85.160.18";//车管所历史数据服务器
	
	public static byte[] getLssjFileData(int storeId, String fileName, String fileName2) throws Exception {

		P2PWrapper p2p = P2PWrapper.build(P2PCommand.GET_LSSJ_FILE, new LssjImageModel(storeId, fileName, fileName2));
		P2PWrapper response = (P2PWrapper) P2PClient.getInstance().excute(p2p);
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

	

	

	public static byte[] checkWithMd5(int storeId, String md5, String path,String path2, long length) throws Exception {

		P2PWrapper p2p = P2PWrapper.build(P2PCommand.CHECK_LSSJ_FILE, new LssjCheckModel(storeId, md5, path, path2));
		P2PWrapper response = (P2PWrapper) P2PClient.getInstance().excute(p2p);
		if (response.getCommand().getValue() == P2PCommand.STD_OK.getValue()) {

			return null;
		} else if (response.getCommand().getValue() == P2PCommand.R_OK_GET_FILE.getValue()) {
			FileDataModel payload = (FileDataModel) response.getData();
			if (payload.length != payload.data.length) {
				throw new RuntimeException("文件长度记录不一致:expected length=" + payload.data.length + ",actual length=" + payload.length);
			}
			return payload.data;
		}
		throw new RuntimeException("未知回应消息：" + response);
	}

	public static boolean exists(int storeId, String path) throws Exception {

		P2PWrapper p2p = P2PWrapper.build(P2PCommand.FILES_COMMAND, new FilesCommandModel(storeId, "exists", path));
		P2PWrapper response = (P2PWrapper) P2PClient.getInstance().excute(p2p);
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

	

	public static String echo(String msg) throws Exception {

		P2PWrapper p2p = P2PWrapper.build(P2PCommand.ECHO, msg);
		P2PWrapper response = (P2PWrapper) P2PClient.getInstance().excute(p2p);
		//P2PCommandHandler handler = (P2PCommandHandler) registryMap.get(response.getCommand());
		//System.out.println(handler);
		//if(handler!=null){
		if (response.getCommand().getValue() == P2PCommand.ECHO.getValue()) {

			return (String) response.getData();
		}
		//}
		throw new RuntimeException("服务器内部错误：" + response.getData());
	}

	public static void test(int count) throws Exception {
		StopWatch stopWatch = new StopWatch();
		System.out.println("test执行开始...");
		stopWatch.start();
		for (int i = 0; i < count; i++) {
			echo("test-" + i);
		}
		stopWatch.stop();
		// 统计执行时间（秒）
		System.out.println("执行时长：" + stopWatch.getTime()/1000 + " 秒.");
		// 统计执行时间（毫秒）
		System.out.println("执行时长：" + stopWatch.getTime() + " 毫秒.");
		// 统计执行时间（纳秒）
		System.out.println("执行时长：" + stopWatch.getNanoTime() + " 纳秒.");
	}

	

	public static void main(String[] args) throws Exception {

		if (args.length > 0) {
			//P2PServerTcp.SERVER_IP = args[args.length - 1];
		}else{
			//P2PServerTcp.SERVER_IP = "127.0.0.1";
			test(100000);
			return;
		}
//ch.qos.logback.classic.encoder.PatternLayoutEncoder d;
		if (args.length == 5 && "get".equals(args[0])) {
			byte[] bytes = getLssjFileData(2, args[1], args[2]);
			Files.write(Paths.get(args[2]), bytes);
			System.out.println(args[0] + " " + args[1] + " " + args[2]);
		}  else if (args.length == 3 && "exists".equals(args[0])) {
			System.out.println(args[0] + " " + args[1] + " -> " + exists(2, args[1]));
		}  else if (args.length == 3 && "test".equals(args[0])) {
			test(Integer.parseInt(args[1]));
		}
	}

}
