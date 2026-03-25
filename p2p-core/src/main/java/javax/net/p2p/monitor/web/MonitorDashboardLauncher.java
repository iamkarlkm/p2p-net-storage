package javax.net.p2p.monitor.web;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Scanner;
import javax.net.p2p.monitor.UdpMonitorDecorator;
import lombok.extern.slf4j.Slf4j;

/**
 * 监控仪表板启动器
 * 
 * 提供一键启动监控Web服务器和WebSocket服务器的功能
 * 
 * 主要功能：
 * 1. 启动HTTP Web服务器（端口8088）
 * 2. 启动WebSocket服务器（端口8089）
 * 3. 自动打开浏览器访问监控仪表板
 * 4. 提供命令行交互界面
 * 
 * @author CodeBuddy
 */
@Slf4j
public class MonitorDashboardLauncher {
    
    private static final int HTTP_PORT = 8088;
    private static final int WS_PORT = 8089;
    private static final String DASHBOARD_URL = "http://localhost:" + HTTP_PORT + "/monitor";
    
    private static MonitorWebServer httpServer;
    private static WebSocketServer wsServer;
    private static boolean isRunning = false;
    
    /**
     * 启动所有监控服务
     */
    public static void startAll() {
        if (isRunning) {
            log.info("监控服务已经在运行中");
            return;
        }
        
        try {
            log.info("正在启动UDP性能监控服务...");
            
            // 启动WebSocket服务器
            log.info("启动WebSocket服务器 (端口: {})", WS_PORT);
            wsServer = new WebSocketServer(WS_PORT);
            wsServer.startServer();
            
            // 启动HTTP服务器
            log.info("启动HTTP服务器 (端口: {})", HTTP_PORT);
            httpServer = new MonitorWebServer(HTTP_PORT);
            httpServer.start();
            
            isRunning = true;
            
            log.info("=========================================");
            log.info("UDP性能监控仪表板已启动！");
            log.info("访问地址: {}", DASHBOARD_URL);
            log.info("API接口: {}/api/udp/overview", DASHBOARD_URL);
            log.info("WebSocket: ws://localhost:{}", WS_PORT);
            log.info("按Ctrl+C停止服务");
            log.info("=========================================");
            
            // 尝试自动打开浏览器
            openBrowser();
            
        } catch (Exception e) {
            log.error("启动监控服务失败", e);
            stopAll();
        }
    }
    
    /**
     * 停止所有监控服务
     */
    public static void stopAll() {
        if (!isRunning) {
            return;
        }
        
        log.info("正在停止监控服务...");
        
        try {
            if (httpServer != null) {
                httpServer.stop();
                log.info("HTTP服务器已停止");
            }
        } catch (Exception e) {
            log.error("停止HTTP服务器失败", e);
        }
        
        try {
            if (wsServer != null) {
                wsServer.stopServer();
                log.info("WebSocket服务器已停止");
            }
        } catch (Exception e) {
            log.error("停止WebSocket服务器失败", e);
        }
        
        isRunning = false;
        log.info("所有监控服务已停止");
    }
    
