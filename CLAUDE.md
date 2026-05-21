# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

P2P-Net-StorageSystem 是一个基于 Java 的分布式 P2P 通信和存储系统，采用 Maven 多模块构建。底层使用 Netty NIO 框架，支持 TCP/UDP/QUIC 多协议通信，Protostuff 二进制序列化。适配本地磁盘、HDFS、腾讯云 COS 三种存储后端。

## 常用命令

```bash
# 编译整个项目
mvn clean compile

# 打包（跳过测试）
mvn clean package -DskipTests

# 运行全部测试
mvn test

# 运行单个模块的测试
cd p2p-core && mvn test

# 运行单个测试类
mvn test -Dtest=AuthHandshakeModesTcpTest -pl p2p-core

# 安装到本地仓库
mvn clean install -DskipTests
```

## 模块依赖关系

```
p2p-core        ← 所有模块依赖的核心（Netty/协议/编解码/命令路由）
  ├── p2p-transfer  （主服务器入口 ImageFileServer）
  ├── p2p-sync      （文件实时同步 P2PSyncNodeMain）
  ├── p2p-db        （嵌入式存储引擎，依赖 p2p-core）
  ├── p2p-cos       （腾讯云 COS 适配）
  ├── p2p-hdfs      （HDFS 适配）
  ├── p2p-im        （即时通讯）
  └── p2p-cache     （分布式缓存）

p2p-ws-protocol   ← 协议规范（spec + protobuf）
p2p-ws-sdk-java   ← WebSocket SDK（Java）
p2p-ws-sdk-ts     ← WebSocket SDK（TypeScript）
p2p-ws-sdk-dart   ← WebSocket SDK（Dart）
p2p-ws-sdk-c      ← WebSocket SDK（C）
p2p-ws-sdk-python ← WebSocket SDK（Python）
```

## 核心架构

### 1. 网络层（Netty 主从 Reactor）

`p2p-core/src/main/java/javax/net/p2p/server/P2PServerTcp.java` 使用 `NioEventLoopGroup`（Boss + Worker）处理连接。`PipelineInitializer` 初始化 ChannelPipeline，顺序注入：`P2PWrapperDecoder` → 业务处理器 → `P2PWrapperEncoder`。

UDP 和 QUIC 分别由 `P2PServerUdp` 和 `P2PServerQuic` 提供，处理器基类为 `AbstractUdpMessageProcessor` / `AbstractQuicMessageProcessor`。

### 2. 协议路由层

所有业务命令定义在 `javax.net.p2p.api.P2PCommand` 枚举中（正数为请求，负数为错误响应）。

`ServerMessageProcessor`（继承 `AbstractTcpMessageProcessor`）在启动时通过类加载器自动扫描 `javax.net.p2p.server.handler` 包下所有实现 `P2PCommandHandler` 接口的类，按 `@P2PCommandHandler(command = ...)` 注解注册到 `HANDLER_REGISTRY_MAP`，实现 O(1) 路由。

新增命令步骤：
1. 在 `P2PCommand` 枚举中定义新命令值
2. 创建 `P2PCommandHandler` 实现类并标注命令映射
3. 若命令属于某服务类别，绑定 `P2PServiceCategory`

### 3. 强管控网络（默认拒绝）

系统按 "deny-by-default" 设计。任何新增能力必须满足：
- 新命令必须绑定 `P2PServiceCategory`，支持按类别统一启停
- 新命令必须有明确的 Protostuff/protobuf 载荷结构，禁止自由格式
- 新命令必须可被 Auth allowlist 管控
- 所有外部输入（路径/参数/二进制数据）必须校验；文件操作必须走沙箱工具方法
- 拒绝/失败必须返回明确错误码（`STD_ERROR` / `STD_UNKNOWN` / `INVALID_DATA`）

### 4. p2p-db 嵌入式存储引擎

`p2p-db` 是基于 Java NIO `MappedByteBuffer` 的持久化存储引擎，核心类：

- `DsObject`: 所有存储对象基类，管理 `MappedByteBuffer` 生命周期、文件 IO、锁
- `DsHashSet` / `DsHashMap`: 基于位图驱动的 256-ary trie 结构，slot payload 变长存储（2/4/8 字节），支持三段快速跳跃寻址（16/32/64 bit）
- `DsList`: 分层 DataLayer 设计，细粒度读写锁
- `DsMemoryRing`: 基于固定长度数组的环形缓冲区
- `DsMftNamespaceStore`: MFT（Master File Table）命名空间存储

所有存储文件头部包含 Magic Number，默认 Block 对齐 64KB。使用堆外内存，减少 GC 压力。

### 5. 文件同步模块（p2p-sync）

`P2PSyncNodeMain` 是同步节点入口：
- 启动 TCP 服务器（默认端口由 `P2PSyncConfig` 配置）
- 通过 `P2PDirectorySyncService` 监听目录变更（Java NIO `WatchService`）
- 通过 `MultiEndpointRpcSyncEventHandler` 将变更事件 RPC 推送到远端节点
- `P2PSyncMonitorServer` 提供 HTTP 监控端点

## 关键入口类

| 入口 | 模块 | 说明 |
|------|------|------|
| `ImageFileServer` | p2p-transfer | 主文件传输服务器（端口 6060） |
| `P2PSyncNodeMain` | p2p-sync | 文件同步节点 |
| `P2PServerWebSocketAuthDevMain` | p2p-core | WebSocket 开发服务器 |
| `DsDatabaseServer` | p2p-db | 数据库服务器入口 |

## 技术栈版本

- Java 17
- Netty 4.1.84.Final（含 netty-incubator-codec-native-quic）
- Protostuff 1.8.0
- BouncyCastle 1.67（SSL/TLS 及国密）
- H2 2.2.224 + jOOQ 3.17.8
- JUnit 4.12 / JUnit Jupiter 5.12.2

## 配置

- 主配置：`application.yml` / `application-dev.yml`
- P2P 配置：`p2p-config.yml`
- 认证配置：`auth.yaml`（或通过系统属性 `p2p.auth.inlineYaml` / `p2p.auth.yaml` 指定）
- 同步配置：`P2PSyncConfig` 从 YAML 加载

## 测试

测试分散在各模块的 `src/test/java` 中。核心模块测试覆盖：
- 认证握手（`AuthHandshakeModesTcpTest` / `UdpTest` / `QuicTest`）
- 编解码（`ProtostuffWireCompatTest`）
- RPC（`RpcCommandHandlersTest`）
- QUIC 可靠性（`QuicReliabilityTest`）
