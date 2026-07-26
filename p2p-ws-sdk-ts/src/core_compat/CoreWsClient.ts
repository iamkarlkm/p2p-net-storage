import crypto from "node:crypto"
import fs from "node:fs"
import { WebSocket } from "ws"
import protobuf from "protobufjs"
import path from "node:path"

import { decodeAllCoreFrames, encodeCoreFrame } from "./core_frame.js"
import { loadCommandOrdinals } from "./ordinals.js"
import {
  decodeHandshakeResponse,
  decodeLoginResponse,
  decodeStreamP2PWrapper,
  encodeHandshakeRequest,
  encodeLoginRequest,
  encodeP2PWrapper,
  encodeStreamP2PWrapper,
  type HandshakeRequest,
  type LoginRequest,
  type P2PWrapper,
  type StreamP2PWrapper,
} from "./protostuff.js"

export type CoreWsHeartbeatConfig = { intervalMs?: number; timeoutMs?: number }
export type CoreWsTelemetry = {
  onConnect?: () => void
  onDisconnect?: () => void
  onReconnectAttempt?: (delayMs: number) => void
  onReconnectSuccess?: () => void
}
export type CoreWsReconnectConfig = {
  enabled?: boolean
  initialMs?: number
  maxMs?: number
  jitterRatio?: number
  onReconnect?: (client: CoreWsClient) => Promise<void>
}

export type CoreWsClientConfig = {
  wsUrl: string
  magic: number
  xorKeyLength?: number
  heartbeat?: CoreWsHeartbeatConfig
  reconnect?: CoreWsReconnectConfig
  maxPending?: number
  telemetry?: CoreWsTelemetry
}

type Pending = { resolve: (w: StreamP2PWrapper) => void; reject: (e: unknown) => void; t: any }
type StreamHandler = (w: StreamP2PWrapper) => void

export class CoreWsClient {
  private cfg: CoreWsClientConfig
  private ws?: WebSocket
  private seq: number = 1
  private pending = new Map<number, Pending>()
  private streamHandlers = new Map<number, StreamHandler>()
  private xorKey?: Buffer
  private ordinals: Map<string, number>
  private rpcRoot?: protobuf.Root
  private heartbeatTimer?: any
  private reconnectTimer?: any
  private lastPongAtMs: number = 0
  private reconnectDelayMs: number = 0
  private reconnecting: boolean = false
  private suppressReconnect: boolean = false

  constructor(cfg: CoreWsClientConfig) {
    this.cfg = cfg
    this.ordinals = loadCommandOrdinals()
  }

  public async connect(timeoutMs: number = 6_000): Promise<void> {
    this.suppressReconnect = false
    this.clearTimers()
    const ws = new WebSocket(this.cfg.wsUrl)
    this.ws = ws
    ws.binaryType = "arraybuffer"
    await new Promise<void>((resolve, reject) => {
      const t = setTimeout(() => reject(new Error("connect timeout")), timeoutMs)
      ws.once("open", () => {
        clearTimeout(t)
        resolve()
      })
      ws.once("error", (e) => {
        clearTimeout(t)
        reject(e)
      })
    })
    ws.on("message", (ev) => this.onMessage(ev as Buffer))
    ws.on("close", () => this.handleDisconnect())
    ws.on("error", () => this.handleDisconnect())
    ws.on("pong", () => {
      this.lastPongAtMs = Date.now()
    })
    this.lastPongAtMs = Date.now()
    this.startHeartbeat()
    this.cfg.telemetry?.onConnect?.()
  }

  public close(): void {
    this.suppressReconnect = true
    this.clearTimers()
    this.cleanup(new Error("closed"))
    try {
      this.ws?.close()
    } catch {}
    this.ws = undefined
  }

