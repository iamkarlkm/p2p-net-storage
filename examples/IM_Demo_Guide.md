# P2P即时通讯系统 - 演示指南

## 概述

本指南提供P2P即时通讯系统的完整演示流程，帮助用户快速了解和使用系统功能。

## 系统架构

```
客户端A <--UDP--> IMServer <--UDP--> 客户端B
        <--UDP-->                 <--UDP-->
```

### 核心组件
1. **IMServer** - 即时通讯服务器（端口：6060）
2. **ChatDemo** - 演示客户端程序
3. **UserManager** - 用户管理服务
4. **ChatMessageProcessor** - 消息处理服务

## 快速开始

### 环境要求
- Java 8+
- Maven 3.6+
- Windows/Linux/macOS

### 1. 编译项目
```bash
# Windows
cd c:\2025\code\ImageFileServer
mvn clean compile

# Linux/macOS
cd /2025/code/ImageFileServer
mvn clean compile
```

### 2. 运行测试
```bash
# Windows
scripts\run_im_tests.bat

# Linux/macOS
chmod +x scripts/run_im_tests.sh
./scripts/run_im_tests.sh
```

## 演示场景

### 场景1：单服务器多客户端聊天

#### 步骤1：启动服务器
```bash
# 启动IM服务器（端口6060）
java -cp target/classes javax.net.p2p.im.IMServer
```

控制台输出：
```
[INFO] IM服务器启动中...
[INFO] 服务器绑定地址: 127.0.0.1:6060
[INFO] 服务器启动成功，等待客户端连接...
```

#### 步骤2：启动第一个客户端（用户1）
```bash
# 新终端窗口
java -cp target/classes javax.net.p2p.im.ChatDemo
```

控制台交互：
```
=== P2P即时通讯系统 ===
请选择操作：
1. 用户登录
2. 发送消息
3. 查看在线用户
4. 退出系统

选择: 1

请输入用户ID: user001
请输入用户名: 张三
请输入昵称（可选）: 张三
[INFO] 登录成功！用户ID: user001
```

#### 步骤3：启动第二个客户端（用户2）
```bash
# 新终端窗口
java -cp target/classes javax.net.p2p.im.ChatDemo
```

控制台交互：
```
=== P2P即时通讯系统 ===
请选择操作：
1. 用户登录
2. 发送消息
3. 查看在线用户
4. 退出系统

选择: 1

请输入用户ID: user002
请输入用户名: 李四
请输入昵称（可选）: 李四
[INFO] 登录成功！用户ID: user002
```

#### 步骤4：查看在线用户
在任意客户端：
```
选择: 3
[INFO] 在线用户列表：
  用户ID: user001, 用户名: 张三, 昵称: 张三
  用户ID: user002, 用户名: 李四, 昵称: 李四
```

#### 步骤5：发送消息
在用户1的客户端：
```
选择: 2

请输入接收者用户ID: user002
请输入消息内容: 你好，李四！
[INFO] 消息发送成功！消息ID: msg_001
```

在用户2的客户端：
```
[收到新消息]
发送者: user001 (张三)
内容: 你好，李四！
时间: 2026-03-14 10:30:00
```

#### 步骤6：回复消息
在用户2的客户端：
```
选择: 2

请输入接收者用户ID: user001
请输入消息内容: 你好，张三！收到你的消息了。
[INFO] 消息发送成功！消息ID: msg_002
```

### 场景2：消息状态跟踪

#### 步骤1：发送消息并跟踪状态
```java
// 在代码中跟踪消息状态
ChatClient client = ChatClient.getInstance();
String messageId = client.sendPrivateMessage("user002", "测试消息状态");

// 检查消息状态
int status = client.getMessageStatus(messageId);
// 状态码: 0=发送中, 1=已发送, 2=已送达, 3=已读

// 注册状态回调
client.registerMessageStatusCallback(messageId, (msgId, newStatus) -> {
    System.out.println("消息状态更新: " + msgId + " -> " + newStatus);
});
```

#### 步骤2：查看消息历史
```java
// 获取聊天历史
List<ChatMessage> history = client.getChatHistory("user002");
for (ChatMessage msg : history) {
    System.out.println(msg.getSender() + ": " + msg.getContent() + 
                      " [" + msg.getStatus() + "]");
}
```

### 场景3：群聊功能演示

#### 步骤1：创建群组
```java
// 创建群组
GroupManager groupManager = GroupManager.getInstance();
String groupId = groupManager.createGroup("技术交流群", "user001");

// 添加成员
groupManager.addMember(groupId, "user002");
groupManager.addMember(groupId, "user003");
```

#### 步骤2：发送群消息
```java
// 发送群消息
String messageId = groupManager.sendGroupMessage(groupId, "user001", 
    "欢迎大家加入技术交流群！");
```

#### 步骤3：接收群消息
```java
// 注册群消息监听器
groupManager.registerGroupMessageListener(groupId, (groupId, sender, content) -> {
    System.out.println("[" + groupId + "] " + sender + ": " + content);
});
```

## API使用示例

### 1. 用户管理API
```java
// 用户登录
UserManager userManager = UserManager.getInstance();
UserInfo user = new UserInfo("test001", "测试用户", "测试昵称");
InetSocketAddress address = new InetSocketAddress("127.0.0.1", 12345);
boolean success = userManager.login(user, address);

// 获取在线用户
List<UserInfo> onlineUsers = userManager.getOnlineUsers();
for (UserInfo onlineUser : onlineUsers) {
    System.out.println(onlineUser.getUserId() + " - " + onlineUser.getUsername());
}

// 用户登出
userManager.logout("test001");
```

