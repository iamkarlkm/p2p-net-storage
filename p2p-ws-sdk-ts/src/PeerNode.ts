import fs from "node:fs"
import path from "node:path"
import crypto from "node:crypto"
import { WebSocketServer, WebSocket } from "ws"

import { decodeFrame, encodeFrame } from "./frame.js"
import { loadProtoRoot } from "./proto.js"
import { decodeWrapper, encodeWrapper } from "./wrapper.js"
import { ClientConfig } from "./config.js"
import { rsaOaepSha256Decrypt } from "./handshake.js"
import { FORCE_PUT_FILE, GET_FILE, GET_FILE_SEGMENTS, IM_CHAT_ACK, IM_CHAT_HISTORY_REQUEST, IM_CHAT_HISTORY_RESPONSE, IM_CHAT_RECEIVE, IM_CHAT_SEND, IM_CHAT_STATUS_UPDATE, IM_GROUP_CREATE, IM_GROUP_DISMISS, IM_GROUP_JOIN, IM_GROUP_LEAVE, IM_GROUP_LIST, IM_GROUP_MEMBERS, IM_GROUP_MESSAGE_RECEIVE, IM_GROUP_MESSAGE_SEND, IM_GROUP_REMOVE_MEMBER, IM_GROUP_SET_ADMIN, IM_GROUP_UPDATE_INFO, IM_SYSTEM_STATUS, IM_USER_LOGIN, IM_USER_LOGOUT, IM_USER_LIST, INFO_FILE, INVALID_DATA, OK_GET_FILE, OK_GET_FILE_SEGMENTS, PUT_FILE, PUT_FILE_SEGMENTS, PUT_FILE_SEGMENTS_COMPLETE, STD_ERROR, STD_OK } from "./commands.js"
import { decodeIMChatAck, decodeIMChatHistoryRequest, decodeIMChatModel, decodeIMGroupDismissRequest, decodeIMGroupModel, decodeIMGroupRemoveMemberRequest, decodeIMGroupSetAdminRequest, decodeIMGroupUpdateInfoRequest, decodeIMUserModel, encodeIMChatAck, encodeIMChatHistoryResponse, encodeIMChatModel, encodeIMGroupListResponse, encodeIMGroupMembersResponse, encodeIMGroupModel, encodeIMSystemEvent, encodeIMUserListResponse, encodeIMUserModel } from "./im.js"

export class PeerNodeDaemon {
  private static readonly IM_STORE_PUBLIC_U32 = 0xFFFFFFFF
  private static readonly IM_STORE_GROUP_U32 = 0xFFFFFFFE
  private static readonly IM_STORE_PRIVATE_U32 = 0xFFFFFFFD

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
  private storageLocations = new Map<number, string>()
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

  private imOnline = new Map<string, { ws: WebSocket; sendEncrypted: (seq: number, command: number, data?: Uint8Array) => void; userModel: any }>()
  private imChatHistory = new Map<string, any[]>()
  private imMsgSenderIndex = new Map<string, string>()
  private imMsgStatusIndex = new Map<string, string>()
  private imGroups = new Map<string, any>()
  private imGroupMembers = new Map<string, Set<string>>()
  private imGroupHistory = new Map<string, any[]>()
  private imGroupAdmins = new Map<string, Set<string>>()

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
    this.loadStorageLocations()

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
      let imUserId: string | null = null

      const nowMs = () => Date.now()
      const pairKey = (a: string, b: string) => (a <= b ? `${a}|${b}` : `${b}|${a}`)
      const newMsgId = () => `${nowMs()}_${crypto.randomInt(1 << 30)}`
      const mergeStatusExtra = (extra: string, status: string) => {
        const key = "|status:"
        const idx = extra.indexOf(key)
        if (idx < 0) return `${extra}${key}${status}`
        const end = extra.indexOf("|", idx + 1)
        if (end < 0) return `${extra.substring(0, idx)}${key}${status}`
        return `${extra.substring(0, idx)}${key}${status}${extra.substring(end)}`
      }

      const sendEncrypted = (seq: number, command: number, data?: Uint8Array) => {
        if (peerOffset < 0) return
        const respWrap = encodeWrapper(this.root, { seq, command, data })
        const cipher = this.xorWithFile(respWrap, peerOffset)
        ws.send(encodeFrame({ length: cipher.length, magic: this.magic, version: this.version, flags: this.flagsEncrypted }, cipher))
      }

      const cleanupIm = () => {
        if (!imUserId) return
        const cur = this.imOnline.get(imUserId)
        if (cur && cur.ws === ws) {
          this.imOnline.delete(imUserId)
        }
        imUserId = null
      }

      ws.on("close", cleanupIm)

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

        if (this.handleStoreFileCommands(w, sendEncrypted)) {
          return
        }
        
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

