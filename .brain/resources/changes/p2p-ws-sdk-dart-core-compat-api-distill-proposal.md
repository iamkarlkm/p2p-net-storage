---
created: "2026-05-11T05:42:05Z"
distill_scope: session
promotion_categories:
    - boundary_fact
    - verification_recipe
proposed_targets:
    - .brain/context/current-state.md
    - .brain/resources/changes/p2p-ws-sdk-dart-core-compat-api.md
source_session_id: "1778478089465816300"
source_task: p2p-ws-sdk-dart 导出 core_compat API
title: p2p-ws-sdk-dart 导出 core_compat API Distill Proposal
type: distill_proposal
updated: "2026-05-11T05:42:05Z"
---
# p2p-ws-sdk-dart 导出 core_compat API Distill Proposal

## Source Provenance

- Mode: `session`
- Session: `1778478089465816300`
- Task: p2p-ws-sdk-dart 导出 core_compat API
- Git baseline: `a37a375af50bffb86207b90e1d2772672b303e89`

### Commands Run

- `dart analyze p2p-ws-sdk-dart` (exit 0)

### Git Diff

- `.brain/context/current-state.md`
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
- `.brain/resources/changes/p2p-core-std-cancel-std-stop-authhandshakequictest-distill-proposal.md`
- `.brain/resources/changes/p2p-core-std-cancel-std-stop-authhandshakequictest.md`
- `.brain/resources/changes/p2p-ws-sdk-c-core-compat-p2p-core-wire-format.md`
- `.brain/resources/changes/p2p-ws-sdk-dart-core-compat-p2p-core-wire-format-distill-proposal.md`
- `.brain/resources/changes/p2p-ws-sdk-dart-core-compat-p2p-core-wire-format.md`
- `.brain/resources/changes/p2p-ws-sdk-dart-python-c-network-mode-server-group-ws-urls-distill-proposal.md`
- `.brain/resources/changes/p2p-ws-sdk-dart-python-c-network-mode-server-group-ws-urls.md`
- `.brain/resources/changes/p2p-ws-sdk-server-group.md`
- `.brain/resources/changes/p2p-ws-sdk-ts-core-compat-p2p-core-wire-format.md`
- `.brain/resources/changes/p2p-ws-sdk-ts-network-mode-servergroupclient.md`
- `.brain/resources/changes/p2p-ws-sdk-ws-urls.md`
- `.brain/resources/changes/p2pcommand-ordinal-p2p-core-wire-format-python.md`
- `p2p-core/src/main/java/javax/net/p2p/api/P2PCommand.java`
- `p2p-core/src/main/java/javax/net/p2p/auth/AuthEnforcer.java`
- `p2p-core/src/main/java/javax/net/p2p/common/AbstractP2PMessageServiceAdapter.java`
- `p2p-core/src/main/java/javax/net/p2p/model/DbQuery.java`
- `p2p-core/src/main/java/javax/net/p2p/model/DbQueryOrGroup.java`
- `p2p-core/src/main/java/javax/net/p2p/model/DbRowCountRequest.java`
- `p2p-core/src/main/java/javax/net/p2p/model/DbRowCountResponse.java`
- `p2p-core/src/main/java/javax/net/p2p/model/DbRowExistsByQueryRequest.java`
- `p2p-core/src/main/java/javax/net/p2p/model/DbRowExistsByQueryResponse.java`
- `p2p-core/src/main/java/javax/net/p2p/model/DbRowQueryIdsStreamRequest.java`
- `p2p-core/src/main/java/javax/net/p2p/server/P2PServerQuic.java`
- `p2p-core/src/main/java/javax/net/p2p/server/P2PServerTcp.java`
- `p2p-core/src/main/java/javax/net/p2p/server/P2PServerUdp.java`
- `p2p-core/src/main/java/javax/net/p2p/server/P2PServerWebSocket.java`
- `p2p-core/src/main/java/javax/net/p2p/startup/`
- `p2p-core/src/main/resources/SystemConfig.yaml`
- `p2p-core/src/main/resources/auth.yaml`
- `p2p-core/src/test/java/javax/net/p2p/auth/AuthHandshakeQuicTest.java`
- `p2p-core/src/test/java/javax/net/p2p/codec/`
- `p2p-core/src/test/java/javax/net/p2p/common/`
- `p2p-core/src/test/java/javax/net/p2p/quic/QuicReliabilityTest.java`
- `p2p-core/src/test/java/javax/net/p2p/startup/`
- `p2p-core/src/test/java/javax/net/p2p/udp/UdpNetworkAnomalyTest.java`
- `p2p-core/src/test/java/javax/net/p2p/websocket/WebSocketReliabilityTest.java`
- `p2p-db/pom.xml`
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
- `p2p-ws-protocol/docs/config.md`
- `p2p-ws-protocol/docs/p2p-core-wire-compat.md`
- `p2p-ws-protocol/generated/`
- `p2p-ws-protocol/proto/p2p_control.proto`
- `p2p-ws-sdk-c/CMakeLists.txt`
- `p2p-ws-sdk-c/build-msvc/`
- `p2p-ws-sdk-c/build-trae/`
- `p2p-ws-sdk-c/build/CMakeFiles/Makefile.cmake`
- `p2p-ws-sdk-c/build/CMakeFiles/Makefile2`
- `p2p-ws-sdk-c/build/CMakeFiles/TargetDirectories.txt`
- `p2p-ws-sdk-c/build/CMakeFiles/p2pws_c.dir/DependInfo.cmake`
- `p2p-ws-sdk-c/build/CMakeFiles/p2pws_c.dir/build.make`
- `p2p-ws-sdk-c/build/CMakeFiles/p2pws_c.dir/cmake_clean.cmake`
- `p2p-ws-sdk-c/build/CMakeFiles/p2pws_c.dir/compiler_depend.internal`
- `p2p-ws-sdk-c/build/CMakeFiles/p2pws_c.dir/compiler_depend.make`
- `p2p-ws-sdk-c/build/CMakeFiles/p2pws_c.dir/link.txt`
- `p2p-ws-sdk-c/build/CMakeFiles/p2pws_c.dir/progress.make`
- `p2p-ws-sdk-c/build/CMakeFiles/p2pws_c.dir/src/p2p_ws.c.obj`
- `p2p-ws-sdk-c/build/CMakeFiles/p2pws_c.dir/src/p2pws_cipher.c.obj`
- `p2p-ws-sdk-c/build/CMakeFiles/p2pws_c.dir/src/p2pws_cipher.c.obj.d`
- `p2p-ws-sdk-c/build/CMakeFiles/p2pws_c.dir/src/p2pws_core_compat.c.obj`
- `p2p-ws-sdk-c/build/CMakeFiles/p2pws_c.dir/src/p2pws_core_compat.c.obj.d`
- `p2p-ws-sdk-c/build/CMakeFiles/p2pws_c.dir/src/p2pws_core_rpc.c.obj`
- `p2p-ws-sdk-c/build/CMakeFiles/p2pws_c.dir/src/p2pws_core_rpc.c.obj.d`
- `p2p-ws-sdk-c/build/CMakeFiles/p2pws_c.dir/src/p2pws_crypto.c.obj`
- `p2p-ws-sdk-c/build/CMakeFiles/p2pws_c.dir/src/p2pws_messages.c.obj`
- `p2p-ws-sdk-c/build/CMakeFiles/p2pws_c.dir/src/p2pws_pb.c.obj`
- `p2p-ws-sdk-c/build/CMakeFiles/p2pws_c.dir/src/p2pws_yaml.c.obj`
- `p2p-ws-sdk-c/build/CMakeFiles/p2pws_center_join.dir/compiler_depend.internal`
- `p2p-ws-sdk-c/build/CMakeFiles/p2pws_center_join.dir/compiler_depend.make`
- `p2p-ws-sdk-c/build/CMakeFiles/p2pws_center_join.dir/demo/center_join.c.obj`
- `p2p-ws-sdk-c/build/CMakeFiles/p2pws_center_join.dir/demo/center_join.c.obj.d`
- `p2p-ws-sdk-c/build/CMakeFiles/p2pws_center_join.dir/objects.a`
- `p2p-ws-sdk-c/build/CMakeFiles/p2pws_center_join.dir/progress.make`
- `p2p-ws-sdk-c/build/CMakeFiles/p2pws_core_ws_rpc_discover_echo.dir/`
- `p2p-ws-sdk-c/build/CMakeFiles/p2pws_peer_connect.dir/compiler_depend.internal`
- `p2p-ws-sdk-c/build/CMakeFiles/p2pws_peer_connect.dir/compiler_depend.make`
- `p2p-ws-sdk-c/build/CMakeFiles/p2pws_peer_connect.dir/demo/peer_connect.c.obj`
- `p2p-ws-sdk-c/build/CMakeFiles/p2pws_peer_connect.dir/demo/peer_connect.c.obj.d`
- `p2p-ws-sdk-c/build/CMakeFiles/p2pws_peer_connect.dir/objects.a`
- `p2p-ws-sdk-c/build/CMakeFiles/p2pws_peer_connect.dir/progress.make`
- `p2p-ws-sdk-c/build/CMakeFiles/p2pws_peer_node.dir/demo/peer_node.c.obj`
- `p2p-ws-sdk-c/build/CMakeFiles/p2pws_peer_node.dir/objects.a`
- `p2p-ws-sdk-c/build/CMakeFiles/p2pws_peer_node.dir/progress.make`
- `p2p-ws-sdk-c/build/CMakeFiles/p2pws_relay_echo.dir/compiler_depend.internal`
- `p2p-ws-sdk-c/build/CMakeFiles/p2pws_relay_echo.dir/compiler_depend.make`
- `p2p-ws-sdk-c/build/CMakeFiles/p2pws_relay_echo.dir/demo/relay_echo.c.obj`
- `p2p-ws-sdk-c/build/CMakeFiles/p2pws_relay_echo.dir/demo/relay_echo.c.obj.d`
- `p2p-ws-sdk-c/build/CMakeFiles/p2pws_relay_echo.dir/objects.a`
- `p2p-ws-sdk-c/build/CMakeFiles/p2pws_relay_echo.dir/progress.make`
- `p2p-ws-sdk-c/build/CMakeFiles/progress.marks`
- `p2p-ws-sdk-c/build/Makefile`
- `p2p-ws-sdk-c/build/libp2pws_c.a`
- `p2p-ws-sdk-c/build/p2pws_center_join.exe`
- `p2p-ws-sdk-c/build/p2pws_core_ws_rpc_discover_echo.exe`
- `p2p-ws-sdk-c/build/p2pws_peer_connect.exe`
- `p2p-ws-sdk-c/build/p2pws_peer_node.exe`
- `p2p-ws-sdk-c/build/p2pws_relay_echo.exe`
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
- `p2p-ws-sdk-java/src/main/java/p2pws/sdk/XorCipher.java`
- `p2p-ws-sdk-java/src/main/java/p2pws/sdk/center/CenterServerHandler.java`
- `p2p-ws-sdk-java/src/main/java/p2pws/sdk/demo/DemoServerHandler.java`
- `p2p-ws-sdk-python/demo/__pycache__/`
- `p2p-ws-sdk-python/demo/echo_client.py`
- `p2p-ws-sdk-python/src/.brain/`
- `p2p-ws-sdk-python/src/p2p_ws_sdk/__init__.py`
- `p2p-ws-sdk-python/src/p2p_ws_sdk/__pycache__/__init__.cpython-312.pyc`
- `p2p-ws-sdk-python/src/p2p_ws_sdk/__pycache__/client.cpython-312.pyc`
- `p2p-ws-sdk-python/src/p2p_ws_sdk/__pycache__/frame.cpython-312.pyc`
- `p2p-ws-sdk-python/src/p2p_ws_sdk/__pycache__/handshake.cpython-312.pyc`
- `p2p-ws-sdk-python/src/p2p_ws_sdk/__pycache__/keyid.cpython-312.pyc`
- `p2p-ws-sdk-python/src/p2p_ws_sdk/__pycache__/wrapper.cpython-312.pyc`
- `p2p-ws-sdk-python/src/p2p_ws_sdk/__pycache__/xor.cpython-312.pyc`
- `p2p-ws-sdk-python/src/p2p_ws_sdk/core_compat/`
- `p2p-ws-sdk-python/src/p2p_ws_sdk/gen/__pycache__/p2p_control_pb2.cpython-312.pyc`
- `p2p-ws-sdk-python/src/p2p_ws_sdk/gen/__pycache__/p2p_rpc_echo_pb2.cpython-312.pyc`
- `p2p-ws-sdk-python/src/p2p_ws_sdk/gen/__pycache__/p2p_rpc_pb2.cpython-312.pyc`
- `p2p-ws-sdk-python/src/p2p_ws_sdk/gen/__pycache__/p2p_wrapper_pb2.cpython-312.pyc`
- `p2p-ws-sdk-python/src/p2p_ws_sdk/gen/p2p_control_pb2.py`
- `p2p-ws-sdk-python/src/p2p_ws_sdk/xor.py`
- `p2p-ws-sdk-ts/.brain/`
- `p2p-ws-sdk-ts/demo/core_ws_rpc_discover_echo.ts`
- `p2p-ws-sdk-ts/src/PeerNode.ts`
- `p2p-ws-sdk-ts/src/ServerGroupClient.ts`
- `p2p-ws-sdk-ts/src/config.ts`
- `p2p-ws-sdk-ts/src/core_compat/`
- `p2p-ws-sdk-ts/src/xor.ts`

