package com.venice.cameraparapc

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import android.util.Base64
import android.util.Size
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var tvStatus: TextView
    private lateinit var etCode: EditText
    private lateinit var btnVermelho: Button

    private val executor = Executors.newSingleThreadExecutor()
    private val ocupado = AtomicBoolean(false)

    @Volatile private var mqtt: MqttClient? = null
    @Volatile private var topico = ""
    private var ultimoEnvio = 0L

    private val intervaloMs = 130L
    private val broker = "tcp://broker.hivemq.com:1883"
    private val baseTopico = "venice/cam/"
    private val larguraMax = 640
    private val reqCamera = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        previewView = findViewById(R.id.preview)
        tvStatus = findViewById(R.id.tvStatus)
        etCode = findViewById(R.id.etCode)
        btnVermelho = findViewById(R.id.btnVermelho)

        // Botao vermelho: nao faz nada
        btnVermelho.setOnClickListener { }

        etCode.setText((1000..9999).random().toString())

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            iniciarTudo()
        } else {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), reqCamera)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == reqCamera && grantResults.isNotEmpty()
            && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            iniciarTudo()
        } else {
            tvStatus.text = "Camera negada"
        }
    }

    private fun iniciarTudo() {
        val codigo = etCode.text.toString().trim().ifEmpty { "1234" }
        topico = baseTopico + codigo
        tvStatus.text = "Sala $codigo - ligando..."
        iniciarCamera()
        conectarMqtt()
    }

    private fun iniciarCamera() {
        val futuro = ProcessCameraProvider.getInstance(this)
        futuro.addListener({
            try {
                val provider = futuro.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analise = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetResolution(Size(640, 480))
                    .build()
                analise.setAnalyzer(executor) { proxy -> enviarFrame(proxy) }
                provider.unbindAll()
                provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analise)
            } catch (e: Exception) {
                tvStatus.text = "Erro camera: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun conectarMqtt() {
        Thread {
            try {
                val id = "cam_" + System.currentTimeMillis()
                val c = MqttClient(broker, id, MemoryPersistence())
                val opt = MqttConnectOptions().apply {
                    isCleanSession = true
                    isAutomaticReconnect = true
                    connectionTimeout = 10
                    keepAliveInterval = 30
                }
                c.connect(opt)
                mqtt = c
                runOnUiThread { tvStatus.text = "No ar - sala $topico" }
            } catch (e: Exception) {
                runOnUiThread { tvStatus.text = "Falha conexao: ${e.message}" }
            }
        }.start()
    }

    private fun enviarFrame(image: ImageProxy) {
        val agora = System.currentTimeMillis()
        if (agora - ultimoEnvio < intervaloMs || ocupado.get()) {
            image.close()
            return
        }
        ocupado.set(true)
        ultimoEnvio = agora
        try {
            var bmp = converter(image) ?: return
            val rot = image.imageInfo.rotationDegrees
            if (rot != 0) {
                val m = Matrix().apply { postRotate(rot.toFloat()) }
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
            if (bmp.width > larguraMax) {
                val h = bmp.height * larguraMax / bmp.width
                bmp = Bitmap.createScaledBitmap(bmp, larguraMax, h, true)
            }
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 55, out)
            publicar(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
        } catch (e: Exception) {
            // ignora frame ruim
        } finally {
            image.close()
            ocupado.set(false)
        }
    }

    private fun converter(image: ImageProxy): Bitmap? {
        val y = image.planes[0].buffer
        val u = image.planes[1].buffer
        val v = image.planes[2].buffer
        val ySize = y.remaining(); val uSize = u.remaining(); val vSize = v.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        y.get(nv21, 0, ySize)
        v.get(nv21, ySize, vSize)
        u.get(nv21, ySize + vSize, uSize)
        val yuv = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, image.width, image.height), 70, out)
        val bytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun publicar(base64: String) {
        val c = mqtt ?: return
        if (!c.isConnected) return
        val msg = MqttMessage(base64.toByteArray())
        msg.qos = 0
        msg.isRetained = true
        c.publish(topico, msg)
    }

    override fun onDestroy() {
        try { mqtt?.disconnect() } catch (_: Exception) {}
        try { mqtt?.close() } catch (_: Exception) {}
        executor.shutdown()
        super.onDestroy()
    }
}