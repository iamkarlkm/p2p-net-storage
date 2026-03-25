/**
 * @author karl 2015-07-20
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