### 2. 消息发送API
```java
// 发送私聊消息
ChatMessageProcessor processor = ChatMessageProcessor.getInstance();
String messageId = processor.sendMessage(
    "sender001", 
    "receiver001", 
    "Hello, World!"
);

// 注册消息状态监听
processor.registerMessageStatusListener(messageId, (msgId, status) -> {
    System.out.println("消息状态更新: " + msgId + " -> " + status);
});

// 获取消息状态
int status = processor.getMessageStatus(messageId);
```

### 3. 客户端API
```java
// 初始化客户端
ChatClient client = ChatClient.getInstance();
UserInfo user = new UserInfo("client001", "客户端用户", "昵称");
InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", 6060);

client.initialize(user, serverAddress, new ChatClient.SimpleChatCallback());
client.start();

// 发送消息
String msgId = client.sendPrivateMessage("user002", "你好！");

// 接收消息回调
client.setMessageReceivedCallback((senderId, content) -> {
    System.out.println("收到来自 " + senderId + " 的消息: " + content);
});
```

## 测试用例演示

### 单元测试执行
```bash
# 运行特定测试类
mvn test -Dtest=javax.net.p2p.im.UserManagerTest

# 运行所有IM系统测试
mvn test -Dtest="javax.net.p2p.im.*Test"
```

### 测试覆盖场景
1. **用户管理测试**
   - 用户登录/登出
   - 在线用户查询
   - 用户状态管理

2. **消息处理测试**
   - 消息发送/接收
   - 消息确认机制
   - 消息状态跟踪

3. **集成测试**
   - 完整聊天流程
   - 并发用户操作
   - 错误处理

## 故障排除

### 常见问题

#### 1. 服务器无法启动
```bash
# 检查端口占用
netstat -an | grep 6060

# 解决方案
# a) 修改服务器端口
java -cp target/classes javax.net.p2p.im.IMServer 7070

# b) 停止占用端口的进程
```

#### 2. 客户端无法连接
```bash
# 检查网络连接
ping 127.0.0.1

# 检查服务器状态
telnet 127.0.0.1 6060

# 解决方案
# a) 确保服务器已启动
# b) 检查防火墙设置
```

#### 3. 消息发送失败
```java
// 检查接收者是否在线
if (userManager.isUserOnline("receiver001")) {
    // 发送消息
} else {
    System.out.println("接收者不在线");
}

// 检查网络连接
// 检查消息格式
```

### 日志查看
```bash
# 查看服务器日志
tail -f logs/im-server.log

# 查看客户端日志
tail -f logs/im-client.log

# 启用调试日志
java -Dlogging.level.javax.net.p2p.im=DEBUG -cp target/classes ...
```

## 性能测试

### 基准测试
```bash
# 启动性能测试
java -cp target/classes javax.net.p2p.im.PerformanceTest

# 测试结果示例
[INFO] 并发用户数: 100
[INFO] 消息发送速率: 1000 msg/s
[INFO] 平均延迟: 50ms
[INFO] 成功率: 99.8%
```

### 压力测试场景
1. **100并发用户**
2. **1000消息/秒**发送速率
3. **持续5分钟**压力测试
4. **消息大小**: 1KB

## 扩展功能演示

### 1. 消息加密
```java
// 启用消息加密
ChatClient client = ChatClient.getInstance();
client.enableEncryption("AES-256-GCM", encryptionKey);

// 发送加密消息
client.sendEncryptedMessage("user002", "加密消息内容");
```

### 2. 文件传输
```java
// 发送文件
File file = new File("document.pdf");
client.sendFile("user002", file, (progress) -> {
    System.out.println("上传进度: " + progress + "%");
});
```

### 3. 消息撤回
```java
// 发送消息
String messageId = client.sendPrivateMessage("user002", "需要撤回的消息");

// 撤回消息
boolean success = client.recallMessage(messageId);
if (success) {
    System.out.println("消息撤回成功");
}
```

## 监控和管理

### 服务器状态监控
```bash
# 查看服务器状态
java -cp target/classes javax.net.p2p.im.IMServer --status

# 输出示例
服务器状态:
  运行时间: 2小时30分钟
  在线用户: 15
  消息总数: 1250
  系统负载: 45%
```

### 客户端状态监控
```java
// 获取客户端统计信息
ChatClient.Stats stats = client.getStatistics();
System.out.println("发送消息: " + stats.getSentMessages());
System.out.println("接收消息: " + stats.getReceivedMessages());
System.out.println("成功率: " + stats.getSuccessRate() + "%");
```

## 总结

通过本演示指南，您可以：
1. ✅ 快速启动IM服务器和客户端
2. ✅ 实现点对点聊天功能
3. ✅ 测试系统各项功能
4. ✅ 监控系统运行状态
5. ✅ 扩展系统功能

### 下一步
1. 阅读详细文档: `IM_SYSTEM_README.md`
2. 查看API文档: `javadoc/`
3. 运行性能测试
4. 部署到生产环境

---
*本演示系统基于P2P UDP协议构建，具有良好的扩展性和可靠性。*