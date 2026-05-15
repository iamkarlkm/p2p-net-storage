import "dart:typed_data";

import "package:pointycastle/asymmetric/api.dart";

import "crypto.dart";
import "keyfile.dart";
import "messages/wrapper.dart";
import "session.dart";

class ServerGroupClientConfig {
  final List<String> wsUrls;
  final P2PSessionConfig session;
  final String clientId;
  final String cryptoMode;
  final int randomKeyBytes;

  const ServerGroupClientConfig({
    required this.wsUrls,
    required this.session,
    required this.clientId,
    this.cryptoMode = P2PCryptoMode.keyfileXorRsaOaep,
    this.randomKeyBytes = 32,
  });
}

class ServerGroupClient {
  final ServerGroupClientConfig config;
  final RSAPrivateKey privateKey;
  final Uint8List clientPubkeySpkiDer;
  final KeyFileReader? keyfile;

  P2PSession? _session;

  ServerGroupClient({
    required this.config,
    required this.privateKey,
    required this.clientPubkeySpkiDer,
    this.keyfile,
  });

  bool get isConnected => _session != null;

  Future<void> connect({Map<String, dynamic>? headers}) async {
    if (config.wsUrls.isEmpty) {
      throw StateError("ws_urls is required");
    }
    Object? lastErr;
    for (final url in config.wsUrls) {
      try {
        final s = await P2PSession.connect(wsUrl: url, config: config.session, keyfile: keyfile, headers: headers);
        await s.handshake(
          privateKey: privateKey,
          clientPubkeySpkiDer: clientPubkeySpkiDer,
          clientId: config.clientId,
          cryptoMode: config.cryptoMode,
          randomKeyBytes: config.randomKeyBytes,
        );
        _session = s;
        return;
      } catch (e) {
        lastErr = e;
      }
    }
    throw StateError("failed to connect any ws_url: $lastErr");
  }

  Future<P2PWrapper> request({
    required int command,
    required Uint8List data,
    required int expectedCommand,
    Map<String, String> headers = const {},
  }) {
    final s = _session;
    if (s == null) {
      throw StateError("not connected");
    }
    return s.request(command: command, data: data, expectedCommand: expectedCommand, headers: headers);
  }

  Future<P2PWrapper> requestAny({
    required int command,
    required Uint8List data,
    Map<String, String> headers = const {},
  }) {
    final s = _session;
    if (s == null) {
      throw StateError("not connected");
    }
    return s.requestAny(command: command, data: data, headers: headers);
  }

  Future<void> close() async {
    final s = _session;
    _session = null;
    await s?.close();
  }
}
