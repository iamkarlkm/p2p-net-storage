# p2p-net-storage 设计原则和设计模式

## 架构设计原则

### 0. 强管控网络原则（默认拒绝）

本系统按“强管控网络”设计：任何未被显式识别与允许的行为都应被拒绝（deny-by-default）。这是一条架构级约束，不是可选优化。

必须拒绝的“未知”：

- 未知用户：未完成握手/登录/权限校验的请求
- 未知节点：不在信任根/注册信息内的节点身份或来源
- 未知服务：服务类别未启用或未加载
- 未知命令/未知数据格式：命令未注册、payload 不符合协议约定或无法解析

开发约束（新增能力必须满足）：

- 命令定义：新增命令必须绑定服务类别（P2PServiceCategory），并确定禁用时的行为（service unavailable/invalid）
- 数据模型：新增命令必须有明确的数据载荷结构（protobuf/Protostuff 模型），禁止使用“自由格式”执行类命令
- 权限模型：新增命令必须可被 Auth allowlist 管控（默认不开放给任意用户）
- 服务开关：新增命令必须可被服务类别统一启停，并支持按类别卸载/加载 handler
- 输入校验：所有外部输入（路径/参数/二进制数据）都必须校验；文件相关必须走沙箱工具方法
- 失败可观测：拒绝/失败必须返回明确错误（STD_ERROR/STD_UNKNOWN/INVALID_DATA）并便于审计

### 1. SOLID 原则

#### 1.1 单一职责原则 (Single Responsibility Principle)
- **ImageFileServer**: 只负责服务器启动和生命周期管理
- **P2PServer**: 只负责网络连接管理和消息分发
- **ServerMessageProcessor**: 只负责消息路由和处理器调度
- **FileGetServerHandler**: 只负责文件下载业务逻辑
- **P2PConfig**: 只负责配置管理和加载

#### 1.2 开闭原则 (Open-Closed Principle)
- **抽象基类设计**: `AbstractTcpMessageProcessor`、`AbstractP2PClient`
- **扩展点**: 通过实现接口添加新的处理器
- **配置驱动**: 通过配置文件扩展功能，无需修改代码

#### 1.3 里氏替换原则 (Liskov Substitution Principle)
- **处理器继承体系**: 所有处理器实现 `P2PCommandHandler` 接口
- **消息处理器继承**: `AbstractTcpMessageProcessor` 可被具体实现替换
- **客户端继承**: `AbstractP2PClient` 可被 `P2PClientTcp/Udp` 替换

#### 1.4 接口隔离原则 (Interface Segregation Principle)
- **细粒度接口**:
  - `P2PCommandHandler`: 仅包含消息处理方法
  - `P2PFileService`: 仅包含文件操作方法
  - `P2PClientService`: 仅包含客户端操作方法
- **角色分离**: 不同模块关注不同的接口

#### 1.5 依赖倒置原则 (Dependency Inversion Principle)
- **依赖抽象**: 模块间通过接口通信，不依赖具体实现
- **依赖注入**: 通过构造函数或方法参数注入依赖
- **配置解耦**: 通过配置而非硬编码决定具体实现

### 2. DRY 原则 (Don't Repeat Yourself)
- **通用工具类**: `FileUtil` 封装所有文件操作
- **编解码复用**: `P2PWrapperEncoder/Decoder` 统一消息处理
- **异常处理**: 统一的异常处理机制和错误响应生成
- **配置管理**: 集中式的配置加载和访问

### 3. KISS 原则 (Keep It Simple, Stupid)
- **简单协议设计**: P2P协议命令清晰，易于理解
- **直观API**: 客户端API设计简单直接
- **最小配置**: 配置文件结构简单明了
- **明确职责**: 每个类和方法职责明确

### 4. YAGNI 原则 (You Ain't Gonna Need It)
- **按需实现**: 只实现当前需要的功能
- **避免过度设计**: 不预先设计可能用不到的功能
- **渐进式开发**: 功能按需添加，不一次性完成所有功能

## 设计模式应用

### 1. 创建型模式

