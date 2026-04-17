import fs from "node:fs"
import path from "node:path"
import crypto from "node:crypto"
import { WebSocketServer, WebSocket } from "ws"

import { decodeFrame, encodeFrame } from "../src/frame.js"
import { loadProtoRoot } from "../src/proto.js"
import { decodeWrapper, encodeWrapper } from "../src/wrapper.js"
import { loadClientConfig, parseIntMaybeHex } from "../src/config.js"
import { rsaOaepSha256Decrypt, rsaOaepSha256Encrypt } from "../src/handshake.js"

const argv = process.argv.slice(2)
const cfgPath = argv[0] ?? path.resolve("..", "p2p-ws-protocol", "examples", "peer1.yaml")
const cfg = loadClientConfig(cfgPath)

const rest = argv.slice(1)
const useRelay = rest.includes("--relay")
const useHint = rest.includes("--hint")
const targetNodeId64 = rest.find((a) => !a.startsWith("--"))

const root = await loadProtoRoot()
const Hand = root.lookupType("p2pws.Hand")
const HandAckPlain = root.lookupType("p2pws.HandAckPlain")
const CenterHelloBody = root.lookupType("p2pws.CenterHelloBody")
const CenterHello = root.lookupType("p2pws.CenterHello")
const CenterHelloAck = root.lookupType("p2pws.CenterHelloAck")
const GetNode = root.lookupType("p2pws.GetNode")
const GetNodeAck = root.lookupType("p2pws.GetNodeAck")
const ConnectHint = root.lookupType("p2pws.ConnectHint")
const ConnectHintAck = root.lookupType("p2pws.ConnectHintAck")
const IncomingHint = root.lookupType("p2pws.IncomingHint")
const PeerHelloBody = root.lookupType("p2pws.PeerHelloBody")
const PeerHello = root.lookupType("p2pws.PeerHello")
const PeerHelloAck = root.lookupType("p2pws.PeerHelloAck")
const RelayData = root.lookupType("p2pws.RelayData")
const FilePutRequest = root.lookupType("p2pws.FilePutRequest")
const FilePutResponse = root.lookupType("p2pws.FilePutResponse")
const FileGetRequest = root.lookupType("p2pws.FileGetRequest")
const FileGetResponse = root.lookupType("p2pws.FileGetResponse")
const P2PWrapper = root.lookupType("p2pws.P2PWrapper")

// Simple file storage directory
const storageDir = path.resolve(path.dirname(cfgPath), `../data/node_${cfg.user_id}`)
fs.mkdirSync(storageDir, { recursive: true })

const magic = parseIntMaybeHex(cfg.magic, 0x1234)
const version = cfg.version ?? 1
const flagsPlain = cfg.flags_plain ?? 4
const flagsEncrypted = cfg.flags_encrypted ?? 5
const maxFramePayload = cfg.max_frame_payload ?? 4 * 1024 * 1024
const cryptoMode = (cfg.crypto_mode ?? "KEYFILE_XOR_RSA_OAEP").trim()
const listenPort = parseInt(cfg.listen_port ?? "0", 10)

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
let targetNodeEndpoints: any[] = []
let dialingPeer = false

