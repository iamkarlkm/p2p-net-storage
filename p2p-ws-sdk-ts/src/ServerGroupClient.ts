import fs from "node:fs"
import path from "node:path"
import crypto from "node:crypto"
import { WebSocket } from "ws"

import { decodeFrame, encodeFrame } from "./frame.js"
import { loadProtoRoot } from "./proto.js"
import { decodeWrapper, encodeWrapper } from "./wrapper.js"
import { ClientConfig, parseIntMaybeHex } from "./config.js"
import { rsaOaepSha256Decrypt } from "./handshake.js"
import { xorRepeat } from "./xor.js"

type Cipher =
  | { kind: "plain" }
  | { kind: "keyfile"; offset: number }
  | { kind: "xor"; key: Uint8Array }

export class ServerGroupClient {
  private static readonly CRYPTO_KEYFILE = "KEYFILE_XOR_RSA_OAEP"
  private static readonly CRYPTO_CLIENT_RANDOM = "CLIENT_RANDOM_XOR_RSA_OAEP"
  private static readonly CRYPTO_SERVER_RANDOM = "SERVER_RANDOM_XOR_RSA_OAEP"
  private static readonly CRYPTO_PLAIN = "PLAIN"

  private cfg: ClientConfig
  private cfgDir: string
  private root: any
  private ws?: WebSocket
  private cipher: Cipher = { kind: "plain" }

  private magic: number
  private version: number
  private flagsPlain: number
  private flagsEncrypted: number
  private maxFramePayload: number

  private cryptoMode: string
  private clientRandomKey?: Buffer
  private keyId?: Buffer
  private keyLen: number = 0
  private fd?: number

  private privateKeyPem: string
  private clientPubDer: Buffer
  private seq: number = 1
  private pending = new Map<number, { resolve: (w: any) => void; reject: (e: any) => void; t: any }>()

  constructor(cfg: ClientConfig, cfgDir: string) {
    this.cfg = cfg
    this.cfgDir = cfgDir

    this.magic = parseIntMaybeHex(cfg.magic, 0x1234)
    this.version = cfg.version ?? 1
    this.flagsPlain = cfg.flags_plain ?? 4
    this.flagsEncrypted = cfg.flags_encrypted ?? 5
    this.maxFramePayload = cfg.max_frame_payload ?? 4 * 1024 * 1024

    this.cryptoMode = this.resolveCryptoMode(cfg)
    const rk = Math.max(1, Math.min(64, Number(cfg.random_key_bytes ?? 32) | 0))
    if (this.cryptoMode === ServerGroupClient.CRYPTO_CLIENT_RANDOM) {
      this.clientRandomKey = crypto.randomBytes(rk)
    }
    if (this.cryptoMode === ServerGroupClient.CRYPTO_KEYFILE) {
      if (!cfg.keyfile_path) throw new Error("keyfile_path required for keyfile mode")
      const keyfileAbs = path.resolve(this.cfgDir, cfg.keyfile_path)
      this.keyLen = fs.statSync(keyfileAbs).size
      this.fd = fs.openSync(keyfileAbs, "r")
      const keyHex = crypto.createHash("sha256").update(fs.readFileSync(keyfileAbs)).digest("hex")
      this.keyId = Buffer.from(keyHex, "hex")
    }

    if (!cfg.rsa_private_key_pem_path) throw new Error("rsa_private_key_pem_path required")
    const privAbs = path.resolve(this.cfgDir, cfg.rsa_private_key_pem_path)
    this.privateKeyPem = fs.readFileSync(privAbs, "utf-8")
    const privObj = crypto.createPrivateKey(this.privateKeyPem)
    const pubObj = crypto.createPublicKey(privObj)
    this.clientPubDer = pubObj.export({ type: "spki", format: "der" }) as Buffer
  }

  public async connect(timeoutMs: number = 6_000): Promise<void> {
    this.root = await loadProtoRoot()
    const urls = this.resolveUrls()
    let lastErr: any = null
    for (const url of urls) {
      try {
        await this.connectOne(url, timeoutMs)
        return
      } catch (e) {
        lastErr = e
        this.disconnect()
      }
    }
    throw lastErr ?? new Error("connect failed")
  }

  public async request(command: number, data?: Uint8Array, timeoutMs: number = 10_000): Promise<any> {
    if (!this.ws) throw new Error("not connected")
    const seq = this.seq++
    const wrap = encodeWrapper(this.root, { seq, command, data })
    const cipher = this.applyCipher(wrap)
    this.ws.send(encodeFrame({ length: cipher.length, magic: this.magic, version: this.version, flags: this.cipherFlags() }, cipher))

    return await new Promise<any>((resolve, reject) => {
      const t = setTimeout(() => {
        this.pending.delete(seq)
        reject(new Error("request timeout"))
      }, timeoutMs)
      this.pending.set(seq, { resolve, reject, t })
    })
  }

  public close() {
    this.disconnect()
    if (this.fd != null) {
      try {
        fs.closeSync(this.fd)
      } catch {}
      this.fd = undefined
    }
  }