```text
.brain/context/current-state.md                    |  38 ++-
 .brain/resources/changes/dsdb.md                   |  50 +++-
 .../main/java/javax/net/p2p/api/P2PCommand.java    |  12 +
 .../main/java/javax/net/p2p/auth/AuthEnforcer.java |   6 +-
 .../common/AbstractP2PMessageServiceAdapter.java   |  16 +-
 .../src/main/java/javax/net/p2p/model/DbQuery.java |   3 +-
 .../java/javax/net/p2p/server/P2PServerQuic.java   |   2 +
 .../java/javax/net/p2p/server/P2PServerTcp.java    |   2 +
 .../java/javax/net/p2p/server/P2PServerUdp.java    |   2 +
 .../javax/net/p2p/server/P2PServerWebSocket.java   |   2 +
 p2p-core/src/main/resources/SystemConfig.yaml      |   7 +
 p2p-core/src/main/resources/auth.yaml              |  11 +
 .../javax/net/p2p/auth/AuthHandshakeQuicTest.java  |   8 +
 .../javax/net/p2p/quic/QuicReliabilityTest.java    |  27 +-
 .../javax/net/p2p/udp/UdpNetworkAnomalyTest.java   |  22 +-
 .../p2p/websocket/WebSocketReliabilityTest.java    |  59 +++--
 p2p-db/pom.xml                                     |  14 +-
 .../main/java/com/q3lives/ds/core/DsObject.java    |  24 +-
 .../com/q3lives/ds/database/DsDatabaseServer.java  | 161 ++++++++++-
 .../ds/database/config/DsDatabaseClientConfig.java |  12 +-
 .../server/handler/DbRowQueryIdsServerHandler.java | 223 +++++++++++++---
 .../ds/database/DbEntityP2PHandlersTest.java       | 291 ++++++++++++++++++++
 p2p-ws-protocol/docs/config.md                     |  11 +-
 p2p-ws-protocol/proto/p2p_control.proto            |   4 +
 p2p-ws-sdk-c/CMakeLists.txt                        |   6 +
 p2p-ws-sdk-c/build/CMakeFiles/Makefile.cmake       |  68 +----
 p2p-ws-sdk-c/build/CMakeFiles/Makefile2            |  48 +++-
 .../build/CMakeFiles/TargetDirectories.txt         |   1 +
 .../build/CMakeFiles/p2pws_c.dir/DependInfo.cmake  |   3 +
 .../build/CMakeFiles/p2pws_c.dir/build.make        |  61 ++++-
 .../build/CMakeFiles/p2pws_c.dir/cmake_clean.cmake |   6 +
 .../p2pws_c.dir/compiler_depend.internal           |  64 +++++
 .../CMakeFiles/p2pws_c.dir/compiler_depend.make    | 295 +++++++++++++--------
 p2p-ws-sdk-c/build/CMakeFiles/p2pws_c.dir/link.txt |   2 +-
 .../build/CMakeFiles/p2pws_c.dir/progress.make     |   3 +
 .../build/CMakeFiles/p2pws_c.dir/src/p2p_ws.c.obj  | Bin 1894 -> 2143 bytes
 .../CMakeFiles/p2pws_c.dir/src/p2pws_crypto.c.obj  | Bin 8716 -> 12467 bytes
 .../p2pws_c.dir/src/p2pws_messages.c.obj           | Bin 7737 -> 8017 bytes
 .../CMakeFiles/p2pws_c.dir/src/p2pws_pb.c.obj      | Bin 11822 -> 12226 bytes
 .../CMakeFiles/p2pws_c.dir/src/p2pws_yaml.c.obj    | Bin 5473 -> 6673 bytes
 .../p2pws_center_join.dir/compiler_depend.internal |   1 +
 .../p2pws_center_join.dir/compiler_depend.make     |   3 +
 .../p2pws_center_join.dir/demo/center_join.c.obj   | Bin 11216 -> 11785 bytes
 .../p2pws_center_join.dir/demo/center_join.c.obj.d |   5 +-
 .../CMakeFiles/p2pws_center_join.dir/objects.a     | Bin 11438 -> 12008 bytes
 .../CMakeFiles/p2pws_center_join.dir/progress.make |   4 +-
 .../compiler_depend.internal                       |   1 +
 .../p2pws_peer_connect.dir/compiler_depend.make    |   3 +
 .../p2pws_peer_connect.dir/demo/peer_connect.c.obj | Bin 13534 -> 14421 bytes
 .../demo/peer_connect.c.obj.d                      |   5 +-
 .../CMakeFiles/p2pws_peer_connect.dir/objects.a    | Bin 13756 -> 14644 bytes
 .../p2pws_peer_connect.dir/progress.make           |   4 +-
 .../p2pws_peer_node.dir/demo/peer_node.c.obj       | Bin 18159 -> 20973 bytes
 .../build/CMakeFiles/p2pws_peer_node.dir/objects.a | Bin 18302 -> 21116 bytes
 .../CMakeFiles/p2pws_peer_node.dir/progress.make   |   4 +-
 .../p2pws_relay_echo.dir/compiler_depend.internal  |   1 +
 .../p2pws_relay_echo.dir/compiler_depend.make      |   3 +
 .../p2pws_relay_echo.dir/demo/relay_echo.c.obj     | Bin 10658 -> 11347 bytes
 .../p2pws_relay_echo.dir/demo/relay_echo.c.obj.d   |   5 +-
 .../CMakeFiles/p2pws_relay_echo.dir/objects.a      | Bin 10878 -> 11568 bytes
 .../CMakeFiles/p2pws_relay_echo.dir/progress.make  |   4 +-
 p2p-ws-sdk-c/build/CMakeFiles/progress.marks       |   2 +-
 p2p-ws-sdk-c/build/Makefile                        | 122 +++++++++
 p2p-ws-sdk-c/build/libp2pws_c.a                    | Bin 56732 -> 78508 bytes
 p2p-ws-sdk-c/build/p2pws_center_join.exe           | Bin 312445 -> 320056 bytes
 p2p-ws-sdk-c/build/p2pws_peer_connect.exe          | Bin 313302 -> 321937 bytes
 p2p-ws-sdk-c/build/p2pws_peer_node.exe             | Bin 320251 -> 328756 bytes
 p2p-ws-sdk-c/build/p2pws_relay_echo.exe            | Bin 311884 -> 320007 bytes
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
 .../src/main/java/p2pws/sdk/XorCipher.java         |  15 +-
 .../java/p2pws/sdk/center/CenterServerHandler.java |  83 ++++--
 .../java/p2pws/sdk/demo/DemoServerHandler.java     |  89 +++++--
 p2p-ws-sdk-python/demo/echo_client.py              | 155 +++++------
 p2p-ws-sdk-python/src/p2p_ws_sdk/__init__.py       |   2 +-
 .../__pycache__/__init__.cpython-312.pyc           | Bin 252 -> 222 bytes
 .../p2p_ws_sdk/__pycache__/frame.cpython-312.pyc   | Bin 2354 -> 2303 bytes
 .../p2p_ws_sdk/__pycache__/keyid.cpython-312.pyc   | Bin 709 -> 658 bytes
 .../p2p_ws_sdk/__pycache__/wrapper.cpython-312.pyc | Bin 1991 -> 1940 bytes
 .../src/p2p_ws_sdk/__pycache__/xor.cpython-312.pyc | Bin 929 -> 1388 bytes
 .../__pycache__/p2p_control_pb2.cpython-312.pyc    | Bin 3492 -> 4875 bytes
 .../__pycache__/p2p_wrapper_pb2.cpython-312.pyc    | Bin 1830 -> 1779 bytes
 .../src/p2p_ws_sdk/gen/p2p_control_pb2.py          |  60 +++--
 p2p-ws-sdk-python/src/p2p_ws_sdk/xor.py            |  11 +-
 p2p-ws-sdk-ts/src/PeerNode.ts                      | 196 +++++++++++---
 p2p-ws-sdk-ts/src/config.ts                        |  14 +-
 p2p-ws-sdk-ts/src/xor.ts                           |   8 +
 107 files changed, 2748 insertions(+), 674 deletions(-)
```

