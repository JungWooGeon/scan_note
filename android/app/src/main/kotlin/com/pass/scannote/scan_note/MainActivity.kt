package com.pass.scannote.scan_note

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import io.flutter.embedding.android.FlutterFragmentActivity
import io.flutter.embedding.engine.FlutterEngine
import java.io.File
import java.io.FileOutputStream

class MainActivity : FlutterFragmentActivity(), ScannerApi {

    private lateinit var scannerLauncher: ActivityResultLauncher<IntentSenderRequest>
    private lateinit var permissionLauncher: ActivityResultLauncher<String>

    // 콜백 보관용
    private var pendingCallback: ((Result<List<String?>>) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 문서 스캐너 결과
        scannerLauncher = registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { ar ->
            val cb = pendingCallback ?: return@registerForActivityResult
            pendingCallback = null

            val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(ar.data)
            if (scanResult == null) {
                cb(Result.success(emptyList())) // ✅ 표준 Kotlin Result
                return@registerForActivityResult
            }

            // ✅ pages의 imageUri들을 캐시에 저장해 file:// 경로로 변환
            val fileUris: List<String?> = (scanResult.pages ?: emptyList())
                .mapIndexedNotNull { i, page ->
                    persistToCache(page.imageUri, i)
                }

            cb(Result.success(fileUris))
        }

        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { /* 요청만, 후속은 Flutter에서 다시 scan() */ }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        ScannerApi.setUp(flutterEngine.dartExecutor.binaryMessenger, this)
    }

    override fun echo(message: String): String = "Android received: $message"

    override fun scan(callback: (Result<List<String?>>) -> Unit) {
        // 중복 콜백 정리
        pendingCallback?.invoke(Result.success(emptyList()))
        pendingCallback = callback

        val hasCamera = ActivityCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasCamera) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
            callback(Result.failure(Exception("Camera permission denied")))
            pendingCallback = null
            return
        }

        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .build()

        val client = GmsDocumentScanning.getClient(options)
        client.getStartScanIntent(this)
            .addOnSuccessListener { sender ->
                val req = IntentSenderRequest.Builder(sender).build()
                scannerLauncher.launch(req)
            }
            .addOnFailureListener { e ->
                val cb = pendingCallback
                pendingCallback = null
                cb?.invoke(Result.failure(e)) // ✅ 실패 시
            }
    }

    // 파일로 복사하는 헬퍼
    private fun persistToCache(src: Uri, index: Int): String? {
        return try {
            val out = File(cacheDir, "scan_${System.currentTimeMillis()}_${index}.jpg")
            contentResolver.openInputStream(src).use { input ->
                if (input == null) return null
                FileOutputStream(out).use { output ->
                    input.copyTo(output)
                }
            }
            // Flutter에서 쉽게 쓰도록 file:// prefix까지 붙여서 반환
            "file://${out.absolutePath}"
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
