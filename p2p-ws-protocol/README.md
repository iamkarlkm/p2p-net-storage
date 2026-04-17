# p2p-ws-protocol

跨语言 P2P WebSocket 通信层协议定义（spec + protobuf + test vectors）。

目录：
- spec.md：协议规范（线协议、握手、加密/混淆、命令号段）
- proto/：protobuf 定义
- test-vectors/：跨语言一致性测试向量
- docs/：设计与配置文档（强管控网络控制平面、YAML 配置等）

示例：
- examples/server.yaml
- examples/client.yaml
- examples/center.yaml
- examples/center_quick.yaml
- examples/center_client.yaml
- examples/center_client_quick.yaml
- examples/registered_users.yaml

生成离线 keyfile：

```bash
python p2p-ws-protocol/scripts/gen_keyfile.py p2p-ws-protocol/keyfiles/demo.key 8388608
```

生成 RSA 私钥（客户端复用）：

```bash
python -m pip install cryptography
python p2p-ws-protocol/scripts/gen_rsa_private_key.py p2p-ws-protocol/keys/demo_rsa_private.pem 2048
```

从 RSA 私钥导出 node_key 与 spki（用于注册系统/center 注册表）：

```bash
python p2p-ws-protocol/scripts/derive_node_key.py p2p-ws-protocol/keys/node1.pem
```

文档：
- docs/overview.md
- docs/config.md
- docs/control-plane.md
