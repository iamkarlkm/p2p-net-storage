import fs from "node:fs"
import path from "node:path"
import crypto from "node:crypto"
import { WebSocketServer, WebSocket } from "ws"

import { decodeFrame, encodeFrame } from "./frame.js"
import { loadProtoRoot } from "./proto.js"
import { decodeWrapper, encodeWrapper } from "./wrapper.js"
import { ClientConfig } from "./config.js"
import { rsaOaepSha256Decrypt } from "./handshake.js"

export class PeerNodeDaemon {
  private cfg: ClientConfig
  private cfgDir: string
  private root: any
  private magic: number
  private version: number
  private flagsPlain: number
  private flagsEncrypted: number
  private maxFramePayload: number
  private listenPort: number
  
  private keyId: Buffer
  private fd: number
  private privateKeyPem: string
  private clientPubDer: Buffer
  private nodeKey32: Buffer
  private nodeId64Str: string
  private storageDir: string
  private reportedEndpoints: Array<{ transport: string; addr: string }>
  private presenceCachePathAbs?: string
  private cooldownCachePathAbs?: string
  private enableConnectHint: boolean
  private centerSeq: number = 0
  
  private wss?: WebSocketServer
  private centerWs?: WebSocket
  private centerOffset: number = -1
  private renewTimer?: NodeJS.Timeout
  private endpointCooldown = new Map<string, { untilMs: number; fails: number }>()

  constructor(cfg: ClientConfig, cfgDir: string) {
    this.cfg = cfg
    this.cfgDir = cfgDir
    this.magic = typeof cfg.magic === "string" ? parseInt(cfg.magic, 16) : (cfg.magic ?? 0x1234)
    this.version = cfg.version ?? 1
    this.flagsPlain = cfg.flags_plain ?? 4
    this.flagsEncrypted = cfg.flags_encrypted ?? 5
    this.maxFramePayload = cfg.max_frame_payload ?? 4 * 1024 * 1024
    this.listenPort = parseInt(String(cfg.listen_port ?? "0"), 10)

    const keyfileAbs = path.resolve(this.cfgDir, cfg.keyfile_path)
    this.fd = fs.openSync(keyfileAbs, "r")
    const keyHex = crypto.createHash("sha256").update(fs.readFileSync(keyfileAbs)).digest("hex")
    this.keyId = Buffer.from(keyHex, "hex")

    const privAbs = path.resolve(this.cfgDir, cfg.rsa_private_key_pem_path!)
    this.privateKeyPem = fs.readFileSync(privAbs, "utf-8")
    const privObj = crypto.createPrivateKey(this.privateKeyPem)
    const pubObj = crypto.createPublicKey(privObj)
    this.clientPubDer = pubObj.export({ type: "spki", format: "der" }) as Buffer
    this.nodeKey32 = Buffer.from(crypto.createHash("sha256").update(this.clientPubDer).digest())
    this.nodeId64Str = String(parseInt(cfg.user_id, 10) || 1)
    
    this.storageDir = path.resolve(this.cfgDir, `../data/node_${this.cfg.user_id}`)
    fs.mkdirSync(this.storageDir, { recursive: true })

    this.reportedEndpoints = Array.isArray(cfg.reported_endpoints) ? cfg.reported_endpoints.slice() : []
    this.enableConnectHint = Boolean(cfg.enable_connect_hint)
    if (cfg.presence_cache_path) {
      this.presenceCachePathAbs = path.resolve(this.cfgDir, cfg.presence_cache_path)
    }
    if (cfg.cooldown_cache_path) {
      this.cooldownCachePathAbs = path.resolve(this.cfgDir, cfg.cooldown_cache_path)
    } else {
      this.cooldownCachePathAbs = this.presenceCachePathAbs
    }
    const cachedObserved = this.loadPresenceCacheObservedAddr()
    if (cachedObserved) {
      this.reportedEndpoints = this.applyObservedIpToEndpoints(this.reportedEndpoints, cachedObserved)
    }
    this.loadCooldownCache()
  }

  public async start() {
    this.root = await loadProtoRoot()
    this.startServer()
    this.connectCenter()
  }

