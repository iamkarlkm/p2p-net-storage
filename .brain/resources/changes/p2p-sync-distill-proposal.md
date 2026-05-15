---
created: "2026-05-15T06:56:58Z"
distill_scope: session
promotion_categories:
    - boundary_fact
    - verification_recipe
proposed_targets:
    - .brain/context/current-state.md
    - .brain/resources/changes/p2p-sync.md
source_session_id: "1778827356066683200"
source_task: p2p-sync 写冲突后写失败
title: p2p-sync 写冲突后写失败 Distill Proposal
type: distill_proposal
updated: "2026-05-15T06:56:58Z"
---
# p2p-sync 写冲突后写失败 Distill Proposal

## Source Provenance

- Mode: `session`
- Session: `1778827356066683200`
- Task: p2p-sync 写冲突后写失败
- Git baseline: `a37a375af50bffb86207b90e1d2772672b303e89`

### Commands Run

- `mvn -pl p2p-sync test` (exit 0)

### Git Diff

- `"p2p-ws-protocol//346/226/260/346/226/207/344/273/266 5.txt"`
- `"p2p-ws-protocol//346/226/260/346/226/207/344/273/266 6.txt"`
- `"p2p-ws-protocol//346/226/260/346/226/207/344/273/266 7.txt"`
- `"p2p-ws-protocol//346/226/260/346/226/207/344/273/266 8.txt"`
- `.brain/context/current-state.md`
- `.brain/resources/changes/auth-rbac-roles-categories.md`
- `.brain/resources/changes/auth-yaml-allowcommands-rpc.md`
- `.brain/resources/changes/c-demos-crypto-mode-client-random-server-random-plain-distill-proposal.md`
- `.brain/resources/changes/c-demos-crypto-mode-client-random-server-random-plain.md`
- `.brain/resources/changes/dsdb-count-db-row-count-distill-proposal.md`
- `.brain/resources/changes/dsdb-count-db-row-count.md`
- `.brain/resources/changes/dsdb-eq-distill-proposal.md`
- `.brain/resources/changes/dsdb-excute-seq-cancelexcute-distill-proposal.md`
- `.brain/resources/changes/dsdb-exists-db-row-exists-by-query-distill-proposal.md`
- `.brain/resources/changes/dsdb-exists-db-row-exists-by-query.md`
- `.brain/resources/changes/dsdb-handle-cancel-cancelexcute.md`
- `.brain/resources/changes/dsdb-in-union-distill-proposal.md`
- `.brain/resources/changes/dsdb-in-union.md`
- `.brain/resources/changes/dsdb-not-in-distill-proposal.md`
- `.brain/resources/changes/dsdb-not-in.md`
- `.brain/resources/changes/dsdb-or-dnf-and-or-distill-proposal.md`
- `.brain/resources/changes/dsdb-or-dnf-and-or.md`
- `.brain/resources/changes/dsdb-rowid-db-row-query-ids-stream-distill-proposal.md`
- `.brain/resources/changes/dsdb-rowid-db-row-query-ids-stream.md`
- `.brain/resources/changes/dsdb-std-cancel-distill-proposal.md`
- `.brain/resources/changes/dsdb-std-cancel.md`
- `.brain/resources/changes/dsdb-stream-orderby-topk-distill-proposal.md`
- `.brain/resources/changes/dsdb-stream-orderby-topk.md`
- `.brain/resources/changes/dsdb.md`
- `.brain/resources/changes/java-p2pcommand-ordinal.md`
- `.brain/resources/changes/no-cert-chain-guard.md`
- `.brain/resources/changes/p2p-core-quic-tcp-udp-4-cryptomode-distill-proposal.md`
- `.brain/resources/changes/p2p-core-quic-tcp-udp-4-cryptomode.md`
- `.brain/resources/changes/p2p-core-std-cancel-std-stop-authhandshakequictest-distill-proposal.md`
- `.brain/resources/changes/p2p-core-std-cancel-std-stop-authhandshakequictest.md`
- `.brain/resources/changes/p2p-core-udp-segment-v2.md`
- `.brain/resources/changes/p2p-core-udp-v2-distill-proposal.md`
- `.brain/resources/changes/p2p-core-websocket-auth-dev-server-ws-sdk-java-pubsub-demo-subscribe-publish-cancel.md`
- `.brain/resources/changes/p2p-sync-distill-proposal.md`
- `.brain/resources/changes/p2p-sync.md`
- `.brain/resources/changes/p2p-ws-sdk-c-core-compat-p2p-core-wire-format.md`
- `.brain/resources/changes/p2p-ws-sdk-dart-core-compat-api-distill-proposal.md`
- `.brain/resources/changes/p2p-ws-sdk-dart-core-compat-p2p-core-wire-format-distill-proposal.md`
- `.brain/resources/changes/p2p-ws-sdk-dart-core-compat-p2p-core-wire-format.md`
- `.brain/resources/changes/p2p-ws-sdk-dart-python-c-network-mode-server-group-ws-urls-distill-proposal.md`
- `.brain/resources/changes/p2p-ws-sdk-dart-python-c-network-mode-server-group-ws-urls.md`
- `.brain/resources/changes/p2p-ws-sdk-server-group.md`
- `.brain/resources/changes/p2p-ws-sdk-ts-core-compat-p2p-core-wire-format.md`
- `.brain/resources/changes/p2p-ws-sdk-ts-network-mode-servergroupclient.md`
- `.brain/resources/changes/p2p-ws-sdk-ws-urls.md`
- `.brain/resources/changes/p2pcommand-ordinal-p2p-core-wire-format-python.md`
- `.brain/resources/changes/rpc-client-stream-error-exception-context.md`
- `.brain/resources/changes/rpc-completeness-request-chunking-and-error-context.md`
- `.brain/resources/changes/rpc-distill-proposal.md`
- `.brain/resources/changes/rpc-p2p-core-p2p-ws-sdk-java-distill-proposal.md`
- `.brain/resources/changes/rpc-server-stream-error-frame-consistency.md`
- `.brain/resources/changes/rpc-ws-sdk-java-rpc-stream-rpc-event-distill-proposal.md`
- `.brain/resources/changes/rpc.md`
- `.brain/resources/changes/ws-sdk-java-cancel-close-rpc-event-rpc-stream-distill-proposal.md`
- `.brain/resources/changes/ws-sdk-java-rpc-event-pubsub-subscribe-p2p-core-distill-proposal.md`
- `.brain/resources/changes/ws-sdk-java-rpc-event-pubsub-subscribe-p2p-core.md`
- `.brain/resources/changes/ws-sdk-java-rpc-stream-server-stream.md`
- `.brain/resources/changes/ws-sdk-java-stream-error-context.md`
- `p2p-cache/src/main/java/javax/net/p2p/cache/server/CacheBackend.java`
- `p2p-core/pom.xml`
- `p2p-core/src/main/java/javax/net/p2p/api/P2PCommand.java`
- `p2p-core/src/main/java/javax/net/p2p/auth/AuthClientPublicKeyResolver.java`
- `p2p-core/src/main/java/javax/net/p2p/auth/AuthEnforcer.java`
- `p2p-core/src/main/java/javax/net/p2p/auth/config/AuthConfig.java`
- `p2p-core/src/main/java/javax/net/p2p/auth/model/HandshakeCryptoMode.java`
- `p2p-core/src/main/java/javax/net/p2p/auth/model/HandshakeRequest.java`
- `p2p-core/src/main/java/javax/net/p2p/auth/model/HandshakeResponse.java`
- `p2p-core/src/main/java/javax/net/p2p/auth/utils/AuthCrypto.java`
- `p2p-core/src/main/java/javax/net/p2p/auth/utils/HandshakePayloads.java`
- `p2p-core/src/main/java/javax/net/p2p/channel/AbstractStreamRequestAdapter.java`
- `p2p-core/src/main/java/javax/net/p2p/channel/AbstractTcpMessageProcessor.java`
- `p2p-core/src/main/java/javax/net/p2p/channel/AbstractUdpMessageProcessor.java`
- `p2p-core/src/main/java/javax/net/p2p/channel/ChannelUtils.java`
- `p2p-core/src/main/java/javax/net/p2p/client/AbstractP2PClient.java`
- `p2p-core/src/main/java/javax/net/p2p/client/ClientSendTcpMesageExecutor.java`
- `p2p-core/src/main/java/javax/net/p2p/client/ClientSendUdpMesageExecutor.java`
- `p2p-core/src/main/java/javax/net/p2p/codec/P2PWrapperSecureDecoder.java`
- `p2p-core/src/main/java/javax/net/p2p/codec/P2PWrapperSecureEncoder.java`
- `p2p-core/src/main/java/javax/net/p2p/common/AbstractP2PMessageServiceAdapter.java`
- `p2p-core/src/main/java/javax/net/p2p/common/UdpFrameInbound.java`
- `p2p-core/src/main/java/javax/net/p2p/model/DbQuery.java`
- `p2p-core/src/main/java/javax/net/p2p/model/DbQueryOrGroup.java`
- `p2p-core/src/main/java/javax/net/p2p/model/DbRowCountRequest.java`
- `p2p-core/src/main/java/javax/net/p2p/model/DbRowCountResponse.java`
- `p2p-core/src/main/java/javax/net/p2p/model/DbRowExistsByQueryRequest.java`
- `p2p-core/src/main/java/javax/net/p2p/model/DbRowExistsByQueryResponse.java`
- `p2p-core/src/main/java/javax/net/p2p/model/DbRowQueryIdsStreamRequest.java`
- `p2p-core/src/main/java/javax/net/p2p/model/StreamP2PWrapper.java`
- `p2p-core/src/main/java/javax/net/p2p/rpc/client/P2PRpcClient.java`
- `p2p-core/src/main/java/javax/net/p2p/rpc/server/RpcDispatcher.java`
- `p2p-core/src/main/java/javax/net/p2p/rpc/server/RpcPubSubBroker.java`
- `p2p-core/src/main/java/javax/net/p2p/rpc/server/RpcPubSubServices.java`
- `p2p-core/src/main/java/javax/net/p2p/rpc/server/RpcServerStreamHandler.java`
- `p2p-core/src/main/java/javax/net/p2p/rpc/server/SyncRpcServices.java`
- `p2p-core/src/main/java/javax/net/p2p/server/P2PServerQuic.java`
- `p2p-core/src/main/java/javax/net/p2p/server/P2PServerTcp.java`
- `p2p-core/src/main/java/javax/net/p2p/server/P2PServerUdp.java`
- `p2p-core/src/main/java/javax/net/p2p/server/P2PServerWebSocket.java`
- `p2p-core/src/main/java/javax/net/p2p/server/P2PServerWebSocketAuthDevMain.java`
- `p2p-core/src/main/java/javax/net/p2p/server/ServerSendUdpMesageExecutor.java`
- `p2p-core/src/main/java/javax/net/p2p/server/ServerUdpMessageProcessor.java`
- `p2p-core/src/main/java/javax/net/p2p/server/handler/HandServerHandler.java`
- `p2p-core/src/main/java/javax/net/p2p/server/handler/LoginServerHandler.java`
- `p2p-core/src/main/java/javax/net/p2p/server/handler/RpcEventCommandServerHandler.java`
- `p2p-core/src/main/java/javax/net/p2p/server/handler/RpcStreamCommandServerHandler.java`
- `p2p-core/src/main/java/javax/net/p2p/startup/`
- `p2p-core/src/main/java/javax/net/p2p/utils/DateUtil.java`
- `p2p-core/src/main/proto/p2p_rpc_sync.proto`
- `p2p-core/src/main/resources/SystemConfig.yaml`
- `p2p-core/src/main/resources/auth.yaml`
- `p2p-core/src/test/java/javax/net/p2p/auth/AuthClientPublicKeyResolverTest.java`
- `p2p-core/src/test/java/javax/net/p2p/auth/AuthEnforcerRoleCategoryTest.java`
- `p2p-core/src/test/java/javax/net/p2p/auth/AuthHandshakeModesQuicTest.java`
- `p2p-core/src/test/java/javax/net/p2p/auth/AuthHandshakeModesTcpTest.java`
- `p2p-core/src/test/java/javax/net/p2p/auth/AuthHandshakeModesUdpTest.java`
- `p2p-core/src/test/java/javax/net/p2p/auth/AuthHandshakeQuicTest.java`
- `p2p-core/src/test/java/javax/net/p2p/codec/`
- `p2p-core/src/test/java/javax/net/p2p/common/`
- `p2p-core/src/test/java/javax/net/p2p/model/`
- `p2p-core/src/test/java/javax/net/p2p/quic/QuicReliabilityTest.java`
- `p2p-core/src/test/java/javax/net/p2p/rpc/RpcCommandHandlersTest.java`
- `p2p-core/src/test/java/javax/net/p2p/security/`
- `p2p-core/src/test/java/javax/net/p2p/server/ServerQuicMessageProcessorTest.java`
- `p2p-core/src/test/java/javax/net/p2p/startup/`
- `p2p-core/src/test/java/javax/net/p2p/udp/UdpNetworkAnomalyTest.java`
- `p2p-core/src/test/java/javax/net/p2p/websocket/WebSocketReliabilityTest.java`
- `p2p-db/nb-configuration.xml`
- `p2p-db/pom.xml`
- `p2p-db/src/main/java/AccessModuleUtil.java`
- `p2p-db/src/main/java/NewClass.java`
- `p2p-db/src/main/java/Wisp2.java`
- `p2p-db/src/main/java/com/q3lives/ds/core/DsObject.java`
- `p2p-db/src/main/java/com/q3lives/ds/database/DsDatabaseServer.java`
- `p2p-db/src/main/java/com/q3lives/ds/database/config/DsDatabaseClientConfig.java`
- `p2p-db/src/main/java/com/q3lives/ds/database/startup/`
- `p2p-db/src/main/java/javax/net/p2p/server/handler/DbRowCountServerHandler.java`
- `p2p-db/src/main/java/javax/net/p2p/server/handler/DbRowExistsByQueryServerHandler.java`
- `p2p-db/src/main/java/javax/net/p2p/server/handler/DbRowQueryIdsServerHandler.java`
- `p2p-db/src/main/java/javax/net/p2p/server/handler/DbRowQueryIdsStreamServerHandler.java`
- `p2p-db/src/main/resources/META-INF/`
- `p2p-db/src/test/java/com/q3lives/ds/database/DbEntityP2PHandlersTest.java`
- `p2p-db/src/test/java/com/q3lives/ds/database/DbRowQueryStreamHandleCancelTest.java`
- `p2p-db/src/test/java/com/q3lives/ds/database/startup/`
- `p2p-db/src/test/java/javax/`
- `p2p-sync/pom.xml`
- `p2p-sync/src/main/java/javax/net/p2p/filesync/app/`
- `p2p-sync/src/main/java/javax/net/p2p/filesync/config/`
- `p2p-sync/src/main/java/javax/net/p2p/filesync/sync/`
- `p2p-sync/src/main/resources/`
- `p2p-sync/src/test/`
- `p2p-transfer/src/main/java/javax/net/p2p/utils/P2PUtils.java`
- `p2p-ws-protocol/docs/config.md`
- `p2p-ws-protocol/docs/p2p-core-wire-compat.md`
- `p2p-ws-protocol/generated/`
- `p2p-ws-protocol/proto/p2p_control.proto`
- `p2p-ws-sdk-c/CMakeLists.txt`
- `p2p-ws-sdk-c/demo/center_join.c`
- `p2p-ws-sdk-c/demo/core_ws_rpc_discover_echo.c`
- `p2p-ws-sdk-c/demo/peer_connect.c`
- `p2p-ws-sdk-c/demo/peer_node.c`
- `p2p-ws-sdk-c/demo/relay_echo.c`
- `p2p-ws-sdk-c/include/p2p_ws.h`
- `p2p-ws-sdk-c/include/p2pws_cipher.h`
- `p2p-ws-sdk-c/include/p2pws_core_compat.h`
- `p2p-ws-sdk-c/include/p2pws_core_rpc.h`
- `p2p-ws-sdk-c/include/p2pws_crypto.h`
- `p2p-ws-sdk-c/include/p2pws_messages.h`
- `p2p-ws-sdk-c/include/p2pws_p2p_command_ordinals.h`
- `p2p-ws-sdk-c/include/p2pws_pb.h`
- `p2p-ws-sdk-c/src/p2p_ws.c`
- `p2p-ws-sdk-c/src/p2pws_cipher.c`
- `p2p-ws-sdk-c/src/p2pws_core_compat.c`
- `p2p-ws-sdk-c/src/p2pws_core_rpc.c`
- `p2p-ws-sdk-c/src/p2pws_crypto.c`
- `p2p-ws-sdk-c/src/p2pws_messages.c`
- `p2p-ws-sdk-c/src/p2pws_pb.c`
- `p2p-ws-sdk-c/src/p2pws_yaml.c`
- `p2p-ws-sdk-dart/.brain/`
- `p2p-ws-sdk-dart/.gitignore`
- `p2p-ws-sdk-dart/AGENTS.md`
- `p2p-ws-sdk-dart/docs/`
- `p2p-ws-sdk-dart/example/core_ws_rpc_discover_echo.dart`
- `p2p-ws-sdk-dart/lib/p2p_ws_sdk.dart`
- `p2p-ws-sdk-dart/lib/src/core_compat/`
- `p2p-ws-sdk-dart/lib/src/crypto.dart`
- `p2p-ws-sdk-dart/lib/src/handshake.dart`
- `p2p-ws-sdk-dart/lib/src/messages/control.dart`
- `p2p-ws-sdk-dart/lib/src/peer_node.dart`
- `p2p-ws-sdk-dart/lib/src/rsa.dart`
- `p2p-ws-sdk-dart/lib/src/server.dart`
- `p2p-ws-sdk-dart/lib/src/server_group_client.dart`
- `p2p-ws-sdk-dart/lib/src/session.dart`
- `p2p-ws-sdk-dart/lib/src/xor.dart`
- `p2p-ws-sdk-java/README.md`
- `p2p-ws-sdk-java/pom.xml`
- `p2p-ws-sdk-java/src/main/java/javax/`
- `p2p-ws-sdk-java/src/main/java/p2pws/sdk/XorCipher.java`
- `p2p-ws-sdk-java/src/main/java/p2pws/sdk/center/CenterServerHandler.java`
- `p2p-ws-sdk-java/src/main/java/p2pws/sdk/core_compat/`
- `p2p-ws-sdk-java/src/main/java/p2pws/sdk/demo/CoreCompatPubSubMain.java`
- `p2p-ws-sdk-java/src/main/java/p2pws/sdk/demo/CoreCompatWsClientMain.java`
- `p2p-ws-sdk-java/src/main/java/p2pws/sdk/demo/DemoServerHandler.java`
- `p2p-ws-sdk-java/src/main/proto/`
- `p2p-ws-sdk-java/src/test/java/p2pws/sdk/core_compat/`
- `p2p-ws-sdk-python/demo/echo_client.py`
- `p2p-ws-sdk-python/src/.brain/`
- `p2p-ws-sdk-python/src/p2p_ws_sdk/__init__.py`
- `p2p-ws-sdk-python/src/p2p_ws_sdk/core_compat/`
- `p2p-ws-sdk-python/src/p2p_ws_sdk/gen/p2p_control_pb2.py`
- `p2p-ws-sdk-python/src/p2p_ws_sdk/xor.py`
- `p2p-ws-sdk-ts/.brain/`
- `p2p-ws-sdk-ts/demo/core_ws_rpc_discover_echo.ts`
- `p2p-ws-sdk-ts/src/PeerNode.ts`
- `p2p-ws-sdk-ts/src/ServerGroupClient.ts`
- `p2p-ws-sdk-ts/src/config.ts`
- `p2p-ws-sdk-ts/src/core_compat/`
- `p2p-ws-sdk-ts/src/xor.ts`
- `pom.xml`

