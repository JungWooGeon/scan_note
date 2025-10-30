package com.pass.scannote.scan_note

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.net.toUri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import io.flutter.embedding.android.FlutterFragmentActivity
import io.flutter.embedding.engine.FlutterEngine
import java.io.File
import java.io.FileOutputStream

class MainActivity : FlutterFragmentActivity(), ScannerApi {

    private lateinit var scannerLauncher: ActivityResultLauncher<IntentSenderRequest>
    private lateinit var permissionLauncher: ActivityResultLauncher<String>

    private var pendingCallback: ((Result<List<String?>>) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        scannerLauncher = registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { ar ->
            val cb = pendingCallback ?: return@registerForActivityResult
            pendingCallback = null

            val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(ar.data)
            if (scanResult == null) {
                cb(Result.success(emptyList()))
                return@registerForActivityResult
            }

            val fileUris: List<String?> = (scanResult.pages ?: emptyList())
                .mapIndexedNotNull { i, page -> persistToCache(page.imageUri, i) }

            cb(Result.success(fileUris))
        }

        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                // ✅ 권한 허용 시 바로 스캐너 시작
                startScanner()
            } else {
                // 거부 시 실패 콜백
                pendingCallback?.invoke(Result.failure(Exception("Camera permission denied")))
                pendingCallback = null
            }
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        ScannerApi.setUp(flutterEngine.dartExecutor.binaryMessenger, this)
    }

    override fun echo(message: String): String = "Android received: $message"

    override fun scan(callback: (Result<List<String?>>) -> Unit) {
        // 이전 요청 클린업
        pendingCallback?.invoke(Result.success(emptyList()))
        pendingCallback = callback

        val hasCamera = ActivityCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasCamera) {
            // ✅ 실패 콜백을 여기서 즉시 호출하지 말고, 권한 결과에 따라 처리
            permissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }

        // 권한 이미 있음 -> 바로 스캐너 시작
        startScanner()
    }

    private fun startScanner() {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .build()

        val client = GmsDocumentScanning.getClient(options)
        client.getStartScanIntent(this)
            .addOnSuccessListener { sender ->
                scannerLauncher.launch(IntentSenderRequest.Builder(sender).build())
            }
            .addOnFailureListener { e ->
                val cb = pendingCallback
                pendingCallback = null
                cb?.invoke(Result.failure(e))
            }
    }

    override fun ocr(fileUris: List<String?>, callback: (Result<List<String?>>) -> Unit) {
        val valid = fileUris.filterNotNull()
        if (valid.isEmpty()) {
            callback(Result.success(emptyList()))
            return
        }

        val recognizer = TextRecognition.getClient(
            KoreanTextRecognizerOptions.Builder().build()
        )

        val results = MutableList<String?>(valid.size) { null }
        var done = 0
        var failed: Throwable? = null

        fun finishIfComplete() {
            if (failed != null) {
                callback(Result.failure(failed!!))
                return
            }
            if (done == valid.size) {
                callback(Result.success(results))
            }
        }

        valid.forEachIndexed { index, path ->
            try {
                val uri = if (path.startsWith("file://")) path.toUri() else Uri.fromFile(File(path))
                val image = InputImage.fromFilePath(this@MainActivity, uri)
                recognizer.process(image)
                    .addOnSuccessListener { visionText ->
                        results[index] = visionText.text
                        done += 1
                        finishIfComplete()
                    }
                    .addOnFailureListener { e ->
                        failed = e
                        finishIfComplete()
                    }
            } catch (_: Throwable) {
                finishIfComplete()
            }
        }
    }

    private fun persistToCache(src: Uri, index: Int): String? {
        return try {
            val out = File(cacheDir, "scan_${System.currentTimeMillis()}_${index}.jpg")
            contentResolver.openInputStream(src).use { input ->
                if (input == null) return null
                FileOutputStream(out).use { output -> input.copyTo(output) }
            }
            "file://${out.absolutePath}"
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