// 1. Start Local WebSocket Server for incoming Peer connections
if (listenPort > 0) {
  const wss = new WebSocketServer({ port: listenPort })
  wss.on("connection", (ws) => {
    console.log(`[PeerServer] Accepted connection`)
    let peerOffset = -1
    let peerHandshakeDone = false

    ws.on("message", (data: Buffer) => {
      const f = decodeFrame(new Uint8Array(data))
      if (!peerHandshakeDone && peerOffset < 0) {
        // Expect Hand message
        const w = decodeWrapper(root, f.cipherPayload)
        if (w.command === -10001) {
          const hand = Hand.decode(w.data ?? new Uint8Array()) as any
          peerOffset = crypto.randomInt(0, 1024)
          const ackPlain = HandAckPlain.encode({
            session_id: crypto.randomBytes(16),
            selected_key_id: keyId,
            offset: peerOffset,
            max_frame_payload: maxFramePayload,
            header_policy_id: 0
          }).finish()
          
          const pubKey = crypto.createPublicKey({ key: Buffer.from(hand.client_pubkey), type: "spki", format: "der" })
          const encryptedAck = crypto.publicEncrypt({ key: pubKey, padding: crypto.constants.RSA_PKCS1_OAEP_PADDING, oaepHash: "sha256" }, ackPlain)
          
          const ackWrap = encodeWrapper(root, { seq: w.seq, command: -10002, data: encryptedAck })
          ws.send(encodeFrame({ length: ackWrap.length, magic, version, flags: flagsPlain }, ackWrap))
        }
        return
      }

      // Encrypted traffic
      const plainPayload = xorWithFile(f.cipherPayload, peerOffset)
      const w = decodeWrapper(root, plainPayload)
      if (w.command === -12001) { // PeerHello
        const hello = PeerHello.decode(w.data ?? new Uint8Array()) as any
        console.log(`[PeerServer] Received PeerHello from node_id64=${hello.body.node_id64}`)
        peerHandshakeDone = true
        
        const ack = PeerHelloAck.encode({
          node_key: Buffer.from(crypto.createHash("sha256").update(clientPubDer).digest()),
          server_time_ms: Date.now()
        }).finish()
        const ackWrap = encodeWrapper(root, { seq: w.seq, command: -12002, data: ack })
        const cipher = xorWithFile(ackWrap, peerOffset)
        ws.send(encodeFrame({ length: cipher.length, magic, version, flags: flagsEncrypted }, cipher))
        return
      }
      
      if (w.command === 1) { // ECHO
        console.log(`[PeerServer] Received ECHO: ${Buffer.from(w.data ?? new Uint8Array()).toString("utf-8")}`)
        const respWrap = encodeWrapper(root, { seq: w.seq, command: 1, data: w.data })
        const cipher = xorWithFile(respWrap, peerOffset)
        ws.send(encodeFrame({ length: cipher.length, magic, version, flags: flagsEncrypted }, cipher))
        return
      }
      
      if (w.command === 1001) { // FILE_PUT_REQ
        const req = FilePutRequest.decode(w.data ?? new Uint8Array()) as any
        const hash = Buffer.from(req.file_hash_sha256).toString("hex")
        console.log(`[PeerServer] Received FilePutRequest: ${req.file_name} (${hash})`)
        const filePath = path.join(storageDir, hash)
        fs.writeFileSync(filePath, req.content)
        
        const resp = FilePutResponse.encode({ success: true, file_hash_sha256: req.file_hash_sha256 }).finish()
        const respWrap = encodeWrapper(root, { seq: w.seq, command: 1002, data: resp })
        const cipher = xorWithFile(respWrap, peerOffset)
        ws.send(encodeFrame({ length: cipher.length, magic, version, flags: flagsEncrypted }, cipher))
        return
      }

      if (w.command === 1003) { // FILE_GET_REQ
        const req = FileGetRequest.decode(w.data ?? new Uint8Array()) as any
        const hash = Buffer.from(req.file_hash_sha256).toString("hex")
        console.log(`[PeerServer] Received FileGetRequest for hash: ${hash}`)
        const filePath = path.join(storageDir, hash)
        
        let resp: Uint8Array
        if (fs.existsSync(filePath)) {
          const content = fs.readFileSync(filePath)
          resp = FileGetResponse.encode({ found: true, content }).finish()
        } else {
          resp = FileGetResponse.encode({ found: false }).finish()
        }
        
        const respWrap = encodeWrapper(root, { seq: w.seq, command: 1004, data: resp })
        const cipher = xorWithFile(respWrap, peerOffset)
        ws.send(encodeFrame({ length: cipher.length, magic, version, flags: flagsEncrypted }, cipher))
        return
      }
    })
  })
  console.log(`[PeerServer] Listening on port ${listenPort}`)
}

