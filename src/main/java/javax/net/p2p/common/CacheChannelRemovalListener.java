
package javax.net.p2p.common;

import com.google.common.cache.RemovalCause;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;
import io.netty.channel.Channel;
import io.netty.channel.pool.SimpleChannelPool;
import javax.net.p2p.client.P2PClient;
import javax.net.p2p.model.P2PWrapper;
import lombok.extern.slf4j.Slf4j;

/**
 *
 * @author karl
 */
// 创建一个channel缓存更新通知过期的监听器
@Slf4j
public  class CacheChannelRemovalListener implements RemovalListener<Integer, ChannelAwaitOnMessage<P2PWrapper>> {

//        private P2PClient client;
//
//        public CacheChannelRemovalListener(P2PClient client) {
//            this.client = client;
//        }

        @Override
        public void onRemoval(RemovalNotification<Integer, ChannelAwaitOnMessage<P2PWrapper>> notification) {
           if(notification.getCause() == RemovalCause.EXPIRED){
            log.error("非正常关闭Channel:{}", notification.getValue());
            try {
                //notification.getValue().signal();
                //去除channel从连接池
                //notification.getKey().close();
                //notification.getValue().release(notification.getKey());
            } catch (Exception ex2) {
                log.error(ex2.getMessage());
            }
           }
        }
    }
