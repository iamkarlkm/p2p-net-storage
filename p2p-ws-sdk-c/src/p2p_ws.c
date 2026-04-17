#include "p2p_ws.h"

static uint32_t read_u32_be(const uint8_t* p) {
  return ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) | ((uint32_t)p[2] << 8) | (uint32_t)p[3];
}

static uint16_t read_u16_be(const uint8_t* p) {
  return (uint16_t)(((uint16_t)p[0] << 8) | (uint16_t)p[1]);
}

static void write_u32_be(uint32_t v, uint8_t* p) {
  p[0] = (uint8_t)((v >> 24) & 0xFF);
  p[1] = (uint8_t)((v >> 16) & 0xFF);
  p[2] = (uint8_t)((v >> 8) & 0xFF);
  p[3] = (uint8_t)(v & 0xFF);
}

static void write_u16_be(uint16_t v, uint8_t* p) {
  p[0] = (uint8_t)((v >> 8) & 0xFF);
  p[1] = (uint8_t)(v & 0xFF);
}

int p2pws_decode_header_be(const uint8_t* in8, p2pws_header_t* out) {
  if (!in8 || !out) return -1;
  out->length = read_u32_be(in8);
  out->magic = read_u16_be(in8 + 4);
  out->version = in8[6];
  out->flags = in8[7];
  return 0;
}

int p2pws_encode_header_be(const p2pws_header_t* h, uint8_t* out8) {
  if (!h || !out8) return -1;
  write_u32_be(h->length, out8);
  write_u16_be(h->magic, out8 + 4);
  out8[6] = h->version;
  out8[7] = h->flags;
  return 0;
}

int p2pws_xor_no_wrap(const uint8_t* in, size_t in_len, const uint8_t* keyfile, size_t key_len, uint32_t offset, uint8_t* out) {
  if (!in || !keyfile || !out) return -1;
  if (offset >= key_len) return -2;
  if ((size_t)offset + in_len > key_len) return -3;
  for (size_t i = 0; i < in_len; i++) {
    out[i] = (uint8_t)(in[i] ^ keyfile[(size_t)offset + i]);
  }
  return 0;
}

