# 控制平面（强管控网络）设计草案

目标：在严格管控的网络中实现节点入网、地址更新、拓扑发现。用户/节点静态注册信息由独立注册程序发放；本控制平面只做“在线信息/地址更新”。

## 1. 身份

- `pubkey_spki_der`：用户注册程序发放/维护（客户端持有私钥）
- `node_key = SHA256(pubkey_spki_der)`（32 bytes）
- `node_id64`：由独立注册程序分配（64bit 简约 ID），用于索引/展示

权威身份：`node_key`。任何入网/更新操作必须由私钥签名，center 用注册表中的公钥验签。

## 2. 管理节点（center）

center 为已知节点（多中心冗余）。提供：
- bootstrap：提供初始拓扑/种子列表
- presence：维护在线节点当前可达地址与能力信息（带 TTL）
- observe：返回连接层观察到的外网地址（NAT 场景）

## 3. 记录模型

### 3.1 UserRecord（静态）
由注册程序写入/管理：
- `node_id64`
- `node_key`
- `pubkey_spki_der`
- `allowed_crypto_modes`
- `status`：enabled/disabled/revoked（或等价的 `enabled: bool`）

### 3.2 PresenceRecord（动态）
由节点入网写入：
- `node_key`
- `reported_endpoints[]`：节点自报地址（内网/外网）
- `observed_public_endpoint`：center 观察地址（连接层）
- `capabilities`：max_frame_payload、协议版本、flags、ws_path 等
- `expires_at`
- `signature`：对动态字段签名

## 4. 入网（Join）语义

节点“注册/入网”仅更新 PresenceRecord：
- 对已注册用户（UserRecord 存在且 enabled）开放写入
- 必须验签；失败拒绝
- 使用 TTL + 周期续租
- 如注册表提供 `allowed_crypto_modes`，center 应对 `CenterHelloBody.crypto_mode` 做白名单校验

## 5. NAT 外网观察与打洞

最低成本做法：
- 节点入网时上报 reported_endpoints
- center 在响应里返回 `observed_endpoint`（建议返回观察到的公网 IP；端口由节点自报的 listen endpoint 决定）
- 节点将 observed IP 应用到 reported_endpoints（替换 host，保留 port），并再次入网更新

工程化建议：
- 客户端可将 observed 地址持久化到本地缓存（例如 `presence_cache_path` 指定的 JSON 文件），下次启动作为初始 `reported_endpoints` 一部分上报，减少一次往返。

可选：打洞协调
- 节点向 center 请求 `CONNECT_HINT(target)`
- center 同时通知 target `INCOMING_HINT(source_observed_endpoint, token)`
- 双方同时主动连出，提高成功率

## 6. 命令分配（建议）

控制平面仍走数据平面（p2p-ws）同一协议栈，使用负数 command：
- `[-99999..-10001]` 为保留段

建议 center 命令号段：
- `-11001`：CENTER_HELLO（入网/续租）
- `-11002`：CENTER_HELLO_ACK（返回 observed 地址、ttl、种子摘要）
- `-11010`：CENTER_GET_NODE（按 node_id64/node_key 查询）
- `-11011`：CENTER_GET_NODE_ACK
- `-11012`：CENTER_RELAY_DATA（中心转发 payload）
- `-11030`：CENTER_CONNECT_HINT（请求 center 协调一次“同时主动连出”）
- `-11031`：CENTER_INCOMING_HINT（center 通知目标：对 source 进行回连尝试）
- `-11020`：CENTER_LIST_SEEDS（拉取拓扑/种子）
- `-11021`：CENTER_LIST_SEEDS_ACK

消息体建议使用 protobuf 扩展 `p2p_control.proto` 定义。

## 7. 签名算法（建议）

为证明节点持有私钥，入网/更新类请求应携带签名：
- 签名算法：`SHA256withRSA`（PKCS#1 v1.5）
- 签名对象：对 `CenterHelloBody` 的 protobuf 序列化字节做签名
- center 校验：从注册表拿到 pubkey 或从请求携带 pubkey_spki_der 推导 pubkey，验签通过才写入 PresenceRecord
