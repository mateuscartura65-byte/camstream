package com.venice.camstream

import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket

class MjpegServer(
    private val port: Int,
    private val frame: () -> ByteArray?
) {

    @Volatile private var running = true
    private var server: ServerSocket? = null

    fun start() {
        Thread {
            try {
                val s = ServerSocket(port)
                server = s
                while (running) {
                    val client = s.accept()
                    Thread { serve(client) }.apply { isDaemon = true }.start()
                }
            } catch (e: Exception) {
            }
        }.apply { isDaemon = true }.start()
    }

    private fun serve(client: Socket) {
        try {
            val out = client.getOutputStream()

            try {
                client.getInputStream().read(ByteArray(2048))
            } catch (e: Exception) {
            }

            out.write((
                "HTTP/1.1 200 OK\r\n" +
                "Content-Type: multipart/x-mixed-replace; boundary=--frameboundary\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Connection: close\r\n" +
                "\r\n"
            ).toByteArray())
            out.flush()

            while (running && !client.isClosed) {
                val jpeg = frame()
                if (jpeg == null) {
                    Thread.sleep(30)
                    continue
                }
                writeFrame(out, jpeg)
            }
        } catch (e: Exception) {
        } finally {
            try {
                client.close()
            } catch (e: Exception) {
            }
        }
    }

    private fun writeFrame(out: OutputStream, jpeg: ByteArray) {
        out.write("--frameboundary\r\n".toByteArray())
        out.write("Content-Type: image/jpeg\r\n".toByteArray())
        out.write("Content-Length: ${jpeg.size}\r\n\r\n".toByteArray())
        out.write(jpeg)
        out.write("\r\n".toByteArray())
        out.flush()
    }

    fun stop() {
        running = false
        try {
            server?.close()
        } catch (e: Exception) {
        }
    }
}