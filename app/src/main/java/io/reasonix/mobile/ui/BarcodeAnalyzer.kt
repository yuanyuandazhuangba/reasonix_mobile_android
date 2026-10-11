package io.reasonix.mobile.ui

import android.annotation.SuppressLint
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.io.Closeable

class BarcodeAnalyzer(
    private val onBarcodeDetected: (String) -> Unit
) : ImageAnalysis.Analyzer, Closeable {

    private val options = BarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
        .build()

    private val scanner = BarcodeScanning.getClient(options)
    private var isScanning = true

    /** 释放 ML Kit 扫描器，避免 Activity 反复进出时泄漏原生资源。 */
    override fun close() {
        scanner.close()
    }

    fun resume() {
        isScanning = true
    }

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        if (!isScanning) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    if (!isScanning) return@addOnSuccessListener
                    for (barcode in barcodes) {
                        barcode.rawValue?.let { value ->
                            if (value.isNotEmpty()) {
                                isScanning = false
                                onBarcodeDetected(value)
                                return@addOnSuccessListener
                            }
                        }
                    }
                }
                .addOnFailureListener {
                    // 忽略单帧分析异常
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }
}
