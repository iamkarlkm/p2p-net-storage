# P2P即时通讯系统 - 点对点单聊功能

## 概述

基于现有的P2P UDP通信协议，构建了一个完整的即时通讯系统，实现了点对点单聊功能。该系统包含服务器端和客户端组件，支持用户登录、在线用户管理、消息发送与接收、消息状态跟踪等功能。

## 系统架构

### 核心组件

1. **IMServer** - 即时通讯服务器
2. **IMServerProcessor** - 集成即时通讯处理器的服务器端处理器
3. **UserLoginProcessor** - 用户登录和状态管理处理器
4. **ChatMessageProcessor** - 聊天消息处理器
5. **ChatClient** - 客户端聊天处理器
6. **ChatDemo** - 演示程序

### 消息协议

系统定义了完整的即时通讯命令枚举 `IMCommand`，包含以下类别：

- **用户相关命令** (1000-1099): 登录、登出、心跳、状态更新
- **聊天相关命令** (1100-1199): 消息发送、接收、确认、状态更新
- **群组相关命令** (1200-1299): 群组创建、加入、消息发送
- **系统状态命令** (1300-1399): 系统状态查询、连接测试

## 功能特性

### 已实现功能

1. **用户管理**
   - 用户登录和身份验证
   - 用户登出和状态清理
   - 在线用户列表查询
   - 用户心跳检测

2. **点对点聊天**
   - 消息发送与接收
   - 消息ID生成和唯一性保证
   - 消息状态跟踪（发送中、已发送、已送达）
   - 消息确认机制

3. **系统功能**
   - 服务器状态监控
   - 连接测试
   - 错误处理和响应

### 扩展功能

1. **消息类型支持**
   - 文本消息
   - 可扩展支持图片、文件、语音等

2. **状态管理**
   - 用户在线状态
   - 消息发送状态
   - 系统运行状态

## 快速开始

### 1. 启动服务器

```bash
# 编译项目
mvn clean compile

# 启动即时通讯服务器
java -cp target/classes javax.net.p2p.im.IMServer
```

### 2. 启动客户端

```bash
# 启动第一个客户端（用户1）
java -cp target/classes javax.net.p2p.im.ChatDemo

# 启动第二个客户端（用户2）
java -cp target/classes javax.net.p2p.im.ChatDemo
```

### 3. 客户端操作流程

1. **用户登录**
   - 输入用户ID (如: user001)
   - 输入用户名 (如: 张三)
   - 输入昵称 (可选)

2. **查看在线用户**
   - 在主菜单中选择 "1" 查看在线用户列表
   - 可以看到其他在线用户的信息

3. **发送消息**
   - 在主菜单中选择 "2" 发送消息
   - 输入接收者的用户ID
   - 输入消息内容
   - 消息将自动发送并跟踪状态

4. **接收消息**
   - 当其他用户发送消息时，会自动接收并显示
   - 系统会自动发送消息送达确认

## API使用示例

### 1. 服务器端

```java
// 启动服务器
IMServer server = new IMServer(6060);
server.start();

// 获取服务器状态
IMServer.ServerStatus status = server.getServerStatus();
System.out.println("在线用户数: " + status.getOnlineUserCount());
```

### 2. 客户端

```java
// 创建用户信息
UserInfo user = new UserInfo("user001", "张三", "张三");

// 初始化聊天客户端
ChatClient client = ChatClient.getInstance();
InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", 6060);
ChatClient.ChatCallback callback = new ChatClient.SimpleChatCallback();

client.initialize(user, serverAddress, callback);
client.start();

// 发送消息
String messageId = client.sendPrivateMessage("user002", "你好！");

// 获取在线用户
List<UserInfo> onlineUsers = client.getOnlineUsers();
```

### 3. 消息处理

```java
// 创建聊天消息
ChatMessage message = new ChatMessage("sender001", "receiver002", "你好！");
message.setMessageType("TEXT");
message.setStatus(0); // 发送中

// 发送消息确认
client.sendMessageAck(messageId, "DELIVERED");
```

## 系统设计

### 消息流程

1. **登录流程**
   ```
   客户端 -> 服务器: USER_LOGIN
   服务器 -> 客户端: 登录响应（成功/失败）
   ```

2. **消息发送流程**
   ```
   客户端A -> 服务器: CHAT_SEND（消息内容）
   服务器 -> 客户端B: CHAT_RECEIVE（转发消息）
   客户端B -> 服务器: CHAT_ACK（确认收到）
   服务器 -> 客户端A: CHAT_STATUS_UPDATE（状态更新）
   ```

### 状态管理

1. **用户状态**
   - 0: 离线
   - 1: 在线
   - 2: 忙碌
   - 3: 离开

2. **消息状态**
   - 0: 发送中
   - 1: 已发送
   - 2: 已送达
   - 3: 已读