        if (w.command === IM_USER_LOGIN) {
          try {
            const user = decodeIMUserModel(this.root, w.data ?? new Uint8Array())
            const uid = String(user.user_id ?? "")
            if (!uid) {
              sendEncrypted(w.seq, INVALID_DATA, Buffer.from("user_id required", "utf-8"))
              return
            }
            cleanupIm()
            imUserId = uid
            ;(user as any).status = user.status ? String(user.status) : "ONLINE"
            const out = user
            this.imOnline.set(uid, { ws, sendEncrypted, userModel: out })
            sendEncrypted(w.seq, STD_OK, encodeIMUserModel(this.root, out))
          } catch (e: any) {
            sendEncrypted(w.seq, INVALID_DATA, Buffer.from(String(e?.message ?? e), "utf-8"))
          }
          return
        }

        if (w.command === IM_USER_LOGOUT) {
          cleanupIm()
          sendEncrypted(w.seq, STD_OK, new Uint8Array())
          return
        }

        if (w.command === IM_USER_LIST) {
          const items = Array.from(this.imOnline.values()).map((s) => s.userModel)
          sendEncrypted(w.seq, STD_OK, encodeIMUserListResponse(this.root, { items }))
          return
        }

        if (w.command === IM_CHAT_SEND) {
          try {
            const msg = decodeIMChatModel(this.root, w.data ?? new Uint8Array())
            const senderId = String(msg.sender_id ?? "")
            const receiverId = String(msg.receiver_id ?? "")
            if (!senderId || !receiverId) {
              sendEncrypted(w.seq, INVALID_DATA, Buffer.from("sender_id and receiver_id required", "utf-8"))
              return
            }
            const msgId = msg.msg_id ? String(msg.msg_id) : newMsgId()
            const ts = msg.timestamp && Number(msg.timestamp) > 0 ? Number(msg.timestamp) : nowMs()
            ;(msg as any).msg_id = msgId
            ;(msg as any).timestamp = ts
            const normalized = this.persistImFileIfNeeded(msg, "")

            const key = pairKey(senderId, receiverId)
            const list = this.imChatHistory.get(key) ?? []
            list.push(normalized)
            if (list.length > 200) {
              list.splice(0, list.length - 200)
            }
            this.imChatHistory.set(key, list)
            this.imMsgSenderIndex.set(msgId, senderId)
            this.imMsgStatusIndex.set(msgId, "DELIVERED")

            const receiver = this.imOnline.get(receiverId)
            receiver?.sendEncrypted(0, IM_CHAT_RECEIVE, encodeIMChatModel(this.root, normalized))

            const ack = { msg_id: msgId, user_id: senderId, timestamp: nowMs(), ack_type: "DELIVERED", peer_id: receiverId }
            sendEncrypted(w.seq, STD_OK, encodeIMChatAck(this.root, ack))
          } catch (e: any) {
            sendEncrypted(w.seq, INVALID_DATA, Buffer.from(String(e?.message ?? e), "utf-8"))
          }
          return
        }

        if (w.command === IM_CHAT_ACK) {
          try {
            const ack = decodeIMChatAck(this.root, w.data ?? new Uint8Array())
            const msgId = String(ack.msg_id ?? "")
            if (!msgId) {
              sendEncrypted(w.seq, INVALID_DATA, Buffer.from("msg_id required", "utf-8"))
              return
            }
            const senderId = this.imMsgSenderIndex.get(msgId)
            const sender = senderId ? this.imOnline.get(senderId) : undefined
            sender?.sendEncrypted(0, IM_CHAT_ACK, encodeIMChatAck(this.root, ack))
            sendEncrypted(w.seq, STD_OK, new Uint8Array())
          } catch (e: any) {
            sendEncrypted(w.seq, INVALID_DATA, Buffer.from(String(e?.message ?? e), "utf-8"))
          }
          return
        }

        if (w.command === IM_CHAT_STATUS_UPDATE) {
          try {
            const ack = decodeIMChatAck(this.root, w.data ?? new Uint8Array())
            const msgId = String(ack.msg_id ?? "")
            if (!msgId) {
              sendEncrypted(w.seq, INVALID_DATA, Buffer.from("msg_id required", "utf-8"))
              return
            }
            const senderId = this.imMsgSenderIndex.get(msgId)
            const sender = senderId ? this.imOnline.get(senderId) : undefined
            sender?.sendEncrypted(0, IM_CHAT_STATUS_UPDATE, encodeIMChatAck(this.root, ack))
            const ackType = ack.ack_type ? String(ack.ack_type) : ""
            if (ackType) {
              this.imMsgStatusIndex.set(msgId, ackType)
            }
            sendEncrypted(w.seq, STD_OK, new Uint8Array())
          } catch (e: any) {
            sendEncrypted(w.seq, INVALID_DATA, Buffer.from(String(e?.message ?? e), "utf-8"))
          }
          return
        }

