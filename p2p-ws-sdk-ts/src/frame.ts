export type WireHeader = {
  length: number
  magic: number
  version: number
  flags: number
}

export type WireFrame = {
  header: WireHeader
  cipherPayload: Uint8Array
}

export const HEADER_LEN = 8

export function decodeFrame(wsBinaryPayload: Uint8Array): WireFrame {
  if (wsBinaryPayload.length < HEADER_LEN) {
    throw new Error("invalid frame")
  }
  const dv = new DataView(wsBinaryPayload.buffer, wsBinaryPayload.byteOffset, wsBinaryPayload.byteLength)
  const length = dv.getUint32(0, false)
  const magic = dv.getUint16(4, false)
  const version = dv.getUint8(6)
  const flags = dv.getUint8(7)
  const cipherPayload = wsBinaryPayload.subarray(HEADER_LEN)
  return { header: { length, magic, version, flags }, cipherPayload }
}

export function encodeFrame(header: WireHeader, cipherPayload: Uint8Array): Uint8Array {
  const out = new Uint8Array(HEADER_LEN + cipherPayload.length)
  const dv = new DataView(out.buffer, out.byteOffset, out.byteLength)
  dv.setUint32(0, header.length >>> 0, false)
  dv.setUint16(4, header.magic & 0xffff, false)
  dv.setUint8(6, header.version & 0xff)
  dv.setUint8(7, header.flags & 0xff)
  out.set(cipherPayload, HEADER_LEN)
  return out
}