### Recent Durable Notes

- No durable note edits were recorded after the session baseline.

## Promotion Review

### boundary_fact [promotable]

Summary: Record the durable outcome and touched boundaries from "p2p-ws-sdk-dart 导出 core_compat API".

Target: `.brain/context/current-state.md`

Why promotable: repo changes touched concrete files and boundaries that future sessions may need

Diagnostics:
- linked to 1 compiled packet(s)
- touches 1 boundary/boundaries
- touches 197 changed file(s)
- 1 successful verification command(s) recorded


### verification_recipe [promotable]

Summary: Capture the repeatable verification recipe that proved "p2p-ws-sdk-dart 导出 core_compat API".

Target: `.brain/resources/changes/p2p-ws-sdk-dart-core-compat-api.md`

Why promotable: successful verification commands were recorded against the packet-driven work

Diagnostics:
- linked to 1 compiled packet(s)
- touches 1 boundary/boundaries
- touches 197 changed file(s)
- 1 successful verification command(s) recorded


### decision [insufficient]

Summary: Preserve the rationale if "p2p-ws-sdk-dart 导出 core_compat API" changed a technical or workflow decision.

Target: `.brain/resources/decisions/p2p-ws-sdk-dart-core-compat-api.md`

Why not promoted: the session does not show strong evidence that a durable decision changed

