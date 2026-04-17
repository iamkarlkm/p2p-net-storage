import crypto from "node:crypto"

export function sha256Bytes(data: Uint8Array): Uint8Array {
  const h = crypto.createHash("sha256")
  h.update(data)
  return new Uint8Array(h.digest())
}

export function sha256Hex(data: Uint8Array): string {
  return Buffer.from(sha256Bytes(data)).toString("hex")
}

