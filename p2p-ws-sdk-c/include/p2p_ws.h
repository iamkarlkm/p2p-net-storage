#pragma once

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct p2pws_header {
  uint32_t length;
  uint16_t magic;
  uint8_t version;
  uint8_t flags;
} p2pws_header_t;

int p2pws_decode_header_be(const uint8_t* in8, p2pws_header_t* out);
int p2pws_encode_header_be(const p2pws_header_t* h, uint8_t* out8);

int p2pws_xor_no_wrap(const uint8_t* in, size_t in_len, const uint8_t* keyfile, size_t key_len, uint32_t offset, uint8_t* out);

#ifdef __cplusplus
}
#endif

