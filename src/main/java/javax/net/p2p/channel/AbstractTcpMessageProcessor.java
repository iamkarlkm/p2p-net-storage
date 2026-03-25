package javax.net.p2p.channel;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import java.io.File;
import java.net.JarURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.jar.JarEntry;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.P2PWrapper;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class AbstractTcpMessageProcessor extends SimpleChannelInboundHandler<P2PWrapper> {

    protected final static HashMap<P2PCommand, P2PCommandHandler> HANDLER_REGISTRY_MAP = new HashMap<>();

    private static final List<String> CLASS_CACHE = new ArrayList<>();

    static {
        //注册命令处理器
        registerProcessors();
    }

    protected int frameLengthInt = -1;

    protected int magic;

    protected int queueSize;

    protected boolean connected = false;

    public AbstractTcpMessageProcessor(int magic, int queueSize) {
        this.magic = magic;
        this.queueSize = queueSize;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.error(cause.getMessage(), cause);
        ctx.close();
        super.exceptionCaught(ctx, cause);
    }

    public static void registerProcessors() {
        try {
            scannerClass("javax.net.p2p.server.handler");
            doRegister();
            log.info("HANDLER_REGISTRY_MAP ->\n{}", HANDLER_REGISTRY_MAP);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private static void scannerClass(String packageName) throws Exception {
        URL url = AbstractTcpMessageProcessor.class.getClassLoader().getResource(packageName.replaceAll("\\.", "/"));
        System.out.println("scan processors -> " + url.getFile());
        if (url.getFile().contains(".jar!")) {

            JarURLConnection urlConnection = (JarURLConnection) url.openConnection();
            // 从此jar包 得到一个枚举类
            Enumeration<JarEntry> entries = urlConnection.getJarFile().entries();
            // 遍历jar
            while (entries.hasMoreElements()) {
                // 获取jar里的一个实体 可以是目录 和一些jar包里的其他文件 如META-INF等文件
                JarEntry entry = entries.nextElement();
                //得到该jar文件下面的类实体
                if (entry.getName().startsWith(packageName.replaceAll("\\.", "/")) && entry.getName().endsWith(".class")) {
                    System.out.println(entry.getName());
                    // 因为scan 就是/  ， 所有把 file的 / 转成  \   统一都是：  /
                    String fPath = entry.getName().replaceAll("\\\\", "/");
                    // 把 包路径 前面的 盘符等 去掉
                    String packName = fPath;
                    int m = fPath.indexOf(":");
                    if (m > 0) {
                        packName = fPath.substring(m);
                    }
                    // 去掉后缀.class ，并且把 / 替换成 .    这样就是  com.hadluo.A 格式了 ， 就可以用Class.forName加载了
                    packName = packName.replace(".class", "").replaceAll("/", ".");
                    // 根据名称加载类
                    CLASS_CACHE.add(packName);
                }
            }
        } else {
            File dir = new File(url.getFile());
            for (File file : dir.listFiles()) {
                //System.out.println(file);
                //如果是一个文件夹，继续递归
                if (file.isDirectory()) {
                    scannerClass(packageName + "." + file.getName());
                } else {
                    CLASS_CACHE.add(packageName + "." + file.getName().replace(".class", "").trim());
                }
            }
        }

    }

    private static void doRegister() {
        if (CLASS_CACHE.isEmpty()) {
            return;
        }

        for (String className : CLASS_CACHE) {
            try {
                Class<?> clazz = Class.forName(className);
                if (P2PCommandHandler.class.isAssignableFrom(clazz)) {
                    //P2PCommandHandler handler = clazz.newInstance();
                    P2PCommandHandler handler = (P2PCommandHandler) clazz.getDeclaredConstructor().newInstance();
                    if (HANDLER_REGISTRY_MAP.get(handler.getCommand()) == null) {
                        HANDLER_REGISTRY_MAP.put(handler.getCommand(), handler);
                    } else {
                        throw new RuntimeException("P2PCommandHandler register confilct:" + handler.getCommand()
                            + " " + className
                            + " <> " + HANDLER_REGISTRY_MAP.get(handler.getCommand()));
                    }
                }

                //Class<?> interfaces = clazz.getInterfaces()[0];
                //registryMap.put(interfaces.getName(), clazz.newInstance()); 
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        connected = false;
        //channelPool.release(ctx.channel());
        super.handlerRemoved(ctx);
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        connected = true;
        super.handlerAdded(ctx);
    }

    public boolean isConnected() {
        return connected;
    }

}