  private xorWithFile(data: Uint8Array, off: number): Uint8Array {
    const slice = Buffer.allocUnsafe(data.length)
    let pos = 0
    while (pos < slice.length) {
      const n = fs.readSync(this.fd, slice, pos, slice.length - pos, off + pos)
      if (n <= 0) throw new Error("read keyfile failed")
      pos += n
    }
    const out = new Uint8Array(data.length)
    for (let i = 0; i < data.length; i++) out[i] = data[i] ^ slice[i]
    return out
  }

  private startServer() {
    if (this.listenPort <= 0) return
    this.wss = new WebSocketServer({ port: this.listenPort })
    this.wss.on("connection", (ws) => {
      console.log(`[PeerDaemon] Accepted incoming connection`)
      let peerOffset = -1
      let peerHandshakeDone = false

      ws.on("message", (data: Buffer) => {
        const f = decodeFrame(new Uint8Array(data))
        if (!peerHandshakeDone && peerOffset < 0) {
          const w = decodeWrapper(this.root, f.cipherPayload)
          if (w.command === -10001) {
            const hand = this.root.lookupType("p2pws.Hand").decode(w.data ?? new Uint8Array()) as any
            peerOffset = crypto.randomInt(0, 1024)
            const ackPlain = this.root.lookupType("p2pws.HandAckPlain").encode({
              session_id: crypto.randomBytes(16),
              selected_key_id: this.keyId,
              offset: peerOffset,
              max_frame_payload: this.maxFramePayload,
              header_policy_id: 0
            }).finish()
            
            const pubKey = crypto.createPublicKey({ key: Buffer.from(hand.client_pubkey), type: "spki", format: "der" })
            const encryptedAck = crypto.publicEncrypt({ key: pubKey, padding: crypto.constants.RSA_PKCS1_OAEP_PADDING, oaepHash: "sha256" }, ackPlain)
            
            const ackWrap = encodeWrapper(this.root, { seq: w.seq, command: -10002, data: encryptedAck })
            ws.send(encodeFrame({ length: ackWrap.length, magic: this.magic, version: this.version, flags: this.flagsPlain }, ackWrap))
          }
          return
        }

        const plainPayload = this.xorWithFile(f.cipherPayload, peerOffset)
        const w = decodeWrapper(this.root, plainPayload)
        
        if (w.command === -12001) {
          const hello = this.root.lookupType("p2pws.PeerHello").decode(w.data ?? new Uint8Array()) as any
          console.log(`[PeerDaemon] Handshake complete with node_id64=${hello.body.node_id64}`)
          peerHandshakeDone = true
          const ack = this.root.lookupType("p2pws.PeerHelloAck").encode({
            node_key: Buffer.from(crypto.createHash("sha256").update(this.clientPubDer).digest()),
            server_time_ms: Date.now()
          }).finish()
          const ackWrap = encodeWrapper(this.root, { seq: w.seq, command: -12002, data: ack })
          const cipher = this.xorWithFile(ackWrap, peerOffset)
          ws.send(encodeFrame({ length: cipher.length, magic: this.magic, version: this.version, flags: this.flagsEncrypted }, cipher))
          return
        }

        if (w.command === 1001) {
          const req = this.root.lookupType("p2pws.FilePutRequest").decode(w.data ?? new Uint8Array()) as any
          const hash = Buffer.from(req.file_hash_sha256).toString("hex")
          console.log(`[PeerDaemon] Saving file ${req.file_name} (${hash})`)
          fs.writeFileSync(path.join(this.storageDir, hash), req.content)
          const resp = this.root.lookupType("p2pws.FilePutResponse").encode({ success: true, file_hash_sha256: req.file_hash_sha256 }).finish()
          const respWrap = encodeWrapper(this.root, { seq: w.seq, command: 1002, data: resp })
          const cipher = this.xorWithFile(respWrap, peerOffset)
          ws.send(encodeFrame({ length: cipher.length, magic: this.magic, version: this.version, flags: this.flagsEncrypted }, cipher))
          return
        }

        if (w.command === 1003) {
          const req = this.root.lookupType("p2pws.FileGetRequest").decode(w.data ?? new Uint8Array()) as any
          const hash = Buffer.from(req.file_hash_sha256).toString("hex")
          const filePath = path.join(this.storageDir, hash)
          let resp: Uint8Array
          if (fs.existsSync(filePath)) {
            resp = this.root.lookupType("p2pws.FileGetResponse").encode({ found: true, content: fs.readFileSync(filePath) }).finish()
          } else {
            resp = this.root.lookupType("p2pws.FileGetResponse").encode({ found: false }).finish()
          }
          const respWrap = encodeWrapper(this.root, { seq: w.seq, command: 1004, data: resp })
          const cipher = this.xorWithFile(respWrap, peerOffset)
          ws.send(encodeFrame({ length: cipher.length, magic: this.magic, version: this.version, flags: this.flagsEncrypted }, cipher))
          return
        }
      })
    })
    console.log(`[PeerDaemon] Listening for peers on port ${this.listenPort}`)
  }

