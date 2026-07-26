---
created: "2026-05-11T02:50:29Z"
distill_scope: session
promotion_categories:
    - boundary_fact
    - follow_up
    - gotcha
    - verification_recipe
proposed_targets:
    - .brain/context/current-state.md
    - .brain/context/current-state.md
    - .brain/context/current-state.md
    - .brain/resources/changes/p2p-core-std-cancel-std-stop-authhandshakequictest.md
source_session_id: "1778466880464801100"
source_task: 'p2p-core: 放行 STD_CANCEL/STD_STOP 并回归 AuthHandshakeQuicTest'
title: 'p2p-core: 放行 STD_CANCEL/STD_STOP 并回归 AuthHandshakeQuicTest Distill Proposal'
type: distill_proposal
updated: "2026-05-11T02:50:29Z"
---
# p2p-core: 放行 STD_CANCEL/STD_STOP 并回归 AuthHandshakeQuicTest Distill Proposal

## Source Provenance

- Mode: `session`
- Session: `1778466880464801100`
- Task: p2p-core: 放行 STD_CANCEL/STD_STOP 并回归 AuthHandshakeQuicTest
- Git baseline: `a37a375af50bffb86207b90e1d2772672b303e89`

### Commands Run

- `mvn -q -pl p2p-core test` (exit 1)
- `mvn -q -pl p2p-core test` (exit 1)
- `mvn -q -pl p2p-core test` (exit 1)
- `mvn -q -pl p2p-core test` (exit 1)
- `mvn -q -pl p2p-core test` (exit 1)
- `mvn -q -pl p2p-core test` (exit 0)

### Git Diff

- `.brain/context/current-state.md`
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
- `.brain/resources/changes/p2p-core-std-cancel-std-stop-authhandshakequictest.md`
- `.brain/resources/changes/p2p-ws-sdk-dart-python-c-network-mode-server-group-ws-urls-distill-proposal.md`
- `.brain/resources/changes/p2p-ws-sdk-dart-python-c-network-mode-server-group-ws-urls.md`
- `.brain/resources/changes/p2p-ws-sdk-server-group.md`
- `.brain/resources/changes/p2p-ws-sdk-ts-network-mode-servergroupclient.md`
- `.brain/resources/changes/p2p-ws-sdk-ws-urls.md`
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
- `p2p-core/src/test/java/javax/net/p2p/common/`
- `p2p-core/src/test/java/javax/net/p2p/quic/QuicReliabilityTest.java`
- `p2p-core/src/test/java/javax/net/p2p/startup/`
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
- `p2p-ws-protocol/proto/p2p_control.proto`
- `p2p-ws-sdk-c/CMakeLists.txt`
- `p2p-ws-sdk-c/build-msvc/`
- `p2p-ws-sdk-c/build-trae/`
- `p2p-ws-sdk-c/demo/center_join.c`
- `p2p-ws-sdk-c/demo/peer_connect.c`
- `p2p-ws-sdk-c/demo/peer_node.c`
- `p2p-ws-sdk-c/demo/relay_echo.c`
- `p2p-ws-sdk-c/include/p2p_ws.h`
- `p2p-ws-sdk-c/include/p2pws_cipher.h`
- `p2p-ws-sdk-c/include/p2pws_crypto.h`
- `p2p-ws-sdk-c/include/p2pws_messages.h`
- `p2p-ws-sdk-c/include/p2pws_pb.h`
- `p2p-ws-sdk-c/src/p2p_ws.c`
- `p2p-ws-sdk-c/src/p2pws_cipher.c`
- `p2p-ws-sdk-c/src/p2pws_crypto.c`
- `p2p-ws-sdk-c/src/p2pws_messages.c`
- `p2p-ws-sdk-c/src/p2pws_pb.c`
- `p2p-ws-sdk-c/src/p2pws_yaml.c`
- `p2p-ws-sdk-dart/.brain/`
- `p2p-ws-sdk-dart/lib/p2p_ws_sdk.dart`
- `p2p-ws-sdk-dart/lib/src/crypto.dart`
- `p2p-ws-sdk-dart/lib/src/handshake.dart`
- `p2p-ws-sdk-dart/lib/src/messages/control.dart`
- `p2p-ws-sdk-dart/lib/src/peer_node.dart`
- `p2p-ws-sdk-dart/lib/src/server.dart`
- `p2p-ws-sdk-dart/lib/src/server_group_client.dart`
- `p2p-ws-sdk-dart/lib/src/session.dart`
- `p2p-ws-sdk-dart/lib/src/xor.dart`
- `p2p-ws-sdk-java/src/main/java/p2pws/sdk/XorCipher.java`
- `p2p-ws-sdk-java/src/main/java/p2pws/sdk/center/CenterServerHandler.java`
- `p2p-ws-sdk-java/src/main/java/p2pws/sdk/demo/DemoServerHandler.java`
- `p2p-ws-sdk-python/demo/__pycache__/`
- `p2p-ws-sdk-python/demo/echo_client.py`
- `p2p-ws-sdk-python/src/p2p_ws_sdk/__init__.py`
- `p2p-ws-sdk-python/src/p2p_ws_sdk/__pycache__/__init__.cpython-312.pyc`
- `p2p-ws-sdk-python/src/p2p_ws_sdk/__pycache__/client.cpython-312.pyc`
- `p2p-ws-sdk-python/src/p2p_ws_sdk/__pycache__/frame.cpython-312.pyc`
- `p2p-ws-sdk-python/src/p2p_ws_sdk/__pycache__/handshake.cpython-312.pyc`
- `p2p-ws-sdk-python/src/p2p_ws_sdk/__pycache__/keyid.cpython-312.pyc`
- `p2p-ws-sdk-python/src/p2p_ws_sdk/__pycache__/wrapper.cpython-312.pyc`
- `p2p-ws-sdk-python/src/p2p_ws_sdk/__pycache__/xor.cpython-312.pyc`
- `p2p-ws-sdk-python/src/p2p_ws_sdk/gen/p2p_control_pb2.py`
- `p2p-ws-sdk-python/src/p2p_ws_sdk/xor.py`
- `p2p-ws-sdk-ts/src/PeerNode.ts`
- `p2p-ws-sdk-ts/src/ServerGroupClient.ts`
- `p2p-ws-sdk-ts/src/config.ts`
- `p2p-ws-sdk-ts/src/xor.ts`

