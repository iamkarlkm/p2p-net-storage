#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <bcrypt.h>

#include "p2p_ws.h"
#include "p2pws_buf.h"
#include "p2pws_crypto.h"
#include "p2pws_fs.h"
#include "p2pws_messages.h"
#include "p2pws_pb.h"
#include "p2pws_ws.h"
#include "p2pws_yaml.h"

static void dirname_of(const char* path, char* out, size_t cap) {
  if (!out || cap == 0) return;
  out[0] = 0;
  if (!path) return;
  const char* last1 = strrchr(path, '/');
  const char* last2 = strrchr(path, '\\');
  const char* last = last1;
  if (!last || (last2 && last2 > last)) last = last2;
  if (!last) {
    strncpy(out, ".", cap - 1);
    out[cap - 1] = 0;
    return;
  }
  size_t n = (size_t)(last - path);
  if (n >= cap) n = cap - 1;
  memcpy(out, path, n);
  out[n] = 0;
}

static int is_abs_path(const char* p) {
  if (!p || !p[0]) return 0;
  if ((p[0] >= 'A' && p[0] <= 'Z') || (p[0] >= 'a' && p[0] <= 'z')) {
    if (p[1] == ':' && (p[2] == '\\' || p[2] == '/')) return 1;
  }
  if (p[0] == '\\' || p[0] == '/') return 1;
  return 0;
}

static void join_path(const char* base, const char* rel, char* out, size_t cap) {
  if (!out || cap == 0) return;
  out[0] = 0;
  if (!rel) rel = "";
  if (is_abs_path(rel)) {
    strncpy(out, rel, cap - 1);
    out[cap - 1] = 0;
    return;
  }
  if (!base || !base[0]) base = ".";
  size_t bn = strlen(base);
  size_t rn = strlen(rel);
  if (bn + 1 + rn + 1 > cap) {
    strncpy(out, rel, cap - 1);
    out[cap - 1] = 0;
    return;
  }
  memcpy(out, base, bn);
  if (bn && base[bn - 1] != '\\' && base[bn - 1] != '/') {
    out[bn] = '\\';
    memcpy(out + bn + 1, rel, rn);
    out[bn + 1 + rn] = 0;
  } else {
    memcpy(out + bn, rel, rn);
    out[bn + rn] = 0;
  }
}

static uint64_t now_ms(void) {
  return (uint64_t)time(NULL) * 1000ULL;
}

static int rand_bytes(uint8_t* out, size_t n) {
  if (!out || !n) return -1;
  if (BCryptGenRandom(NULL, out, (ULONG)n, BCRYPT_USE_SYSTEM_PREFERRED_RNG) != 0) return -2;
  return 0;
}

static int send_wire_frame_server(p2pws_ws_client_t* ws, uint32_t magic, uint8_t version, uint8_t flags, const uint8_t* payload, size_t payload_len) {
  uint8_t hdr8[8];
  p2pws_header_t h;
  h.length = (uint32_t)payload_len;
  h.magic = (uint16_t)magic;
  h.version = version;
  h.flags = flags;
  if (p2pws_encode_header_be(&h, hdr8) != 0) return -1;
  p2pws_buf_t out;
  p2pws_buf_init(&out);
  if (p2pws_buf_reserve(&out, 8 + payload_len) != 0) return -2;
  p2pws_buf_append(&out, hdr8, 8);
  p2pws_buf_append(&out, payload, payload_len);
  int r = p2pws_ws_send_binary_unmasked(ws, out.data, out.len);
  p2pws_buf_free(&out);
  return r;
}

static int send_wire_frame_client(p2pws_ws_client_t* ws, uint32_t magic, uint8_t version, uint8_t flags, const uint8_t* payload, size_t payload_len) {
  uint8_t hdr8[8];
  p2pws_header_t h;
  h.length = (uint32_t)payload_len;
  h.magic = (uint16_t)magic;
  h.version = version;
  h.flags = flags;
  if (p2pws_encode_header_be(&h, hdr8) != 0) return -1;
  p2pws_buf_t out;
  p2pws_buf_init(&out);
  if (p2pws_buf_reserve(&out, 8 + payload_len) != 0) return -2;
  p2pws_buf_append(&out, hdr8, 8);
  p2pws_buf_append(&out, payload, payload_len);
  int r = p2pws_ws_send_binary(ws, out.data, out.len);
  p2pws_buf_free(&out);
  return r;
}

