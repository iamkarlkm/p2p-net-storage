import fs from "node:fs"
import path from "node:path"
import crypto from "node:crypto"
import { WebSocket } from "ws"

import { decodeFrame, encodeFrame } from "./frame.js"
import { loadProtoRoot } from "./proto.js"
import { decodeWrapper, encodeWrapper } from "./wrapper.js"
import { loadClientConfig, parseIntMaybeHex } from "./config.js"
import { rsaOaepSha256Decrypt } from "./handshake.js"

const args0 = process.argv.slice(2)
const useRelay = args0.includes("--relay")
const noFallback = args0.includes("--no-fallback")
const useHint = args0.includes("--hint")
const args = args0.filter((a) => a !== "--relay" && a !== "--no-fallback" && a !== "--hint")
if (args.length < 3) {
  console.log("Usage: tsx src/cli.ts <config.yaml> put <target_node_id> <filepath> [--hint] [--relay] [--no-fallback]")
  console.log("Usage: tsx src/cli.ts <config.yaml> get <target_node_id> <filehash> <outpath> [--hint] [--relay] [--no-fallback]")
  console.log("Flags: --hint (connect-hint协同), --relay (force relay), --no-fallback (disable direct->relay fallback)")
  process.exit(1)
}

const cfgPath = path.resolve(args[0])
const action = args[1]
const targetNodeId64 = args[2]

let filepath = ""
let filehash = ""
let outpath = ""

if (action === "put") {
  if (args.length < 4) {
    console.error("Missing filepath for put")
    process.exit(1)
  }
  filepath = path.resolve(args[3])
  if (!fs.existsSync(filepath)) {
    console.error("File not found:", filepath)
    process.exit(1)
  }
} else if (action === "get") {
  if (args.length < 5) {
    console.error("Missing filehash or outpath for get")
    process.exit(1)
  }
  filehash = args[3]
  outpath = path.resolve(args[4])
} else {
  console.error("Unknown action:", action)
  process.exit(1)
}

const cfg = loadClientConfig(cfgPath)
const root = await loadProtoRoot()
const Hand = root.lookupType("p2pws.Hand")
const HandAckPlain = root.lookupType("p2pws.HandAckPlain")
const CenterHelloBody = root.lookupType("p2pws.CenterHelloBody")
const CenterHello = root.lookupType("p2pws.CenterHello")
const GetNode = root.lookupType("p2pws.GetNode")
const GetNodeAck = root.lookupType("p2pws.GetNodeAck")
const PeerHelloBody = root.lookupType("p2pws.PeerHelloBody")
const PeerHello = root.lookupType("p2pws.PeerHello")
const FilePutRequest = root.lookupType("p2pws.FilePutRequest")
const FilePutResponse = root.lookupType("p2pws.FilePutResponse")
const FileGetRequest = root.lookupType("p2pws.FileGetRequest")
const FileGetResponse = root.lookupType("p2pws.FileGetResponse")
const RelayData = root.lookupType("p2pws.RelayData")
const ConnectHint = root.lookupType("p2pws.ConnectHint")
const ConnectHintAck = root.lookupType("p2pws.ConnectHintAck")
const IncomingHint = root.lookupType("p2pws.IncomingHint")

const magic = parseIntMaybeHex(cfg.magic, 0x1234)
const version = cfg.version ?? 1
const flagsPlain = cfg.flags_plain ?? 4
const flagsEncrypted = cfg.flags_encrypted ?? 5
const maxFramePayload = cfg.max_frame_payload ?? 4 * 1024 * 1024
const cryptoMode = (cfg.crypto_mode ?? "KEYFILE_XOR_RSA_OAEP").trim()

const keyfileAbs = path.resolve(path.dirname(cfgPath), cfg.keyfile_path)
const fd = fs.openSync(keyfileAbs, "r")
const keyHex = crypto.createHash("sha256").update(fs.readFileSync(keyfileAbs)).digest("hex")
const keyId = Buffer.from(keyHex, "hex")

