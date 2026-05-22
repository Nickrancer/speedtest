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
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

data class NetworkInfo(
    val publicIp: String,
    val isp: String,
    val city: String,
    val country: String,
    val asn: String
)

data class SpeedTestResult(
    val ping: Long,
    val jitter: Long,
    val downloadSpeed: Float,
    val uploadSpeed: Float,
    val networkInfo: NetworkInfo
)

data class SpeedSample(val speedMbps: Float, val timestamp: Long)

class SpeedTestEngine {

    private val TAG = "SpeedTestEngine"

    // 100 MB for serious download test — Cloudflare will stream as much as needed
    private val downloadUrls = listOf(
        "https://speed.cloudflare.com/__down?bytes=100000000",
        "https://speed.cloudflare.com/__down?bytes=25000000",
        "https://proof.ovh.net/files/10Mb.dat"
    )

    private val uploadUrl   = "https://speed.cloudflare.com/__up"
    private val metaUrl     = "https://speed.cloudflare.com/meta"
    private val ipFallback  = "https://api.ipify.org"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()

    interface ProgressListener {
        fun onNetworkInfo(info: NetworkInfo)
        fun onPingResult(ping: Long, jitter: Long)
        fun onDownloadProgress(speedMbps: Float, progressPercent: Int)
        fun onUploadProgress(speedMbps: Float, progressPercent: Int)
        fun onComplete(result: SpeedTestResult)
        fun onError(error: String)
    }

    suspend fun runTest(listener: ProgressListener) = withContext(Dispatchers.IO) {
        try {
            // Step 1: Fetch IP + ISP info first so UI shows it immediately
            Log.d(TAG, "Fetching network info...")
            val info = fetchNetworkInfo()
            withContext(Dispatchers.Main) { listener.onNetworkInfo(info) }

            // Step 2: Ping
            Log.d(TAG, "Starting ping test...")
            val (ping, jitter) = measurePing("speed.cloudflare.com", 443)
            withContext(Dispatchers.Main) { listener.onPingResult(ping, jitter) }

            // Step 3: Download
            Log.d(TAG, "Starting download test...")
            val downloadSpeed = measureDownloadSpeed { speed, progress ->
                listener.onDownloadProgress(speed, progress)
            }

            // Step 4: Upload
            Log.d(TAG, "Starting upload test...")
            val uploadSpeed = measureUploadSpeed { speed, progress ->
                listener.onUploadProgress(speed, progress)
            }

            val result = SpeedTestResult(
                ping = ping,
                jitter = jitter,
                downloadSpeed = downloadSpeed,
                uploadSpeed = uploadSpeed,
                networkInfo = info
            )
            withContext(Dispatchers.Main) { listener.onComplete(result) }

        } catch (e: Exception) {
            Log.e(TAG, "Speed test error", e)
            withContext(Dispatchers.Main) {
                listener.onError(e.message ?: "Unknown error")
            }
        }
    }

    // ─── Network Info (IP + ISP + location) ────────────────────────────────────

