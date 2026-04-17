#include "p2pws_pb.h"

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

void p2pws_pb_reset(p2pws_buf_t* b) {
  if (!b) return;
  b->len = 0;
}

int p2pws_pb_write_varint_u64(p2pws_buf_t* b, uint64_t v) {
  if (!b) return -1;
  uint8_t tmp[10];
  size_t i = 0;
  while (v >= 0x80) {
    tmp[i++] = (uint8_t)((v & 0x7F) | 0x80);
    v >>= 7;
  }
  tmp[i++] = (uint8_t)(v & 0x7F);
  return p2pws_buf_append(b, tmp, i);
}

int p2pws_pb_write_varint_i32(p2pws_buf_t* b, int32_t v) {
  if (!b) return -1;
  uint64_t u = (uint32_t)v;
  if (v < 0) {
    u = (uint64_t)(uint32_t)v;
    u |= 0xFFFFFFFF00000000ULL;
  }
  return p2pws_pb_write_varint_u64(b, u);
}

int p2pws_pb_write_key(p2pws_buf_t* b, uint32_t field_no, uint32_t wire_type) {
  uint64_t k = ((uint64_t)field_no << 3) | (uint64_t)(wire_type & 0x7);
  return p2pws_pb_write_varint_u64(b, k);
}

int p2pws_pb_write_bytes(p2pws_buf_t* b, uint32_t field_no, const void* p, size_t n) {
  if (!b) return -1;
  if (!p && n) return -2;
  int r = p2pws_pb_write_key(b, field_no, 2);
  if (r != 0) return r;
  r = p2pws_pb_write_varint_u64(b, (uint64_t)n);
  if (r != 0) return r;
  return p2pws_buf_append(b, p, n);
}

int p2pws_pb_write_string(p2pws_buf_t* b, uint32_t field_no, const char* s) {
  if (!s) s = "";
  return p2pws_pb_write_bytes(b, field_no, s, strlen(s));
}

int p2pws_pb_write_fixed64(p2pws_buf_t* b, uint32_t field_no, uint64_t v) {
  if (!b) return -1;
  int r = p2pws_pb_write_key(b, field_no, 1);
  if (r != 0) return r;
  uint8_t tmp[8];
  for (int i = 0; i < 8; i++) tmp[i] = (uint8_t)((v >> (8 * i)) & 0xFF);
  return p2pws_buf_append(b, tmp, 8);
}

int p2pws_pb_write_bool(p2pws_buf_t* b, uint32_t field_no, int v) {
  int r = p2pws_pb_write_key(b, field_no, 0);
  if (r != 0) return r;
  return p2pws_pb_write_varint_u64(b, v ? 1 : 0);
}

int p2pws_pb_decode_wrapper(const uint8_t* p, size_t n, p2pws_wrapper_view_t* out) {
  if (!p || !out) return -1;
  memset(out, 0, sizeof(*out));
  size_t off = 0;
  while (off < n) {
    int ok = 0;
    uint64_t key = read_varint(p, n, &off, &ok);
    if (!ok) return -2;
    uint32_t field = (uint32_t)(key >> 3);
    uint32_t wt = (uint32_t)(key & 7);
    if (field == 1 && wt == 0) {
      uint64_t v = read_varint(p, n, &off, &ok);
      if (!ok) return -3;
      out->seq = (int32_t)v;
      continue;
    }
    if (field == 2 && wt == 0) {
      uint64_t v = read_varint(p, n, &off, &ok);
      if (!ok) return -4;
      out->command = (int32_t)v;
      continue;
    }
    if (field == 3 && wt == 2) {
      uint64_t len = read_varint(p, n, &off, &ok);
      if (!ok) return -5;
      if (off + (size_t)len > n) return -6;
      out->data.p = p + off;
      out->data.n = (size_t)len;
      off += (size_t)len;
      continue;
    }
    int r = skip_field(p, n, &off, wt);
    if (r != 0) return r;
  }
  return 0;
}

static int decode_len_bytes(const uint8_t* p, size_t n, size_t* off, p2pws_pb_slice_t* out) {
  int ok = 0;
  uint64_t len = read_varint(p, n, off, &ok);
  if (!ok) return -1;
  if (*off + (size_t)len > n) return -2;
  out->p = p + *off;
  out->n = (size_t)len;
  *off += (size_t)len;
  return 0;
}

