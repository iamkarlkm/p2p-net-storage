## p2p-core UDP 分片 v2

- What: 为 UDP 大包引入分片 v2（datagram 自带 offset/index/count/seq），接收侧可乱序重组并做 hash 校验。
- Toggle: `p2p.udp.segment.v2.enabled`（默认 true，仅对超过 UDP 限制的大包生效）。
- Files: `UdpFrameInbound`, `AbstractUdpMessageProcessor`, `ClientSendUdpMesageExecutor`, `ServerSendUdpMesageExecutor`

## Verification

- `mvn -pl p2p-core "-Dtest=javax.net.p2p.auth.AuthHandshakeModesTcpTest,javax.net.p2p.auth.AuthHandshakeModesQuicTest,javax.net.p2p.auth.AuthHandshakeModesUdpTest" test`

