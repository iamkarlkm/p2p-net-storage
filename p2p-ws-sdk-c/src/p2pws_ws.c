#include "p2pws_ws.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#ifndef _WIN32
#include <strings.h>
#endif

#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#include <winsock2.h>
#include <ws2tcpip.h>
#pragma comment(lib, "ws2_32.lib")
static int p2pws_wsa_inited = 0;
static int p2pws_wsa_init(void) {
  if (p2pws_wsa_inited) return 0;
  WSADATA wsa;
  int r = WSAStartup(MAKEWORD(2, 2), &wsa);
  if (r != 0) return -1;
  p2pws_wsa_inited = 1;
  return 0;
}
#else
#include <unistd.h>
#include <netdb.h>
#include <sys/socket.h>
#include <arpa/inet.h>
#define closesocket close
#endif

typedef struct p2pws_sha1_ctx {
  uint32_t h[5];
  uint64_t len_bits;
  uint8_t buf[64];
  size_t buf_len;
} p2pws_sha1_ctx_t;

static uint32_t rol32(uint32_t x, uint32_t n) {
  return (x << n) | (x >> (32 - n));
}

static void sha1_init(p2pws_sha1_ctx_t* c) {
  c->h[0] = 0x67452301u;
  c->h[1] = 0xEFCDAB89u;
  c->h[2] = 0x98BADCFEu;
  c->h[3] = 0x10325476u;
  c->h[4] = 0xC3D2E1F0u;
  c->len_bits = 0;
  c->buf_len = 0;
}

static void sha1_block(p2pws_sha1_ctx_t* c, const uint8_t b[64]) {
  uint32_t w[80];
  for (int i = 0; i < 16; i++) {
    w[i] = ((uint32_t)b[i * 4] << 24) | ((uint32_t)b[i * 4 + 1] << 16) | ((uint32_t)b[i * 4 + 2] << 8) | (uint32_t)b[i * 4 + 3];
  }
  for (int i = 16; i < 80; i++) {
    w[i] = rol32(w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16], 1);
  }
  uint32_t a = c->h[0], b0 = c->h[1], c0 = c->h[2], d = c->h[3], e = c->h[4];
  for (int i = 0; i < 80; i++) {
    uint32_t f, k;
    if (i < 20) {
      f = (b0 & c0) | ((~b0) & d);
      k = 0x5A827999u;
    } else if (i < 40) {
      f = b0 ^ c0 ^ d;
      k = 0x6ED9EBA1u;
    } else if (i < 60) {
      f = (b0 & c0) | (b0 & d) | (c0 & d);
      k = 0x8F1BBCDCu;
    } else {
      f = b0 ^ c0 ^ d;
      k = 0xCA62C1D6u;
    }
    uint32_t t = rol32(a, 5) + f + e + k + w[i];
    e = d;
    d = c0;
    c0 = rol32(b0, 30);
    b0 = a;
    a = t;
  }
  c->h[0] += a;
  c->h[1] += b0;
  c->h[2] += c0;
  c->h[3] += d;
  c->h[4] += e;
}

static void sha1_update(p2pws_sha1_ctx_t* c, const uint8_t* p, size_t n) {
  c->len_bits += (uint64_t)n * 8ULL;
  while (n) {
    size_t take = 64 - c->buf_len;
    if (take > n) take = n;
    memcpy(c->buf + c->buf_len, p, take);
    c->buf_len += take;
    p += take;
    n -= take;
    if (c->buf_len == 64) {
      sha1_block(c, c->buf);
      c->buf_len = 0;
    }
  }
}

static void sha1_final(p2pws_sha1_ctx_t* c, uint8_t out20[20]) {
  c->buf[c->buf_len++] = 0x80;
  if (c->buf_len > 56) {
    while (c->buf_len < 64) c->buf[c->buf_len++] = 0;
    sha1_block(c, c->buf);
    c->buf_len = 0;
  }
  while (c->buf_len < 56) c->buf[c->buf_len++] = 0;
  uint64_t L = c->len_bits;
  for (int i = 0; i < 8; i++) c->buf[63 - i] = (uint8_t)((L >> (8 * i)) & 0xFF);
  sha1_block(c, c->buf);
  for (int i = 0; i < 5; i++) {
    out20[i * 4] = (uint8_t)((c->h[i] >> 24) & 0xFF);
    out20[i * 4 + 1] = (uint8_t)((c->h[i] >> 16) & 0xFF);
    out20[i * 4 + 2] = (uint8_t)((c->h[i] >> 8) & 0xFF);
    out20[i * 4 + 3] = (uint8_t)(c->h[i] & 0xFF);
  }
}