#### 1.1 单例模式 (Singleton Pattern)
```java
// P2PConfig 配置管理单例
public class P2PConfig {
    private static volatile P2PConfig instance;
    
    private P2PConfig() {
        // 私有构造函数
    }
    
    public static P2PConfig getInstance() {
        if (instance == null) {
            synchronized (P2PConfig.class) {
                if (instance == null) {
                    instance = new P2PConfig();
                    instance.loadConfig();
                }
            }
        }
        return instance;
    }
}
```

#### 1.2 工厂方法模式 (Factory Method Pattern)
```java
// 消息处理器工厂
public class HandlerFactory {
    public static P2PCommandHandler createHandler(P2PCommand command) {
        switch (command) {
            case GET_FILE:
                return new FileGetServerHandler();
            case PUT_FILE:
                return new FilePutServerHandler();
            case GET_COS_FILE:
                return new CosFileGetServerHandler();
            default:
                throw new IllegalArgumentException("未知命令类型");
        }
    }
}
```

#### 1.3 建造者模式 (Builder Pattern)
```java
// P2PWrapper 建造者
public class P2PWrapperBuilder {
    private int magic;
    private byte version;
    private P2PCommand command;
    private int length;
    private byte[] data;
    
    public P2PWrapperBuilder setMagic(int magic) {
        this.magic = magic;
        return this;
    }
    
    public P2PWrapperBuilder setCommand(P2PCommand command) {
        this.command = command;
        return this;
    }
    
    public P2PWrapperBuilder setData(byte[] data) {
        this.data = data;
        this.length = data.length;
        return this;
    }
    
    public P2PWrapper build() {
        return new P2PWrapper(magic, version, command, length, data);
    }
}
```

#### 1.4 对象池模式 (Object Pool Pattern)
```java
// ThreadLocal 对象池实现
public class P2PWrapperPool {
    private static final ThreadLocal<Stack<P2PWrapper>> pool = 
        ThreadLocal.withInitial(Stack::new);
    
    public static P2PWrapper acquire() {
        Stack<P2PWrapper> stack = pool.get();
        if (stack.isEmpty()) {
            return new P2PWrapper();
        }
        return stack.pop();
    }
    
    public static void release(P2PWrapper wrapper) {
        wrapper.clear(); // 重置状态
        pool.get().push(wrapper);
    }
}
```

### 2. 结构型模式

#### 2.1 适配器模式 (Adapter Pattern)
```java
// 不同传输协议的适配器
public class QuicMessageProcessorAdapter extends AbstractTcpMessageProcessor {
    private AbstractQuicMessageProcessor quicProcessor;
    
    @Override
    public void processMessage(ChannelHandlerContext ctx, P2PWrapper wrapper) {
        // 将TCP消息适配为QUIC处理
        QuicMessage quicMsg = adaptToQuicMessage(wrapper);
        quicProcessor.processQuicMessage(ctx, quicMsg);
    }
    
    private QuicMessage adaptToQuicMessage(P2PWrapper wrapper) {
        // 适配逻辑
        return new QuicMessage(wrapper.getCommand(), wrapper.getData());
    }
}
```

#### 2.2 装饰器模式 (Decorator Pattern)
```java
// 加密装饰器
public class EncryptedHandlerDecorator implements P2PCommandHandler {
    private final P2PCommandHandler delegate;
    private final EncryptionService encryptionService;
    
    public EncryptedHandlerDecorator(P2PCommandHandler delegate) {
        this.delegate = delegate;
        this.encryptionService = new AesEncryptionService();
    }
    
    @Override
    public P2PWrapper process(P2PWrapper request) {
        // 解密请求
        byte[] decryptedData = encryptionService.decrypt(request.getData());
        request.setData(decryptedData);
        
        // 处理请求
        P2PWrapper response = delegate.process(request);
        
        // 加密响应
        byte[] encryptedData = encryptionService.encrypt(response.getData());
        response.setData(encryptedData);
        
        return response;
    }
}
```