static int decode_endpoint(const uint8_t* p, size_t n, p2pws_endpoint_view_t* out) {
  memset(out, 0, sizeof(*out));
  size_t off = 0;
  while (off < n) {
    int ok = 0;
    uint64_t key = read_varint(p, n, &off, &ok);
    if (!ok) return -1;
    uint32_t field = (uint32_t)(key >> 3);
    uint32_t wt = (uint32_t)(key & 7);
    if (wt == 2 && (field == 1 || field == 2)) {
      p2pws_pb_slice_t s;
      int r = decode_len_bytes(p, n, &off, &s);
      if (r != 0) return r;
      if (field == 1) {
        size_t m = s.n < sizeof(out->transport) - 1 ? s.n : sizeof(out->transport) - 1;
        memcpy(out->transport, s.p, m);
        out->transport[m] = 0;
      } else {
        size_t m = s.n < sizeof(out->addr) - 1 ? s.n : sizeof(out->addr) - 1;
        memcpy(out->addr, s.p, m);
        out->addr[m] = 0;
      }
      continue;
    }
    int r = skip_field(p, n, &off, wt);
    if (r != 0) return r;
  }
  return 0;
}

int p2pws_pb_decode_hand(const uint8_t* p, size_t n, p2pws_hand_view_t* out) {
  if (!p || !out) return -1;
  memset(out, 0, sizeof(*out));
  size_t off = 0;
  while (off < n) {
    int ok = 0;
    uint64_t key = read_varint(p, n, &off, &ok);
    if (!ok) return -2;
    uint32_t field = (uint32_t)(key >> 3);
    uint32_t wt = (uint32_t)(key & 7);
    if (field == 1 && wt == 2) {
      if (decode_len_bytes(p, n, &off, &out->client_pubkey) != 0) return -3;
      continue;
    }
    if (field == 2 && wt == 2) {
      if (out->key_id.n == 0) {
        if (decode_len_bytes(p, n, &off, &out->key_id) != 0) return -4;
      } else {
        int r = skip_field(p, n, &off, wt);
        if (r != 0) return r;
      }
      continue;
    }
    if (field == 3 && wt == 0) {
      uint64_t v = read_varint(p, n, &off, &ok);
      if (!ok) return -5;
      out->max_frame_payload = (uint32_t)v;
      continue;
    }
    if (field == 4 && wt == 2) {
      p2pws_pb_slice_t s;
      if (decode_len_bytes(p, n, &off, &s) != 0) return -6;
      size_t m = s.n < sizeof(out->client_id) - 1 ? s.n : sizeof(out->client_id) - 1;
      memcpy(out->client_id, s.p, m);
      out->client_id[m] = 0;
      continue;
    }
    int r = skip_field(p, n, &off, wt);
    if (r != 0) return r;
  }
  return 0;
}

int p2pws_pb_decode_hand_ack_plain(const uint8_t* p, size_t n, p2pws_hand_ack_plain_view_t* out) {
  if (!p || !out) return -1;
  memset(out, 0, sizeof(*out));
  size_t off = 0;
  while (off < n) {
    int ok = 0;
    uint64_t key = read_varint(p, n, &off, &ok);
    if (!ok) return -2;
    uint32_t field = (uint32_t)(key >> 3);
    uint32_t wt = (uint32_t)(key & 7);
    if (field == 1 && wt == 2) {
      if (decode_len_bytes(p, n, &off, &out->session_id) != 0) return -3;
      continue;
    }
    if (field == 2 && wt == 2) {
      if (decode_len_bytes(p, n, &off, &out->selected_key_id) != 0) return -4;
      continue;
    }
    if ((field == 3 || field == 4 || field == 5) && wt == 0) {
      uint64_t v = read_varint(p, n, &off, &ok);
      if (!ok) return -5;
      if (field == 3) out->offset = (uint32_t)v;
      if (field == 4) out->max_frame_payload = (uint32_t)v;
      if (field == 5) out->header_policy_id = (uint32_t)v;
      continue;
    }
    int r = skip_field(p, n, &off, wt);
    if (r != 0) return r;
  }
  return 0;
}