const privAbs = path.resolve(path.dirname(cfgPath), cfg.rsa_private_key_pem_path)
const privateKeyPem = fs.readFileSync(privAbs, "utf-8")
const privObj = crypto.createPrivateKey(privateKeyPem)
const pubObj = crypto.createPublicKey(privObj)
const clientPubDer = pubObj.export({ type: "spki", format: "der" }) as Buffer
const nodeId64Str = String(parseInt(cfg.user_id, 10) || 1)

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

let centerOffset = -1
let targetNodeKey: Uint8Array | null = null
let targetNodeId64Resolved: string | null = null
let targetEndpoints: any[] = []
let relayOuterSeq = 1000
let relayInnerSeq = 2000
let attemptedDirect = false
let done = false
let kicked = false

const endpointCooldown = new Map<string, { untilMs: number; fails: number }>()
const endpointStats = new Map<string, { success: number; lastSuccessMs: number }>()
const baseCooldownMs = 5_000
const maxCooldownMs = 5 * 60_000
const directHandshakeTimeoutMs = 4_000
const directTransferTimeoutMs = 10_000
const happyStaggerMs = 250
const happyMaxParallel = 4

const cfgDir = path.dirname(cfgPath)
const cooldownCachePathAbs = cfg.cooldown_cache_path
  ? path.resolve(cfgDir, cfg.cooldown_cache_path)
  : (cfg.presence_cache_path ? path.resolve(cfgDir, cfg.presence_cache_path) : path.resolve(cfgDir, `${path.basename(cfgPath)}.cooldown.json`))

function loadJsonFile(p: string): any | null {
  try {
    if (!fs.existsSync(p)) return null
    return JSON.parse(fs.readFileSync(p, "utf-8"))
  } catch {
    return null
  }
}

function writeJsonFileMerge(p: string, patch: any) {
  try {
    const cur = loadJsonFile(p) ?? {}
    const next = { ...cur, ...patch }
    fs.mkdirSync(path.dirname(p), { recursive: true })
    fs.writeFileSync(p, JSON.stringify(next, null, 2), "utf-8")
  } catch {
  }
}

function loadCooldownCache() {
  const j = loadJsonFile(cooldownCachePathAbs)
  const m = j?.endpoint_cooldown
  if (!m || typeof m !== "object") return
  for (const [k, v] of Object.entries(m)) {
    const vv = v as any
    const untilMs = Number(vv?.untilMs)
    const fails = Number(vv?.fails)
    if (!Number.isFinite(untilMs) || !Number.isFinite(fails)) continue
    endpointCooldown.set(String(k), { untilMs, fails })
  }

  const s = j?.endpoint_stats
  if (!s || typeof s !== "object") return
  for (const [k, v] of Object.entries(s)) {
    const vv = v as any
    const success = Number(vv?.success)
    const lastSuccessMs = Number(vv?.lastSuccessMs)
    if (!Number.isFinite(success) || !Number.isFinite(lastSuccessMs)) continue
    endpointStats.set(String(k), { success, lastSuccessMs })
  }
}

function saveCooldownCache() {
  const obj: any = {}
  for (const [k, v] of endpointCooldown.entries()) {
    obj[k] = { untilMs: v.untilMs, fails: v.fails }
  }
  const stats: any = {}
  for (const [k, v] of endpointStats.entries()) {
    stats[k] = { success: v.success, lastSuccessMs: v.lastSuccessMs }
  }
  writeJsonFileMerge(cooldownCachePathAbs, { endpoint_cooldown: obj, endpoint_stats: stats })
}

loadCooldownCache()

console.log("[CenterClient] Connecting to Center to lookup target peer...")
const centerWs = new WebSocket(cfg.ws_url)
centerWs.binaryType = "arraybuffer"

centerWs.on("open", () => {
  const handData = Hand.encode(Hand.create({ client_pubkey: clientPubDer, key_ids: [keyId], max_frame_payload: maxFramePayload, client_id: cfg.user_id })).finish()
  const wrapper = encodeWrapper(root, { seq: 1, command: -10001, data: handData })
  centerWs.send(encodeFrame({ length: wrapper.length, magic, version, flags: flagsPlain }, wrapper))
})

