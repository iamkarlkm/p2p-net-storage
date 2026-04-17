import crypto from "node:crypto"
import protobuf from "protobufjs"

export function rsaOaepSha256Decrypt(privateKeyPem: string | Buffer, cipher: Uint8Array): Uint8Array {
  const plain = crypto.privateDecrypt(
    {
      key: privateKeyPem,
      padding: crypto.constants.RSA_PKCS1_OAEP_PADDING,
      oaepHash: "sha256",
    },
    Buffer.from(cipher),
  )
  return new Uint8Array(plain)
}

export function rsaOaepSha256Encrypt(publicKeyPem: string | Buffer, plain: Uint8Array): Uint8Array {
  const cipher = crypto.publicEncrypt(
    {
      key: publicKeyPem,
      padding: crypto.constants.RSA_PKCS1_OAEP_PADDING,
      oaepHash: "sha256",
    },
    Buffer.from(plain),
  )
  return new Uint8Array(cipher)
}

export function decodeHandAckPlain(root: protobuf.Root, plain: Uint8Array): { [k: string]: unknown } {
  const T = root.lookupType("p2pws.HandAckPlain")
  return T.decode(plain) as unknown as { [k: string]: unknown }
}

