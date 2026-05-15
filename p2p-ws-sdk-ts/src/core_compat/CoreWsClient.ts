import crypto from "node:crypto"
import fs from "node:fs"
import { WebSocket } from "ws"
import protobuf from "protobufjs"
import path from "node:path"

import { decodeCoreFrame, encodeCoreFrame } from "./core_frame.js"
import { loadCommandOrdinals } from "./ordinals.js"
import {
  decodeHandshakeResponse,
  decodeLoginResponse,
  decodeP2PWrapper,
  encodeHandshakeRequest,
  encodeLoginRequest,
  encodeP2PWrapper,
  type HandshakeRequest,
  type LoginRequest,
  type P2PWrapper,
} from "./protostuff.js"

export type CoreWsClientConfig = { wsUrl: string; magic: number; xorKeyLength?: number }

type Pending = { resolve: (w: P2PWrapper) => void; reject: (e: unknown) => void; t: any }

export class CoreWsClient {
  private cfg: CoreWsClientConfig
  private ws?: WebSocket
  private seq: number = 1
  private pending = new Map<number, Pending>()
  private xorKey?: Buffer
  private ordinals: Map<string, number>
  private rpcRoot?: protobuf.Root

  constructor(cfg: CoreWsClientConfig) {
    this.cfg = cfg
    this.ordinals = loadCommandOrdinals()
  }

  public async connect(timeoutMs: number = 6_000): Promise<void> {
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
    ws.on("close", () => this.close())
    ws.on("error", () => this.close())
  }

  public close(): void {
    for (const [seq, p] of this.pending.entries()) {
      clearTimeout(p.t)
      p.reject(new Error("closed"))
      this.pending.delete(seq)
    }
    try {
      this.ws?.close()
    } catch {}
    this.ws = undefined
    this.xorKey = undefined
  }

  public async request(commandName: string, data: Uint8Array, timeoutMs: number = 10_000): Promise<P2PWrapper> {
    const ws = this.ws
    if (!ws) throw new Error("not connected")
    const cmd = this.ordinals.get(commandName)
    if (cmd == null) throw new Error(`unknown command: ${commandName}`)
    const seq = ++this.seq
    const w: P2PWrapper = { seq, commandOrdinal: cmd, data }
    await this.sendWrapper(w, commandName !== "HAND")
    return await new Promise<P2PWrapper>((resolve, reject) => {
      const t = setTimeout(() => {
        this.pending.delete(seq)
        reject(new Error("request timeout"))
      }, timeoutMs)
      this.pending.set(seq, { resolve, reject, t })
    })
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
    const meta = RpcMeta.create({
      request_id: process.hrtime.bigint().toString(),
      service,
      method,
      service_version: "",
      call_type: 1,
      deadline_epoch_ms: "0",
      codec: "protobuf",
      idempotent: false,
      method_hash: 0,
      headers: {},
    })
    const open = RpcFrame.encode({ meta, frame_type: 1, payload: requestPayload, end_of_stream: true }).finish()
    const resp = await this.request(commandName, open, timeoutMs)
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

  private mustOrdinal(name: string): number {
    const v = this.ordinals.get(name)
    if (v == null) throw new Error(`missing ordinal: ${name}`)
    return v
  }

  private async sendWrapper(w: P2PWrapper, encrypt: boolean): Promise<void> {
    const ws = this.ws
    if (!ws) throw new Error("not connected")
    let payload = Buffer.from(encodeP2PWrapper(w))
    if (encrypt && this.xorKey && payload.length > 0) xorRepeatInPlace(payload, this.xorKey)
    ws.send(encodeCoreFrame(this.cfg.magic, payload))
  }

  private onMessage(ev: Buffer): void {
    let w: P2PWrapper
    try {
      const f = decodeCoreFrame(new Uint8Array(ev))
      if ((f.magic | 0) !== (this.cfg.magic | 0)) return
      let payload = Buffer.from(f.payload)
      if (this.xorKey && payload.length > 0) xorRepeatInPlace(payload, this.xorKey)
      w = decodeP2PWrapper(payload)
    } catch {
      return
    }
    const p = this.pending.get(w.seq)
    if (!p) return
    this.pending.delete(w.seq)
    clearTimeout(p.t)
    p.resolve(w)
  }
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
