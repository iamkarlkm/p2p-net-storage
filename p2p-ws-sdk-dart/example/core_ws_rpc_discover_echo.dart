import "dart:io";

import "package:p2p_ws_sdk/src/core_compat/core_ws_client.dart";
import "package:p2p_ws_sdk/src/core_compat/ordinals.dart";
import "package:p2p_ws_sdk/src/core_compat/rpc_client.dart";

Future<void> main(List<String> args) async {
  final wsUrl = args.isNotEmpty ? args[0] : "ws://127.0.0.1:18089/p2p";
  final magic = args.length >= 2 ? int.parse(args[1]) : -252702961;
  final privateKeyPemPath = args.length >= 3 ? args[2] : null;
  final userId = args.length >= 4 ? args[3] : "example-user-id";
  final msg = args.length >= 5 ? args[4] : "hello from dart core_compat";
  final ordinalsPath = args.length >= 6 ? args[5] : "../p2p-ws-protocol/generated/p2p_command_ordinals.json";

  if (privateKeyPemPath == null) {
    stderr.writeln("need client private key pem path arg2");
    exitCode = 2;
    return;
  }

  final ordinals = loadCommandOrdinalsFromJsonFile(ordinalsPath);
  final pem = File(privateKeyPemPath).readAsStringSync();

  final c = CoreWsClient(CoreWsClientConfig(wsUrl: wsUrl, magic: magic), ordinals);
  await c.connect();
  try {
    await c.handshakeAndLogin(userId: userId, clientPrivateKeyPem: pem);
    final rpc = CoreRpcClient(c);

    final services = await rpc.discover(includeMethods: true);
    stdout.writeln("discover.services=${services.length}");
    for (final s in services.take(10)) {
      stdout.writeln("  - ${s.service} version=${s.version} methods=${s.methods.length}");
    }

    final health = await rpc.health(service: "p2p.rpc.echo.v1.EchoService");
    stdout.writeln("health.healthy=${health.healthy} ready=${health.ready} message=${health.message}");

    final echo = await rpc.echo(msg);
    stdout.writeln("echo.message=${echo.message} server_time=${echo.serverTime}");
  } finally {
    await c.close();
  }
}

