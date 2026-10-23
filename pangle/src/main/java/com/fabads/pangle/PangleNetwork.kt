package com.fabads.pangle

import android.app.Activity
import android.content.Context
import android.view.View
import android.widget.FrameLayout
import com.bytedance.sdk.openadsdk.api.banner.PAGBannerAd
import com.bytedance.sdk.openadsdk.api.banner.PAGBannerAdLoadListener
import com.bytedance.sdk.openadsdk.api.banner.PAGBannerRequest
import com.bytedance.sdk.openadsdk.api.banner.PAGBannerSize
import com.bytedance.sdk.openadsdk.api.init.PAGConfig
import com.bytedance.sdk.openadsdk.api.init.PAGSdk
import com.bytedance.sdk.openadsdk.api.interstitial.PAGInterstitialAd
import com.bytedance.sdk.openadsdk.api.interstitial.PAGInterstitialAdInteractionListener
import com.bytedance.sdk.openadsdk.api.interstitial.PAGInterstitialAdLoadListener
import com.bytedance.sdk.openadsdk.api.interstitial.PAGInterstitialRequest
import com.bytedance.sdk.openadsdk.api.open.PAGAppOpenAd
import com.bytedance.sdk.openadsdk.api.open.PAGAppOpenAdInteractionListener
import com.bytedance.sdk.openadsdk.api.open.PAGAppOpenAdLoadListener
import com.bytedance.sdk.openadsdk.api.open.PAGAppOpenRequest
import com.bytedance.sdk.openadsdk.api.reward.PAGRewardItem
import com.bytedance.sdk.openadsdk.api.reward.PAGRewardedAd
import com.bytedance.sdk.openadsdk.api.reward.PAGRewardedAdInteractionListener
import com.bytedance.sdk.openadsdk.api.reward.PAGRewardedAdLoadListener
import com.bytedance.sdk.openadsdk.api.reward.PAGRewardedRequest
import com.fabads.AdEnvironment
import com.fabads.AdFormat
import com.fabads.AdNetwork
import com.fabads.AdProvider
import com.fabads.AdUtils
import com.fabads.BannerSize
import com.fabads.NetworkConfig
import com.fabads.NetworkIds

class PangleProvider : AdProvider {
    override val id = NetworkIds.PANGLE

    override fun create(context: Context, config: NetworkConfig, env: AdEnvironment): AdNetwork? =
        if (config.appId.isBlank()) null else PangleNetwork(context, config, env)
}

/**
 * Pangle (TikTok / ByteDance): banner, medium rectangle, interstitial, rewarded and app open. Units are Pangle
 * placement ids. Pangle reads the GDPR answer from the IAB TCF string the consent form saved.
 */
class PangleNetwork(private val context: Context, private val config: NetworkConfig, private val env: AdEnvironment) : AdNetwork {
    override val id = NetworkIds.PANGLE
    private var interstitial: PAGInterstitialAd? = null
    private var interstitialLoading = false
    private var appOpen: PAGAppOpenAd? = null

    override fun supports(format: AdFormat) = when (format) {
        AdFormat.BANNER -> config.banner.isNotBlank()
        AdFormat.MREC -> config.mrec.isNotBlank()
        AdFormat.INTERSTITIAL -> config.interstitial.isNotBlank()
        AdFormat.REWARDED -> config.rewarded.isNotBlank()
        AdFormat.APP_OPEN -> config.appOpen.isNotBlank()
        AdFormat.NATIVE -> false
    }

    override fun init(activity: Activity, onReady: () -> Unit) {
        if (PAGSdk.isInitSuccess()) return onReady()
        val pagConfig = PAGConfig.Builder()
            .appId(config.appId)
            .debugLog(env.debug)
            .build()
        PAGSdk.init(context, pagConfig, object : PAGSdk.PAGInitCallback {
            override fun success() = onReady()
            override fun fail(code: Int, message: String) {
                AdUtils.log(NetworkIds.PANGLE, "init: $message")
                onReady()
            }
        })
    }