centerWs.on("message", (ev) => {
  const f = decodeFrame(new Uint8Array(ev as Buffer))
  const plainPayload = centerOffset >= 0 ? xorWithFile(f.cipherPayload, centerOffset) : f.cipherPayload
  const w = decodeWrapper(root, plainPayload)
  
  if (w.command === -10002) {
    const enc = w.data ?? new Uint8Array()
    const ack = HandAckPlain.decode(rsaOaepSha256Decrypt(privateKeyPem, enc)) as any
    centerOffset = Number(ack.offset)
    
    const bodyMsg = CenterHelloBody.create({
      node_id64: nodeId64Str,
      pubkey_spki_der: clientPubDer,
      reported_endpoints: cfg.reported_endpoints ?? [],
      caps: { max_frame_payload: maxFramePayload, magic, version, flags_plain: flagsPlain, flags_encrypted: flagsEncrypted },
      timestamp_ms: String(Date.now()),
      crypto_mode: cryptoMode,
    })
    const bodyBytes = CenterHelloBody.encode(bodyMsg).finish()
    const sig = crypto.sign("RSA-SHA256", Buffer.from(bodyBytes), crypto.createPrivateKey(privateKeyPem))
    const hello = CenterHello.encode(CenterHello.create({ body: bodyMsg, signature: sig })).finish()
    const helloWrap = encodeWrapper(root, { seq: 2, command: -11001, data: hello })
    const helloCipher = xorWithFile(helloWrap, centerOffset)
    centerWs.send(encodeFrame({ length: helloCipher.length, magic, version, flags: flagsEncrypted }, helloCipher))
    return
  }

  if (w.command === -11002) {
    if (useHint) {
      const req = ConnectHint.encode(ConnectHint.create({ target_node_id64: targetNodeId64 })).finish()
      const wrap = encodeWrapper(root, { seq: 2_000_000_001, command: -11030, data: req })
      const cipher = xorWithFile(wrap, centerOffset)
      centerWs.send(encodeFrame({ length: cipher.length, magic, version, flags: flagsEncrypted }, cipher))
    }
    const req = GetNode.encode(GetNode.create({ node_id64: targetNodeId64 })).finish()
    const wrap = encodeWrapper(root, { seq: 3, command: -11010, data: req })
    const cipher = xorWithFile(wrap, centerOffset)
    centerWs.send(encodeFrame({ length: cipher.length, magic, version, flags: flagsEncrypted }, cipher))
    return
  }

  if (w.command === -11011) {
    const r = GetNodeAck.decode(w.data ?? new Uint8Array()) as any
    if (r.found) {
      targetNodeKey = new Uint8Array(r.node_key)
      targetNodeId64Resolved = String(r.node_id64)
      targetEndpoints = Array.isArray(r.endpoints) ? r.endpoints : []
      if (useRelay) {
        sendRelayRequest(targetNodeId64Resolved, targetNodeKey)
        return
      }
      startDirectOrFallback()
    } else {
      console.log(`[CenterClient] Target node not found or has no valid endpoints.`)
      process.exit(1)
    }
    return
  }

  if (w.command === -11030 && useHint) {
    const r = ConnectHintAck.decode(w.data ?? new Uint8Array()) as any
    if (r.found) {
      if (r.target_node_key && (!targetNodeKey || Buffer.from(targetNodeKey).compare(Buffer.from(r.target_node_key)) !== 0)) {
        targetNodeKey = new Uint8Array(r.target_node_key)
      }
      targetNodeId64Resolved = targetNodeId64Resolved ?? String(r.target_node_id64)
      const eps = Array.isArray(r.target_endpoints) ? r.target_endpoints : []
      if (eps.length > 0) {
        targetEndpoints = mergeEndpoints(targetEndpoints, eps)
        startDirectOrFallback()
      }
    }
    return
  }

  if (w.command === -11031) {
    const r = IncomingHint.decode(w.data ?? new Uint8Array()) as any
    const eps = Array.isArray(r.source_endpoints) ? r.source_endpoints : []
    if (eps.length > 0) {
      console.log(`[Hint] IncomingHint received: ${eps.map((e:any)=>e.transport+"://"+e.addr).join(", ")}`)
    }
    return
  }

  if (w.command === -11012 && useRelay) {
    const rd = RelayData.decode(w.data ?? new Uint8Array()) as any
    if (!rd.payload) return
    if (!targetNodeKey) return
    if (rd.source_node_key && Buffer.from(rd.source_node_key).compare(Buffer.from(targetNodeKey)) !== 0) return
    let inner: any
    try {
      inner = decodeWrapper(root, new Uint8Array(rd.payload))
    } catch {
      return
    }
    if (inner.command === 1002) {
      const resp = FilePutResponse.decode(inner.data ?? new Uint8Array()) as any
      console.log(`[Relay] File PUT response: success=${resp.success}`)
      process.exit(0)
      return
    }
    if (inner.command === 1004) {
      const resp = FileGetResponse.decode(inner.data ?? new Uint8Array()) as any
      if (resp.found) {
        fs.writeFileSync(outpath, resp.content)
        console.log(`[Relay] File GET success! Saved to ${outpath}`)
      } else {
        console.log(`[Relay] File GET failed: Not found on peer.`)
      }
      process.exit(0)
      return
    }
  }
})