int p2pws_pb_decode_peer_hello(const uint8_t* p, size_t n, p2pws_peer_hello_view_t* out) {
  if (!p || !out) return -1;
  memset(out, 0, sizeof(*out));
  size_t off = 0;
  while (off < n) {
    int ok = 0;
    uint64_t key = read_varint(p, n, &off, &ok);
    if (!ok) return -2;
    uint32_t field = (uint32_t)(key >> 3);
    uint32_t wt = (uint32_t)(key & 7);
    if (field == 1 && wt == 2) {
      if (decode_len_bytes(p, n, &off, &out->body) != 0) return -3;
      continue;
    }
    if (field == 2 && wt == 2) {
      if (decode_len_bytes(p, n, &off, &out->signature) != 0) return -4;
      continue;
    }
    int r = skip_field(p, n, &off, wt);
    if (r != 0) return r;
  }
  return 0;
}

int p2pws_pb_decode_peer_hello_body(const uint8_t* p, size_t n, p2pws_peer_hello_body_view_t* out) {
  if (!p || !out) return -1;
  memset(out, 0, sizeof(*out));
  size_t off = 0;
  while (off < n) {
    int ok = 0;
    uint64_t key = read_varint(p, n, &off, &ok);
    if (!ok) return -2;
    uint32_t field = (uint32_t)(key >> 3);
    uint32_t wt = (uint32_t)(key & 7);
    if (field == 1 && wt == 1) {
      if (off + 8 > n) return -3;
      uint64_t v = 0;
      for (int i = 0; i < 8; i++) v |= (uint64_t)p[off + i] << (8 * i);
      off += 8;
      out->node_id64 = v;
      continue;
    }
    if (field == 2 && wt == 2) {
      if (decode_len_bytes(p, n, &off, &out->pubkey_spki_der) != 0) return -4;
      continue;
    }
    if (field == 3 && wt == 0) {
      uint64_t v = read_varint(p, n, &off, &ok);
      if (!ok) return -5;
      out->timestamp_ms = v;
      continue;
    }
    if (field == 4 && wt == 2) {
      p2pws_pb_slice_t s;
      if (decode_len_bytes(p, n, &off, &s) != 0) return -6;
      size_t m = s.n < sizeof(out->crypto_mode) - 1 ? s.n : sizeof(out->crypto_mode) - 1;
      memcpy(out->crypto_mode, s.p, m);
      out->crypto_mode[m] = 0;
      continue;
    }
    int r = skip_field(p, n, &off, wt);
    if (r != 0) return r;
  }
  return 0;
}

int p2pws_pb_decode_center_hello_ack(const uint8_t* p, size_t n, p2pws_center_hello_ack_view_t* out) {
  if (!p || !out) return -1;
  memset(out, 0, sizeof(*out));
  size_t off = 0;
  while (off < n) {
    int ok = 0;
    uint64_t key = read_varint(p, n, &off, &ok);
    if (!ok) return -2;
    uint32_t field = (uint32_t)(key >> 3);
    uint32_t wt = (uint32_t)(key & 7);
    if (field == 1 && wt == 2) {
      if (decode_len_bytes(p, n, &off, &out->node_key) != 0) return -3;
      continue;
    }
    if (field == 2 && wt == 2) {
      p2pws_pb_slice_t s;
      if (decode_len_bytes(p, n, &off, &s) != 0) return -4;
      if (decode_endpoint(s.p, s.n, &out->observed_endpoint) != 0) return -5;
      out->has_observed = 1;
      continue;
    }
    if (field == 3 && wt == 0) {
      uint64_t v = read_varint(p, n, &off, &ok);
      if (!ok) return -6;
      out->ttl_seconds = (uint32_t)v;
      continue;
    }
    if (field == 4 && wt == 0) {
      uint64_t v = read_varint(p, n, &off, &ok);
      if (!ok) return -7;
      out->server_time_ms = v;
      continue;
    }
    int r = skip_field(p, n, &off, wt);
    if (r != 0) return r;
  }
  return 0;
}

