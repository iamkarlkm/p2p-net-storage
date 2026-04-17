#!/usr/bin/env node
import path from "node:path"
import { loadClientConfig } from "../src/config.js"
import { PeerNodeDaemon } from "../src/PeerNode.js"

const cfgPath = process.argv[2]
if (!cfgPath) {
  console.error("Usage: npx tsx bin/p2pd.ts <config.yaml>")
  process.exit(1)
}

const cfg = loadClientConfig(cfgPath)
const daemon = new PeerNodeDaemon(cfg, path.dirname(path.resolve(cfgPath)))

daemon.start().catch(e => {
  console.error(e)
  process.exit(1)
})

process.on("SIGINT", () => {
  console.log("Shutting down daemon...")
  daemon.stop()
  process.exit(0)
})
