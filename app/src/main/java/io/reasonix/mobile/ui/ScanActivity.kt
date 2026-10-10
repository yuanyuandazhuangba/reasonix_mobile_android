package io.reasonix.mobile.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import io.reasonix.mobile.R
import io.reasonix.mobile.databinding.ActivityScanBinding
import io.reasonix.mobile.model.PairingInfo
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScanBinding
    private lateinit var cameraExecutor: ExecutorService
    private var camera: Camera? = null
    private var isTorchOn = false
    private var barcodeAnalyzer: BarcodeAnalyzer? = null

    companion object {
        const val EXTRA_SCANNED_URL = "extra_scanned_url"
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startCamera()
        } else {
            Toast.makeText(this, R.string.camera_permission_required, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        setupViews()
        checkCameraPermission()
    }

    private fun setupViews() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnTorch.setOnClickListener {
            toggleTorch()
        }
    }

    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                }

            barcodeAnalyzer = BarcodeAnalyzer { qrRawValue ->
                runOnUiThread {
                    onBarcodeScanned(qrRawValue)
                }
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, barcodeAnalyzer!!)
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )
            } catch (exc: Exception) {
                Toast.makeText(this, "相机初始化失败: ${exc.message}", Toast.LENGTH_SHORT).show()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun toggleTorch() {
        camera?.let {
            if (it.cameraInfo.hasFlashUnit()) {
                isTorchOn = !isTorchOn
                it.cameraControl.enableTorch(isTorchOn)
                binding.btnTorch.setImageResource(
                    if (isTorchOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off
                )
            } else {
                Toast.makeText(this, R.string.no_flash_unit, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun onBarcodeScanned(rawValue: String) {
        // 扫码成功震动反馈
        vibratePhone()

        val pairingInfo = PairingInfo.parse(rawValue)
        if (pairingInfo == null) {
            Toast.makeText(this, R.string.invalid_qr_code, Toast.LENGTH_SHORT).show()
            // 恢复扫码
            binding.root.postDelayed({
                barcodeAnalyzer?.resume()
            }, 1500)
            return
        }

        val resultIntent = Intent().apply {
            putExtra(EXTRA_SCANNED_URL, pairingInfo.rawUrl)
        }
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }

    private fun vibratePhone() {
        val vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator
        if (vibrator != null && vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(80)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