// 2. Connect to Center Server
const centerWs = new WebSocket(cfg.ws_url)
centerWs.binaryType = "arraybuffer"

centerWs.on("open", () => {
  console.log(`[CenterClient] Connected to Center`)
  const reported = Array.isArray(cfg.reported_endpoints) ? cfg.reported_endpoints.slice() : []
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
    console.log(`[CenterClient] CenterHelloAck received. Successfully joined network!`)
    if (targetNodeId64) {
      if (useHint) {
        console.log(`[CenterClient] Requesting connect hint for target node ${targetNodeId64}...`)
        const req = ConnectHint.encode(ConnectHint.create({ target_node_id64: targetNodeId64 })).finish()
        const wrap = encodeWrapper(root, { seq: 3, command: -11030, data: req })
        const cipher = xorWithFile(wrap, centerOffset)
        centerWs.send(encodeFrame({ length: cipher.length, magic, version, flags: flagsEncrypted }, cipher))
      } else {
        console.log(`[CenterClient] Querying for target node ${targetNodeId64}...`)
        const req = GetNode.encode(GetNode.create({ node_id64: targetNodeId64 })).finish()
        const wrap = encodeWrapper(root, { seq: 3, command: -11010, data: req })
        const cipher = xorWithFile(wrap, centerOffset)
        centerWs.send(encodeFrame({ length: cipher.length, magic, version, flags: flagsEncrypted }, cipher))
      }
    }
    return
  }

  if (w.command === -11030) {
    const r = ConnectHintAck.decode(w.data ?? new Uint8Array()) as any
    if (r.found && r.target_endpoints && r.target_endpoints.length > 0) {
      console.log(`[CenterClient] ConnectHintAck found endpoints: ${r.target_endpoints.map((e:any)=>e.transport+"://"+e.addr).join(", ")}`)
      const endpoint = r.target_endpoints.find((e: any) => e.addr.includes(":")) || r.target_endpoints[0]
      if (!dialingPeer) {
        dialingPeer = true
        connectToPeer(endpoint)
      }
    } else {
      console.log(`[CenterClient] ConnectHintAck: target not found or no endpoints.`)
    }
    return
  }

  if (w.command === -11031) {
    const r = IncomingHint.decode(w.data ?? new Uint8Array()) as any
    if (r.source_endpoints && r.source_endpoints.length > 0) {
      console.log(`[CenterClient] IncomingHint received: source_endpoints=${r.source_endpoints.map((e:any)=>e.transport+"://"+e.addr).join(", ")}`)
      const endpoint = r.source_endpoints.find((e: any) => e.addr.includes(":")) || r.source_endpoints[0]
      if (!dialingPeer) {
        dialingPeer = true
        connectToPeer(endpoint)
      }
    }
    return
  }

  if (w.command === -11011) {
    const r = GetNodeAck.decode(w.data ?? new Uint8Array()) as any
    if (r.found) {
      if (useRelay) {
        console.log(`[CenterClient] Target node found! Using relay to send ECHO...`)
        const targetNodeKey = Buffer.from(r.node_key as Uint8Array)
        const relayReq = RelayData.encode({
          target_node_id64: targetNodeId64,
          target_node_key: targetNodeKey,
          source_node_id64: nodeId64Str,
          source_node_key: Buffer.from(crypto.createHash("sha256").update(clientPubDer).digest()),
          payload: Buffer.from("Hello via Relay!")
        }).finish()
        const wrap = encodeWrapper(root, { seq: 4, command: -11012, data: relayReq })
        const cipher = xorWithFile(wrap, centerOffset)
        centerWs.send(encodeFrame({ length: cipher.length, magic, version, flags: flagsEncrypted }, cipher))
      } else if (r.endpoints && r.endpoints.length > 0) {
        console.log(`[CenterClient] Target node found! Endpoints: ${r.endpoints.map((e:any)=>e.transport+"://"+e.addr).join(", ")}`)
        const endpoint = r.endpoints.find((e: any) => e.addr.includes(":")) || r.endpoints[0]
        if (!dialingPeer) {
          dialingPeer = true
          connectToPeer(endpoint)
        }
      } else {
        console.log(`[CenterClient] Target node has no endpoints.`)
      }
    } else {
      console.log(`[CenterClient] Target node not found.`)
    }
    return
  }

  if (w.command === -11012) {
    const r = RelayData.decode(w.data ?? new Uint8Array()) as any
    const msg = Buffer.from(r.payload as Uint8Array).toString("utf-8")
    console.log(`[CenterClient] Received RelayData from node_id64=${r.source_node_id64}: ${msg}`)
    // Reply back if it's not a reply
    if (msg === "Hello via Relay!") {
      const reply = RelayData.encode({
        target_node_id64: r.source_node_id64,
        target_node_key: Buffer.from(r.source_node_key as Uint8Array),
        source_node_id64: nodeId64Str,
        source_node_key: Buffer.from(crypto.createHash("sha256").update(clientPubDer).digest()),
        payload: Buffer.from("Relay ECHO Reply!")
      }).finish()
      const wrap = encodeWrapper(root, { seq: w.seq, command: -11012, data: reply })
      const cipher = xorWithFile(wrap, centerOffset)
      centerWs.send(encodeFrame({ length: cipher.length, magic, version, flags: flagsEncrypted }, cipher))
    }
    return
  }
})