function endpointKey(e: any) {
  return `${String(e?.transport ?? "")}|${String(e?.addr ?? "")}`
}

function endpointHostPort(addr: string): { host: string; port: string } {
  const s = String(addr ?? "")
  const idx = s.lastIndexOf(":")
  if (idx <= 0) return { host: s, port: "" }
  return { host: s.slice(0, idx), port: s.slice(idx + 1) }
}

function isPrivateIpv4(host: string): boolean {
  const h = host.trim()
  if (!/^\d+\.\d+\.\d+\.\d+$/.test(h)) return false
  const [a, b] = h.split(".").map((x) => parseInt(x, 10))
  if (a === 10) return true
  if (a === 127) return true
  if (a === 192 && b === 168) return true
  if (a === 172 && b >= 16 && b <= 31) return true
  return false
}

function endpointScore(e: any): number {
  const k = endpointKey(e)
  const { host } = endpointHostPort(String(e?.addr ?? ""))
  const st = endpointStats.get(k)
  const success = st?.success ?? 0
  const last = st?.lastSuccessMs ?? 0
  const recency = last > 0 ? Math.max(0, Math.min(30, Math.floor((Date.now() - last) / 10_000))) : 30
  const successScore = Math.min(50, success * 10) + Math.max(0, 30 - recency)

  if (host === "127.0.0.1" || host === "localhost" || host === "0.0.0.0") return -1000 + successScore
  if (isPrivateIpv4(host)) return 10 + successScore
  if (/^\d+\.\d+\.\d+\.\d+$/.test(host)) return 100 + successScore
  return 30 + successScore
}

function mergeEndpoints(a: any[], b: any[]) {
  const m = new Map<string, any>()
  for (const e of a ?? []) m.set(endpointKey(e), e)
  for (const e of b ?? []) if (!m.has(endpointKey(e))) m.set(endpointKey(e), e)
  return Array.from(m.values()).sort((x, y) => endpointScore(y) - endpointScore(x))
}

function isEndpointCoolDown(e: any) {
  const k = endpointKey(e)
  const s = endpointCooldown.get(k)
  return Boolean(s && s.untilMs > Date.now())
}

function markEndpointFail(e: any) {
  const k = endpointKey(e)
  const prev = endpointCooldown.get(k) ?? { untilMs: 0, fails: 0 }
  const fails = prev.fails + 1
  const cd = Math.min(maxCooldownMs, baseCooldownMs * Math.pow(2, Math.min(6, fails - 1)))
  endpointCooldown.set(k, { untilMs: Date.now() + cd, fails })
  saveCooldownCache()
}

