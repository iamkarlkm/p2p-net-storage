#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "p2p_ws.h"
#include "p2pws_buf.h"
#include "p2pws_crypto.h"
#include "p2pws_messages.h"
#include "p2pws_pb.h"
#include "p2pws_ws.h"
#include "p2pws_yaml.h"

static int is_abs_path(const char* p) {
  if (!p || !p[0]) return 0;
  if ((p[0] >= 'A' && p[0] <= 'Z') || (p[0] >= 'a' && p[0] <= 'z')) {
    if (p[1] == ':' && (p[2] == '\\' || p[2] == '/')) return 1;
  }
  if (p[0] == '\\' || p[0] == '/') return 1;
  return 0;
}

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

static int send_wire_frame(p2pws_ws_client_t* ws, uint32_t magic, uint8_t version, uint8_t flags, const uint8_t* payload, size_t payload_len) {
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
  p2pws_pb_reset(out_payload);
  p2pws_buf_reserve(out_payload, (size_t)out_h->length + 1);
  memcpy(out_payload->data, raw.data + 8, out_h->length);
  out_payload->len = out_h->length;
  p2pws_buf_free(&raw);
  return 0;
}

static int pick_endpoint_ws_url(const p2pws_get_node_ack_view_t* ga, char* out_url, size_t cap) {
  if (!ga || !out_url || cap == 0) return -1;
  for (size_t i = 0; i < ga->endpoints_len; i++) {
    const char* t = ga->endpoints[i].transport;
    const char* a = ga->endpoints[i].addr;
    if (!t[0] || !a[0]) continue;
    if (strchr(a, ':') == NULL) continue;
    if (_stricmp(t, "ws") != 0) continue;
    int n = snprintf(out_url, cap, "ws://%s/", a);
    if (n > 0 && (size_t)n < cap) return 0;
  }
  if (ga->endpoints_len > 0) {
    const char* a = ga->endpoints[0].addr;
    int n = snprintf(out_url, cap, "ws://%s/", a);
    if (n > 0 && (size_t)n < cap) return 0;
  }
  return -2;
}