    private fun fetchNetworkInfo(): NetworkInfo {
        // Primary: Cloudflare meta (returns IP, ASN, org, city, country)
        try {
            val request = Request.Builder().url(metaUrl).build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: throw IOException("Empty body")
                val json = JsonParser.parseString(body).asJsonObject
                val ip      = json.get("ip")?.asString             ?: fetchPublicIpFallback()
                val isp     = json.get("asOrganization")?.asString ?: "Unknown ISP"
                val city    = json.get("city")?.asString           ?: ""
                val country = json.get("country")?.asString        ?: ""
                val asn     = json.get("asn")?.let {
                    if (it.isJsonPrimitive) "AS${it.asString}" else ""
                } ?: ""
                return NetworkInfo(
                    publicIp = ip,
                    isp      = isp,
                    city     = city,
                    country  = country,
                    asn      = asn
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cloudflare meta failed: ${e.message}")
        }

        // Fallback: ipify for IP only
        val ip = fetchPublicIpFallback()
        return NetworkInfo(
            publicIp = ip,
            isp      = "Unknown ISP",
            city     = "",
            country  = "",
            asn      = ""
        )
    }

    private fun fetchPublicIpFallback(): String {
        return try {
            val req = Request.Builder().url(ipFallback).build()
            client.newCall(req).execute().use { it.body?.string()?.trim() ?: "Unknown" }
        } catch (e: Exception) {
            Log.w(TAG, "IP fallback failed: ${e.message}")
            "Unknown"
        }
    }

    // ─── Ping / Jitter ─────────────────────────────────────────────────────────

    private fun measurePing(host: String, port: Int): Pair<Long, Long> {
        val pings = mutableListOf<Long>()

        // Warm-up connection first
        try { Socket().use { it.connect(InetSocketAddress(host, port), 5000) } }
        catch (e: Exception) { /* ignore warm-up */ }

        repeat(6) {
            try {
                val start = System.currentTimeMillis()
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), 5000)
                }
                pings.add(System.currentTimeMillis() - start)
                Thread.sleep(150)
            } catch (e: Exception) {
                // HTTP-based RTT fallback
                try {
                    val start = System.currentTimeMillis()
                    val req = Request.Builder()
                        .url("https://speed.cloudflare.com/__down?bytes=0")
                        .build()
                    client.newCall(req).execute().use {}
                    pings.add(System.currentTimeMillis() - start)
                } catch (e2: Exception) {
                    Log.w(TAG, "Ping attempt failed: ${e2.message}")
                }
            }
        }

        if (pings.isEmpty()) return Pair(0L, 0L)

        // Drop the highest outlier
        val sorted = pings.sorted()
        val clean  = if (sorted.size > 3) sorted.dropLast(1) else sorted

        val avgPing = clean.average().roundToLong()
        val jitter  = if (clean.size > 1)
            clean.zipWithNext { a, b -> kotlin.math.abs(b - a) }.average().roundToLong()
        else 0L

        return Pair(avgPing, jitter)
    }

    // ─── Download Speed ─────────────────────────────────────────────────────────

    private suspend fun measureDownloadSpeed(
        onProgress: suspend (speedMbps: Float, progress: Int) -> Unit
    ): Float = withContext(Dispatchers.IO) {

        val testDurationMs = 12_000L   // 12-second window
        val startTime      = System.currentTimeMillis()
        var totalBytes     = 0L
        val samples        = mutableListOf<SpeedSample>()

        // Discard first 2s of samples (TCP slow-start ramp-up)
        val warmupMs = 2_000L

        for (url in downloadUrls) {
            if (System.currentTimeMillis() - startTime >= testDurationMs) break
            try {
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val body   = response.body ?: return@use
                    val buffer = ByteArray(131_072)   // 128 KB read buffer
                    val stream = body.byteStream()
                    var bytesRead: Int

                    while (stream.read(buffer).also { bytesRead = it } != -1) {
                        totalBytes += bytesRead
                        val elapsed = System.currentTimeMillis() - startTime

                        if (elapsed > warmupMs && elapsed > 0) {
                            // Calculate speed only from post-warm-up bytes
                            val effectiveMs    = elapsed - warmupMs
                            val speedMbps      = (totalBytes * 8.0 / (elapsed / 1000.0) / 1_000_000).toFloat()
                            val progress       = ((elapsed.toFloat() / testDurationMs) * 100).toInt().coerceIn(0, 100)
                            samples.add(SpeedSample(speedMbps, effectiveMs))
                            withContext(Dispatchers.Main) { onProgress(speedMbps, progress) }
                        } else if (elapsed > 0) {
                            // Still in warm-up — update UI but don't count in final result
                            val speedMbps = (totalBytes * 8.0 / (elapsed / 1000.0) / 1_000_000).toFloat()
                            withContext(Dispatchers.Main) { onProgress(speedMbps, 0) }
                        }

                        if (System.currentTimeMillis() - startTime >= testDurationMs) break
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "Download from $url failed: ${e.message}")
            }
        }

        if (samples.isEmpty()) return@withContext 0f
        // Return the 90th percentile average (discard bottom 10% slow samples too)
        val trimmed = samples
            .sortedBy { it.speedMbps }
            .drop((samples.size * 0.1).toInt())
        trimmed.map { it.speedMbps }.average().toFloat()
    }

    // ─── Upload Speed ───────────────────────────────────────────────────────────

    private suspend fun measureUploadSpeed(
        onProgress: suspend (speedMbps: Float, progress: Int) -> Unit
    ): Float = withContext(Dispatchers.IO) {

        val chunkSize      = 512 * 1024          // 512 KB per POST
        val totalChunks    = 30                  // up to 15 MB total
        val chunk          = ByteArray(chunkSize) { (it % 251).toByte() }
        val mediaType      = "application/octet-stream".toMediaType()
        val samples        = mutableListOf<SpeedSample>()
        val startTime      = System.currentTimeMillis()
        val testDurationMs = 10_000L
        val warmupMs       = 1_500L
        var totalUploaded  = 0L

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
                        val progress  = ((idx.toFloat() / totalChunks) * 100).toInt().coerceIn(0, 100)
                        if (elapsed > warmupMs) {
                            samples.add(SpeedSample(speedMbps, elapsed))
                        }
                        withContext(Dispatchers.Main) { onProgress(speedMbps, progress) }
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "Upload chunk $idx failed: ${e.message}")
            }
        }

        if (samples.isEmpty()) return@withContext 0f
        val trimmed = samples
            .sortedBy { it.speedMbps }
            .drop((samples.size * 0.1).toInt())
        trimmed.map { it.speedMbps }.average().toFloat()
    }

    fun cancel() {
        client.dispatcher.cancelAll()
    }
}