  private loadJsonFile(p: string | undefined): any | null {
    if (!p) return null
    try {
      if (!fs.existsSync(p)) return null
      const raw = fs.readFileSync(p, "utf-8")
      return JSON.parse(raw)
    } catch {
      return null
    }
  }

  private writeJsonFileMerge(p: string | undefined, patch: any) {
    if (!p) return
    try {
      const cur = this.loadJsonFile(p) ?? {}
      const next = { ...cur, ...patch }
      fs.mkdirSync(path.dirname(p), { recursive: true })
      fs.writeFileSync(p, JSON.stringify(next, null, 2), "utf-8")
    } catch {}
  }

  private loadPresenceCacheObservedAddr(): string | null {
    const j = this.loadJsonFile(this.presenceCachePathAbs)
    const addr = j?.observed_endpoint?.addr
    if (!addr) return null
    return String(addr)
  }

  private savePresenceCacheObserved(observed: { transport?: string; addr?: string } | null | undefined) {
    const addr = observed?.addr ? String(observed.addr) : ""
    const transport = observed?.transport ? String(observed.transport) : "ws"
    if (!addr) return
    this.writeJsonFileMerge(this.presenceCachePathAbs, { observed_endpoint: { transport, addr } })
  }

  private endpointKey(e: any) {
    return `${String(e?.transport ?? "")}|${String(e?.addr ?? "")}`
  }

  private loadCooldownCache() {
    const j = this.loadJsonFile(this.cooldownCachePathAbs)
    const m = j?.endpoint_cooldown
    if (!m || typeof m !== "object") return
    for (const [k, v] of Object.entries(m)) {
      const vv = v as any
      const untilMs = Number(vv?.untilMs)
      const fails = Number(vv?.fails)
      if (!Number.isFinite(untilMs) || !Number.isFinite(fails)) continue
      this.endpointCooldown.set(String(k), { untilMs, fails })
    }
  }

  private saveCooldownCache() {
    const obj: any = {}
    for (const [k, v] of this.endpointCooldown.entries()) {
      obj[k] = { untilMs: v.untilMs, fails: v.fails }
    }
    this.writeJsonFileMerge(this.cooldownCachePathAbs, { endpoint_cooldown: obj })
  }

  private isCoolDownEndpoint(e: any) {
    const k = this.endpointKey(e)
    const s = this.endpointCooldown.get(k)
    return Boolean(s && s.untilMs > Date.now())
  }

  private markEndpointFail(e: any) {
    const k = this.endpointKey(e)
    const prev = this.endpointCooldown.get(k) ?? { untilMs: 0, fails: 0 }
    const fails = prev.fails + 1
    const baseCooldownMs = 5_000
    const maxCooldownMs = 5 * 60_000
    const cd = Math.min(maxCooldownMs, baseCooldownMs * Math.pow(2, Math.min(6, fails - 1)))
    this.endpointCooldown.set(k, { untilMs: Date.now() + cd, fails })
    this.saveCooldownCache()
  }

  private markEndpointSuccess(e: any) {
    const k = this.endpointKey(e)
    if (this.endpointCooldown.has(k)) {
      this.endpointCooldown.delete(k)
      this.saveCooldownCache()
    }
  }

  private applyObservedIpToEndpoints(endpoints: Array<{ transport: string; addr: string }>, observedAddr: string) {
    const host = String(observedAddr).split(":")[0]
    if (!host) return endpoints
    return endpoints.map((e) => {
      const addr = String(e.addr ?? "")
      const idx = addr.lastIndexOf(":")
      if (idx <= 0) return e
      const port = addr.slice(idx + 1)
      return { ...e, addr: `${host}:${port}` }
    })
  }

