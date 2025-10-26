import 'package:flutter/material.dart';
import 'bridge/scanner_api.g.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: const HomeScreen(),
      theme: ThemeData(useMaterial3: true, colorSchemeSeed: Colors.indigo),
    );
  }
}

class HomeScreen extends StatelessWidget {
  const HomeScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('SmartScan Test')),
      body: const Center(child: Text('아래 버튼을 눌러 네이티브 테스트')),
      floatingActionButton: FloatingActionButton(
        onPressed: () async {
          // 네이티브 echo 호출
          final api = ScannerApi();
          final msg = await api.echo('hello from Flutter');

          if (context.mounted) {
            ScaffoldMessenger.of(context).showSnackBar(
              SnackBar(content: Text('응답: $msg')),
            );
          }
        },
        child: const Icon(Icons.play_arrow),
      ),
    );
  }
}
