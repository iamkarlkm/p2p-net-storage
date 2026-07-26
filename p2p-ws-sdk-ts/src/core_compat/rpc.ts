import protobuf from "protobufjs"
import path from "node:path"

import { CoreWsClient } from "./CoreWsClient.js"

export type RpcFlowControlOptions = {
  permits?: number
  windowUpdateBatch?: number
  maxInflightFrames?: number
  maxFrameBytes?: number
}

export type RpcCallOptions = {
  timeoutMs?: number
  deadlineEpochMs?: bigint
  serviceVersion?: string
  codec?: string
  traceId?: string
  spanId?: string
  parentSpanId?: string
  callerNodeId?: string
  callerUserId?: string
  headers?: Record<string, string>
  idempotent?: boolean
  methodHash?: number
}

export type RpcUnaryResult = { frame: any }

export class CoreRpcClient {
  private ws: CoreWsClient

  constructor(ws: CoreWsClient) {
    this.ws = ws
  }

  public async unary(commandName: "RPC_UNARY" | "RPC_DISCOVER" | "RPC_HEALTH", service: string, method: string, payload: Uint8Array, opts?: RpcCallOptions): Promise<RpcUnaryResult> {
    const root = await loadRpcRoot()
    const RpcFrame = root.lookupType("p2p.rpc.v1.RpcFrame")
    const { requestId, meta } = buildMeta(this.ws, root, service, method, 1, opts)
    const open = RpcFrame.encode({ meta, frame_type: 1, payload: payload ?? new Uint8Array(), end_of_stream: true }).finish()

    const cmd = this.ws.mustOrdinal(commandName)
    const resp = await this.ws.sendAndAwait({ seq: requestId, commandOrdinal: cmd, data: open }, true, Number(opts?.timeoutMs ?? 10_000))
    const frame = RpcFrame.decode(resp.data) as any
    ensureOk(frame)
    return { frame }
  }

  public async discover(service: string = "", includeMethods: boolean = false, opts?: RpcCallOptions): Promise<any> {
    const root = await loadRpcRoot()
    const DiscoverRequest = root.lookupType("p2p.rpc.v1.DiscoverRequest")
    const DiscoverResponse = root.lookupType("p2p.rpc.v1.DiscoverResponse")
    const req = DiscoverRequest.encode({ service, include_methods: includeMethods }).finish()
    const { frame } = await this.unary("RPC_DISCOVER", service, "Discover", req, opts)
    return DiscoverResponse.decode(frame.payload) as any
  }

  public async health(service: string, opts?: RpcCallOptions): Promise<any> {
    const root = await loadRpcRoot()
    const HealthCheckRequest = root.lookupType("p2p.rpc.v1.HealthCheckRequest")
    const HealthCheckResponse = root.lookupType("p2p.rpc.v1.HealthCheckResponse")
    const req = HealthCheckRequest.encode({ service }).finish()
    const { frame } = await this.unary("RPC_HEALTH", service, "Check", req, opts)
    return HealthCheckResponse.decode(frame.payload) as any
  }

  public async echo(message: string, opts?: RpcCallOptions): Promise<any> {
    const root = await loadRpcRoot()
    const EchoRequest = root.lookupType("p2p.rpc.echo.v1.EchoRequest")
    const EchoResponse = root.lookupType("p2p.rpc.echo.v1.EchoResponse")
    const req = EchoRequest.encode({ message }).finish()
    const { frame } = await this.unary("RPC_UNARY", "p2p.rpc.echo.v1.EchoService", "Echo", req, opts)
    return EchoResponse.decode(frame.payload) as any
  }
}

export type RpcStreamOptions = RpcCallOptions & { flowControl?: RpcFlowControlOptions }

export class CoreRpcServerStream implements AsyncIterable<Uint8Array> {
  private ws: CoreWsClient
  private root: protobuf.Root
  private requestId: number
  private session: InboundStreamSession
  private closed = false