static int sock_send_all(int sock, const uint8_t* p, size_t n) {
  size_t off = 0;
  while (off < n) {
    int sent = send(sock, (const char*)p + off, (int)(n - off), 0);
    if (sent <= 0) return -1;
    off += (size_t)sent;
  }
  return 0;
}

static int sock_recv_some(int sock, uint8_t* p, size_t n, int* out_got) {
  int got = recv(sock, (char*)p, (int)n, 0);
  if (got <= 0) return -1;
  *out_got = got;
  return 0;
}

static int sock_recv_exact(int sock, uint8_t* p, size_t n) {
  size_t off = 0;
  while (off < n) {
    int got = recv(sock, (char*)p + off, (int)(n - off), 0);
    if (got <= 0) return -1;
    off += (size_t)got;
  }
  return 0;
}

static int base64_encode(const uint8_t* in, size_t in_len, char* out, size_t out_cap) {
  static const char* tbl = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
  size_t olen = ((in_len + 2) / 3) * 4;
  if (out_cap < olen + 1) return -1;
  size_t i = 0, o = 0;
  while (i + 3 <= in_len) {
    uint32_t v = ((uint32_t)in[i] << 16) | ((uint32_t)in[i + 1] << 8) | (uint32_t)in[i + 2];
    out[o++] = tbl[(v >> 18) & 0x3F];
    out[o++] = tbl[(v >> 12) & 0x3F];
    out[o++] = tbl[(v >> 6) & 0x3F];
    out[o++] = tbl[v & 0x3F];
    i += 3;
  }
  if (i < in_len) {
    uint32_t v = (uint32_t)in[i] << 16;
    out[o++] = tbl[(v >> 18) & 0x3F];
    if (i + 1 < in_len) {
      v |= (uint32_t)in[i + 1] << 8;
      out[o++] = tbl[(v >> 12) & 0x3F];
      out[o++] = tbl[(v >> 6) & 0x3F];
      out[o++] = '=';
    } else {
      out[o++] = tbl[(v >> 12) & 0x3F];
      out[o++] = '=';
      out[o++] = '=';
    }
  }
  out[o] = 0;
  return (int)o;
}

static int compute_accept(const char* sec_key_b64, char* out_b64, size_t out_cap) {
  const char* guid = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
  char tmp[256];
  size_t n1 = strlen(sec_key_b64);
  size_t n2 = strlen(guid);
  if (n1 + n2 + 1 > sizeof(tmp)) return -1;
  memcpy(tmp, sec_key_b64, n1);
  memcpy(tmp + n1, guid, n2);
  tmp[n1 + n2] = 0;
  uint8_t sha1[20];
  p2pws_sha1_ctx_t c;
  sha1_init(&c);
  sha1_update(&c, (const uint8_t*)tmp, n1 + n2);
  sha1_final(&c, sha1);
  if (base64_encode(sha1, 20, out_b64, out_cap) < 0) return -2;
  return 0;
}

int p2pws_ws_parse_url(const char* url, p2pws_ws_url_t* out) {
  if (!url || !out) return -1;
  memset(out, 0, sizeof(*out));
  const char* p = url;
  if (strncmp(p, "ws://", 5) != 0) return -2;
  p += 5;
  const char* slash = strchr(p, '/');
  const char* hostport_end = slash ? slash : p + strlen(p);
  const char* colon = NULL;
  for (const char* q = p; q < hostport_end; q++) {
    if (*q == ':') colon = q;
  }
  size_t host_len = colon ? (size_t)(colon - p) : (size_t)(hostport_end - p);
  if (host_len == 0 || host_len >= sizeof(out->host)) return -3;
  memcpy(out->host, p, host_len);
  out->host[host_len] = 0;
  out->port = 80;
  if (colon) {
    const char* ps = colon + 1;
    char tmp[16];
    size_t plen = (size_t)(hostport_end - ps);
    if (plen == 0 || plen >= sizeof(tmp)) return -4;
    memcpy(tmp, ps, plen);
    tmp[plen] = 0;
    long port = strtol(tmp, NULL, 10);
    if (port <= 0 || port > 65535) return -5;
    out->port = (uint16_t)port;
  }
  if (slash) {
    size_t path_len = strlen(slash);
    if (path_len >= sizeof(out->path)) return -6;
    memcpy(out->path, slash, path_len + 1);
  } else {
    strcpy(out->path, "/");
  }
  return 0;
}