  private sendCenterHello(seq: number) {
    if (this.centerOffset < 0 || !this.centerWs) return
    const bodyMsg = this.root.lookupType("p2pws.CenterHelloBody").create({
      node_id64: this.nodeId64Str,
      pubkey_spki_der: this.clientPubDer,
      reported_endpoints: this.reportedEndpoints,
      caps: { max_frame_payload: this.maxFramePayload, magic: this.magic, version: this.version, flags_plain: this.flagsPlain, flags_encrypted: this.flagsEncrypted },
      timestamp_ms: String(Date.now()),
      crypto_mode: this.cfg.crypto_mode ?? "KEYFILE_XOR_RSA_OAEP",
    })
    const bodyBytes = this.root.lookupType("p2pws.CenterHelloBody").encode(bodyMsg).finish()
    const sig = crypto.sign("RSA-SHA256", Buffer.from(bodyBytes), crypto.createPrivateKey(this.privateKeyPem))
    const hello = this.root.lookupType("p2pws.CenterHello").encode({ body: bodyMsg, signature: sig }).finish()
    const wrap = encodeWrapper(this.root, { seq, command: -11001, data: hello })
    const cipher = this.xorWithFile(wrap, this.centerOffset)
    this.centerWs.send(encodeFrame({ length: cipher.length, magic: this.magic, version: this.version, flags: this.flagsEncrypted }, cipher))
  }

  private connectCenter() {
    this.centerWs = new WebSocket(this.cfg.ws_url)
    this.centerWs.binaryType = "arraybuffer"

    this.centerWs.on("open", () => {
      console.log(`[PeerDaemon] Connecting to Center...`)
      this.centerSeq = 1
      const handData = this.root.lookupType("p2pws.Hand").encode({ client_pubkey: this.clientPubDer, key_ids: [this.keyId], max_frame_payload: this.maxFramePayload, client_id: this.cfg.user_id }).finish()
      const wrapper = encodeWrapper(this.root, { seq: this.centerSeq++, command: -10001, data: handData })
      this.centerWs!.send(encodeFrame({ length: wrapper.length, magic: this.magic, version: this.version, flags: this.flagsPlain }, wrapper))
    })

    this.centerWs.on("message", (ev) => {
      const f = decodeFrame(new Uint8Array(ev as Buffer))
      const plainPayload = this.centerOffset >= 0 ? this.xorWithFile(f.cipherPayload, this.centerOffset) : f.cipherPayload
      const w = decodeWrapper(this.root, plainPayload)
      
      if (w.command === -10002) {
        const ack = this.root.lookupType("p2pws.HandAckPlain").decode(rsaOaepSha256Decrypt(this.privateKeyPem, w.data ?? new Uint8Array())) as any
        this.centerOffset = Number(ack.offset)
        this.sendCenterHello(this.centerSeq++)
        return
      }

      if (w.command === -11002) {
        const CenterHelloAck = this.root.lookupType("p2pws.CenterHelloAck")
        const ack = CenterHelloAck.decode(w.data ?? new Uint8Array()) as any
        if (ack?.observed_endpoint?.addr) {
          const observedAddr = String(ack.observed_endpoint.addr)
          const cached = this.loadPresenceCacheObservedAddr()
          if (cached !== observedAddr) {
            this.savePresenceCacheObserved(ack.observed_endpoint)
            const next = this.applyObservedIpToEndpoints(this.reportedEndpoints, observedAddr)
            if (JSON.stringify(next) !== JSON.stringify(this.reportedEndpoints)) {
              this.reportedEndpoints = next
              this.sendCenterHello(this.centerSeq++)
            }
          }
        }
        console.log(`[PeerDaemon] Successfully joined P2P network as node ${this.nodeId64Str}`)
        if (this.cfg.renew_seconds && this.cfg.renew_seconds > 0) {
          this.renewTimer = setInterval(() => this.sendRenew(), this.cfg.renew_seconds! * 1000)
        }
        return
      }

      if (w.command === -11031 && this.enableConnectHint) {
        const IncomingHint = this.root.lookupType("p2pws.IncomingHint")
        const r = IncomingHint.decode(w.data ?? new Uint8Array()) as any
        const eps = Array.isArray(r.source_endpoints) ? r.source_endpoints : []
        if (eps.length > 0) {
          this.dialHintEndpoints(eps)
        }
        return
      }

      if (w.command === -11012 && this.centerOffset >= 0) {
        const RelayData = this.root.lookupType("p2pws.RelayData")
        const rd = RelayData.decode(w.data ?? new Uint8Array()) as any
        if (!rd.target_node_key || Buffer.from(rd.target_node_key).compare(this.nodeKey32) !== 0) return
        if (!rd.payload) return
        let inner: any
        try {
          inner = decodeWrapper(this.root, new Uint8Array(rd.payload))
        } catch {
          return
        }

        if (inner.command === 1001) {
          const req = this.root.lookupType("p2pws.FilePutRequest").decode(inner.data ?? new Uint8Array()) as any
          const hash = Buffer.from(req.file_hash_sha256).toString("hex")
          fs.writeFileSync(path.join(this.storageDir, hash), req.content)
          const resp = this.root.lookupType("p2pws.FilePutResponse").encode({ success: true, file_hash_sha256: req.file_hash_sha256 }).finish()
          const respWrap = encodeWrapper(this.root, { seq: inner.seq, command: 1002, data: resp })
          this.sendRelay(rd.source_node_id64, rd.source_node_key, respWrap, w.seq)
          return
        }

        if (inner.command === 1003) {
          const req = this.root.lookupType("p2pws.FileGetRequest").decode(inner.data ?? new Uint8Array()) as any
          const hash = Buffer.from(req.file_hash_sha256).toString("hex")
          const filePath = path.join(this.storageDir, hash)
          let resp: Uint8Array
          if (fs.existsSync(filePath)) {
            resp = this.root.lookupType("p2pws.FileGetResponse").encode({ found: true, content: fs.readFileSync(filePath) }).finish()
          } else {
            resp = this.root.lookupType("p2pws.FileGetResponse").encode({ found: false }).finish()
          }
          const respWrap = encodeWrapper(this.root, { seq: inner.seq, command: 1004, data: resp })
          this.sendRelay(rd.source_node_id64, rd.source_node_key, respWrap, w.seq)
          return
        }
      }
    })
  }

