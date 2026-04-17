import fs from "node:fs"
import path from "node:path"
import { xorNoWrap } from "../src/xor.js"
import { decodeFrame } from "../src/frame.js"

function hexToBytes(s: string): Uint8Array {
  const t = s.trim()
  if (t.length % 2 !== 0) throw new Error("hex length must be even")
  const out = new Uint8Array(t.length / 2)
  for (let i = 0; i < out.length; i++) {
    const hi = parseInt(t[i * 2], 16)
    const lo = parseInt(t[i * 2 + 1], 16)
    if (Number.isNaN(hi) || Number.isNaN(lo)) throw new Error("invalid hex")
    out[i] = (hi << 4) | lo
  }
  return out
}

function bytesToHex(b: Uint8Array): string {
  return Buffer.from(b).toString("hex")
}

const root = path.resolve(import.meta.dirname, "..", "..")
const vecPath = path.resolve(root, "p2p-ws-protocol", "test-vectors", "xor_vector_001.json")
const v = JSON.parse(fs.readFileSync(vecPath, "utf-8"))

const key = hexToBytes(v.keyfile_bytes_hex)
const plain = hexToBytes(v.plain_hex)
const got = xorNoWrap(plain, key, v.offset)
const gotHex = bytesToHex(got)
const expected = String(v.cipher_hex).toLowerCase()

if (gotHex !== expected) {
  throw new Error(`mismatch: got=${gotHex} expected=${expected}`)
}

console.log("ok=1")

const framePath = path.resolve(root, "p2p-ws-protocol", "test-vectors", "frame_vector_001.json")
const fvec = JSON.parse(fs.readFileSync(framePath, "utf-8"))
const ws = hexToBytes(fvec.ws_binary_payload_hex)
const decoded = decodeFrame(ws)
if (decoded.header.length !== fvec.header.length_u32) throw new Error("frame header length mismatch")
if (decoded.header.magic !== fvec.header.magic_u16) throw new Error("frame header magic mismatch")
if (decoded.header.version !== fvec.header.version_u8) throw new Error("frame header version mismatch")
if (decoded.header.flags !== fvec.header.flags_u8) throw new Error("frame header flags mismatch")
if (bytesToHex(decoded.cipherPayload) !== String(fvec.cipher_payload_hex).toLowerCase()) throw new Error("frame payload mismatch")

console.log("ok=2")