static int connect_tcp(const char* host, uint16_t port) {
#ifdef _WIN32
  if (p2pws_wsa_init() != 0) return -1;
#endif
  char port_str[16];
  snprintf(port_str, sizeof(port_str), "%u", (unsigned)port);
  struct addrinfo hints;
  memset(&hints, 0, sizeof(hints));
  hints.ai_socktype = SOCK_STREAM;
  hints.ai_family = AF_UNSPEC;
  struct addrinfo* res = NULL;
  if (getaddrinfo(host, port_str, &hints, &res) != 0) return -2;
  int sock = -1;
  for (struct addrinfo* it = res; it; it = it->ai_next) {
    sock = (int)socket(it->ai_family, it->ai_socktype, it->ai_protocol);
    if (sock < 0) continue;
    if (connect(sock, it->ai_addr, (int)it->ai_addrlen) == 0) {
      break;
    }
    closesocket(sock);
    sock = -1;
  }
  freeaddrinfo(res);
  return sock;
}

static int read_http_headers(int sock, p2pws_buf_t* out) {
  out->len = 0;
  uint8_t tmp[1024];
  for (;;) {
    int got = 0;
    if (sock_recv_some(sock, tmp, sizeof(tmp), &got) != 0) return -1;
    if (p2pws_buf_append(out, tmp, (size_t)got) != 0) return -2;
    if (out->len >= 4) {
      for (size_t i = 3; i < out->len; i++) {
        if (out->data[i - 3] == '\r' && out->data[i - 2] == '\n' && out->data[i - 1] == '\r' && out->data[i] == '\n') {
          return 0;
        }
      }
    }
    if (out->len > 64 * 1024) return -3;
  }
}

