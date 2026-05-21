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
  return p2pws_core_rpc_encode_meta_ex(request_id, service, method, call_type, 0, "protobuf", out);
}

int p2pws_core_rpc_encode_meta_ex(
  uint64_t request_id,
  const char* service,
  const char* method,
  int call_type,
  uint64_t deadline_epoch_ms,
  const char* codec,
  p2pws_buf_t* out) {
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
  r = p2pws_pb_write_varint_u64(out, deadline_epoch_ms);
  if (r != 0) return r;
  return p2pws_pb_write_string(out, 7, (codec && codec[0]) ? codec : "protobuf");
}

int p2pws_core_rpc_encode_meta_minimal(uint64_t request_id, p2pws_buf_t* out) {
  if (!out) return -1;
  p2pws_pb_reset(out);
  int r = p2pws_pb_write_key(out, 1, 0);
  if (r != 0) return r;
  return p2pws_pb_write_varint_u64(out, request_id);
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

static int encode_flow_control(int permits, int max_inflight_frames, int max_frame_bytes, p2pws_buf_t* out) {
  if (!out) return -1;
  p2pws_pb_reset(out);
  int r = 0;
  if (permits > 0) {
    r = p2pws_pb_write_key(out, 1, 0);
    if (r != 0) return r;
    r = p2pws_pb_write_varint_i32(out, permits);
    if (r != 0) return r;
  }
  if (max_inflight_frames > 0) {
    r = p2pws_pb_write_key(out, 2, 0);
    if (r != 0) return r;
    r = p2pws_pb_write_varint_i32(out, max_inflight_frames);
    if (r != 0) return r;
  }
  if (max_frame_bytes > 0) {
    r = p2pws_pb_write_key(out, 3, 0);
    if (r != 0) return r;
    r = p2pws_pb_write_varint_i32(out, max_frame_bytes);
    if (r != 0) return r;
  }
  return 0;
}

int p2pws_core_rpc_encode_open_stream(
  const uint8_t* meta,
  size_t meta_len,
  const uint8_t* payload,
  size_t payload_len,
  int permits,
  int max_inflight_frames,
  int max_frame_bytes,
  int end_of_stream,
  p2pws_buf_t* out) {
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
  p2pws_buf_t fc;
  p2pws_buf_init(&fc);
  r = encode_flow_control(permits, max_inflight_frames, max_frame_bytes, &fc);
  if (r == 0) {
    r = p2pws_pb_write_bytes(out, 7, fc.data, fc.len);
  }
  p2pws_buf_free(&fc);
  if (r != 0) return r;
  return p2pws_pb_write_bool(out, 6, end_of_stream ? 1 : 0);
}

int p2pws_core_rpc_encode_data(
  uint64_t request_id,
  const uint8_t* payload,
  size_t payload_len,
  int32_t chunk_index,
  int end_of_message,
  p2pws_buf_t* out) {
  if (!out) return -1;
  if (!payload && payload_len) return -2;
  p2pws_pb_reset(out);
  p2pws_buf_t meta;
  p2pws_buf_init(&meta);
  int r = p2pws_core_rpc_encode_meta_minimal(request_id, &meta);
  if (r != 0) {
    p2pws_buf_free(&meta);
    return r;
  }
  r = p2pws_pb_write_bytes(out, 1, meta.data, meta.len);
  p2pws_buf_free(&meta);
  if (r != 0) return r;
  r = p2pws_pb_write_key(out, 2, 0);
  if (r != 0) return r;
  r = p2pws_pb_write_varint_u64(out, 2);
  if (r != 0) return r;
  r = p2pws_pb_write_bytes(out, 3, payload, payload_len);
  if (r != 0) return r;
  r = p2pws_pb_write_key(out, 5, 0);
  if (r != 0) return r;
  r = p2pws_pb_write_varint_i32(out, chunk_index);
  if (r != 0) return r;
  r = p2pws_pb_write_bool(out, 8, end_of_message ? 1 : 0);
  if (r != 0) return r;
  return p2pws_pb_write_bool(out, 6, 0);
}

int p2pws_core_rpc_encode_close(uint64_t request_id, p2pws_buf_t* out) {
  if (!out) return -1;
  p2pws_pb_reset(out);
  p2pws_buf_t meta;
  p2pws_buf_init(&meta);
  int r = p2pws_core_rpc_encode_meta_minimal(request_id, &meta);
  if (r != 0) {
    p2pws_buf_free(&meta);
    return r;
  }
  r = p2pws_pb_write_bytes(out, 1, meta.data, meta.len);
  p2pws_buf_free(&meta);
  if (r != 0) return r;
  r = p2pws_pb_write_key(out, 2, 0);
  if (r != 0) return r;
  r = p2pws_pb_write_varint_u64(out, 3);
  if (r != 0) return r;
  return p2pws_pb_write_bool(out, 6, 1);
}

int p2pws_core_rpc_encode_cancel(uint64_t request_id, p2pws_buf_t* out) {
  if (!out) return -1;
  p2pws_pb_reset(out);
  p2pws_buf_t meta;
  p2pws_buf_init(&meta);
  int r = p2pws_core_rpc_encode_meta_minimal(request_id, &meta);
  if (r != 0) {
    p2pws_buf_free(&meta);
    return r;
  }
  r = p2pws_pb_write_bytes(out, 1, meta.data, meta.len);
  p2pws_buf_free(&meta);
  if (r != 0) return r;
  r = p2pws_pb_write_key(out, 2, 0);
  if (r != 0) return r;
  r = p2pws_pb_write_varint_u64(out, 4);
  if (r != 0) return r;
  return p2pws_pb_write_bool(out, 6, 1);
}

int p2pws_core_rpc_encode_window_update(uint64_t request_id, int permits, p2pws_buf_t* out) {
  if (!out) return -1;
  p2pws_pb_reset(out);
  p2pws_buf_t meta;
  p2pws_buf_init(&meta);
  int r = p2pws_core_rpc_encode_meta_minimal(request_id, &meta);
  if (r != 0) {
    p2pws_buf_free(&meta);
    return r;
  }
  r = p2pws_pb_write_bytes(out, 1, meta.data, meta.len);
  p2pws_buf_free(&meta);
  if (r != 0) return r;
  r = p2pws_pb_write_key(out, 2, 0);
  if (r != 0) return r;
  r = p2pws_pb_write_varint_u64(out, 6);
  if (r != 0) return r;
  p2pws_buf_t fc;
  p2pws_buf_init(&fc);
  r = encode_flow_control(permits, 0, 0, &fc);
  if (r == 0) {
    r = p2pws_pb_write_bytes(out, 7, fc.data, fc.len);
  }
  p2pws_buf_free(&fc);
  if (r != 0) return r;
  return p2pws_pb_write_bool(out, 6, 1);
}

int p2pws_core_rpc_decode_frame_ex(const uint8_t* p, size_t n, p2pws_core_rpc_frame_ex_t* out) {
  if (!p || !out) return -1;
  memset(out, 0, sizeof(*out));
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
      out->frame_type = (int)v;
      continue;
    }
    if (field == 3 && wt == 2) {
      uint64_t len = read_varint(p, n, &off, &ok);
      if (!ok) return -4;
      if (off + (size_t)len > n) return -5;
      out->payload.p = p + off;
      out->payload.n = (size_t)len;
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
          out->status_code = (int)v;
          continue;
        }
        if (skip_field(sp, sn, &so, swt) != 0) break;
      }
      off += (size_t)len;
      continue;
    }
    if (field == 5 && wt == 0) {
      uint64_t v = read_varint(p, n, &off, &ok);
      if (!ok) return -8;
      out->chunk_index = (int32_t)v;
      continue;
    }
    if (field == 6 && wt == 0) {
      uint64_t v = read_varint(p, n, &off, &ok);
      if (!ok) return -9;
      out->end_of_stream = v ? 1 : 0;
      continue;
    }
    if (field == 7 && wt == 2) {
      uint64_t len = read_varint(p, n, &off, &ok);
      if (!ok) return -10;
      if (off + (size_t)len > n) return -11;
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
          out->permits = (int32_t)v;
          continue;
        }
        if (sf == 2 && swt == 0) {
          uint64_t v = read_varint(sp, sn, &so, &sok);
          if (!sok) break;
          out->max_inflight_frames = (int32_t)v;
          continue;
        }
        if (sf == 3 && swt == 0) {
          uint64_t v = read_varint(sp, sn, &so, &sok);
          if (!sok) break;
          out->max_frame_bytes = (int32_t)v;
          continue;
        }
        if (skip_field(sp, sn, &so, swt) != 0) break;
      }
      off += (size_t)len;
      continue;
    }
    if (field == 8 && wt == 0) {
      uint64_t v = read_varint(p, n, &off, &ok);
      if (!ok) return -12;
      out->end_of_message = v ? 1 : 0;
      continue;
    }
    if (skip_field(p, n, &off, wt) != 0) return -13;
  }
  return 0;
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