  private async dialHintEndpoints(endpoints: any[]) {
    const eps = endpoints.filter((e) => !this.isCoolDownEndpoint(e)).sort((a, b) => this.endpointScore(b) - this.endpointScore(a))
    if (eps.length === 0) return

    const maxParallel = 4
    const staggerMs = 250
    const timeoutMs = 4_000

    const queue = eps.slice()
    const active: Array<Promise<{ ok: boolean; ep: any }>> = []

    const spawn = async (ep: any) => {
      const ok = await this.tryHandshakeOnce(ep, timeoutMs)
      return { ok, ep }
    }

    const startMore = () => {
      while (active.length < maxParallel && queue.length > 0) {
        const ep = queue.shift()
        if (!ep) break
        if (this.isCoolDownEndpoint(ep)) continue
        active.push(spawn(ep))
      }
    }

    startMore()
    while (active.length > 0) {
      const raced = await Promise.race(active.map((p, i) => p.then((v) => ({ i, v }))))
      active.splice(raced.i, 1)
      if (raced.v.ok) {
        this.markEndpointSuccess(raced.v.ep)
        return
      }
      this.markEndpointFail(raced.v.ep)
      if (queue.length > 0) {
        await new Promise((r) => setTimeout(r, staggerMs))
        startMore()
      }
    }
  }

  private endpointHost(addr: string): string {
    const s = String(addr ?? "")
    const idx = s.lastIndexOf(":")
    if (idx <= 0) return s
    return s.slice(0, idx)
  }

  private isPrivateIpv4(host: string): boolean {
    const h = host.trim()
    if (!/^\d+\.\d+\.\d+\.\d+$/.test(h)) return false
    const [a, b] = h.split(".").map((x) => parseInt(x, 10))
    if (a === 10) return true
    if (a === 127) return true
    if (a === 192 && b === 168) return true
    if (a === 172 && b >= 16 && b <= 31) return true
    return false
  }

  private endpointScore(e: any): number {
    const host = this.endpointHost(String(e?.addr ?? ""))
    if (host === "127.0.0.1" || host === "localhost" || host === "0.0.0.0") return -1000
    if (this.isPrivateIpv4(host)) return 10
    if (/^\d+\.\d+\.\d+\.\d+$/.test(host)) return 100
    return 30
  }