        if (w.command === IM_CHAT_HISTORY_REQUEST) {
          try {
            const q = decodeIMChatHistoryRequest(this.root, w.data ?? new Uint8Array())
            const userId = String(q.user_id ?? "")
            const peerId = String(q.peer_id ?? "")
            if (!userId || !peerId) {
              sendEncrypted(w.seq, INVALID_DATA, Buffer.from("user_id and peer_id required", "utf-8"))
              return
            }
            const limit = q.limit && Number(q.limit) > 0 ? Number(q.limit) : 50
            const groupList = this.imGroupHistory.get(peerId)
            const list = groupList ?? (this.imChatHistory.get(pairKey(userId, peerId)) ?? [])
            const start = list.length > limit ? list.length - limit : 0
            const items = list.slice(start)
            for (const it of items) {
              const msgId = it?.msg_id ? String(it.msg_id) : ""
              if (!msgId) continue
              const st = this.imMsgStatusIndex.get(msgId)
              if (!st) continue
              const extra = it?.extra ? String(it.extra) : ""
              it.extra = mergeStatusExtra(extra, st)
            }
            sendEncrypted(w.seq, IM_CHAT_HISTORY_RESPONSE, encodeIMChatHistoryResponse(this.root, { items }))
          } catch (e: any) {
            sendEncrypted(w.seq, INVALID_DATA, Buffer.from(String(e?.message ?? e), "utf-8"))
          }
          return
        }

        if (w.command === IM_GROUP_CREATE) {
          try {
            const g = decodeIMGroupModel(this.root, w.data ?? new Uint8Array())
            const ownerId = imUserId ? String(imUserId) : String(g.owner_id ?? "")
            if (!ownerId) {
              sendEncrypted(w.seq, INVALID_DATA, Buffer.from("owner_id required", "utf-8"))
              return
            }
            const groupId = g.group_id ? String(g.group_id) : `g_${newMsgId()}`
            ;(g as any).group_id = groupId
            ;(g as any).owner_id = ownerId
            ;(g as any).admin_ids = []
            this.imGroups.set(groupId, g)
            const members = this.imGroupMembers.get(groupId) ?? new Set<string>()
            members.add(ownerId)
            this.imGroupMembers.set(groupId, members)
            this.imGroupAdmins.set(groupId, new Set<string>())
            sendEncrypted(w.seq, STD_OK, encodeIMGroupModel(this.root, g))
          } catch (e: any) {
            sendEncrypted(w.seq, INVALID_DATA, Buffer.from(String(e?.message ?? e), "utf-8"))
          }
          return
        }

        if (w.command === IM_GROUP_DISMISS) {
          try {
            const r = decodeIMGroupDismissRequest(this.root, w.data ?? new Uint8Array())
            const groupId = String(r.group_id ?? "")
            const operatorId = String(r.operator_id ?? "")
            if (!groupId || !operatorId) {
              sendEncrypted(w.seq, INVALID_DATA, Buffer.from("group_id and operator_id required", "utf-8"))
              return
            }
            const g = this.imGroups.get(groupId)
            if (!g) {
              sendEncrypted(w.seq, INVALID_DATA, Buffer.from("group not found", "utf-8"))
              return
            }
            const ownerId = g.owner_id ? String(g.owner_id) : ""
            if (ownerId !== operatorId) {
              sendEncrypted(w.seq, INVALID_DATA, Buffer.from("permission denied", "utf-8"))
              return
            }
            const members = this.imGroupMembers.get(groupId) ?? new Set<string>()
            this.imGroups.delete(groupId)
            this.imGroupMembers.delete(groupId)
            this.imGroupHistory.delete(groupId)
            this.imGroupAdmins.delete(groupId)
            const evt = { type: "GROUP_DISMISSED", group_id: groupId, operator_id: operatorId, target_id: "", timestamp: nowMs(), message: "group dismissed" }
            for (const uid of members) {
              const s = this.imOnline.get(uid)
              s?.sendEncrypted(0, IM_SYSTEM_STATUS, encodeIMSystemEvent(this.root, evt))
            }
            sendEncrypted(w.seq, STD_OK, new Uint8Array())
          } catch (e: any) {
            sendEncrypted(w.seq, INVALID_DATA, Buffer.from(String(e?.message ?? e), "utf-8"))
          }
          return
        }

        if (w.command === IM_GROUP_JOIN) {
          try {
            const uid = imUserId ? String(imUserId) : ""
            if (!uid) {
              sendEncrypted(w.seq, INVALID_DATA, Buffer.from("login required", "utf-8"))
              return
            }
            const g = decodeIMGroupModel(this.root, w.data ?? new Uint8Array())
            const groupId = String(g.group_id ?? "")
            if (!groupId) {
              sendEncrypted(w.seq, INVALID_DATA, Buffer.from("group_id required", "utf-8"))
              return
            }
            if (!this.imGroups.has(groupId)) {
              sendEncrypted(w.seq, INVALID_DATA, Buffer.from("group not found", "utf-8"))
              return
            }
            const members = this.imGroupMembers.get(groupId) ?? new Set<string>()
            members.add(uid)
            this.imGroupMembers.set(groupId, members)
            const evt = { type: "GROUP_MEMBER_JOINED", group_id: groupId, operator_id: uid, target_id: uid, timestamp: nowMs(), message: "joined group" }
            for (const mid of members) {
              const s = this.imOnline.get(mid)
              s?.sendEncrypted(0, IM_SYSTEM_STATUS, encodeIMSystemEvent(this.root, evt))
            }
            sendEncrypted(w.seq, STD_OK, new Uint8Array())
          } catch (e: any) {
            sendEncrypted(w.seq, INVALID_DATA, Buffer.from(String(e?.message ?? e), "utf-8"))
          }
          return
        }

