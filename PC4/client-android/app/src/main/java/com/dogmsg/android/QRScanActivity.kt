package com.dogmsg.android

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.CountDownTimer
import android.os.IBinder
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.widget.ViewFlipper
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.util.EnumMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * QRScanActivity (T-B10): pantalla dual de clonacion de sesion via QR.
 *
 * Modo SHOW: pide QR_GENERATE al [ChatEngine] y dibuja el token recibido como
 * bitmap QR con ZXing (solo generacion, sin AWT -- equivalente Android de
 * QRManager.java).
 *
 * Modo SCAN: usa CameraX para la vista previa y ML Kit Barcode Scanning para
 * decodificar el QR en vivo; al detectar un token, envia QR_VALIDATE.
 */
class QRScanActivity : AppCompatActivity(), ChatEngine.UiCallbacks {

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_SHOW = 0
        const val MODE_SCAN = 1
        private const val CAMERA_PERMISSION_CODE = 42
    }

    private lateinit var flipper: ViewFlipper
    private lateinit var qrImage: ImageView
    private lateinit var qrCountdown: TextView
    private lateinit var cameraPreview: PreviewView

    private var engine: ChatEngine? = null
    private var bound = false
    private var mode = MODE_SHOW
    private var cameraExecutor: ExecutorService? = null
    private var countdownTimer: CountDownTimer? = null
    private val tokenHandled = AtomicBoolean(false)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val local = binder as SocketService.LocalBinder
            engine = local.getEngine()
            engine?.setUi(this@QRScanActivity)
            bound = true
            if (mode == MODE_SHOW) engine?.requestQrToken()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            engine = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qrscan)

        mode = intent.getIntExtra(EXTRA_MODE, MODE_SHOW)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = getString(
            if (mode == MODE_SHOW) R.string.btn_show_qr else R.string.btn_scan_qr
        )
        toolbar.setNavigationOnClickListener { finish() }

        flipper = findViewById(R.id.qrFlipper)
        qrImage = findViewById(R.id.qrImage)
        qrCountdown = findViewById(R.id.qrCountdown)
        cameraPreview = findViewById(R.id.cameraPreview)
        flipper.displayedChild = mode

        findViewById<android.widget.Button>(R.id.btnToggleMode).setOnClickListener {
            mode = if (mode == MODE_SHOW) MODE_SCAN else MODE_SHOW
            flipper.displayedChild = mode
            if (mode == MODE_SHOW) {
                engine?.requestQrToken()
            } else {
                tokenHandled.set(false)
                ensureCameraPermissionAndStart()
            }
        }

        val svcIntent = Intent(this, SocketService::class.java)
        startService(svcIntent)
        bindService(svcIntent, connection, Context.BIND_AUTO_CREATE)

        if (mode == MODE_SCAN) ensureCameraPermissionAndStart()
    }

    override fun onDestroy() {
        countdownTimer?.cancel()
        cameraExecutor?.shutdown()
        if (bound) {
            engine?.setUi(null)
            unbindService(connection)
        }
        super.onDestroy()
    }

    // ----------------- Modo SHOW: generar QR propio -----------------

    private fun renderQr(token: String) {
        try {
            val size = 600
            val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java)
            hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.M
            hints[EncodeHintType.MARGIN] = 2
            hints[EncodeHintType.CHARACTER_SET] = "UTF-8"

            val matrix = QRCodeWriter().encode(token, BarcodeFormat.QR_CODE, size, size, hints)
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (y in 0 until size) {
                for (x in 0 until size) {
                    bmp.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }
            qrImage.setImageBitmap(bmp)
        } catch (e: Exception) {
            Toast.makeText(this, "Error generando QR: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startCountdown(seconds: Int) {
        countdownTimer?.cancel()
        countdownTimer = object : CountDownTimer(seconds * 1000L, 1000L) {
            override fun onTick(msLeft: Long) {
                qrCountdown.text = "Expira en ${msLeft / 1000}s"
            }
            override fun onFinish() {
                qrCountdown.text = "QR expirado, genera uno nuevo"
            }
        }.also { it.start() }
    }

    // ----------------- Modo SCAN: CameraX + ML Kit -----------------

    private fun ensureCameraPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                Toast.makeText(this, R.string.camera_denied, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startCamera() {
        if (cameraExecutor == null) cameraExecutor = Executors.newSingleThreadExecutor()
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val preview = androidx.camera.core.Preview.Builder().build().also {
                it.setSurfaceProvider(cameraPreview.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(cameraExecutor!!) { proxy -> analyzeFrame(proxy) }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                )
            } catch (e: Exception) {
                Toast.makeText(this, "No se pudo iniciar la camara: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.camera.core.ExperimentalGetImage
    private fun analyzeFrame(proxy: ImageProxy) {
        if (tokenHandled.get()) {
            proxy.close()
            return
        }
        val mediaImage = proxy.image
        if (mediaImage == null) {
            proxy.close()
            return
        }
        val image = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
        val scanner = BarcodeScanning.getClient()
        scanner.process(image)
            .addOnSuccessListener { barcodes -> handleBarcodes(barcodes) }
            .addOnCompleteListener { proxy.close() }
    }

    private fun handleBarcodes(barcodes: List<Barcode>) {
        val token = barcodes.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }?.rawValue
            ?: return
        if (tokenHandled.compareAndSet(false, true)) {
            engine?.validateQr(token)
        }
    }

    // ----------------- ChatEngine.UiCallbacks -----------------

    override fun onConnectionState(connected: Boolean, detail: String) {}
    override fun onAuthResult(ok: Boolean, userId: Long, tokenOrError: String) {}
    override fun onTextMessage(peerId: Long, isGroup: Boolean, senderId: Long, text: String, timestamp: Long) {}
    override fun onAck(sequence: Long) {}

    override fun onQrToken(token: String, expiresInSeconds: Int) {
        runOnUiThread {
            renderQr(token)
            startCountdown(expiresInSeconds)
        }
    }

    override fun onQrValidated(ok: Boolean, message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            if (ok) finish()
            else tokenHandled.set(false) // permite re-escanear si fallo
        }
    }

    override fun onHistorySynced(count: Int) {}
    override fun onAddedToGroup(groupId: Long, byUserId: Long, groupName: String) {}
    override fun onGroupCreated(groupId: Long, name: String) {}
    override fun onFileReceived(senderId: Long, receiverId: Long, isImage: Boolean, savedFile: java.io.File) {}
    override fun onSystem(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }
}