```text
.brain/context/current-state.md                    |  74 +++++-
 .brain/resources/changes/dsdb.md                   |  50 +++-
 .../javax/net/p2p/cache/server/CacheBackend.java   |   6 +-
 p2p-core/pom.xml                                   |   4 +-
 .../main/java/javax/net/p2p/api/P2PCommand.java    |  12 +
 .../main/java/javax/net/p2p/auth/AuthEnforcer.java | 198 ++++++++++++--
 .../java/javax/net/p2p/auth/config/AuthConfig.java | 162 ++++++++++++
 .../javax/net/p2p/auth/model/HandshakeRequest.java |  27 ++
 .../net/p2p/auth/model/HandshakeResponse.java      |  36 +++
 .../java/javax/net/p2p/auth/utils/AuthCrypto.java  |  59 ++++-
 .../net/p2p/auth/utils/HandshakePayloads.java      |  38 +++
 .../p2p/channel/AbstractStreamRequestAdapter.java  |  46 +++-
 .../p2p/channel/AbstractTcpMessageProcessor.java   |   1 +
 .../p2p/channel/AbstractUdpMessageProcessor.java   |  86 +++++-
 .../java/javax/net/p2p/channel/ChannelUtils.java   |   6 +
 .../javax/net/p2p/client/AbstractP2PClient.java    |   3 +-
 .../p2p/client/ClientSendTcpMesageExecutor.java    |  22 +-
 .../p2p/client/ClientSendUdpMesageExecutor.java    |  58 ++--
 .../net/p2p/codec/P2PWrapperSecureDecoder.java     |   6 +-
 .../net/p2p/codec/P2PWrapperSecureEncoder.java     |   6 +-
 .../common/AbstractP2PMessageServiceAdapter.java   | 196 ++++++++++----
 .../java/javax/net/p2p/common/UdpFrameInbound.java | 147 +++++++++++
 .../src/main/java/javax/net/p2p/model/DbQuery.java |   3 +-
 .../java/javax/net/p2p/model/StreamP2PWrapper.java |  10 +
 .../javax/net/p2p/rpc/client/P2PRpcClient.java     | 139 +++++++++-
 .../javax/net/p2p/rpc/server/RpcDispatcher.java    |   6 +-
 .../javax/net/p2p/rpc/server/RpcPubSubBroker.java  |   8 +
 .../net/p2p/rpc/server/RpcPubSubServices.java      |   5 +-
 .../net/p2p/rpc/server/RpcServerStreamHandler.java |  28 +-
 .../java/javax/net/p2p/server/P2PServerQuic.java   |   2 +
 .../java/javax/net/p2p/server/P2PServerTcp.java    |   2 +
 .../java/javax/net/p2p/server/P2PServerUdp.java    |   2 +
 .../javax/net/p2p/server/P2PServerWebSocket.java   |  10 +
 .../p2p/server/ServerSendUdpMesageExecutor.java    |  74 ++++--
 .../net/p2p/server/ServerUdpMessageProcessor.java  |   7 +-
 .../net/p2p/server/handler/HandServerHandler.java  |  85 ++++--
 .../net/p2p/server/handler/LoginServerHandler.java |   8 +-
 .../handler/RpcEventCommandServerHandler.java      |   3 +
 .../handler/RpcStreamCommandServerHandler.java     |  76 +++++-
 .../main/java/javax/net/p2p/utils/DateUtil.java    |  50 +++-
 p2p-core/src/main/resources/SystemConfig.yaml      |   7 +
 p2p-core/src/main/resources/auth.yaml              |  42 ++-
 .../javax/net/p2p/auth/AuthHandshakeQuicTest.java  |   8 +
 .../javax/net/p2p/quic/QuicReliabilityTest.java    |  27 +-
 .../javax/net/p2p/rpc/RpcCommandHandlersTest.java  | 281 +++++++++++++++++---
 .../p2p/server/ServerQuicMessageProcessorTest.java |  23 +-
 .../javax/net/p2p/udp/UdpNetworkAnomalyTest.java   |  22 +-
 .../p2p/websocket/WebSocketReliabilityTest.java    |  59 +++--
 p2p-db/nb-configuration.xml                        |  18 --
 p2p-db/pom.xml                                     |  21 +-
 p2p-db/src/main/java/AccessModuleUtil.java         |  23 +-
 p2p-db/src/main/java/NewClass.java                 |   9 +-
 p2p-db/src/main/java/Wisp2.java                    |  11 +-
 .../main/java/com/q3lives/ds/core/DsObject.java    |  31 ++-
 .../com/q3lives/ds/database/DsDatabaseServer.java  | 161 +++++++++++-
 .../ds/database/config/DsDatabaseClientConfig.java |  12 +-
 .../server/handler/DbRowQueryIdsServerHandler.java | 223 +++++++++++++---
 .../ds/database/DbEntityP2PHandlersTest.java       | 291 +++++++++++++++++++++
 p2p-sync/pom.xml                                   |  11 +-
 .../main/java/javax/net/p2p/utils/P2PUtils.java    |   9 +-
 p2p-ws-protocol/docs/config.md                     |  11 +-
 p2p-ws-protocol/proto/p2p_control.proto            |   4 +
 p2p-ws-sdk-c/CMakeLists.txt                        |   6 +
 p2p-ws-sdk-c/demo/center_join.c                    |  74 +++---
 p2p-ws-sdk-c/demo/peer_connect.c                   |  93 ++++---
 p2p-ws-sdk-c/demo/peer_node.c                      | 134 ++++++++--
 p2p-ws-sdk-c/demo/relay_echo.c                     |  80 +++---
 p2p-ws-sdk-c/include/p2p_ws.h                      |   2 +-
 p2p-ws-sdk-c/include/p2pws_crypto.h                |   5 +
 p2p-ws-sdk-c/include/p2pws_messages.h              |   4 +-
 p2p-ws-sdk-c/include/p2pws_pb.h                    |   4 +
 p2p-ws-sdk-c/src/p2p_ws.c                          |   8 +
 p2p-ws-sdk-c/src/p2pws_crypto.c                    | 168 ++++++++++++
 p2p-ws-sdk-c/src/p2pws_messages.c                  |  28 +-
 p2p-ws-sdk-c/src/p2pws_pb.c                        |  24 ++
 p2p-ws-sdk-c/src/p2pws_yaml.c                      |  58 +++-
 p2p-ws-sdk-dart/lib/p2p_ws_sdk.dart                |   7 +
 p2p-ws-sdk-dart/lib/src/crypto.dart                |  19 ++
 p2p-ws-sdk-dart/lib/src/handshake.dart             |   5 +
 p2p-ws-sdk-dart/lib/src/messages/control.dart      |  56 ++++
 p2p-ws-sdk-dart/lib/src/peer_node.dart             |  16 +-
 p2p-ws-sdk-dart/lib/src/rsa.dart                   |  39 +++
 p2p-ws-sdk-dart/lib/src/server.dart                |  99 +++++--
 p2p-ws-sdk-dart/lib/src/session.dart               | 116 ++++++--
 p2p-ws-sdk-dart/lib/src/xor.dart                   |  19 ++
 p2p-ws-sdk-java/README.md                          |  20 ++
 p2p-ws-sdk-java/pom.xml                            |  57 +++-
 .../src/main/java/p2pws/sdk/XorCipher.java         |  15 +-
 .../java/p2pws/sdk/center/CenterServerHandler.java |  83 ++++--
 .../java/p2pws/sdk/demo/DemoServerHandler.java     |  89 +++++--
 p2p-ws-sdk-python/demo/echo_client.py              | 155 +++++------
 p2p-ws-sdk-python/src/p2p_ws_sdk/__init__.py       |   2 +-
 .../src/p2p_ws_sdk/gen/p2p_control_pb2.py          |  60 +++--
 p2p-ws-sdk-python/src/p2p_ws_sdk/xor.py            |  11 +-
 p2p-ws-sdk-ts/src/PeerNode.ts                      | 196 ++++++++++----
 p2p-ws-sdk-ts/src/config.ts                        |  14 +-
 p2p-ws-sdk-ts/src/xor.ts                           |   8 +
 pom.xml                                            |   4 +-
 98 files changed, 4050 insertions(+), 793 deletions(-)
```

