package com.speedtest.app

import android.util.Log
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

data class SpeedTestResult(
    val ping: Long,
    val jitter: Long,
    val downloadSpeed: Float,
    val uploadSpeed: Float,
    val serverName: String,
    val isp: String
)

data class SpeedSample(val speedMbps: Float, val timestamp: Long)

class SpeedTestEngine {

    private val TAG = "SpeedTestEngine"

    private val downloadUrls = listOf(
        "https://speed.cloudflare.com/__down?bytes=25000000",
        "https://proof.ovh.net/files/10Mb.dat",
        "https://speed.hetzner.de/10MB.bin"
    )

    private val uploadUrl = "https://speed.cloudflare.com/__up"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    interface ProgressListener {
        fun onPingResult(ping: Long, jitter: Long)
        fun onDownloadProgress(speedMbps: Float, progressPercent: Int)
        fun onUploadProgress(speedMbps: Float, progressPercent: Int)
        fun onComplete(result: SpeedTestResult)
        fun onError(error: String)
    }

    suspend fun runTest(listener: ProgressListener) = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting ping test...")
            val (ping, jitter) = measurePing("speed.cloudflare.com", 80)
            withContext(Dispatchers.Main) { listener.onPingResult(ping, jitter) }

            Log.d(TAG, "Starting download test...")
            val downloadSpeed = measureDownloadSpeed { speed, progress ->
                listener.onDownloadProgress(speed, progress)
            }

            Log.d(TAG, "Starting upload test...")
            val uploadSpeed = measureUploadSpeed { speed, progress ->
                listener.onUploadProgress(speed, progress)
            }

            val ispInfo = getIspInfo()

            val result = SpeedTestResult(
                ping = ping,
                jitter = jitter,
                downloadSpeed = downloadSpeed,
                uploadSpeed = uploadSpeed,
                serverName = ispInfo.first,
                isp = ispInfo.second
            )
            withContext(Dispatchers.Main) { listener.onComplete(result) }

        } catch (e: Exception) {
            Log.e(TAG, "Speed test error", e)
            withContext(Dispatchers.Main) {
                listener.onError(e.message ?: "Unknown error occurred")
            }
        }
    }

    private fun measurePing(host: String, port: Int): Pair<Long, Long> {
        val pings = mutableListOf<Long>()

        repeat(5) {
            try {
                val start = System.currentTimeMillis()
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), 5000)
                }
                pings.add(System.currentTimeMillis() - start)
                Thread.sleep(100)
            } catch (e: Exception) {
                try {
                    val start = System.currentTimeMillis()
                    InetAddress.getByName(host).isReachable(3000)
                    pings.add(System.currentTimeMillis() - start)
                } catch (e2: Exception) {
                    Log.w(TAG, "Ping attempt failed: ${e2.message}")
                }
            }
        }

        if (pings.isEmpty()) {
            try {
                val start = System.currentTimeMillis()
                val req = Request.Builder()
                    .url("https://speed.cloudflare.com/__down?bytes=0")
                    .build()
                client.newCall(req).execute().use {
                    pings.add(System.currentTimeMillis() - start)
                }
            } catch (e: Exception) {
                Log.e(TAG, "HTTP ping fallback failed", e)
            }
        }

        if (pings.isEmpty()) return Pair(0L, 0L)

        val avgPing = pings.average().roundToLong()
        val jitter = if (pings.size > 1) {
            pings.zipWithNext { a, b -> kotlin.math.abs(b - a) }.average().roundToLong()
        } else 0L

        return Pair(avgPing, jitter)
    }

    private suspend fun measureDownloadSpeed(
        onProgress: suspend (speedMbps: Float, progress: Int) -> Unit
    ): Float = withContext(Dispatchers.IO) {
        val samples = mutableListOf<SpeedSample>()
        val testDurationMs = 10_000L
        val startTime = System.currentTimeMillis()
        var totalBytes = 0L

        for (url in downloadUrls.take(2)) {
            if (System.currentTimeMillis() - startTime >= testDurationMs) break
            try {
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val body = response.body ?: return@use
                    val buffer = ByteArray(65536)
                    val stream = body.byteStream()
                    var bytesRead: Int

                    while (stream.read(buffer).also { bytesRead = it } != -1) {
                        totalBytes += bytesRead
                        val elapsed = System.currentTimeMillis() - startTime
                        if (elapsed > 0) {
                            val speedMbps = (totalBytes * 8.0 / (elapsed / 1000.0) / 1_000_000).toFloat()
                            samples.add(SpeedSample(speedMbps, elapsed))
                            val progress = ((elapsed.toFloat() / testDurationMs) * 100).toInt().coerceIn(0, 100)
                            withContext(Dispatchers.Main) { onProgress(speedMbps, progress) }
                        }
                        if (System.currentTimeMillis() - startTime >= testDurationMs) break
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "Download from $url failed: ${e.message}")
            }
        }

        if (samples.isEmpty()) return@withContext 0f
        samples.drop((samples.size * 0.4).toInt()).map { it.speedMbps }.average().toFloat()
    }

    private suspend fun measureUploadSpeed(
        onProgress: suspend (speedMbps: Float, progress: Int) -> Unit
    ): Float = withContext(Dispatchers.IO) {
        val chunkSize = 256 * 1024          // 256 KB per request
        val totalChunks = 20                 // 20 × 256KB = 5 MB total
        val chunk = ByteArray(chunkSize) { (it % 256).toByte() }
        val mediaType = "application/octet-stream".toMediaType()
        val samples = mutableListOf<SpeedSample>()
        val startTime = System.currentTimeMillis()
        val testDurationMs = 8_000L
        var totalUploaded = 0L

        for (idx in 0 until totalChunks) {
            if (System.currentTimeMillis() - startTime >= testDurationMs) break

            val requestBody = object : RequestBody() {
                override fun contentType() = mediaType
                override fun contentLength() = chunk.size.toLong()
                override fun writeTo(sink: BufferedSink) { sink.write(chunk) }
            }

            val request = Request.Builder()
                .url(uploadUrl)
                .post(requestBody)
                .build()

            try {
                client.newCall(request).execute().use {
                    totalUploaded += chunk.size
                    val elapsed = System.currentTimeMillis() - startTime
                    if (elapsed > 0) {
                        val speedMbps = (totalUploaded * 8.0 / (elapsed / 1000.0) / 1_000_000).toFloat()
                        samples.add(SpeedSample(speedMbps, elapsed))
                        val progress = ((idx.toFloat() / totalChunks) * 100).toInt().coerceIn(0, 100)
                        withContext(Dispatchers.Main) { onProgress(speedMbps, progress) }
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "Upload chunk $idx failed: ${e.message}")
            }
        }

        if (samples.isEmpty()) return@withContext 0f
        samples.drop((samples.size * 0.3).toInt()).map { it.speedMbps }.average().toFloat()
    }

    private fun getIspInfo(): Pair<String, String> {
        return try {
            val request = Request.Builder()
                .url("https://speed.cloudflare.com/meta")
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return Pair("Cloudflare", "Unknown ISP")
                val json = JsonParser.parseString(body).asJsonObject
                val city = json.get("city")?.asString ?: "Unknown"
                val country = json.get("country")?.asString ?: ""
                val isp = json.get("asOrganization")?.asString ?: "Unknown ISP"
                Pair("$city, $country", isp)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get ISP info: ${e.message}")
            Pair("Cloudflare CDN", "Unknown ISP")
        }
    }

    fun cancel() {
        client.dispatcher.cancelAll()
    }
}
