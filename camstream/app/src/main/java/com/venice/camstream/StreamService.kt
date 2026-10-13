package com.venice.camstream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class StreamService : LifecycleService() {

    private lateinit var prefs: Prefs
    private var mjpeg: MjpegServer? = null
    private var relay: RelayClient? = null
    private var provider: ProcessCameraProvider? = null
    private var wake: PowerManager.WakeLock? = null
    private val lastSent = AtomicLong(0)

    @Volatile private var latest: ByteArray? = null

    companion object {
        private const val CH = "camstream"
        private const val NOTIF_ID = 1

        @Volatile var status: String = "parado"
        @Volatile var lanUrl: String = "-"
    }

    override fun onCreate() {
        super.onCreate()

        prefs = Prefs(this)
        startForegroundCompat()
        acquireWake()

        mjpeg = MjpegServer(prefs.port) { latest }.also { it.start() }
        lanUrl = "http://${Net.localIp() ?: "IP"}:${prefs.port}"

        if (prefs.relayUrl.isNotBlank()) {
            relay = RelayClient(prefs.relayUrl, prefs.deviceId, prefs.token) { st ->
                status = st
                updateNotif()
            }.also { it.start() }
        } else {
            status = "somente LAN"
        }

        updateNotif()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startCamera()
        return START_STICKY
    }

    private fun startCamera() {
        if (provider != null) return

        val future = ProcessCameraProvider.getInstance(this)

        future.addListener({
            try {
                val p = future.get()
                provider = p

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                analysis.setAnalyzer(Executors.newSingleThreadExecutor()) { img ->
                    val jpeg = toJpeg(img, prefs.quality)
                    if (jpeg != null) {
                        latest = jpeg
                        maybeSend(jpeg)
                    }
                    img.close()
                }

                val selector = if (prefs.frontCamera)
                    CameraSelector.DEFAULT_FRONT_CAMERA
                else
                    CameraSelector.DEFAULT_BACK_CAMERA

                p.bindToLifecycle(this, selector, analysis)

            } catch (e: Exception) {
                Log.e("CamStream", "falha ao iniciar camera", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun maybeSend(jpeg: ByteArray) {
        val r = relay ?: return
        val interval = 1000L / prefs.fps.coerceIn(1, 30)
        val now = System.currentTimeMillis()
        if (now - lastSent.get() >= interval) {
            lastSent.set(now)
            r.send(jpeg)
        }
    }

    private fun toJpeg(img: ImageProxy, quality: Int): ByteArray? = try {
        val y = img.planes[0].buffer
        val u = img.planes[1].buffer
        val v = img.planes[2].buffer

        val ySize = y.remaining()
        val uSize = u.remaining()
        val vSize = v.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        y.get(nv21, 0, ySize)
        v.get(nv21, ySize, vSize)
        u.get(nv21, ySize + vSize, uSize)

        val yuv = YuvImage(nv21, ImageFormat.NV21, img.width, img.height, null)
        val out = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, img.width, img.height), quality, out)

        val bytes = out.toByteArray()
        val rotation = img.imageInfo.rotationDegrees

        if (rotation == 0) bytes else rotate(bytes, rotation)

    } catch (e: Exception) {
        Log.e("CamStream", "falha ao converter frame", e)
        null
    }

    private fun rotate(jpeg: ByteArray, degrees: Int): ByteArray {
        val src = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val dst = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        val out = ByteArrayOutputStream()
        dst.compress(Bitmap.CompressFormat.JPEG, 95, out)
        src.recycle()
        dst.recycle()
        return out.toByteArray()
    }

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CH, "CamStream", NotificationManager.IMPORTANCE_LOW)
            )
        }

        val notification = buildNotif()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildNotif(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CH)
            .setContentTitle("CamStream ativo")
            .setContentText("$status  |  $lanUrl")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private fun updateNotif() {
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotif())
        } catch (e: Exception) {
        }
    }

    private fun acquireWake() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "camstream:lock")
            .also { it.acquire(12 * 60 * 60 * 1000L) }
    }

    override fun onDestroy() {
        mjpeg?.stop()
        relay?.stop()

        try {
            wake?.release()
        } catch (e: Exception) {
        }

        provider?.unbindAll()
        status = "parado"

        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)
}