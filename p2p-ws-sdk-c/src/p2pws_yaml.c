#include "p2pws_yaml.h"

#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static void trim(char* s) {
  if (!s) return;
  size_t n = strlen(s);
  while (n && (s[n - 1] == '\r' || s[n - 1] == '\n' || isspace((unsigned char)s[n - 1]))) {
    s[n - 1] = 0;
    n--;
  }
  char* p = s;
  while (*p && isspace((unsigned char)*p)) p++;
  if (p != s) memmove(s, p, strlen(p) + 1);
}

static void strip_comment(char* s) {
  if (!s) return;
  char* p = strchr(s, '#');
  if (p) *p = 0;
}

static int starts_with(const char* s, const char* pref) {
  return strncmp(s, pref, strlen(pref)) == 0;
}

static uint32_t parse_u32_auto(const char* s, uint32_t defv) {
  if (!s) return defv;
  while (*s && isspace((unsigned char)*s)) s++;
  if (!*s) return defv;
  if (starts_with(s, "0x") || starts_with(s, "0X")) return (uint32_t)strtoul(s + 2, NULL, 16);
  return (uint32_t)strtoul(s, NULL, 10);
}

static void set_str(char* dst, size_t cap, const char* v) {
  if (!dst || cap == 0) return;
  if (!v) v = "";
  size_t n = strlen(v);
  if (n >= cap) n = cap - 1;
  memcpy(dst, v, n);
  dst[n] = 0;
}

static int split_kv(const char* line, char* k, size_t kcap, char* v, size_t vcap) {
  const char* colon = strchr(line, ':');
  if (!colon) return -1;
  size_t kn = (size_t)(colon - line);
  if (kn >= kcap) kn = kcap - 1;
  memcpy(k, line, kn);
  k[kn] = 0;
  set_str(v, vcap, colon + 1);
  trim(k);
  trim(v);
  return 0;
}

int p2pws_cfg_load_yaml(const char* path, p2pws_cfg_t* out) {
  if (!path || !out) return -1;
  memset(out, 0, sizeof(*out));
  out->magic = 0x1234;
  out->version = 1;
  out->flags_plain = 4;
  out->flags_encrypted = 5;
  out->max_frame_payload = 4u * 1024u * 1024u;
  out->listen_port = 0;
  out->renew_seconds = 0;
  set_str(out->crypto_mode, sizeof(out->crypto_mode), "KEYFILE_XOR_RSA_OAEP");

  FILE* f = fopen(path, "rb");
  if (!f) return -2;

  char line[1024];
  int in_reported = 0;
  int got_report_item = 0;
  while (fgets(line, sizeof(line), f)) {
    strip_comment(line);
    trim(line);
    if (!*line) continue;

    if (starts_with(line, "reported_endpoints")) {
      in_reported = 1;
      got_report_item = 0;
      continue;
    }
    if (in_reported) {
      if (line[0] != '-' && strchr(line, ':') == NULL) {
        continue;
      }
      if (starts_with(line, "-")) {
        got_report_item = 1;
        char* p = line + 1;
        trim(p);
        if (*p && strchr(p, ':')) {
          char k[128], v[512];
          if (split_kv(p, k, sizeof(k), v, sizeof(v)) == 0) {
            if (strcmp(k, "transport") == 0) set_str(out->reported_transport, sizeof(out->reported_transport), v);
            if (strcmp(k, "addr") == 0) set_str(out->reported_addr, sizeof(out->reported_addr), v);
          }
        }
        continue;
      }
      if (got_report_item && strchr(line, ':')) {
        char k[128], v[512];
        if (split_kv(line, k, sizeof(k), v, sizeof(v)) == 0) {
          if (strcmp(k, "transport") == 0) set_str(out->reported_transport, sizeof(out->reported_transport), v);
          if (strcmp(k, "addr") == 0) set_str(out->reported_addr, sizeof(out->reported_addr), v);
        }
        continue;
      }
      continue;
    }

    if (strchr(line, ':')) {
      char k[128], v[768];
      if (split_kv(line, k, sizeof(k), v, sizeof(v)) != 0) continue;
      if (strcmp(k, "user_id") == 0) set_str(out->user_id, sizeof(out->user_id), v);
      else if (strcmp(k, "ws_url") == 0) set_str(out->ws_url, sizeof(out->ws_url), v);
      else if (strcmp(k, "keyfile_path") == 0) set_str(out->keyfile_path, sizeof(out->keyfile_path), v);
      else if (strcmp(k, "key_id_sha256_hex") == 0) set_str(out->key_id_sha256_hex, sizeof(out->key_id_sha256_hex), v);
      else if (strcmp(k, "rsa_private_key_pem_path") == 0) set_str(out->rsa_private_key_pem_path, sizeof(out->rsa_private_key_pem_path), v);
      else if (strcmp(k, "pubkey_spki_der_base64") == 0) set_str(out->pubkey_spki_der_base64, sizeof(out->pubkey_spki_der_base64), v);
      else if (strcmp(k, "crypto_mode") == 0) set_str(out->crypto_mode, sizeof(out->crypto_mode), v);
      else if (strcmp(k, "listen_port") == 0) out->listen_port = parse_u32_auto(v, out->listen_port);
      else if (strcmp(k, "renew_seconds") == 0) out->renew_seconds = parse_u32_auto(v, out->renew_seconds);
      else if (strcmp(k, "magic") == 0) out->magic = parse_u32_auto(v, out->magic);
      else if (strcmp(k, "version") == 0) out->version = parse_u32_auto(v, out->version);
      else if (strcmp(k, "flags_plain") == 0) out->flags_plain = parse_u32_auto(v, out->flags_plain);
      else if (strcmp(k, "flags_encrypted") == 0) out->flags_encrypted = parse_u32_auto(v, out->flags_encrypted);
      else if (strcmp(k, "max_frame_payload") == 0) out->max_frame_payload = parse_u32_auto(v, out->max_frame_payload);
    }
  }
  fclose(f);

  if (!out->user_id[0]) return -3;
  if (!out->ws_url[0]) return -4;
  if (!out->keyfile_path[0]) return -5;
  if (!out->rsa_private_key_pem_path[0]) return -6;
  if (!out->pubkey_spki_der_base64[0]) return -7;
  if (!out->reported_transport[0]) set_str(out->reported_transport, sizeof(out->reported_transport), "ws");

  return 0;
}
