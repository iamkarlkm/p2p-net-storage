# YAML 配置

协议与 demo 约定统一使用 YAML 配置文件提供运行参数。

## client.yaml

字段：
- `user_id`：用户/节点标识（字符串）。强管控网络下，建议对应注册系统的用户标识。
- `ws_url`：目标 WebSocket 地址，例如 `ws://ip:port/p2p`
- `keyfile_path`：离线 keyfile 路径（相对路径相对于 YAML 文件所在目录解析）
- `key_id_sha256_hex`：可选；期望的 keyfile sha256(hex)。设置后客户端必须校验本地文件是否匹配。
- `rsa_private_key_pem_path`：可选；客户端 RSA 私钥 pem 路径（PKCS8）。设置后客户端复用该私钥；未设置则临时生成 keypair。
- `crypto_mode`：可选；控制平面入网声明的加密/协商模式字符串（强管控网络可用于白名单校验）。
- `reported_endpoints`：可选；控制平面入网时自报 endpoints（对象数组：`{transport, addr}`）。
- `presence_cache_path`：可选；客户端本地缓存文件路径（JSON）。用于持久化保存 `observed_endpoint`，下次启动作为初始 `reported_endpoints` 一部分上报。
- `renew_seconds`：可选；控制平面续租周期（秒）。设置后客户端会定时发送 `CENTER_HELLO` 刷新 Presence TTL。
- `renew_count`：可选；续租次数（用于 demo/测试）。未设置时默认 3；为 0 表示不启用续租。
- `magic`：可选；u16。支持十六进制字符串（例如 `0x1234`）或整数。
- `version`：可选；u8。
- `flags_plain`：可选；明文帧 flags（握手阶段）。
- `flags_encrypted`：可选；加密/混淆帧 flags（握手后）。
- `max_frame_payload`：可选；默认 4194304（4MB，不含 8B 头）。

示例文件：`examples/client.yaml`

控制平面入网示例：`examples/center_client.yaml`
快速过期/续租示例：`examples/center_quick.yaml` + `examples/center_client_quick.yaml`

## server.yaml

字段：
- `listen_port`：监听端口
- `ws_path`：WebSocket path，例如 `/p2p`
- `keyfile_path`：离线 keyfile 路径（相对路径相对于 YAML 文件所在目录解析）
- `magic`/`version`/`flags_plain`/`flags_encrypted`/`max_frame_payload`：同 client
- `storage_locations`：可选；共享文件空间映射（`store_id -> dir`），用于 `GET_FILE/PUT_FILE/...` 等文件命令
- `im_storage_locations`：可选；IM 文件交换共享空间映射（`store_id -> dir`），用于 `IMChatModel.file_info` 相关落盘/读取

示例文件：`examples/server.yaml`

## peer.yaml（peer 节点）

peer 节点通常同时具备：
- 作为 client 接入 center 的能力（`ws_url`）
- 作为 server 提供本地 WS 服务的能力（`listen_port`）

字段：在 `client.yaml` 的基础上增加：
- `listen_port`：本机 peer server 监听端口
- `storage_locations`：共享文件空间映射（`store_id -> dir`），用于 `GET_FILE/PUT_FILE/...` 等文件命令
- `im_storage_locations`：IM 文件交换共享空间映射（`store_id -> dir`）

### IM 预定义共享空间（3 个）

IM 文件交换统一使用 3 个预定义空间：
- `public`：公共共享空间
- `group`：群共享空间（实际落盘路径会自动附加 `group_id` 子目录）
- `private`：用户私有空间（实际落盘路径会自动附加 `user_id` 子目录）

这些空间在 `FileDataModel.store_id` 上使用“负数语义”的预留编号：
- `-1`：public
- `-2`：group
- `-3`：private

注意：协议里 `store_id` 是 `uint32`。实现上会把 `-1/-2/-3` 按 32-bit 无符号解释编码到 wire（例如 `-1` 对应 `4294967295`），因此：
- YAML 中推荐直接写 `-1/-2/-3`
- 程序内部需要按 `uint32` 处理/比较（避免语言差异导致的负数编码问题）

## center.yaml / registered_users.yaml

`center.yaml`：管理节点配置
- `registered_users_path`：注册用户清单（来自注册程序的静态信息）
- `ttl_seconds`：Presence 续租 TTL

`registered_users.yaml`：注册用户清单（示例）
- `users[].node_id64`
- `users[].node_key_hex`：`SHA256(pubkey_spki_der)`（32B hex）
- `users[].pubkey_spki_der_base64`

示例文件：
- `examples/center.yaml`
- `examples/registered_users.yaml`
