import 'dart:io';
import 'package:path_provider/path_provider.dart';
import 'package:pdf/pdf.dart';
import 'package:pdf/widgets.dart' as pw;
import 'package:printing/printing.dart';

class PdfExportService {
  /// file:// 경로 리스트를 받아 PDF 파일 생성 후, 저장 경로를 리턴한다.
  static Future<File> createPdfFromImages(List<String> fileUris,
      {String filename = 'scan.pdf'}) async {
    final doc = pw.Document();

    for (final uri in fileUris) {
      if (uri.isEmpty) continue;
      final file = File(Uri.parse(uri).toFilePath());
      if (!await file.exists()) continue;

      final image = pw.MemoryImage(await file.readAsBytes());
      doc.addPage(
        pw.Page(
          pageFormat: PdfPageFormat.a4,
          build: (_) => pw.Center(
            child: pw.FittedBox(
              fit: pw.BoxFit.contain,
              child: pw.Image(image),
            ),
          ),
        ),
      );
    }

    final dir = await getApplicationDocumentsDirectory();
    final out = File('${dir.path}/$filename');
    await out.writeAsBytes(await doc.save(), flush: true);
    return out;
  }

  /// 생성된 PDF를 시스템 공유 시트로 열어준다.
  static Future<void> sharePdf(File pdfFile) async {
    await Printing.sharePdf(bytes: await pdfFile.readAsBytes(), filename: pdfFile.uri.pathSegments.last);
  }
}