  constructor(ws: CoreWsClient, root: protobuf.Root, requestId: number, session: InboundStreamSession) {
    this.ws = ws
    this.root = root
    this.requestId = requestId
    this.session = session
  }

  public [Symbol.asyncIterator](): AsyncIterator<Uint8Array> {
    return this.session.iterMessages()
  }

  public cancel(): void {
    if (this.closed) return
    this.closed = true
    this.ws.registerStreamHandler(this.requestId, undefined)
    const RpcFrame = this.root.lookupType("p2p.rpc.v1.RpcFrame")
    const cancel = RpcFrame.encode({ frame_type: 4, meta: { request_id: String(this.requestId) }, end_of_stream: true }).finish()
    void this.ws.send({ seq: this.requestId, commandOrdinal: this.ws.mustOrdinal("RPC_CONTROL"), data: cancel }, true).catch(() => {})
    this.session.close(new Error("canceled"))
  }

  public close(): void {
    if (this.closed) return
    this.closed = true
    this.ws.registerStreamHandler(this.requestId, undefined)
    this.session.close()
  }
}

export class CoreRpcStreamClient {
  private ws: CoreWsClient

  constructor(ws: CoreWsClient) {
    this.ws = ws
  }

  public async openServerStream(service: string, method: string, requestPayload: Uint8Array, opts?: RpcStreamOptions): Promise<CoreRpcServerStream> {
    return await this.openInboundStream("RPC_STREAM", 2, service, method, requestPayload, opts)
  }

  public async openEventStream(service: string, method: string, requestPayload: Uint8Array, opts?: RpcStreamOptions): Promise<CoreRpcServerStream> {
    return await this.openInboundStream("RPC_EVENT", 2, service, method, requestPayload, opts)
  }

  public async openClientStream(service: string, method: string, opts?: RpcStreamOptions): Promise<CoreRpcClientStream> {
    return await this.openDuplexStream(3, service, method, opts)
  }

  public async openBidiStream(service: string, method: string, opts?: RpcStreamOptions): Promise<CoreRpcBidiStream> {
    return await this.openDuplexStream(4, service, method, opts)
  }

  private async openInboundStream(commandName: "RPC_STREAM" | "RPC_EVENT", callType: number, service: string, method: string, requestPayload: Uint8Array, opts?: RpcStreamOptions): Promise<CoreRpcServerStream> {
    const root = await loadRpcRoot()
    const RpcFrame = root.lookupType("p2p.rpc.v1.RpcFrame")
    const RpcFlowControl = root.lookupType("p2p.rpc.v1.RpcFlowControl")
    const { requestId, meta } = buildMeta(this.ws, root, service, method, callType, opts)

    const permits = Math.max(1, Number(opts?.flowControl?.permits ?? 2) | 0)
    const maxInflightFrames = Math.max(0, Number(opts?.flowControl?.maxInflightFrames ?? 0) | 0)
    const maxFrameBytes = Math.max(0, Number(opts?.flowControl?.maxFrameBytes ?? 0) | 0)
    const flow = RpcFlowControl.create({ permits, max_inflight_frames: maxInflightFrames, max_frame_bytes: maxFrameBytes })

    const open = RpcFrame.encode({
      meta,
      frame_type: 1,
      payload: requestPayload ?? new Uint8Array(),
      flow_control: flow,
      end_of_stream: true,
    }).finish()

    const windowUpdateBatch = Math.max(1, Number(opts?.flowControl?.windowUpdateBatch ?? permits) | 0)
    const session = new InboundStreamSession(root, requestId, windowUpdateBatch, (p) => this.sendWindowUpdate(root, requestId, p))
    this.ws.registerStreamHandler(requestId, (w) => session.onWrapper(w))

    const cmd = this.ws.mustOrdinal(commandName)
    const openWrapper = { seq: requestId, commandOrdinal: cmd, data: open, index: 0, completed: false, canceled: false }
    const ack = await this.ws.sendStreamOpen(openWrapper, Number(opts?.timeoutMs ?? 10_000))
    if (ack.commandOrdinal !== this.ws.mustOrdinal("STREAM_ACK")) {
      this.ws.registerStreamHandler(requestId, undefined)
      throw new Error(`open stream failed: ${ack.commandOrdinal}`)
    }
    return new CoreRpcServerStream(this.ws, root, requestId, session)
  }

