---
updated: "2026-04-30T11:04:53Z"
---
# P2PCommand QUIC RPC

## Verification

- `mvn -pl p2p-core -DskipTests compile`
- `mvn -pl p2p-core -DskipTests=false -Dtest=RpcCommandHandlersTest test`
- `mvn -pl p2p-core -DskipTests=false -Dtest=ServerQuicMessageProcessorTest,RpcCommandHandlersTest test`

## Coverage

- RPC unary, health, discover, and stream handlers.
- Built-in `EchoService`, DFS_MAP `Get/Put/Remove/Range`, PubSub `Publish/Subscribe`, and stream built-ins `StreamService/Collect` plus `StreamService/Chat`.
- Rejected `RPC_EVENT` subscriptions now return structured `RpcFrame` errors instead of legacy `STD_ERROR`.
- `RPC_CONTROL` now accepts structured `CANCEL`, `HEARTBEAT`, and `WINDOW_UPDATE` frames and routes window updates into the active stream controller registry.
- `RPC_EVENT` PubSub subscriptions use the same `WINDOW_UPDATE` mechanism and now chunk oversized events by `maxFrameBytes` before the client reassembles them by `chunk_index/end_of_message`.
- Standard `RPC_STREAM` clients open with a small initial window and batch `WINDOW_UPDATE` by default, enabling the same minimal backpressure loop for range-style streams.
- Shared framing and flow-control internals are now centralized: `RpcFrames` builds chunked `DATA` frames, `RpcFlowWindowState` owns permit/window state, and `RpcQueuedFrameSender` owns permit-aware frame queueing for stream responses.
- `RpcStreamCommandServerHandler` now routes `RPC_STREAM` by `RpcMeta.call_type`, so `SERVER_STREAM`, `CLIENT_STREAM`, and `BIDI_STREAM` share one protocol entrypoint.
- `CLIENT_STREAM` now supports end-to-end aggregation via `RpcClientStreamInvoker/RpcClientStreamSession`; the client opens a stream, sends additional `DATA/CLOSE` frames on the same seq, and consumes the final close-frame payload as the aggregated response.
- `BIDI_STREAM` now supports end-to-end duplex callbacks via `RpcBidiStreamInvoker/RpcBidiStreamSession`; the client can send multiple request messages on one seq while reusing the existing stream observer path for server responses.
- `P2PRpcClient` now exposes generic `clientStream(...)` and `bidiStream(...)` helpers, plus built-in convenience wrappers `streamCollect(...)` and `streamChat(...)`.
- Discover metadata now includes the built-in streaming service with `Collect=CLIENT_STREAM` and `Chat=BIDI_STREAM`.
- Production hardening: `BoundStreamMessageService` exposes the executor chosen by `streamRequest`, and `P2PRpcClient` now reuses that bound executor for follow-up `CLIENT_STREAM/BIDI_STREAM` frames instead of re-polling a possibly different connection.
- Production hardening: client-generated stream `OPEN` frames no longer set `end_of_stream=true`; input completion is now modeled by explicit `CLOSE` or wrapper completion, which removes the earlier semantic mismatch.
- Production hardening: `P2PRpcClient` now offers real client-side stream handles `RpcClientStreamHandle` and `RpcBidiStreamHandle`, so request streams can be sent incrementally with explicit `halfClose` and `cancel` instead of forcing full materialization.
- Production hardening: upload-side minimal backpressure now exists for `CLIENT_STREAM/BIDI_STREAM`. The client honors local outbound permits, `CLIENT_STREAM` `OPEN` frames also carry initial flow control, the server counts inbound payload permits, and batched `WINDOW_UPDATE` frames reopen the upload window.
- Production hardening: upload permit waiting is now bounded by deadline and is cancel-aware; missing `WINDOW_UPDATE` no longer causes an unbounded client-side hang.
- Real transport-style regression coverage now includes `ServerQuicMessageProcessorTest`: `AbstractStreamRequestAdapter` no longer keeps only one pending frame. It now preserves already-arrived stream messages in order, preventing the classic race where a later frame overwrites an earlier one before the worker thread consumes it.
- Remaining gap: upload-side backpressure is still a minimal implementation; it now fails closed instead of hanging, but still needs real transport integration and stress coverage before the stack should be called fully production-complete.