  public async request(commandName: string, data: Uint8Array, timeoutMs: number = 10_000): Promise<P2PWrapper> {
    const ws = this.ws
    if (!ws) throw new Error("not connected")
    const cmd = this.ordinals.get(commandName)
    if (cmd == null) throw new Error(`unknown command: ${commandName}`)
    const maxPending = Number(this.cfg.maxPending ?? 0) | 0
    if (maxPending > 0 && this.pending.size >= maxPending) throw new Error("too many pending requests")
    const seq = ++this.seq
    const w: P2PWrapper = { seq, commandOrdinal: cmd, data }
    await this.sendWrapper(w, commandName !== "HAND")
    return await new Promise<P2PWrapper>((resolve, reject) => {
      const t = setTimeout(() => {
        this.pending.delete(seq)
        reject(new Error("request timeout"))
      }, timeoutMs)
      this.pending.set(seq, {
        resolve: (sw) => resolve({ seq: sw.seq, commandOrdinal: sw.commandOrdinal, data: sw.data }),
        reject,
        t,
      })
    })
  }

  public allocateSeq(): number {
    return ++this.seq
  }

  public registerStreamHandler(requestId: number, handler?: StreamHandler): void {
    if (requestId <= 0) throw new Error("requestId required")
    if (!handler) {
      this.streamHandlers.delete(requestId)
      return
    }
    this.streamHandlers.set(requestId, handler)
  }

  public async sendAndAwait(wrapper: P2PWrapper, encrypt: boolean, timeoutMs: number = 10_000): Promise<P2PWrapper> {
    if (wrapper.seq <= 0) throw new Error("wrapper.seq required")
    await this.sendObject(wrapper, encrypt)
    return await new Promise<P2PWrapper>((resolve, reject) => {
      const t = setTimeout(() => {
        this.pending.delete(wrapper.seq)
        reject(new Error("request timeout"))
      }, timeoutMs)
      this.pending.set(wrapper.seq, {
        resolve: (sw) => resolve({ seq: sw.seq, commandOrdinal: sw.commandOrdinal, data: sw.data }),
        reject,
        t,
      })
    })
  }

  public async sendStreamOpen(wrapper: StreamP2PWrapper, timeoutMs: number = 10_000): Promise<P2PWrapper> {
    if (wrapper.seq <= 0) throw new Error("wrapper.seq required")
    await this.sendObject(wrapper, true)
    return await new Promise<P2PWrapper>((resolve, reject) => {
      const t = setTimeout(() => {
        this.pending.delete(wrapper.seq)
        reject(new Error("request timeout"))
      }, timeoutMs)
      this.pending.set(wrapper.seq, {
        resolve: (sw) => resolve({ seq: sw.seq, commandOrdinal: sw.commandOrdinal, data: sw.data }),
        reject,
        t,
      })
    })
  }

  public async send(wrapper: P2PWrapper | StreamP2PWrapper, encrypt: boolean): Promise<void> {
    await this.sendObject(wrapper, encrypt)
  }

  public async handshakeAndLogin(userId: string, privateKeyPemPath: string): Promise<void> {
    const keyPem = fs.readFileSync(privateKeyPemPath, "utf-8")
    const xorLen = Math.max(1, Number(this.cfg.xorKeyLength ?? 4096) | 0)
    const xorKey = crypto.randomBytes(xorLen)

    const tsMs = BigInt(Date.now())
    const nonce = crypto.createHash("sha256").update(Buffer.concat([beI64(tsMs), Buffer.from(userId, "utf-8")])).digest().subarray(0, 16)
    const encryptedXorKey = rsaPrivateEncryptPkcs1v15Large(keyPem, xorKey)

    const req0: HandshakeRequest = {
      userId,
      timestamp: tsMs,
      nonce,
      xorKeyLength: xorLen,
      encryptedXorKey,
      signature: new Uint8Array(),
    }
    const sig = crypto.sign("RSA-SHA256", handSigPayload(req0), keyPem)
    const req: HandshakeRequest = { ...req0, signature: sig }

    const hand = await this.request("HAND", encodeHandshakeRequest(req), 10_000)
    if (hand.commandOrdinal !== this.mustOrdinal("STD_OK")) throw new Error("handshake failed")
    const hresp = decodeHandshakeResponse(hand.data)
    if (!hresp.ok) throw new Error(`handshake failed: ${hresp.error}`)
    this.xorKey = xorKey

    const ltsMs = BigInt(Date.now())
    const l0: LoginRequest = { userId, timestamp: ltsMs, signature: new Uint8Array() }
    const lsig = crypto.sign("RSA-SHA256", loginSigPayload(l0), keyPem)
    const lreq: LoginRequest = { ...l0, signature: lsig }
    const login = await this.request("LOGIN", encodeLoginRequest(lreq), 10_000)
    if (login.commandOrdinal !== this.mustOrdinal("STD_OK")) throw new Error("login failed")
    const lresp = decodeLoginResponse(login.data)
    if (!lresp.ok) throw new Error(`login failed: ${lresp.error}`)
  }

