# p2p-ws-sdk-python

Python SDK 骨架（codec/xor），对齐 `p2p-ws-protocol/spec.md`。

提供：
- `p2p_ws_sdk.frame`：8B 头解析/编码
- `p2p_ws_sdk.xor`：XOR 向量实现（no-wrap）
- `p2p_ws_sdk.handshake`：RSA-OAEP(SHA-256) 解密/加密（需要 cryptography）
- `p2p_ws_sdk.wrapper`：P2PWrapper protobuf 编解码（需要先生成 pb2）
- `scripts/verify_vectors.py`：校验 `p2p-ws-protocol/test-vectors`

生成 pb2：

```bash
python -m pip install grpcio-tools
python p2p-ws-sdk-python/scripts/gen_proto.py
python p2p-ws-sdk-python/scripts/verify_wrapper.py
```

Demo（真实 keyfile + YAML 配置）：

```bash
python -m pip install websockets cryptography PyYAML grpcio-tools
python p2p-ws-sdk-python/scripts/gen_proto.py
python p2p-ws-sdk-python/demo/echo_client.py p2p-ws-protocol/examples/client.yaml
```

Center Demo（强管控网络入网/查询，Python node client）：

```bash
python -m pip install websockets cryptography PyYAML grpcio-tools
python p2p-ws-sdk-python/scripts/gen_proto.py
python p2p-ws-sdk-python/demo/center_join.py p2p-ws-protocol/examples/center_client.yaml
```

`center_client.yaml` 可选字段 `presence_cache_path` 会在收到 `observed_endpoint` 后写入本地 JSON 缓存，供下次启动作为初始 endpoints 上报。

快速 TTL/续租/过期演示：

```bash
python p2p-ws-sdk-python/demo/center_join.py p2p-ws-protocol/examples/center_client_quick.yaml
```
