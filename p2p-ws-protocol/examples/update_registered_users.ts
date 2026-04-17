import fs from "node:fs"
import path from "node:path"
import crypto from "node:crypto"

const users = [
  { id: 1, keyPath: "keys/node1.pem" },
  { id: 2, keyPath: "keys/node2.pem" }
]

const result = { users: [] as any[] }

for (const u of users) {
  const p = path.resolve(u.keyPath)
  const pem = fs.readFileSync(p, "utf-8")
  const priv = crypto.createPrivateKey(pem)
  const pub = crypto.createPublicKey(priv)
  const der = pub.export({ type: "spki", format: "der" }) as Buffer
  const nodeKey = crypto.createHash("sha256").update(der).digest("hex")
  const b64 = der.toString("base64")
  
  result.users.push({
    node_id64: u.id,
    node_key_hex: nodeKey,
    pubkey_spki_der_base64: b64,
    enabled: true,
    allowed_crypto_modes: ["KEYFILE_XOR_RSA_OAEP"]
  })
}

const yaml = `users:
${result.users.map(u => `  - node_id64: ${u.node_id64}
    node_key_hex: ${u.node_key_hex}
    pubkey_spki_der_base64: ${u.pubkey_spki_der_base64}
    enabled: true
    allowed_crypto_modes:
      - KEYFILE_XOR_RSA_OAEP`).join("\n")}
`

fs.writeFileSync("registered_users.yaml", yaml)
console.log("Updated registered_users.yaml")
