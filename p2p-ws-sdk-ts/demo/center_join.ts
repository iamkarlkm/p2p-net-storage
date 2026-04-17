import fs from "node:fs"
import path from "node:path"
import crypto from "node:crypto"

import { decodeFrame, encodeFrame } from "../src/frame.js"
import { loadProtoRoot } from "../src/proto.js"
import { decodeWrapper, encodeWrapper } from "../src/wrapper.js"
import { loadClientConfig, parseIntMaybeHex } from "../src/config.js"
import { rsaOaepSha256Decrypt } from "../src/handshake.js"

const cfgPath = process.argv[2] ?? path.resolve("..", "p2p-ws-protocol", "examples", "center_client.yaml")
const cfg = loadClientConfig(cfgPath)

const root = await loadProtoRoot()
const Hand = root.lookupType("p2pws.Hand")
const HandAckPlain = root.lookupType("p2pws.HandAckPlain")
const CenterHelloBody = root.lookupType("p2pws.CenterHelloBody")
const CenterHello = root.lookupType("p2pws.CenterHello")
const CenterHelloAck = root.lookupType("p2pws.CenterHelloAck")
const GetNode = root.lookupType("p2pws.GetNode")
const GetNodeAck = root.lookupType("p2pws.GetNodeAck")

const magic = parseIntMaybeHex(cfg.magic, 0x1234)
const version = cfg.version ?? 1
const flagsPlain = cfg.flags_plain ?? 4
const flagsEncrypted = cfg.flags_encrypted ?? 5
const maxFramePayload = cfg.max_frame_payload ?? 4 * 1024 * 1024
const cryptoMode = (cfg.crypto_mode ?? "KEYFILE_XOR_RSA_OAEP").trim()

const keyfileAbs = path.resolve(path.dirname(cfgPath), cfg.keyfile_path)
const fd = fs.openSync(keyfileAbs, "r")
const cachePathRaw = (cfg.presence_cache_path ?? "").trim()
const cachePath = cachePathRaw ? path.resolve(path.dirname(cfgPath), cachePathRaw) : ""

function sha256FileHex(p: string): Promise<string> {
  return new Promise((resolve, reject) => {
    const h = crypto.createHash("sha256")
    const s = fs.createReadStream(p)
    s.on("data", (b) => h.update(b))
    s.on("error", reject)
    s.on("end", () => resolve(h.digest("hex")))
  })
}

const keyHex = await sha256FileHex(keyfileAbs)
const expectedKeyHex = (cfg.key_id_sha256_hex ?? "").trim().toLowerCase()
if (expectedKeyHex && expectedKeyHex !== keyHex) {
  throw new Error(`key_id_sha256_hex mismatch: got=${keyHex} expected=${expectedKeyHex}`)
}
const keyId = Buffer.from(keyHex, "hex")

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

const nodeId64Str = String(parseInt(cfg.user_id, 10) || 1)

let offset = -1
let stage = 0
let cachedObserved: { transport: string; addr: string } | null = null
let cachedObservedKey = ""
let lastAck: any | null = null
let renewTimer: NodeJS.Timeout | null = null
const renewSec = typeof cfg.renew_seconds === "number" ? cfg.renew_seconds : 0
let renewLeft = typeof cfg.renew_count === "number" ? cfg.renew_count : renewSec > 0 ? 3 : 0
let queriedOnce = false

if (cachePath) {
  try {
    const txt = fs.readFileSync(cachePath, "utf-8")
    const j = JSON.parse(txt) as any
    const t = String(j?.observed_endpoint?.transport ?? "")
    const a = String(j?.observed_endpoint?.addr ?? "")
    if (t && a) {
      cachedObserved = { transport: t, addr: a }
      cachedObservedKey = t + "|" + a
    }
  } catch {}
}

function xorWithFile(data: Uint8Array, off: number): Uint8Array {
  const slice = Buffer.allocUnsafe(data.length)
  let pos = 0
  while (pos < slice.length) {
    const n = fs.readSync(fd, slice, pos, slice.length - pos, off + pos)
    if (n <= 0) throw new Error("read keyfile failed")
    pos += n
  }
  const out = new Uint8Array(data.length)
  for (let i = 0; i < data.length; i++) out[i] = data[i] ^ slice[i]
  return out
}

