---
title: Window Update Send
updated: "2026-05-01T11:46:55+08:00"
---

## Verification

- `mvn -pl p2p-core -DskipTests=false -Dtest=RpcCommandHandlersTest#clientRequestStreamSendStopsWaitingWhenServerReturnsError test`
- `mvn -pl p2p-core -DskipTests=false -Dtest=RpcCommandHandlersTest#bidiStreamSendStopsWhenServerClosesNormally test`
- `mvn -pl p2p-core -DskipTests=false -Dtest=RpcCommandHandlersTest#clientRequestStreamSendWaitsForDelayedWindowUpdateAndThenRecovers test`
- `mvn -pl p2p-core -DskipTests=false -Dtest=RpcCommandHandlersTest#clientRequestStreamSendTimesOutWhenDelayedWindowUpdateArrivesTooLate test`
- `mvn -pl p2p-core -DskipTests=false -Dtest=ServerQuicMessageProcessorTest,RpcCommandHandlersTest test`

## Notes

- Client upload-side flow control now reacts to remote termination as well as local cancel: `P2PRpcClient.MessageStreamAdapter` closes the local `OutboundWindow` on remote `ERROR`, remote `CLOSE`, and cancel signals.
- `RpcCommandHandlersTest#clientRequestStreamSendStopsWaitingWhenServerReturnsError` verifies that a blocked `send()` fails fast with the remote error instead of waiting for permit timeout after the server has already terminated the stream.
- `RpcCommandHandlersTest#bidiStreamSendStopsWhenServerClosesNormally` verifies the non-error path: after a remote normal `CLOSE`, later `send()` calls fail fast with `RPC request stream closed by server` even if local permits remain.
- `RpcTestRpcStreamMessageService` now has a small buffered-outbound hook in tests so delayed `WINDOW_UPDATE` delivery can be simulated without changing production RPC code.
- `RpcCommandHandlersTest#clientRequestStreamSendWaitsForDelayedWindowUpdateAndThenRecovers` verifies the recovery path: when `WINDOW_UPDATE` is delayed but eventually replayed, a blocked `send()` keeps waiting, resumes after replay, and the collect stream still completes with the full payload set.
- `RpcCommandHandlersTest#clientRequestStreamSendTimesOutWhenDelayedWindowUpdateArrivesTooLate` verifies the opposite edge: if delayed `WINDOW_UPDATE` arrives only after the waiting sender has already crossed its deadline, the blocked `send()` still fails with permit timeout and is not revived by the late replay.
