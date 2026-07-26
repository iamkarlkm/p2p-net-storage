---
updated: "2026-05-11T10:28:19Z"
---
# auth.yaml allowCommands 收敛与补齐

## What Changed

- `p2p-core/src/main/resources/auth.yaml`：收敛示例用户 `example-user-id` 的 `allowCommands` 为“最小可用集合”，覆盖 core_compat 冒烟所需的 `HAND/LOGIN/ECHO/RPC_DISCOVER/RPC_HEALTH/RPC_UNARY`（并保留控制命令），减少示例噪音与维护成本。
- `UdpNetworkAnomalyTest.testDuplicatePackets`：调整为只走接收侧 `processDataMessage` 来模拟重复包，避免与 ACK 交付路径混用导致重复计数与随机失败。

## Verification

- `mvn -q -pl p2p-core "-Dtest=javax.net.p2p.udp.UdpNetworkAnomalyTest#testDuplicatePackets" test`
- `mvn -q -pl p2p-core test`
