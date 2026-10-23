package com.fabads.ironsource

import android.app.Activity
import android.content.Context
import android.view.View
import com.fabads.AdEnvironment
import com.fabads.AdFormat
import com.fabads.AdNetwork
import com.fabads.AdProvider
import com.fabads.AdUtils
import com.fabads.BannerSize
import com.fabads.NetworkConfig
import com.fabads.NetworkIds
import com.unity3d.mediation.LevelPlay
import com.unity3d.mediation.LevelPlayAdError
import com.unity3d.mediation.LevelPlayAdInfo
import com.unity3d.mediation.LevelPlayAdSize
import com.unity3d.mediation.LevelPlayConfiguration
import com.unity3d.mediation.LevelPlayInitError
import com.unity3d.mediation.LevelPlayInitListener
import com.unity3d.mediation.LevelPlayInitRequest
import com.unity3d.mediation.banner.LevelPlayBannerAdView
import com.unity3d.mediation.banner.LevelPlayBannerAdViewListener
import com.unity3d.mediation.interstitial.LevelPlayInterstitialAd
import com.unity3d.mediation.interstitial.LevelPlayInterstitialAdListener
import com.unity3d.mediation.rewarded.LevelPlayReward
import com.unity3d.mediation.rewarded.LevelPlayRewardedAd
import com.unity3d.mediation.rewarded.LevelPlayRewardedAdListener

class IronSourceProvider : AdProvider {
    override val id = NetworkIds.IRONSOURCE

    override fun create(context: Context, config: NetworkConfig, env: AdEnvironment): AdNetwork? =
        if (config.appKey.isBlank()) null else IronSourceNetwork(context, config, env)
}

/** ironSource LevelPlay (Unity LevelPlay): banner, medium rectangle, interstitial and rewarded. Units are ad unit ids. */
class IronSourceNetwork(private val context: Context, private val config: NetworkConfig, private val env: AdEnvironment) : AdNetwork {
    override val id = NetworkIds.IRONSOURCE
    private var started = false
    private var interstitial: LevelPlayInterstitialAd? = null
    private var onInterstitialClosed: (() -> Unit)? = null

    override fun supports(format: AdFormat) = when (format) {
        AdFormat.BANNER -> config.banner.isNotBlank()
        AdFormat.MREC -> config.mrec.isNotBlank()
        AdFormat.INTERSTITIAL -> config.interstitial.isNotBlank()
        AdFormat.REWARDED -> config.rewarded.isNotBlank()
        AdFormat.NATIVE, AdFormat.APP_OPEN -> false
    }

    override fun init(activity: Activity, onReady: () -> Unit) {
        if (env.privacy.gdprApplies) LevelPlay.setConsent(env.privacy.consent)
        LevelPlay.setMetaData("do_not_sell", env.privacy.doNotSell.toString())
        if (env.privacy.childDirected) LevelPlay.setMetaData("is_child_directed", "true")
        if (env.testMode) LevelPlay.setMetaData("is_test_suite", "enable")
        LevelPlay.setAdaptersDebug(env.debug)
        LevelPlay.init(context, LevelPlayInitRequest.Builder(config.appKey).build(), object : LevelPlayInitListener {
            override fun onInitSuccess(configuration: LevelPlayConfiguration) {
                started = true
                onReady()
            }

            override fun onInitFailed(error: LevelPlayInitError) {
                AdUtils.log(NetworkIds.IRONSOURCE, "init: ${error.errorMessage}")
                onReady()
            }
        })
    }

    override fun loadInterstitial(activity: Activity) {
        if (!started || !supports(AdFormat.INTERSTITIAL)) return
        val ad = interstitial ?: LevelPlayInterstitialAd(config.interstitial).also { ad ->
            interstitial = ad
            ad.setListener(object : LevelPlayInterstitialAdListener {
                override fun onAdLoaded(adInfo: LevelPlayAdInfo) {}
                override fun onAdLoadFailed(error: LevelPlayAdError) { AdUtils.log(NetworkIds.IRONSOURCE, "interstitial: ${error.errorMessage}") }
                override fun onAdDisplayed(adInfo: LevelPlayAdInfo) {}
                override fun onAdDisplayFailed(error: LevelPlayAdError, adInfo: LevelPlayAdInfo) = closed()
                override fun onAdClosed(adInfo: LevelPlayAdInfo) = closed()
            })
        }
        if (!ad.isAdReady()) ad.loadAd()
    }

    private fun closed() {
        onInterstitialClosed?.invoke()
        onInterstitialClosed = null
    }

    override fun showInterstitial(activity: Activity, onClosed: () -> Unit): Boolean {
        val ad = interstitial?.takeIf { it.isAdReady() } ?: return false
        onInterstitialClosed = onClosed
        ad.showAd(activity)
        return true
    }

    override fun showRewarded(activity: Activity, onResult: (Boolean?) -> Unit) {
        if (!started || !supports(AdFormat.REWARDED)) return onResult(null)
        val ad = LevelPlayRewardedAd(config.rewarded)
        var earned = false
        ad.setListener(object : LevelPlayRewardedAdListener {
            override fun onAdLoaded(adInfo: LevelPlayAdInfo) = ad.showAd(activity)
            override fun onAdLoadFailed(error: LevelPlayAdError) {
                AdUtils.log(NetworkIds.IRONSOURCE, "rewarded: ${error.errorMessage}")
                onResult(null)
            }
            override fun onAdDisplayed(adInfo: LevelPlayAdInfo) {}
            override fun onAdRewarded(reward: LevelPlayReward, adInfo: LevelPlayAdInfo) { earned = true }
            override fun onAdDisplayFailed(error: LevelPlayAdError, adInfo: LevelPlayAdInfo) = onResult(null)
            override fun onAdClosed(adInfo: LevelPlayAdInfo) = onResult(earned)
        })
        ad.loadAd()
    }

    override fun banner(activity: Activity, size: BannerSize, onFailed: () -> Unit): View? {
        val mrec = size == BannerSize.MREC
        val unit = if (mrec) config.mrec else config.banner
        if (!started || unit.isBlank()) return null
        val adSize = if (mrec) LevelPlayAdSize.MEDIUM_RECTANGLE else LevelPlayAdSize.BANNER
        return LevelPlayBannerAdView(activity, unit, LevelPlayBannerAdView.Config.Builder().setAdSize(adSize).build()).apply {
            setBannerListener(object : LevelPlayBannerAdViewListener {
                override fun onAdLoaded(adInfo: LevelPlayAdInfo) {}
                override fun onAdLoadFailed(error: LevelPlayAdError) {
                    AdUtils.log(NetworkIds.IRONSOURCE, "banner: ${error.errorMessage}")
                    onFailed()
                }
            })
            loadAd()
        }
    }

    override fun destroyBanner(view: View) {
        (view as? LevelPlayBannerAdView)?.destroy()
    }
}