### 线程模型

- **服务器端**: Netty事件循环组处理网络IO
- **消息处理**: 专用线程池处理业务逻辑
- **心跳检测**: 独立线程定期发送心跳

## 测试方法

### 1. 单元测试

```java
// 测试用户登录
@Test
public void testUserLogin() {
    UserManager userManager = UserManager.getInstance();
    UserInfo user = new UserInfo("test001", "测试用户", "测试");
    InetSocketAddress address = new InetSocketAddress("127.0.0.1", 12345);
    
    boolean success = userManager.login(user, address);
    assertTrue(success);
    assertTrue(userManager.isUserOnline("test001"));
}
```

### 2. 集成测试

```java
// 测试消息发送和接收
@Test
public void testMessageFlow() {
    // 初始化服务器
    IMServer server = new IMServer(6060);
    server.start();
    
    // 初始化客户端
    ChatClient client1 = ChatClient.getInstance();
    ChatClient client2 = ChatClient.getInstance();
    
    // 用户登录
    client1.login(user1, serverAddress);
    client2.login(user2, serverAddress);
    
    // 发送消息
    String messageId = client1.sendPrivateMessage("user2", "测试消息");
    
    // 验证消息接收
    assertNotNull(messageId);
    assertTrue(client2.hasReceivedMessage(messageId));
}
```

## 配置参数

### 服务器配置

| 参数 | 默认值 | 说明 |
|------|--------|------|
| SERVER_IP | 127.0.0.1 | 服务器绑定IP地址 |
| SERVER_PORT | 6060 | 服务器监听端口 |
| MAGIC | 0x12345678 | 消息魔法数字 |
| QUEUE_SIZE | 1000 | 消息队列大小 |

### 客户端配置

| 参数 | 默认值 | 说明 |
|------|--------|------|
| HEARTBEAT_INTERVAL | 30000 | 心跳间隔（毫秒） |
| RECONNECT_INTERVAL | 5000 | 重连间隔（毫秒） |
| MESSAGE_TIMEOUT | 30000 | 消息超时时间（毫秒） |

## 扩展计划

### 1. 短期计划
- [ ] 消息历史记录存储
- [ ] 消息撤回功能
- [ ] 消息转发功能
- [ ] 用户状态更新通知

### 2. 中期计划
- [ ] 群聊功能完整实现
- [ ] 文件传输功能
- [ ] 消息加密传输
- [ ] 用户权限管理

### 3. 长期计划
- [ ] 分布式服务器架构
- [ ] 消息持久化存储
- [ ] 负载均衡支持
- [ ] 跨平台客户端

## 技术依赖

### 核心依赖
- **Netty 4.x**: 网络通信框架
- **Lombok**: 简化Java代码
- **Java 8+**: 运行时环境

### 可选依赖
- **Redis**: 用户状态缓存
- **MySQL**: 消息持久化存储
- **Spring Boot**: 服务器管理框架

## 故障排除

### 常见问题

1. **服务器无法启动**
   ```
   检查端口是否被占用: netstat -an | grep 6060
   检查防火墙设置
   验证Java版本
   ```

2. **客户端无法连接**
   ```
   检查服务器IP和端口
   验证网络连接
   检查防火墙设置
   ```

3. **消息发送失败**
   ```
   检查用户是否在线
   验证消息格式
   检查网络连接
   ```

### 日志查看

```bash
# 查看服务器日志
tail -f logs/im-server.log

# 查看客户端日志
tail -f logs/im-client.log
```

## 性能指标

### 基准测试

| 指标 | 预期值 | 测试条件 |
|------|--------|----------|
| 并发用户数 | 1000 | 单服务器 |
| 消息延迟 | < 100ms | 局域网环境 |
| 吞吐量 | 1000 msg/s | 普通硬件 |
| 内存占用 | < 512MB | 1000在线用户 |

## 安全考虑

### 已实现安全措施
1. **用户身份验证**: 登录时验证用户信息
2. **消息完整性**: 序列化/反序列化校验
3. **状态跟踪**: 防止消息重复和丢失

### 建议安全增强
1. **消息加密**: 使用SSL/TLS或自定义加密
2. **访问控制**: 基于角色的权限管理
3. **审计日志**: 操作记录和追溯

## 部署建议

### 开发环境
- 单服务器部署
- 本地数据库
- 直接运行Java应用

### 生产环境
- 多服务器负载均衡
- 独立数据库服务器
- Docker容器化部署
- 监控和告警系统

## 联系方式

如有问题或建议，请联系：
- **项目维护者**: IM System Team
- **技术支持**: 通过项目Issues反馈
- **文档更新**: 提交Pull Request

---

*本系统基于现有P2P UDP协议构建，具有良好的扩展性和可靠性，可以满足基本即时通讯需求。*