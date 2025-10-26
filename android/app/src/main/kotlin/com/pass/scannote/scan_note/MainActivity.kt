package com.pass.scannote.scan_note

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine

class MainActivity: FlutterActivity(), ScannerApi {

    // Flutter → Android 호출에 대한 실제 구현
    override fun echo(message: String): String {
        return "Android received: $message"
    }

    // 피전 브릿지를 Flutter 엔진에 등록 (중요)
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        ScannerApi.setUp(flutterEngine.dartExecutor.binaryMessenger, this)
    }

}