  private tryHandshakeOnce(endpoint: any, timeoutMs: number): Promise<boolean> {
    const url = `${endpoint.transport}://${endpoint.addr}`
    const ws = new WebSocket(url)
    ws.binaryType = "arraybuffer"
    let peerOffset = -1
    let done = false

    const finish = (ok: boolean) => {
      if (done) return
      done = true
      try { ws.close() } catch {}
      return ok
    }

    return new Promise<boolean>((resolve) => {
      const t = setTimeout(() => resolve(finish(false)!), timeoutMs)

      ws.on("open", () => {
        const handData = this.root.lookupType("p2pws.Hand").encode({ client_pubkey: this.clientPubDer, key_ids: [this.keyId], max_frame_payload: this.maxFramePayload, client_id: this.cfg.user_id }).finish()
        const wrapper = encodeWrapper(this.root, { seq: 1, command: -10001, data: handData })
        ws.send(encodeFrame({ length: wrapper.length, magic: this.magic, version: this.version, flags: this.flagsPlain }, wrapper))
      })

      ws.on("message", (ev) => {
        const f = decodeFrame(new Uint8Array(ev as Buffer))
        const plainPayload = peerOffset >= 0 ? this.xorWithFile(f.cipherPayload, peerOffset) : f.cipherPayload
        const w = decodeWrapper(this.root, plainPayload)

        if (w.command === -10002) {
          const enc = w.data ?? new Uint8Array()
          const ack = this.root.lookupType("p2pws.HandAckPlain").decode(rsaOaepSha256Decrypt(this.privateKeyPem, enc)) as any
          peerOffset = Number(ack.offset)

          const bodyMsg = this.root.lookupType("p2pws.PeerHelloBody").create({
            node_id64: this.nodeId64Str,
            pubkey_spki_der: this.clientPubDer,
            timestamp_ms: String(Date.now()),
            crypto_mode: this.cfg.crypto_mode ?? "KEYFILE_XOR_RSA_OAEP",
          })
          const bodyBytes = this.root.lookupType("p2pws.PeerHelloBody").encode(bodyMsg).finish()
          const sig = crypto.sign("RSA-SHA256", Buffer.from(bodyBytes), crypto.createPrivateKey(this.privateKeyPem))
          const hello = this.root.lookupType("p2pws.PeerHello").encode({ body: bodyMsg, signature: sig }).finish()
          const helloWrap = encodeWrapper(this.root, { seq: 2, command: -12001, data: hello })
          const helloCipher = this.xorWithFile(helloWrap, peerOffset)
          ws.send(encodeFrame({ length: helloCipher.length, magic: this.magic, version: this.version, flags: this.flagsEncrypted }, helloCipher))
          return
        }

        if (w.command === -12002) {
          clearTimeout(t)
          resolve(finish(true)!)
          return
        }
      })

      ws.on("error", () => {
        clearTimeout(t)
        resolve(finish(false)!)
      })
      ws.on("close", () => {
        clearTimeout(t)
        resolve(finish(false)!)
      })
    })
  }

  private sendRelay(targetNodeId64: any, targetNodeKey: any, payload: Uint8Array, seq: number) {
    if (!this.centerWs || this.centerOffset < 0) return
    const RelayData = this.root.lookupType("p2pws.RelayData")
    const data = RelayData.encode({
      target_node_id64: String(targetNodeId64),
      target_node_key: targetNodeKey,
      source_node_id64: this.nodeId64Str,
      source_node_key: this.nodeKey32,
      payload
    }).finish()
    const wrap = encodeWrapper(this.root, { seq, command: -11012, data })
    const cipher = this.xorWithFile(wrap, this.centerOffset)
    this.centerWs.send(encodeFrame({ length: cipher.length, magic: this.magic, version: this.version, flags: this.flagsEncrypted }, cipher))
  }

  private sendRenew() {
    this.sendCenterHello(this.centerSeq++)
  }

  public stop() {
    if (this.renewTimer) clearInterval(this.renewTimer)
    if (this.wss) this.wss.close()
    if (this.centerWs) this.centerWs.close()
    fs.closeSync(this.fd)
  }
}
