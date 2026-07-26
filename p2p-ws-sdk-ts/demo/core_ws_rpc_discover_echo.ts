import path from "node:path"

import { CoreWsClient } from "../src/core_compat/CoreWsClient.js"

async function main(): Promise<void> {
  const wsUrl = process.argv[2] ?? "ws://127.0.0.1:18089/p2p"
  const magic = process.argv[3] ? Number(process.argv[3]) : -252702961
  const privateKeyPemPath = process.argv[4]
  const userId = process.argv[5] ?? "example-user-id"
  const msg = process.argv[6] ?? "hello from ts core_compat"

  if (!privateKeyPemPath) throw new Error("need client private key pem path argv4")

  const absKey = path.resolve(process.cwd(), privateKeyPemPath)
  const c = new CoreWsClient({ wsUrl, magic })
  await c.connect()
  try {
    await c.handshakeAndLogin(userId, absKey)
    const disc = await c.rpcDiscover("", true)
    const services = Array.isArray(disc.services) ? disc.services : []
    console.log("discover.services=", services.length)
    for (const svc of services.slice(0, 10)) {
      const methods = Array.isArray(svc.methods) ? svc.methods : []
      console.log("  -", svc.service, "version=", svc.version, "methods=", methods.length)
    }

    const health = await c.rpcHealth("p2p.rpc.echo.v1.EchoService")
    console.log("health.healthy=", health.healthy, "ready=", health.ready, "message=", health.message)

    const echo = await c.rpcEcho(msg)
    console.log("echo.message=", echo.message, "server_time=", echo.server_time)
  } finally {
    c.close()
  }
}

main().catch((e) => {
  console.error(e)
  process.exit(1)
})

