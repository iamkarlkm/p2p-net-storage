import { Buffer } from "node:buffer"

function encodeVarint(value: bigint): Buffer {
  let v = value & 0xffff_ffff_ffff_ffffn
  const out: number[] = []
  while (true) {
    const b = Number(v & 0x7fn)
    v >>= 7n
    if (v !== 0n) out.push(0x80 | b)
    else {
      out.push(b)
      break
    }
  }
  return Buffer.from(out)
}

function decodeVarint(data: Uint8Array, pos: number): { value: bigint; pos: number } {
  let shift = 0n
  let out = 0n
  while (true) {
    if (pos >= data.length) throw new Error("varint truncated")
    const b = BigInt(data[pos]!)
    pos += 1
    out |= (b & 0x7fn) << shift
    if ((b & 0x80n) === 0n) return { value: out, pos }
    shift += 7n
    if (shift > 70n) throw new Error("varint too long")
  }
}

function key(fieldNo: number, wireType: number): Buffer {
  return encodeVarint(BigInt((fieldNo << 3) | wireType))
}

function writeInt32(fieldNo: number, value: number): Buffer {
  return Buffer.concat([key(fieldNo, 0), encodeVarint(BigInt(value >>> 0))])
}

function writeInt64(fieldNo: number, value: bigint): Buffer {
  return Buffer.concat([key(fieldNo, 0), encodeVarint(value)])
}

function writeBool(fieldNo: number, value: boolean): Buffer {
  return Buffer.concat([key(fieldNo, 0), encodeVarint(value ? 1n : 0n)])
}

function writeBytes(fieldNo: number, value: Uint8Array): Buffer {
  const v = Buffer.from(value)
  return Buffer.concat([key(fieldNo, 2), encodeVarint(BigInt(v.length)), v])
}

function writeString(fieldNo: number, value: string): Buffer {
  return writeBytes(fieldNo, Buffer.from(value, "utf-8"))
}

type Field = { wt: number; raw: Uint8Array }

function readFields(data: Uint8Array): Map<number, Field[]> {
  const out = new Map<number, Field[]>()
  let pos = 0
  while (pos < data.length) {
    const k = decodeVarint(data, pos)
    pos = k.pos
    const fieldNo = Number(k.value >> 3n)
    const wt = Number(k.value & 0x7n)
    let raw: Uint8Array
    if (wt === 0) {
      const v = decodeVarint(data, pos)
      pos = v.pos
      raw = encodeVarint(v.value)
    } else if (wt === 2) {
      const ln = decodeVarint(data, pos)
      pos = ln.pos
      const end = pos + Number(ln.value)
      if (end > data.length) throw new Error("len delimited truncated")
      raw = data.slice(pos, end)
      pos = end
    } else {
      throw new Error(`unsupported wire_type=${wt}`)
    }
    const arr = out.get(fieldNo) ?? []
    arr.push({ wt, raw })
    out.set(fieldNo, arr)
  }
  return out
}

function readInt(fields: Map<number, Field[]>, fieldNo: number, def: number = 0): number {
  const items = fields.get(fieldNo)
  if (!items || items.length === 0) return def
  const it = items[0]!
  if (it.wt !== 0) return def
  return Number(decodeVarint(it.raw, 0).value)
}

function readBool(fields: Map<number, Field[]>, fieldNo: number, def: boolean = false): boolean {
  return readInt(fields, fieldNo, def ? 1 : 0) !== 0
}

function readBytes(fields: Map<number, Field[]>, fieldNo: number): Uint8Array {
  const items = fields.get(fieldNo)
  if (!items || items.length === 0) return new Uint8Array()
  const it = items[0]!
  if (it.wt !== 2) return new Uint8Array()
  return it.raw
}

function readString(fields: Map<number, Field[]>, fieldNo: number): string {
  return Buffer.from(readBytes(fields, fieldNo)).toString("utf-8")
}

export type P2PWrapper = { seq: number; commandOrdinal: number; data: Uint8Array }

