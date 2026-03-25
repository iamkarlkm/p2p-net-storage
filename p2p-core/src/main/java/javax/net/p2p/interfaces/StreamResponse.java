
package javax.net.p2p.interfaces;

import javax.net.p2p.model.StreamP2PWrapper;

/**
 * (文件下载/订阅)流消息返回,异步回调
 * @author karl
 */
public interface StreamResponse {
    
    void response(StreamP2PWrapper message);
    void cancel(StreamP2PWrapper message);
    
}
