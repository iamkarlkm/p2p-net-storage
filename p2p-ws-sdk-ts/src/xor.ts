export function xorNoWrap(plain: Uint8Array, keyfile: Uint8Array, offset: number): Uint8Array {
  if (offset < 0 || offset >= keyfile.length) {
    throw new Error("offset out of range")
  }
  if (offset + plain.length > keyfile.length) {
    throw new Error("offset+plainLen exceeds keyLen (no wrap)")
  }
  const out = new Uint8Array(plain.length)
  for (let i = 0; i < plain.length; i++) {
    out[i] = plain[i] ^ keyfile[offset + i]
  }
  return out
}