  private async openDuplexStream(callType: number, service: string, method: string, opts?: RpcStreamOptions): Promise<CoreRpcClientStream | CoreRpcBidiStream> {
    const root = await loadRpcRoot()
    const RpcFrame = root.lookupType("p2p.rpc.v1.RpcFrame")
    const RpcFlowControl = root.lookupType("p2p.rpc.v1.RpcFlowControl")
    const { requestId, meta } = buildMeta(this.ws, root, service, method, callType, opts)

    const permits = Math.max(1, Number(opts?.flowControl?.permits ?? 2) | 0)
    const maxInflightFrames = Math.max(0, Number(opts?.flowControl?.maxInflightFrames ?? 0) | 0)
    const maxFrameBytes = Math.max(0, Number(opts?.flowControl?.maxFrameBytes ?? 0) | 0)
    const flow = RpcFlowControl.create({ permits, max_inflight_frames: maxInflightFrames, max_frame_bytes: maxFrameBytes })

    const open = RpcFrame.encode({
      meta,
      frame_type: 1,
      payload: new Uint8Array(),
      flow_control: flow,
      end_of_stream: false,
    }).finish()

    const outboundWindow = new OutboundWindow(permits)
    const windowUpdateBatch = Math.max(1, Number(opts?.flowControl?.windowUpdateBatch ?? permits) | 0)
    const inbound = new InboundStreamSession(root, requestId, windowUpdateBatch, (p) => this.sendWindowUpdate(root, requestId, p), (p) =>
      outboundWindow.addPermits(p),
    )
    this.ws.registerStreamHandler(requestId, (w) => inbound.onWrapper(w))

    const openWrapper = { seq: requestId, commandOrdinal: this.ws.mustOrdinal("RPC_STREAM"), data: open, index: 0, completed: false, canceled: false }
    const ack = await this.ws.sendStreamOpen(openWrapper, Number(opts?.timeoutMs ?? 10_000))
    if (ack.commandOrdinal !== this.ws.mustOrdinal("STREAM_ACK")) {
      this.ws.registerStreamHandler(requestId, undefined)
      throw new Error(`open stream failed: ${ack.commandOrdinal}`)
    }

    const sender = new OutboundStreamSender(this.ws, root, requestId, outboundWindow, maxFrameBytes)
    if (callType === 3) return new CoreRpcClientStream(this.ws, root, requestId, sender, inbound)
    return new CoreRpcBidiStream(this.ws, root, requestId, sender, inbound)
  }

  private async sendWindowUpdate(root: protobuf.Root, requestId: number, permits: number): Promise<void> {
    const RpcFrame = root.lookupType("p2p.rpc.v1.RpcFrame")
    const RpcFlowControl = root.lookupType("p2p.rpc.v1.RpcFlowControl")
    const frame = RpcFrame.encode({
      frame_type: 6,
      meta: { request_id: String(requestId) },
      flow_control: RpcFlowControl.create({ permits: Math.max(0, permits | 0) }),
      end_of_stream: true,
    }).finish()
    await this.ws.send({ seq: requestId, commandOrdinal: this.ws.mustOrdinal("RPC_CONTROL"), data: frame }, true)
  }
}

export class CoreRpcClientStream {
  private ws: CoreWsClient
  private root: protobuf.Root
  private requestId: number
  private sender: OutboundStreamSender
  private inbound: InboundStreamSession
  private done = false

