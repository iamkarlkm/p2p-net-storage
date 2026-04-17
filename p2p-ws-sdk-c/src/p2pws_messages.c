#include "p2pws_messages.h"

#include <string.h>

#include "p2pws_pb.h"

int p2pws_msg_encode_wrapper(int32_t seq, int32_t command, const uint8_t* data, size_t data_len, p2pws_buf_t* out) {
  if (!out) return -1;
  p2pws_pb_reset(out);
  int r = p2pws_pb_write_key(out, 1, 0);
  if (r != 0) return r;
  r = p2pws_pb_write_varint_i32(out, seq);
  if (r != 0) return r;
  r = p2pws_pb_write_key(out, 2, 0);
  if (r != 0) return r;
  r = p2pws_pb_write_varint_i32(out, command);
  if (r != 0) return r;
  if (data && data_len) {
    r = p2pws_pb_write_bytes(out, 3, data, data_len);
    if (r != 0) return r;
  } else {
    r = p2pws_pb_write_bytes(out, 3, "", 0);
    if (r != 0) return r;
  }
  return 0;
}

int p2pws_msg_encode_hand(const uint8_t* client_pub_spki_der, size_t client_pub_len, const uint8_t* key_id32, uint32_t max_frame_payload, const char* client_id, p2pws_buf_t* out) {
  if (!out || !client_pub_spki_der || !key_id32) return -1;
  p2pws_pb_reset(out);
  int r = p2pws_pb_write_bytes(out, 1, client_pub_spki_der, client_pub_len);
  if (r != 0) return r;
  r = p2pws_pb_write_bytes(out, 2, key_id32, 32);
  if (r != 0) return r;
  r = p2pws_pb_write_key(out, 3, 0);
  if (r != 0) return r;
  r = p2pws_pb_write_varint_u64(out, max_frame_payload);
  if (r != 0) return r;
  r = p2pws_pb_write_string(out, 4, client_id ? client_id : "");
  if (r != 0) return r;
  return 0;
}

int p2pws_msg_encode_hand_ack_plain(const uint8_t session_id16[16], const uint8_t* selected_key_id32, uint32_t offset, uint32_t max_frame_payload, uint32_t header_policy_id, p2pws_buf_t* out) {
  if (!out || !session_id16 || !selected_key_id32) return -1;
  p2pws_pb_reset(out);
  int r = p2pws_pb_write_bytes(out, 1, session_id16, 16);
  if (r != 0) return r;
  r = p2pws_pb_write_bytes(out, 2, selected_key_id32, 32);
  if (r != 0) return r;
  r = p2pws_pb_write_key(out, 3, 0);
  if (r != 0) return r;
  r = p2pws_pb_write_varint_u64(out, offset);
  if (r != 0) return r;
  r = p2pws_pb_write_key(out, 4, 0);
  if (r != 0) return r;
  r = p2pws_pb_write_varint_u64(out, max_frame_payload);
  if (r != 0) return r;
  r = p2pws_pb_write_key(out, 5, 0);
  if (r != 0) return r;
  r = p2pws_pb_write_varint_u64(out, header_policy_id);
  if (r != 0) return r;
  return 0;
}

static int encode_endpoint(const char* transport, const char* addr, p2pws_buf_t* tmp) {
  p2pws_pb_reset(tmp);
  int r = p2pws_pb_write_string(tmp, 1, transport ? transport : "");
  if (r != 0) return r;
  r = p2pws_pb_write_string(tmp, 2, addr ? addr : "");
  if (r != 0) return r;
  return 0;
}

static int encode_caps(uint32_t max_frame_payload, uint32_t magic, uint32_t version, uint32_t flags_plain, uint32_t flags_encrypted, p2pws_buf_t* tmp) {
  p2pws_pb_reset(tmp);
  int r = p2pws_pb_write_key(tmp, 1, 0);
  if (r != 0) return r;
  r = p2pws_pb_write_varint_u64(tmp, max_frame_payload);
  if (r != 0) return r;
  r = p2pws_pb_write_key(tmp, 2, 0);
  if (r != 0) return r;
  r = p2pws_pb_write_varint_u64(tmp, magic);
  if (r != 0) return r;
  r = p2pws_pb_write_key(tmp, 3, 0);
  if (r != 0) return r;
  r = p2pws_pb_write_varint_u64(tmp, version);
  if (r != 0) return r;
  r = p2pws_pb_write_key(tmp, 4, 0);
  if (r != 0) return r;
  r = p2pws_pb_write_varint_u64(tmp, flags_plain);
  if (r != 0) return r;
  r = p2pws_pb_write_key(tmp, 5, 0);
  if (r != 0) return r;
  r = p2pws_pb_write_varint_u64(tmp, flags_encrypted);
  if (r != 0) return r;
  return 0;
}

