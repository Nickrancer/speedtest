package com.speedtest.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.nativead.MediaView
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.google.android.gms.ads.nativead.NativeAdView
import com.speedtest.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val engine = SpeedTestEngine()
    private var testJob: Job? = null
    private var isTesting = false
    private var currentNativeAd: NativeAd? = null

    // ── AdMob Unit IDs ─────────────────────────────────────────────────────────
    private val BANNER_AD_UNIT_ID         = "ca-app-pub-6649164620167182/2457345781"
    private val NATIVE_ADVANCED_AD_UNIT_ID = "ca-app-pub-6649164620167182/6714257786"

    enum class TestPhase { IDLE, PING, DOWNLOAD, UPLOAD, DONE }
    private var currentPhase = TestPhase.IDLE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initAdMob()
        setupUI()
        checkNetworkAndUpdateUI()
    }

    // ── AdMob ──────────────────────────────────────────────────────────────────

    private fun initAdMob() {
        MobileAds.initialize(this) {
            loadBannerAd()
        }
    }

    private fun loadBannerAd() {
        binding.adBanner.loadAd(AdRequest.Builder().build())
    }

    /** Load a Native Advanced ad and show it in nativeAdContainer */
    private fun loadAndShowNativeAd() {
        val adLoader = AdLoader.Builder(this, NATIVE_ADVANCED_AD_UNIT_ID)
            .forNativeAd { nativeAd ->
                // Destroy old ad if we have one
                currentNativeAd?.destroy()
                currentNativeAd = nativeAd
                renderNativeAd(nativeAd)
            }
            .withAdListener(object : com.google.android.gms.ads.AdListener() {
                override fun onAdFailedToLoad(error: LoadAdError) {
                    binding.nativeAdContainer.visibility = View.GONE
                }
            })
            .withNativeAdOptions(NativeAdOptions.Builder().build())
            .build()

        adLoader.loadAd(AdRequest.Builder().build())
    }

    private fun renderNativeAd(nativeAd: NativeAd) {
        val adView = LayoutInflater.from(this)
            .inflate(R.layout.native_ad_view, binding.nativeAdContainer, false) as NativeAdView

        // Wire each view to the NativeAdView
        adView.headlineView   = adView.findViewById<TextView>(R.id.ad_headline).also {
            it.text = nativeAd.headline
        }
        adView.bodyView       = adView.findViewById<TextView>(R.id.ad_body).also {
            it.text = nativeAd.body
            it.visibility = if (nativeAd.body != null) View.VISIBLE else View.GONE
        }
        adView.advertiserView = adView.findViewById<TextView>(R.id.ad_advertiser).also {
            it.text = nativeAd.advertiser
            it.visibility = if (nativeAd.advertiser != null) View.VISIBLE else View.GONE
        }
        adView.iconView       = adView.findViewById<ImageView>(R.id.ad_app_icon).also {
            if (nativeAd.icon != null) {
                it.setImageDrawable(nativeAd.icon?.drawable)
                it.visibility = View.VISIBLE
            } else {
                it.visibility = View.GONE
            }
        }
        adView.mediaView      = adView.findViewById<MediaView>(R.id.ad_media).also {
            adView.mediaView = it
        }
        adView.callToActionView = adView.findViewById<Button>(R.id.ad_call_to_action).also {
            it.text = nativeAd.callToAction
            it.visibility = if (nativeAd.callToAction != null) View.VISIBLE else View.GONE
        }

        // Register the native ad — MUST be called after all views are wired
        adView.setNativeAd(nativeAd)

        // Display in container
        binding.nativeAdContainer.removeAllViews()
        binding.nativeAdContainer.addView(adView)
        binding.nativeAdContainer.visibility = View.VISIBLE

        val anim = AnimationUtils.loadAnimation(this, android.R.anim.fade_in)
        anim.duration = 400
        binding.nativeAdContainer.startAnimation(anim)
    }

    // ── UI Setup ───────────────────────────────────────────────────────────────

    private fun setupUI() {
        binding.btnStart.setOnClickListener {
            if (isTesting) stopTest() else startTest()
        }
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
        binding.tvStatus.text = "Fetching network info..."

        testJob = lifecycleScope.launch {
            engine.runTest(object : SpeedTestEngine.ProgressListener {

                override fun onNetworkInfo(info: NetworkInfo) {
                    binding.tvIpAddress.text = info.publicIp
                    binding.tvIspName.text   = info.isp
                    val location = listOf(info.city, info.country)
                        .filter { it.isNotBlank() }.joinToString(", ")
                    binding.tvServerName.text = location.ifBlank { "Unknown" }
                    if (info.asn.isNotBlank()) {
                        binding.tvAsn.text       = info.asn
                        binding.tvAsn.visibility = View.VISIBLE
                    }
                    binding.tvStatus.text = "Measuring ping..."
                }

                override fun onPingResult(ping: Long, jitter: Long) {
                    binding.tvPing.text   = "${ping}ms"
                    binding.tvJitter.text = "±${jitter}ms"
                    setPhase(TestPhase.DOWNLOAD)
                    binding.tvStatus.text = "Testing download speed..."
                }

                override fun onDownloadProgress(speedMbps: Float, progressPercent: Int) {
                    binding.speedometer.setSpeed(speedMbps)
                    binding.tvCurrentSpeed.text       = String.format("%.1f Mbps", speedMbps)
                    binding.tvCurrentSpeed.visibility = View.VISIBLE
                    binding.tvDownload.text           = String.format("%.1f", speedMbps)
                    binding.progressBar.progress      = progressPercent / 2
                }

                override fun onUploadProgress(speedMbps: Float, progressPercent: Int) {
                    if (currentPhase != TestPhase.UPLOAD) {
                        setPhase(TestPhase.UPLOAD)
                        binding.tvStatus.text = "Testing upload speed..."
                        binding.speedometer.setSpeed(0f)
                    }
                    binding.speedometer.setSpeed(speedMbps)
                    binding.tvCurrentSpeed.text  = String.format("%.1f Mbps", speedMbps)
                    binding.tvUpload.text        = String.format("%.1f", speedMbps)
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
                    binding.tvStatus.text = "Error — check connection"
                    binding.speedometer.setAnimating(false)
                    binding.speedometer.setSpeed(0f)
                    binding.tvCurrentSpeed.visibility = View.GONE
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
        binding.tvCurrentSpeed.visibility = View.GONE
        binding.progressBar.progress = 0
    }

    private fun onTestComplete(result: SpeedTestResult) {
        isTesting = false
        currentPhase = TestPhase.DONE
        binding.btnStart.text = "RETEST"
        binding.btnStart.setBackgroundResource(R.drawable.btn_start_bg)
        binding.speedometer.setAnimating(false)
        binding.speedometer.setSpeed(result.downloadSpeed)
        binding.tvStatus.text = "Test Complete ✓"
        binding.progressBar.progress = 100
        binding.tvCurrentSpeed.visibility = View.GONE

        binding.tvPing.text     = "${result.ping}ms"
        binding.tvJitter.text   = "±${result.jitter}ms"
        binding.tvDownload.text = String.format("%.1f", result.downloadSpeed)
        binding.tvUpload.text   = String.format("%.1f", result.uploadSpeed)

        binding.tvIpAddress.text  = result.networkInfo.publicIp
        binding.tvIspName.text    = result.networkInfo.isp
        val location = listOf(result.networkInfo.city, result.networkInfo.country)
            .filter { it.isNotBlank() }.joinToString(", ")
        binding.tvServerName.text = location.ifBlank { "Unknown" }

        binding.cardResults.visibility = View.VISIBLE
        val anim = AnimationUtils.loadAnimation(this, android.R.anim.fade_in)
        anim.duration = 500
        binding.cardResults.startAnimation(anim)

        showRating(result.downloadSpeed)

        // Show native ad after results appear
        loadAndShowNativeAd()
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun showRating(mbps: Float) {
        val (rating, color) = when {
            mbps >= 500 -> Pair("⚡ BLAZING FAST", "#00FF88")
            mbps >= 100 -> Pair("🚀 EXCELLENT",    "#00E5FF")
            mbps >= 50  -> Pair("✅ GOOD",          "#44BBFF")
            mbps >= 20  -> Pair("🆗 AVERAGE",       "#FFD700")
            mbps >= 5   -> Pair("⚠️ SLOW",          "#FF8800")
            else        -> Pair("❌ VERY SLOW",      "#FF4444")
        }
        binding.tvRating.text = rating
        binding.tvRating.setTextColor(android.graphics.Color.parseColor(color))
    }

    private fun setPhase(phase: TestPhase) {
        currentPhase = phase
        val indicators = listOf(
            binding.indicatorPing,
            binding.indicatorDownload,
            binding.indicatorUpload
        )
        indicators.forEach { it.setBackgroundResource(R.drawable.indicator_inactive) }
        when (phase) {
            TestPhase.PING     -> indicators[0].setBackgroundResource(R.drawable.indicator_active)
            TestPhase.DOWNLOAD -> indicators[1].setBackgroundResource(R.drawable.indicator_active)
            TestPhase.UPLOAD   -> indicators[2].setBackgroundResource(R.drawable.indicator_active)
            TestPhase.DONE     -> indicators.forEach { it.setBackgroundResource(R.drawable.indicator_done) }
            TestPhase.IDLE     -> {}
        }
    }

    private fun resetResults() {
        binding.tvPing.text       = "—"
        binding.tvJitter.text     = "—"
        binding.tvDownload.text   = "—"
        binding.tvUpload.text     = "—"
        binding.tvIpAddress.text  = "Detecting..."
        binding.tvIspName.text    = "Detecting..."
        binding.tvServerName.text = "Detecting..."
        binding.tvAsn.visibility  = View.GONE
        binding.tvRating.text     = ""
        binding.cardResults.visibility     = View.GONE
        binding.nativeAdContainer.visibility = View.GONE
        binding.progressBar.progress       = 0
        binding.tvCurrentSpeed.visibility  = View.GONE
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
        currentNativeAd?.destroy()
        engine.cancel()
        testJob?.cancel()
    }
}
