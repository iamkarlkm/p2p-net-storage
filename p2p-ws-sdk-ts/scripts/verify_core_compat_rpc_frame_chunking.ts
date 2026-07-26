import protobuf from "protobufjs"
import path from "node:path"

const root = new protobuf.Root()
const repoRoot = path.resolve(import.meta.dirname, "..", "..")
const protoDir = path.resolve(repoRoot, "p2p-core", "src", "main", "proto")
await root.load([path.join(protoDir, "p2p_rpc.proto")], { keepCase: true })
root.resolveAll()

const RpcFrame = root.lookupType("p2p.rpc.v1.RpcFrame")

const payload = Buffer.alloc(10_000, 7)
const maxFrameBytes = 1024
const chunks: Uint8Array[] = []
for (let off = 0; off < payload.length; off += maxFrameBytes) chunks.push(payload.subarray(off, Math.min(payload.length, off + maxFrameBytes)))

for (let i = 0; i < chunks.length; i++) {
  const b = RpcFrame.encode({
    frame_type: 2,
    meta: { request_id: "1" },
    payload: chunks[i],
    chunk_index: i,
    end_of_message: i === chunks.length - 1,
    end_of_stream: false,
  }).finish()
  const back = RpcFrame.decode(b) as any
  if (back.chunk_index !== i) throw new Error("chunk_index mismatch")
  if (Boolean(back.end_of_message) !== (i === chunks.length - 1)) throw new Error("end_of_message mismatch")
  if (Buffer.from(back.payload).length !== chunks[i]!.length) throw new Error("payload length mismatch")
}

console.log("ok=1")
