#include "p2pws_core_rpc.h"

#include <string.h>

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

int p2pws_core_rpc_encode_discover_request(const char* service, int include_methods, p2pws_buf_t* out) {
  if (!out) return -1;
  p2pws_pb_reset(out);
  int r = p2pws_pb_write_string(out, 1, service ? service : "");
  if (r != 0) return r;
  return p2pws_pb_write_bool(out, 2, include_methods ? 1 : 0);
}

int p2pws_core_rpc_encode_health_request(const char* service, p2pws_buf_t* out) {
  if (!out) return -1;
  p2pws_pb_reset(out);
  return p2pws_pb_write_string(out, 1, service ? service : "");
}

int p2pws_core_rpc_encode_echo_request(const char* message, p2pws_buf_t* out) {
  if (!out) return -1;
  p2pws_pb_reset(out);
  return p2pws_pb_write_string(out, 1, message ? message : "");
}

int p2pws_core_rpc_encode_meta(uint64_t request_id, const char* service, const char* method, int call_type, p2pws_buf_t* out) {
  if (!out) return -1;
  p2pws_pb_reset(out);
  int r = p2pws_pb_write_key(out, 1, 0);
  if (r != 0) return r;
  r = p2pws_pb_write_varint_u64(out, request_id);
  if (r != 0) return r;
  r = p2pws_pb_write_string(out, 2, service ? service : "");
  if (r != 0) return r;
  r = p2pws_pb_write_string(out, 3, method ? method : "");
  if (r != 0) return r;
  r = p2pws_pb_write_string(out, 4, "");
  if (r != 0) return r;
  r = p2pws_pb_write_key(out, 5, 0);
  if (r != 0) return r;
  r = p2pws_pb_write_varint_u64(out, (uint64_t)call_type);
  if (r != 0) return r;
  r = p2pws_pb_write_key(out, 6, 0);
  if (r != 0) return r;
  r = p2pws_pb_write_varint_u64(out, 0);
  if (r != 0) return r;
  return p2pws_pb_write_string(out, 7, "protobuf");
}

int p2pws_core_rpc_encode_open_unary(const uint8_t* meta, size_t meta_len, const uint8_t* payload, size_t payload_len, p2pws_buf_t* out) {
  if (!out) return -1;
  if ((!meta && meta_len) || (!payload && payload_len)) return -2;
  p2pws_pb_reset(out);
  int r = p2pws_pb_write_bytes(out, 1, meta, meta_len);
  if (r != 0) return r;
  r = p2pws_pb_write_key(out, 2, 0);
  if (r != 0) return r;
  r = p2pws_pb_write_varint_u64(out, 1);
  if (r != 0) return r;
  r = p2pws_pb_write_bytes(out, 3, payload, payload_len);
  if (r != 0) return r;
  return p2pws_pb_write_bool(out, 6, 1);
}

int p2pws_core_rpc_decode_frame_basic(const uint8_t* p, size_t n, int* out_frame_type, int* out_status_code, p2pws_pb_slice_t* out_payload) {
  if (!p || !out_frame_type || !out_status_code || !out_payload) return -1;
  *out_frame_type = 0;
  *out_status_code = 0;
  out_payload->p = NULL;
  out_payload->n = 0;

  size_t off = 0;
  while (off < n) {
    int ok = 0;
    uint64_t key = read_varint(p, n, &off, &ok);
    if (!ok) return -2;
    uint32_t field = (uint32_t)(key >> 3);
    uint32_t wt = (uint32_t)(key & 0x7);
    if (field == 2 && wt == 0) {
      uint64_t v = read_varint(p, n, &off, &ok);
      if (!ok) return -3;
      *out_frame_type = (int)v;
      continue;
    }
    if (field == 3 && wt == 2) {
      uint64_t len = read_varint(p, n, &off, &ok);
      if (!ok) return -4;
      if (off + (size_t)len > n) return -5;
      out_payload->p = p + off;
      out_payload->n = (size_t)len;
      off += (size_t)len;
      continue;
    }
    if (field == 4 && wt == 2) {
      uint64_t len = read_varint(p, n, &off, &ok);
      if (!ok) return -6;
      if (off + (size_t)len > n) return -7;
      const uint8_t* sp = p + off;
      size_t sn = (size_t)len;
      size_t so = 0;
      while (so < sn) {
        int sok = 0;
        uint64_t skey = read_varint(sp, sn, &so, &sok);
        if (!sok) break;
        uint32_t sf = (uint32_t)(skey >> 3);
        uint32_t swt = (uint32_t)(skey & 0x7);
        if (sf == 1 && swt == 0) {
          uint64_t v = read_varint(sp, sn, &so, &sok);
          if (!sok) break;
          *out_status_code = (int)v;
          continue;
        }
        if (skip_field(sp, sn, &so, swt) != 0) break;
      }
      off += (size_t)len;
      continue;
    }
    if (skip_field(p, n, &off, wt) != 0) return -8;
  }
  return 0;
}

int p2pws_core_rpc_decode_echo_response(const uint8_t* p, size_t n, char* out_msg, size_t out_cap, uint64_t* out_server_time) {
  if (!p || !out_msg || out_cap == 0 || !out_server_time) return -1;
  out_msg[0] = 0;
  *out_server_time = 0;
  size_t off = 0;
  while (off < n) {
    int ok = 0;
    uint64_t key = read_varint(p, n, &off, &ok);
    if (!ok) return -2;
    uint32_t field = (uint32_t)(key >> 3);
    uint32_t wt = (uint32_t)(key & 0x7);
    if (field == 1 && wt == 2) {
      uint64_t len = read_varint(p, n, &off, &ok);
      if (!ok) return -3;
      if (off + (size_t)len > n) return -4;
      size_t cp = (size_t)len;
      if (cp + 1 > out_cap) cp = out_cap - 1;
      memcpy(out_msg, p + off, cp);
      out_msg[cp] = 0;
      off += (size_t)len;
      continue;
    }
    if (field == 2 && wt == 0) {
      uint64_t v = read_varint(p, n, &off, &ok);
      if (!ok) return -5;
      *out_server_time = v;
      continue;
    }
    if (skip_field(p, n, &off, wt) != 0) return -6;
  }
  return 0;
}