int p2pws_pb_decode_get_node_ack(const uint8_t* p, size_t n, p2pws_get_node_ack_view_t* out) {
  if (!p || !out) return -1;
  memset(out, 0, sizeof(*out));
  size_t off = 0;
  while (off < n) {
    int ok = 0;
    uint64_t key = read_varint(p, n, &off, &ok);
    if (!ok) return -2;
    uint32_t field = (uint32_t)(key >> 3);
    uint32_t wt = (uint32_t)(key & 7);
    if (field == 1 && wt == 0) {
      uint64_t v = read_varint(p, n, &off, &ok);
      if (!ok) return -3;
      out->found = v ? 1 : 0;
      continue;
    }
    if (field == 2 && wt == 2) {
      if (decode_len_bytes(p, n, &off, &out->node_key) != 0) return -4;
      continue;
    }
    if (field == 3 && wt == 1) {
      if (off + 8 > n) return -5;
      uint64_t v = 0;
      for (int i = 0; i < 8; i++) v |= (uint64_t)p[off + i] << (8 * i);
      off += 8;
      out->node_id64 = v;
      continue;
    }
    if (field == 4 && wt == 2) {
      p2pws_pb_slice_t s;
      if (decode_len_bytes(p, n, &off, &s) != 0) return -6;
      if (out->endpoints_len < 4) {
        if (decode_endpoint(s.p, s.n, &out->endpoints[out->endpoints_len]) == 0) {
          out->endpoints_len++;
        }
      }
      continue;
    }
    int r = skip_field(p, n, &off, wt);
    if (r != 0) return r;
  }
  return 0;
}

int p2pws_pb_decode_peer_hello_ack(const uint8_t* p, size_t n, p2pws_peer_hello_ack_view_t* out) {
  if (!p || !out) return -1;
  memset(out, 0, sizeof(*out));
  size_t off = 0;
  while (off < n) {
    int ok = 0;
    uint64_t key = read_varint(p, n, &off, &ok);
    if (!ok) return -2;
    uint32_t field = (uint32_t)(key >> 3);
    uint32_t wt = (uint32_t)(key & 7);
    if (field == 1 && wt == 2) {
      if (decode_len_bytes(p, n, &off, &out->node_key) != 0) return -3;
      continue;
    }
    if (field == 2 && wt == 0) {
      uint64_t v = read_varint(p, n, &off, &ok);
      if (!ok) return -4;
      out->server_time_ms = v;
      continue;
    }
    int r = skip_field(p, n, &off, wt);
    if (r != 0) return r;
  }
  return 0;
}

int p2pws_pb_decode_relay_data(const uint8_t* p, size_t n, p2pws_relay_data_view_t* out) {
  if (!p || !out) return -1;
  memset(out, 0, sizeof(*out));
  size_t off = 0;
  while (off < n) {
    int ok = 0;
    uint64_t key = read_varint(p, n, &off, &ok);
    if (!ok) return -2;
    uint32_t field = (uint32_t)(key >> 3);
    uint32_t wt = (uint32_t)(key & 7);
    if (field == 1 && wt == 1) {
      if (off + 8 > n) return -3;
      uint64_t v = 0;
      for (int i = 0; i < 8; i++) v |= (uint64_t)p[off + i] << (8 * i);
      off += 8;
      out->target_node_id64 = v;
      continue;
    }
    if (field == 2 && wt == 2) {
      if (decode_len_bytes(p, n, &off, &out->target_node_key) != 0) return -4;
      continue;
    }
    if (field == 3 && wt == 1) {
      if (off + 8 > n) return -5;
      uint64_t v = 0;
      for (int i = 0; i < 8; i++) v |= (uint64_t)p[off + i] << (8 * i);
      off += 8;
      out->source_node_id64 = v;
      continue;
    }
    if (field == 4 && wt == 2) {
      if (decode_len_bytes(p, n, &off, &out->source_node_key) != 0) return -6;
      continue;
    }
    if (field == 5 && wt == 2) {
      if (decode_len_bytes(p, n, &off, &out->payload) != 0) return -7;
      continue;
    }
    int r = skip_field(p, n, &off, wt);
    if (r != 0) return r;
  }
  return 0;
}