  constructor(ws: CoreWsClient, root: protobuf.Root, requestId: number, sender: OutboundStreamSender, inbound: InboundStreamSession) {
    this.ws = ws
    this.root = root
    this.requestId = requestId
    this.sender = sender
    this.inbound = inbound
  }

  public async sendMessage(payload: Uint8Array): Promise<void> {
    if (this.done) throw new Error("stream closed")
    await this.sender.sendData(payload)
  }

  public async complete(): Promise<Uint8Array> {
    if (this.done) throw new Error("stream closed")
    this.done = true
    await this.sender.sendClose()

    for await (const msg of this.inbound.iterMessages()) {
      this.close()
      return msg
    }
    this.close()
    return new Uint8Array()
  }

  public cancel(): void {
    if (this.done) return
    this.done = true
    const RpcFrame = this.root.lookupType("p2p.rpc.v1.RpcFrame")
    const cancel = RpcFrame.encode({ frame_type: 4, meta: { request_id: String(this.requestId) }, end_of_stream: true }).finish()
    void this.ws.send({ seq: this.requestId, commandOrdinal: this.ws.mustOrdinal("RPC_CONTROL"), data: cancel }, true).catch(() => {})
    this.close()
  }

  public close(): void {
    this.ws.registerStreamHandler(this.requestId, undefined)
    this.inbound.close()
  }
}

export class CoreRpcBidiStream implements AsyncIterable<Uint8Array> {
  private ws: CoreWsClient
  private root: protobuf.Root
  private requestId: number
  private sender: OutboundStreamSender
  private inbound: InboundStreamSession
  private done = false

  constructor(ws: CoreWsClient, root: protobuf.Root, requestId: number, sender: OutboundStreamSender, inbound: InboundStreamSession) {
    this.ws = ws
    this.root = root
    this.requestId = requestId
    this.sender = sender
    this.inbound = inbound
  }

  public async sendMessage(payload: Uint8Array): Promise<void> {
    if (this.done) throw new Error("stream closed")
    await this.sender.sendData(payload)
  }

  public async complete(): Promise<void> {
    if (this.done) throw new Error("stream closed")
    this.done = true
    await this.sender.sendClose()
  }

  public cancel(): void {
    if (this.done) return
    this.done = true
    const RpcFrame = this.root.lookupType("p2p.rpc.v1.RpcFrame")
    const cancel = RpcFrame.encode({ frame_type: 4, meta: { request_id: String(this.requestId) }, end_of_stream: true }).finish()
    void this.ws.send({ seq: this.requestId, commandOrdinal: this.ws.mustOrdinal("RPC_CONTROL"), data: cancel }, true).catch(() => {})
    this.close()
  }

  public close(): void {
    this.ws.registerStreamHandler(this.requestId, undefined)
    this.inbound.close()
  }

  public [Symbol.asyncIterator](): AsyncIterator<Uint8Array> {
    return this.inbound.iterMessages()
  }
}

let cachedRpcRoot: protobuf.Root | undefined
async function loadRpcRoot(): Promise<protobuf.Root> {
  if (cachedRpcRoot) return cachedRpcRoot
  const root = new protobuf.Root()
  const repoRoot = path.resolve(import.meta.dirname, "..", "..", "..", "..")
  const protoDir = path.resolve(repoRoot, "p2p-core", "src", "main", "proto")
  await root.load([path.join(protoDir, "p2p_rpc.proto"), path.join(protoDir, "p2p_rpc_echo.proto")], { keepCase: true })
  root.resolveAll()
  cachedRpcRoot = root
  return root
}

