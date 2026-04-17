# p2p-ws-sdk-ts

TypeScript SDK 骨架（codec/xor/proto loader），对齐 `p2p-ws-protocol/spec.md`。

Demo（真实 keyfile + YAML 配置）：

```bash
npm install
npm run demo-echo -- ..\\p2p-ws-protocol\\examples\\client.yaml
```

Center Demo（强管控网络入网/查询）：

```bash
npm run demo-center-join -- ..\\p2p-ws-protocol\\examples\\center_client.yaml
```

`center_client.yaml` 可选字段 `presence_cache_path` 会在收到 `observed_endpoint` 后写入本地 JSON 缓存，供下次启动作为初始 endpoints 上报。

快速 TTL/续租/过期演示：

```bash
npm run demo-center-join -- ..\\p2p-ws-protocol\\examples\\center_client_quick.yaml
```
