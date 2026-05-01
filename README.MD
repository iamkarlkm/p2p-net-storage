#系统架构。
1. 系统概述 p2p-net-storage 是一个分布式的 P2P 架构文件服务系统。系统底层采用 NIO 框架 Netty，支持 TCP/UDP/QUIC 多协议通信；序列化层面使用高效的 Protostuff 进行二进制数据封装。系统设计了高度解耦的命令处理机制，并适配了多元化的底层存储引擎，可无缝对接本地磁盘、HDFS 大数据存储生态以及腾讯云对象存储 (COS)。

2. 核心技术栈

- 网络与通信: Netty (全异步事件驱动), netty-incubator-codec-native-quic (QUIC协议支持), Barchart UDT (基于UDP的可靠传输)
- 序列化与协议: Protostuff (主协议数据序列化), Protobuf, Fastjson
- 存储与云端生态: 腾讯云 COS_API , Hadoop HDFS
- 安全与加密: BouncyCastle (SSL/TLS及国密支持), RSA/MD5 校验
- 其他组件: Guava (缓存与并发), Commons IO (文件监控机制), HikariCP + H2 (内嵌数据库)
3. 整体架构分层

- 网络接入层 (Network Layer)
  - 基于 NioEventLoopGroup 的主从多线程 Reactor 模型（ P2PServer.java ）。
  - 自定义编解码 ：使用 P2PWrapperEncoder 与 P2PWrapperDecoder 对网络流进行半包/粘包处理及数据加解密。
- 协议路由层 (Protocol Layer)
  - 自定义报文结构 P2PWrapper ，包含消息序号(seq)、指令枚举 P2PCommand 以及具体的载荷数据(Payload)。
  - 动态处理器注册 ：在系统启动时， AbstractTcpMessageProcessor 会利用类加载器自动扫描 javax.net.p2p.server.handler 包下的所有处理器，并基于 P2PCommand 类型存入 HANDLER_REGISTRY_MAP ，实现 O(1) 的命令路由。
- 业务处理层 (Business Layer)
  - 采用策略模式（Strategy Pattern），针对每种指令实现单独的 P2PCommandHandler 。例如 CosFilePutServerHandler 、 FileGetServerHandler 等，保证了职责单一与极强的可扩展性。
- 存储适配层 (Storage Layer)
  - SharedStorage : 本地文件存储路径映射管理。
  - COS/HDFS 桥接 : 提供工具类（如 CosUtil ）处理云端上传下载分片机制。
- 客户端架构 (Client Architecture)
  - 实现了带有连接池 ( SimpleChannelPool ) 的高并发客户端 P2PClient 。
  - 提供同步堵塞（ sync ）和异步回调（ Future 、 ChannelAwaitOnMessage ）的多种请求范式，支持海量并发文件抓取。
  
#系统的全部业务功能均围绕核心指令集（ P2PCommand.java ）展开，主要划分为以下六大业务模块：

1. 基础网络与节点管理模块 维持 P2P 网络的拓扑状态与节点健康度。

- 握手与认证: HAND , LOGIN , LOGOUT ，节点接入时的权限验证与会话建立。
- 心跳保活: 客户端定期发送 HEART_PING ，服务端响应 HEART_PONG 维持长连接。
- 消息透传: 提供 ECHO 接口用于网络连通性测试及简单的字符串传递。
2. 本地文件存储与传输模块 提供高性能的局域网/广域网直连文件读写能力。

- 基础文件操作: 支持上传 ( PUT_FILE )、强制覆盖上传 ( FORCE_PUT_FILE )、下载 ( GET_FILE ) 与删除 ( REMOVE_FILE )。
- 大文件分片传输: 针对超过阈值的大文件，系统自动切割：获取分片 ( GET_FILE_SEGMENTS ) -> 写入分片 ( PUT_FILE_SEGMENTS ) -> 确认完成与重组校验 ( PUT_FILE_SEGMENTS_COMPLETE )。
- 流式/区间传输: 支持零拷贝级别的文件流传输 ( GET_FILE_STREAM , PUT_FILE_STREAM ) 及特定偏移量的区间读取 ( GET_FILE_REGION )。
- 文件校验: 基于 MD5 的数据完整性校验指令 ( CHECK_FILE ) 和元信息查询 ( INFO_FILE )。
3. 腾讯云 COS 云存储模块 深度集成腾讯云对象存储生态，实现数据的云端持久化。

- 基础云端操作: 上传文件到 COS ( PUT_COS_FILE )，从 COS 下载 ( GET_COS_FILE )，云端文件存在性检查 ( CHECK_COS_FILE )。
- 云端大文件分片: 适配 COS 官方的 Multipart 接口，支持分片拉取 ( GET_COS_FILE_SEGMENTS )、分片推送 ( PUT_COS_FILE_SEGMENTS ) 和分片合并 ( PUT_COS_FILE_SEGMENTS_COMPLETE )。
- 原生与桥接控制: 支持发送 COS 原生控制指令 ( COS_COMMAND )，以及将数据从 HDFS 提取后直接转存至 COS 的桥接业务 ( PUT_COS_FILE_FROM_HDFS )。
4. HDFS 分布式文件系统模块 对接大数据生态集群，适合海量图片归档存储。

- HDFS 读写: 完整的文件上传与下载 ( PUT_HDFS_FILE , GET_HDFS_FILE )。
- HDFS 块级别操作: 精确到 Hadoop Block 级别的读写指令 ( PUT_HDFS_BLOCK , GET_HDFS_BLOCK )。
- HDFS 系统指令: 文件信息查询 ( GET_HDFS_FILE_INFO ) 及目录级操作指令 ( HDFS_COMMAND )。
5. 特定业务：LSSJ (历史数据) 管理模块 此模块为针对特定环境（如车管所车辆图片）定制的历史数据回溯功能（ FileLssjCheckServerHandler.java ）。

- 图片检索降级策略: 当客户端发起校验或拉取指令 ( CHECK_LSSJ_FILE , GET_LSSJ_FILE ) 时，系统支持按优先级在多个本地硬盘目录（如 E:/VEH_IMAGES 和 d:/kmcgs_images ）及子级目录 ( fileName2 ) 中进行级联检索。
- 智能分发: 当命中的历史图片超出配置块大小 ( P2PConfig.DATA_BLOCK_SIZE ) 时，自动降级并转换为分片返回指令 ( R_OK_GET_FILE_SEGMENTS ) 以降低内存压力。
6. 文件实时同步与监控模块 (File Sync) 提供指定目录（如 D:\data\cms\sync ）的热插拔式数据同步能力（ FilesWatchService.java ）。

- 底层监控: 封装 Java NIO WatchService ，监听文件创建 ( ENTRY_CREATE )、修改 ( ENTRY_MODIFY ) 及删除事件。
- 防冲突机制: 在监听到修改事件后，采用独占式 FileLock 试探（ 尝试获取文件锁 ），防止在其他进程正在写入时触发脏同步。
- 智能过滤: 提供基于文件名前后缀黑白名单（ includeStarts , excludeEnds 等）的过滤机制，仅对有效变更抛给线程池 ( FILE_SYNC_POOLS ) 进行处理)。