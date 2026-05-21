#include "p2pws_core_compat.h"

#include <string.h>

#include "p2p_ws.h"
#include "p2pws_messages.h"

static uint64_t read_varint(const uint8_t* p, size_t n, size_t* off, int* ok) {
  uint64_t out = 0;
  uint32_t shift = 0;
  *ok = 0;
  while (*off < n && shift < 64) {
    uint8_t b = p[*off];
    (*off)++;
    out |= (uint64_t)(b & 0x7F) << shift;
    if ((b & 0x80) == 0) {
      *ok = 1;
      return out;
    }
    shift += 7;
  }
  return 0;
}

static int skip_field(const uint8_t* p, size_t n, size_t* off, uint32_t wt) {
  if (*off > n) return -1;
  if (wt == 0) {
    int ok = 0;
    (void)read_varint(p, n, off, &ok);
    return ok ? 0 : -2;
  }
  if (wt == 1) {
    if (*off + 8 > n) return -3;
    *off += 8;
    return 0;
  }
  if (wt == 2) {
    int ok = 0;
    uint64_t len = read_varint(p, n, off, &ok);
    if (!ok) return -4;
    if (*off + (size_t)len > n) return -5;
    *off += (size_t)len;
    return 0;
  }
  if (wt == 5) {
    if (*off + 4 > n) return -6;
    *off += 4;
    return 0;
  }
  return -7;
}

int p2pws_core_encode_header_be(int32_t payload_len, int32_t magic, uint8_t out8[8]) {
  if (!out8) return -1;
  uint32_t len = (uint32_t)payload_len;
  uint32_t m = (uint32_t)magic;
  out8[0] = (uint8_t)((len >> 24) & 0xFF);
  out8[1] = (uint8_t)((len >> 16) & 0xFF);
  out8[2] = (uint8_t)((len >> 8) & 0xFF);
  out8[3] = (uint8_t)((len)&0xFF);
  out8[4] = (uint8_t)((m >> 24) & 0xFF);
  out8[5] = (uint8_t)((m >> 16) & 0xFF);
  out8[6] = (uint8_t)((m >> 8) & 0xFF);
  out8[7] = (uint8_t)((m)&0xFF);
  return 0;
}

int p2pws_core_decode_header_be(const uint8_t in8[8], int32_t* out_payload_len, int32_t* out_magic) {
  if (!in8 || !out_payload_len || !out_magic) return -1;
  uint32_t len = ((uint32_t)in8[0] << 24) | ((uint32_t)in8[1] << 16) | ((uint32_t)in8[2] << 8) | ((uint32_t)in8[3]);
  uint32_t magic = ((uint32_t)in8[4] << 24) | ((uint32_t)in8[5] << 16) | ((uint32_t)in8[6] << 8) | ((uint32_t)in8[7]);
  *out_payload_len = (int32_t)len;
  *out_magic = (int32_t)magic;
  return 0;
}

int p2pws_core_send_wrapper(
  p2pws_ws_client_t* ws,
  int32_t magic,
  const uint8_t* xor_key,
  size_t xor_key_len,
  int encrypt,
  int32_t seq,
  int32_t command_ordinal,
  const uint8_t* data,
  size_t data_len) {
  if (!ws) return -1;
  p2pws_buf_t wrapper;
  p2pws_buf_t frame;
  p2pws_buf_init(&wrapper);
  p2pws_buf_init(&frame);

  int r = p2pws_msg_encode_wrapper(seq, command_ordinal, data, data_len, &wrapper);
  if (r != 0) goto done;

  uint8_t hdr[8];
  r = p2pws_core_encode_header_be((int32_t)wrapper.len, magic, hdr);
  if (r != 0) goto done;
  r = p2pws_buf_append(&frame, hdr, sizeof(hdr));
  if (r != 0) goto done;

  if (encrypt && xor_key && xor_key_len && wrapper.len) {
    p2pws_buf_t tmp;
    p2pws_buf_init(&tmp);
    r = p2pws_buf_reserve(&tmp, wrapper.len + 1);
    if (r != 0) {
      p2pws_buf_free(&tmp);
      goto done;
    }
    tmp.len = wrapper.len;
    p2pws_xor_repeat(wrapper.data, wrapper.len, xor_key, xor_key_len, tmp.data);
    r = p2pws_buf_append(&frame, tmp.data, tmp.len);
    p2pws_buf_free(&tmp);
    if (r != 0) goto done;
  } else {
    r = p2pws_buf_append(&frame, wrapper.data, wrapper.len);
    if (r != 0) goto done;
  }

  r = p2pws_ws_send_binary(ws, frame.data, frame.len);

done:
  p2pws_buf_free(&wrapper);
  p2pws_buf_free(&frame);
  return r;
}

