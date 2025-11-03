package com.pass.scannote.scan_note

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import io.flutter.embedding.android.FlutterFragmentActivity
import io.flutter.embedding.engine.FlutterEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class MainActivity : FlutterFragmentActivity(), ScannerApi {

    private lateinit var scannerLauncher: ActivityResultLauncher<IntentSenderRequest>
    private lateinit var cameraPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var writePermissionLauncher: ActivityResultLauncher<String>
    private data class PendingWrite(val filename: String, val bytes: ByteArray, val callback: (Result<String>) -> Unit) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as PendingWrite

            if (filename != other.filename) return false
            if (!bytes.contentEquals(other.bytes)) return false
            if (callback != other.callback) return false

            return true
        }

        override fun hashCode(): Int {
            var result = filename.hashCode()
            result = 31 * result + bytes.contentHashCode()
            result = 31 * result + callback.hashCode()
            return result
        }
    }
    private var pendingWrite: PendingWrite? = null

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

        cameraPermissionLauncher = registerForActivityResult(
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

        writePermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            val pending = pendingWrite ?: return@registerForActivityResult
            pendingWrite = null
            if (granted) {
                try {
                    val uri = saveToDownloadsInternal(pending.filename, pending.bytes)
                    pending.callback(Result.success(uri.toString()))
                } catch (t: Throwable) {
                    pending.callback(Result.failure(t))
                }
            } else {
                pending.callback(Result.failure(Exception("WRITE_EXTERNAL_STORAGE denied")))
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
            // 실패 콜백을 여기서 즉시 호출하지 말고, 권한 결과에 따라 처리
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }

        // 권한 이미 있음 -> 바로 스캐너 시작
        startScanner()
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

    override fun saveToDownloads(bytes: ByteArray, filename: String, callback: (Result<String>) -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val uri = saveToDownloadsInternal(filename, bytes)
                    // 메인에서 콜백
                    launch(Dispatchers.Main) { callback(Result.success(uri.toString())) }
                } catch (t: Throwable) {
                    launch(Dispatchers.Main) { callback(Result.failure(t)) }
                }
            } else {
                val granted = ActivityCompat.checkSelfPermission(
                    this@MainActivity, Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED

                if (granted) {
                    try {
                        val uri = saveToDownloadsInternal(filename, bytes)
                        // 메인에서 콜백
                        launch(Dispatchers.Main) { callback(Result.success(uri.toString())) }
                    } catch (t: Throwable) {
                        launch(Dispatchers.Main) { callback(Result.failure(t)) }
                    }
                } else {
                    // 권한 요청 → 결과에서 이어서 저장
                    pendingWrite = PendingWrite(filename, bytes, callback)
                    launch(Dispatchers.Main) {
                        writePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    }
                }
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

    private fun saveToDownloadsInternal(filename: String, bytes: ByteArray): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10(API 29)+ → MediaStore Downloads 컬렉션 사용
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download") // Scoped Storage 경로
            }

            val extUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val newUri = contentResolver.insert(extUri, values)
                ?: throw IllegalStateException("Insert to MediaStore failed")

            contentResolver.openOutputStream(newUri, "w").use { os ->
                requireNotNull(os) { "OutputStream null" }
                os.write(bytes)
                os.flush()
            }
            newUri
        } else {
            // Android 9 이하 → 직접 경로로 저장
            val downloads = File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                ),
                filename
            )
            FileOutputStream(downloads).use { it.write(bytes) }
            Uri.fromFile(downloads)
        }
    }
}