function applyObservedIpToEndpoints(observedAddr: string, endpoints: any[]): any[] {
  const ip = String(observedAddr).split(":")[0]
  if (!ip) return endpoints
  const out: any[] = []
  for (const e of endpoints) {
    const t = String(e?.transport ?? "")
    const addr = String(e?.addr ?? "")
    const parts = addr.split(":")
    if (parts.length >= 2) {
      out.push({ transport: t, addr: ip + ":" + parts.slice(1).join(":") })
    } else {
      out.push({ transport: t, addr })
    }
  }
  return out
}

const ws = new WebSocket(cfg.ws_url)
ws.binaryType = "arraybuffer"

ws.onopen = () => {
  console.log("open")
  const initialReported = Array.isArray(cfg.reported_endpoints) ? cfg.reported_endpoints.slice() : []
  const initialWithCache = cachedObserved ? applyObservedIpToEndpoints(cachedObserved.addr, initialReported) : initialReported
  const handData = Hand.encode(
    Hand.create({
      client_pubkey: clientPubDer,
      key_ids: [keyId],
      max_frame_payload: maxFramePayload,
      client_id: cfg.user_id,
    }),
  ).finish()
  const wrapper = encodeWrapper(root, { seq: 1, command: -10001, data: handData })
  ws.send(encodeFrame({ length: wrapper.length, magic, version, flags: flagsPlain }, wrapper))
  stage = 1
  ;(ws as any).__reported = initialWithCache
}

ws.onmessage = (ev) => {
  const wsBytes = new Uint8Array(ev.data as ArrayBuffer)
  const f = decodeFrame(wsBytes)
  const cipher = f.cipherPayload
  const plainPayload = offset >= 0 ? xorWithFile(cipher, offset) : cipher
  const w = decodeWrapper(root, plainPayload)
  console.log("recv.cmd=" + w.command)

  if (w.command === -10002) {
    const enc = w.data ?? new Uint8Array()
    const handAckBytes = rsaOaepSha256Decrypt(privateKeyPem, enc)
    const ack = HandAckPlain.decode(handAckBytes) as any
    offset = Number(ack.offset)
    stage = 2

    const caps = { max_frame_payload: maxFramePayload, magic, version, flags_plain: flagsPlain, flags_encrypted: flagsEncrypted }
    const reported = ((ws as any).__reported ?? []) as any[]
    const bodyMsg = CenterHelloBody.create({
      node_id64: nodeId64Str,
      pubkey_spki_der: clientPubDer,
      reported_endpoints: reported,
      caps,
      timestamp_ms: String(Date.now()),
      crypto_mode: cryptoMode,
    })
    const bodyBytes = CenterHelloBody.encode(bodyMsg).finish()
    const sig = crypto.sign("RSA-SHA256", Buffer.from(bodyBytes), crypto.createPrivateKey(privateKeyPem))
    const hello = CenterHello.encode(CenterHello.create({ body: bodyMsg, signature: sig })).finish()

    const helloWrap = encodeWrapper(root, { seq: 2, command: -11001, data: hello })
    const helloCipher = xorWithFile(helloWrap, offset)
    ws.send(encodeFrame({ length: helloCipher.length, magic, version, flags: flagsEncrypted }, helloCipher))
    stage = 3
    return
  }

  if (w.command === -11002) {
    const ack = CenterHelloAck.decode(w.data ?? new Uint8Array()) as any
    lastAck = ack
    const oldKey = cachedObservedKey
    const nodeKey = Buffer.from(ack.node_key as Uint8Array).toString("hex")
    console.log("center_ack.node_key=" + nodeKey)
    if (ack.observed_endpoint) {
      const oe = ack.observed_endpoint as any
      console.log("center_ack.observed_endpoint=" + String(oe.transport) + ":" + String(oe.addr))
      const oeKey = String(oe.transport) + "|" + String(oe.addr)
      cachedObservedKey = oeKey
      cachedObserved = { transport: String(oe.transport), addr: String(oe.addr) }
      if (cachePath && oe.transport && oe.addr) {
        try {
          fs.mkdirSync(path.dirname(cachePath), { recursive: true })
          fs.writeFileSync(cachePath, JSON.stringify({ observed_endpoint: { transport: String(oe.transport), addr: String(oe.addr) } }, null, 2), "utf-8")
        } catch {}
      }
    }
    stage = 4
    if (ack.observed_endpoint) {
      const oe = ack.observed_endpoint as any
      const oeKey = String(oe.transport) + "|" + String(oe.addr)
      if (!oldKey || oldKey !== oeKey) {
        const caps = { max_frame_payload: maxFramePayload, magic, version, flags_plain: flagsPlain, flags_encrypted: flagsEncrypted }
        const base = ((ws as any).__reported ?? []) as any[]
        const applied = applyObservedIpToEndpoints(String(oe.addr), base)
        const bodyMsg2 = CenterHelloBody.create({
          node_id64: nodeId64Str,
          pubkey_spki_der: clientPubDer,
          reported_endpoints: applied,
          caps,
          timestamp_ms: String(Date.now()),
          crypto_mode: cryptoMode,
        })
        const b2 = CenterHelloBody.encode(bodyMsg2).finish()
        const sig2 = crypto.sign("RSA-SHA256", Buffer.from(b2), crypto.createPrivateKey(privateKeyPem))
        const hello2 = CenterHello.encode(CenterHello.create({ body: bodyMsg2, signature: sig2 })).finish()
        const wrap2 = encodeWrapper(root, { seq: 21, command: -11001, data: hello2 })
        const cipherX = xorWithFile(wrap2, offset)
        ws.send(encodeFrame({ length: cipherX.length, magic, version, flags: flagsEncrypted }, cipherX))
      }
    }

    if (!queriedOnce) {
      queriedOnce = true
      const req = GetNode.encode(GetNode.create({ node_key: ack.node_key })).finish()
      const wrap = encodeWrapper(root, { seq: 3, command: -11010, data: req })
      const cipher2 = xorWithFile(wrap, offset)
      ws.send(encodeFrame({ length: cipher2.length, magic, version, flags: flagsEncrypted }, cipher2))
    }
    return
  }

  if (w.command === -11011) {
    const r = GetNodeAck.decode(w.data ?? new Uint8Array()) as any
    console.log("get_node.found=" + String(r.found))
    if (renewSec <= 0) {
      fs.closeSync(fd)
      ws.close()
      stage = 5
    } else {
      stage = 6
    }
  }
}

