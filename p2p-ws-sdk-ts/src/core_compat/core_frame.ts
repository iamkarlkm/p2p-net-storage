import { Buffer } from "node:buffer"

export function encodeCoreFrame(magic: number, payload: Uint8Array): Buffer {
  const buf = Buffer.allocUnsafe(8 + payload.length)
  buf.writeInt32BE(payload.length, 0)
  buf.writeInt32BE(magic | 0, 4)
  Buffer.from(payload).copy(buf, 8)
  return buf
}

export function decodeCoreFrame(frame: Uint8Array): { magic: number; payload: Uint8Array } {
  if (frame.byteLength < 8) throw new Error("frame too short")
  const buf = Buffer.from(frame)
  const len = buf.readInt32BE(0)
  const magic = buf.readInt32BE(4)
  if (len < 0 || 8 + len > buf.length) throw new Error("bad length")
  return { magic, payload: buf.subarray(8, 8 + len) }
}

export function decodeAllCoreFrames(frame: Uint8Array): Array<{ magic: number; payload: Uint8Array }> {
  const buf = Buffer.from(frame)
  const out: Array<{ magic: number; payload: Uint8Array }> = []
  let pos = 0
  while (pos < buf.length) {
    if (pos + 8 > buf.length) throw new Error("frame truncated")
    const len = buf.readInt32BE(pos)
    const magic = buf.readInt32BE(pos + 4)
    const end = pos + 8 + len
    if (len < 0 || end > buf.length) throw new Error("bad length")
    out.push({ magic, payload: buf.subarray(pos + 8, end) })
    pos = end
  }
  return out
}