    /**
     * 尝试自动打开浏览器
     */
    private static void openBrowser() {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(DASHBOARD_URL));
                log.info("已自动打开浏览器访问监控仪表板");
            } else {
                log.info("无法自动打开浏览器，请手动访问: {}", DASHBOARD_URL);
            }
        } catch (Exception e) {
            log.warn("无法自动打开浏览器: {}", e.getMessage());
            log.info("请手动访问: {}", DASHBOARD_URL);
        }
    }
    
    /**
     * 获取性能报告
     */
    public static String getPerformanceReport() {
        return UdpMonitorDecorator.getPerformanceReport();
    }
    
    /**
     * 显示命令行菜单
     */
    private static void showMenu() {
        System.out.println("\n=== UDP性能监控仪表板控制台 ===");
        System.out.println("1. 启动监控服务");
        System.out.println("2. 停止监控服务");
        System.out.println("3. 查看性能报告");
        System.out.println("4. 打开监控仪表板");
        System.out.println("5. 重启监控服务");
        System.out.println("6. 查看服务状态");
        System.out.println("7. 退出");
        System.out.print("请选择操作 (1-7): ");
    }
    
    /**
     * 处理用户输入
     */
    private static void handleUserInput(Scanner scanner) {
        while (true) {
            showMenu();
            
            try {
                String input = scanner.nextLine().trim();
                int choice = Integer.parseInt(input);
                
                switch (choice) {
                    case 1: // 启动监控服务
                        if (isRunning) {
                            System.out.println("监控服务已经在运行中");
                        } else {
                            new Thread(() -> startAll()).start();
                            System.out.println("正在启动监控服务...");
                        }
                        break;
                        
                    case 2: // 停止监控服务
                        if (!isRunning) {
                            System.out.println("监控服务未运行");
                        } else {
                            stopAll();
                            System.out.println("监控服务已停止");
                        }
                        break;
                        
                    case 3: // 查看性能报告
                        System.out.println("\n=== UDP性能监控报告 ===");
                        System.out.println(getPerformanceReport());
                        break;
                        
                    case 4: // 打开监控仪表板
                        if (!isRunning) {
                            System.out.println("请先启动监控服务");
                        } else {
                            openBrowser();
                        }
                        break;
                        
                    case 5: // 重启监控服务
                        stopAll();
                        try {
                            Thread.sleep(1000); // 等待1秒
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        new Thread(() -> startAll()).start();
                        System.out.println("正在重启监控服务...");
                        break;
                        
                    case 6: // 查看服务状态
                        System.out.println("\n=== 服务状态 ===");
                        System.out.println("HTTP服务器: " + (isRunning ? "运行中 (端口: " + HTTP_PORT + ")" : "未运行"));
                        System.out.println("WebSocket服务器: " + (isRunning ? "运行中 (端口: " + WS_PORT + ")" : "未运行"));
                        System.out.println("监控仪表板: " + (isRunning ? DASHBOARD_URL : "未运行"));
                        break;
                        
                    case 7: // 退出
                        System.out.println("正在退出...");
                        stopAll();
                        return;
                        
                    default:
                        System.out.println("无效的选择，请重新输入");
                }
                
                System.out.println("\n按Enter键继续...");
                scanner.nextLine();
                
            } catch (NumberFormatException e) {
                System.out.println("请输入有效的数字");
            } catch (Exception e) {
                System.out.println("发生错误: " + e.getMessage());
            }
        }
    }
    
    /**
     * 主方法：启动监控仪表板
     */
    public static void main(String[] args) {
        // 添加关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("接收到关闭信号，正在停止监控服务...");
            stopAll();
        }));
        
        // 检查命令行参数
        if (args.length > 0) {
            if ("--start".equals(args[0]) || "-s".equals(args[0])) {
                // 直接启动服务
                startAll();
                
                // 保持程序运行
                try {
                    System.out.println("按Ctrl+C停止服务");
                    Thread.sleep(Long.MAX_VALUE);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                
            } else if ("--help".equals(args[0]) || "-h".equals(args[0])) {
                showHelp();
            } else {
                System.out.println("未知参数: " + args[0]);
                showHelp();
            }
        } else {
            // 交互模式
            try (Scanner scanner = new Scanner(System.in)) {
                System.out.println("欢迎使用UDP性能监控仪表板");
                System.out.println("=========================================");
                handleUserInput(scanner);
            }
        }
    }
    
    /**
     * 显示帮助信息
     */
    private static void showHelp() {
        System.out.println("UDP性能监控仪表板启动器");
        System.out.println("使用方法:");
        System.out.println("  java -jar monitor.jar            # 交互模式");
        System.out.println("  java -jar monitor.jar --start    # 直接启动服务");
        System.out.println("  java -jar monitor.jar --help     # 显示帮助");
        System.out.println();
        System.out.println("功能:");
        System.out.println("  - 实时监控UDP性能指标");
        System.out.println("  - 可视化图表展示");
        System.out.println("  - 告警和通知系统");
        System.out.println("  - 会话管理和统计");
        System.out.println();
        System.out.println("默认端口:");
        System.out.println("  HTTP服务器: 8088");
        System.out.println("  WebSocket服务器: 8089");
    }
    
    /**
     * 检查服务是否在运行
     */
    public static boolean isRunning() {
        return isRunning;
    }
    
    /**
     * 获取HTTP服务器端口
     */
    public static int getHttpPort() {
        return HTTP_PORT;
    }
    
    /**
     * 获取WebSocket服务器端口
     */
    public static int getWebSocketPort() {
        return WS_PORT;
    }
    
    /**
     * 获取监控仪表板URL
     */
    public static String getDashboardUrl() {
        return DASHBOARD_URL;
    }
}