int p2pws_msg_encode_center_hello_body(uint64_t node_id64, const uint8_t* pub_spki_der, size_t pub_len, const char* reported_transport, const char* reported_addr, uint32_t max_frame_payload, uint32_t magic, uint32_t version, uint32_t flags_plain, uint32_t flags_encrypted, uint64_t timestamp_ms, const char* crypto_mode, p2pws_buf_t* out) {
  if (!out || !pub_spki_der) return -1;
  p2pws_buf_t tmp;
  p2pws_buf_init(&tmp);
  p2pws_buf_reserve(&tmp, 256);

  p2pws_pb_reset(out);
  int r = p2pws_pb_write_fixed64(out, 1, node_id64);
  if (r != 0) goto done;
  r = p2pws_pb_write_bytes(out, 2, pub_spki_der, pub_len);
  if (r != 0) goto done;
  if (reported_addr && reported_addr[0]) {
    r = encode_endpoint(reported_transport ? reported_transport : "ws", reported_addr, &tmp);
    if (r != 0) goto done;
    r = p2pws_pb_write_bytes(out, 3, tmp.data, tmp.len);
    if (r != 0) goto done;
  }
  r = encode_caps(max_frame_payload, magic, version, flags_plain, flags_encrypted, &tmp);
  if (r != 0) goto done;
  r = p2pws_pb_write_bytes(out, 4, tmp.data, tmp.len);
  if (r != 0) goto done;
  r = p2pws_pb_write_key(out, 5, 0);
  if (r != 0) goto done;
  r = p2pws_pb_write_varint_u64(out, timestamp_ms);
  if (r != 0) goto done;
  r = p2pws_pb_write_string(out, 6, crypto_mode ? crypto_mode : "");
  if (r != 0) goto done;

done:
  p2pws_buf_free(&tmp);
  return r;
}

int p2pws_msg_encode_center_hello(const uint8_t* body, size_t body_len, const uint8_t* sig, size_t sig_len, p2pws_buf_t* out) {
  if (!out || !body || !sig) return -1;
  p2pws_pb_reset(out);
  int r = p2pws_pb_write_bytes(out, 1, body, body_len);
  if (r != 0) return r;
  r = p2pws_pb_write_bytes(out, 2, sig, sig_len);
  if (r != 0) return r;
  return 0;
}

int p2pws_msg_encode_get_node(uint64_t node_id64, const uint8_t* node_key32, size_t node_key_len, p2pws_buf_t* out) {
  if (!out) return -1;
  p2pws_pb_reset(out);
  if (node_id64) {
    int r = p2pws_pb_write_fixed64(out, 1, node_id64);
    if (r != 0) return r;
  }
  if (node_key32 && node_key_len) {
    int r = p2pws_pb_write_bytes(out, 2, node_key32, node_key_len);
    if (r != 0) return r;
  }
  return 0;
}

int p2pws_msg_encode_peer_hello_body(uint64_t node_id64, const uint8_t* pub_spki_der, size_t pub_len, uint64_t timestamp_ms, const char* crypto_mode, p2pws_buf_t* out) {
  if (!out || !pub_spki_der) return -1;
  p2pws_pb_reset(out);
  int r = p2pws_pb_write_fixed64(out, 1, node_id64);
  if (r != 0) return r;
  r = p2pws_pb_write_bytes(out, 2, pub_spki_der, pub_len);
  if (r != 0) return r;
  r = p2pws_pb_write_key(out, 3, 0);
  if (r != 0) return r;
  r = p2pws_pb_write_varint_u64(out, timestamp_ms);
  if (r != 0) return r;
  r = p2pws_pb_write_string(out, 4, crypto_mode ? crypto_mode : "");
  if (r != 0) return r;
  return 0;
}

