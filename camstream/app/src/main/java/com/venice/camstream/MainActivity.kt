package com.venice.camstream

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var tvStatus: TextView
    private lateinit var tvLan: TextView

    private val handler = Handler(Looper.getMainLooper())

    private val permissions: Array<String> =
        if (Build.VERSION.SDK_INT >= 33)
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS)
        else
            arrayOf(Manifest.permission.CAMERA)

    private val tick = object : Runnable {
        override fun run() {
            tvStatus.text = "status: ${StreamService.status}"
            tvLan.text = "LAN: ${StreamService.lanUrl}"
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = Prefs(this)

        tvStatus = findViewById(R.id.tvStatus)
        tvLan = findViewById(R.id.tvLan)

        val etId = findViewById<EditText>(R.id.etId)
        val etRelay = findViewById<EditText>(R.id.etRelay)
        val etToken = findViewById<EditText>(R.id.etToken)
        val etPort = findViewById<EditText>(R.id.etPort)
        val etFps = findViewById<EditText>(R.id.etFps)
        val etQuality = findViewById<EditText>(R.id.etQuality)
        val swFront = findViewById<Switch>(R.id.swFront)

        etId.setText(prefs.deviceId)
        etRelay.setText(prefs.relayUrl)
        etToken.setText(prefs.token)
        etPort.setText(prefs.port.toString())
        etFps.setText(prefs.fps.toString())
        etQuality.setText(prefs.quality.toString())
        swFront.isChecked = prefs.frontCamera

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            prefs.deviceId = etId.text.toString().ifBlank { prefs.deviceId }
            prefs.relayUrl = etRelay.text.toString()
            prefs.token = etToken.text.toString()
            prefs.port = etPort.text.toString().toIntOrNull() ?: 8080
            prefs.fps = etFps.text.toString().toIntOrNull()?.coerceIn(1, 30) ?: 12
            prefs.quality = etQuality.text.toString().toIntOrNull()?.coerceIn(20, 95) ?: 70
            prefs.frontCamera = swFront.isChecked

            askPermissionsThenStart()
        }

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopService(Intent(this, StreamService::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    private fun askPermissionsThenStart() {
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) startStream()
        else ActivityCompat.requestPermissions(this, missing.toTypedArray(), 10)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        grantResults: Array<out String>,
        results: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, grantResults, results)

        if (requestCode == 10 && results.isNotEmpty() && results.all {
                it == PackageManager.PERMISSION_GRANTED
            }) {
            startStream()
        } else if (requestCode == 10) {
            Toast.makeText(this, "Permissoes necessarias", Toast.LENGTH_LONG).show()
        }
    }

    private fun startStream() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, StreamService::class.java)
        )
        Toast.makeText(this, "Stream iniciado", Toast.LENGTH_SHORT).show()
    }
}