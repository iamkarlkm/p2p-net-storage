
package javax.net.p2p.common.pool;

/**
 *
 * @author Administrator
 * @param <T>
 */
public abstract class PooledObjectFactory<T>{
        public abstract T newInstance();
 }
