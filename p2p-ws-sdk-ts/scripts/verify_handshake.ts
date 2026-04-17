import crypto from "node:crypto"
import { loadProtoRoot } from "../src/proto.js"
import { decodeHandAckPlain, rsaOaepSha256Decrypt, rsaOaepSha256Encrypt } from "../src/handshake.js"

const { publicKey, privateKey } = crypto.generateKeyPairSync("rsa", { modulusLength: 2048 })

const root = await loadProtoRoot()
const T = root.lookupType("p2pws.HandAckPlain")

const sessionId = Buffer.from(Array.from({ length: 16 }, (_, i) => i))
const keyId = Buffer.from(Array.from({ length: 32 }, (_, i) => 255 - i))

const msg = T.create({
  session_id: sessionId,
  selected_key_id: keyId,
  offset: 1234,
  max_frame_payload: 4 * 1024 * 1024,
  header_policy_id: 0,
})

const plain = T.encode(msg).finish()
const cipher = rsaOaepSha256Encrypt(publicKey.export({ type: "pkcs1", format: "pem" }) as string, plain)
const back = rsaOaepSha256Decrypt(privateKey.export({ type: "pkcs1", format: "pem" }) as string, cipher)
const decoded = decodeHandAckPlain(root, back)

const sidHex = Buffer.from(decoded["session_id"] as Uint8Array).toString("hex")
if (sidHex !== sessionId.toString("hex")) throw new Error("session_id mismatch")

const kidHex = Buffer.from(decoded["selected_key_id"] as Uint8Array).toString("hex")
if (kidHex !== keyId.toString("hex")) throw new Error("selected_key_id mismatch")

if (Number(decoded["offset"]) !== 1234) throw new Error("offset mismatch")
if (Number(decoded["max_frame_payload"]) !== 4 * 1024 * 1024) throw new Error("max_frame_payload mismatch")

console.log("ok=1")