```text
.brain/context/current-state.md                    |  25 +-
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
 p2p-core/src/main/resources/auth.yaml              |   5 +
 .../javax/net/p2p/auth/AuthHandshakeQuicTest.java  |   8 +
 .../javax/net/p2p/quic/QuicReliabilityTest.java    |  27 +-
 .../p2p/websocket/WebSocketReliabilityTest.java    |  59 +++--
 p2p-db/pom.xml                                     |  14 +-
 .../main/java/com/q3lives/ds/core/DsObject.java    |  24 +-
 .../com/q3lives/ds/database/DsDatabaseServer.java  | 161 +++++++++++-
 .../ds/database/config/DsDatabaseClientConfig.java |  12 +-
 .../server/handler/DbRowQueryIdsServerHandler.java | 223 +++++++++++++---
 .../ds/database/DbEntityP2PHandlersTest.java       | 291 +++++++++++++++++++++
 p2p-ws-protocol/docs/config.md                     |  11 +-
 p2p-ws-protocol/proto/p2p_control.proto            |   4 +
 p2p-ws-sdk-c/CMakeLists.txt                        |   1 +
 p2p-ws-sdk-c/demo/center_join.c                    |  74 +++---
 p2p-ws-sdk-c/demo/peer_connect.c                   |  93 ++++---
 p2p-ws-sdk-c/demo/peer_node.c                      | 134 ++++++++--
 p2p-ws-sdk-c/demo/relay_echo.c                     |  80 +++---
 p2p-ws-sdk-c/include/p2p_ws.h                      |   2 +-
 p2p-ws-sdk-c/include/p2pws_crypto.h                |   2 +
 p2p-ws-sdk-c/include/p2pws_messages.h              |   4 +-
 p2p-ws-sdk-c/include/p2pws_pb.h                    |   4 +
 p2p-ws-sdk-c/src/p2p_ws.c                          |   8 +
 p2p-ws-sdk-c/src/p2pws_crypto.c                    |   7 +
 p2p-ws-sdk-c/src/p2pws_messages.c                  |  28 +-
 p2p-ws-sdk-c/src/p2pws_pb.c                        |  24 ++
 p2p-ws-sdk-c/src/p2pws_yaml.c                      |  58 +++-
 p2p-ws-sdk-dart/lib/p2p_ws_sdk.dart                |   1 +
 p2p-ws-sdk-dart/lib/src/crypto.dart                |  19 ++
 p2p-ws-sdk-dart/lib/src/handshake.dart             |   5 +
 p2p-ws-sdk-dart/lib/src/messages/control.dart      |  56 ++++
 p2p-ws-sdk-dart/lib/src/peer_node.dart             |  16 +-
 p2p-ws-sdk-dart/lib/src/server.dart                |  99 +++++--
 p2p-ws-sdk-dart/lib/src/session.dart               | 116 ++++++--
 p2p-ws-sdk-dart/lib/src/xor.dart                   |  10 +
 .../src/main/java/p2pws/sdk/XorCipher.java         |  15 +-
 .../java/p2pws/sdk/center/CenterServerHandler.java |  83 ++++--
 .../java/p2pws/sdk/demo/DemoServerHandler.java     |  89 +++++--
 p2p-ws-sdk-python/demo/echo_client.py              | 155 +++++------
 p2p-ws-sdk-python/src/p2p_ws_sdk/__init__.py       |   2 +-
 .../__pycache__/__init__.cpython-312.pyc           | Bin 252 -> 209 bytes
 .../p2p_ws_sdk/__pycache__/frame.cpython-312.pyc   | Bin 2354 -> 2303 bytes
 .../p2p_ws_sdk/__pycache__/keyid.cpython-312.pyc   | Bin 709 -> 658 bytes
 .../p2p_ws_sdk/__pycache__/wrapper.cpython-312.pyc | Bin 1991 -> 1940 bytes
 .../src/p2p_ws_sdk/__pycache__/xor.cpython-312.pyc | Bin 929 -> 1388 bytes
 .../src/p2p_ws_sdk/gen/p2p_control_pb2.py          |  60 +++--
 p2p-ws-sdk-python/src/p2p_ws_sdk/xor.py            |  11 +-
 p2p-ws-sdk-ts/src/PeerNode.ts                      | 196 ++++++++++----
 p2p-ws-sdk-ts/src/config.ts                        |  14 +-
 p2p-ws-sdk-ts/src/xor.ts                           |   8 +
 60 files changed, 1988 insertions(+), 452 deletions(-)
```

