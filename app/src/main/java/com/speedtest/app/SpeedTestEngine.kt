package com.speedtest.app

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

data class SpeedTestResult(
    val ping: Long,         // ms
    val jitter: Long,       // ms
    val downloadSpeed: Float,  // Mbps
    val uploadSpeed: Float,    // Mbps
    val serverName: String,
    val isp: String
)

data class SpeedSample(val speedMbps: Float, val timestamp: Long)

class SpeedTestEngine {

    private val TAG = "SpeedTestEngine"

    // Multiple CDN endpoints for real-world download testing
    private val downloadUrls = listOf(
        "https://speed.cloudflare.com/__down?bytes=25000000",
        "https://proof.ovh.net/files/10Mb.dat",
        "https://speed.hetzner.de/10MB.bin",
        "https://ash-speed.hetzner.com/10MB.bin"
    )

    // Upload endpoint
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
            // Step 1: Ping test
            Log.d(TAG, "Starting ping test...")
            val (ping, jitter) = measurePing("speed.cloudflare.com", 80)
            withContext(Dispatchers.Main) {
                listener.onPingResult(ping, jitter)
            }

            // Step 2: Download test
            Log.d(TAG, "Starting download test...")
            val downloadSpeed = measureDownloadSpeed { speed, progress ->
                listener.onDownloadProgress(speed, progress)
            }

            // Step 3: Upload test
            Log.d(TAG, "Starting upload test...")
            val uploadSpeed = measureUploadSpeed { speed, progress ->
                listener.onUploadProgress(speed, progress)
            }

            // Step 4: Get server/ISP info
            val ispInfo = getIspInfo()

            val result = SpeedTestResult(
                ping = ping,
                jitter = jitter,
                downloadSpeed = downloadSpeed,
                uploadSpeed = uploadSpeed,
                serverName = ispInfo.first,
                isp = ispInfo.second
            )

            withContext(Dispatchers.Main) {
                listener.onComplete(result)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Speed test error", e)
            withContext(Dispatchers.Main) {
                listener.onError(e.message ?: "Unknown error occurred")
            }
        }
    }

    private fun measurePing(host: String, port: Int): Pair<Long, Long> {
        val pings = mutableListOf<Long>()
        val attempts = 5

        repeat(attempts) {
            try {
                val start = System.currentTimeMillis()
                val socket = Socket()
                socket.connect(InetSocketAddress(host, port), 5000)
                val elapsed = System.currentTimeMillis() - start
                pings.add(elapsed)
                socket.close()
                Thread.sleep(100)
            } catch (e: Exception) {
                // Try ICMP fallback
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
            // HTTP-based ping fallback
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
            val diffs = pings.zipWithNext { a, b -> Math.abs(b - a) }
            diffs.average().roundToLong()
        } else 0L

        return Pair(avgPing, jitter)
    }

    private suspend fun measureDownloadSpeed(
        onProgress: suspend (speedMbps: Float, progress: Int) -> Unit
    ): Float = withContext(Dispatchers.IO) {
        val samples = mutableListOf<SpeedSample>()
        val testDurationMs = 10000L  // 10 seconds
        val startTime = System.currentTimeMillis()
        var totalBytesDownloaded = 0L

        // Try multiple URLs for more accurate measurement
        val urlsToTry = downloadUrls.take(2)

        for (url in urlsToTry) {
            if (System.currentTimeMillis() - startTime >= testDurationMs) break
            try {
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val body = response.body ?: return@use
                    val buffer = ByteArray(65536) // 64KB chunks
                    val inputStream = body.byteStream()
                    var bytesRead: Int

                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        totalBytesDownloaded += bytesRead

                        val elapsed = System.currentTimeMillis() - startTime
                        if (elapsed > 0) {
                            val speedBps = totalBytesDownloaded * 8.0 / (elapsed / 1000.0)
                            val speedMbps = (speedBps / 1_000_000).toFloat()
                            samples.add(SpeedSample(speedMbps, elapsed))

                            val progress = ((elapsed.toFloat() / testDurationMs) * 100).toInt().coerceIn(0, 100)
                            withContext(Dispatchers.Main) {
                                onProgress(speedMbps, progress)
                            }
                        }

                        if (System.currentTimeMillis() - startTime >= testDurationMs) break
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "Download from $url failed: ${e.message}")
            }
        }

        // Use the median of the last 60% of samples for stability
        if (samples.isEmpty()) return@withContext 0f
        val stableSamples = samples.drop((samples.size * 0.4).toInt())
        stableSamples.map { it.speedMbps }.average().toFloat()
    }

    private suspend fun measureUploadSpeed(
        onProgress: suspend (speedMbps: Float, progress: Int) -> Unit
    ): Float = withContext(Dispatchers.IO) {
        val uploadSizeBytes = 10 * 1024 * 1024  // 10MB
        val data = ByteArray(uploadSizeBytes) { (it % 256).toByte() }
        val samples = mutableListOf<SpeedSample>()
        val startTime = System.currentTimeMillis()
        val testDurationMs = 8000L  // 8 seconds

        // Use chunked upload to track progress
        val chunkSize = 131072  // 128KB chunks
        val chunks = data.asSequence().chunked(chunkSize).map { it.toByteArray() }.toList()
        var totalUploaded = 0L

        try {
            for ((idx, chunk) in chunks.withIndex()) {
                if (System.currentTimeMillis() - startTime >= testDurationMs) break

                val requestBody = object : RequestBody() {
                    override fun contentType() = MediaType.parse("application/octet-stream")
                    override fun contentLength() = chunk.size.toLong()
                    override fun writeTo(sink: okio.BufferedSink) {
                        sink.write(chunk)
                    }
                }

                val request = Request.Builder()
                    .url(uploadUrl)
                    .post(requestBody)
                    .build()

                try {
                    client.newCall(request).execute().use { response ->
                        totalUploaded += chunk.size
                        val elapsed = System.currentTimeMillis() - startTime
                        if (elapsed > 0) {
                            val speedBps = totalUploaded * 8.0 / (elapsed / 1000.0)
                            val speedMbps = (speedBps / 1_000_000).toFloat()
                            samples.add(SpeedSample(speedMbps, elapsed))

                            val progress = ((idx.toFloat() / chunks.size) * 100).toInt().coerceIn(0, 100)
                            withContext(Dispatchers.Main) {
                                onProgress(speedMbps, progress)
                            }
                        }
                    }
                } catch (e: IOException) {
                    Log.w(TAG, "Upload chunk failed: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Upload test error", e)
        }

        if (samples.isEmpty()) return@withContext 0f
        val stableSamples = samples.drop((samples.size * 0.3).toInt())
        stableSamples.map { it.speedMbps }.average().toFloat()
    }

    private fun getIspInfo(): Pair<String, String> {
        return try {
            val request = Request.Builder()
                .url("https://speed.cloudflare.com/meta")
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return Pair("Cloudflare", "Unknown ISP")
            val gson = com.google.gson.JsonParser.parseString(body).asJsonObject
            val city = gson.get("city")?.asString ?: "Unknown"
            val country = gson.get("country")?.asString ?: ""
            val isp = gson.get("asOrganization")?.asString ?: "Unknown ISP"
            Pair("$city, $country", isp)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get ISP info: ${e.message}")
            Pair("Cloudflare CDN", "Unknown ISP")
        }
    }

    fun cancel() {
        client.dispatcher.cancelAll()
    }
}
