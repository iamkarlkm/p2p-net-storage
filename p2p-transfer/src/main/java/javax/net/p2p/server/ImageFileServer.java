/**
 * ImageFileServer - 基于P2P协议的文件传输服务器主入口类
 * 
 * 主要职责：
 * 1. 作为应用程序的启动入口，初始化并启动P2P服务器
 * 2. 提供服务器启动、停止的生命周期管理
 * 3. 处理服务器异常和优雅关闭
 * 
 * 功能特点：
 * - 监听固定端口（默认6060）接收客户端连接
 * - 支持P2P文件传输协议，包括TCP/UDP/QUIC等多种传输方式
 * - 集成腾讯云COS对象存储，支持云存储文件操作
 * - 支持文件分片传输和断点续传
 * 
 * 使用场景：
 * - 企业内部文件共享服务器
 * - 云存储网关服务
 * - 分布式文件传输系统
 * 
 * 注意事项：
 * 1. 服务器启动后会在后台运行，需要显式调用stop方法停止
 * 2. 默认端口6060，如需修改请修改SERVER_PORT常量
 * 3. 服务器异常时会自动退出，退出码为2
 * 
 * @author Administrator
 * @version 1.0
 * @since 2025
 */
package javax.net.p2p.server;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import javax.net.p2p.health.HealthHttpServer;


public class ImageFileServer {
    
    /** 日志记录器，用于记录服务器运行状态和异常信息 */
    static Log log = LogFactory.getLog(ImageFileServer.class);
    
    /** 服务器监听端口，默认6060端口，可根据需要修改 */
    public final static int SERVER_PORT = 6060;
    public final static int HEALTH_PORT = 8080;
    
    /** P2P服务器实例，负责处理网络连接和消息分发 */
    private static P2PServerTcp server;
    private static HealthHttpServer healthServer;
	
	
    
    /**
     * 服务器主启动方法 - 应用程序的入口点
     * 
     * 功能流程：
     * 1. 初始化日志，输出启动信息
     * 2. 检查是否已有服务器实例运行，如有则先停止
     * 3. 创建新的P2PServer实例并绑定指定端口
     * 4. 启动服务器并开始监听客户端连接
     * 5. 处理启动异常，异常时退出程序（退出码2）
     * 
     * 启动成功后：
     * - 服务器会在后台运行，监听6060端口
     * - 接收客户端连接并处理P2P协议消息
     * - 支持文件上传、下载、云存储等操作
     * 
     * 异常处理：
     * - 启动失败时记录错误日志并退出程序
     * - 退出码2表示服务器启动异常
     * 
     * @param args 命令行参数，当前版本未使用
     * @throws Exception 服务器启动过程中可能抛出的异常
     */
    public static void main(String[] args) throws Exception { 
        log.info("starting ImageFileServer ...");
        // 如果已有服务器实例，先停止再重新启动
        if(server!=null){
            stopServer();
        }
        // 创建P2P服务器实例，指定监听端口
        server = new P2PServerTcp(SERVER_PORT);
        healthServer = new HealthHttpServer(HEALTH_PORT);
        try{
            healthServer.start();
            // 启动服务器，开始监听端口
            server.start();
        }catch(Exception e){
            // 启动失败，记录错误并退出
            log.error("服务器启动失败", e);
            System.exit(2); // 退出码2表示服务器启动异常
        }
        
        log.info("ImageFileServer ended!");
    } 
    
    /**
     * 服务器停止方法（带参数版本）
     * 
     * 功能说明：
     * 1. 停止正在运行的P2P服务器实例
     * 2. 清理服务器资源，释放端口
     * 3. 退出应用程序
     * 
     * 执行流程：
     * 1. 检查服务器实例是否存在
     * 2. 调用服务器stop方法停止网络服务
     * 3. 清理服务器引用
     * 4. 正常退出程序（退出码0）
     * 
     * 异常处理：
     * - 停止失败时记录错误日志
     * - 异常退出程序（退出码2）
     * 
     * @param args 命令行参数，当前版本未使用
     * @throws Exception 服务器停止过程中可能抛出的异常
     */
    public static void stop(String[] args) throws Exception {  
        try {
            if (server != null) {
                server.stop();
            }
            if (healthServer != null) {
                healthServer.stop();
            }
            server = null;
            healthServer = null;
            System.exit(0); // 正常退出，退出码0
        } catch (Exception e) {
            log.error("服务器停止失败", e);
            System.exit(2); // 异常退出，退出码2
        }
    }
    
    /**
     * 服务器停止方法（无参数版本）
     * 
     * 功能说明：
     * 1. 停止正在运行的P2P服务器实例
     * 2. 清理服务器资源，但不退出程序
     * 3. 主要用于服务器重启或重新初始化
     * 
     * 与stop(String[])的区别：
     * - 此方法只停止服务器，不退出应用程序
     * - 异常处理更宽松，只打印堆栈跟踪
     * - 适用于需要重新启动服务器的场景
     * 
     * 使用场景：
     * - 服务器配置热更新
     * - 服务器重启
     * - 资源清理
     * 
     * @throws Exception 服务器停止过程中可能抛出的异常
     */
    public static void stopServer() throws Exception {  
        try {
            if (server != null) {
                server.stop();
            }
            if (healthServer != null) {
                healthServer.stop();
            }
            server = null;
            healthServer = null;
        } catch (Exception e) {
            e.printStackTrace(); // 只打印异常，不退出程序
        }
    }
    
}
