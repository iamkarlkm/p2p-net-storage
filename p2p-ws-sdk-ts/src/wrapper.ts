import protobuf from "protobufjs"

export type P2PWrapper = {
  seq: number
  command: number
  data?: Uint8Array
  headers?: Record<string, string>
}

export function encodeWrapper(root: protobuf.Root, w: P2PWrapper): Uint8Array {
  const T = root.lookupType("p2pws.P2PWrapper")
  const msg = T.create(w as unknown as Record<string, unknown>)
  return T.encode(msg).finish()
}

export function decodeWrapper(root: protobuf.Root, data: Uint8Array): P2PWrapper {
  const T = root.lookupType("p2pws.P2PWrapper")
  const msg = T.decode(data) as unknown as { [k: string]: unknown }
  return {
    seq: Number(msg["seq"] ?? 0),
    command: Number(msg["command"] ?? 0),
    data: msg["data"] as Uint8Array | undefined,
    headers: msg["headers"] as Record<string, string> | undefined,
  }
}

