import 'dart:io'; // 👈 추가!
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
      home: const ScanTestScreen(),
      theme: ThemeData(useMaterial3: true, colorSchemeSeed: Colors.indigo),
    );
  }
}

class ScanTestScreen extends StatefulWidget {
  const ScanTestScreen({super.key});

  @override
  State<ScanTestScreen> createState() => _ScanTestScreenState();
}

class _ScanTestScreenState extends State<ScanTestScreen> {
  final _scannerApi = ScannerApi();
  List<String?> _uris = [];

  Future<void> _startScan() async {
    try {
      final result = await _scannerApi.scan();
      setState(() {
        _uris = result;
      });
    } catch (e) {
      debugPrint("❌ Scan failed: $e");
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('📄 SmartScan 테스트')),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: _startScan,
        label: const Text('스캔하기'),
        icon: const Icon(Icons.camera_alt),
      ),
      body: _uris.isEmpty
          ? const Center(child: Text('스캔된 문서가 없습니다'))
          : ListView.builder(
        itemCount: _uris.length,
        itemBuilder: (context, index) {
          final uriStr = _uris[index];
          if (uriStr == null) return const SizedBox.shrink();

          // file:// 또는 절대 경로 대응
          final uri = Uri.parse(uriStr);
          if (uri.scheme == 'file') {
            return Padding(
              padding: const EdgeInsets.all(8.0),
              child: Image.file(
                File(uri.toFilePath()),
                fit: BoxFit.cover,
                errorBuilder: (_, __, ___) =>
                    Text('이미지 표시 실패: $uriStr'),
              ),
            );
          }

          // 혹시 안드에서 content:// 그대로 온 경우(백업 처리)
          if (uri.scheme == 'content') {
            return ListTile(
              title: const Text('미지원 URI 형식(content://)'),
              subtitle: Text(uriStr),
              leading: const Icon(Icons.warning_amber_rounded),
            );
          }

          // 스킴이 없고 그냥 경로만 온 경우도 대비
          if (uri.scheme.isEmpty) {
            return Padding(
              padding: const EdgeInsets.all(8.0),
              child: Image.file(
                File(uriStr),
                fit: BoxFit.cover,
                errorBuilder: (_, __, ___) =>
                    Text('이미지 표시 실패: $uriStr'),
              ),
            );
          }

          // 기타 스킴
          return ListTile(
            title: const Text('지원하지 않는 URI'),
            subtitle: Text(uriStr),
          );
        },
      ),
    );
  }
}