  public async rpcDiscover(service: string = "", includeMethods: boolean = false, timeoutMs: number = 10_000): Promise<any> {
    const root = await this.loadRpcRoot()
    const DiscoverRequest = root.lookupType("p2p.rpc.v1.DiscoverRequest")
    const DiscoverResponse = root.lookupType("p2p.rpc.v1.DiscoverResponse")
    const req = DiscoverRequest.encode({ service, include_methods: includeMethods }).finish()
    const frame = await this.rpcUnaryRaw("RPC_DISCOVER", service, "Discover", req, timeoutMs)
    return DiscoverResponse.decode(frame.payload) as any
  }

  public async rpcHealth(service: string, timeoutMs: number = 10_000): Promise<any> {
    const root = await this.loadRpcRoot()
    const HealthCheckRequest = root.lookupType("p2p.rpc.v1.HealthCheckRequest")
    const HealthCheckResponse = root.lookupType("p2p.rpc.v1.HealthCheckResponse")
    const req = HealthCheckRequest.encode({ service }).finish()
    const frame = await this.rpcUnaryRaw("RPC_HEALTH", service, "Check", req, timeoutMs)
    return HealthCheckResponse.decode(frame.payload) as any
  }

  public async rpcEcho(message: string, timeoutMs: number = 10_000): Promise<any> {
    const root = await this.loadRpcRoot()
    const EchoRequest = root.lookupType("p2p.rpc.echo.v1.EchoRequest")
    const EchoResponse = root.lookupType("p2p.rpc.echo.v1.EchoResponse")
    const req = EchoRequest.encode({ message }).finish()
    const frame = await this.rpcUnaryRaw("RPC_UNARY", "p2p.rpc.echo.v1.EchoService", "Echo", req, timeoutMs)
    return EchoResponse.decode(frame.payload) as any
  }

  private async rpcUnaryRaw(
    commandName: string,
    service: string,
    method: string,
    requestPayload: Uint8Array,
    timeoutMs: number,
  ): Promise<any> {
    const root = await this.loadRpcRoot()
    const RpcFrame = root.lookupType("p2p.rpc.v1.RpcFrame")
    const RpcMeta = root.lookupType("p2p.rpc.v1.RpcMeta")
    const requestId = this.allocateSeq()
    const meta = RpcMeta.create({
      request_id: String(requestId),
      service,
      method,
      service_version: "",
      call_type: 1,
      deadline_epoch_ms: String(BigInt(Date.now() + Math.max(1, timeoutMs | 0))),
      codec: "protobuf",
      idempotent: false,
      method_hash: 0,
      headers: {},
    })
    const open = RpcFrame.encode({ meta, frame_type: 1, payload: requestPayload, end_of_stream: true }).finish()
    const cmd = this.ordinals.get(commandName)
    if (cmd == null) throw new Error(`unknown command: ${commandName}`)
    const resp = await this.sendAndAwait({ seq: requestId, commandOrdinal: cmd, data: open }, true, timeoutMs)
    const frame = RpcFrame.decode(resp.data) as any
    if (!frame.status) throw new Error("RPC missing status")
    if (frame.frame_type === 5) throw new Error(String(frame.status.message ?? "RPC ERROR"))
    if (frame.status.code !== 0 && (!frame.payload || frame.payload.length === 0)) throw new Error(String(frame.status.message ?? "RPC failed"))
    return frame
  }