function markEndpointSuccess(e: any) {
  const k = endpointKey(e)
  const prev = endpointStats.get(k) ?? { success: 0, lastSuccessMs: 0 }
  endpointStats.set(k, { success: prev.success + 1, lastSuccessMs: Date.now() })
  if (endpointCooldown.has(k)) {
    endpointCooldown.delete(k)
  }
  saveCooldownCache()
}

function sendRelayRequest(targetNodeId64: string, targetKey: Uint8Array) {
  if (centerOffset < 0) {
    console.error("[Relay] centerOffset not ready")
    process.exit(1)
  }

  if (action === "put") {
    const fileContent = fs.readFileSync(filepath)
    const hash = crypto.createHash("sha256").update(fileContent).digest()
    console.log(`[Relay] Uploading file (${fileContent.length} bytes), Hash: ${hash.toString("hex")}`)
    const req = FilePutRequest.encode({
      file_name: path.basename(filepath),
      file_size: fileContent.length,
      file_hash_sha256: hash,
      content: fileContent
    }).finish()
    const inner = encodeWrapper(root, { seq: relayInnerSeq++, command: 1001, data: req })
    const rd = RelayData.encode({
      target_node_id64: targetNodeId64,
      target_node_key: targetKey,
      source_node_id64: nodeId64Str,
      source_node_key: Buffer.from(crypto.createHash("sha256").update(clientPubDer).digest()),
      payload: inner
    }).finish()
    const wrap = encodeWrapper(root, { seq: relayOuterSeq++, command: -11012, data: rd })
    const cipher = xorWithFile(wrap, centerOffset)
    centerWs.send(encodeFrame({ length: cipher.length, magic, version, flags: flagsEncrypted }, cipher))
    return
  }

  if (action === "get") {
    console.log(`[Relay] Requesting file Hash: ${filehash}`)
    const req = FileGetRequest.encode({ file_hash_sha256: Buffer.from(filehash, "hex") }).finish()
    const inner = encodeWrapper(root, { seq: relayInnerSeq++, command: 1003, data: req })
    const rd = RelayData.encode({
      target_node_id64: targetNodeId64,
      target_node_key: targetKey,
      source_node_id64: nodeId64Str,
      source_node_key: Buffer.from(crypto.createHash("sha256").update(clientPubDer).digest()),
      payload: inner
    }).finish()
    const wrap = encodeWrapper(root, { seq: relayOuterSeq++, command: -11012, data: rd })
    const cipher = xorWithFile(wrap, centerOffset)
    centerWs.send(encodeFrame({ length: cipher.length, magic, version, flags: flagsEncrypted }, cipher))
    return
  }
}

function startDirectOrFallback() {
  if (done || kicked) return
  if (!targetNodeId64Resolved || !targetNodeKey) {
    console.error("[Auto] target info not ready")
    process.exit(1)
  }
  if (attemptedDirect) return
  attemptedDirect = true
  kicked = true
  runDirectWithHappyEyeballs().finally(() => { kicked = false })
}

async function runDirectWithHappyEyeballs() {
  const endpointsAll = Array.isArray(targetEndpoints) ? targetEndpoints : []
  const endpoints = endpointsAll.filter((e) => !isEndpointCoolDown(e)).sort((x, y) => endpointScore(y) - endpointScore(x))
  if (endpoints.length === 0) {
    if (noFallback) {
      console.log("[Auto] No usable endpoints (cooldown); fallback disabled.")
      process.exit(1)
    }
    console.log("[Auto] No usable endpoints (cooldown); fallback to relay.")
    sendRelayRequest(targetNodeId64Resolved!, targetNodeKey!)
    return
  }

  console.log(`[Auto] Happy-eyeballs dialing (${Math.min(happyMaxParallel, endpoints.length)} parallel, ${happyStaggerMs}ms stagger): ${endpoints.map((e:any)=>e.transport+"://"+e.addr).join(", ")}`)

  while (endpoints.length > 0) {
    const winner = await dialHappyEyeballsHandshake(endpoints, directHandshakeTimeoutMs)
    if (!winner) break
    const wi = endpoints.findIndex((e) => endpointKey(e) === endpointKey(winner.endpoint))
    if (wi >= 0) endpoints.splice(wi, 1)
    const ok = await winner.runAction(directTransferTimeoutMs)
    if (ok) {
      markEndpointSuccess(winner.endpoint)
      done = true
      centerWs.close()
      process.exit(0)
      return
    }
    markEndpointFail(winner.endpoint)
  }

  if (noFallback) {
    console.log("[Auto] Direct connect failed; fallback disabled.")
    process.exit(1)
    return
  }
  console.log("[Auto] Direct connect failed; fallback to relay.")
  sendRelayRequest(targetNodeId64Resolved!, targetNodeKey!)
}

