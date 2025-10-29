import 'package:pigeon/pigeon.dart';

@HostApi()
abstract class ScannerApi {
  String echo(String message);

  @async
  List<String?> scan();
}