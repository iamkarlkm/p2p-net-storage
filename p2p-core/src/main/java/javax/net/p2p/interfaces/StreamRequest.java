
package javax.net.p2p.interfaces;

import javax.net.p2p.common.AbstractSendMesageExecutor;
import javax.net.p2p.model.StreamP2PWrapper;
import javax.net.p2p.server.ServerSendUdpMesageExecutor;

/**
 * (文件上传/发布)流消息请求处理,异步回调
 * @author karl
 */
public interface StreamRequest {
    
    StreamP2PWrapper request(AbstractSendMesageExecutor executor,StreamP2PWrapper message);
    void cancel(AbstractSendMesageExecutor executor,StreamP2PWrapper message);
    
}