### Recent Durable Notes

- No durable note edits were recorded after the session baseline.

## Promotion Review

### boundary_fact [promotable]

Summary: Record the durable outcome and touched boundaries from "p2p-sync 写冲突后写失败".

Target: `.brain/context/current-state.md`

Why promotable: repo changes touched concrete files and boundaries that future sessions may need

Diagnostics:
- linked to 1 compiled packet(s)
- touches 2 boundary/boundaries
- touches 223 changed file(s)
- 1 successful verification command(s) recorded


### verification_recipe [promotable]

Summary: Capture the repeatable verification recipe that proved "p2p-sync 写冲突后写失败".

Target: `.brain/resources/changes/p2p-sync.md`

Why promotable: successful verification commands were recorded against the packet-driven work

Diagnostics:
- linked to 1 compiled packet(s)
- touches 2 boundary/boundaries
- touches 223 changed file(s)
- 1 successful verification command(s) recorded


### decision [insufficient]

Summary: Preserve the rationale if "p2p-sync 写冲突后写失败" changed a technical or workflow decision.

Target: `.brain/resources/decisions/p2p-sync.md`

Why not promoted: the session does not show strong evidence that a durable decision changed

Diagnostics:
- linked to 1 compiled packet(s)
- touches 2 boundary/boundaries
- touches 223 changed file(s)
- 1 successful verification command(s) recorded


