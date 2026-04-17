import { loadProtoRoot } from "../src/proto.js"
import { decodeWrapper, encodeWrapper } from "../src/wrapper.js"

const root = await loadProtoRoot()
const plain = encodeWrapper(root, { seq: 1, command: 123, data: new Uint8Array([1, 2, 3]) })
const back = decodeWrapper(root, plain)

if (back.seq !== 1) throw new Error("seq mismatch")
if (back.command !== 123) throw new Error("command mismatch")
if (!back.data || Buffer.from(back.data).toString("hex") !== "010203") throw new Error("data mismatch")

console.log("ok=1")

