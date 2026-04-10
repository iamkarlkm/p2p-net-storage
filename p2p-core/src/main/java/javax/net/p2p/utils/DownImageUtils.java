/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package javax.net.p2p.utils;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Paths;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.client.P2PClientTcp;
import javax.net.p2p.model.FileDataModel;
import javax.net.p2p.model.LssjImageModel;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.server.AbstractP2PServer;
import javax.net.p2p.server.P2PServerTcp;

/**
 *
 * @author Administrator
 */
public class DownImageUtils {


    public static byte[] getImageData(P2PClientTcp client,int storeId, String fileName, String fileName2) throws Exception {
        
        P2PWrapper p2p = P2PWrapper.build(P2PCommand.GET_FILE, new LssjImageModel(storeId, fileName, fileName2));
        P2PWrapper response = (P2PWrapper) client.excute(p2p);
        //P2PCommandHandler handler = (P2PCommandHandler) registryMap.get(response.getCommand());
        //System.out.println(handler);
        //if(handler!=null){
        System.out.println(fileName);
            if (response.getCommand().getValue() == P2PCommand.R_OK_GET_FILE.getValue()) {
               FileDataModel payload = (FileDataModel) response.getData();
                if (payload.length != payload.data.length) {
                    throw new RuntimeException("文件长度记录不一致:expected length="+payload.data.length+",actual length="+payload.length);
                }
                return payload.data;
            } else if (response.getCommand().getValue() == P2PCommand.STD_ERROR.getValue()) {
                
                System.out.println(response);
                //FileUtils.writeStringToFile(new File("e:/test/error.log"), response.getData().toString());
                throw new RuntimeException(response.getData().toString());
            }
        //}
        throw new RuntimeException("未知回应消息："+response);
    }

    public static void main(String[] args) throws Exception {
//        byte[] bytes = getImageData(766l, "1.png");
//        Files.write(Paths.get("E:/VEH_IMAGES/2.png"), bytes);
        //P2PClient client = new P2PClient(InetAddress.getLocalHost(), P2PServer.SERVER_PORT);
        P2PClientTcp client = P2PClientTcp.getInstance(P2PClientTcp.class, "127.0.0.1",AbstractP2PServer.SERVER_PORT);
         byte[] bytes = getImageData(client,766, "2019-12-25/1.png", "2019-12-25/1.png");
        Files.write(Paths.get("/opt/2.jpg"), bytes);
    }
}