  private async loadRpcRoot(): Promise<protobuf.Root> {
    if (this.rpcRoot) return this.rpcRoot
    const root = new protobuf.Root()
    const repoRoot = path.resolve(import.meta.dirname, "..", "..", "..", "..")
    const protoDir = path.resolve(repoRoot, "p2p-core", "src", "main", "proto")
    await root.load([path.join(protoDir, "p2p_rpc.proto"), path.join(protoDir, "p2p_rpc_echo.proto")], { keepCase: true })
    root.resolveAll()
    this.rpcRoot = root
    return root
  }

  public mustOrdinal(name: string): number {
    const v = this.ordinals.get(name)
    if (v == null) throw new Error(`missing ordinal: ${name}`)
    return v
  }

  private async sendWrapper(w: P2PWrapper, encrypt: boolean): Promise<void> {
    await this.sendObject(w, encrypt)
  }

  private async sendObject(obj: P2PWrapper | StreamP2PWrapper, encrypt: boolean): Promise<void> {
    const ws = this.ws
    if (!ws) throw new Error("not connected")
    const bytes = isStreamWrapper(obj) ? encodeStreamP2PWrapper(obj) : encodeP2PWrapper(obj)
    let payload = Buffer.from(bytes)
    if (encrypt && this.xorKey && payload.length > 0) xorRepeatInPlace(payload, this.xorKey)
    ws.send(encodeCoreFrame(this.cfg.magic, payload))
  }

  private onMessage(ev: Buffer): void {
    try {
      const frames = decodeAllCoreFrames(new Uint8Array(ev))
      for (const f of frames) {
        if ((f.magic | 0) !== (this.cfg.magic | 0)) continue
        let payload = Buffer.from(f.payload)
        if (this.xorKey && payload.length > 0) xorRepeatInPlace(payload, this.xorKey)
        const sw = decodeStreamP2PWrapper(payload)
        const isAck =
          sw.commandOrdinal === this.mustOrdinal("STREAM_ACK") ||
          sw.commandOrdinal === this.mustOrdinal("STD_OK") ||
          sw.commandOrdinal === this.mustOrdinal("STD_ERROR") ||
          sw.commandOrdinal === this.mustOrdinal("INVALID_PROTOCOL")
        if (isAck) {
          const p = this.pending.get(sw.seq)
          if (p) {
            this.pending.delete(sw.seq)
            clearTimeout(p.t)
            p.resolve(sw)
          }
          continue
        }

        const handler = this.streamHandlers.get(sw.seq)
        if (
          handler &&
          (sw.commandOrdinal === this.mustOrdinal("RPC_STREAM") || sw.commandOrdinal === this.mustOrdinal("RPC_EVENT"))
        ) {
          handler(sw)
          continue
        }

        if (sw.commandOrdinal === this.mustOrdinal("RPC_STREAM") || sw.commandOrdinal === this.mustOrdinal("RPC_EVENT")) {
          continue
        }

        const p = this.pending.get(sw.seq)
        if (!p) continue
        this.pending.delete(sw.seq)
        clearTimeout(p.t)
        p.resolve(sw)
      }
    } catch {
      return
    }
  }

  private cleanup(error: Error): void {
    for (const [seq, p] of this.pending.entries()) {
      clearTimeout(p.t)
      p.reject(error)
      this.pending.delete(seq)
    }
    this.streamHandlers.clear()
    this.xorKey = undefined
  }

