/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package javax.net.p2p.server;

import com.giyo.chdfs.CosUtil;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;


/**
 *
 * @author Administrator
 */
public class ImageFileServer {
    
    static Log log = LogFactory.getLog(ImageFileServer.class);
    
    public final static int SERVER_PORT = 6060;
    
    private static P2PServer server;
	
	public static void main3(String[] args) throws Exception { 
	CosUtil.moveAllObjects();
//		CosUtil.renameAllBuckets();
	}
    
    public static void main(String[] args) throws Exception { 
        log.info("starting ImageFileServer ...");
        if(server!=null){
            stopServer();
        }
        server = new P2PServer(SERVER_PORT);
        try{
            server.start();
        }catch(Exception e){
            log.error(e,e);
            System.exit(2);
        }
        
        log.info("ImageFileServer ended!");
    } 
    
    public static void stop(String[] args) throws Exception {  
        try {
            if (server != null) {
                server.stop();
            }
            server = null;
            System.exit(0);
        } catch (Exception e) {
            log.error(e,e);
            System.exit(2);
        }
    }
    
    public static void stopServer() throws Exception {  
        try {
            if (server != null) {
                server.stop();
            }
            server = null;
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
}
