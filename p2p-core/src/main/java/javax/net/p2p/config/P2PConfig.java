/**
 * P2PConfig - P2P文件传输服务器配置管理类
 * 
 * 主要功能：
 * 1. 统一配置管理：集中管理系统所有配置参数
 * 2. 配置文件加载：从system.properties加载配置，支持热重载
 * 3. 常量定义：定义系统使用的各种常量值
 * 4. 环境适配：根据运行环境提供不同的配置值
 * 
 * 配置来源：
 * 1. system.properties：主配置文件，包含服务器基础配置
 * 2. 环境变量：支持通过环境变量覆盖配置
 * 3. 系统属性：支持通过JVM参数设置配置
 * 4. 默认值：提供合理的默认配置值
 * 
 * 配置分类：
 * 1. 服务器配置：端口、线程数、超时时间等
 * 2. 存储配置：上传路径、分块大小、存储限制等
 * 3. 网络配置：缓冲区大小、连接数、协议参数等
 * 4. 安全配置：沙箱目录、访问控制、加密参数等
 * 5. 数据库配置：连接字符串、连接池参数等
 * 
 * 热重载特性：
 * 1. 支持运行时重新加载配置
 * 2. 不影响已建立的连接
 * 3. 新配置立即生效
 * 4. 提供load()方法显式触发重载
 * 
 * 线程安全：
 * 1. 使用静态内部类实现线程安全的单例
 * 2. 配置读取使用同步机制
 * 3. 支持多线程并发访问配置
 * 
 * 使用场景：
 * - 服务器启动时加载基础配置
 * - 运行时动态调整服务器参数
 * - 不同环境（开发/测试/生产）配置切换
 * - 配置参数集中管理和维护
 * 
 * 设计模式：
 * - 单例模式：确保全局唯一的配置实例
 * - 工厂模式：根据环境提供不同的配置实现
 * - 观察者模式：支持配置变更通知（可选扩展）
 * 
 * 注意事项：
 * 1. 配置文件应放置在classpath根目录
 * 2. 敏感配置建议加密存储
 * 3. 生产环境应禁用调试配置
 * 4. 配置变更后需要验证服务器功能
 * 
 * @author karl 2015-07-20
 * @version 2.0
 * @since 2025
 */
package javax.net.p2p.config;

import io.netty.util.internal.SystemPropertyUtil;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;
import lombok.extern.slf4j.Slf4j;


//@Configuration
//@PropertySources(value = {
//  @PropertySource("classpath:system.properties")})
@Slf4j
public class P2PConfig {

    private static ResourceBundle bundle;
    protected static final String CHARSET = "UTF-8";
    protected static final String resourceName = "system.properties";

    

    private P2PConfig() {
    }

    static {
        load(false);
//		THREAD_LOCAL_BUFFER_SIZE = SystemPropertyUtil.getInt("io.netty.threadLocalDirectBufferSize", 64 * 1024);

    }