    override fun loadInterstitial(activity: Activity) {
        if (interstitial != null || interstitialLoading || !supports(AdFormat.INTERSTITIAL) || !PAGSdk.isInitSuccess()) return
        interstitialLoading = true
        PAGInterstitialAd.loadAd(config.interstitial, PAGInterstitialRequest(), object : PAGInterstitialAdLoadListener {
            override fun onAdLoaded(ad: PAGInterstitialAd) { interstitialLoading = false; interstitial = ad }
            override fun onError(code: Int, message: String) { interstitialLoading = false; AdUtils.log(NetworkIds.PANGLE, "interstitial: $message") }
        })
    }

    override fun showInterstitial(activity: Activity, onClosed: () -> Unit): Boolean {
        val ad = interstitial ?: return false
        interstitial = null
        ad.setAdInteractionListener(object : PAGInterstitialAdInteractionListener {
            override fun onAdShowed() {}
            override fun onAdClicked() {}
            override fun onAdDismissed() = onClosed()
        })
        ad.show(activity)
        return true
    }

    override fun showRewarded(activity: Activity, onResult: (Boolean?) -> Unit) {
        if (!supports(AdFormat.REWARDED) || !PAGSdk.isInitSuccess()) return onResult(null)
        PAGRewardedAd.loadAd(config.rewarded, PAGRewardedRequest(), object : PAGRewardedAdLoadListener {
            override fun onError(code: Int, message: String) {
                AdUtils.log(NetworkIds.PANGLE, "rewarded: $message")
                onResult(null)
            }

            override fun onAdLoaded(ad: PAGRewardedAd) {
                var earned = false
                ad.setAdInteractionListener(object : PAGRewardedAdInteractionListener {
                    override fun onAdShowed() {}
                    override fun onAdClicked() {}
                    override fun onAdDismissed() = onResult(earned)
                    override fun onUserEarnedReward(item: PAGRewardItem) { earned = true }
                    override fun onUserEarnedRewardFail(code: Int, message: String) {}
                })
                ad.show(activity)
            }
        })
    }

    override fun loadAppOpen(activity: Activity, onLoaded: () -> Unit) {
        if (appOpen != null || !supports(AdFormat.APP_OPEN) || !PAGSdk.isInitSuccess()) return
        PAGAppOpenAd.loadAd(config.appOpen, PAGAppOpenRequest(), object : PAGAppOpenAdLoadListener {
            override fun onAdLoaded(ad: PAGAppOpenAd) { appOpen = ad; onLoaded() }
            override fun onError(code: Int, message: String) { AdUtils.log(NetworkIds.PANGLE, "app open: $message") }
        })
    }

    override fun showAppOpen(activity: Activity, onClosed: () -> Unit): Boolean {
        val ad = appOpen ?: return false
        appOpen = null
        ad.setAdInteractionListener(object : PAGAppOpenAdInteractionListener {
            override fun onAdShowed() {}
            override fun onAdClicked() {}
            override fun onAdDismissed() = onClosed()
        })
        ad.show(activity)
        return true
    }

    /** Pangle hands over its banner view only after loading, so it goes into a container returned right away. */
    override fun banner(activity: Activity, size: BannerSize, onFailed: () -> Unit): View? {
        val mrec = size == BannerSize.MREC
        val unit = if (mrec) config.mrec else config.banner
        if (unit.isBlank() || !PAGSdk.isInitSuccess()) return null
        val container = FrameLayout(activity)
        val pagSize = if (mrec) PAGBannerSize.BANNER_W_300_H_250 else PAGBannerSize.BANNER_W_320_H_50
        PAGBannerAd.loadAd(unit, PAGBannerRequest(pagSize), object : PAGBannerAdLoadListener {
            override fun onAdLoaded(ad: PAGBannerAd) {
                container.tag = ad
                container.addView(ad.bannerView)
            }
            override fun onError(code: Int, message: String) {
                AdUtils.log(NetworkIds.PANGLE, "banner: $message")
                onFailed()
            }
        })
        return container
    }

    override fun destroyBanner(view: View) {
        (view.tag as? PAGBannerAd)?.destroy()
    }
}
