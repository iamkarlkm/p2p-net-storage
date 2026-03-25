package javax.net.p2p.common;

import java.util.Arrays;
import java.util.HashMap;
import lombok.extern.slf4j.Slf4j;

/**
 *
 * @author karl
 */
@Slf4j
public abstract class ReferencedSingleton {

//    private static final ConcurrentHashMap<Object, ReferencedSingleton> REFERENCED_SINGLETON_MAP = new ConcurrentHashMap<>();
    private static final HashMap<Class, HashMap<Object, ReferencedSingleton>> CLASS_REFERENCED_SINGLETON_MAP = new HashMap<>();

    private Object key;
    private int refCount = 0;
    private KeyWrapper keyWrapper;

    protected boolean isRunning = false;

    public synchronized static <T> T getInstanceBySuper(Class<T> clazz, Object key) {
        HashMap<Object, ReferencedSingleton> REFERENCED_SINGLETON_MAP = CLASS_REFERENCED_SINGLETON_MAP.get(clazz);
        if (REFERENCED_SINGLETON_MAP == null) {
            REFERENCED_SINGLETON_MAP = new HashMap();
            CLASS_REFERENCED_SINGLETON_MAP.put(clazz, REFERENCED_SINGLETON_MAP);
        }
        T t = (T) REFERENCED_SINGLETON_MAP.get(key);
        if (t == null) {
            Class[] params = {key.getClass()};
            try {
                t = clazz.getDeclaredConstructor(params).newInstance(key);
                ReferencedSingleton ref = (ReferencedSingleton) t;
                ref.refCount++;
                ref.key = key;
                ref.isRunning = true;
                REFERENCED_SINGLETON_MAP.put(key, ref);
                //jvm退出,释放资源
                Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                    @Override
                    public void run() {
                        HashMap<Object, ReferencedSingleton> REFERENCED_SINGLETON_MAP = CLASS_REFERENCED_SINGLETON_MAP.get(clazz);
                        if (REFERENCED_SINGLETON_MAP != null) {
                            ReferencedSingleton s0 = REFERENCED_SINGLETON_MAP.remove(key);
                            s0.released();
                        }
                    }
                }));
                //以线程模式fire event singletonCreated
                final T instance = t;
                Thread thread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        ref.singletonCreated(instance);
                    }
                });
                thread.start();
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }

        }

        return t;
    }

    public synchronized static <T> T getInstanceBySuper(Class<T> clazz, Object... keys) {
        HashMap<Object, ReferencedSingleton> REFERENCED_SINGLETON_MAP = CLASS_REFERENCED_SINGLETON_MAP.get(clazz);
        if (REFERENCED_SINGLETON_MAP == null) {
             REFERENCED_SINGLETON_MAP = new HashMap();
            CLASS_REFERENCED_SINGLETON_MAP.put(clazz, REFERENCED_SINGLETON_MAP);
        }
        KeyWrapper kw = new KeyWrapper(keys);
        T t = (T) REFERENCED_SINGLETON_MAP.get(kw);
        if (t == null) {
            Class[] params = kw.getClasses();
            try {
                t = clazz.getDeclaredConstructor(params).newInstance(keys);
                final ReferencedSingleton ref = (ReferencedSingleton) t;
                ref.refCount++;
                ref.keyWrapper = kw;
                ref.isRunning = true;
                REFERENCED_SINGLETON_MAP.put(kw, ref);
                
                //jvm退出,释放资源
                Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                    @Override
                    public void run() {
                        log.info("jvm退出,释放资源 ReferencedSingleton {} released,refCont = {}", ref.getClass(),ref.refCount);
                        HashMap<Object, ReferencedSingleton> REFERENCED_SINGLETON_MAP = CLASS_REFERENCED_SINGLETON_MAP.get(clazz);
                        if (REFERENCED_SINGLETON_MAP != null) {
                            ReferencedSingleton s0 = REFERENCED_SINGLETON_MAP.remove(kw);
                            s0.released();
                        }
                    }
                }));
                //以线程模式fire event singletonCreated
                final T instance = t;
                Thread thread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        ref.singletonCreated(instance);
                    }
                });
                thread.start();
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }

        }

        return t;
    }

    public Object getKey() {
        return key;
    }

    public int getRefCount() {
        return refCount;
    }

    public KeyWrapper getKeyWrapper() {
        return keyWrapper;
    }

    public void retain() {
        refCount++;
    }

    public void released() {
        log.info("ReferencedSingleton {} released,refCont = {}", this.getClass(),refCount);
        refCount--;
        if (refCount == 0) {
            if (key != null) {
                HashMap<Object, ReferencedSingleton> REFERENCED_SINGLETON_MAP = CLASS_REFERENCED_SINGLETON_MAP.get(this.getClass());
                if (REFERENCED_SINGLETON_MAP != null) {
                    ReferencedSingleton s0 = REFERENCED_SINGLETON_MAP.remove(key);
                    s0.singletonFinalized();
                }

            } else if (keyWrapper != null) {
                HashMap<Object, ReferencedSingleton> REFERENCED_SINGLETON_MAP = CLASS_REFERENCED_SINGLETON_MAP.get(this.getClass());
                if (REFERENCED_SINGLETON_MAP != null) {
                    ReferencedSingleton s0 = REFERENCED_SINGLETON_MAP.remove(keyWrapper);
                    s0.singletonFinalized();
                }

            }
        }
    }
    

    /**
     * 对象创建做一些初始化工作,例如线程池创建\启动服务等
     * @param instance
     */
    public abstract void singletonCreated(Object instance);

    public abstract void singletonFinalized();

    static class KeyWrapper {

        private final Object[] keys;

        private final Class[] classes;

        public KeyWrapper(Object... params) {
            this.keys = params;
            classes = new Class[params.length];
            for (int i = 0; i < params.length; i++) {
                classes[i] = params[i].getClass();
            }
        }

        @Override
        public int hashCode() {
            int hash = 7;
            hash = 97 * hash + Arrays.deepHashCode(this.keys);
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            final KeyWrapper other = (KeyWrapper) obj;
            return Arrays.deepEquals(this.keys, other.keys);
        }

        @Override
        public String toString() {
            return Arrays.toString(keys);
        }

        public Class[] getClasses() {
            return classes;
        }

        public Object getKey(int index) {
            return keys[index];
        }

    }


}
