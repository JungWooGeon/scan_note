import 'package:pigeon/pigeon.dart';

@HostApi()
abstract class ScannerApi {

  String echo(String message);

  @async
  List<String?> scan();

  // file:// 경로 리스트를 받아 페이지별 텍스트를 반환
  @async
  List<String?> ocr(List<String?> fileUris);

}