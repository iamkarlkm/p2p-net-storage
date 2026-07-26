## Verification for p2p-core QUIC/TCP/UDP 握手支持 4 种 cryptoMode

- `mvn -pl p2p-core "-Dtest=javax.net.p2p.auth.AuthHandshakeModesTcpTest" test`
- `mvn -pl p2p-core "-Dtest=javax.net.p2p.auth.AuthHandshakeModesQuicTest,javax.net.p2p.auth.AuthHandshakeModesTcpTest,javax.net.p2p.auth.AuthHandshakeModesUdpTest" test`
- `mvn -pl p2p-core "-Dtest=javax.net.p2p.auth.AuthHandshakeModesUdpTest" test`
- `mvn -pl p2p-core test-compile`

