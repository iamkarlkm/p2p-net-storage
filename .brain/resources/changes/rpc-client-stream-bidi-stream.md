---
title: RPC Client Stream Bidi Stream
updated: "2026-04-30T17:49:33Z"
---
## Verification

- `mvn -pl p2p-core -DskipTests=false -Dtest=ServerQuicMessageProcessorTest#streamMessagesQueuedBeforeAwaitAreStillProcessed test`
- `mvn -pl p2p-core -DskipTests=false -Dtest=ServerQuicMessageProcessorTest#rpcClientStreamCanFlowThroughQuicProcessor+rpcBidiStreamCanFlowThroughQuicProcessor test`
- `mvn -pl p2p-core -DskipTests=false -Dtest=ServerQuicMessageProcessorTest#rpcClientStreamEmitsWindowUpdateThroughQuicProcessor+rpcBidiStreamEmitsWindowUpdateThroughQuicProcessor test`
- `mvn -pl p2p-core -DskipTests=false -Dtest=ServerQuicMessageProcessorTest#rpcClientStreamsStayIsolatedAcrossConcurrentSeqs+rpcBidiStreamsStayIsolatedAcrossConcurrentSeqs test`
- `mvn -pl p2p-core -DskipTests=false -Dtest=ServerQuicMessageProcessorTest#rpcClientStreamWindowUpdatesStayIsolatedAcrossConcurrentSeqs+rpcBidiStreamWindowUpdatesStayIsolatedAcrossConcurrentSeqs test`
- `mvn -pl p2p-core -DskipTests=false -Dtest=ServerQuicMessageProcessorTest#rpcClientStreamCancelStaysIsolatedAcrossConcurrentSeqs+rpcBidiStreamCancelStaysIsolatedAcrossConcurrentSeqs test`
- `mvn -pl p2p-core -DskipTests=false -Dtest=ServerQuicMessageProcessorTest#rpcBidiLowTrafficSeqIsNotStarvedByBusySeq test`
- `mvn -pl p2p-core -DskipTests=false -Dtest=ServerQuicMessageProcessorTest#rpcClientStreamLowTrafficSeqIsNotStarvedByBusySeq test`
- `mvn -pl p2p-core -DskipTests=false -Dtest=RpcCommandHandlersTest#clientRequestStreamSendStopsWaitingWhenServerReturnsError test`
- `mvn -pl p2p-core -DskipTests=false -Dtest=ServerQuicMessageProcessorTest,RpcCommandHandlersTest test`

## Notes

- Real transport-style stream regression now exercises `ServerQuicMessageProcessor` directly instead of relying only on in-memory loopback RPC tests.
- `AbstractStreamRequestAdapter` now preserves already-arrived stream frames in order; a later frame no longer overwrites an earlier one before the worker thread consumes it.
- The test-only `FakeExecutor` used by `ServerQuicMessageProcessorTest` must override `isActive()` because the fixture does not bind a real Netty channel.
- `ServerQuicMessageProcessorTest` now also covers built-in `CLIENT_STREAM` `Collect` and `BIDI_STREAM` `Chat`, verifying that `RPC_STREAM` can flow through the real processor path and emit the expected `DATA/CLOSE` frames.
- The same processor-level suite now verifies upload-side backpressure signaling for both built-ins: after inbound `CLIENT_STREAM/BIDI_STREAM` payload consumption, the server emits `WINDOW_UPDATE` frames through the real `RPC_STREAM` transport path.
- The same suite now verifies concurrent stream isolation: interleaved `CLIENT_STREAM` and `BIDI_STREAM` requests on different `seq` values keep their responses separated and do not cross-contaminate state.
- The same suite now also verifies concurrent `WINDOW_UPDATE` isolation: interleaved upload-side replenishment frames remain scoped to the originating `seq` and do not leak across concurrent streams.
- The same suite now verifies concurrent cancel isolation: canceling one `seq` does not prevent another in-flight `CLIENT_STREAM` or `BIDI_STREAM` from continuing to emit `DATA/CLOSE`.
- The same suite now verifies a minimal fairness property: a low-traffic `BIDI_STREAM` still receives its own `DATA` response before a busy peer stream needs to finish.
- The same suite now verifies the symmetric fairness property for `CLIENT_STREAM`: a low-traffic collect stream can still finish and receive its `CLOSE` response while a busy peer stream remains open.
- Client-side upload flow control now also reacts to remote termination instead of only local cancel/timeout: `MessageStreamAdapter` closes its `OutboundWindow` on remote `ERROR`, `CLOSE`, and cancel signals, so a blocked `send()` exits immediately once the server has already terminated the stream.
- `RpcCommandHandlersTest#clientRequestStreamSendStopsWaitingWhenServerReturnsError` now covers that linkage by forcing a server-side `CLIENT_STREAM` error after the first payload and asserting the second `send()` fails quickly with the remote error instead of waiting for permit timeout.
- A real bug surfaced under full-suite load: `RpcStreamCommandServerHandler` is pooled/cloned, but its per-stream `session` field was not cleared on recycle. Overriding `clear()` to null the session fixes cross-stream state leakage in concurrent bidi tests.
- Another real concurrency bug surfaced while adding fairness coverage: `AbstractStreamRequestAdapter` relies on `Object.clone()` and was shallow-copying its `ReentrantLock`/`Condition`. Reinitializing them in `loadParams(...)` prevents different cloned stream handlers from sharing synchronization primitives and starving unrelated seqs.
- The processor-level wait helpers still use a wider timeout budget because full-suite runs can be slower than single-test runs under the shared executor pool.