#### 2.3 代理模式 (Proxy Pattern)
```java
// 缓存代理
public class CachedFileHandlerProxy implements P2PFileService {
    private final P2PFileService realService;
    private final Cache<String, FileMetadata> metadataCache;
    
    public CachedFileHandlerProxy(P2PFileService realService) {
        this.realService = realService;
        this.metadataCache = CacheBuilder.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .build();
    }
    
    @Override
    public FileMetadata getFileInfo(String filePath) {
        // 先尝试从缓存获取
        FileMetadata cached = metadataCache.getIfPresent(filePath);
        if (cached != null) {
            return cached;
        }
        
        // 缓存未命中，调用真实服务
        FileMetadata metadata = realService.getFileInfo(filePath);
        
        // 放入缓存
        metadataCache.put(filePath, metadata);
        
        return metadata;
    }
}
```

#### 2.4 组合模式 (Composite Pattern)
```java
// 复合消息处理器
public class CompositeMessageHandler implements P2PCommandHandler {
    private final List<P2PCommandHandler> handlers = new ArrayList<>();
    
    public void addHandler(P2PCommandHandler handler) {
        handlers.add(handler);
    }
    
    @Override
    public P2PWrapper process(P2PWrapper request) {
        for (P2PCommandHandler handler : handlers) {
            try {
                return handler.process(request);
            } catch (UnsupportedOperationException e) {
                // 当前处理器不支持，尝试下一个
                continue;
            }
        }
        throw new UnsupportedOperationException("没有处理器支持该命令");
    }
}
```

### 3. 行为型模式

#### 3.1 策略模式 (Strategy Pattern)
```java
// 传输策略接口
public interface TransferStrategy {
    void transferFile(String source, String destination);
}

// 具体策略实现
public class TcpTransferStrategy implements TransferStrategy {
    @Override
    public void transferFile(String source, String destination) {
        // TCP传输实现
    }
}

public class UdpTransferStrategy implements TransferStrategy {
    @Override
    public void transferFile(String source, String destination) {
        // UDP传输实现
    }
}

// 策略上下文
public class FileTransferContext {
    private TransferStrategy strategy;
    
    public void setStrategy(TransferStrategy strategy) {
        this.strategy = strategy;
    }
    
    public void transfer(String source, String destination) {
        strategy.transferFile(source, destination);
    }
}
```

#### 3.2 观察者模式 (Observer Pattern)
```java
// 配置变更观察者
public interface ConfigChangeObserver {
    void onConfigChanged(P2PConfig newConfig);
}

// 可观察的配置管理器
public class ObservableP2PConfig extends P2PConfig {
    private final List<ConfigChangeObserver> observers = new ArrayList<>();
    
    public void addObserver(ConfigChangeObserver observer) {
        observers.add(observer);
    }
    
    @Override
    public void reloadConfig() {
        super.reloadConfig();
        notifyObservers();
    }
    
    private void notifyObservers() {
        for (ConfigChangeObserver observer : observers) {
            observer.onConfigChanged(this);
        }
    }
}
```

#### 3.3 命令模式 (Command Pattern)
```java
// 命令接口
public interface FileCommand {
    P2PWrapper execute();
    void undo();
}

// 具体命令
public class GetFileCommand implements FileCommand {
    private final FileGetServerHandler handler;
    private final P2PWrapper request;
    
    public GetFileCommand(FileGetServerHandler handler, P2PWrapper request) {
        this.handler = handler;
        this.request = request;
    }
    
    @Override
    public P2PWrapper execute() {
        return handler.process(request);
    }
    
    @Override
    public void undo() {
        // 文件下载操作的撤销逻辑
    }
}

// 命令调用者
public class CommandInvoker {
    private final Queue<FileCommand> commandQueue = new LinkedList<>();
    
    public void addCommand(FileCommand command) {
        commandQueue.add(command);
    }
    
    public P2PWrapper executeNext() {
        FileCommand command = commandQueue.poll();
        if (command != null) {
            return command.execute();
        }
        return null;
    }
}
```

