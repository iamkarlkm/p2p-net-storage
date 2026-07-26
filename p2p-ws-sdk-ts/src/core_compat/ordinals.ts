import fs from "node:fs"
import path from "node:path"

let cached: Map<string, number> | null = null

export function loadCommandOrdinals(): Map<string, number> {
  if (cached) return cached
  const root = path.resolve(import.meta.dirname, "..", "..", "..", "..")
  const jsonPath = path.resolve(root, "p2p-ws-protocol", "generated", "p2p_command_ordinals.json")
  const raw = JSON.parse(fs.readFileSync(jsonPath, "utf-8")) as { names?: unknown }
  const names = Array.isArray(raw.names) ? (raw.names as unknown[]) : []
  const m = new Map<string, number>()
  for (let i = 0; i < names.length; i++) {
    const n = names[i]
    if (typeof n === "string") m.set(n, i)
  }
  cached = m
  return m
}