int main(int argc, char** argv) {
  if (argc < 3) {
    printf("usage: p2pws_peer_connect <config.yaml> <target_node_id64>\n");
    return 2;
  }
  const char* cfg_path = argv[1];
  uint64_t target_node_id64 = (uint64_t)strtoull(argv[2], NULL, 10);

  p2pws_cfg_t cfg;
  if (p2pws_cfg_load_yaml(cfg_path, &cfg) != 0) {
    printf("cfg_load_failed\n");
    return 3;
  }
  char cfg_dir[512];
  dirname_of(cfg_path, cfg_dir, sizeof(cfg_dir));

  char keyfile_abs[1024];
  char priv_abs[1024];
  join_path(cfg_dir, cfg.keyfile_path, keyfile_abs, sizeof(keyfile_abs));
  join_path(cfg_dir, cfg.rsa_private_key_pem_path, priv_abs, sizeof(priv_abs));

  p2pws_keyfile_t kf;
  if (p2pws_keyfile_load(keyfile_abs, &kf) != 0) {
    printf("keyfile_load_failed\n");
    return 4;
  }
  char key_hex[65];
  if (p2pws_sha256_hex(kf.data, kf.len, key_hex) != 0) {
    printf("keyfile_sha256_failed\n");
    p2pws_keyfile_free(&kf);
    return 5;
  }
  if (cfg.key_id_sha256_hex[0] && _stricmp(cfg.key_id_sha256_hex, key_hex) != 0) {
    printf("key_id_sha256_hex_mismatch\n");
    p2pws_keyfile_free(&kf);
    return 6;
  }
  uint8_t key_id32[32];
  for (int i = 0; i < 32; i++) {
    char tmp2[3];
    tmp2[0] = key_hex[i * 2];
    tmp2[1] = key_hex[i * 2 + 1];
    tmp2[2] = 0;
    key_id32[i] = (uint8_t)strtoul(tmp2, NULL, 16);
  }

  p2pws_rsa_t rsa;
  if (p2pws_rsa_load_private_pem(priv_abs, &rsa) != 0) {
    printf("rsa_load_failed\n");
    p2pws_keyfile_free(&kf);
    return 7;
  }
  if (p2pws_rsa_set_public_spki_der_base64(&rsa, cfg.pubkey_spki_der_base64) != 0) {
    printf("pubkey_base64_invalid\n");
    p2pws_rsa_free(&rsa);
    p2pws_keyfile_free(&kf);
    return 7;
  }

  p2pws_ws_url_t cu;
  if (p2pws_ws_parse_url(cfg.ws_url, &cu) != 0) {
    printf("ws_url_invalid\n");
    p2pws_rsa_free(&rsa);
    p2pws_keyfile_free(&kf);
    return 8;
  }
  p2pws_ws_client_t cws;
  if (p2pws_ws_connect(&cu, &cws) != 0) {
    printf("center_ws_connect_failed\n");
    p2pws_rsa_free(&rsa);
    p2pws_keyfile_free(&kf);
    return 9;
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

  p2pws_msg_encode_hand(rsa.pub_spki_der, rsa.pub_spki_der_len, key_id32, cfg.max_frame_payload, cfg.user_id, &msg);
  p2pws_msg_encode_wrapper(1, -10001, msg.data, msg.len, &wrap);
  send_wire_frame(&cws, cfg.magic, (uint8_t)cfg.version, (uint8_t)cfg.flags_plain, wrap.data, wrap.len);

  p2pws_header_t h;
  recv_wire_frame(&cws, &plain, &h);
  p2pws_wrapper_view_t wv;
  p2pws_pb_decode_wrapper(plain.data, plain.len, &wv);
  if (wv.command != -10002) {
    printf("unexpected_center_hand_ack_cmd=%d\n", (int)wv.command);
    return 10;
  }
  p2pws_rsa_oaep_sha256_decrypt(&rsa, wv.data.p, wv.data.n, &tmp);
  p2pws_hand_ack_plain_view_t hak;
  p2pws_pb_decode_hand_ack_plain(tmp.data, tmp.len, &hak);
  uint32_t offset = hak.offset;

  uint64_t node_id64 = (uint64_t)strtoull(cfg.user_id, NULL, 10);
  p2pws_msg_encode_center_hello_body(node_id64, rsa.pub_spki_der, rsa.pub_spki_der_len, cfg.reported_transport, cfg.reported_addr, cfg.max_frame_payload, cfg.magic, cfg.version, cfg.flags_plain, cfg.flags_encrypted, now_ms(), cfg.crypto_mode, &msg);
  p2pws_rsa_sign_sha256(&rsa, msg.data, msg.len, &tmp);
  p2pws_msg_encode_center_hello(msg.data, msg.len, tmp.data, tmp.len, &plain);
  p2pws_msg_encode_wrapper(2, -11001, plain.data, plain.len, &wrap);
  p2pws_pb_reset(&cipher);
  p2pws_buf_reserve(&cipher, wrap.len);
  p2pws_xor_no_wrap(wrap.data, wrap.len, kf.data, kf.len, offset, cipher.data);
  cipher.len = wrap.len;
  send_wire_frame(&cws, cfg.magic, (uint8_t)cfg.version, (uint8_t)cfg.flags_encrypted, cipher.data, cipher.len);

  recv_wire_frame(&cws, &cipher, &h);
  p2pws_pb_reset(&plain);
  p2pws_buf_reserve(&plain, cipher.len);
  p2pws_xor_no_wrap(cipher.data, cipher.len, kf.data, kf.len, offset, plain.data);
  plain.len = cipher.len;
  p2pws_pb_decode_wrapper(plain.data, plain.len, &wv);
  if (wv.command != -11002) {
    printf("unexpected_center_join_ack_cmd=%d\n", (int)wv.command);
    return 11;
  }
  p2pws_center_hello_ack_view_t ca;
  p2pws_pb_decode_center_hello_ack(wv.data.p, wv.data.n, &ca);

  p2pws_msg_encode_get_node(target_node_id64, NULL, 0, &msg);
  p2pws_msg_encode_wrapper(3, -11010, msg.data, msg.len, &wrap);
  p2pws_pb_reset(&cipher);
  p2pws_buf_reserve(&cipher, wrap.len);
  p2pws_xor_no_wrap(wrap.data, wrap.len, kf.data, kf.len, offset, cipher.data);
  cipher.len = wrap.len;
  send_wire_frame(&cws, cfg.magic, (uint8_t)cfg.version, (uint8_t)cfg.flags_encrypted, cipher.data, cipher.len);

  recv_wire_frame(&cws, &cipher, &h);
  p2pws_pb_reset(&plain);
  p2pws_buf_reserve(&plain, cipher.len);
  p2pws_xor_no_wrap(cipher.data, cipher.len, kf.data, kf.len, offset, plain.data);
  plain.len = cipher.len;
  p2pws_pb_decode_wrapper(plain.data, plain.len, &wv);
  if (wv.command != -11011) {
    printf("unexpected_get_node_ack_cmd=%d\n", (int)wv.command);
    return 12;
  }
  p2pws_get_node_ack_view_t ga;
  p2pws_pb_decode_get_node_ack(wv.data.p, wv.data.n, &ga);
  if (!ga.found) {
    printf("target_not_found\n");
    return 13;
  }
  char peer_ws_url[512];
  if (pick_endpoint_ws_url(&ga, peer_ws_url, sizeof(peer_ws_url)) != 0) {
    printf("no_peer_endpoint\n");
    return 14;
  }
  printf("peer_ws_url=%s\n", peer_ws_url);
  p2pws_ws_close(&cws);

  p2pws_ws_url_t pu;
  if (p2pws_ws_parse_url(peer_ws_url, &pu) != 0) {
    printf("peer_url_invalid\n");
    return 15;
  }
  p2pws_ws_client_t pws;
  if (p2pws_ws_connect(&pu, &pws) != 0) {
    printf("peer_connect_failed\n");
    return 16;
  }

  p2pws_msg_encode_hand(rsa.pub_spki_der, rsa.pub_spki_der_len, key_id32, cfg.max_frame_payload, cfg.user_id, &msg);
  p2pws_msg_encode_wrapper(1, -10001, msg.data, msg.len, &wrap);
  send_wire_frame(&pws, cfg.magic, (uint8_t)cfg.version, (uint8_t)cfg.flags_plain, wrap.data, wrap.len);

  recv_wire_frame(&pws, &plain, &h);
  p2pws_pb_decode_wrapper(plain.data, plain.len, &wv);
  if (wv.command != -10002) {
    printf("unexpected_peer_hand_ack_cmd=%d\n", (int)wv.command);
    return 17;
  }
  p2pws_rsa_oaep_sha256_decrypt(&rsa, wv.data.p, wv.data.n, &tmp);
  p2pws_pb_decode_hand_ack_plain(tmp.data, tmp.len, &hak);
  uint32_t peer_offset = hak.offset;

  p2pws_msg_encode_peer_hello_body(node_id64, rsa.pub_spki_der, rsa.pub_spki_der_len, now_ms(), cfg.crypto_mode, &msg);
  p2pws_rsa_sign_sha256(&rsa, msg.data, msg.len, &tmp);
  p2pws_msg_encode_peer_hello(msg.data, msg.len, tmp.data, tmp.len, &plain);
  p2pws_msg_encode_wrapper(2, -12001, plain.data, plain.len, &wrap);
  p2pws_pb_reset(&cipher);
  p2pws_buf_reserve(&cipher, wrap.len);
  p2pws_xor_no_wrap(wrap.data, wrap.len, kf.data, kf.len, peer_offset, cipher.data);
  cipher.len = wrap.len;
  send_wire_frame(&pws, cfg.magic, (uint8_t)cfg.version, (uint8_t)cfg.flags_encrypted, cipher.data, cipher.len);

  recv_wire_frame(&pws, &cipher, &h);
  p2pws_pb_reset(&plain);
  p2pws_buf_reserve(&plain, cipher.len);
  p2pws_xor_no_wrap(cipher.data, cipher.len, kf.data, kf.len, peer_offset, plain.data);
  plain.len = cipher.len;
  p2pws_pb_decode_wrapper(plain.data, plain.len, &wv);
  if (wv.command != -12002) {
    printf("unexpected_peer_hello_ack_cmd=%d\n", (int)wv.command);
    return 18;
  }
  p2pws_peer_hello_ack_view_t pa;
  p2pws_pb_decode_peer_hello_ack(wv.data.p, wv.data.n, &pa);

  uint8_t content[128];
  int content_len = snprintf((char*)content, sizeof(content), "Hello P2P Storage World! %llu", (unsigned long long)now_ms());
  if (content_len <= 0) content_len = 0;
  uint8_t file_hash32[32];
  p2pws_sha256_bytes(content, (size_t)content_len, file_hash32);
  p2pws_msg_encode_file_put_req("hello.txt", file_hash32, content, (size_t)content_len, &msg);
  p2pws_msg_encode_wrapper(3, 1001, msg.data, msg.len, &wrap);
  p2pws_pb_reset(&cipher);
  p2pws_buf_reserve(&cipher, wrap.len);
  p2pws_xor_no_wrap(wrap.data, wrap.len, kf.data, kf.len, peer_offset, cipher.data);
  cipher.len = wrap.len;
  send_wire_frame(&pws, cfg.magic, (uint8_t)cfg.version, (uint8_t)cfg.flags_encrypted, cipher.data, cipher.len);

  recv_wire_frame(&pws, &cipher, &h);
  p2pws_pb_reset(&plain);
  p2pws_buf_reserve(&plain, cipher.len);
  p2pws_xor_no_wrap(cipher.data, cipher.len, kf.data, kf.len, peer_offset, plain.data);
  plain.len = cipher.len;
  p2pws_pb_decode_wrapper(plain.data, plain.len, &wv);
  if (wv.command != 1002) {
    printf("unexpected_file_put_resp_cmd=%d\n", (int)wv.command);
    return 19;
  }
  p2pws_file_put_resp_view_t fr;
  p2pws_pb_decode_file_put_resp(wv.data.p, wv.data.n, &fr);
  printf("file_put.success=%d\n", fr.success);

  p2pws_msg_encode_file_get_req(file_hash32, &msg);
  p2pws_msg_encode_wrapper(4, 1003, msg.data, msg.len, &wrap);
  p2pws_pb_reset(&cipher);
  p2pws_buf_reserve(&cipher, wrap.len);
  p2pws_xor_no_wrap(wrap.data, wrap.len, kf.data, kf.len, peer_offset, cipher.data);
  cipher.len = wrap.len;
  send_wire_frame(&pws, cfg.magic, (uint8_t)cfg.version, (uint8_t)cfg.flags_encrypted, cipher.data, cipher.len);

  recv_wire_frame(&pws, &cipher, &h);
  p2pws_pb_reset(&plain);
  p2pws_buf_reserve(&plain, cipher.len);
  p2pws_xor_no_wrap(cipher.data, cipher.len, kf.data, kf.len, peer_offset, plain.data);
  plain.len = cipher.len;
  p2pws_pb_decode_wrapper(plain.data, plain.len, &wv);
  if (wv.command != 1004) {
    printf("unexpected_file_get_resp_cmd=%d\n", (int)wv.command);
    return 20;
  }
  p2pws_file_get_resp_view_t gr;
  p2pws_pb_decode_file_get_resp(wv.data.p, wv.data.n, &gr);
  printf("file_get.found=%d content_len=%u\n", gr.found, (unsigned)gr.content.n);
  if (gr.found && gr.content.n) {
    fwrite(gr.content.p, 1, gr.content.n, stdout);
    printf("\n");
  }

  p2pws_ws_close(&pws);
  p2pws_buf_free(&msg);
  p2pws_buf_free(&wrap);
  p2pws_buf_free(&plain);
  p2pws_buf_free(&cipher);
  p2pws_buf_free(&tmp);
  p2pws_rsa_free(&rsa);
  p2pws_keyfile_free(&kf);
  return 0;
}