#### 3.4 状态模式 (State Pattern)
```java
// 连接状态接口
public interface ConnectionState {
    void handleMessage(P2PWrapper message);
    void connect();
    void disconnect();
}

// 具体状态
public class ConnectedState implements ConnectionState {
    @Override
    public void handleMessage(P2PWrapper message) {
        // 已连接状态下的消息处理
    }
    
    @Override
    public void connect() {
        // 已经是连接状态，不需要操作
    }
    
    @Override
    public void disconnect() {
        // 断开连接逻辑
    }
}

public class DisconnectedState implements ConnectionState {
    @Override
    public void handleMessage(P2PWrapper message) {
        throw new IllegalStateException("未连接，无法处理消息");
    }
    
    @Override
    public void connect() {
        // 建立连接逻辑
    }
    
    @Override
    public void disconnect() {
        // 已经是断开状态，不需要操作
    }
}

// 状态上下文
public class ConnectionContext {
    private ConnectionState state;
    
    public ConnectionContext() {
        this.state = new DisconnectedState();
    }
    
    public void setState(ConnectionState state) {
        this.state = state;
    }
    
    public void handleMessage(P2PWrapper message) {
        state.handleMessage(message);
    }
}
```

#### 3.5 模板方法模式 (Template Method Pattern)
```java
// 抽象消息处理器模板
public abstract class AbstractMessageHandler {
    
    // 模板方法，定义处理流程
    public final P2PWrapper processMessage(P2PWrapper request) {
        // 1. 验证请求
        validateRequest(request);
        
        // 2. 预处理
        preProcess(request);
        
        // 3. 具体处理（由子类实现）
        P2PWrapper response = doProcess(request);
        
        // 4. 后处理
        postProcess(response);
        
        return response;
    }
    
    // 具体步骤实现
    protected void validateRequest(P2PWrapper request) {
        // 验证逻辑
    }
    
    protected void preProcess(P2PWrapper request) {
        // 预处理逻辑
    }
    
    // 抽象方法，子类必须实现
    protected abstract P2PWrapper doProcess(P2PWrapper request);
    
    protected void postProcess(P2PWrapper response) {
        // 后处理逻辑
    }
}
```

#### 3.6 访问者模式 (Visitor Pattern)
```java
// 消息访问者接口
public interface MessageVisitor {
    void visit(FileGetMessage message);
    void visit(FilePutMessage message);
    void visit(CosGetMessage message);
    void visit(HdfsGetMessage message);
}

// 具体访问者：消息统计
public class MessageStatisticsVisitor implements MessageVisitor {
    private int fileGetCount = 0;
    private int filePutCount = 0;
    private int cosGetCount = 0;
    private int hdfsGetCount = 0;
    
    @Override
    public void visit(FileGetMessage message) {
        fileGetCount++;
    }
    
    @Override
    public void visit(FilePutMessage message) {
        filePutCount++;
    }
    
    @Override
    public void visit(CosGetMessage message) {
        cosGetCount++;
    }
    
    @Override
    public void visit(HdfsGetMessage message) {
        hdfsGetCount++;
    }
    
    public void printStatistics() {
        System.out.println("文件下载次数: " + fileGetCount);
        System.out.println("文件上传次数: " + filePutCount);
        System.out.println("COS下载次数: " + cosGetCount);
        System.out.println("HDFS下载次数: " + hdfsGetCount);
    }
}
```

## 架构模式

### 1. 分层架构 (Layered Architecture)
```
表示层 (Presentation Layer): 客户端API和用户界面
业务层 (Business Layer): 消息处理器和业务逻辑
持久层 (Persistence Layer): 文件存储和云存储
基础设施层 (Infrastructure Layer): 网络、配置、工具
```

### 2. 事件驱动架构 (Event-Driven Architecture)
- **Netty事件循环**: 基于ChannelFuture的异步处理
- **消息总线**: 内部事件发布订阅机制
- **响应式编程**: CompletableFuture支持异步编排

### 3. 微内核架构 (Microkernel Architecture)
```
核心系统 (Core System): 消息路由、协议处理
插件模块 (Plug-in Modules): 文件处理器、云存储处理器
通信机制: 基于接口的插件通信
```

### 4. 管道-过滤器架构 (Pipe-Filter Architecture)
```
输入 → 解码过滤器 → 路由过滤器 → 业务过滤器 → 编码过滤器 → 输出
每个过滤器独立处理，通过管道连接
```

## 性能设计原则

