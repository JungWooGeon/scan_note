import 'dart:io';
import 'package:flutter/material.dart';
import 'package:pdf/widgets.dart' as pw;
import 'package:printing/printing.dart';

import 'bridge/scanner_api.g.dart';

/// 전역 스낵바용 키 (async 갭에서 BuildContext 미사용)
final rootMessengerKey = GlobalKey<ScaffoldMessengerState>();

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});
  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      scaffoldMessengerKey: rootMessengerKey,
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
  List<String> _uris = [];
  List<String> _texts = [];
  bool _busy = false;

  Future<void> _startScan() async {
    setState(() => _busy = true);
    try {
      final result = await _scannerApi.scan(); // List<String?>
      if (!mounted) return;
      setState(() {
        _uris = result.whereType<String>().toList(); // null 제거
        _texts = [];
      });
    } catch (e) {
      rootMessengerKey.currentState?.showSnackBar(
        SnackBar(content: Text('스캔 실패: $e')),
      );
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _runOcr() async {
    if (_uris.isEmpty) {
      rootMessengerKey.currentState?.showSnackBar(
        const SnackBar(content: Text('먼저 스캔을 해주세요.')),
      );
      return;
    }
    setState(() => _busy = true);
    try {
      final texts = await _scannerApi.ocr(_uris); // List<String?>
      if (!mounted) return;
      setState(() {
        _texts = texts.map((e) => e ?? '').toList(); // null -> 빈문자
      });
    } catch (e) {
      rootMessengerKey.currentState?.showSnackBar(
        SnackBar(content: Text('OCR 실패: $e')),
      );
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _clearAll() {
    setState(() {
      _uris.clear();
      _texts.clear();
    });
  }

  /// PDF 생성 후 네이티브(ScannerApi.saveToDownloads)로 Downloads에 저장
  Future<void> _exportPdfSave() async {
    if (_uris.isEmpty) {
      rootMessengerKey.currentState?.showSnackBar(
        const SnackBar(content: Text('내보낼 스캔 이미지가 없습니다.')),
      );
      return;
    }
    setState(() => _busy = true);
    try {
      final doc = pw.Document();
      for (var i = 0; i < _uris.length; i++) {
        final file = File(Uri.parse(_uris[i]).toFilePath());
        final bytes = await file.readAsBytes();
        final text = (i < _texts.length) ? _texts[i] : '';
        doc.addPage(
          pw.Page(
            margin: const pw.EdgeInsets.all(24),
            build: (_) => pw.Column(
              crossAxisAlignment: pw.CrossAxisAlignment.stretch,
              children: [
                pw.Expanded(
                  child: pw.Image(pw.MemoryImage(bytes), fit: pw.BoxFit.contain),
                ),
                if (text.isNotEmpty) ...[
                  pw.SizedBox(height: 16),
                  pw.Text(text, style: const pw.TextStyle(fontSize: 12)),
                ],
              ],
            ),
          ),
        );
      }

      final pdfBytes = await doc.save();
      final filename = 'scan_${DateTime.now().millisecondsSinceEpoch}.pdf';

      // ✅ 네이티브 경로로 저장 (API29+는 MediaStore Downloads, 이하 버전은 퍼블릭 Downloads)
      final savedUri = await _scannerApi.saveToDownloads(pdfBytes, filename);

      rootMessengerKey.currentState?.showSnackBar(
        SnackBar(content: Text('PDF 저장 완료: $savedUri')),
      );
    } catch (e) {
      rootMessengerKey.currentState?.showSnackBar(
        SnackBar(content: Text('PDF 저장 실패: $e')),
      );
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  /// PDF 생성 후 공유 시트로 공유
  Future<void> _exportPdfShare() async {
    if (_uris.isEmpty) return;
    setState(() => _busy = true);
    try {
      final doc = pw.Document();
      for (var i = 0; i < _uris.length; i++) {
        final file = File(Uri.parse(_uris[i]).toFilePath());
        final bytes = await file.readAsBytes();
        final text = (i < _texts.length) ? _texts[i] : '';
        doc.addPage(
          pw.Page(
            margin: const pw.EdgeInsets.all(24),
            build: (_) => pw.Column(
              crossAxisAlignment: pw.CrossAxisAlignment.stretch,
              children: [
                pw.Expanded(
                  child: pw.Image(pw.MemoryImage(bytes), fit: pw.BoxFit.contain),
                ),
                if (text.isNotEmpty) ...[
                  pw.SizedBox(height: 16),
                  pw.Text(text, style: const pw.TextStyle(fontSize: 12)),
                ],
              ],
            ),
          ),
        );
      }
      final bytes = await doc.save();
      await Printing.sharePdf(
        bytes: bytes,
        filename: 'scan_${DateTime.now().millisecondsSinceEpoch}.pdf',
      );
    } catch (e) {
      rootMessengerKey.currentState?.showSnackBar(
        SnackBar(content: Text('PDF 공유 실패: $e')),
      );
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final hasText = _texts.any((e) => e.isNotEmpty);

    return Scaffold(
      appBar: AppBar(
        title: const Text('📄 SmartScan 테스트'),
        actions: [
          IconButton(
            tooltip: '전체 삭제',
            onPressed: _uris.isEmpty ? null : _clearAll,
            icon: const Icon(Icons.delete_forever),
          ),
          IconButton(
            tooltip: 'PDF 저장',
            onPressed: _uris.isEmpty ? null : _exportPdfSave,
            icon: const Icon(Icons.picture_as_pdf),
          ),
          IconButton(
            tooltip: 'PDF 공유',
            onPressed: _uris.isEmpty ? null : _exportPdfShare,
            icon: const Icon(Icons.ios_share),
          ),
        ],
      ),
      floatingActionButton: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          FloatingActionButton.extended(
            onPressed: _busy ? null : _startScan,
            label: const Text('스캔하기'),
            icon: const Icon(Icons.camera_alt),
          ),
          const SizedBox(height: 12),
          FloatingActionButton.extended(
            onPressed: _busy ? null : _runOcr,
            label: const Text('텍스트 추출'),
            icon: const Icon(Icons.text_fields),
          ),
        ],
      ),
      body: _busy
          ? const Center(child: CircularProgressIndicator())
          : _uris.isEmpty
          ? const Center(child: Text('스캔된 문서가 없습니다'))
          : ListView.builder(
        itemCount: _uris.length,
        itemBuilder: (context, index) {
          final uriStr = _uris[index];
          Widget imageWidget;

          final uri = Uri.tryParse(uriStr);
          if (uri != null && uri.scheme == 'file') {
            imageWidget = Image.file(
              File(uri.toFilePath()),
              fit: BoxFit.cover,
              errorBuilder: (_, __, ___) =>
                  Text('이미지 표시 실패: $uriStr'),
            );
          } else {
            imageWidget = ListTile(
              title: const Text('미지원 URI'),
              subtitle: Text(uriStr),
            );
          }

          final text = (index < _texts.length) ? _texts[index] : '';

          return Padding(
            padding: const EdgeInsets.all(8.0),
            child: Card(
              child: Padding(
                padding: const EdgeInsets.all(12.0),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    // 개별 삭제 버튼
                    Row(
                      mainAxisAlignment: MainAxisAlignment.end,
                      children: [
                        IconButton(
                          tooltip: '이 페이지 삭제',
                          onPressed: () {
                            setState(() {
                              _uris.removeAt(index);
                              if (index < _texts.length) {
                                _texts.removeAt(index);
                              }
                            });
                          },
                          icon: const Icon(Icons.close),
                        ),
                      ],
                    ),
                    imageWidget,
                    const SizedBox(height: 8),
                    if (hasText)
                      SelectableText(
                        text.isEmpty ? '[텍스트 없음]' : text,
                        style: const TextStyle(fontSize: 14),
                      ),
                  ],
                ),
              ),
            ),
          );
        },
      ),
    );
  }
}