### Recent Durable Notes

- No durable note edits were recorded after the session baseline.

## Promotion Review

### boundary_fact [promotable]

Summary: Record the durable outcome and touched boundaries from "p2p-core: 放行 STD_CANCEL/STD_STOP 并回归 AuthHandshakeQuicTest".

Target: `.brain/context/current-state.md`

Why promotable: repo changes touched concrete files and boundaries that future sessions may need

Diagnostics:
- linked to 1 compiled packet(s)
- touches 2 boundary/boundaries
- touches 114 changed file(s)
- 1 successful verification command(s) recorded
- 1 failed command(s) recorded


### follow_up [promotable]

Summary: Record the unresolved follow-up required to fully close "p2p-core: 放行 STD_CANCEL/STD_STOP 并回归 AuthHandshakeQuicTest".

Target: `.brain/context/current-state.md`

Why promotable: the session still has unresolved verification or execution follow-up

Diagnostics:
- linked to 1 compiled packet(s)
- touches 2 boundary/boundaries
- touches 114 changed file(s)
- 1 successful verification command(s) recorded
- 1 failed command(s) recorded


### gotcha [promotable]

Summary: Capture any recurring trap or regression guard exposed while working on "p2p-core: 放行 STD_CANCEL/STD_STOP 并回归 AuthHandshakeQuicTest".