function buildMeta(ws: CoreWsClient, root: protobuf.Root, service: string, method: string, callType: number, opts?: RpcCallOptions): { requestId: number; meta: any } {
  const RpcMeta = root.lookupType("p2p.rpc.v1.RpcMeta")
  const requestId = ws.allocateSeq()
  const timeoutMs = Math.max(1, Number(opts?.timeoutMs ?? 10_000) | 0)
  const deadlineEpochMs = opts?.deadlineEpochMs ?? BigInt(Date.now() + timeoutMs)
  const meta = RpcMeta.create({
    request_id: String(requestId),
    service: service ?? "",
    method: method ?? "",
    service_version: opts?.serviceVersion ?? "",
    call_type: callType,
    deadline_epoch_ms: String(deadlineEpochMs),
    codec: opts?.codec ?? "protobuf",
    trace_id: opts?.traceId ?? "",
    span_id: opts?.spanId ?? "",
    parent_span_id: opts?.parentSpanId ?? "",
    caller_node_id: opts?.callerNodeId ?? "",
    caller_user_id: opts?.callerUserId ?? "",
    headers: opts?.headers ?? {},
    idempotent: Boolean(opts?.idempotent ?? false),
    method_hash: Math.max(0, Number(opts?.methodHash ?? 0) | 0),
  })
  return { requestId, meta }
}

function ensureOk(frame: any): void {
  if (!frame.status) throw new Error("RPC missing status")
  if (frame.frame_type === 5) throw new Error(String(frame.status.message ?? "RPC ERROR"))
  if (frame.status.code !== 0 && (!frame.payload || frame.payload.length === 0)) throw new Error(String(frame.status.message ?? "RPC failed"))
}

class InboundStreamSession {
  private root: protobuf.Root
  private requestId: number
  private windowUpdateBatch: number
  private sendWindowUpdate: (permits: number) => Promise<void>
  private onRequestWindowUpdate?: (permits: number) => void
  private closedError: Error | undefined
  private done = false

  private waiting: Array<(v: any) => void> = []
  private queue: any[] = []

  private bufferedChunks: Uint8Array[] = []
  private pendingCredits = 0

  constructor(
    root: protobuf.Root,
    requestId: number,
    windowUpdateBatch: number,
    sendWindowUpdate: (permits: number) => Promise<void>,
    onRequestWindowUpdate?: (permits: number) => void,
  ) {
    this.root = root
    this.requestId = requestId
    this.windowUpdateBatch = windowUpdateBatch
    this.sendWindowUpdate = sendWindowUpdate
    this.onRequestWindowUpdate = onRequestWindowUpdate
  }

  public onWrapper(w: { data: Uint8Array }): void {
    if (this.done) return
    const RpcFrame = this.root.lookupType("p2p.rpc.v1.RpcFrame")
    let frame: any
    try {
      frame = RpcFrame.decode(w.data) as any
    } catch (e) {
      this.close(e instanceof Error ? e : new Error(String(e)))
      return
    }
    this.enqueue(frame)
  }

  private enqueue(frame: any): void {
    if (this.waiting.length > 0) {
      const r = this.waiting.shift()!
      r(frame)
      return
    }
    this.queue.push(frame)
  }

  private async nextFrame(): Promise<any | undefined> {
    if (this.queue.length > 0) return this.queue.shift()
    if (this.done) return undefined
    return await new Promise<any | undefined>((resolve) => this.waiting.push(resolve))
  }

  public async *iterMessages(): AsyncGenerator<Uint8Array> {
    for (;;) {
      const frame = await this.nextFrame()
      if (!frame) break

      if (frame.frame_type === 6) {
        const p = Number(frame.flow_control?.permits ?? 0) | 0
        if (p > 0) this.onRequestWindowUpdate?.(p)
        continue
      }
      if (frame.frame_type === 7) continue

      if (frame.frame_type === 5) {
        this.close(new Error(String(frame.status?.message ?? "RPC ERROR")))
        throw this.closedError
      }

      const payload = (frame.payload as Uint8Array | undefined) ?? new Uint8Array()
      if (payload.length > 0) this.bufferedChunks.push(payload)

      if (frame.end_of_message) {
        const merged = concatChunks(this.bufferedChunks)
        this.bufferedChunks = []
        this.pendingCredits += 1
        if (this.pendingCredits >= this.windowUpdateBatch) {
          const p = this.pendingCredits
          this.pendingCredits = 0
          void this.sendWindowUpdate(p).catch(() => {})
        }
        yield merged
      }

      if (frame.frame_type === 3 || frame.end_of_stream) {
        ensureOk(frame)
        this.close()
        break
      }
    }
  }