ws.onerror = (e) => {
  console.error(e)
  process.exit(1)
}

if (renewSec <= 0) {
  setTimeout(() => {
    if (stage < 5) {
      console.error("timeout.stage=" + stage)
      try {
        fs.closeSync(fd)
      } catch {}
      ws.close()
      process.exit(2)
    }
  }, 8000)
}

function sendGetNode() {
  if (!lastAck?.node_key || offset < 0) return
  const req = GetNode.encode(GetNode.create({ node_key: lastAck.node_key })).finish()
  const wrap = encodeWrapper(root, { seq: 300, command: -11010, data: req })
  const cipher = xorWithFile(wrap, offset)
  ws.send(encodeFrame({ length: cipher.length, magic, version, flags: flagsEncrypted }, cipher))
}

function finishAfterExpiry() {
  const ttl = Number(lastAck?.ttl_seconds ?? 0)
  if (ttl <= 0) return
  setTimeout(() => {
    sendGetNode()
    setTimeout(() => {
      try {
        fs.closeSync(fd)
      } catch {}
      ws.close()
      process.exit(0)
    }, 1500)
  }, (ttl + 1) * 1000)
}

function sendRenew() {
  if (stage < 2 || offset < 0) return
  if (!lastAck?.node_key) return
  const caps = { max_frame_payload: maxFramePayload, magic, version, flags_plain: flagsPlain, flags_encrypted: flagsEncrypted }
  const reported = ((ws as any).__reported ?? []) as any[]
  const bodyMsg = CenterHelloBody.create({
    node_id64: nodeId64Str,
    pubkey_spki_der: clientPubDer,
    reported_endpoints: reported,
    caps,
    timestamp_ms: String(Date.now()),
    crypto_mode: cryptoMode,
  })
  const bodyBytes = CenterHelloBody.encode(bodyMsg).finish()
  const sig = crypto.sign("RSA-SHA256", Buffer.from(bodyBytes), crypto.createPrivateKey(privateKeyPem))
  const hello = CenterHello.encode(CenterHello.create({ body: bodyMsg, signature: sig })).finish()
  const wrap = encodeWrapper(root, { seq: 100 + renewLeft, command: -11001, data: hello })
  const cipher = xorWithFile(wrap, offset)
  ws.send(encodeFrame({ length: cipher.length, magic, version, flags: flagsEncrypted }, cipher))
  if (renewLeft > 0) {
    renewLeft -= 1
    if (renewLeft === 0 && renewTimer) {
      clearInterval(renewTimer)
      renewTimer = null
      finishAfterExpiry()
    }
  }
}

if (renewSec > 0) {
  renewTimer = setInterval(sendRenew, renewSec * 1000)
}