type PeerSession = {
  endpoint: any
  close: () => void
  runAction: (timeoutMs: number) => Promise<boolean>
}

async function dialHappyEyeballsHandshake(endpoints: any[], timeoutMs: number): Promise<PeerSession | null> {
  const queue = endpoints.slice()
  const active: Array<Promise<PeerSession | null>> = []
  const sessions: PeerSession[] = []

  const spawn = async (ep: any) => {
    const s = await connectToPeerHandshake(ep, timeoutMs)
    if (!s) markEndpointFail(ep)
    return s
  }

  const tryStart = () => {
    while (active.length < happyMaxParallel && queue.length > 0) {
      const ep = queue.shift()
      if (!ep) break
      if (isEndpointCoolDown(ep)) continue
      const p = spawn(ep)
      active.push(p)
    }
  }

  tryStart()
  while (active.length > 0) {
    const raced = await Promise.race(active.map((p, i) => p.then((v) => ({ i, v }))))
    active.splice(raced.i, 1)

    if (raced.v) {
      sessions.push(raced.v)
      for (const s of sessions) {
        if (s !== raced.v) s.close()
      }
      return raced.v
    }

    if (queue.length > 0) {
      await new Promise((r) => setTimeout(r, happyStaggerMs))
      tryStart()
    }
  }

  for (const s of sessions) s.close()
  return null
}

function connectToPeerHandshake(endpoint: any, timeoutMs: number): Promise<PeerSession | null> {
  const peerWsUrl = `${endpoint.transport}://${endpoint.addr}`
  console.log(`[PeerClient] Dialing ${peerWsUrl}`)
  const peerWs = new WebSocket(peerWsUrl)
  peerWs.binaryType = "arraybuffer"
  let peerOffset = -1
  let finished = false

  const close = () => {
    if (finished) return
    finished = true
    try { peerWs.close() } catch {}
  }

  return new Promise<PeerSession | null>((resolve) => {
    const timer = setTimeout(() => {
      close()
      resolve(null)
    }, timeoutMs)

    peerWs.on("open", () => {
      const handData = Hand.encode(Hand.create({ client_pubkey: clientPubDer, key_ids: [keyId], max_frame_payload: maxFramePayload, client_id: cfg.user_id })).finish()
      const wrapper = encodeWrapper(root, { seq: 1, command: -10001, data: handData })
      peerWs.send(encodeFrame({ length: wrapper.length, magic, version, flags: flagsPlain }, wrapper))
    })

    peerWs.on("message", (ev) => {
      const f = decodeFrame(new Uint8Array(ev as Buffer))
      const plainPayload = peerOffset >= 0 ? xorWithFile(f.cipherPayload, peerOffset) : f.cipherPayload
      const w = decodeWrapper(root, plainPayload)

      if (w.command === -10002) {
        const enc = w.data ?? new Uint8Array()
        const ack = HandAckPlain.decode(rsaOaepSha256Decrypt(privateKeyPem, enc)) as any
        peerOffset = Number(ack.offset)
        
        const bodyMsg = PeerHelloBody.create({
          node_id64: nodeId64Str,
          pubkey_spki_der: clientPubDer,
          timestamp_ms: String(Date.now()),
          crypto_mode: cryptoMode,
        })
        const bodyBytes = PeerHelloBody.encode(bodyMsg).finish()
        const sig = crypto.sign("RSA-SHA256", Buffer.from(bodyBytes), crypto.createPrivateKey(privateKeyPem))
        const hello = PeerHello.encode(PeerHello.create({ body: bodyMsg, signature: sig })).finish()
        const helloWrap = encodeWrapper(root, { seq: 2, command: -12001, data: hello })
        const helloCipher = xorWithFile(helloWrap, peerOffset)
        peerWs.send(encodeFrame({ length: helloCipher.length, magic, version, flags: flagsEncrypted }, helloCipher))
        return
      }

      if (w.command === -12002) {
        clearTimeout(timer)
        const session: PeerSession = {
          endpoint,
          close,
          runAction: (transferTimeoutMs: number) => runActionOverPeer(peerWs, peerOffset, transferTimeoutMs)
        }
        resolve(session)
        return
      }
    })

    peerWs.on("close", () => {
      clearTimeout(timer)
      resolve(null)
    })

    peerWs.on("error", () => {
      clearTimeout(timer)
      resolve(null)
    })
  })
}

