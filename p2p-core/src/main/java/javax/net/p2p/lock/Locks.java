

package javax.net.p2p.lock;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 *
 * @author Administrator
 */
public class Locks {
    
     private final static ReentrantLock LOCAL_LOCK = new ReentrantLock();
//    private final static Map<String,ReentrantLock> REENTRANTLOCK_MAP = new ConcurrentHashMap<>();
	
	private static final Cache<String, ReentrantLock> LOCKS_CACHE = CacheBuilder.newBuilder()
			.expireAfterAccess(8, TimeUnit.HOURS).removalListener(new CacheChannelRemovalListener()).build();
    
	// 创建一个channel缓存更新通知过期的监听器
    private static class CacheChannelRemovalListener implements RemovalListener<String, ReentrantLock> {
//		private  Listener Listener;
//
//		public CacheChannelRemovalListener(Listener Listener) {
//			this.Listener = client;
//		}
		
        @Override
        public void onRemoval(RemovalNotification<String, ReentrantLock> notification) {
            //System.out.println("channel缓存更新通知过期->" + notification.getKey());
            try {
//				if(notification.getValue().getHoldCount()>0){
//					
//				}
			} catch (Exception ex2) {
				
			}
        }
    }
    public static void main(String[] args) {
        ReentrantLock lock = new ReentrantLock();
       
	    }
    
    public static void lock(String key){
        ReentrantLock lock;
        LOCAL_LOCK.lock();
        try{
            lock = LOCKS_CACHE.getIfPresent(key);
        if(lock==null){
            lock = new ReentrantLock();
            LOCKS_CACHE.put(key, lock);
        }
        }finally{
           LOCAL_LOCK.unlock();
        }
        lock.lock();
    }
    
    public static void unLock(String key){
        ReentrantLock lock = LOCKS_CACHE.getIfPresent(key);
        lock.unlock();
    }
    
    public static boolean tryLock(String key){
        ReentrantLock lock;
        LOCAL_LOCK.lock();
        try{
            lock = LOCKS_CACHE.getIfPresent(key);
        if(lock==null){
            lock = new ReentrantLock();
            LOCKS_CACHE.put(key, lock);
        }
        }finally{
           LOCAL_LOCK.unlock();
        }
        return lock.tryLock();
    }
 
}