static int recv_wire_frame(p2pws_ws_client_t* ws, p2pws_buf_t* out_payload, p2pws_header_t* out_h) {
  p2pws_buf_t raw;
  p2pws_buf_init(&raw);
  p2pws_buf_reserve(&raw, 4096);
  int r = p2pws_ws_recv_binary(ws, &raw);
  if (r != 0) {
    p2pws_buf_free(&raw);
    return r;
  }
  if (raw.len < 8) {
    p2pws_buf_free(&raw);
    return -2;
  }
  if (p2pws_decode_header_be(raw.data, out_h) != 0) {
    p2pws_buf_free(&raw);
    return -3;
  }
  if ((size_t)out_h->length + 8 != raw.len) {
    p2pws_buf_free(&raw);
    return -4;
  }
  out_payload->len = 0;
  p2pws_buf_reserve(out_payload, (size_t)out_h->length + 1);
  memcpy(out_payload->data, raw.data + 8, out_h->length);
  out_payload->len = out_h->length;
  p2pws_buf_free(&raw);
  return 0;
}

static void hex32(const uint8_t* b32, char out65[65]) {
  static const char* h = "0123456789abcdef";
  for (int i = 0; i < 32; i++) {
    out65[i * 2] = h[(b32[i] >> 4) & 0xF];
    out65[i * 2 + 1] = h[b32[i] & 0xF];
  }
  out65[64] = 0;
}