function runActionOverPeer(peerWs: WebSocket, peerOffset: number, timeoutMs: number): Promise<boolean> {
  let finished = false
  const finish = (ok: boolean) => {
    if (finished) return
    finished = true
    try { peerWs.close() } catch {}
    return ok
  }

  return new Promise<boolean>((resolve) => {
    const timer = setTimeout(() => resolve(finish(false)!), timeoutMs)

    const onMessage = (ev: any) => {
      const f = decodeFrame(new Uint8Array(ev as Buffer))
      const plainPayload = peerOffset >= 0 ? xorWithFile(f.cipherPayload, peerOffset) : f.cipherPayload
      const w = decodeWrapper(root, plainPayload)

      if (w.command === 1002) {
        const resp = FilePutResponse.decode(w.data ?? new Uint8Array()) as any
        console.log(`[PeerClient] File PUT response: success=${resp.success}`)
        peerWs.off("message", onMessage)
        clearTimeout(timer)
        resolve(finish(Boolean(resp.success))!)
        return
      }

      if (w.command === 1004) {
        const resp = FileGetResponse.decode(w.data ?? new Uint8Array()) as any
        if (resp.found) {
          fs.writeFileSync(outpath, resp.content)
          console.log(`[PeerClient] File GET success! Saved to ${outpath}`)
        } else {
          console.log(`[PeerClient] File GET failed: Not found on peer.`)
        }
        peerWs.off("message", onMessage)
        clearTimeout(timer)
        resolve(finish(Boolean(resp.found))!)
        return
      }
    }
    peerWs.on("message", onMessage)

    if (action === "put") {
      const fileContent = fs.readFileSync(filepath)
      const hash = crypto.createHash("sha256").update(fileContent).digest()
      console.log(`[PeerClient] Uploading file (${fileContent.length} bytes), Hash: ${hash.toString("hex")}`)
      const req = FilePutRequest.encode({
        file_name: path.basename(filepath),
        file_size: fileContent.length,
        file_hash_sha256: hash,
        content: fileContent
      }).finish()
      const reqWrap = encodeWrapper(root, { seq: 3, command: 1001, data: req })
      const cipher = xorWithFile(reqWrap, peerOffset)
      peerWs.send(encodeFrame({ length: cipher.length, magic, version, flags: flagsEncrypted }, cipher))
      return
    }

    if (action === "get") {
      console.log(`[PeerClient] Requesting file Hash: ${filehash}`)
      const req = FileGetRequest.encode({ file_hash_sha256: Buffer.from(filehash, "hex") }).finish()
      const reqWrap = encodeWrapper(root, { seq: 4, command: 1003, data: req })
      const cipher = xorWithFile(reqWrap, peerOffset)
      peerWs.send(encodeFrame({ length: cipher.length, magic, version, flags: flagsEncrypted }, cipher))
      return
    }

    peerWs.off("message", onMessage)
    clearTimeout(timer)
    resolve(finish(false)!)
  })
}