// 3. Connect to Peer
function connectToPeer(endpoint: any) {
  const peerWsUrl = `${endpoint.transport}://${endpoint.addr}`
  console.log(`[PeerClient] Connecting to peer at ${peerWsUrl}...`)
  const peerWs = new WebSocket(peerWsUrl)
  peerWs.binaryType = "arraybuffer"
  let peerOffset = -1

  peerWs.on("open", () => {
    console.log(`[PeerClient] Connected. Sending Hand...`)
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
      console.log(`[PeerClient] HandAck received. Sending PeerHello...`)
      
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
      console.log(`[PeerClient] PeerHelloAck received. Sending FilePutRequest...`)
      
      const fileContent = Buffer.from("Hello P2P Storage World! " + Date.now())
      const fileHash = crypto.createHash("sha256").update(fileContent).digest()
      const req = FilePutRequest.encode({
        file_name: "hello.txt",
        file_size: fileContent.length,
        file_hash_sha256: fileHash,
        content: fileContent
      }).finish()
      
      const reqWrap = encodeWrapper(root, { seq: 3, command: 1001, data: req })
      const cipher = xorWithFile(reqWrap, peerOffset)
      peerWs.send(encodeFrame({ length: cipher.length, magic, version, flags: flagsEncrypted }, cipher))
      
      // Save hash to ask later
      ;(peerWs as any).lastHash = fileHash
      return
    }

    if (w.command === 1002) {
      const resp = FilePutResponse.decode(w.data ?? new Uint8Array()) as any
      console.log(`[PeerClient] Received FilePutResponse: success=${resp.success}`)
      if (resp.success) {
        console.log(`[PeerClient] Requesting FileGet...`)
        const req = FileGetRequest.encode({ file_hash_sha256: (peerWs as any).lastHash }).finish()
        const reqWrap = encodeWrapper(root, { seq: 4, command: 1003, data: req })
        const cipher = xorWithFile(reqWrap, peerOffset)
        peerWs.send(encodeFrame({ length: cipher.length, magic, version, flags: flagsEncrypted }, cipher))
      }
      return
    }
    
    if (w.command === 1004) {
      const resp = FileGetResponse.decode(w.data ?? new Uint8Array()) as any
      console.log(`[PeerClient] Received FileGetResponse: found=${resp.found}`)
      if (resp.found) {
        console.log(`[PeerClient] File Content: ${Buffer.from(resp.content).toString("utf-8")}`)
      }
      return
    }

    if (w.command === 1) {
      console.log(`[PeerClient] Received ECHO Reply: ${Buffer.from(w.data ?? new Uint8Array()).toString("utf-8")}`)
    }
  })
}
