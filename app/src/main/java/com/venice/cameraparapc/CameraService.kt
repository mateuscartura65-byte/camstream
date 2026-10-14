package com.venice.cameraparapc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Build
import android.os.PowerManager
import android.util.Base64
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class CameraService : LifecycleService() {

    companion object {
        private const val CANAL = "camstream"
        private const val NOTIF_ID = 42
        private const val BROKER = "tcp://broker.hivemq.com:1883"
        private const val TOPICO = "venice/cam/pc"
        private const val LARGURA_MAX = 640
        private const val INTERVALO_MS = 130L
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val ocupado = AtomicBoolean(false)

    @Volatile private var mqtt: MqttClient? = null
    private var ultimoEnvio = 0L
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        criarCanal()
        startForeground(NOTIF_ID, montarNotificacao())
        segurarCpu()
        iniciarCamera()
        conectarMqtt()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    private fun criarCanal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CANAL, "Transmissao da camera", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun montarNotificacao(): Notification =
        NotificationCompat.Builder(this, CANAL)
            .setContentTitle("CameraParaPC")
            .setContentText("Transmitindo para o PC")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun segurarCpu() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "camstream::camera").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun iniciarCamera() {
        val futuro = ProcessCameraProvider.getInstance(this)
        futuro.addListener({
            try {
                val provider = futuro.get()
                val analise = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetResolution(Size(640, 480))
                    .build()
                analise.setAnalyzer(executor) { proxy -> enviarFrame(proxy) }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analise)
            } catch (e: Exception) {
                // camera indisponivel
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun conectarMqtt() {
        Thread {
            try {
                val id = "cam_" + System.currentTimeMillis()
                val c = MqttClient(BROKER, id, MemoryPersistence())
                val opt = MqttConnectOptions().apply {
                    isCleanSession = true
                    isAutomaticReconnect = true
                    connectionTimeout = 10
                    keepAliveInterval = 30
                }
                c.connect(opt)
                mqtt = c
            } catch (e: Exception) {
                // offline, o automaticReconnect cuida
            }
        }.start()
    }

    private fun enviarFrame(image: ImageProxy) {
        val agora = System.currentTimeMillis()
        if (agora - ultimoEnvio < INTERVALO_MS || ocupado.get()) {
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
            if (bmp.width > LARGURA_MAX) {
                val h = bmp.height * LARGURA_MAX / bmp.width
                bmp = Bitmap.createScaledBitmap(bmp, LARGURA_MAX, h, true)
            }
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 55, out)
            publicar(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
        } catch (e: Exception) {
            // frame ruim, ignora
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
        c.publish(TOPICO, msg)
    }

    override fun onDestroy() {
        try { mqtt?.disconnect() } catch (_: Exception) {}
        try { mqtt?.close() } catch (_: Exception) {}
        try { wakeLock?.release() } catch (_: Exception) {}
        executor.shutdown()
        super.onDestroy()
    }
}
