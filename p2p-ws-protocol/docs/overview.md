# 概览

本仓库定义并实现了一个“强管控”的 P2P 网络通信层与 SDK：

- 数据平面：节点之间点对点通信（WebSocket + Binary Frame + P2PWrapper + XOR keyfile 混淆）
- 控制平面：节点入网、地址更新、拓扑拉取（多中心管理节点，已注册用户才能写入）

## 数据平面关键点

- Wire Header（8B 明文，big-endian）：`length:u32 + magic:u16 + version:u8 + flags:u8`
- Payload：protobuf `P2PWrapper(seq:int32, command:int32, data:bytes, headers:map)`
- 混淆：离线 keyfile；`keyId = SHA256(keyfile)`；offset 由服务端握手随机下发且固定；每包从 0 开始 `keyfile[offset+i] XOR payload`
- 控制命令号段（保留）：`[-99999..-10001]`

## 控制平面关键点（强管控网络）

- 身份根：`node_key = SHA256(pubkey_spki_der)`（32B）
- nodeId64：由独立用户注册程序分配（短 ID，仅用于索引/展示）
- “入网”仅更新动态信息：当前可达地址、能力信息；静态注册信息由注册程序维护
- 多中心管理节点（center）是已知的：节点启动时从配置文件读取 center 列表获取拓扑，并注册在线信息

### 默认拒绝（Deny-by-default）

系统在实现层面应对以下情况严格拒绝（返回错误或断开连接），并确保不会进入业务 handler：

- 未知用户：未握手/未登录/未授权的命令请求
- 未知节点：node_key 不匹配、未知 center 来源、未注册的 nodeId64 或 node_key
- 未知服务：命令所属服务类别未启用或未加载（service unavailable）
- 未知命令/未知数据格式：命令未注册、payload 无法解析/不符合协议模型（invalid/unknown）

## Demo

- P2P Echo：Java server + TS/Python client，使用 `examples/server.yaml` 与 `examples/client.yaml`
- Center Join：Java center server + TS client，使用 `examples/center.yaml`、`examples/center_client.yaml`、`examples/registered_users.yaml`
