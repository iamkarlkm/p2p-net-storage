#pragma once

#include <stddef.h>
#include <stdint.h>

#include "p2pws_buf.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct p2pws_ws_url {
  char host[256];
  uint16_t port;
  char path[256];
} p2pws_ws_url_t;

typedef struct p2pws_ws_client {
  int sock;
} p2pws_ws_client_t;

int p2pws_ws_parse_url(const char* url, p2pws_ws_url_t* out);
int p2pws_ws_connect(const p2pws_ws_url_t* u, p2pws_ws_client_t* out);
void p2pws_ws_close(p2pws_ws_client_t* c);

int p2pws_ws_send_binary(p2pws_ws_client_t* c, const uint8_t* payload, size_t payload_len);
int p2pws_ws_send_binary_unmasked(p2pws_ws_client_t* c, const uint8_t* payload, size_t payload_len);
int p2pws_ws_recv_binary(p2pws_ws_client_t* c, p2pws_buf_t* out_payload);

int p2pws_ws_server_listen(uint16_t port, int* out_listen_sock);
int p2pws_ws_server_accept(int listen_sock, p2pws_ws_client_t* out_client);

#ifdef __cplusplus
}
#endif
