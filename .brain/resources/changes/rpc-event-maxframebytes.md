---
updated: "2026-04-29T20:35:15Z"
---
# RPC_EVENT maxFrameBytes

## Verification

- `mvn -pl p2p-core -DskipTests=false -Dtest=RpcCommandHandlersTest#rpcEventMaxFrameBytesChunksOversizedPayload+rpcSubscribeReassemblesChunkedEvents test`
- `mvn -pl p2p-core -DskipTests=false -Dtest=RpcCommandHandlersTest test`

## Coverage

- `RpcPubSubBroker` now chunks oversized `PubSubEvent` payloads by `maxFrameBytes` instead of returning `PAYLOAD_TOO_LARGE`.
- Chunked `RPC_EVENT` frames preserve order through the existing permit queue and continue to honor `WINDOW_UPDATE` and `maxInflightFrames`.
- `P2PRpcClient.rpcSubscribe(...)` reuses the shared `chunk_index/end_of_message` reassembly path, so observers still receive one logical `PubSubEvent`.
- Regression coverage includes direct broker chunk emission and end-to-end client reassembly.
