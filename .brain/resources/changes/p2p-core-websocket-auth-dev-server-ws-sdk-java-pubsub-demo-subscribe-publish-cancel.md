---
title: p2p-core WebSocket dev server + ws-sdk-java PubSub demo 验证
updated: "2026-05-12T12:23:46Z"
---
## Verification for 端到端验证：p2p-core WebSocket auth dev server + ws-sdk-java PubSub demo（subscribe/publish/cancel）

### Build & Tests

- `mvn -q -f p2p-ws-sdk-java/pom.xml test`
- `mvn -q -DskipTests package`

### End-to-End PubSub Smoke

1) 启动 WebSocket dev server（会在控制台打印 WS_URL / MAGIC / USER_ID / CLIENT_PRIVATE_KEY_PATH）：

- `mvn -q -pl p2p-core -DskipTests "-Dexec.mainClass=javax.net.p2p.server.P2PServerWebSocketAuthDevMain" "-Dexec.args=18094 -252702961" org.codehaus.mojo:exec-maven-plugin:3.5.0:java`

2) 运行 PubSub demo（subscribe → publish → receive → cancel）：

- `mvn -q -f p2p-ws-sdk-java/pom.xml -DskipTests "-Dexec.mainClass=p2pws.sdk.demo.CoreCompatPubSubMain" "-Dexec.args=ws://127.0.0.1:18094/p2p -252702961 <CLIENT_PRIVATE_KEY_PATH> <USER_ID> demo-topic hello" org.codehaus.mojo:exec-maven-plugin:3.5.0:java`