static void handle_client(p2pws_ws_client_t* c, const p2pws_cfg_t* cfg, const p2pws_keyfile_t* kf, const uint8_t key_id32[32], const p2pws_rsa_t* self_rsa, const char* storage_dir) {
  p2pws_buf_t plain;
  p2pws_buf_t cipher;
  p2pws_buf_t tmp;
  p2pws_buf_t wrap;
  p2pws_buf_init(&plain);
  p2pws_buf_init(&cipher);
  p2pws_buf_init(&tmp);
  p2pws_buf_init(&wrap);
  p2pws_buf_reserve(&plain, 4 * 1024 * 1024);
  p2pws_buf_reserve(&cipher, 4 * 1024 * 1024);
  p2pws_buf_reserve(&tmp, 4 * 1024 * 1024);
  p2pws_buf_reserve(&wrap, 4 * 1024 * 1024);

  int stage = 0;
  uint32_t offset = 0;

  for (;;) {
    p2pws_header_t h;
    int rr = recv_wire_frame(c, &cipher, &h);
    if (rr != 0) break;

    const uint8_t* wbytes = cipher.data;
    size_t wlen = cipher.len;
    if (stage >= 1) {
      plain.len = 0;
      p2pws_buf_reserve(&plain, cipher.len);
      if (p2pws_xor_no_wrap(cipher.data, cipher.len, kf->data, kf->len, offset, plain.data) != 0) break;
      plain.len = cipher.len;
      wbytes = plain.data;
      wlen = plain.len;
    }

    p2pws_wrapper_view_t wv;
    if (p2pws_pb_decode_wrapper(wbytes, wlen, &wv) != 0) break;

    if (stage == 0) {
      if (wv.command != -10001) break;
      p2pws_hand_view_t hv;
      if (p2pws_pb_decode_hand(wv.data.p, wv.data.n, &hv) != 0) break;
      if (hv.key_id.n != 32 || memcmp(hv.key_id.p, key_id32, 32) != 0) break;

      uint8_t session_id[16];
      if (rand_bytes(session_id, sizeof(session_id)) != 0) break;
      uint8_t offb[4];
      if (rand_bytes(offb, sizeof(offb)) != 0) break;
      offset = ((uint32_t)offb[0] << 24) | ((uint32_t)offb[1] << 16) | ((uint32_t)offb[2] << 8) | (uint32_t)offb[3];
      offset = offset % 1024;

      if (p2pws_msg_encode_hand_ack_plain(session_id, key_id32, offset, cfg->max_frame_payload, 0, &tmp) != 0) break;
      if (p2pws_rsa_oaep_sha256_encrypt_spki_der(hv.client_pubkey.p, hv.client_pubkey.n, tmp.data, tmp.len, &cipher) != 0) break;
      if (p2pws_msg_encode_wrapper(wv.seq, -10002, cipher.data, cipher.len, &wrap) != 0) break;
      if (send_wire_frame_server(c, cfg->magic, (uint8_t)cfg->version, (uint8_t)cfg->flags_plain, wrap.data, wrap.len) != 0) break;

      stage = 1;
      continue;
    }

    if (wv.command == -12001) {
      p2pws_peer_hello_view_t ph;
      if (p2pws_pb_decode_peer_hello(wv.data.p, wv.data.n, &ph) != 0) break;
      p2pws_peer_hello_body_view_t pb;
      if (p2pws_pb_decode_peer_hello_body(ph.body.p, ph.body.n, &pb) != 0) break;

      if (p2pws_msg_encode_peer_hello_ack(self_rsa->node_key32, now_ms(), &tmp) != 0) break;
      if (p2pws_msg_encode_wrapper(wv.seq, -12002, tmp.data, tmp.len, &wrap) != 0) break;
      cipher.len = 0;
      p2pws_buf_reserve(&cipher, wrap.len);
      if (p2pws_xor_no_wrap(wrap.data, wrap.len, kf->data, kf->len, offset, cipher.data) != 0) break;
      cipher.len = wrap.len;
      if (send_wire_frame_server(c, cfg->magic, (uint8_t)cfg->version, (uint8_t)cfg->flags_encrypted, cipher.data, cipher.len) != 0) break;

      stage = 2;
      (void)pb;
      continue;
    }

    if (wv.command == 1001 && stage >= 2) {
      p2pws_file_put_req_view_t fr;
      if (p2pws_pb_decode_file_put_req(wv.data.p, wv.data.n, &fr) != 0) break;
      if (fr.file_hash_sha256.n != 32) break;
      char hh[65];
      hex32(fr.file_hash_sha256.p, hh);
      char fp[1024];
      snprintf(fp, sizeof(fp), "%s\\%s", storage_dir, hh);
      p2pws_file_write_all(fp, fr.content.p, fr.content.n);

      if (p2pws_msg_encode_file_put_resp(1, fr.file_hash_sha256.p, &tmp) != 0) break;
      if (p2pws_msg_encode_wrapper(wv.seq, 1002, tmp.data, tmp.len, &wrap) != 0) break;
      cipher.len = 0;
      p2pws_buf_reserve(&cipher, wrap.len);
      p2pws_xor_no_wrap(wrap.data, wrap.len, kf->data, kf->len, offset, cipher.data);
      cipher.len = wrap.len;
      if (send_wire_frame_server(c, cfg->magic, (uint8_t)cfg->version, (uint8_t)cfg->flags_encrypted, cipher.data, cipher.len) != 0) break;
      continue;
    }

    if (wv.command == 1003 && stage >= 2) {
      p2pws_file_get_req_view_t gr;
      if (p2pws_pb_decode_file_get_req(wv.data.p, wv.data.n, &gr) != 0) break;
      if (gr.file_hash_sha256.n != 32) break;
      char hh[65];
      hex32(gr.file_hash_sha256.p, hh);
      char fp[1024];
      snprintf(fp, sizeof(fp), "%s\\%s", storage_dir, hh);
      void* file_data = NULL;
      size_t file_len = 0;
      int found = (p2pws_file_read_all(fp, &file_data, &file_len) == 0) ? 1 : 0;

      if (p2pws_msg_encode_file_get_resp(found, (const uint8_t*)file_data, file_len, &tmp) != 0) {
        free(file_data);
        break;
      }
      free(file_data);
      if (p2pws_msg_encode_wrapper(wv.seq, 1004, tmp.data, tmp.len, &wrap) != 0) break;
      cipher.len = 0;
      p2pws_buf_reserve(&cipher, wrap.len);
      p2pws_xor_no_wrap(wrap.data, wrap.len, kf->data, kf->len, offset, cipher.data);
      cipher.len = wrap.len;
      if (send_wire_frame_server(c, cfg->magic, (uint8_t)cfg->version, (uint8_t)cfg->flags_encrypted, cipher.data, cipher.len) != 0) break;
      continue;
    }
  }

  p2pws_buf_free(&plain);
  p2pws_buf_free(&cipher);
  p2pws_buf_free(&tmp);
  p2pws_buf_free(&wrap);
  p2pws_ws_close(c);
}

