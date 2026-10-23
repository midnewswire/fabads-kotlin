package com.fabads.inmobi

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import com.fabads.AdEnvironment
import com.fabads.AdFormat
import com.fabads.AdNetwork
import com.fabads.AdProvider
import com.fabads.AdUtils
import com.fabads.BannerSize
import com.fabads.NetworkConfig
import com.fabads.NetworkIds
import com.inmobi.ads.AdMetaInfo
import com.inmobi.ads.InMobiAdRequestStatus
import com.inmobi.ads.InMobiBanner
import com.inmobi.ads.InMobiInterstitial
import com.inmobi.ads.listeners.BannerAdEventListener
import com.inmobi.ads.listeners.InterstitialAdEventListener
import com.inmobi.sdk.InMobiSdk
import com.inmobi.sdk.SdkInitializationListener
import org.json.JSONObject

class InMobiProvider : AdProvider {
    override val id = NetworkIds.INMOBI

    override fun create(context: Context, config: NetworkConfig, env: AdEnvironment): AdNetwork? =
        if (config.string("account_id").isBlank()) null else InMobiNetwork(context, config, env)
}

/** InMobi: banner, medium rectangle, interstitial and rewarded. Units are numeric placement ids. */
class InMobiNetwork(private val context: Context, private val config: NetworkConfig, private val env: AdEnvironment) : AdNetwork {
    override val id = NetworkIds.INMOBI
    private var interstitial: InMobiInterstitial? = null
    private var interstitialReady = false
    private var onInterstitialClosed: (() -> Unit)? = null

    private fun placement(value: String): Long? = value.trim().toLongOrNull()

    override fun supports(format: AdFormat) = when (format) {
        AdFormat.BANNER -> placement(config.banner) != null
        AdFormat.MREC -> placement(config.mrec) != null
        AdFormat.INTERSTITIAL -> placement(config.interstitial) != null
        AdFormat.REWARDED -> placement(config.rewarded) != null
        AdFormat.NATIVE, AdFormat.APP_OPEN -> false
    }

    override fun init(activity: Activity, onReady: () -> Unit) {
        if (env.debug) InMobiSdk.setLogLevel(InMobiSdk.LogLevel.DEBUG)
        if (env.privacy.childDirected) InMobiSdk.setIsAgeRestricted(true)
        if (InMobiSdk.isSDKInitialized()) return onReady()
        val consent = JSONObject()
            .put("gdpr", if (env.privacy.gdprApplies) "1" else "0")
            .put("gdpr_consent_available", env.privacy.consent)
        InMobiSdk.init(context, config.string("account_id"), consent, object : SdkInitializationListener {
            override fun onInitializationComplete(error: Error?) {
                if (error != null) AdUtils.log(NetworkIds.INMOBI, "init: ${error.message}")
                onReady()
            }
        })
    }

    override fun loadInterstitial(activity: Activity) {
        val placement = placement(config.interstitial) ?: return
        if (interstitialReady || !InMobiSdk.isSDKInitialized()) return
        val ad = interstitial ?: InMobiInterstitial(activity, placement, object : InterstitialAdEventListener() {
            override fun onAdLoadSucceeded(ad: InMobiInterstitial, info: AdMetaInfo) { interstitialReady = true }
            override fun onAdLoadFailed(ad: InMobiInterstitial, status: InMobiAdRequestStatus) { AdUtils.log(NetworkIds.INMOBI, "interstitial: ${status.message}") }
            override fun onAdDismissed(ad: InMobiInterstitial) = closed()
            override fun onAdDisplayFailed(ad: InMobiInterstitial) = closed()
        }).also { interstitial = it }
        ad.load()
    }

    private fun closed() {
        onInterstitialClosed?.invoke()
        onInterstitialClosed = null
    }

    override fun showInterstitial(activity: Activity, onClosed: () -> Unit): Boolean {
        val ad = interstitial?.takeIf { interstitialReady && it.isReady() } ?: return false
        interstitialReady = false
        onInterstitialClosed = onClosed
        ad.show()
        return true
    }

    /** InMobi rewarded video uses the interstitial class with a rewarded placement. */
    override fun showRewarded(activity: Activity, onResult: (Boolean?) -> Unit) {
        val placement = placement(config.rewarded) ?: return onResult(null)
        if (!InMobiSdk.isSDKInitialized()) return onResult(null)
        var earned = false
        lateinit var ad: InMobiInterstitial
        ad = InMobiInterstitial(activity, placement, object : InterstitialAdEventListener() {
            override fun onAdLoadSucceeded(loaded: InMobiInterstitial, info: AdMetaInfo) = ad.show()
            override fun onAdLoadFailed(failed: InMobiInterstitial, status: InMobiAdRequestStatus) {
                AdUtils.log(NetworkIds.INMOBI, "rewarded: ${status.message}")
                onResult(null)
            }
            override fun onRewardsUnlocked(shown: InMobiInterstitial, rewards: Map<Any, Any>) { earned = true }
            override fun onAdDismissed(shown: InMobiInterstitial) = onResult(earned)
            override fun onAdDisplayFailed(shown: InMobiInterstitial) = onResult(null)
        })
        ad.load()
    }

    override fun banner(activity: Activity, size: BannerSize, onFailed: () -> Unit): View? {
        val mrec = size == BannerSize.MREC
        val placement = placement(if (mrec) config.mrec else config.banner) ?: return null
        if (!InMobiSdk.isSDKInitialized()) return null
        return InMobiBanner(activity, placement).apply {
            setBannerSize(size.width, size.height)
            layoutParams = ViewGroup.LayoutParams(AdUtils.dp(activity, size.width), AdUtils.dp(activity, size.height))
            setListener(object : BannerAdEventListener() {
                override fun onAdLoadFailed(ad: InMobiBanner, status: InMobiAdRequestStatus) {
                    AdUtils.log(NetworkIds.INMOBI, "banner: ${status.message}")
                    onFailed()
                }
            })
            load()
        }
    }

    override fun destroyBanner(view: View) {
        (view as? InMobiBanner)?.destroy()
    }
}