  private disconnect() {
    for (const [seq, p] of this.pending.entries()) {
      clearTimeout(p.t)
      p.reject(new Error("closed"))
      this.pending.delete(seq)
    }
    try {
      this.ws?.close()
    } catch {}
    this.ws = undefined
    this.cipher = { kind: "plain" }
  }

  private async connectOne(wsUrl: string, timeoutMs: number): Promise<void> {
    const ws = new WebSocket(wsUrl)
    this.ws = ws
    ws.binaryType = "arraybuffer"
    this.cipher = { kind: "plain" }

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

    const handshakeDone = this.waitHandshake(timeoutMs)
    this.sendHand()
    await handshakeDone

    ws.on("message", (ev) => this.onMessage(ev as Buffer))
    ws.on("close", () => this.disconnect())
    ws.on("error", () => this.disconnect())
  }

  private waitHandshake(timeoutMs: number): Promise<void> {
    const ws = this.ws
    if (!ws) throw new Error("ws missing")
    return new Promise<void>((resolve, reject) => {
      const t = setTimeout(() => reject(new Error("handshake timeout")), timeoutMs)
      const onMsg = (ev: Buffer) => {
        try {
          const f = decodeFrame(new Uint8Array(ev))
          const w = decodeWrapper(this.root, f.cipherPayload)
          if (w.command !== -10002) return
          ws.off("message", onMsg)
          clearTimeout(t)
          this.applyHandAck(w.data ?? new Uint8Array())
          resolve()
        } catch (e) {
          ws.off("message", onMsg)
          clearTimeout(t)
          reject(e)
        }
      }
      ws.on("message", onMsg)
    })
  }

  private onMessage(ev: Buffer) {
    let w: any
    try {
      const f = decodeFrame(new Uint8Array(ev))
      const plain = this.applyCipher(f.cipherPayload)
      w = decodeWrapper(this.root, plain)
    } catch {
      return
    }
    const p = this.pending.get(w.seq)
    if (!p) return
    this.pending.delete(w.seq)
    clearTimeout(p.t)
    p.resolve(w)
  }

  private sendHand() {
    const ws = this.ws
    if (!ws) throw new Error("ws missing")
    const handData = this.root.lookupType("p2pws.Hand").encode({
      client_pubkey: this.clientPubDer,
      key_ids: this.keyId ? [this.keyId] : [],
      max_frame_payload: this.maxFramePayload,
      client_id: this.cfg.user_id,
      crypto_mode: this.cryptoMode,
      client_random_key: this.clientRandomKey ?? new Uint8Array(),
    }).finish()
    const wrap = encodeWrapper(this.root, { seq: 1, command: -10001, data: handData })
    ws.send(encodeFrame({ length: wrap.length, magic: this.magic, version: this.version, flags: this.flagsPlain }, wrap))
  }

  private applyHandAck(enc: Uint8Array) {
    const ack = this.root.lookupType("p2pws.HandAckPlain").decode(rsaOaepSha256Decrypt(this.privateKeyPem, enc)) as any
    const mode = String(ack.crypto_mode ?? this.cryptoMode)
    if (mode === ServerGroupClient.CRYPTO_KEYFILE) {
      this.cipher = { kind: "keyfile", offset: Number(ack.offset) | 0 }
      return
    }
    if (mode === ServerGroupClient.CRYPTO_SERVER_RANDOM) {
      this.cipher = { kind: "xor", key: new Uint8Array(ack.server_random_key ?? new Uint8Array()) }
      return
    }
    if (mode === ServerGroupClient.CRYPTO_CLIENT_RANDOM) {
      this.cipher = { kind: "xor", key: new Uint8Array(this.clientRandomKey ?? new Uint8Array()) }
      return
    }
    this.cipher = { kind: "plain" }
  }

  private applyCipher(data: Uint8Array): Uint8Array {
    if (this.cipher.kind === "plain") return data
    if (this.cipher.kind === "xor") return xorRepeat(data, this.cipher.key)
    return this.xorWithFile(data, this.cipher.offset)
  }

  private cipherFlags(): number {
    return this.cipher.kind === "plain" ? this.flagsPlain : this.flagsEncrypted
  }

  private xorWithFile(data: Uint8Array, off: number): Uint8Array {
    if (this.fd == null) throw new Error("keyfile not opened")
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

  private resolveCryptoMode(cfg: ClientConfig): string {
    if (cfg.crypto_mode) return String(cfg.crypto_mode)
    const enabled = cfg.encryption_enabled !== false
    if (!enabled) return ServerGroupClient.CRYPTO_PLAIN
    const m = String(cfg.encryption_mode ?? "keyfile").toLowerCase()
    if (m === "server_random") return ServerGroupClient.CRYPTO_SERVER_RANDOM
    if (m === "client_random") return ServerGroupClient.CRYPTO_CLIENT_RANDOM
    return ServerGroupClient.CRYPTO_KEYFILE
  }

  private resolveUrls(): string[] {
    const urls = (this.cfg.ws_urls && this.cfg.ws_urls.length > 0 ? this.cfg.ws_urls : [this.cfg.ws_url]).filter(Boolean)
    if (urls.length === 0) throw new Error("ws_url/ws_urls required")
    return urls.map((u) => String(u))
  }
}