typedef struct {
  const p2pws_cfg_t* cfg;
  const p2pws_keyfile_t* kf;
  uint8_t key_id32[32];
  p2pws_rsa_t* rsa;
  char storage_dir[512];
} center_thread_args_t;

typedef struct {
  p2pws_ws_client_t ws;
  uint32_t offset;
  const p2pws_cfg_t* cfg;
  const p2pws_keyfile_t* kf;
  p2pws_rsa_t* rsa;
  uint64_t node_id64;
  char storage_dir[512];
  volatile LONG stop;
} center_conn_t;

static DWORD WINAPI center_recv_thread_func(LPVOID lpParam) {
  center_conn_t* conn = (center_conn_t*)lpParam;
  p2pws_buf_t cipher;
  p2pws_buf_t plain;
  p2pws_buf_t tmp;
  p2pws_buf_t wrap;
  p2pws_buf_t rd_bytes;
  p2pws_buf_init(&cipher);
  p2pws_buf_init(&plain);
  p2pws_buf_init(&tmp);
  p2pws_buf_init(&wrap);
  p2pws_buf_init(&rd_bytes);
  p2pws_buf_reserve(&cipher, 4 * 1024 * 1024);
  p2pws_buf_reserve(&plain, 4 * 1024 * 1024);
  p2pws_buf_reserve(&tmp, 4 * 1024 * 1024);
  p2pws_buf_reserve(&wrap, 4 * 1024 * 1024);
  p2pws_buf_reserve(&rd_bytes, 4 * 1024 * 1024);

  for (;;) {
    if (conn->stop) break;
    p2pws_header_t h;
    cipher.len = 0;
    int rr = recv_wire_frame(&conn->ws, &cipher, &h);
    if (rr != 0) break;

    plain.len = 0;
    p2pws_buf_reserve(&plain, cipher.len);
    if (p2pws_xor_no_wrap(cipher.data, cipher.len, conn->kf->data, conn->kf->len, conn->offset, plain.data) != 0) break;
    plain.len = cipher.len;

    p2pws_wrapper_view_t wv;
    if (p2pws_pb_decode_wrapper(plain.data, plain.len, &wv) != 0) continue;

    if (wv.command != -11012) continue;

    p2pws_relay_data_view_t rd;
    if (p2pws_pb_decode_relay_data(wv.data.p, wv.data.n, &rd) != 0) continue;
    if (rd.target_node_key.n != 32 || memcmp(rd.target_node_key.p, conn->rsa->node_key32, 32) != 0) continue;
    if (rd.payload.n == 0) continue;

    p2pws_wrapper_view_t inner;
    if (p2pws_pb_decode_wrapper(rd.payload.p, rd.payload.n, &inner) != 0) continue;

    if (inner.command == 1001) {
      p2pws_file_put_req_view_t fr;
      if (p2pws_pb_decode_file_put_req(inner.data.p, inner.data.n, &fr) != 0) continue;
      if (fr.file_hash_sha256.n != 32) continue;
      char hh[65];
      hex32(fr.file_hash_sha256.p, hh);
      char fp[1024];
      snprintf(fp, sizeof(fp), "%s\\%s", conn->storage_dir, hh);
      p2pws_file_write_all(fp, fr.content.p, fr.content.n);

      tmp.len = 0;
      if (p2pws_msg_encode_file_put_resp(1, fr.file_hash_sha256.p, &tmp) != 0) continue;
      wrap.len = 0;
      if (p2pws_msg_encode_wrapper(inner.seq, 1002, tmp.data, tmp.len, &wrap) != 0) continue;

      rd_bytes.len = 0;
      if (p2pws_msg_encode_relay_data(rd.source_node_id64, rd.source_node_key.p, rd.source_node_key.n, conn->node_id64, conn->rsa->node_key32, 32, wrap.data, wrap.len, &rd_bytes) != 0) continue;
      wrap.len = 0;
      if (p2pws_msg_encode_wrapper(wv.seq, -11012, rd_bytes.data, rd_bytes.len, &wrap) != 0) continue;

      cipher.len = 0;
      p2pws_buf_reserve(&cipher, wrap.len);
      if (p2pws_xor_no_wrap(wrap.data, wrap.len, conn->kf->data, conn->kf->len, conn->offset, cipher.data) != 0) continue;
      cipher.len = wrap.len;
      (void)send_wire_frame_client(&conn->ws, conn->cfg->magic, (uint8_t)conn->cfg->version, (uint8_t)conn->cfg->flags_encrypted, cipher.data, cipher.len);
      continue;
    }

    if (inner.command == 1003) {
      p2pws_file_get_req_view_t gr;
      if (p2pws_pb_decode_file_get_req(inner.data.p, inner.data.n, &gr) != 0) continue;
      if (gr.file_hash_sha256.n != 32) continue;
      char hh[65];
      hex32(gr.file_hash_sha256.p, hh);
      char fp[1024];
      snprintf(fp, sizeof(fp), "%s\\%s", conn->storage_dir, hh);
      void* file_data = NULL;
      size_t file_len = 0;
      int found = (p2pws_file_read_all(fp, &file_data, &file_len) == 0) ? 1 : 0;

      tmp.len = 0;
      if (p2pws_msg_encode_file_get_resp(found, (const uint8_t*)file_data, file_len, &tmp) != 0) {
        free(file_data);
        continue;
      }
      free(file_data);
      wrap.len = 0;
      if (p2pws_msg_encode_wrapper(inner.seq, 1004, tmp.data, tmp.len, &wrap) != 0) continue;

      rd_bytes.len = 0;
      if (p2pws_msg_encode_relay_data(rd.source_node_id64, rd.source_node_key.p, rd.source_node_key.n, conn->node_id64, conn->rsa->node_key32, 32, wrap.data, wrap.len, &rd_bytes) != 0) continue;
      wrap.len = 0;
      if (p2pws_msg_encode_wrapper(wv.seq, -11012, rd_bytes.data, rd_bytes.len, &wrap) != 0) continue;

      cipher.len = 0;
      p2pws_buf_reserve(&cipher, wrap.len);
      if (p2pws_xor_no_wrap(wrap.data, wrap.len, conn->kf->data, conn->kf->len, conn->offset, cipher.data) != 0) continue;
      cipher.len = wrap.len;
      (void)send_wire_frame_client(&conn->ws, conn->cfg->magic, (uint8_t)conn->cfg->version, (uint8_t)conn->cfg->flags_encrypted, cipher.data, cipher.len);
      continue;
    }
  }

  conn->stop = 1;
  p2pws_buf_free(&cipher);
  p2pws_buf_free(&plain);
  p2pws_buf_free(&tmp);
  p2pws_buf_free(&wrap);
  p2pws_buf_free(&rd_bytes);
  return 0;
}

