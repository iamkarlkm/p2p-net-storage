/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package javax.net.p2p.server;

import javax.net.p2p.common.ExecutorServicePool;
import javax.net.p2p.common.ReferencedSingleton;

/**
 *
 * @author Administrator
 */
public class ServerReferencedSingleton extends ReferencedSingleton {
    
    private int serverRefCount = 0;

    @Override
    public void singletonCreated(Object instance) {
        serverRefCount++;
    }

    /**
     * 引用归零，回调，关闭服务器资源
     */
    @Override
    public void singletonFinalized() {
        serverRefCount--;if (serverRefCount <=0) {
            ExecutorServicePool.releaseP2PServerPools();
//            try {
//                HdfsUtil.closeFileSysytem();
//            } catch (IOException ex) {
//                Logger.getLogger(P2PServer.class.getName()).log(Level.SEVERE, null, ex);
//            }

        }
        
    }
    
}
