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
