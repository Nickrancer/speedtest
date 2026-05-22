package com.speedtest.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.speedtest.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val engine = SpeedTestEngine()
    private var testJob: Job? = null
    private var isTesting = false

    enum class TestPhase { IDLE, PING, DOWNLOAD, UPLOAD, DONE }
    private var currentPhase = TestPhase.IDLE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        checkNetworkAndUpdateUI()
    }

    private fun setupUI() {
        binding.btnStart.setOnClickListener {
            if (isTesting) {
                stopTest()
            } else {
                startTest()
            }
        }

        // Initial state
        setPhase(TestPhase.IDLE)
        binding.tvNetworkType.text = getNetworkType()
    }

    private fun startTest() {
        if (!isNetworkAvailable()) {
            Toast.makeText(this, "No internet connection!", Toast.LENGTH_SHORT).show()
            return
        }

        isTesting = true
        resetResults()
        setPhase(TestPhase.PING)
        binding.btnStart.text = "STOP"
        binding.btnStart.setBackgroundResource(R.drawable.btn_stop_bg)
        binding.speedometer.setAnimating(true)
        binding.speedometer.setSpeed(0f, false)
        binding.tvStatus.text = "Measuring ping..."

        testJob = lifecycleScope.launch {
            engine.runTest(object : SpeedTestEngine.ProgressListener {
                override fun onPingResult(ping: Long, jitter: Long) {
                    binding.tvPing.text = "${ping}ms"
                    binding.tvJitter.text = "±${jitter}ms"
                    setPhase(TestPhase.DOWNLOAD)
                    binding.tvStatus.text = "Testing download..."
                }

                override fun onDownloadProgress(speedMbps: Float, progressPercent: Int) {
                    binding.speedometer.setSpeed(speedMbps)
                    binding.tvCurrentSpeed.text = String.format("%.1f", speedMbps)
                    binding.tvDownload.text = String.format("%.1f", speedMbps)
                    binding.progressBar.progress = progressPercent
                }

                override fun onUploadProgress(speedMbps: Float, progressPercent: Int) {
                    if (currentPhase != TestPhase.UPLOAD) {
                        setPhase(TestPhase.UPLOAD)
                        binding.tvStatus.text = "Testing upload..."
                        binding.speedometer.setSpeed(0f)
                    }
                    binding.speedometer.setSpeed(speedMbps)
                    binding.tvCurrentSpeed.text = String.format("%.1f", speedMbps)
                    binding.tvUpload.text = String.format("%.1f", speedMbps)
                    binding.progressBar.progress = 50 + progressPercent / 2
                }

                override fun onComplete(result: SpeedTestResult) {
                    onTestComplete(result)
                }

                override fun onError(error: String) {
                    isTesting = false
                    setPhase(TestPhase.IDLE)
                    binding.btnStart.text = "START"
                    binding.btnStart.setBackgroundResource(R.drawable.btn_start_bg)
                    binding.tvStatus.text = "Error: $error"
                    binding.speedometer.setAnimating(false)
                    binding.speedometer.setSpeed(0f)
                    Toast.makeText(this@MainActivity, "Test failed: $error", Toast.LENGTH_LONG).show()
                }
            })
        }
    }

    private fun stopTest() {
        testJob?.cancel()
        engine.cancel()
        isTesting = false
        setPhase(TestPhase.IDLE)
        binding.btnStart.text = "START"
        binding.btnStart.setBackgroundResource(R.drawable.btn_start_bg)
        binding.tvStatus.text = "Test stopped"
        binding.speedometer.setAnimating(false)
        binding.speedometer.setSpeed(0f)
        binding.progressBar.progress = 0
    }

    private fun onTestComplete(result: SpeedTestResult) {
        isTesting = false
        currentPhase = TestPhase.DONE
        binding.btnStart.text = "RETEST"
        binding.btnStart.setBackgroundResource(R.drawable.btn_start_bg)
        binding.speedometer.setAnimating(false)
        binding.speedometer.setSpeed(result.downloadSpeed)
        binding.tvStatus.text = "Test Complete"
        binding.progressBar.progress = 100

        // Final results
        binding.tvPing.text = "${result.ping}ms"
        binding.tvJitter.text = "±${result.jitter}ms"
        binding.tvDownload.text = String.format("%.1f", result.downloadSpeed)
        binding.tvUpload.text = String.format("%.1f", result.uploadSpeed)
        binding.tvCurrentSpeed.text = String.format("%.1f", result.downloadSpeed)
        binding.tvServerName.text = result.serverName
        binding.tvIspName.text = result.isp

        // Show results card with animation
        binding.cardResults.visibility = View.VISIBLE
        val anim = AnimationUtils.loadAnimation(this, android.R.anim.fade_in)
        anim.duration = 600
        binding.cardResults.startAnimation(anim)

        showRating(result.downloadSpeed)
    }

    private fun showRating(downloadMbps: Float) {
        val (rating, color) = when {
            downloadMbps >= 500 -> Pair("⚡ BLAZING FAST", "#00FF88")
            downloadMbps >= 100 -> Pair("🚀 EXCELLENT", "#00E5FF")
            downloadMbps >= 50  -> Pair("✅ GOOD", "#44BBFF")
            downloadMbps >= 20  -> Pair("🆗 AVERAGE", "#FFD700")
            downloadMbps >= 5   -> Pair("⚠️ SLOW", "#FF8800")
            else                 -> Pair("❌ VERY SLOW", "#FF4444")
        }
        binding.tvRating.text = rating
        binding.tvRating.setTextColor(android.graphics.Color.parseColor(color))
    }

    private fun setPhase(phase: TestPhase) {
        currentPhase = phase
        // Update phase indicators
        val phases = listOf(
            binding.indicatorPing,
            binding.indicatorDownload,
            binding.indicatorUpload
        )
        phases.forEach { it.setBackgroundResource(R.drawable.indicator_inactive) }

        when (phase) {
            TestPhase.PING     -> phases[0].setBackgroundResource(R.drawable.indicator_active)
            TestPhase.DOWNLOAD -> phases[1].setBackgroundResource(R.drawable.indicator_active)
            TestPhase.UPLOAD   -> phases[2].setBackgroundResource(R.drawable.indicator_active)
            TestPhase.DONE     -> phases.forEach { it.setBackgroundResource(R.drawable.indicator_done) }
            TestPhase.IDLE     -> {}
        }
    }

    private fun resetResults() {
        binding.tvPing.text = "—"
        binding.tvJitter.text = "—"
        binding.tvDownload.text = "—"
        binding.tvUpload.text = "—"
        binding.tvCurrentSpeed.text = "0.0"
        binding.tvServerName.text = "Detecting..."
        binding.tvIspName.text = "Detecting..."
        binding.tvRating.text = ""
        binding.cardResults.visibility = View.GONE
        binding.progressBar.progress = 0
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun getNetworkType(): String {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return "No Connection"
        val caps = cm.getNetworkCapabilities(network) ?: return "Unknown"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)     -> "📶 Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "📡 Cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "🔌 Ethernet"
            else -> "Unknown"
        }
    }

    private fun checkNetworkAndUpdateUI() {
        binding.tvNetworkType.text = getNetworkType()
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.cancel()
        testJob?.cancel()
    }
}
