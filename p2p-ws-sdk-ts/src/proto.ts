import protobuf from "protobufjs"
import path from "node:path"

const ROOT = path.resolve(import.meta.dirname, "..", "..")
const PROTO_DIR = path.resolve(ROOT, "p2p-ws-protocol", "proto")

export async function loadProtoRoot(): Promise<protobuf.Root> {
  const root = new protobuf.Root()
  await root.load([path.join(PROTO_DIR, "p2p_wrapper.proto"), path.join(PROTO_DIR, "p2p_control.proto"), path.join(PROTO_DIR, "p2p_data.proto")], { keepCase: true })
  root.resolveAll()
  return root
}