        if (w.command === IM_GROUP_LEAVE) {
          try {
            const uid = imUserId ? String(imUserId) : ""
            if (!uid) {
              sendEncrypted(w.seq, INVALID_DATA, Buffer.from("login required", "utf-8"))
              return
            }
            const g = decodeIMGroupModel(this.root, w.data ?? new Uint8Array())
            const groupId = String(g.group_id ?? "")
            if (!groupId) {
              sendEncrypted(w.seq, INVALID_DATA, Buffer.from("group_id required", "utf-8"))
              return
            }
            this.imGroupMembers.get(groupId)?.delete(uid)
            this.imGroupAdmins.get(groupId)?.delete(uid)
            const members = this.imGroupMembers.get(groupId) ?? new Set<string>()
            const evt = { type: "GROUP_MEMBER_LEFT", group_id: groupId, operator_id: uid, target_id: uid, timestamp: nowMs(), message: "left group" }
            for (const mid of members) {
              const s = this.imOnline.get(mid)
              s?.sendEncrypted(0, IM_SYSTEM_STATUS, encodeIMSystemEvent(this.root, evt))
            }
            sendEncrypted(w.seq, STD_OK, new Uint8Array())
          } catch (e: any) {
            sendEncrypted(w.seq, INVALID_DATA, Buffer.from(String(e?.message ?? e), "utf-8"))
          }
          return
        }

        if (w.command === IM_GROUP_LIST) {
          const uid = imUserId ? String(imUserId) : ""
          if (!uid) {
            const items = Array.from(this.imGroups.values())
            sendEncrypted(w.seq, STD_OK, encodeIMGroupListResponse(this.root, { items }))
            return
          }
          const items: any[] = []
          for (const g of this.imGroups.values()) {
            const groupId = String(g?.group_id ?? "")
            if (!groupId) continue
            const members = this.imGroupMembers.get(groupId)
            if (!members || !members.has(uid)) continue
            items.push(g)
          }
          sendEncrypted(w.seq, STD_OK, encodeIMGroupListResponse(this.root, { items }))
          return
        }

        if (w.command === IM_GROUP_MEMBERS) {
          try {
            const g = decodeIMGroupModel(this.root, w.data ?? new Uint8Array())
            const groupId = String(g.group_id ?? "")
            if (!groupId) {
              sendEncrypted(w.seq, INVALID_DATA, Buffer.from("group_id required", "utf-8"))
              return
            }
            const members = this.imGroupMembers.get(groupId) ?? new Set<string>()
            const items: any[] = []
            for (const uid of members) {
              const s = this.imOnline.get(uid)
              if (!s) continue
              items.push(s.userModel)
            }
            sendEncrypted(w.seq, STD_OK, encodeIMGroupMembersResponse(this.root, { group_id: groupId, items }))
          } catch (e: any) {
            sendEncrypted(w.seq, INVALID_DATA, Buffer.from(String(e?.message ?? e), "utf-8"))
          }
          return
        }

        if (w.command === IM_GROUP_MESSAGE_SEND) {
          try {
            const msg = decodeIMChatModel(this.root, w.data ?? new Uint8Array())
            const senderId = String(msg.sender_id ?? "")
            const groupId = String(msg.receiver_id ?? "")
            if (!senderId || !groupId) {
              sendEncrypted(w.seq, INVALID_DATA, Buffer.from("sender_id and receiver_id(group_id) required", "utf-8"))
              return
            }
            if (!this.imGroups.has(groupId)) {
              sendEncrypted(w.seq, INVALID_DATA, Buffer.from("group not found", "utf-8"))
              return
            }
            const members = this.imGroupMembers.get(groupId)
            if (!members || !members.has(senderId)) {
              sendEncrypted(w.seq, INVALID_DATA, Buffer.from("not a member", "utf-8"))
              return
            }
            const msgId = msg.msg_id ? String(msg.msg_id) : newMsgId()
            const ts = msg.timestamp && Number(msg.timestamp) > 0 ? Number(msg.timestamp) : nowMs()
            ;(msg as any).msg_id = msgId
            ;(msg as any).timestamp = ts
            ;(msg as any).receiver_type = msg.receiver_type ? String(msg.receiver_type) : "GROUP"
            const normalized = this.persistImFileIfNeeded(msg, groupId)

            const list = this.imGroupHistory.get(groupId) ?? []
            list.push(normalized)
            if (list.length > 200) {
              list.splice(0, list.length - 200)
            }
            this.imGroupHistory.set(groupId, list)
            this.imMsgSenderIndex.set(msgId, senderId)
            this.imMsgStatusIndex.set(msgId, "DELIVERED")

            for (const uid of members) {
              if (uid === senderId) continue
              const s = this.imOnline.get(uid)
              s?.sendEncrypted(0, IM_GROUP_MESSAGE_RECEIVE, encodeIMChatModel(this.root, normalized))
            }

            const ack = { msg_id: msgId, user_id: senderId, timestamp: nowMs(), ack_type: "DELIVERED", peer_id: groupId }
            sendEncrypted(w.seq, STD_OK, encodeIMChatAck(this.root, ack))
          } catch (e: any) {
            sendEncrypted(w.seq, INVALID_DATA, Buffer.from(String(e?.message ?? e), "utf-8"))
          }
          return
        }