static DWORD WINAPI center_renew_thread_func(LPVOID lpParam) {
  center_thread_args_t* args = (center_thread_args_t*)lpParam;
  const p2pws_cfg_t* cfg = args->cfg;
  const p2pws_keyfile_t* kf = args->kf;
  const uint8_t* key_id32 = args->key_id32;
  p2pws_rsa_t* rsa = args->rsa;
  const char* storage_dir = args->storage_dir;

  if (!cfg || !cfg->ws_url[0]) return 0;
  p2pws_ws_url_t u;
  if (p2pws_ws_parse_url(cfg->ws_url, &u) != 0) return 0;

  for (;;) {
    p2pws_ws_client_t ws;
    center_conn_t* conn = NULL;
    HANDLE recv_th = NULL;
    if (p2pws_ws_connect(&u, &ws) != 0) {
      Sleep(5000);
      continue;
    }

    p2pws_buf_t msg;
    p2pws_buf_t wrap;
    p2pws_buf_t plain;
    p2pws_buf_t cipher;
    p2pws_buf_t tmp;
    p2pws_buf_init(&msg);
    p2pws_buf_init(&wrap);
    p2pws_buf_init(&plain);
    p2pws_buf_init(&cipher);
    p2pws_buf_init(&tmp);
    p2pws_buf_reserve(&msg, 4096);
    p2pws_buf_reserve(&wrap, 4096);
    p2pws_buf_reserve(&plain, 4096);
    p2pws_buf_reserve(&cipher, 4096);
    p2pws_buf_reserve(&tmp, 4096);

    if (p2pws_msg_encode_hand(rsa->pub_spki_der, rsa->pub_spki_der_len, key_id32, cfg->max_frame_payload, cfg->user_id, &msg) != 0) goto reconnect;
    if (p2pws_msg_encode_wrapper(1, -10001, msg.data, msg.len, &wrap) != 0) goto reconnect;
    if (send_wire_frame_client(&ws, cfg->magic, (uint8_t)cfg->version, (uint8_t)cfg->flags_plain, wrap.data, wrap.len) != 0) goto reconnect;

    p2pws_header_t h;
    if (recv_wire_frame(&ws, &plain, &h) != 0) goto reconnect;
    p2pws_wrapper_view_t wv;
    if (p2pws_pb_decode_wrapper(plain.data, plain.len, &wv) != 0) goto reconnect;
    if (wv.command != -10002) goto reconnect;
    if (p2pws_rsa_oaep_sha256_decrypt(rsa, wv.data.p, wv.data.n, &tmp) != 0) goto reconnect;
    p2pws_hand_ack_plain_view_t hak;
    if (p2pws_pb_decode_hand_ack_plain(tmp.data, tmp.len, &hak) != 0) goto reconnect;
    uint32_t offset = hak.offset;

    uint64_t node_id64 = (uint64_t)strtoull(cfg->user_id, NULL, 10);
    uint32_t seq = 2;

    printf("[Center] Connected, offset=%u\n", (unsigned)offset);

    conn = (center_conn_t*)calloc(1, sizeof(center_conn_t));
    conn->ws = ws;
    conn->offset = offset;
    conn->cfg = cfg;
    conn->kf = kf;
    conn->rsa = rsa;
    conn->node_id64 = node_id64;
    strcpy(conn->storage_dir, storage_dir);
    conn->stop = 0;
    recv_th = CreateThread(NULL, 0, center_recv_thread_func, conn, 0, NULL);

    for (;;) {
      if (conn->stop) goto reconnect2;
      msg.len = 0;
      if (p2pws_msg_encode_center_hello_body(node_id64, rsa->pub_spki_der, rsa->pub_spki_der_len, cfg->reported_transport, cfg->reported_addr, cfg->max_frame_payload, cfg->magic, cfg->version, cfg->flags_plain, cfg->flags_encrypted, now_ms(), cfg->crypto_mode, &msg) != 0) goto reconnect;
      tmp.len = 0;
      if (p2pws_rsa_sign_sha256(rsa, msg.data, msg.len, &tmp) != 0) goto reconnect;
      plain.len = 0;
      if (p2pws_msg_encode_center_hello(msg.data, msg.len, tmp.data, tmp.len, &plain) != 0) goto reconnect;
      wrap.len = 0;
      if (p2pws_msg_encode_wrapper(seq++, -11001, plain.data, plain.len, &wrap) != 0) goto reconnect;
      cipher.len = 0;
      p2pws_buf_reserve(&cipher, wrap.len);
      if (p2pws_xor_no_wrap(wrap.data, wrap.len, kf->data, kf->len, offset, cipher.data) != 0) goto reconnect;
      cipher.len = wrap.len;
      if (send_wire_frame_client(&conn->ws, cfg->magic, (uint8_t)cfg->version, (uint8_t)cfg->flags_encrypted, cipher.data, cipher.len) != 0) goto reconnect;

      int sleep_sec = cfg->renew_seconds > 0 ? cfg->renew_seconds : 30;
      printf("[Center] Renew sent, sleeping for %d seconds...\n", sleep_sec);
      Sleep(sleep_sec * 1000);
    }

  reconnect:
    if (conn) conn->stop = 1;
  reconnect2:
    printf("[Center] Disconnected, reconnecting in 5s...\n");
    if (conn) {
      p2pws_ws_close(&conn->ws);
    } else {
      p2pws_ws_close(&ws);
    }
    if (recv_th) {
      WaitForSingleObject(recv_th, 2000);
      CloseHandle(recv_th);
    }
    free(conn);
    p2pws_buf_free(&msg);
    p2pws_buf_free(&wrap);
    p2pws_buf_free(&plain);
    p2pws_buf_free(&cipher);
    p2pws_buf_free(&tmp);
    Sleep(5000);
  }
  return 0;
}