static int header_find_value(const char* headers, const char* name, char* out, size_t out_cap) {
  const char* p = headers;
  size_t name_len = strlen(name);
  while (*p) {
    const char* line_end = strstr(p, "\r\n");
    if (!line_end) break;
    const char* colon = memchr(p, ':', (size_t)(line_end - p));
    if (colon) {
      size_t key_len = (size_t)(colon - p);
#ifdef _WIN32
      if (key_len == name_len && _strnicmp(p, name, name_len) == 0) {
#else
      if (key_len == name_len && strncasecmp(p, name, name_len) == 0) {
#endif
        const char* v = colon + 1;
        while (*v == ' ' || *v == '\t') v++;
        size_t vlen = (size_t)(line_end - v);
        if (vlen >= out_cap) vlen = out_cap - 1;
        memcpy(out, v, vlen);
        out[vlen] = 0;
        return 0;
      }
    }
    p = line_end + 2;
  }
  return -1;
}

int p2pws_ws_connect(const p2pws_ws_url_t* u, p2pws_ws_client_t* out) {
  if (!u || !out) return -1;
  out->sock = -1;
  int sock = connect_tcp(u->host, u->port);
  if (sock < 0) return -2;

  uint8_t key_raw[16];
  for (size_t i = 0; i < sizeof(key_raw); i++) key_raw[i] = (uint8_t)(rand() & 0xFF);
  char sec_key[64];
  if (base64_encode(key_raw, sizeof(key_raw), sec_key, sizeof(sec_key)) < 0) {
    closesocket(sock);
    return -3;
  }
  char accept_expected[64];
  if (compute_accept(sec_key, accept_expected, sizeof(accept_expected)) != 0) {
    closesocket(sock);
    return -4;
  }

  char req[1024];
  int req_len = snprintf(
      req,
      sizeof(req),
      "GET %s HTTP/1.1\r\n"
      "Host: %s:%u\r\n"
      "Upgrade: websocket\r\n"
      "Connection: Upgrade\r\n"
      "Sec-WebSocket-Key: %s\r\n"
      "Sec-WebSocket-Version: 13\r\n"
      "\r\n",
      u->path,
      u->host,
      (unsigned)u->port,
      sec_key);
  if (req_len <= 0 || (size_t)req_len >= sizeof(req)) {
    closesocket(sock);
    return -5;
  }
  if (sock_send_all(sock, (const uint8_t*)req, (size_t)req_len) != 0) {
    closesocket(sock);
    return -6;
  }

  p2pws_buf_t headers;
  p2pws_buf_init(&headers);
  if (p2pws_buf_reserve(&headers, 2048) != 0) {
    p2pws_buf_free(&headers);
    closesocket(sock);
    return -7;
  }
  if (read_http_headers(sock, &headers) != 0) {
    p2pws_buf_free(&headers);
    closesocket(sock);
    return -8;
  }
  if (p2pws_buf_append_u8(&headers, 0) != 0) {
    p2pws_buf_free(&headers);
    closesocket(sock);
    return -9;
  }
  const char* hs = (const char*)headers.data;
  if (strstr(hs, " 101 ") == NULL) {
    p2pws_buf_free(&headers);
    closesocket(sock);
    return -10;
  }
  char accept_got[128];
  accept_got[0] = 0;
  if (header_find_value(hs, "Sec-WebSocket-Accept", accept_got, sizeof(accept_got)) != 0) {
    p2pws_buf_free(&headers);
    closesocket(sock);
    return -11;
  }
  if (strcmp(accept_got, accept_expected) != 0) {
    p2pws_buf_free(&headers);
    closesocket(sock);
    return -12;
  }

  p2pws_buf_free(&headers);
  out->sock = sock;
  return 0;
}

void p2pws_ws_close(p2pws_ws_client_t* c) {
  if (!c) return;
  if (c->sock >= 0) {
    closesocket(c->sock);
    c->sock = -1;
  }
}

int p2pws_ws_send_binary(p2pws_ws_client_t* c, const uint8_t* payload, size_t payload_len) {
  if (!c || c->sock < 0) return -1;
  if (!payload && payload_len) return -2;
  uint8_t mask_key[4];
  for (int i = 0; i < 4; i++) mask_key[i] = (uint8_t)(rand() & 0xFF);
  p2pws_buf_t frame;
  p2pws_buf_init(&frame);
  if (p2pws_buf_reserve(&frame, 14 + payload_len) != 0) return -3;
  uint8_t b0 = 0x80 | 0x2;
  p2pws_buf_append_u8(&frame, b0);
  if (payload_len <= 125) {
    p2pws_buf_append_u8(&frame, (uint8_t)(0x80 | (uint8_t)payload_len));
  } else if (payload_len <= 0xFFFF) {
    p2pws_buf_append_u8(&frame, 0x80 | 126);
    uint8_t tmp[2];
    tmp[0] = (uint8_t)((payload_len >> 8) & 0xFF);
    tmp[1] = (uint8_t)(payload_len & 0xFF);
    p2pws_buf_append(&frame, tmp, 2);
  } else {
    p2pws_buf_append_u8(&frame, 0x80 | 127);
    uint8_t tmp[8];
    uint64_t v = (uint64_t)payload_len;
    for (int i = 0; i < 8; i++) tmp[7 - i] = (uint8_t)((v >> (8 * i)) & 0xFF);
    p2pws_buf_append(&frame, tmp, 8);
  }
  p2pws_buf_append(&frame, mask_key, 4);
  size_t start = frame.len;
  if (p2pws_buf_append(&frame, payload, payload_len) != 0) {
    p2pws_buf_free(&frame);
    return -4;
  }
  for (size_t i = 0; i < payload_len; i++) {
    frame.data[start + i] ^= mask_key[i & 3];
  }
  int r = sock_send_all(c->sock, frame.data, frame.len);
  p2pws_buf_free(&frame);
  return r;
}

int p2pws_ws_send_binary_unmasked(p2pws_ws_client_t* c, const uint8_t* payload, size_t payload_len) {
  if (!c || c->sock < 0) return -1;
  if (!payload && payload_len) return -2;
  p2pws_buf_t frame;
  p2pws_buf_init(&frame);
  if (p2pws_buf_reserve(&frame, 14 + payload_len) != 0) return -3;
  uint8_t b0 = 0x80 | 0x2;
  p2pws_buf_append_u8(&frame, b0);
  if (payload_len <= 125) {
    p2pws_buf_append_u8(&frame, (uint8_t)payload_len);
  } else if (payload_len <= 0xFFFF) {
    p2pws_buf_append_u8(&frame, 126);
    uint8_t tmp[2];
    tmp[0] = (uint8_t)((payload_len >> 8) & 0xFF);
    tmp[1] = (uint8_t)(payload_len & 0xFF);
    p2pws_buf_append(&frame, tmp, 2);
  } else {
    p2pws_buf_append_u8(&frame, 127);
    uint8_t tmp[8];
    uint64_t v = (uint64_t)payload_len;
    for (int i = 0; i < 8; i++) tmp[7 - i] = (uint8_t)((v >> (8 * i)) & 0xFF);
    p2pws_buf_append(&frame, tmp, 8);
  }
  if (p2pws_buf_append(&frame, payload, payload_len) != 0) {
    p2pws_buf_free(&frame);
    return -4;
  }
  int r = sock_send_all(c->sock, frame.data, frame.len);
  p2pws_buf_free(&frame);
  return r;
}

int p2pws_ws_server_listen(uint16_t port, int* out_listen_sock) {
  if (!out_listen_sock) return -1;
#ifdef _WIN32
  if (p2pws_wsa_init() != 0) return -2;
#endif
  int s = (int)socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
  if (s < 0) return -3;
  int opt = 1;
  setsockopt(s, SOL_SOCKET, SO_REUSEADDR, (const char*)&opt, sizeof(opt));
  struct sockaddr_in addr;
  memset(&addr, 0, sizeof(addr));
  addr.sin_family = AF_INET;
  addr.sin_addr.s_addr = htonl(INADDR_ANY);
  addr.sin_port = htons(port);
  if (bind(s, (struct sockaddr*)&addr, sizeof(addr)) != 0) {
    closesocket(s);
    return -4;
  }
  if (listen(s, 16) != 0) {
    closesocket(s);
    return -5;
  }
  *out_listen_sock = s;
  return 0;
}

int p2pws_ws_server_accept(int listen_sock, p2pws_ws_client_t* out_client) {
  if (!out_client) return -1;
  out_client->sock = -1;
  struct sockaddr_in addr;
  int addr_len = (int)sizeof(addr);
  int s = (int)accept(listen_sock, (struct sockaddr*)&addr, &addr_len);
  if (s < 0) return -2;

  p2pws_buf_t headers;
  p2pws_buf_init(&headers);
  if (p2pws_buf_reserve(&headers, 4096) != 0) {
    p2pws_buf_free(&headers);
    closesocket(s);
    return -3;
  }
  if (read_http_headers(s, &headers) != 0) {
    p2pws_buf_free(&headers);
    closesocket(s);
    return -4;
  }
  if (p2pws_buf_append_u8(&headers, 0) != 0) {
    p2pws_buf_free(&headers);
    closesocket(s);
    return -5;
  }
  const char* hs = (const char*)headers.data;
  char sec_key[256];
  sec_key[0] = 0;
  if (header_find_value(hs, "Sec-WebSocket-Key", sec_key, sizeof(sec_key)) != 0) {
    p2pws_buf_free(&headers);
    closesocket(s);
    return -6;
  }
  char accept_b64[128];
  if (compute_accept(sec_key, accept_b64, sizeof(accept_b64)) != 0) {
    p2pws_buf_free(&headers);
    closesocket(s);
    return -7;
  }
  char resp[512];
  int resp_len = snprintf(
      resp,
      sizeof(resp),
      "HTTP/1.1 101 Switching Protocols\r\n"
      "Upgrade: websocket\r\n"
      "Connection: Upgrade\r\n"
      "Sec-WebSocket-Accept: %s\r\n"
      "\r\n",
      accept_b64);
  p2pws_buf_free(&headers);
  if (resp_len <= 0 || (size_t)resp_len >= sizeof(resp)) {
    closesocket(s);
    return -8;
  }
  if (sock_send_all(s, (const uint8_t*)resp, (size_t)resp_len) != 0) {
    closesocket(s);
    return -9;
  }
  out_client->sock = s;
  return 0;
}

int p2pws_ws_recv_binary(p2pws_ws_client_t* c, p2pws_buf_t* out_payload) {
  if (!c || c->sock < 0 || !out_payload) return -1;
  uint8_t h2[2];
  if (sock_recv_exact(c->sock, h2, 2) != 0) return -2;
  uint8_t b0 = h2[0];
  uint8_t b1 = h2[1];
  uint8_t opcode = (uint8_t)(b0 & 0x0F);
  int masked = (b1 & 0x80) ? 1 : 0;
  uint64_t len = (uint64_t)(b1 & 0x7F);
  if (len == 126) {
    uint8_t t[2];
    if (sock_recv_exact(c->sock, t, 2) != 0) return -3;
    len = ((uint64_t)t[0] << 8) | (uint64_t)t[1];
  } else if (len == 127) {
    uint8_t t[8];
    if (sock_recv_exact(c->sock, t, 8) != 0) return -4;
    len = 0;
    for (int i = 0; i < 8; i++) len = (len << 8) | (uint64_t)t[i];
  }
  uint8_t mask_key[4];
  if (masked) {
    if (sock_recv_exact(c->sock, mask_key, 4) != 0) return -5;
  }
  if (len > (64ULL * 1024ULL * 1024ULL)) return -6;
  out_payload->len = 0;
  if (p2pws_buf_reserve(out_payload, (size_t)len + 1) != 0) return -7;
  out_payload->len = (size_t)len;
  if (len) {
    if (sock_recv_exact(c->sock, out_payload->data, (size_t)len) != 0) return -8;
  }
  if (masked) {
    for (size_t i = 0; i < (size_t)len; i++) out_payload->data[i] ^= mask_key[i & 3];
  }
  if (opcode == 0x8) return -9;
  if (opcode != 0x2) return -10;
  return 0;
}
