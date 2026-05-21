cmake_minimum_required(VERSION 3.20)

project(p2p_ws_sdk_c C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

add_library(p2pws_c
  src/p2p_ws.c
  src/p2pws_buf.c
  src/p2pws_pb.c
  src/p2pws_ws.c
  src/p2pws_crypto.c
  src/p2pws_cipher.c
  src/p2pws_yaml.c
  src/p2pws_messages.c
  src/p2pws_fs.c
  src/p2pws_core_compat.c
  src/p2pws_core_rpc.c
  src/p2pws_core_stream.c
)

target_include_directories(p2pws_c PUBLIC include)

if(WIN32)
  target_link_libraries(p2pws_c PUBLIC ws2_32 bcrypt ncrypt crypt32)
endif()

add_executable(p2pws_center_join demo/center_join.c)
target_link_libraries(p2pws_center_join PRIVATE p2pws_c)

add_executable(p2pws_peer_connect demo/peer_connect.c)
target_link_libraries(p2pws_peer_connect PRIVATE p2pws_c)

add_executable(p2pws_relay_echo demo/relay_echo.c)
target_link_libraries(p2pws_relay_echo PRIVATE p2pws_c)

add_executable(p2pws_peer_node demo/peer_node.c)
target_link_libraries(p2pws_peer_node PRIVATE p2pws_c)

add_executable(p2pws_core_ws_rpc_discover_echo demo/core_ws_rpc_discover_echo.c)
target_link_libraries(p2pws_core_ws_rpc_discover_echo PRIVATE p2pws_c)

add_executable(p2pws_core_ws_verify_stream_wrapper demo/core_ws_verify_stream_wrapper.c)
target_link_libraries(p2pws_core_ws_verify_stream_wrapper PRIVATE p2pws_c)

add_executable(p2pws_core_ws_rpc_stream_collect_chat demo/core_ws_rpc_stream_collect_chat.c)
target_link_libraries(p2pws_core_ws_rpc_stream_collect_chat PRIVATE p2pws_c)