typedef struct {
  p2pws_ws_client_t c;
  const p2pws_cfg_t* cfg;
  const p2pws_keyfile_t* kf;
  uint8_t key_id32[32];
  const p2pws_rsa_t* rsa;
  char storage_dir[512];
} client_thread_args_t;

static DWORD WINAPI client_thread_func(LPVOID lpParam) {
  client_thread_args_t* args = (client_thread_args_t*)lpParam;
  printf("[Peer] Accepted new connection, starting handler thread\n");
  handle_client(&args->c, args->cfg, args->kf, args->key_id32, args->rsa, args->storage_dir);
  printf("[Peer] Connection closed\n");
  free(args);
  return 0;
}

int main(int argc, char** argv) {
  if (argc < 2) {
    printf("usage: p2pws_peer_node <config.yaml>\n");
    return 2;
  }
  const char* cfg_path = argv[1];
  static p2pws_cfg_t cfg;
  if (p2pws_cfg_load_yaml(cfg_path, &cfg) != 0) {
    printf("cfg_load_failed\n");
    return 3;
  }
  if (cfg.listen_port == 0) {
    printf("listen_port_required\n");
    return 4;
  }
  char cfg_dir[512];
  dirname_of(cfg_path, cfg_dir, sizeof(cfg_dir));
  char keyfile_abs[1024];
  char priv_abs[1024];
  join_path(cfg_dir, cfg.keyfile_path, keyfile_abs, sizeof(keyfile_abs));
  join_path(cfg_dir, cfg.rsa_private_key_pem_path, priv_abs, sizeof(priv_abs));

  static p2pws_keyfile_t kf;
  if (p2pws_keyfile_load(keyfile_abs, &kf) != 0) return 5;
  char key_hex[65];
  if (p2pws_sha256_hex(kf.data, kf.len, key_hex) != 0) return 6;
  if (cfg.key_id_sha256_hex[0] && _stricmp(cfg.key_id_sha256_hex, key_hex) != 0) {
    printf("key_id_sha256_hex_mismatch\n");
    return 7;
  }
  static uint8_t key_id32[32];
  for (int i = 0; i < 32; i++) {
    char t[3];
    t[0] = key_hex[i * 2];
    t[1] = key_hex[i * 2 + 1];
    t[2] = 0;
    key_id32[i] = (uint8_t)strtoul(t, NULL, 16);
  }

  static p2pws_rsa_t rsa;
  if (p2pws_rsa_load_private_pem(priv_abs, &rsa) != 0) return 8;
  if (p2pws_rsa_set_public_spki_der_base64(&rsa, cfg.pubkey_spki_der_base64) != 0) return 9;

  char storage_dir[512];
  snprintf(storage_dir, sizeof(storage_dir), "%s\\..\\data\\node_%s", cfg_dir, cfg.user_id);
  p2pws_mkdirs(storage_dir);

  center_thread_args_t* cargs = malloc(sizeof(center_thread_args_t));
  cargs->cfg = &cfg;
  cargs->kf = &kf;
  memcpy(cargs->key_id32, key_id32, 32);
  cargs->rsa = &rsa;
  strcpy(cargs->storage_dir, storage_dir);
  CreateThread(NULL, 0, center_renew_thread_func, cargs, 0, NULL);

  int ls = -1;
  if (p2pws_ws_server_listen((uint16_t)cfg.listen_port, &ls) != 0) {
    printf("listen_failed\n");
    return 10;
  }
  printf("peer_node_listen=%u storage=%s\n", (unsigned)cfg.listen_port, storage_dir);

  for (;;) {
    p2pws_ws_client_t c;
    if (p2pws_ws_server_accept(ls, &c) != 0) continue;
    client_thread_args_t* clargs = malloc(sizeof(client_thread_args_t));
    clargs->c = c;
    clargs->cfg = &cfg;
    clargs->kf = &kf;
    memcpy(clargs->key_id32, key_id32, 32);
    clargs->rsa = &rsa;
    strcpy(clargs->storage_dir, storage_dir);
    CreateThread(NULL, 0, client_thread_func, clargs, 0, NULL);
  }
}