  public close(err?: Error): void {
    if (this.done) return
    this.done = true
    this.closedError = err
    const waiters = this.waiting
    this.waiting = []
    for (const r of waiters) r(undefined)
  }
}

function concatChunks(chunks: Uint8Array[]): Uint8Array {
  if (chunks.length === 0) return new Uint8Array()
  if (chunks.length === 1) return chunks[0]!
  let total = 0
  for (const c of chunks) total += c.length
  const out = new Uint8Array(total)
  let pos = 0
  for (const c of chunks) {
    out.set(c, pos)
    pos += c.length
  }
  return out
}

class OutboundWindow {
  private permits: number
  private waiters: Array<() => void> = []

  constructor(initialPermits: number) {
    this.permits = Math.max(0, initialPermits | 0)
  }

  public addPermits(n: number): void {
    const v = Math.max(0, n | 0)
    if (v === 0) return
    this.permits += v
    while (this.permits > 0 && this.waiters.length > 0) {
      const r = this.waiters.shift()!
      this.permits -= 1
      r()
    }
  }

  public async acquire(): Promise<void> {
    if (this.permits > 0) {
      this.permits -= 1
      return
    }
    await new Promise<void>((resolve) => this.waiters.push(resolve))
  }
}

class OutboundStreamSender {
  private ws: CoreWsClient
  private root: protobuf.Root
  private requestId: number
  private window: OutboundWindow
  private maxFrameBytes: number
  private nextIndex: number = 1

  constructor(ws: CoreWsClient, root: protobuf.Root, requestId: number, window: OutboundWindow, maxFrameBytes: number) {
    this.ws = ws
    this.root = root
    this.requestId = requestId
    this.window = window
    this.maxFrameBytes = Math.max(0, maxFrameBytes | 0)
  }

  public async sendData(payload: Uint8Array): Promise<void> {
    const RpcFrame = this.root.lookupType("p2p.rpc.v1.RpcFrame")
    const chunks = chunkBytes(payload ?? new Uint8Array(), this.maxFrameBytes)
    for (let i = 0; i < chunks.length; i++) {
      await this.window.acquire()
      const frame = RpcFrame.encode({
        frame_type: 2,
        meta: { request_id: String(this.requestId) },
        payload: chunks[i],
        chunk_index: i,
        end_of_message: i === chunks.length - 1,
        end_of_stream: false,
      }).finish()
      await this.ws.send(
        {
          seq: this.requestId,
          commandOrdinal: this.ws.mustOrdinal("RPC_STREAM"),
          data: frame,
          index: this.nextIndex++,
          completed: false,
          canceled: false,
        },
        true,
      )
    }
  }

  public async sendClose(): Promise<void> {
    const RpcFrame = this.root.lookupType("p2p.rpc.v1.RpcFrame")
    const frame = RpcFrame.encode({ frame_type: 3, meta: { request_id: String(this.requestId) }, end_of_stream: true }).finish()
    await this.ws.send(
      { seq: this.requestId, commandOrdinal: this.ws.mustOrdinal("RPC_STREAM"), data: frame, index: this.nextIndex++, completed: true, canceled: false },
      true,
    )
  }
}

function chunkBytes(payload: Uint8Array, maxFrameBytes: number): Uint8Array[] {
  if (maxFrameBytes <= 0 || payload.length <= maxFrameBytes) return [payload]
  const out: Uint8Array[] = []
  for (let offset = 0; offset < payload.length; offset += maxFrameBytes) {
    out.push(payload.subarray(offset, Math.min(payload.length, offset + maxFrameBytes)))
  }
  return out
}
