import { decodeStreamP2PWrapper, encodeStreamP2PWrapper } from "../src/core_compat/protostuff.js"

const bytes = encodeStreamP2PWrapper({
  seq: 7,
  commandOrdinal: 99,
  data: new Uint8Array([1, 2, 3]),
  index: 5,
  completed: true,
  canceled: false,
})

const back = decodeStreamP2PWrapper(bytes)
if (back.seq !== 7) throw new Error("seq mismatch")
if (back.commandOrdinal !== 99) throw new Error("command mismatch")
if (back.index !== 5) throw new Error("index mismatch")
if (!back.completed) throw new Error("completed mismatch")
if (back.canceled) throw new Error("canceled mismatch")
if (Buffer.from(back.data).toString("hex") !== "010203") throw new Error("data mismatch")

console.log("ok=1")
