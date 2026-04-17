import fs from "node:fs"
import path from "node:path"
import crypto from "node:crypto"
import { WebSocket } from "ws"

import { decodeFrame, encodeFrame } from "./frame.js"
import { loadProtoRoot } from "./proto.js"
import { decodeWrapper, encodeWrapper } from "./wrapper.js"
import { loadClientConfig, parseIntMaybeHex } from "./config.js"
import { rsaOaepSha256Decrypt } from "./handshake.js"

type Endpoint = { transport: string; addr: string }
type ManifestChunk = { index: number; offset: number; size: number; sha256_hex: string }
type Manifest = {
  version: number
  file_name: string
  file_size: number
  chunk_size: number
  file_sha256_hex: string
  chunks: ManifestChunk[]
}

function sha256Hex(buf: Buffer) {
  return crypto.createHash("sha256").update(buf).digest("hex")
}

function storageDirFor(cfgPathAbs: string, userId: string) {
  const cfgDir = path.dirname(cfgPathAbs)
  return path.resolve(cfgDir, `../data/node_${userId}`)
}

function writeByHash(storageDir: string, sha256HexStr: string, content: Buffer) {
  fs.mkdirSync(storageDir, { recursive: true })
  fs.writeFileSync(path.join(storageDir, sha256HexStr), content)
}

function buildManifest(filePathAbs: string, chunkSize: number): { manifest: Manifest; chunks: Array<{ sha256_hex: string; content: Buffer }> } {
  const file = fs.readFileSync(filePathAbs)
  const fileSha = sha256Hex(file)
  const chunks: Array<{ sha256_hex: string; content: Buffer }> = []
  const mChunks: ManifestChunk[] = []
  let off = 0
  let idx = 0
  while (off < file.length) {
    const end = Math.min(file.length, off + chunkSize)
    const part = file.subarray(off, end)
    const h = sha256Hex(part)
    chunks.push({ sha256_hex: h, content: Buffer.from(part) })
    mChunks.push({ index: idx, offset: off, size: end - off, sha256_hex: h })
    off = end
    idx++
  }
  const manifest: Manifest = {
    version: 1,
    file_name: path.basename(filePathAbs),
    file_size: file.length,
    chunk_size: chunkSize,
    file_sha256_hex: fileSha,
    chunks: mChunks,
  }
  return { manifest, chunks }
}

function parsePeers(arg: string | undefined): string[] {
  if (!arg) return []
  return arg
    .split(",")
    .map((s) => s.trim())
    .filter((s) => s.length > 0)
}

function endpointKey(e: any) {
  return `${String(e?.transport ?? "")}|${String(e?.addr ?? "")}`
}

function mergeEndpoints(a: any[], b: any[]) {
  const m = new Map<string, any>()
  for (const e of a ?? []) m.set(endpointKey(e), e)
  for (const e of b ?? []) if (!m.has(endpointKey(e))) m.set(endpointKey(e), e)
  return Array.from(m.values())
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
  const { host } = endpointHostPort(String(e?.addr ?? ""))
  if (host === "127.0.0.1" || host === "localhost" || host === "0.0.0.0") return -1000
  if (isPrivateIpv4(host)) return 10
  if (/^\d+\.\d+\.\d+\.\d+$/.test(host)) return 100
  return 30
}

