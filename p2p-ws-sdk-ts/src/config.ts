import fs from "node:fs"
import path from "node:path"
import YAML from "yaml"

export type ClientConfig = {
  user_id: string
  ws_url: string
  keyfile_path: string
  key_id_sha256_hex?: string
  rsa_private_key_pem_path?: string
  crypto_mode?: string
  reported_endpoints?: Array<{ transport: string; addr: string }>
  presence_cache_path?: string
  cooldown_cache_path?: string
  enable_connect_hint?: boolean
  renew_seconds?: number
  renew_count?: number
  magic?: number | string
  version?: number
  flags_plain?: number
  flags_encrypted?: number
  max_frame_payload?: number
  listen_port?: number | string
}

export function loadClientConfig(p: string): ClientConfig {
  const abs = path.resolve(p)
  const raw = fs.readFileSync(abs, "utf-8")
  const cfg0 = YAML.parse(raw) as any
  const cfg: ClientConfig = {
    user_id: cfg0?.user_id == null ? "" : String(cfg0.user_id),
    ws_url: cfg0?.ws_url == null ? "" : String(cfg0.ws_url),
    keyfile_path: cfg0?.keyfile_path == null ? "" : String(cfg0.keyfile_path),
    key_id_sha256_hex: cfg0?.key_id_sha256_hex == null ? undefined : String(cfg0.key_id_sha256_hex),
    rsa_private_key_pem_path: cfg0?.rsa_private_key_pem_path == null ? undefined : String(cfg0.rsa_private_key_pem_path),
    crypto_mode: cfg0?.crypto_mode == null ? undefined : String(cfg0.crypto_mode),
    reported_endpoints: cfg0?.reported_endpoints,
    presence_cache_path: cfg0?.presence_cache_path == null ? undefined : String(cfg0.presence_cache_path),
    cooldown_cache_path: cfg0?.cooldown_cache_path == null ? undefined : String(cfg0.cooldown_cache_path),
    enable_connect_hint: cfg0?.enable_connect_hint,
    renew_seconds: cfg0?.renew_seconds,
    renew_count: cfg0?.renew_count,
    magic: cfg0?.magic,
    version: cfg0?.version,
    flags_plain: cfg0?.flags_plain,
    flags_encrypted: cfg0?.flags_encrypted,
    max_frame_payload: cfg0?.max_frame_payload,
    listen_port: cfg0?.listen_port,
  }
  if (!cfg || typeof cfg !== "object") throw new Error("invalid yaml")
  if (!cfg.user_id) throw new Error("user_id required")
  if (!cfg.ws_url) throw new Error("ws_url required")
  if (!cfg.keyfile_path) throw new Error("keyfile_path required")
  return cfg
}

export function parseIntMaybeHex(v: number | string | undefined, def: number): number {
  if (v === undefined || v === null) return def
  if (typeof v === "number") return v | 0
  const s = String(v).trim()
  if (s.startsWith("0x") || s.startsWith("0X")) return parseInt(s.slice(2), 16) | 0
  return parseInt(s, 10) | 0
}
