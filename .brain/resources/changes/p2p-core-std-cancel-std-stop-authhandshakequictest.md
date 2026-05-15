# p2p-core：Auth 放行 STD_CANCEL/STD_STOP + 测试回归

## 背景

`STD_CANCEL/STD_STOP` 在服务端处理链路中属于“控制类命令”（取消/停止当前 seq 的 long/stream 任务）。若它们被 auth allowCommands 拦截，会导致客户端无法取消流或停止长任务，产生难以定位的“取消无效”问题。

## 变更

- AuthEnforcer 默认放行 `STD_CANCEL/STD_STOP`（不依赖 allowCommands）
  - 相关文件：`p2p-core/src/main/java/javax/net/p2p/auth/AuthEnforcer.java`
- 示例配置补齐 allowCommands
  - 相关文件：`p2p-core/src/main/resources/auth.yaml`
- 回归测试：`AuthHandshakeQuicTest` 增加 cancel 在未登录阶段可达（期望 TASK_NOT_FOUND，而非 AUTH_LOGIN_REQUIRED/权限拒绝）
  - 相关文件：`p2p-core/src/test/java/javax/net/p2p/auth/AuthHandshakeQuicTest.java`
- 测试稳定性：并发可靠性测试避免 `seq=0` 触发自动分配导致 seq 碰撞，并使用线程池替代海量 Thread
  - 相关文件：`p2p-core/src/test/java/javax/net/p2p/websocket/WebSocketReliabilityTest.java`
  - 相关文件：`p2p-core/src/test/java/javax/net/p2p/quic/QuicReliabilityTest.java`

## 验证

- `mvn -q -pl p2p-core test`

## Verification for p2p-core: 放行 STD_CANCEL/STD_STOP 并回归 AuthHandshakeQuicTest

- `mvn -q -pl p2p-core test`