Diagnostics:
- linked to 1 compiled packet(s)
- touches 1 boundary/boundaries
- touches 197 changed file(s)
- 1 successful verification command(s) recorded


### follow_up [insufficient]

Summary: Record the unresolved follow-up required to fully close "p2p-ws-sdk-dart 导出 core_compat API".

Target: `.brain/context/current-state.md`

Why not promoted: no unresolved verification or execution follow-up remains

Diagnostics:
- linked to 1 compiled packet(s)
- touches 1 boundary/boundaries
- touches 197 changed file(s)
- 1 successful verification command(s) recorded


### gotcha [insufficient]

Summary: Capture any recurring trap or regression guard exposed while working on "p2p-ws-sdk-dart 导出 core_compat API".

Target: `.brain/context/current-state.md`

Why not promoted: no failed verification or execution signal exposed a recurring trap

Diagnostics:
- linked to 1 compiled packet(s)
- touches 1 boundary/boundaries
- touches 197 changed file(s)
- 1 successful verification command(s) recorded


### invariant [insufficient]

Summary: Promote any durable workflow or interface rule that "p2p-ws-sdk-dart 导出 core_compat API" changed.

Target: `AGENTS.md`

Why not promoted: no workflow or contract surface changed strongly enough to justify a durable rule

Diagnostics:
- linked to 1 compiled packet(s)
- touches 1 boundary/boundaries
- touches 197 changed file(s)
- 1 successful verification command(s) recorded


## Proposed Updates

### .brain/context/current-state.md

Reason: repo changes touched concrete files and boundaries that future sessions may need [boundary_fact]

Suggested update:

```md
- Summarize the durable outcome from "p2p-ws-sdk-dart 导出 core_compat API".
- Note the touched boundaries: `.brain/`.
- Mention the highest-signal changed files: `.brain/context/current-state.md`, `.brain/resources/changes/auth-yaml-allowcommands-rpc.md`, `.brain/resources/changes/c-demos-crypto-mode-client-random-server-random-plain-distill-proposal.md`, `.brain/resources/changes/c-demos-crypto-mode-client-random-server-random-plain.md`, `.brain/resources/changes/dsdb-count-db-row-count-distill-proposal.md`, `.brain/resources/changes/dsdb-count-db-row-count.md`.
```

### .brain/resources/changes/p2p-ws-sdk-dart-core-compat-api.md

Reason: successful verification commands were recorded against the packet-driven work [verification_recipe]

Suggested update:

```md
## Verification for p2p-ws-sdk-dart 导出 core_compat API

- Capture only the commands that proved the work after review.
- `dart analyze p2p-ws-sdk-dart`
```