        if (w.command === IM_GROUP_REMOVE_MEMBER) {
          try {
            const r = decodeIMGroupRemoveMemberRequest(this.root, w.data ?? new Uint8Array())
            const groupId = String(r.group_id ?? "")
            const operatorId = String(r.operator_id ?? "")
            const memberId = String(r.member_id ?? "")
            if (!groupId || !operatorId || !memberId) {
              sendEncrypted(w.seq, INVALID_DATA, Buffer.from("group_id/operator_id/member_id required", "utf-8"))
              return
            }
            const g = this.imGroups.get(groupId)
            if (!g) {
              sendEncrypted(w.seq, INVALID_DATA, Buffer.from("group not found", "utf-8"))
              return
            }
            const ownerId = g.owner_id ? String(g.owner_id) : ""
            const admins = this.imGroupAdmins.get(groupId) ?? new Set<string>()
            if (ownerId !== operatorId && !admins.has(operatorId)) {
              sendEncrypted(w.seq, INVALID_DATA, Buffer.from("permission denied", "utf-8"))
              return
            }
            this.imGroupMembers.get(groupId)?.delete(memberId)
            admins.delete(memberId)
            this.imGroupAdmins.set(groupId, admins)
            ;(g as any).admin_ids = Array.from(admins.values())
            sendEncrypted(w.seq, STD_OK, new Uint8Array())
            const evt = { type: "GROUP_MEMBER_REMOVED", group_id: groupId, operator_id: operatorId, target_id: memberId, timestamp: nowMs(), message: "removed from group" }
            const members = this.imGroupMembers.get(groupId) ?? new Set<string>()
            for (const mid of members) {
              const s = this.imOnline.get(mid)
              s?.sendEncrypted(0, IM_SYSTEM_STATUS, encodeIMSystemEvent(this.root, evt))
            }
            const target = this.imOnline.get(memberId)
            target?.sendEncrypted(0, IM_SYSTEM_STATUS, encodeIMSystemEvent(this.root, evt))
          } catch (e: any) {
            sendEncrypted(w.seq, INVALID_DATA, Buffer.from(String(e?.message ?? e), "utf-8"))
          }
          return
        }

        if (w.command === IM_GROUP_UPDATE_INFO) {
          try {
            const r = decodeIMGroupUpdateInfoRequest(this.root, w.data ?? new Uint8Array())
            const groupId = String(r.group_id ?? "")
            const operatorId = String(r.operator_id ?? "")
            if (!groupId || !operatorId) {
              sendEncrypted(w.seq, INVALID_DATA, Buffer.from("group_id and operator_id required", "utf-8"))
              return
            }
            const g = this.imGroups.get(groupId)
            if (!g) {
              sendEncrypted(w.seq, INVALID_DATA, Buffer.from("group not found", "utf-8"))
              return
            }
            const ownerId = g.owner_id ? String(g.owner_id) : ""
            const admins = this.imGroupAdmins.get(groupId) ?? new Set<string>()
            if (ownerId !== operatorId && !admins.has(operatorId)) {
              sendEncrypted(w.seq, INVALID_DATA, Buffer.from("permission denied", "utf-8"))
              return
            }
            const name = r.name ? String(r.name) : ""
            const avatar = r.avatar ? String(r.avatar) : ""
            const notice = r.notice ? String(r.notice) : ""
            const extra = r.extra ? String(r.extra) : ""
            if (name) g.name = name
            if (avatar) g.avatar = avatar
            if (notice) g.notice = notice
            if (extra) g.extra = extra
            ;(g as any).admin_ids = Array.from(admins.values())
            this.imGroups.set(groupId, g)
            sendEncrypted(w.seq, STD_OK, encodeIMGroupModel(this.root, g))
            const evt = { type: "GROUP_INFO_UPDATED", group_id: groupId, operator_id: operatorId, target_id: "", timestamp: nowMs(), message: "group info updated" }
            const members = this.imGroupMembers.get(groupId) ?? new Set<string>()
            for (const mid of members) {
              const s = this.imOnline.get(mid)
              s?.sendEncrypted(0, IM_SYSTEM_STATUS, encodeIMSystemEvent(this.root, evt))
            }
          } catch (e: any) {
            sendEncrypted(w.seq, INVALID_DATA, Buffer.from(String(e?.message ?? e), "utf-8"))
          }
          return
        }