### 1. 异步非阻塞设计
- **Netty NIO**: 基于事件驱动的异步网络模型
- **CompletableFuture**: 异步任务编排和结果处理
- **回调机制**: 避免线程阻塞，提高并发能力

### 2. 资源池化设计
- **连接池**: NettyChannelPool管理TCP连接复用
- **线程池**: ExecutorServicePool管理不同类型线程池
- **对象池**: ThreadLocal对象池减少GC压力

### 3. 缓存策略设计
- **LRU缓存**: 文件元数据缓存，减少磁盘访问
- **本地缓存**: 频繁访问数据的本地缓存
- **分布式缓存**: 集群部署时的分布式缓存支持

### 4. 零拷贝设计
- **FileChannel**: 使用NIO FileChannel实现零拷贝传输
- **DirectByteBuf**: 直接内存分配，减少堆内存拷贝
- **CompositeByteBuf**: 组合缓冲区，减少数据重组

## 安全设计原则

### 1. 最小权限原则
- **文件访问权限**: 严格控制文件读写权限
- **网络访问权限**: IP白名单和端口控制
- **操作权限**: 基于角色的访问控制

### 2. 纵深防御原则
- **网络层防御**: 防火墙和网络隔离
- **应用层防御**: 输入验证和输出编码
- **数据层防御**: 加密存储和传输

### 3. 安全默认原则
- **默认加密**: 所有传输默认使用SSL/TLS
- **默认认证**: 所有操作需要身份验证
- **默认审计**: 所有操作记录审计日志

## 可维护性设计原则

### 1. 模块化设计
- **高内聚**: 相关功能集中在同一模块
- **低耦合**: 模块间通过接口通信，依赖最小化
- **可替换**: 模块可独立替换和升级

### 2. 可测试性设计
- **依赖注入**: 便于模拟和测试
- **接口隔离**: 单元测试可针对接口进行
- **测试工具**: 提供测试工具和示例

### 3. 文档化设计
- **代码注释**: 详细的JavaDoc注释
- **架构文档**: 完整的架构设计文档
- **API文档**: 清晰的API使用文档

## 扩展性设计原则

### 1. 插件化设计
- **SPI机制**: 基于Java SPI的插件发现
- **热插拔**: 插件动态加载和卸载
- **配置驱动**: 通过配置文件启用插件

### 2. 协议扩展设计
- **版本兼容**: 支持多版本协议共存
- **命令扩展**: 可添加新的协议命令
- **编解码扩展**: 支持自定义编解码器

### 3. 存储扩展设计
- **存储接口**: 统一的存储操作接口
- **多存储支持**: 同时支持多种存储后端
- **数据迁移**: 存储间数据迁移工具

## 容错性设计原则

### 1. 故障隔离
- **线程池隔离**: 不同业务使用独立线程池
- **资源隔离**: 关键资源独立管理和监控
- **异常隔离**: 异常不影响其他功能

### 2. 优雅降级
- **功能降级**: 非核心功能可降级使用
- **性能降级**: 负载过高时降低性能保证可用
- **服务降级**: 依赖服务不可用时使用本地缓存

### 3. 自动恢复
- **健康检查**: 定期健康检查，发现问题自动重启
- **状态恢复**: 异常后自动恢复到正常状态
- **数据恢复**: 数据损坏时自动从备份恢复

## 监控设计原则

### 1. 可观测性
- **指标收集**: 系统指标和业务指标收集
- **日志聚合**: 结构化日志收集和分析
- **分布式追踪**: 请求链路追踪和性能分析

### 2. 预警机制
- **阈值告警**: 关键指标超过阈值自动告警
- **趋势预警**: 基于趋势分析提前预警
- **智能告警**: 机器学习优化告警策略

### 3. 可视化展示
- **仪表盘**: 关键指标可视化展示
- **报表系统**: 定期报表和统计分析
- **实时监控**: 实时数据流监控和分析

---

*本文档总结了p2p-net-storage项目中应用的设计原则、设计模式和架构模式，为项目的设计决策提供理论依据和实践指导。这些原则和模式的应用使得项目具有良好的可维护性、可扩展性、高性能和高可靠性。*