export function encodeP2PWrapper(w: P2PWrapper): Uint8Array {
  const parts: Buffer[] = [writeInt32(1, w.seq), writeInt32(2, w.commandOrdinal)]
  if (w.data.byteLength > 0) parts.push(writeBytes(3, w.data))
  return Buffer.concat(parts)
}

export function decodeP2PWrapper(payload: Uint8Array): P2PWrapper {
  const f = readFields(payload)
  return { seq: readInt(f, 1, 0), commandOrdinal: readInt(f, 2, 0), data: readBytes(f, 3) }
}

export type StreamP2PWrapper = {
  seq: number
  commandOrdinal: number
  data: Uint8Array
  index: number
  completed: boolean
  canceled: boolean
}

export function encodeStreamP2PWrapper(w: StreamP2PWrapper): Uint8Array {
  const parts: Buffer[] = [writeInt32(1, w.seq), writeInt32(2, w.commandOrdinal), writeInt32(4, w.index)]
  if (w.data.byteLength > 0) parts.push(writeBytes(3, w.data))
  if (w.completed) parts.push(writeBool(5, true))
  if (w.canceled) parts.push(writeBool(6, true))
  return Buffer.concat(parts)
}

export function decodeStreamP2PWrapper(payload: Uint8Array): StreamP2PWrapper {
  const f = readFields(payload)
  return {
    seq: readInt(f, 1, 0),
    commandOrdinal: readInt(f, 2, 0),
    data: readBytes(f, 3),
    index: readInt(f, 4, 0),
    completed: readBool(f, 5, false),
    canceled: readBool(f, 6, false),
  }
}

export type HandshakeRequest = {
  userId: string
  timestamp: bigint
  nonce: Uint8Array
  xorKeyLength: number
  encryptedXorKey: Uint8Array
  signature: Uint8Array
}

export function encodeHandshakeRequest(req: HandshakeRequest): Uint8Array {
  const parts: Buffer[] = []
  if (req.userId) parts.push(writeString(1, req.userId))
  parts.push(writeInt64(2, req.timestamp))
  if (req.nonce.byteLength > 0) parts.push(writeBytes(3, req.nonce))
  parts.push(writeInt32(4, req.xorKeyLength))
  if (req.encryptedXorKey.byteLength > 0) parts.push(writeBytes(5, req.encryptedXorKey))
  if (req.signature.byteLength > 0) parts.push(writeBytes(6, req.signature))
  return Buffer.concat(parts)
}

export type HandshakeResponse = {
  ok: boolean
  error: string
  userId: string
  serverTime: number
  nonce: Uint8Array
  xorKeyLength: number
  encryptedSeed: Uint8Array
  signature: Uint8Array
}

export function decodeHandshakeResponse(payload: Uint8Array): HandshakeResponse {
  const f = readFields(payload)
  return {
    ok: readBool(f, 1, false),
    error: readString(f, 2),
    userId: readString(f, 3),
    serverTime: readInt(f, 4, 0),
    nonce: readBytes(f, 5),
    xorKeyLength: readInt(f, 6, 0),
    encryptedSeed: readBytes(f, 7),
    signature: readBytes(f, 8),
  }
}

export type LoginRequest = { userId: string; timestamp: bigint; signature: Uint8Array }

export function encodeLoginRequest(req: LoginRequest): Uint8Array {
  const parts: Buffer[] = []
  if (req.userId) parts.push(writeString(1, req.userId))
  parts.push(writeInt64(2, req.timestamp))
  if (req.signature.byteLength > 0) parts.push(writeBytes(3, req.signature))
  return Buffer.concat(parts)
}

export type LoginResponse = { ok: boolean; error: string; userId: string; serverTime: number; signature: Uint8Array }

export function decodeLoginResponse(payload: Uint8Array): LoginResponse {
  const f = readFields(payload)
  return {
    ok: readBool(f, 1, false),
    error: readString(f, 2),
    userId: readString(f, 3),
    serverTime: readInt(f, 4, 0),
    signature: readBytes(f, 5),
  }
}