        if (w.command === IM_GROUP_SET_ADMIN) {
          try {
            const r = decodeIMGroupSetAdminRequest(this.root, w.data ?? new Uint8Array())
            const groupId = String(r.group_id ?? "")
            const operatorId = String(r.operator_id ?? "")
            const memberId = String(r.member_id ?? "")
            const isAdmin = Boolean(r.is_admin)
            if (!groupId || !operatorId || !memberId) {
              sendEncrypted(w.seq, INVALID_DATA, Buffer.from("group_id/operator_id/member_id required", "utf-8"))
              return
            }
            const g = this.imGroups.get(groupId)
            if (!g) {
              sendEncrypted(w.seq, INVALID_DATA, Buffer.from("group not found", "utf-8"))
              return
            }
            const ownerId = g.owner_id ? String(g.owner_id) : ""
            if (ownerId !== operatorId) {
              sendEncrypted(w.seq, INVALID_DATA, Buffer.from("permission denied", "utf-8"))
              return
            }
            const members = this.imGroupMembers.get(groupId) ?? new Set<string>()
            if (!members.has(memberId)) {
              sendEncrypted(w.seq, INVALID_DATA, Buffer.from("member not in group", "utf-8"))
              return
            }
            const admins = this.imGroupAdmins.get(groupId) ?? new Set<string>()
            if (isAdmin) {
              admins.add(memberId)
            } else {
              admins.delete(memberId)
            }
            this.imGroupAdmins.set(groupId, admins)
            ;(g as any).admin_ids = Array.from(admins.values())
            this.imGroups.set(groupId, g)
            sendEncrypted(w.seq, STD_OK, encodeIMGroupModel(this.root, g))
            const evt = { type: "GROUP_ROLE_CHANGED", group_id: groupId, operator_id: operatorId, target_id: memberId, timestamp: nowMs(), message: isAdmin ? "set admin" : "unset admin" }
            const members = this.imGroupMembers.get(groupId) ?? new Set<string>()
            for (const mid of members) {
              const s = this.imOnline.get(mid)
              s?.sendEncrypted(0, IM_SYSTEM_STATUS, encodeIMSystemEvent(this.root, evt))
            }
            const target = this.imOnline.get(memberId)
            target?.sendEncrypted(0, IM_SYSTEM_STATUS, encodeIMSystemEvent(this.root, evt))
          } catch (e: any) {
            sendEncrypted(w.seq, INVALID_DATA, Buffer.from(String(e?.message ?? e), "utf-8"))
          }
          return
        }
      })
    })
    console.log(`[PeerDaemon] Listening for peers on port ${this.listenPort}`)
  }

  private loadStorageLocations() {
    const addOne = (storeIdRaw: any, dirRaw: any) => {
      const sidSigned = parseInt(String(storeIdRaw ?? "0"), 10)
      if (!Number.isFinite(sidSigned)) return
      const sid = sidSigned >>> 0
      if (sid === 0) return
      const p0 = String(dirRaw ?? "").trim()
      if (!p0) return
      const abs = path.isAbsolute(p0) ? p0 : path.resolve(this.cfgDir, p0)
      this.storageLocations.set(sid, abs)
      fs.mkdirSync(abs, { recursive: true })
    }

    const addFrom = (raw: any) => {
      if (!raw) return
      if (Array.isArray(raw)) {
        for (const e of raw) {
          if (!e || typeof e !== "object") continue
          addOne((e as any).store_id ?? (e as any).storeId, (e as any).path ?? (e as any).dir)
        }
        return
      }
      if (typeof raw === "object") {
        for (const [k, v] of Object.entries(raw)) {
          addOne(k, v)
        }
      }
    }

    addFrom((this.cfg as any).storage_locations)
    addFrom((this.cfg as any).im_storage_locations)
  }

  private persistImFileIfNeeded(msg: any, groupId: string): any {
    const fi = msg?.file_info
    const data: Uint8Array | undefined = fi?.data
    if (!data || data.length === 0) return msg

    const receiverType = String(msg?.receiver_type ?? "")
    const msgId = String(msg?.msg_id ?? "")
    const senderId = String(msg?.sender_id ?? "")
    if (!msgId || !senderId) return msg

    const defaultStoreId = receiverType === "GROUP" ? PeerNodeDaemon.IM_STORE_GROUP_U32 : PeerNodeDaemon.IM_STORE_PRIVATE_U32
    const storeId = fi?.store_id ? (Number(fi.store_id) >>> 0) : defaultStoreId

    let prefix = msgId
    if (storeId === PeerNodeDaemon.IM_STORE_GROUP_U32) {
      if (!groupId) {
        throw new Error("group_id required for IM group storage")
      }
      prefix = `${groupId}/${msgId}`
    } else if (storeId === PeerNodeDaemon.IM_STORE_PRIVATE_U32) {
      prefix = `${senderId}/${msgId}`
    } else if (storeId === PeerNodeDaemon.IM_STORE_PUBLIC_U32) {
      prefix = msgId
    }

    const name = this.safeBaseName(String(fi?.path ?? ""))
    const relPath = `${prefix}/${name}`
    this.writeStoreFile(storeId, relPath, data)

    const md5 = fi?.md5 ? String(fi.md5) : crypto.createHash("md5").update(Buffer.from(data)).digest("hex")
    msg.file_info = { ...fi, store_id: storeId, path: relPath, length: data.length, md5, data: new Uint8Array() }
    return msg
  }

  private handleStoreFileCommands(w: any, sendEncrypted: (seq: number, command: number, data?: Uint8Array) => void): boolean {
    if (w.command === PUT_FILE || w.command === FORCE_PUT_FILE) {
      try {
        const req = this.root.lookupType("p2pws.FileDataModel").decode(w.data ?? new Uint8Array()) as any
        const storeId = Number(req.store_id ?? 0) >>> 0
        const relPath = String(req.path ?? "")
        const data = req.data ? (req.data as Uint8Array) : new Uint8Array()
        if (!storeId || !relPath) {
          sendEncrypted(w.seq, INVALID_DATA, Buffer.from("store_id and path required", "utf-8"))
          return true
        }
        const abs = this.resolveSandboxPath(storeId, relPath)
        if (w.command === PUT_FILE && fs.existsSync(abs)) {
          sendEncrypted(w.seq, STD_ERROR, Buffer.from("file exists", "utf-8"))
          return true
        }
        fs.mkdirSync(path.dirname(abs), { recursive: true })
        fs.writeFileSync(abs, Buffer.from(data))
        sendEncrypted(w.seq, STD_OK, new Uint8Array())
      } catch (e: any) {
        sendEncrypted(w.seq, STD_ERROR, Buffer.from(String(e?.message ?? e), "utf-8"))
      }
      return true
    }

    if (w.command === GET_FILE) {
      try {
        const req = this.root.lookupType("p2pws.FileDataModel").decode(w.data ?? new Uint8Array()) as any
        const storeId = Number(req.store_id ?? 0) >>> 0
        const relPath = String(req.path ?? "")
        if (!storeId || !relPath) {
          sendEncrypted(w.seq, INVALID_DATA, Buffer.from("store_id and path required", "utf-8"))
          return true
        }
        const abs = this.resolveSandboxPath(storeId, relPath)
        if (!fs.existsSync(abs)) {
          sendEncrypted(w.seq, STD_ERROR, Buffer.from("file not found", "utf-8"))
          return true
        }
        const content = fs.readFileSync(abs)
        const resp = this.root.lookupType("p2pws.FileDataModel").encode({ store_id: storeId, length: content.length, data: content, path: relPath, md5: "", block_size: 0 }).finish()
        sendEncrypted(w.seq, OK_GET_FILE, resp)
      } catch (e: any) {
        sendEncrypted(w.seq, STD_ERROR, Buffer.from(String(e?.message ?? e), "utf-8"))
      }
      return true
    }

    if (w.command === GET_FILE_SEGMENTS) {
      try {
        const req = this.root.lookupType("p2pws.FileSegmentsDataModel").decode(w.data ?? new Uint8Array()) as any
        const storeId = Number(req.store_id ?? 0) >>> 0
        const relPath = String(req.path ?? "")
        const start = Number(req.start ?? 0)
        const blockSize = Number(req.block_size ?? 0)
        if (!storeId || !relPath || blockSize <= 0) {
          sendEncrypted(w.seq, INVALID_DATA, Buffer.from("store_id/path/block_size required", "utf-8"))
          return true
        }
        const abs = this.resolveSandboxPath(storeId, relPath)
        if (!fs.existsSync(abs)) {
          sendEncrypted(w.seq, STD_ERROR, Buffer.from("file not found", "utf-8"))
          return true
        }
        const fd = fs.openSync(abs, "r")
        try {
          const st = fs.statSync(abs)
          const len = Number(req.length ?? 0) > 0 ? Number(req.length) : st.size
          const maxRead = Math.min(blockSize, Math.max(0, st.size - start))
          const buf = Buffer.allocUnsafe(maxRead)
          const n = maxRead > 0 ? fs.readSync(fd, buf, 0, maxRead, start) : 0
          const block = n > 0 ? buf.subarray(0, n) : Buffer.alloc(0)
          const blockMd5 = crypto.createHash("md5").update(block).digest("hex")
          const resp = this.root.lookupType("p2pws.FileSegmentsDataModel").encode({
            store_id: storeId,
            length: len,
            start,
            block_index: Number(req.block_index ?? 0),
            block_size: blockSize,
            block_data: block,
            block_md5: blockMd5,
            path: relPath,
            md5: req.md5 ? String(req.md5) : ""
          }).finish()
          sendEncrypted(w.seq, OK_GET_FILE_SEGMENTS, resp)
        } finally {
          fs.closeSync(fd)
        }
      } catch (e: any) {
        sendEncrypted(w.seq, STD_ERROR, Buffer.from(String(e?.message ?? e), "utf-8"))
      }
      return true
    }

    if (w.command === PUT_FILE_SEGMENTS) {
      try {
        const req = this.root.lookupType("p2pws.FileSegmentsDataModel").decode(w.data ?? new Uint8Array()) as any
        const storeId = Number(req.store_id ?? 0) >>> 0
        const relPath = String(req.path ?? "")
        const start = Number(req.start ?? 0)
        const block = req.block_data ? Buffer.from(req.block_data as Uint8Array) : Buffer.alloc(0)
        const expectBlockMd5 = req.block_md5 ? String(req.block_md5) : ""
        if (!storeId || !relPath) {
          sendEncrypted(w.seq, INVALID_DATA, Buffer.from("store_id and path required", "utf-8"))
          return true
        }
        if (expectBlockMd5) {
          const got = crypto.createHash("md5").update(block).digest("hex")
          if (got.toLowerCase() !== expectBlockMd5.toLowerCase()) {
            sendEncrypted(w.seq, INVALID_DATA, Buffer.from("block md5 mismatch", "utf-8"))
            return true
          }
        }
        const abs = this.resolveSandboxPath(storeId, relPath)
        fs.mkdirSync(path.dirname(abs), { recursive: true })
        const fd = fs.openSync(abs, "a+")
        try {
          fs.writeSync(fd, block, 0, block.length, start)
        } finally {
          fs.closeSync(fd)
        }
        sendEncrypted(w.seq, STD_OK, new Uint8Array())
      } catch (e: any) {
        sendEncrypted(w.seq, STD_ERROR, Buffer.from(String(e?.message ?? e), "utf-8"))
      }
      return true
    }

    if (w.command === PUT_FILE_SEGMENTS_COMPLETE) {
      try {
        const req = this.root.lookupType("p2pws.FileSegmentsDataModel").decode(w.data ?? new Uint8Array()) as any
        const storeId = Number(req.store_id ?? 0) >>> 0
        const relPath = String(req.path ?? "")
        if (!storeId || !relPath) {
          sendEncrypted(w.seq, INVALID_DATA, Buffer.from("store_id and path required", "utf-8"))
          return true
        }
        const abs = this.resolveSandboxPath(storeId, relPath)
        const st = fs.statSync(abs)
        const expectLen = Number(req.length ?? 0)
        if (expectLen > 0 && st.size !== expectLen) {
          sendEncrypted(w.seq, INVALID_DATA, Buffer.from(`length mismatch ${expectLen} <> ${st.size}`, "utf-8"))
          return true
        }
        sendEncrypted(w.seq, STD_OK, new Uint8Array())
      } catch (e: any) {
        sendEncrypted(w.seq, STD_ERROR, Buffer.from(String(e?.message ?? e), "utf-8"))
      }
      return true
    }

    if (w.command === INFO_FILE) {
      try {
        const req = this.root.lookupType("p2pws.FileDataModel").decode(w.data ?? new Uint8Array()) as any
        const storeId = Number(req.store_id ?? 0) >>> 0
        const relPath = String(req.path ?? "")
        if (!storeId || !relPath) {
          sendEncrypted(w.seq, INVALID_DATA, Buffer.from("store_id and path required", "utf-8"))
          return true
        }
        const abs = this.resolveSandboxPath(storeId, relPath)
        const st = fs.statSync(abs)
        const resp = this.root.lookupType("p2pws.FileDataModel").encode({ store_id: storeId, length: st.size, data: new Uint8Array(), path: relPath, md5: req.md5 ? String(req.md5) : "", block_size: 8 * 1024 * 1024 }).finish()
        sendEncrypted(w.seq, STD_OK, resp)
      } catch (e: any) {
        sendEncrypted(w.seq, STD_ERROR, Buffer.from(String(e?.message ?? e), "utf-8"))
      }
      return true
    }

    return false
  }

  private writeStoreFile(storeId: number, relPath: string, content: Uint8Array) {
    const abs = this.resolveSandboxPath(storeId, relPath)
    fs.mkdirSync(path.dirname(abs), { recursive: true })
    fs.writeFileSync(abs, Buffer.from(content))
  }

  private resolveSandboxPath(storeId: number, relPath: string): string {
    const base = this.storageLocations.get(storeId >>> 0)
    if (!base) {
      throw new Error(`storage location not configured for store_id=${storeId >>> 0}`)
    }
    const p = String(relPath ?? "").replaceAll("\\", "/")
    const segs = p.split("/").filter((x) => x.length > 0)
    for (const s of segs) {
      if (s === "." || s === "..") {
        throw new Error("invalid path segment")
      }
    }
    const baseAbs = path.resolve(base)
    const full = path.resolve(baseAbs, ...segs)
    const prefix = baseAbs.endsWith(path.sep) ? baseAbs : baseAbs + path.sep
    if (!(full + path.sep).startsWith(prefix)) {
      throw new Error("path escape blocked")
    }
    return full
  }

  private safeBaseName(p: string): string {
    const s = String(p ?? "").trim()
    if (!s) return "file.bin"
    const parts = s.replaceAll("\\", "/").split("/")
    for (let i = parts.length - 1; i >= 0; i--) {
      const seg = String(parts[i] ?? "").trim()
      if (!seg) continue
      if (seg === "." || seg === "..") break
      return seg
    }
    return "file.bin"
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