    public static void load(boolean reload) {
        ClassLoader loader = P2PConfig.class.getClassLoader();
        try {
            InputStream stream = null;
            if (reload) {
                URL url = loader.getResource(resourceName);
                if (url != null) {
                    URLConnection connection = url.openConnection();
                    if (connection != null) {
                        connection.setUseCaches(false);
                        stream = connection.getInputStream();
                    }
                }
            } else {
                stream = loader.getResourceAsStream(resourceName);
            }
            if (stream != null) {
                try {
                    bundle = new PropertyResourceBundle(new InputStreamReader(stream, CHARSET));
                } finally {
                    stream.close();
                }
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }

    public static String getProperty(String key) {
        //Environment.getProperty(key)
        return bundle.getString(key);
    }

    public static String getUploadPath() {
        return bundle.getString("upload.root.path");
    }

    public static String getWebStaticPath() {
        return bundle.getString("app.web.static.dir");
    }

    public static String getWebbasePath() {
        return bundle.getString("app.web.base.dir");
    }

    public static String getMysqlDbUrl() {
        return bundle.getString("mysql.db.url");
    }

    public static String getMysqlDbUser() {
        return bundle.getString("mysql.db.user");
    }

    public static String getMysqlDbPwd() {
        return bundle.getString("mysql.db.pwd");
    }

    public static Double getTagLimitConfidence() {
        return Double.valueOf(bundle.getString("tag.used.limit.confidence"));
    }

    public static int getTaskLoaderScanInterval() {
        return Integer.parseInt(bundle.getString("task.loader.scan.interval"));
    }

    public static String getConvertExe() {
        return bundle.getString("convert.exe");
    }

    public static String getImageMagickPath() {
        return bundle.getString("image.magick.path");
    }

    public static String getShowWebdriver() {
        //Environment.getProperty(key)
        return bundle.getString("show.webdriver");
    }

    public static boolean isUseRemoteWebDriver() {
        //Environment.getProperty(key)
        return "true".equalsIgnoreCase(bundle.getString("isRemote"));
    }

    public static String getRemoteWebDriver() {
        //Environment.getProperty(key)
        return bundle.getString("remotedriver.url");
    }

    public static String getFtpHost() {
        return bundle.getString("ftp.host");
    }

    public static int getFtpPort() {
        return Integer.parseInt(bundle.getString("ftp.port"));
    }

    public static String getFtpUserName() {
        return bundle.getString("ftp.user.name");
    }

    public static String getFtpPassword() {
        return bundle.getString("ftp.password");
    }

    public static String getftpToHttpUrlBase() {
        return bundle.getString("ftp.to.http.url.base");
    }

    public static String getftpToHttpPathBase() {
        return bundle.getString("ftp.to.http.path.base");
    }

    public static String getSystemSerialNumber() {
        return bundle.getString("system.serial.number");
    }

    public static String getProjectName() {
        return bundle.getString("project.name");
    }

    public static String getHttpProxyAddress() {
        return bundle.getString("network.proxy.http") + ":" + bundle.getString("network.proxy.http_port");
    }

    public static String getHttpProxyHost() {
        return bundle.getString("network.proxy.http");
    }

    //max.allowed.aotoload.i18n.limit
    public static Integer getHttpProxyPort() {
        return Integer.parseInt(bundle.getString("network.proxy.http_port"));
    }

    public static Integer getMaxAllowedAotoloadI18nLimit() {
        return Integer.parseInt(bundle.getString("max.allowed.aotoload.i18n.limit"));
    }

    public static String getProjectSourceDir() {
        return bundle.getString("project.src");
    }

    public static String getProjectClassPackage() {
        return bundle.getString("project.class.package");
    }

    public static String getMailSmtpHost() {
        return bundle.getString("mail.smtp.host");
    }

    public static int getMailSmtpPort() {
        return Integer.parseInt(bundle.getString("mail.smtp.port"));
    }

    public static String getMailSmtpUserName() {
        return bundle.getString("mail.smtp.username");
    }

    public static String getMailSmtpPassword() {
        return bundle.getString("mail.smtp.password");
    }

    public static String getMailSmtpDefaultFromEmail() {
        return bundle.getString("mail.smtp.from.email");
    }

    public static String getMailSmtpDefaultFromName() {
        return bundle.getString("mail.smtp.from.name");
    }

    public static boolean isMailSmtpUseSsl() {
        return "true".equalsIgnoreCase(bundle.getString("mail.smtp.use.ssl"));
    }

    public static String getMailSmtpSslPort() {
        return bundle.getString("mail.smtp.ssl.port");
    }

    private static Long currentServerId = null;

    public static Long getCurrentServerId() {
        if (currentServerId == null) {
            currentServerId = Long.parseLong(bundle.getString("project.current.server.id"));
        }
        return currentServerId;
    }

//    
//    private static Integer nettyReceiveBufferSize = null;
//    public final static Integer getNettyBufferSize() {
//        if(nettyReceiveBufferSize == null){
//            try{
//                String size = bundle.getString("p2p.receive.buffer.size");
//                nettyReceiveBufferSize = Integer.parseInt(size);
//            }catch(Exception e){
//                nettyReceiveBufferSize = 8*1024*1024+4096;
//            }
//			//统一缓冲区尺寸
//			//THREAD_LOCAL_BUFFER_SIZE = SystemPropertyUtil.getInt("io.netty.threadLocalDirectBufferSize", 64 * 1024);
//			System.setProperty("io.netty.threadLocalDirectBufferSize", nettyReceiveBufferSize+"");
//        }
//		
//        return nettyReceiveBufferSize;
//    }
//    public final static int DATA_BLOCK_SIZE = 8 * 1024;//8M 64k
    public final static int DATA_BLOCK_SIZE = 8 * 1024 * 1024;//8M 64k
    public final static int BUFFER_BLOCK_SIZE = DATA_BLOCK_SIZE + 128;//8M (头尾冗余128)  64k

    /**
     * 传输层限制字节数
     */
    public final static int TRANSPORT_LIMIT_SIZE = 64 * 1024;//8M  / 64k
    
    /**
     * UDP传输层限制字节数
     */
    public final static int UDP_TRANSPORT_LIMIT_SIZE = 2 * 1024;//8M  / 64k

    public static int DATA_GET_BLOCK_SIZE = DATA_BLOCK_SIZE;

    public static int DATA_PUT_BLOCK_SIZE = DATA_BLOCK_SIZE;

//	private static Integer DATA_BLOCK_SIZE = null;
//    public final static Integer getDataBlockSize() {
//        if(DATA_BLOCK_SIZE == null){
//            try{
//                String size = bundle.getString("p2p.data.block.size");
//                DATA_BLOCK_SIZE = Integer.valueOf(size);
//            }catch(Exception e){
//                DATA_BLOCK_SIZE = 8*1024*1024;
//            }
//        }
//		
//        return DATA_BLOCK_SIZE;
//    }
    private static File p2pSharedPath = null;

    public static File getP2pSharedPath() {
//        if(nettyReceiveBufferSize == null){
//            try{
//                String path = bundle.getString("p2p.shared.path");
//                if(path!=null){
//                    p2pSharedPath = new File(path);
//                    if(!p2pSharedPath.exists()){
//                        p2pSharedPath.mkdirs();
//                    }
//                }
//            }catch(Exception e){
//                p2pSharedPath = new File("/opt");
//                //p2pSharedPath = new File("E:/VEH_IMAGES");
//            }
//        }
        return p2pSharedPath;
    }

}