function xorWithKeyFile(fd: number, off: number, data: Uint8Array): Uint8Array {
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

type PeerConn = {
  endpoint: Endpoint
  ws: WebSocket
  peerOffset: number
  nextSeq: number
  pending: Map<number, { resolve: (b: Uint8Array) => void; reject: () => void }>
}

async function connectPeerHappyEyeballs(params: {
  endpoints: Endpoint[]
  root: any
  keyId: Buffer
  clientPubDer: Buffer
  privateKeyPem: string
  nodeId64Str: string
  cryptoMode: string
  fd: number
  magic: number
  version: number
  flagsPlain: number
  flagsEncrypted: number
  maxParallel: number
  staggerMs: number
  handshakeTimeoutMs: number
}): Promise<PeerConn | null> {
  const queue = params.endpoints.slice().sort((a, b) => endpointScore(b) - endpointScore(a))
  const active: Array<Promise<PeerConn | null>> = []
  const conns: PeerConn[] = []

  const tryStart = () => {
    while (active.length < params.maxParallel && queue.length > 0) {
      const ep = queue.shift()
      if (!ep) break
      active.push(connectPeerHandshakeOnce({ ...params, endpoint: ep }))
    }
  }

  tryStart()
  while (active.length > 0) {
    const raced = await Promise.race(active.map((p, i) => p.then((v) => ({ i, v }))))
    active.splice(raced.i, 1)
    if (raced.v) {
      conns.push(raced.v)
      for (const c of conns) {
        if (c !== raced.v) {
          try { c.ws.close() } catch {}
        }
      }
      return raced.v
    }
    if (queue.length > 0) {
      await new Promise((r) => setTimeout(r, params.staggerMs))
      tryStart()
    }
  }
  return null
}

function connectPeerHandshakeOnce(params: {
  endpoint: Endpoint
  root: any
  keyId: Buffer
  clientPubDer: Buffer
  privateKeyPem: string
  nodeId64Str: string
  cryptoMode: string
  fd: number
  magic: number
  version: number
  flagsPlain: number
  flagsEncrypted: number
  handshakeTimeoutMs: number
}): Promise<PeerConn | null> {
  const url = `${params.endpoint.transport}://${params.endpoint.addr}`
  const ws = new WebSocket(url)
  ws.binaryType = "arraybuffer"
  let peerOffset = -1
  let done = false

  const finish = (ok: PeerConn | null) => {
    if (done) return
    done = true
    if (!ok) {
      try { ws.close() } catch {}
    }
    return ok
  }

  return new Promise<PeerConn | null>((resolve) => {
    const t = setTimeout(() => resolve(finish(null)!), params.handshakeTimeoutMs)

    ws.on("open", () => {
      const Hand = params.root.lookupType("p2pws.Hand")
      const handData = Hand.encode(Hand.create({ client_pubkey: params.clientPubDer, key_ids: [params.keyId], max_frame_payload: 4 * 1024 * 1024, client_id: params.nodeId64Str })).finish()
      const wrapper = encodeWrapper(params.root, { seq: 1, command: -10001, data: handData })
      ws.send(encodeFrame({ length: wrapper.length, magic: params.magic, version: params.version, flags: params.flagsPlain }, wrapper))
    })

    ws.on("message", (ev) => {
      const f = decodeFrame(new Uint8Array(ev as Buffer))
      const plainPayload = peerOffset >= 0 ? xorWithKeyFile(params.fd, peerOffset, f.cipherPayload) : f.cipherPayload
      const w = decodeWrapper(params.root, plainPayload)

      if (w.command === -10002) {
        const HandAckPlain = params.root.lookupType("p2pws.HandAckPlain")
        const enc = w.data ?? new Uint8Array()
        const ack = HandAckPlain.decode(rsaOaepSha256Decrypt(params.privateKeyPem, enc)) as any
        peerOffset = Number(ack.offset)

        const PeerHelloBody = params.root.lookupType("p2pws.PeerHelloBody")
        const PeerHello = params.root.lookupType("p2pws.PeerHello")
        const bodyMsg = PeerHelloBody.create({
          node_id64: params.nodeId64Str,
          pubkey_spki_der: params.clientPubDer,
          timestamp_ms: String(Date.now()),
          crypto_mode: params.cryptoMode,
        })
        const bodyBytes = PeerHelloBody.encode(bodyMsg).finish()
        const sig = crypto.sign("RSA-SHA256", Buffer.from(bodyBytes), crypto.createPrivateKey(params.privateKeyPem))
        const hello = PeerHello.encode(PeerHello.create({ body: bodyMsg, signature: sig })).finish()
        const helloWrap = encodeWrapper(params.root, { seq: 2, command: -12001, data: hello })
        const helloCipher = xorWithKeyFile(params.fd, peerOffset, helloWrap)
        ws.send(encodeFrame({ length: helloCipher.length, magic: params.magic, version: params.version, flags: params.flagsEncrypted }, helloCipher))
        return
      }

      if (w.command === -12002) {
        clearTimeout(t)
        const conn: PeerConn = { endpoint: params.endpoint, ws, peerOffset, nextSeq: 10, pending: new Map() }
        ws.on("message", (ev2) => {
          const f2 = decodeFrame(new Uint8Array(ev2 as Buffer))
          const plain2 = xorWithKeyFile(params.fd, conn.peerOffset, f2.cipherPayload)
          const w2 = decodeWrapper(params.root, plain2)
          const p = conn.pending.get(w2.seq)
          if (!p) return
          if (w2.command === 1004) {
            p.resolve(w2.data ?? new Uint8Array())
          } else {
            p.reject()
          }
          conn.pending.delete(w2.seq)
        })
        resolve(finish(conn)!)
        return
      }
    })

    ws.on("close", () => {
      clearTimeout(t)
      resolve(finish(null)!)
    })
    ws.on("error", () => {
      clearTimeout(t)
      resolve(finish(null)!)
    })
  })
}

function peerGet(conn: PeerConn, root: any, fd: number, magic: number, version: number, flagsEncrypted: number, fileHashHex: string): Promise<Uint8Array> {
  const FileGetRequest = root.lookupType("p2pws.FileGetRequest")
  const req = FileGetRequest.encode({ file_hash_sha256: Buffer.from(fileHashHex, "hex") }).finish()
  const seq = conn.nextSeq++
  const wrap = encodeWrapper(root, { seq, command: 1003, data: req })
  const cipher = xorWithKeyFile(fd, conn.peerOffset, wrap)
  conn.ws.send(encodeFrame({ length: cipher.length, magic, version, flags: flagsEncrypted }, cipher))
  return new Promise<Uint8Array>((resolve, reject) => {
    conn.pending.set(seq, { resolve, reject })
  })
}

async function centerLookupEndpoints(params: {
  cfgPathAbs: string
  targetNodeId64: string
}): Promise<{ endpoints: Endpoint[]; nodeKey?: Uint8Array; nodeId64?: string }> {
  const cfg = loadClientConfig(params.cfgPathAbs)
  const cfgDir = path.dirname(params.cfgPathAbs)
  const root = await loadProtoRoot()
  const magic = parseIntMaybeHex(cfg.magic, 0x1234)
  const version = cfg.version ?? 1
  const flagsPlain = cfg.flags_plain ?? 4
  const flagsEncrypted = cfg.flags_encrypted ?? 5
  const maxFramePayload = cfg.max_frame_payload ?? 4 * 1024 * 1024
  const cryptoMode = (cfg.crypto_mode ?? "KEYFILE_XOR_RSA_OAEP").trim()

  const keyfileAbs = path.resolve(cfgDir, cfg.keyfile_path)
  const fd = fs.openSync(keyfileAbs, "r")
  const keyHex = crypto.createHash("sha256").update(fs.readFileSync(keyfileAbs)).digest("hex")
  const keyId = Buffer.from(keyHex, "hex")
  const privAbs = path.resolve(cfgDir, cfg.rsa_private_key_pem_path)
  const privateKeyPem = fs.readFileSync(privAbs, "utf-8")
  const privObj = crypto.createPrivateKey(privateKeyPem)
  const pubObj = crypto.createPublicKey(privObj)
  const clientPubDer = pubObj.export({ type: "spki", format: "der" }) as Buffer
  const nodeId64Str = String(parseInt(cfg.user_id, 10) || 1)

  const Hand = root.lookupType("p2pws.Hand")
  const HandAckPlain = root.lookupType("p2pws.HandAckPlain")
  const CenterHelloBody = root.lookupType("p2pws.CenterHelloBody")
  const CenterHello = root.lookupType("p2pws.CenterHello")
  const GetNode = root.lookupType("p2pws.GetNode")
  const GetNodeAck = root.lookupType("p2pws.GetNodeAck")

  let centerOffset = -1

  return new Promise((resolve, reject) => {
    const ws = new WebSocket(cfg.ws_url)
    ws.binaryType = "arraybuffer"
    const t = setTimeout(() => {
      try { ws.close() } catch {}
      reject(new Error("center_timeout"))
    }, 8_000)

    ws.on("open", () => {
      const handData = Hand.encode(Hand.create({ client_pubkey: clientPubDer, key_ids: [keyId], max_frame_payload: maxFramePayload, client_id: cfg.user_id })).finish()
      const wrapper = encodeWrapper(root, { seq: 1, command: -10001, data: handData })
      ws.send(encodeFrame({ length: wrapper.length, magic, version, flags: flagsPlain }, wrapper))
    })

    ws.on("message", (ev) => {
      const f = decodeFrame(new Uint8Array(ev as Buffer))
      const plainPayload = centerOffset >= 0 ? xorWithKeyFile(fd, centerOffset, f.cipherPayload) : f.cipherPayload
      const w = decodeWrapper(root, plainPayload)

      if (w.command === -10002) {
        const ack = HandAckPlain.decode(rsaOaepSha256Decrypt(privateKeyPem, w.data ?? new Uint8Array())) as any
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
        const helloCipher = xorWithKeyFile(fd, centerOffset, helloWrap)
        ws.send(encodeFrame({ length: helloCipher.length, magic, version, flags: flagsEncrypted }, helloCipher))
        return
      }

      if (w.command === -11002) {
        const req = GetNode.encode(GetNode.create({ node_id64: params.targetNodeId64 })).finish()
        const wrap = encodeWrapper(root, { seq: 3, command: -11010, data: req })
        const cipher = xorWithKeyFile(fd, centerOffset, wrap)
        ws.send(encodeFrame({ length: cipher.length, magic, version, flags: flagsEncrypted }, cipher))
        return
      }

      if (w.command === -11011) {
        clearTimeout(t)
        const r = GetNodeAck.decode(w.data ?? new Uint8Array()) as any
        try { ws.close() } catch {}
        if (!r.found) {
          reject(new Error("node_not_found"))
          return
        }
        const endpoints: Endpoint[] = Array.isArray(r.endpoints) ? r.endpoints : []
        resolve({ endpoints, nodeKey: r.node_key ? new Uint8Array(r.node_key) : undefined, nodeId64: String(r.node_id64) })
        return
      }
    })

    ws.on("error", (e) => {
      clearTimeout(t)
      reject(e)
    })
    ws.on("close", () => {
      clearTimeout(t)
    })
  })
}

async function main() {
  const argv = process.argv.slice(2)
  if (argv.length < 2) {
    console.log("Usage: tsx src/swarm_cli.ts <config.yaml> seed <file> [--chunk-size <bytes>]")
    console.log("Usage: tsx src/swarm_cli.ts <config.yaml> fetch <manifest_hash_hex> <outpath> --peers <id64,id64,...> [--max-parallel <n>]")
    process.exit(1)
  }
  const cfgPathAbs = path.resolve(argv[0])
  const cmd = argv[1]
  const cfg = loadClientConfig(cfgPathAbs)
  const cfgDir = path.dirname(cfgPathAbs)
  const storageDir = storageDirFor(cfgPathAbs, cfg.user_id)

  if (cmd === "seed") {
    const filePathAbs = path.resolve(argv[2] ?? "")
    if (!filePathAbs || !fs.existsSync(filePathAbs)) {
      console.error("file required")
      process.exit(1)
    }
    const idx = argv.findIndex((a) => a === "--chunk-size")
    const chunkSize = idx >= 0 ? parseInt(argv[idx + 1] ?? "", 10) : 1024 * 1024
    const { manifest, chunks } = buildManifest(filePathAbs, chunkSize > 0 ? chunkSize : 1024 * 1024)
    for (const c of chunks) writeByHash(storageDir, c.sha256_hex, c.content)
    const manifestBytes = Buffer.from(JSON.stringify(manifest), "utf-8")
    const manifestHash = sha256Hex(manifestBytes)
    writeByHash(storageDir, manifestHash, manifestBytes)
    console.log(manifestHash)
    return
  }

  if (cmd === "fetch") {
    const manifestHash = String(argv[2] ?? "")
    const outPathAbs = path.resolve(argv[3] ?? "")
    const peersIdx = argv.findIndex((a) => a === "--peers")
    const peers = peersIdx >= 0 ? parsePeers(argv[peersIdx + 1]) : []
    if (!manifestHash || manifestHash.length !== 64) {
      console.error("manifest_hash_hex required")
      process.exit(1)
    }
    if (!outPathAbs) {
      console.error("outpath required")
      process.exit(1)
    }
    if (peers.length === 0) {
      console.error("--peers required")
      process.exit(1)
    }

    const maxParIdx = argv.findIndex((a) => a === "--max-parallel")
    const maxParallel = maxParIdx >= 0 ? Math.max(1, parseInt(argv[maxParIdx + 1] ?? "8", 10)) : 8

    const root = await loadProtoRoot()
    const magic = parseIntMaybeHex(cfg.magic, 0x1234)
    const version = cfg.version ?? 1
    const flagsPlain = cfg.flags_plain ?? 4
    const flagsEncrypted = cfg.flags_encrypted ?? 5
    const cryptoMode = (cfg.crypto_mode ?? "KEYFILE_XOR_RSA_OAEP").trim()
    const keyfileAbs = path.resolve(cfgDir, cfg.keyfile_path)
    const fd = fs.openSync(keyfileAbs, "r")
    const keyHex = crypto.createHash("sha256").update(fs.readFileSync(keyfileAbs)).digest("hex")
    const keyId = Buffer.from(keyHex, "hex")
    const privAbs = path.resolve(cfgDir, cfg.rsa_private_key_pem_path)
    const privateKeyPem = fs.readFileSync(privAbs, "utf-8")
    const privObj = crypto.createPrivateKey(privateKeyPem)
    const pubObj = crypto.createPublicKey(privObj)
    const clientPubDer = pubObj.export({ type: "spki", format: "der" }) as Buffer
    const nodeId64Str = String(parseInt(cfg.user_id, 10) || 1)

    const peerInfos: Array<{ nodeId64: string; endpoints: Endpoint[] }> = []
    for (const id of peers) {
      const r = await centerLookupEndpoints({ cfgPathAbs, targetNodeId64: id })
      peerInfos.push({ nodeId64: id, endpoints: (r.endpoints ?? []).sort((a, b) => endpointScore(b) - endpointScore(a)) })
    }

    const conns: PeerConn[] = []
    for (const p of peerInfos) {
      const c = await connectPeerHappyEyeballs({
        endpoints: p.endpoints,
        root,
        keyId,
        clientPubDer,
        privateKeyPem,
        nodeId64Str,
        cryptoMode,
        fd,
        magic,
        version,
        flagsPlain,
        flagsEncrypted,
        maxParallel: 2,
        staggerMs: 200,
        handshakeTimeoutMs: 4_000,
      })
      if (c) conns.push(c)
    }
    if (conns.length === 0) {
      console.error("no peer connections")
      process.exit(1)
    }

    const manifestBytes = await (async () => {
      for (const c of conns) {
        try {
          const data = await peerGet(c, root, fd, magic, version, flagsEncrypted, manifestHash)
          return Buffer.from(data)
        } catch {
        }
      }
      return null
    })()
    if (!manifestBytes) {
      console.error("manifest_not_found_on_peers")
      process.exit(1)
    }
    const manifest: Manifest = JSON.parse(manifestBytes.toString("utf-8"))

    const tmpDir = path.resolve(path.dirname(outPathAbs), `.p2p_tmp_${manifestHash}`)
    fs.mkdirSync(tmpDir, { recursive: true })

    const need = new Set<string>(manifest.chunks.map((c) => c.sha256_hex))
    const inFlight = new Set<string>()
    const completed = new Set<string>()

    const pickPeer = (() => {
      let i = 0
      return () => {
        const c = conns[i % conns.length]
        i++
        return c
      }
    })()

    const workers: Promise<void>[] = []
    const runOne = async () => {
      while (completed.size < manifest.chunks.length) {
        const next = Array.from(need).find((h) => !inFlight.has(h))
        if (!next) {
          await new Promise((r) => setTimeout(r, 50))
          continue
        }
        inFlight.add(next)
        const c = pickPeer()
        try {
          const data = await peerGet(c, root, fd, magic, version, flagsEncrypted, next)
          const buf = Buffer.from(data)
          const ok = sha256Hex(buf) === next
          if (!ok) throw new Error("bad_hash")
          fs.writeFileSync(path.join(tmpDir, next), buf)
          completed.add(next)
          need.delete(next)
        } catch {
          inFlight.delete(next)
          await new Promise((r) => setTimeout(r, 100))
          continue
        }
        inFlight.delete(next)
      }
    }
    for (let i = 0; i < maxParallel; i++) workers.push(runOne())
    await Promise.all(workers)

    const outFd = fs.openSync(outPathAbs, "w")
    try {
      for (const ch of manifest.chunks.sort((a, b) => a.index - b.index)) {
        const p = path.join(tmpDir, ch.sha256_hex)
        const buf = fs.readFileSync(p)
        fs.writeSync(outFd, buf, 0, buf.length)
      }
    } finally {
      fs.closeSync(outFd)
    }
    const outBytes = fs.readFileSync(outPathAbs)
    if (sha256Hex(outBytes) !== manifest.file_sha256_hex) {
      console.error("final_hash_mismatch")
      process.exit(2)
    }
    console.log("ok")
    return
  }

  console.error("unknown cmd")
  process.exit(1)
}

await main()