int p2pws_msg_encode_peer_hello(const uint8_t* body, size_t body_len, const uint8_t* sig, size_t sig_len, p2pws_buf_t* out) {
  if (!out || !body || !sig) return -1;
  p2pws_pb_reset(out);
  int r = p2pws_pb_write_bytes(out, 1, body, body_len);
  if (r != 0) return r;
  r = p2pws_pb_write_bytes(out, 2, sig, sig_len);
  if (r != 0) return r;
  return 0;
}

int p2pws_msg_encode_peer_hello_ack(const uint8_t node_key32[32], uint64_t server_time_ms, p2pws_buf_t* out) {
  if (!out || !node_key32) return -1;
  p2pws_pb_reset(out);
  int r = p2pws_pb_write_bytes(out, 1, node_key32, 32);
  if (r != 0) return r;
  r = p2pws_pb_write_key(out, 2, 0);
  if (r != 0) return r;
  r = p2pws_pb_write_varint_u64(out, server_time_ms);
  if (r != 0) return r;
  return 0;
}

int p2pws_msg_encode_file_put_req(const char* file_name, const uint8_t* file_hash32, const uint8_t* content, size_t content_len, p2pws_buf_t* out) {
  if (!out || !file_hash32 || (!content && content_len)) return -1;
  p2pws_pb_reset(out);
  int r = p2pws_pb_write_string(out, 1, file_name ? file_name : "");
  if (r != 0) return r;
  r = p2pws_pb_write_key(out, 2, 0);
  if (r != 0) return r;
  r = p2pws_pb_write_varint_u64(out, (uint64_t)content_len);
  if (r != 0) return r;
  r = p2pws_pb_write_bytes(out, 3, file_hash32, 32);
  if (r != 0) return r;
  r = p2pws_pb_write_bytes(out, 4, content, content_len);
  if (r != 0) return r;
  return 0;
}

int p2pws_msg_encode_file_put_resp(int success, const uint8_t* file_hash32, p2pws_buf_t* out) {
  if (!out || !file_hash32) return -1;
  p2pws_pb_reset(out);
  int r = p2pws_pb_write_bool(out, 1, success ? 1 : 0);
  if (r != 0) return r;
  r = p2pws_pb_write_bytes(out, 3, file_hash32, 32);
  if (r != 0) return r;
  return 0;
}

int p2pws_msg_encode_file_get_req(const uint8_t* file_hash32, p2pws_buf_t* out) {
  if (!out || !file_hash32) return -1;
  p2pws_pb_reset(out);
  return p2pws_pb_write_bytes(out, 1, file_hash32, 32);
}

int p2pws_msg_encode_file_get_resp(int found, const uint8_t* content, size_t content_len, p2pws_buf_t* out) {
  if (!out) return -1;
  if (!content && content_len) return -2;
  p2pws_pb_reset(out);
  int r = p2pws_pb_write_bool(out, 1, found ? 1 : 0);
  if (r != 0) return r;
  if (found && content && content_len) {
    r = p2pws_pb_write_bytes(out, 4, content, content_len);
    if (r != 0) return r;
  }
  return 0;
}

int p2pws_msg_encode_relay_data(uint64_t target_node_id64, const uint8_t* target_node_key32, size_t target_node_key_len, uint64_t source_node_id64, const uint8_t* source_node_key32, size_t source_node_key_len, const uint8_t* payload, size_t payload_len, p2pws_buf_t* out) {
  if (!out) return -1;
  if (!payload && payload_len) return -2;
  p2pws_pb_reset(out);
  int r = 0;
  if (target_node_id64) {
    r = p2pws_pb_write_fixed64(out, 1, target_node_id64);
    if (r != 0) return r;
  }
  if (target_node_key32 && target_node_key_len) {
    r = p2pws_pb_write_bytes(out, 2, target_node_key32, target_node_key_len);
    if (r != 0) return r;
  }
  if (source_node_id64) {
    r = p2pws_pb_write_fixed64(out, 3, source_node_id64);
    if (r != 0) return r;
  }
  if (source_node_key32 && source_node_key_len) {
    r = p2pws_pb_write_bytes(out, 4, source_node_key32, source_node_key_len);
    if (r != 0) return r;
  }
  r = p2pws_pb_write_bytes(out, 5, payload ? payload : (const uint8_t*)"", payload_len);
  if (r != 0) return r;
  return 0;
}
