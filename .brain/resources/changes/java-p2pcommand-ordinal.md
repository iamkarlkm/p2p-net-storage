---
updated: "2026-05-11T06:49:19Z"
---
# Java P2PCommand ordinal 与 core_compat 验证

## What Changed

- 生成跨语言一致的 `P2PCommand` ordinal 映射，并在 Java 侧落地用于 core_compat 直连（避免手写 ordinal 漂移）。
- Java core_compat 与 p2p-core wire-format（`length+magic+payload`）对齐：WebSocket 直连、payload XOR、HAND/LOGIN、以及最小 RPC（discover/health/unary echo）端到端可跑通。
- 兼容性修复：从“手写 protostuff-like 编码”切换为 `protostuff-runtime`（RuntimeSchema）以匹配 p2p-core 实际序列化，避免服务端解码报 `Unknown field number: 0`。
- 新增 p2p-core WebSocket+auth 开发启动入口，便于本地快速启动并打印 demo 所需参数（wsUrl/magic/userId/客户端私钥路径）。

## Verification

- `python tools/gen_p2p_command_ordinals.py`
- `mvn -f p2p-ws-sdk-java/pom.xml -DskipTests protobuf:compile`
- `mvn -q -f p2p-ws-sdk-java/pom.xml clean test`
- `mvn -q -f p2p-ws-sdk-java/pom.xml -DskipTests -Dexec.mainClass=p2pws.sdk.demo.CoreCompatWsClientMain -Dexec.args="ws://127.0.0.1:18089/p2p -252702961 I:/2025/code/P2P-Net-StorageSystem/p2p-net-storage/target/ws-auth-keys-18089/client-private.key d559c2254fbbf739fad7c6549f630c1b57fbbbde917fa8eeb7ecd27aa6d43524 hello" org.codehaus.mojo:exec-maven-plugin:3.5.0:java`
- `mvn -q -pl p2p-core test`