  private clearTimers(): void {
    if (this.heartbeatTimer) clearInterval(this.heartbeatTimer)
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer)
    this.heartbeatTimer = undefined
    this.reconnectTimer = undefined
  }

  private startHeartbeat(): void {
    const intervalMs = Number(this.cfg.heartbeat?.intervalMs ?? 0) | 0
    if (intervalMs <= 0) return
    const timeoutMs = Math.max(intervalMs * 2, Number(this.cfg.heartbeat?.timeoutMs ?? 0) | 0)
    this.heartbeatTimer = setInterval(() => {
      const ws = this.ws
      if (!ws) return
      const now = Date.now()
      if (timeoutMs > 0 && now - this.lastPongAtMs > timeoutMs) {
        try {
          ws.terminate()
        } catch {}
        return
      }
      try {
        ws.ping()
      } catch {}
    }, intervalMs)
  }

  private handleDisconnect(): void {
    if (!this.ws) return
    this.clearTimers()
    this.ws = undefined
    this.cleanup(new Error("closed"))
    this.cfg.telemetry?.onDisconnect?.()
    if (this.suppressReconnect) return
    if (!this.cfg.reconnect?.enabled) return
    if (this.reconnecting) return
    this.reconnecting = true
    this.scheduleReconnect()
  }

  private scheduleReconnect(): void {
    const cfg = this.cfg.reconnect
    if (!cfg?.enabled) return
    const initialMs = Math.max(50, Number(cfg.initialMs ?? 250) | 0)
    const maxMs = Math.max(initialMs, Number(cfg.maxMs ?? 10_000) | 0)
    const jitterRatio = Math.max(0, Math.min(1, Number(cfg.jitterRatio ?? 0.2)))
    const base = this.reconnectDelayMs > 0 ? Math.min(maxMs, this.reconnectDelayMs * 2) : initialMs
    const jitter = Math.floor(base * jitterRatio * Math.random())
    const delay = base + jitter
    this.reconnectDelayMs = base
    this.cfg.telemetry?.onReconnectAttempt?.(delay)
    this.reconnectTimer = setTimeout(() => {
      void this.reconnectNow()
    }, delay)
  }

  private async reconnectNow(): Promise<void> {
    if (this.suppressReconnect) {
      this.reconnecting = false
      return
    }
    try {
      await this.connect()
      this.reconnectDelayMs = 0
      if (this.cfg.reconnect?.onReconnect) await this.cfg.reconnect.onReconnect(this)
      this.reconnecting = false
      this.cfg.telemetry?.onReconnectSuccess?.()
    } catch {
      this.clearTimers()
      this.ws = undefined
      this.cleanup(new Error("closed"))
      this.scheduleReconnect()
    }
  }
}

function isStreamWrapper(w: P2PWrapper | StreamP2PWrapper): w is StreamP2PWrapper {
  return (w as StreamP2PWrapper).index != null
}

function xorRepeatInPlace(out: Uint8Array, key: Uint8Array): void {
  if (key.byteLength === 0) return
  for (let i = 0; i < out.length; i++) out[i] ^= key[i % key.byteLength]!
}

function beI64(v: bigint): Buffer {
  const b = Buffer.allocUnsafe(8)
  b.writeBigInt64BE(BigInt.asIntN(64, v), 0)
  return b
}

function beI32(v: number): Buffer {
  const b = Buffer.allocUnsafe(4)
  b.writeInt32BE(v | 0, 0)
  return b
}

function handSigPayload(req: HandshakeRequest): Buffer {
  const user = Buffer.from(req.userId ?? "", "utf-8")
  const nonce = Buffer.from(req.nonce)
  const keyHash = crypto.createHash("sha256").update(Buffer.from(req.encryptedXorKey)).digest()
  return Buffer.concat([beI64(req.timestamp), beI32(req.xorKeyLength), user, nonce, keyHash])
}

function loginSigPayload(req: LoginRequest): Buffer {
  const user = Buffer.from(req.userId ?? "", "utf-8")
  return Buffer.concat([beI64(req.timestamp), user])
}

function rsaPrivateEncryptPkcs1v15Large(privateKeyPem: string, data: Buffer): Buffer {
  const keyObj = crypto.createPrivateKey(privateKeyPem)
  const details = (keyObj as any).asymmetricKeyDetails as { modulusLength?: number } | undefined
  const keyBytes = Math.max(1, Math.ceil(Number(details?.modulusLength ?? 2048) / 8))
  const maxPlain = Math.max(1, keyBytes - 11)
  const chunks: Buffer[] = []
  for (let i = 0; i < data.length; i += maxPlain) {
    const block = data.subarray(i, i + maxPlain)
    const enc = crypto.privateEncrypt({ key: keyObj, padding: crypto.constants.RSA_PKCS1_PADDING }, block)
    chunks.push(enc)
  }
  return Buffer.concat(chunks)
}
