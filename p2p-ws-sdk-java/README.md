# p2p-ws-sdk-java

Java 参考实现（codec/xor/handshake），对齐 `p2p-ws-protocol/spec.md`。

输入：
- `../p2p-ws-protocol/proto/*.proto`
- `../p2p-ws-protocol/test-vectors/*`

Demo（真实 keyfile + YAML 配置）：

```bash
python ..\\p2p-ws-protocol\\scripts\\gen_keyfile.py ..\\p2p-ws-protocol\\keyfiles\\demo.key 8388608
mvn -DskipTests package exec:java "-Dexec.mainClass=p2pws.sdk.demo.WsServerMain" "-Dexec.args=..\\p2p-ws-protocol\\examples\\server.yaml"
```

Center Demo（强管控网络入网/查询，Java center server）：

```bash
mvn -DskipTests package exec:java "-Dexec.mainClass=p2pws.sdk.center.CenterServerMain" "-Dexec.args=..\\p2p-ws-protocol\\examples\\center.yaml"
```

## core_compat（直连 p2p-core wire-format）

启动本地 p2p-core WebSocket 服务端（自动生成临时 auth 配置与密钥，并打印连接参数）：

```bash
mvn -q -pl p2p-core -DskipTests "-Dexec.mainClass=javax.net.p2p.server.P2PServerWebSocketAuthDevMain" "-Dexec.args=18089 -252702961" org.codehaus.mojo:exec-maven-plugin:3.5.0:java
```

另起终端运行 Java core_compat 客户端 demo（参数：wsUrl magic privKeyPath userId message）：

```bash
mvn -q -f p2p-ws-sdk-java/pom.xml -DskipTests "-Dexec.mainClass=p2pws.sdk.demo.CoreCompatWsClientMain" "-Dexec.args=ws://127.0.0.1:18089/p2p -252702961 <CLIENT_PRIVATE_KEY_PATH> <USER_ID> hello" org.codehaus.mojo:exec-maven-plugin:3.5.0:java
```

PubSub demo（subscribe + publish + cancel 后不再收到事件；参数：wsUrl magic privKeyPath userId topic message）：

```bash
mvn -q -f p2p-ws-sdk-java/pom.xml -DskipTests "-Dexec.mainClass=p2pws.sdk.demo.CoreCompatPubSubMain" "-Dexec.args=ws://127.0.0.1:18089/p2p -252702961 <CLIENT_PRIVATE_KEY_PATH> <USER_ID> demo-topic hello" org.codehaus.mojo:exec-maven-plugin:3.5.0:java
```