int p2pws_core_send_stream_wrapper(
  p2pws_ws_client_t* ws,
  int32_t magic,
  const uint8_t* xor_key,
  size_t xor_key_len,
  int encrypt,
  int32_t seq,
  int32_t command_ordinal,
  const uint8_t* data,
  size_t data_len,
  int32_t index,
  int completed,
  int canceled) {
  if (!ws) return -1;
  p2pws_buf_t wrapper;
  p2pws_buf_t frame;
  p2pws_buf_init(&wrapper);
  p2pws_buf_init(&frame);

  int r = p2pws_msg_encode_stream_wrapper(seq, command_ordinal, data, data_len, index, completed, canceled, &wrapper);
  if (r != 0) goto done;

  uint8_t hdr[8];
  r = p2pws_core_encode_header_be((int32_t)wrapper.len, magic, hdr);
  if (r != 0) goto done;
  r = p2pws_buf_append(&frame, hdr, sizeof(hdr));
  if (r != 0) goto done;

  if (encrypt && xor_key && xor_key_len && wrapper.len) {
    p2pws_buf_t tmp;
    p2pws_buf_init(&tmp);
    r = p2pws_buf_reserve(&tmp, wrapper.len + 1);
    if (r != 0) {
      p2pws_buf_free(&tmp);
      goto done;
    }
    tmp.len = wrapper.len;
    p2pws_xor_repeat(wrapper.data, wrapper.len, xor_key, xor_key_len, tmp.data);
    r = p2pws_buf_append(&frame, tmp.data, tmp.len);
    p2pws_buf_free(&tmp);
    if (r != 0) goto done;
  } else {
    r = p2pws_buf_append(&frame, wrapper.data, wrapper.len);
    if (r != 0) goto done;
  }

  r = p2pws_ws_send_binary(ws, frame.data, frame.len);

done:
  p2pws_buf_free(&wrapper);
  p2pws_buf_free(&frame);
  return r;
}

int p2pws_core_recv_wrapper(
  p2pws_ws_client_t* ws,
  int32_t expected_magic,
  const uint8_t* xor_key,
  size_t xor_key_len,
  p2pws_buf_t* io_frame_payload,
  p2pws_wrapper_view_t* out_view) {
  if (!ws || !io_frame_payload || !out_view) return -1;
  p2pws_pb_reset(io_frame_payload);
  int r = p2pws_ws_recv_binary(ws, io_frame_payload);
  if (r != 0) return r;
  if (io_frame_payload->len < 8) return -2;

  int32_t payload_len = 0;
  int32_t magic = 0;
  r = p2pws_core_decode_header_be(io_frame_payload->data, &payload_len, &magic);
  if (r != 0) return r;
  if (magic != expected_magic) return -3;
  if (payload_len < 0) return -4;
  if ((size_t)payload_len + 8 > io_frame_payload->len) return -5;

  uint8_t* payload = io_frame_payload->data + 8;
  size_t n = (size_t)payload_len;
  if (xor_key && xor_key_len && n) {
    p2pws_xor_repeat(payload, n, xor_key, xor_key_len, payload);
  }
  return p2pws_pb_decode_wrapper(payload, n, out_view);
}

int p2pws_core_recv_stream_wrapper(
  p2pws_ws_client_t* ws,
  int32_t expected_magic,
  const uint8_t* xor_key,
  size_t xor_key_len,
  p2pws_buf_t* io_frame_payload,
  p2pws_stream_wrapper_view_t* out_view) {
  if (!ws || !io_frame_payload || !out_view) return -1;
  p2pws_pb_reset(io_frame_payload);
  int r = p2pws_ws_recv_binary(ws, io_frame_payload);
  if (r != 0) return r;
  if (io_frame_payload->len < 8) return -2;

  int32_t payload_len = 0;
  int32_t magic = 0;
  r = p2pws_core_decode_header_be(io_frame_payload->data, &payload_len, &magic);
  if (r != 0) return r;
  if (magic != expected_magic) return -3;
  if (payload_len < 0) return -4;
  if ((size_t)payload_len + 8 > io_frame_payload->len) return -5;

  uint8_t* payload = io_frame_payload->data + 8;
  size_t n = (size_t)payload_len;
  if (xor_key && xor_key_len && n) {
    p2pws_xor_repeat(payload, n, xor_key, xor_key_len, payload);
  }
  return p2pws_pb_decode_stream_wrapper(payload, n, out_view);
}

int p2pws_core_request(
  p2pws_ws_client_t* ws,
  int32_t magic,
  const uint8_t* xor_key,
  size_t xor_key_len,
  int encrypt,
  int32_t seq,
  int32_t command_ordinal,
  const uint8_t* data,
  size_t data_len,
  p2pws_buf_t* io_frame_payload,
  p2pws_wrapper_view_t* out_view) {
  if (!out_view) return -1;
  int r = p2pws_core_send_wrapper(ws, magic, xor_key, xor_key_len, encrypt, seq, command_ordinal, data, data_len);
  if (r != 0) return r;
  for (;;) {
    p2pws_wrapper_view_t v;
    r = p2pws_core_recv_wrapper(ws, magic, xor_key, xor_key_len, io_frame_payload, &v);
    if (r != 0) return r;
    if (v.seq == seq) {
      *out_view = v;
      return 0;
    }
  }
}