### follow_up [insufficient]

Summary: Record the unresolved follow-up required to fully close "p2p-sync 写冲突后写失败".

Target: `.brain/context/current-state.md`

Why not promoted: no unresolved verification or execution follow-up remains

Diagnostics:
- linked to 1 compiled packet(s)
- touches 2 boundary/boundaries
- touches 223 changed file(s)
- 1 successful verification command(s) recorded


### gotcha [insufficient]

Summary: Capture any recurring trap or regression guard exposed while working on "p2p-sync 写冲突后写失败".

Target: `.brain/context/current-state.md`

Why not promoted: no failed verification or execution signal exposed a recurring trap

Diagnostics:
- linked to 1 compiled packet(s)
- touches 2 boundary/boundaries
- touches 223 changed file(s)
- 1 successful verification command(s) recorded


### invariant [insufficient]

Summary: Promote any durable workflow or interface rule that "p2p-sync 写冲突后写失败" changed.

Target: `AGENTS.md`

Why not promoted: no workflow or contract surface changed strongly enough to justify a durable rule

Diagnostics:
- linked to 1 compiled packet(s)
- touches 2 boundary/boundaries
- touches 223 changed file(s)
- 1 successful verification command(s) recorded


## Proposed Updates

### .brain/context/current-state.md

Reason: repo changes touched concrete files and boundaries that future sessions may need [boundary_fact]

Suggested update:

```md
- Summarize the durable outcome from "p2p-sync 写冲突后写失败".
- Note the touched boundaries: `.brain/`, `p2p-cache/`.
- Mention the highest-signal changed files: `"p2p-ws-protocol//346/226/260/346/226/207/344/273/266 5.txt"`, `"p2p-ws-protocol//346/226/260/346/226/207/344/273/266 6.txt"`, `"p2p-ws-protocol//346/226/260/346/226/207/344/273/266 7.txt"`, `"p2p-ws-protocol//346/226/260/346/226/207/344/273/266 8.txt"`, `.brain/context/current-state.md`, `.brain/resources/changes/auth-rbac-roles-categories.md`.
```

### .brain/resources/changes/p2p-sync.md

Reason: successful verification commands were recorded against the packet-driven work [verification_recipe]

Suggested update:

```md
## Verification for p2p-sync 写冲突后写失败

- Capture only the commands that proved the work after review.
- `mvn -pl p2p-sync test`
```
