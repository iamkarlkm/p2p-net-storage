package examples;

import javax.net.p2p.im.ChatClient;
import javax.net.p2p.im.GroupManager;
import javax.net.p2p.model.UserInfo;
import java.net.InetSocketAddress;
import java.util.Scanner;

/**
 * 群聊演示程序 - 展示群聊功能的使用
 * 
 * 功能说明：
 * 1. 演示群组的创建和管理
 * 2. 展示群成员添加和移除
 * 3. 实现群消息发送和接收
 * 4. 提供群聊相关的API使用示例
 * 
 * 使用说明：
 * 1. 运行程序，输入用户信息
 * 2. 创建或加入群组
 * 3. 在群组中发送和接收消息
 * 4. 查看群组信息和成员列表
 * 
 * @author IM System
 * @version 1.0
 * @since 2026
 */
public class GroupChatDemo {
    
    private static ChatClient chatClient;
    private static GroupManager groupManager;
    private static UserInfo currentUser;
    private static boolean running = true;
    
    public static void main(String[] args) {
        try {
            System.out.println("=== P2P即时通讯系统 - 群聊功能演示 ===");
            System.out.println();
            
            // 初始化用户
            initializeUser();
            
            // 初始化聊天客户端
            initializeChatClient();
            
            // 初始化群组管理器
            initializeGroupManager();
            
            // 显示主菜单
            showMainMenu();
            
            // 启动命令行交互
            startCommandLine();
            
        } catch (Exception e) {
            System.err.println("群聊演示程序运行异常: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // 清理资源
            cleanup();
        }
    }
    
    /**
     * 初始化用户
     */
    private static void initializeUser() {
        Scanner scanner = new Scanner(System.in);
        
        System.out.println("=== 用户登录 ===");
        System.out.print("请输入用户ID: ");
        String userId = scanner.nextLine().trim();
        
        System.out.print("请输入用户名: ");
        String username = scanner.nextLine().trim();
        
        System.out.print("请输入昵称（可选）: ");
        String nickname = scanner.nextLine().trim();
        
        if (nickname.isEmpty()) {
            nickname = username;
        }
        
        currentUser = new UserInfo(userId, username, nickname);
        
        System.out.println();
        System.out.printf("用户登录成功: %s (%s)%n", currentUser.getNickname(), currentUser.getUserId());
    }
    
    /**
     * 初始化聊天客户端
     */
    private static void initializeChatClient() {
        try {
            // 创建服务器地址
            InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", 6060);
            
            // 创建聊天回调
            ChatClient.ChatCallback callback = new ChatClient.SimpleChatCallback();
            
            // 获取聊天客户端实例并初始化
            chatClient = ChatClient.getInstance();
            boolean success = chatClient.initialize(currentUser, serverAddress, callback);
            
            if (!success) {
                System.err.println("聊天客户端初始化失败");
                System.exit(1);
            }
            
            // 启动聊天客户端
            chatClient.start();
            
            System.out.println("聊天客户端初始化成功");
            System.out.println();
            
        } catch (Exception e) {
            System.err.println("初始化聊天客户端失败: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    /**
     * 初始化群组管理器
     */
    private static void initializeGroupManager() {
        try {
            groupManager = GroupManager.getInstance();
            System.out.println("群组管理器初始化成功");
            System.out.println();
            
        } catch (Exception e) {
            System.err.println("初始化群组管理器失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 显示主菜单
     */
    private static void showMainMenu() {
        System.out.println("=== 群聊功能主菜单 ===");
        System.out.println("1. 创建群组");
        System.out.println("2. 加入群组");
        System.out.println("3. 查看我的群组");
        System.out.println("4. 发送群消息");
        System.out.println("5. 查看群成员");
        System.out.println("6. 添加群成员");
        System.out.println("7. 退出群聊");
        System.out.println("8. 退出系统");
        System.out.println();
    }
    
    /**
     * 启动命令行交互
     */
    private static void startCommandLine() {
        Scanner scanner = new Scanner(System.in);
        
        while (running) {
            System.out.print("请选择操作 (输入1-8): ");
            String input = scanner.nextLine().trim();
            
            switch (input) {
                case "1":
                    createGroup();
                    break;
                case "2":
                    joinGroup();
                    break;
                case "3":
                    listMyGroups();
                    break;
                case "4":
                    sendGroupMessage();
                    break;
                case "5":
                    listGroupMembers();
                    break;
                case "6":
                    addGroupMember();
                    break;
                case "7":
                    leaveGroup();
                    break;
                case "8":
                    System.out.println("正在退出系统...");
                    running = false;
                    break;
                default:
                    System.out.println("无效的选择，请重新输入");
                    break;
            }
            
            System.out.println();
        }
        
        scanner.close();
    }
    
    /**
     * 创建群组
     */
    private static void createGroup() {
        Scanner scanner = new Scanner(System.in);
        
        System.out.println("=== 创建群组 ===");
        System.out.print("请输入群组名称: ");
        String groupName = scanner.nextLine().trim();
        
        if (groupName.isEmpty()) {
            System.out.println("群组名称不能为空");
            return;
        }
        
        System.out.print("请输入群组描述（可选）: ");
        String groupDesc = scanner.nextLine().trim();
        
        // 创建群组
        String groupId = groupManager.createGroup(groupName, currentUser.getUserId(), groupDesc);
        
        if (groupId != null) {
            System.out.println("群组创建成功!");
            System.out.println("群组ID: " + groupId);
            System.out.println("群组名称: " + groupName);
        } else {
            System.out.println("群组创建失败");
        }
    }
    
    /**
     * 加入群组
     */
    private static void joinGroup() {
        Scanner scanner = new Scanner(System.in);
        
        System.out.println("=== 加入群组 ===");
        System.out.print("请输入要加入的群组ID: ");
        String groupId = scanner.nextLine().trim();
        
        if (groupId.isEmpty()) {
            System.out.println("群组ID不能为空");
            return;
        }
        
        // 加入群组
        boolean success = groupManager.joinGroup(groupId, currentUser.getUserId());
        
        if (success) {
            System.out.println("加入群组成功!");
            System.out.println("群组ID: " + groupId);
        } else {
            System.out.println("加入群组失败");
        }
    }
    
    /**
     * 列出我的群组
     */
    private static void listMyGroups() {
        System.out.println("=== 我的群组列表 ===");
        
        // 获取用户加入的群组
        java.util.List<String> groupIds = groupManager.getUserGroups(currentUser.getUserId());
        
        if (groupIds.isEmpty()) {
            System.out.println("您还没有加入任何群组");
            return;
        }
        
        System.out.printf("您加入了 %d 个群组:%n", groupIds.size());
        System.out.println("-------------------------------");
        System.out.printf("%-30s %-20s%n", "群组ID", "群组名称");
        System.out.println("-------------------------------");
        
        for (String groupId : groupIds) {
            try {
                String groupName = groupManager.getGroupInfo(groupId).getName();
                System.out.printf("%-30s %-20s%n", groupId, groupName);
            } catch (Exception e) {
                System.out.printf("%-30s %-20s%n", groupId, "未知群组");
            }
        }
        
        System.out.println("-------------------------------");
    }
    
    /**
     * 发送群消息
     */
    private static void sendGroupMessage() {
        Scanner scanner = new Scanner(System.in);
        
        System.out.println("=== 发送群消息 ===");
        
        // 先列出用户的群组
        listMyGroups();
        
        System.out.print("请输入要发送消息的群组ID: ");
        String groupId = scanner.nextLine().trim();
        
        if (groupId.isEmpty()) {
            System.out.println("群组ID不能为空");
            return;
        }
        
        System.out.print("请输入消息内容: ");
        String content = scanner.nextLine().trim();
        
        if (content.isEmpty()) {
            System.out.println("消息内容不能为空");
            return;
        }
        
        // 发送群消息
        String messageId = groupManager.sendGroupMessage(
            groupId, 
            currentUser.getUserId(), 
            content
        );
        
        if (messageId != null) {
            System.out.println("群消息发送成功!");
            System.out.println("消息ID: " + messageId);
        } else {
            System.out.println("群消息发送失败");
        }
    }
    
    /**
     * 列出群组成员
     */
    private static void listGroupMembers() {
        Scanner scanner = new Scanner(System.in);
        
        System.out.println("=== 查看群组成员 ===");
        
        // 先列出用户的群组
        listMyGroups();
        
        System.out.print("请输入要查看的群组ID: ");
        String groupId = scanner.nextLine().trim();
        
        if (groupId.isEmpty()) {
            System.out.println("群组ID不能为空");
            return;
        }
        
        // 获取群组成员
        java.util.List<String> memberIds = groupManager.getGroupMembers(groupId);
        
        if (memberIds.isEmpty()) {
            System.out.println("该群组没有成员");
            return;
        }
        
        System.out.printf("群组 %s 的成员列表:%n", groupId);
        System.out.println("-------------------------------");
        System.out.printf("%-20s %-15s%n", "用户ID", "用户名");
        System.out.println("-------------------------------");
        
        for (String memberId : memberIds) {
            try {
                // 这里应该查询用户信息，简化处理
                System.out.printf("%-20s %-15s%n", memberId, "用户" + memberId);
            } catch (Exception e) {
                System.out.printf("%-20s %-15s%n", memberId, "未知用户");
            }
        }
        
        System.out.println("-------------------------------");
    }
    
    /**
     * 添加群组成员
     */
    private static void addGroupMember() {
        Scanner scanner = new Scanner(System.in);
        
        System.out.println("=== 添加群组成员 ===");
        
        // 先列出用户的群组
        listMyGroups();
        
        System.out.print("请输入要添加成员的群组ID: ");
        String groupId = scanner.nextLine().trim();
        
        if (groupId.isEmpty()) {
            System.out.println("群组ID不能为空");
            return;
        }
        
        System.out.print("请输入要添加的用户ID: ");
        String userId = scanner.nextLine().trim();
        
        if (userId.isEmpty()) {
            System.out.println("用户ID不能为空");
            return;
        }
        
        // 检查是否是自己
        if (userId.equals(currentUser.getUserId())) {
            System.out.println("不能添加自己");
            return;
        }
        
        // 添加群成员
        boolean success = groupManager.addMember(groupId, userId);
        
        if (success) {
            System.out.println("添加群成员成功!");
            System.out.println("用户ID: " + userId);
        } else {
            System.out.println("添加群成员失败");
        }
    }
    
    /**
     * 退出群组
     */
    private static void leaveGroup() {
        Scanner scanner = new Scanner(System.in);
        
        System.out.println("=== 退出群组 ===");
        
        // 先列出用户的群组
        listMyGroups();
        
        System.out.print("请输入要退出的群组ID: ");
        String groupId = scanner.nextLine().trim();
        
        if (groupId.isEmpty()) {
            System.out.println("群组ID不能为空");
            return;
        }
        
        System.out.print("确认要退出群组 " + groupId + " 吗？(y/N): ");
        String confirm = scanner.nextLine().trim().toLowerCase();
        
        if (!confirm.equals("y") && !confirm.equals("yes")) {
            System.out.println("取消退出群组");
            return;
        }
        
        // 退出群组
        boolean success = groupManager.leaveGroup(groupId, currentUser.getUserId());
        
        if (success) {
            System.out.println("退出群组成功!");
            System.out.println("已退出群组: " + groupId);
        } else {
            System.out.println("退出群组失败");
        }
    }
    
    /**
     * 清理资源
     */
    private static void cleanup() {
        System.out.println("正在清理资源...");
        
        if (chatClient != null) {
            chatClient.stop();
            System.out.println("聊天客户端已停止");
        }
        
        System.out.println("群聊演示程序结束");
    }
    
    /**
     * 群聊API使用示例
     */
    public static class GroupApiExamples {
        
        public static void main(String[] args) {
            System.out.println("=== 群聊API使用示例 ===");
            
            // 初始化示例用户
            UserInfo user1 = new UserInfo("user001", "张三", "张三");
            UserInfo user2 = new UserInfo("user002", "李四", "李四");
            UserInfo user3 = new UserInfo("user003", "王五", "王五");
            
            // 获取GroupManager实例
            GroupManager groupManager = GroupManager.getInstance();
            
            // 示例1: 创建群组
            System.out.println("\n示例1: 创建群组");
            String groupId = groupManager.createGroup("技术交流群", user1.getUserId(), "技术讨论和分享");
            System.out.println("创建群组成功，群组ID: " + groupId);
            
            // 示例2: 添加群成员
            System.out.println("\n示例2: 添加群成员");
            boolean addMember1 = groupManager.addMember(groupId, user2.getUserId());
            boolean addMember2 = groupManager.addMember(groupId, user3.getUserId());
            System.out.println("添加用户2: " + (addMember1 ? "成功" : "失败"));
            System.out.println("添加用户3: " + (addMember2 ? "成功" : "失败"));
            
            // 示例3: 获取群信息
            System.out.println("\n示例3: 获取群信息");
            try {
                javax.net.p2p.model.GroupInfo groupInfo = groupManager.getGroupInfo(groupId);
                System.out.println("群组名称: " + groupInfo.getName());
                System.out.println("群组描述: " + groupInfo.getDescription());
                System.out.println("创建者: " + groupInfo.getCreatorId());
                System.out.println("创建时间: " + new java.util.Date(groupInfo.getCreateTime()));
            } catch (Exception e) {
                System.out.println("获取群信息失败: " + e.getMessage());
            }
            
            // 示例4: 获取群成员
            System.out.println("\n示例4: 获取群成员");
            java.util.List<String> members = groupManager.getGroupMembers(groupId);
            System.out.println("群成员列表: " + members);
            
            // 示例5: 发送群消息
            System.out.println("\n示例5: 发送群消息");
            String messageId = groupManager.sendGroupMessage(groupId, user1.getUserId(), 
                "欢迎大家加入技术交流群！");
            System.out.println("群消息发送" + (messageId != null ? "成功，消息ID: " + messageId : "失败"));
            
            // 示例6: 获取用户加入的群组
            System.out.println("\n示例6: 获取用户加入的群组");
            java.util.List<String> userGroups = groupManager.getUserGroups(user1.getUserId());
            System.out.println("用户1加入的群组: " + userGroups);
            
            // 示例7: 退出群组
            System.out.println("\n示例7: 退出群组");
            boolean leaveSuccess = groupManager.leaveGroup(groupId, user2.getUserId());
            System.out.println("用户2退出群组: " + (leaveSuccess ? "成功" : "失败"));
            
            System.out.println("\n群聊API示例演示完成");
        }
    }
    
    /**
     * 群聊场景模拟
     */
    public static class GroupChatScenario {
        
        public static void runScenario() {
            System.out.println("=== 群聊场景模拟 ===");
            System.out.println();
            
            // 模拟三个用户
            String user1 = "user001";
            String user2 = "user002";
            String user3 = "user003";
            
            // 创建群组
            GroupManager groupManager = GroupManager.getInstance();
            String groupId = groupManager.createGroup("项目讨论组", user1, "项目进度讨论");
            
            System.out.println("用户1创建群组: " + groupId);
            
            // 添加成员
            groupManager.addMember(groupId, user2);
            groupManager.addMember(groupId, user3);
            
            System.out.println("添加用户2和用户3到群组");
            
            // 模拟群聊对话
            System.out.println("\n模拟群聊对话:");
            System.out.println("----------------------------------------");
            System.out.printf("[%s] %s: 大家好，今天的项目进度如何？%n", 
                formatTime(System.currentTimeMillis()), "user001");
            System.out.printf("[%s] %s: 我这边开发完成了用户管理模块%n", 
                formatTime(System.currentTimeMillis() + 1000), "user002");
            System.out.printf("[%s] %s: 我在做UI界面，还需要1天时间%n", 
                formatTime(System.currentTimeMillis() + 2000), "user003");
            System.out.printf("[%s] %s: 好的，明天上午10点开会讨论%n", 
                formatTime(System.currentTimeMillis() + 3000), "user001");
            System.out.println("----------------------------------------");
            
            System.out.println("\n群聊场景模拟完成");
        }
        
        private static String formatTime(long timestamp) {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("HH:mm:ss");
            return sdf.format(new java.util.Date(timestamp));
        }
    }
    
    /**
     * 快速启动示例
     */
    public static void quickStart() {
        System.out.println("=== 群聊功能快速启动示例 ===");
        System.out.println();
        
        // 运行群聊场景模拟
        GroupChatScenario.runScenario();
        
        System.out.println();
        System.out.println("使用说明:");
        System.out.println("1. 首先运行IM服务器: IMServer");
        System.out.println("2. 运行GroupChatDemo，输入用户信息");
        System.out.println("3. 创建或加入群组");
        System.out.println("4. 在群组中发送和接收消息");
        System.out.println();
        
        System.out.println("API 使用示例:");
        System.out.println("----------------------");
        System.out.println("// 创建群组");
        System.out.println("GroupManager manager = GroupManager.getInstance();");
        System.out.println("String groupId = manager.createGroup(\"技术交流\", \"user001\", \"技术讨论\");");
        System.out.println();
        
        System.out.println("// 添加群成员");
        System.out.println("manager.addMember(groupId, \"user002\");");
        System.out.println();
        
        System.out.println("// 发送群消息");
        System.out.println("String msgId = manager.sendGroupMessage(groupId, \"user001\", \"大家好！\");");
        System.out.println();
        
        System.out.println("// 获取群信息");
        System.out.println("GroupInfo info = manager.getGroupInfo(groupId);");
        System.out.println();
        
        System.out.println("// 获取用户加入的群组");
        System.out.println("List<String> groups = manager.getUserGroups(\"user001\");");
        System.out.println();
    }
}