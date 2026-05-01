# Current State

<!-- brain:begin context-current-state -->
This file is a deterministic snapshot of the repository state at the last refresh.

## Repository

- Project: `p2p-net-storage`
- Root: `.`
- Runtime: `unknown`
- Current branch: `main`
- Default branch: `main`
- Remote: `https://github.com/iamkarlkm/p2p-net-storage`
- Go test files: `0`

## Docs

- `README.md`
<!-- brain:end context-current-state -->

## Local Notes

- Updated: 2026-05-01 11:46:55 +08:00
- RPC unary, health, discover, server stream, event stream, client stream, and bidi stream paths are implemented in `p2p-core`.
- High-signal regression currently passes with `mvn -pl p2p-core -DskipTests=false -Dtest=ServerQuicMessageProcessorTest,RpcCommandHandlersTest test`.
- Real processor-path regression in `ServerQuicMessageProcessorTest` now covers queued-stream race handling, client-stream and bidi-stream transport flow, upload `WINDOW_UPDATE`, concurrent seq isolation, cancel isolation, and minimal fairness.
- Client upload flow control now fails closed instead of hanging: missing `WINDOW_UPDATE` reaches permit timeout, remote `ERROR/CLOSE/cancel` closes the local `OutboundWindow`, and later blocked `send()` exits immediately with the remote reason.
- Delayed `WINDOW_UPDATE` behavior now has both edges covered in `RpcCommandHandlersTest`: a delayed-but-eventually-delivered update unblocks `send()`, while an update replayed only after deadline does not revive an already timed-out sender.
- `AbstractStreamRequestAdapter` now preserves already-arrived stream frames in order and rebuilds per-clone synchronization primitives in `loadParams(...)` to avoid cross-stream starvation after shallow `clone()`.
- `RpcStreamCommandServerHandler` now clears its recycled `session` field in `clear()` so pooled handler instances do not leak stream state across concurrent seq values.
- Upload-side backpressure is still intentionally minimal. The current stack now has much stronger correctness coverage, but long-run stress, partial transport loss, and observability remain the main production-hardening gaps.
