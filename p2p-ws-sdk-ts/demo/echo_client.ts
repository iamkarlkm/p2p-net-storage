import fs from "node:fs"
import path from "node:path"

import { encodeFrame, decodeFrame } from "../src/frame.js"
import { xorNoWrap } from "../src/xor.js"
import { loadProtoRoot } from "../src/proto.js"
import { encodeWrapper, decodeWrapper } from "../src/wrapper.js"
import crypto from "node:crypto"
import { rsaOaepSha256Decrypt } from "../src/handshake.js"
import { sha256Bytes } from "../src/keyid.js"
import { loadClientConfig, parseIntMaybeHex } from "../src/config.js"

const cfgPath = process.argv[2] ?? path.resolve("..", "p2p-ws-protocol", "examples", "client.yaml")
const cfg = loadClientConfig(cfgPath)
const url = cfg.ws_url

const keyfileAbs = path.resolve(path.dirname(cfgPath), cfg.keyfile_path)
const fd = fs.openSync(keyfileAbs, "r")
const keyId = await new Promise<Uint8Array>((resolve, reject) => {
  const h = crypto.createHash("sha256")
  const s = fs.createReadStream(keyfileAbs)
  s.on("data", (b) => h.update(b))
  s.on("error", reject)
  s.on("end", () => resolve(new Uint8Array(h.digest())))
})
const expectedKeyIdHex = (cfg.key_id_sha256_hex ?? "").trim().toLowerCase()
if (expectedKeyIdHex) {
  const gotHex = Buffer.from(keyId).toString("hex")
  if (gotHex !== expectedKeyIdHex) {
    throw new Error(`key_id_sha256_hex mismatch: got=${gotHex} expected=${expectedKeyIdHex}`)
  }
}

const root = await loadProtoRoot()
const Hand = root.lookupType("p2pws.Hand")
const HandAckPlain = root.lookupType("p2pws.HandAckPlain")
const CryptUpdate = root.lookupType("p2pws.CryptUpdate")

let privateKeyPem: string
let clientPubDer: Buffer
const privPathRaw = (cfg.rsa_private_key_pem_path ?? "").trim()
if (privPathRaw) {
  const privAbs = path.resolve(path.dirname(cfgPath), privPathRaw)
  privateKeyPem = fs.readFileSync(privAbs, "utf-8")
  const privObj = crypto.createPrivateKey(privateKeyPem)
  const pubObj = crypto.createPublicKey(privObj)
  clientPubDer = pubObj.export({ type: "spki", format: "der" }) as Buffer
} else {
  const kp = crypto.generateKeyPairSync("rsa", { modulusLength: 2048 })
  privateKeyPem = kp.privateKey.export({ type: "pkcs8", format: "pem" }) as string
  clientPubDer = kp.publicKey.export({ type: "spki", format: "der" }) as Buffer
}

const ws = new WebSocket(url)
ws.binaryType = "arraybuffer"

let offset = -1
const magic = parseIntMaybeHex(cfg.magic, 0x1234)
const version = cfg.version ?? 1
const flagsPlain = cfg.flags_plain ?? 4
const flagsEncrypted = cfg.flags_encrypted ?? 5
const maxFramePayload = cfg.max_frame_payload ?? 4 * 1024 * 1024

function xorWithFile(data: Uint8Array, off: number): Uint8Array {
  const slice = Buffer.allocUnsafe(data.length)
  fs.readSync(fd, slice, 0, data.length, off)
  return xorNoWrap(data, new Uint8Array(slice), 0)
}

ws.onopen = () => {
  const handData = Hand.encode(
    Hand.create({
      client_pubkey: clientPubDer,
      key_ids: [Buffer.from(keyId)],
      max_frame_payload: maxFramePayload,
      client_id: cfg.user_id,
    }),
  ).finish()

  const wrapper = encodeWrapper(root, { seq: 1, command: -10001, data: handData })
  const header = { length: wrapper.length, magic, version, flags: flagsPlain }
  const frame = encodeFrame(header, wrapper)
  ws.send(frame)
}

ws.onmessage = (ev) => {
  const wsBytes = new Uint8Array(ev.data as ArrayBuffer)
  const f = decodeFrame(wsBytes)
  const cipher = f.cipherPayload
  const plainPayload = offset >= 0 ? xorWithFile(cipher, offset) : cipher
  const w = decodeWrapper(root, plainPayload)

  if (w.command === -10002) {
    const enc = w.data ?? new Uint8Array()
    const handAckBytes = rsaOaepSha256Decrypt(privateKeyPem, enc)
    const ack = HandAckPlain.decode(handAckBytes) as any
    offset = Number(ack.offset)

    const msg = Buffer.from("hello")
    const echo = encodeWrapper(root, { seq: 2, command: 1, data: new Uint8Array(msg) })
    const echoCipher = xorWithFile(echo, offset)
    const header = { length: echoCipher.length, magic, version, flags: flagsEncrypted }
    ws.send(encodeFrame(header, echoCipher))
    return
  }

  if (w.command === -10010) {
    const cu = CryptUpdate.decode(w.data ?? new Uint8Array()) as any
    offset = Number(cu.offset)
    return
  }

  if (w.command === 1 && w.seq === 2) {
    const got = Buffer.from(w.data ?? new Uint8Array()).toString()
    console.log("echo=" + got)
    fs.closeSync(fd)
    ws.close()
  }
}

ws.onerror = (e) => {
  console.error(e)
  process.exit(1)
}