int p2pws_pb_decode_file_put_req(const uint8_t* p, size_t n, p2pws_file_put_req_view_t* out) {
  if (!p || !out) return -1;
  memset(out, 0, sizeof(*out));
  size_t off = 0;
  while (off < n) {
    int ok = 0;
    uint64_t key = read_varint(p, n, &off, &ok);
    if (!ok) return -2;
    uint32_t field = (uint32_t)(key >> 3);
    uint32_t wt = (uint32_t)(key & 7);
    if (field == 1 && wt == 2) {
      p2pws_pb_slice_t s;
      if (decode_len_bytes(p, n, &off, &s) != 0) return -3;
      size_t m = s.n < sizeof(out->file_name) - 1 ? s.n : sizeof(out->file_name) - 1;
      memcpy(out->file_name, s.p, m);
      out->file_name[m] = 0;
      continue;
    }
    if (field == 2 && wt == 0) {
      uint64_t v = read_varint(p, n, &off, &ok);
      if (!ok) return -4;
      out->file_size = v;
      continue;
    }
    if (field == 3 && wt == 2) {
      if (decode_len_bytes(p, n, &off, &out->file_hash_sha256) != 0) return -5;
      continue;
    }
    if (field == 4 && wt == 2) {
      if (decode_len_bytes(p, n, &off, &out->content) != 0) return -6;
      continue;
    }
    int r = skip_field(p, n, &off, wt);
    if (r != 0) return r;
  }
  return 0;
}

int p2pws_pb_decode_file_put_resp(const uint8_t* p, size_t n, p2pws_file_put_resp_view_t* out) {
  if (!p || !out) return -1;
  memset(out, 0, sizeof(*out));
  size_t off = 0;
  while (off < n) {
    int ok = 0;
    uint64_t key = read_varint(p, n, &off, &ok);
    if (!ok) return -2;
    uint32_t field = (uint32_t)(key >> 3);
    uint32_t wt = (uint32_t)(key & 7);
    if (field == 1 && wt == 0) {
      uint64_t v = read_varint(p, n, &off, &ok);
      if (!ok) return -3;
      out->success = v ? 1 : 0;
      continue;
    }
    if (field == 3 && wt == 2) {
      if (decode_len_bytes(p, n, &off, &out->file_hash_sha256) != 0) return -4;
      continue;
    }
    int r = skip_field(p, n, &off, wt);
    if (r != 0) return r;
  }
  return 0;
}

int p2pws_pb_decode_file_get_req(const uint8_t* p, size_t n, p2pws_file_get_req_view_t* out) {
  if (!p || !out) return -1;
  memset(out, 0, sizeof(*out));
  size_t off = 0;
  while (off < n) {
    int ok = 0;
    uint64_t key = read_varint(p, n, &off, &ok);
    if (!ok) return -2;
    uint32_t field = (uint32_t)(key >> 3);
    uint32_t wt = (uint32_t)(key & 7);
    if (field == 1 && wt == 2) {
      if (decode_len_bytes(p, n, &off, &out->file_hash_sha256) != 0) return -3;
      continue;
    }
    int r = skip_field(p, n, &off, wt);
    if (r != 0) return r;
  }
  return 0;
}

int p2pws_pb_decode_file_get_resp(const uint8_t* p, size_t n, p2pws_file_get_resp_view_t* out) {
  if (!p || !out) return -1;
  memset(out, 0, sizeof(*out));
  size_t off = 0;
  while (off < n) {
    int ok = 0;
    uint64_t key = read_varint(p, n, &off, &ok);
    if (!ok) return -2;
    uint32_t field = (uint32_t)(key >> 3);
    uint32_t wt = (uint32_t)(key & 7);
    if (field == 1 && wt == 0) {
      uint64_t v = read_varint(p, n, &off, &ok);
      if (!ok) return -3;
      out->found = v ? 1 : 0;
      continue;
    }
    if (field == 4 && wt == 2) {
      if (decode_len_bytes(p, n, &off, &out->content) != 0) return -4;
      continue;
    }
    int r = skip_field(p, n, &off, wt);
    if (r != 0) return r;
  }
  return 0;
}