int p2pws_core_encode_handshake_request(
  const char* user_id,
  uint64_t timestamp_ms,
  const uint8_t nonce16[16],
  int32_t xor_key_length,
  const uint8_t* encrypted_xor_key,
  size_t encrypted_xor_key_len,
  const uint8_t* signature,
  size_t signature_len,
  p2pws_buf_t* out) {
  if (!out) return -1;
  if (!user_id) user_id = "";
  if (!nonce16) return -2;
  if ((!encrypted_xor_key && encrypted_xor_key_len) || (!signature && signature_len)) return -3;
  p2pws_pb_reset(out);
  int r = p2pws_pb_write_string(out, 1, user_id);
  if (r != 0) return r;
  r = p2pws_pb_write_key(out, 2, 0);
  if (r != 0) return r;
  r = p2pws_pb_write_varint_u64(out, timestamp_ms);
  if (r != 0) return r;
  r = p2pws_pb_write_bytes(out, 3, nonce16, 16);
  if (r != 0) return r;
  r = p2pws_pb_write_key(out, 4, 0);
  if (r != 0) return r;
  r = p2pws_pb_write_varint_i32(out, xor_key_length);
  if (r != 0) return r;
  r = p2pws_pb_write_bytes(out, 5, encrypted_xor_key, encrypted_xor_key_len);
  if (r != 0) return r;
  return p2pws_pb_write_bytes(out, 6, signature, signature_len);
}

int p2pws_core_decode_handshake_response(const uint8_t* p, size_t n, int* out_ok, char* out_error, size_t out_error_cap) {
  if (!p || !out_ok || !out_error || out_error_cap == 0) return -1;
  *out_ok = 0;
  out_error[0] = 0;
  size_t off = 0;
  while (off < n) {
    int ok = 0;
    uint64_t key = read_varint(p, n, &off, &ok);
    if (!ok) return -2;
    uint32_t field = (uint32_t)(key >> 3);
    uint32_t wt = (uint32_t)(key & 0x7);
    if (field == 1 && wt == 0) {
      uint64_t v = read_varint(p, n, &off, &ok);
      if (!ok) return -3;
      *out_ok = v ? 1 : 0;
      continue;
    }
    if (field == 2 && wt == 2) {
      uint64_t len = read_varint(p, n, &off, &ok);
      if (!ok) return -4;
      if (off + (size_t)len > n) return -5;
      size_t cp = (size_t)len;
      if (cp + 1 > out_error_cap) cp = out_error_cap - 1;
      memcpy(out_error, p + off, cp);
      out_error[cp] = 0;
      off += (size_t)len;
      continue;
    }
    int r = skip_field(p, n, &off, wt);
    if (r != 0) return r;
  }
  return 0;
}

int p2pws_core_encode_login_request(
  const char* user_id,
  uint64_t timestamp_ms,
  const uint8_t* signature,
  size_t signature_len,
  p2pws_buf_t* out) {
  if (!out) return -1;
  if (!user_id) user_id = "";
  if (!signature && signature_len) return -2;
  p2pws_pb_reset(out);
  int r = p2pws_pb_write_string(out, 1, user_id);
  if (r != 0) return r;
  r = p2pws_pb_write_key(out, 2, 0);
  if (r != 0) return r;
  r = p2pws_pb_write_varint_u64(out, timestamp_ms);
  if (r != 0) return r;
  return p2pws_pb_write_bytes(out, 3, signature, signature_len);
}

int p2pws_core_decode_login_response(const uint8_t* p, size_t n, int* out_ok, char* out_error, size_t out_error_cap) {
  if (!p || !out_ok || !out_error || out_error_cap == 0) return -1;
  *out_ok = 0;
  out_error[0] = 0;
  size_t off = 0;
  while (off < n) {
    int ok = 0;
    uint64_t key = read_varint(p, n, &off, &ok);
    if (!ok) return -2;
    uint32_t field = (uint32_t)(key >> 3);
    uint32_t wt = (uint32_t)(key & 0x7);
    if (field == 1 && wt == 0) {
      uint64_t v = read_varint(p, n, &off, &ok);
      if (!ok) return -3;
      *out_ok = v ? 1 : 0;
      continue;
    }
    if (field == 2 && wt == 2) {
      uint64_t len = read_varint(p, n, &off, &ok);
      if (!ok) return -4;
      if (off + (size_t)len > n) return -5;
      size_t cp = (size_t)len;
      if (cp + 1 > out_error_cap) cp = out_error_cap - 1;
      memcpy(out_error, p + off, cp);
      out_error[cp] = 0;
      off += (size_t)len;
      continue;
    }
    int r = skip_field(p, n, &off, wt);
    if (r != 0) return r;
  }
  return 0;
}
