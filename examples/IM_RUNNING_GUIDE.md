# P2P即时通讯系统 - 运行指南

## 目录

1. [快速开始](#快速开始)
2. [系统架构](#系统架构)
3. [环境要求](#环境要求)
4. [安装和编译](#安装和编译)
5. [运行演示](#运行演示)
6. [API使用](#api使用)
7. [测试系统](#测试系统)
8. [故障排除](#故障排除)
9. [性能优化](#性能优化)
10. [常见问题](#常见问题)

## 快速开始

### 1分钟快速体验

```bash
# Windows
cd c:\2025\code\ImageFileServer
scripts\run_demo.bat

# Linux/macOS
cd /2025/code/ImageFileServer
chmod +x scripts/run_demo.sh
./scripts/run_demo.sh
```

### 基本流程

1. **启动服务器**
   ```bash
   scripts/start_server.bat   # Windows
   ./scripts/start_server.sh  # Linux/macOS
   ```

2. **启动客户端**
   ```bash
   # 在多个终端窗口运行
   java -cp target/classes javax.net.p2p.im.ChatDemo
   ```

3. **开始聊天**
   - 输入用户信息登录
   - 查看在线用户
   - 发送消息给其他用户

## 系统架构

### 组件概述

```
┌─────────────────────────────────────────────┐
│                客户端应用                    │
│  (ChatDemo, GroupChatDemo, PerformanceDemo) │
└───────────────────┬─────────────────────────┘
                    │
┌───────────────────▼─────────────────────────┐
│             聊天客户端 (ChatClient)           │
│  - 消息发送/接收                            │
│  - 用户状态管理                            │
│  - 连接管理                                │
└───────────────────┬─────────────────────────┘
                    │
┌───────────────────▼─────────────────────────┐
│               UDP网络层                      │
│  - 可靠UDP传输                              │
│  - 数据包序列化/反序列化                     │
└───────────────────┬─────────────────────────┘
                    │
┌───────────────────▼─────────────────────────┐
│              IM服务器 (IMServer)             │
│  - 用户认证                                │
│  - 消息路由                                │
│  - 群组管理                                │
└─────────────────────────────────────────────┘
```

### 核心组件

| 组件 | 功能 | 位置 |
|------|------|------|
| IMServer | 即时通讯服务器 | `javax.net.p2p.im.IMServer` |
| ChatClient | 聊天客户端 | `javax.net.p2p.im.ChatClient` |
| UserManager | 用户管理 | `javax.net.p2p.im.UserManager` |
| GroupManager | 群组管理 | `javax.net.p2p.im.GroupManager` |
| ChatMessageProcessor | 消息处理 | `javax.net.p2p.im.ChatMessageProcessor` |

## 环境要求

### 必需环境

- **Java**: JDK 8 或更高版本
- **Maven**: 3.6 或更高版本
- **操作系统**: Windows 10+, Linux, macOS

### 推荐配置

- **内存**: 至少 2GB RAM
- **磁盘空间**: 至少 500MB 可用空间
- **网络**: 稳定的网络连接

### 验证环境

```bash
# 检查Java版本
java -version

# 检查Maven版本
mvn -version

# 检查网络连接
ping 127.0.0.1
```

## 安装和编译

### 1. 获取项目

```bash
# 如果项目已存在，直接进入目录
cd c:\2025\code\ImageFileServer   # Windows
cd /2025/code/ImageFileServer     # Linux/macOS
```

### 2. 编译项目

```bash
# 完整编译（包括测试）
mvn clean compile

# 快速编译（跳过测试）
mvn clean compile -DskipTests

# 生成Jar包
mvn clean package
```

### 3. 导入IDE

#### IntelliJ IDEA
1. File → Open → 选择项目目录
2. 等待Maven依赖下载完成
3. 运行 `IMServer.java` 作为服务器

#### Eclipse
1. File → Import → Maven → Existing Maven Projects
2. 选择项目目录
3. 运行 `IMServer.java` 作为服务器

## 运行演示

### 演示类型

#### 1. 点对点聊天演示

**功能**: 展示基本的用户登录、在线用户查看、消息发送功能

```bash
# 方法1: 使用演示脚本
scripts\run_demo.bat
# 选择选项1

# 方法2: 手动运行
# 终端1: 启动服务器
java -cp target/classes javax.net.p2p.im.IMServer

# 终端2: 启动客户端1
java -cp target/classes javax.net.p2p.im.ChatDemo

# 终端3: 启动客户端2
java -cp target/classes javax.net.p2p.im.ChatDemo
```

**操作步骤**:
1. 在两个客户端分别输入不同用户信息登录
2. 在任意客户端查看在线用户
3. 选择一个在线用户发送消息
4. 查看消息接收情况

#### 2. 群聊功能演示

**功能**: 展示群组创建、成员管理、群消息发送功能

```bash
# 方法1: 使用演示脚本
scripts\run_demo.bat
# 选择选项2

# 方法2: 手动运行
java -cp "target/classes;." examples.GroupChatDemo
```

**操作步骤**:
1. 输入用户信息登录
2. 创建新群组
3. 添加其他用户到群组
4. 在群组中发送消息
5. 查看群成员和群信息

#### 3. 性能测试演示

**功能**: 测试系统性能和可靠性

```bash
# 方法1: 使用演示脚本
scripts\run_demo.bat
# 选择选项3

# 方法2: 手动运行
java -cp "target/classes;." examples.PerformanceDemo
```

**测试场景**:
- **并发用户测试**: 模拟多用户同时登录和发送消息
- **消息吞吐量测试**: 测量系统消息处理能力
- **系统稳定性测试**: 长时间运行测试系统稳定性
- **压力测试**: 高负载下的系统表现

#### 4. 完整系统演示

**功能**: 同时运行所有演示组件

```bash
# 使用演示脚本
scripts\run_demo.bat
# 选择选项4
```

**包含组件**:
- IM服务器
- 两个聊天客户端
- 群聊演示程序
- 性能测试程序

### 演示脚本说明

#### Windows脚本
- `scripts\run_demo.bat`: 主演示脚本
- `scripts\start_server.bat`: 启动服务器脚本
- `scripts\run_im_tests.bat`: 运行测试脚本

#### Linux/macOS脚本
- `scripts/run_demo.sh`: 主演示脚本
- `scripts/start_server.sh`: 启动服务器脚本
- `scripts/run_im_tests.sh`: 运行测试脚本

## API使用

### 核心API示例

#### 1. 用户管理API

```java
// 获取UserManager实例
UserManager userManager = UserManager.getInstance();

// 用户登录
UserInfo user = new UserInfo("user001", "张三", "张三");
InetSocketAddress address = new InetSocketAddress("127.0.0.1", 12345);
boolean loginSuccess = userManager.login(user, address);

// 获取在线用户
List<UserInfo> onlineUsers = userManager.getOnlineUsers();

// 用户登出
userManager.logout("user001");

// 检查用户在线状态
boolean isOnline = userManager.isUserOnline("user001");
```

#### 2. 消息发送API

```java
// 获取ChatMessageProcessor实例
ChatMessageProcessor processor = ChatMessageProcessor.getInstance();

// 发送消息
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

#### 3. 群聊API

```java
// 获取GroupManager实例
GroupManager groupManager = GroupManager.getInstance();

// 创建群组
String groupId = groupManager.createGroup("技术交流群", "user001", "技术讨论");

// 添加群成员
boolean success = groupManager.addMember(groupId, "user002");

// 发送群消息
String messageId = groupManager.sendGroupMessage(groupId, "user001", "大家好！");

// 获取群信息
GroupInfo groupInfo = groupManager.getGroupInfo(groupId);

// 获取群成员
List<String> members = groupManager.getGroupMembers(groupId);
```

#### 4. 客户端API

```java
// 获取ChatClient实例
ChatClient client = ChatClient.getInstance();

// 初始化客户端
UserInfo user = new UserInfo("client001", "客户端用户", "昵称");
InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", 6060);
ChatClient.ChatCallback callback = new ChatClient.SimpleChatCallback();

boolean initSuccess = client.initialize(user, serverAddress, callback);
client.start();

// 发送私聊消息
String msgId = client.sendPrivateMessage("user002", "你好！");

// 获取在线用户
List<UserInfo> onlineUsers = client.getOnlineUsers();

// 检查用户在线状态
boolean isOnline = client.isUserOnline("user002");
```

### API使用示例程序

#### 运行API示例

```bash
# 群聊API示例
java -cp "target/classes;." examples.GroupChatDemo$GroupApiExamples

# 性能测试API示例
java -cp "target/classes;." examples.PerformanceDemo$BenchmarkExample

# 性能优化建议
java -cp "target/classes;." examples.PerformanceDemo$OptimizationGuide
```

## 测试系统

### 单元测试

```bash
# 运行所有IM系统测试
scripts\run_im_tests.bat   # Windows
./scripts/run_im_tests.sh  # Linux/macOS

# 运行特定测试类
mvn test -Dtest=javax.net.p2p.im.UserManagerTest
mvn test -Dtest=javax.net.p2p.im.ChatMessageProcessorTest
mvn test -Dtest=javax.net.p2p.im.IMIntegrationTest
```

### 测试覆盖范围

| 测试类 | 测试功能 | 重要性 |
|--------|----------|--------|
| UserManagerTest | 用户登录、登出、状态管理 | 高 |
| ChatMessageProcessorTest | 消息发送、接收、状态跟踪 | 高 |
| IMCommandTest | 命令枚举和协议验证 | 中 |
| IMIntegrationTest | 完整系统集成测试 | 高 |

### 性能测试

#### 基准测试

```bash
# 运行性能基准测试
java -cp "target/classes;." examples.PerformanceDemo$BenchmarkExample

# 自定义性能测试
java -cp "target/classes;." examples.PerformanceDemo 10 100 60
```

#### 测试指标

1. **延迟**: 消息发送到接收的时间
2. **吞吐量**: 每秒处理的消息数
3. **成功率**: 消息发送成功的比例
4. **资源使用**: CPU和内存使用情况

## 故障排除

### 常见问题

#### 1. 服务器无法启动

**症状**: 端口被占用或权限不足

**解决方案**:
```bash
# 检查端口占用
netstat -an | findstr :6060   # Windows
netstat -an | grep :6060      # Linux/macOS

# 修改服务器端口
java -cp target/classes javax.net.p2p.im.IMServer 7070

# 以管理员权限运行（Windows）
runas /user:Administrator "java -cp target/classes javax.net.p2p.im.IMServer"
```

#### 2. 客户端无法连接服务器

**症状**: 连接超时或连接拒绝

**解决方案**:
```bash
# 检查服务器是否运行
telnet 127.0.0.1 6060

# 检查防火墙设置
# Windows: 控制面板 → Windows Defender防火墙
# Linux: sudo ufw status

# 修改客户端连接地址
# 在代码中修改 serverAddress 参数
```

#### 3. 消息发送失败

**症状**: 消息发送后无响应或超时

**解决方案**:
```java
// 检查接收者是否在线
if (userManager.isUserOnline("receiver001")) {
    // 发送消息
} else {
    System.out.println("接收者不在线");
}

// 检查网络连接
// 增加超时时间
// 启用重试机制
```

#### 4. 内存使用过高

**症状**: 系统运行缓慢或崩溃

**解决方案**:
```bash
# 调整JVM内存参数
java -Xms512m -Xmx1024m -cp target/classes ...

# 监控内存使用
jconsole  # Java监控工具

# 优化代码
# - 及时释放资源
# - 使用对象池
# - 优化数据序列化
```

### 日志查看

#### 启用详细日志

```bash
# 启动时启用调试日志
java -Dlogging.level.javax.net.p2p.im=DEBUG -cp target/classes ...

# 查看日志文件
# Windows: type logs\im-server.log
# Linux: tail -f logs/im-server.log
```

#### 日志级别

- **ERROR**: 错误信息
- **WARN**: 警告信息
- **INFO**: 一般信息（默认）
- **DEBUG**: 调试信息
- **TRACE**: 详细跟踪信息

## 性能优化

### 配置优化

#### JVM参数优化

```bash
# 生产环境推荐配置
java -server \
     -Xms1g -Xmx2g \
     -XX:+UseG1GC \
     -XX:MaxGCPauseMillis=200 \
     -XX:+ParallelRefProcEnabled \
     -XX:+HeapDumpOnOutOfMemoryError \
     -cp target/classes javax.net.p2p.im.IMServer
```

#### 网络参数优化

```java
// 在代码中优化网络参数
// 增加UDP缓冲区大小
// 调整超时和重试策略
// 启用数据压缩
```

### 代码优化建议

#### 1. 消息处理优化

```java
// 使用批量消息发送
List<ChatMessage> messages = ...;
processor.sendMessagesBatch(messages);

// 启用消息压缩
processor.enableCompression(true);

// 使用异步消息处理
processor.sendMessageAsync(sender, receiver, content, callback);
```

#### 2. 内存管理优化

```java
// 使用对象池
ObjectPool<ByteBuf> bufferPool = new GenericObjectPool<>(...);

// 及时释放资源
try (ByteBuf buffer = bufferPool.borrowObject()) {
    // 使用buffer
} // 自动归还到池中

// 监控内存泄漏
// 定期检查对象引用
```

#### 3. 并发优化

```java
// 使用线程池管理并发
ExecutorService executor = Executors.newFixedThreadPool(
    Runtime.getRuntime().availableProcessors() * 2
);

// 使用并发集合
ConcurrentHashMap<String, UserInfo> onlineUsers = new ConcurrentHashMap<>();

// 避免锁竞争
// 使用读写锁或乐观锁
```

### 监控和调优

#### 系统监控

```bash
# 监控系统资源
top        # Linux/macOS
tasklist   # Windows

# 监控网络连接
netstat -an | grep ESTABLISHED

# 监控磁盘IO
iostat     # Linux
```

#### 性能监控工具

1. **JConsole**: Java监控和管理控制台
2. **VisualVM**: 可视化性能分析工具
3. **JProfiler**: 商业性能分析工具
4. **Arthas**: 阿里巴巴开源Java诊断工具

## 常见问题

### Q1: 系统支持多少并发用户？

**A**: 系统设计支持1000+并发用户，具体取决于服务器配置和网络带宽。

### Q2: 消息是否安全？

**A**: 当前版本使用明文传输，生产环境建议：
1. 启用TLS/SSL加密
2. 实现端到端加密
3. 使用消息签名

### Q3: 如何扩展系统？

**A**: 系统支持水平扩展：
1. 部署多个IMServer实例
2. 使用负载均衡器
3. 共享用户状态数据库

### Q4: 是否支持移动端？

**A**: 当前为Java桌面应用，但可以：
1. 开发Android/iOS客户端
2. 提供REST API接口
3. 使用WebSocket协议

### Q5: 如何备份数据？

**A**: 系统数据包括：
1. **用户数据**: 导出为JSON/CSV格式
2. **消息记录**: 定期归档到数据库
3. **系统配置**: 版本控制管理

### Q6: 如何贡献代码？

**A**: 欢迎贡献：
1. Fork项目仓库
2. 创建功能分支
3. 提交Pull Request
4. 遵循代码规范

## 获取帮助

### 文档资源

1. **项目文档**: `README.md`, `IM_SYSTEM_README.md`
2. **API文档**: 运行 `mvn javadoc:javadoc` 生成
3. **演示指南**: `examples/IM_Demo_Guide.md`
4. **架构文档**: `ARCHITECTURE.md`

### 技术支持

1. **问题报告**: 创建GitHub Issue
2. **功能请求**: 提交Feature Request
3. **代码审查**: 提交Pull Request
4. **讨论交流**: 参与项目讨论

### 联系方式

- **项目主页**: [项目GitHub地址]
- **问题反馈**: [GitHub Issues]
- **开发者**: IM System Team

---

*最后更新: 2026年3月14日*
*版本: 1.0*