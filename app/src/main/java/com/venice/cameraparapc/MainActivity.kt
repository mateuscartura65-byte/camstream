package com.venice.cameraparapc

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var raiz: FrameLayout
    private lateinit var tvStatus: TextView
    private lateinit var tvContador: TextView
    private lateinit var btnVermelho: Button

    private var cliques = 0
    private val reqPerm = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        raiz = findViewById(R.id.raiz)
        tvStatus = findViewById(R.id.tvStatus)
        tvContador = findViewById(R.id.tvContador)
        btnVermelho = findViewById(R.id.btnVermelho)

        btnVermelho.setOnClickListener { v -> soltarMaisUm(v) }

        pedirPermissoes()
    }

    private fun pedirPermissoes() {
        val faltando = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            faltando.add(Manifest.permission.CAMERA)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            faltando.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (faltando.isEmpty()) iniciarServico()
        else ActivityCompat.requestPermissions(this, faltando.toTypedArray(), reqPerm)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == reqPerm &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            iniciarServico()
        } else {
            tvStatus.text = "sem camera"
        }
    }

    private fun iniciarServico() {
        tvStatus.text = "no ar"
        ContextCompat.startForegroundService(this, Intent(this, CameraService::class.java))
    }

    private fun soltarMaisUm(v: View) {
        cliques++
        tvContador.text = cliques.toString()

        val t = TextView(this)
        t.text = "+1"
        t.setTextColor(Color.WHITE)
        t.textSize = 30f
        t.setTypeface(null, Typeface.BOLD)
        t.setShadowLayer(8f, 0f, 0f, Color.BLACK)

        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        lp.leftMargin = (v.x + v.width / 2f).toInt() - 25 + Random.nextInt(-50, 50)
        lp.topMargin = (v.y + v.height / 2f).toInt() - 30
        t.layoutParams = lp
        raiz.addView(t)

        t.animate()
            .translationY(-190f)
            .alpha(0f)
            .setDuration(900)
            .withEndAction { raiz.removeView(t) }
            .start()
    }
}