Target: `.brain/context/current-state.md`

Why promotable: the session recorded failed commands that may deserve a durable trap note

Diagnostics:
- linked to 1 compiled packet(s)
- touches 2 boundary/boundaries
- touches 114 changed file(s)
- 1 successful verification command(s) recorded
- 1 failed command(s) recorded


### verification_recipe [promotable]

Summary: Capture the repeatable verification recipe that proved "p2p-core: 放行 STD_CANCEL/STD_STOP 并回归 AuthHandshakeQuicTest".

Target: `.brain/resources/changes/p2p-core-std-cancel-std-stop-authhandshakequictest.md`

Why promotable: successful verification commands were recorded against the packet-driven work

Diagnostics:
- linked to 1 compiled packet(s)
- touches 2 boundary/boundaries
- touches 114 changed file(s)
- 1 successful verification command(s) recorded
- 1 failed command(s) recorded


### decision [insufficient]

Summary: Preserve the rationale if "p2p-core: 放行 STD_CANCEL/STD_STOP 并回归 AuthHandshakeQuicTest" changed a technical or workflow decision.

Target: `.brain/resources/decisions/p2p-core-std-cancel-std-stop-authhandshakequictest.md`

Why not promoted: the session does not show strong evidence that a durable decision changed

Diagnostics:
- linked to 1 compiled packet(s)
- touches 2 boundary/boundaries
- touches 114 changed file(s)
- 1 successful verification command(s) recorded
- 1 failed command(s) recorded


### invariant [insufficient]

Summary: Promote any durable workflow or interface rule that "p2p-core: 放行 STD_CANCEL/STD_STOP 并回归 AuthHandshakeQuicTest" changed.

Target: `AGENTS.md`

Why not promoted: no workflow or contract surface changed strongly enough to justify a durable rule

Diagnostics:
- linked to 1 compiled packet(s)
- touches 2 boundary/boundaries
- touches 114 changed file(s)
- 1 successful verification command(s) recorded
- 1 failed command(s) recorded


## Proposed Updates

### .brain/context/current-state.md

Reason: repo changes touched concrete files and boundaries that future sessions may need [boundary_fact]

Suggested update:

```md
- Summarize the durable outcome from "p2p-core: 放行 STD_CANCEL/STD_STOP 并回归 AuthHandshakeQuicTest".
- Note the touched boundaries: `.brain/`, `p2p-core/`.
- Mention the highest-signal changed files: `.brain/context/current-state.md`, `.brain/resources/changes/c-demos-crypto-mode-client-random-server-random-plain-distill-proposal.md`, `.brain/resources/changes/c-demos-crypto-mode-client-random-server-random-plain.md`, `.brain/resources/changes/dsdb-count-db-row-count-distill-proposal.md`, `.brain/resources/changes/dsdb-count-db-row-count.md`, `.brain/resources/changes/dsdb-eq-distill-proposal.md`.
```

### .brain/context/current-state.md

Reason: the session recorded failed commands that may deserve a durable trap note [gotcha]

Suggested update:

```md
- Capture the recurring trap exposed while working on "p2p-core: 放行 STD_CANCEL/STD_STOP 并回归 AuthHandshakeQuicTest" only if it will matter again.
- Failed command to inspect: `mvn -q -pl p2p-core test`
```

### .brain/context/current-state.md

Reason: the session still has unresolved verification or execution follow-up [follow_up]

Suggested update:

```md
- Record the unresolved follow-up for "p2p-core: 放行 STD_CANCEL/STD_STOP 并回归 AuthHandshakeQuicTest" only if it should survive this session.
- Failed command still needing follow-up: `mvn -q -pl p2p-core test`
```

### .brain/resources/changes/p2p-core-std-cancel-std-stop-authhandshakequictest.md

Reason: successful verification commands were recorded against the packet-driven work [verification_recipe]

Suggested update:

```md
## Verification for p2p-core: 放行 STD_CANCEL/STD_STOP 并回归 AuthHandshakeQuicTest

- Capture only the commands that proved the work after review.
- `mvn -q -pl p2p-core test`
```
