package com.fabads.wortise

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
import com.wortise.ads.AdError
import com.wortise.ads.AdSettings
import com.wortise.ads.AdSize
import com.wortise.ads.RevenueData
import com.wortise.ads.WortiseSdk
import com.wortise.ads.appopen.AppOpenAd
import com.wortise.ads.banner.BannerAd
import com.wortise.ads.interstitial.InterstitialAd
import com.wortise.ads.rewarded.RewardedAd
import com.wortise.ads.rewarded.models.Reward

class WortiseProvider : AdProvider {
    override val id = NetworkIds.WORTISE

    override fun create(context: Context, config: NetworkConfig, env: AdEnvironment): AdNetwork? =
        if (config.appKey.isBlank()) null else WortiseNetwork(context, config, env)
}

/** Wortise: banner, medium rectangle, interstitial, rewarded and app open. Units are Wortise ad unit ids. */
class WortiseNetwork(private val context: Context, private val config: NetworkConfig, private val env: AdEnvironment) : AdNetwork {
    override val id = NetworkIds.WORTISE
    private var interstitial: InterstitialAd? = null
    private var onInterstitialClosed: (() -> Unit)? = null
    private var appOpen: AppOpenAd? = null
    private var onAppOpenClosed: (() -> Unit)? = null

    override fun supports(format: AdFormat) = when (format) {
        AdFormat.BANNER -> config.banner.isNotBlank()
        AdFormat.MREC -> config.mrec.isNotBlank()
        AdFormat.INTERSTITIAL -> config.interstitial.isNotBlank()
        AdFormat.REWARDED -> config.rewarded.isNotBlank()
        AdFormat.APP_OPEN -> config.appOpen.isNotBlank()
        AdFormat.NATIVE -> false
    }

    override fun init(activity: Activity, onReady: () -> Unit) {
        if (env.privacy.childDirected) AdSettings.setChildDirected(context, true)
        if (WortiseSdk.isInitialized) return onReady()
        WortiseSdk.initialize(context, config.appKey) { onReady() }
    }

    override fun loadInterstitial(activity: Activity) {
        if (!supports(AdFormat.INTERSTITIAL) || !WortiseSdk.isInitialized) return
        val ad = interstitial ?: InterstitialAd(activity, config.interstitial).also { ad ->
            interstitial = ad
            ad.listener = object : InterstitialAd.Listener {
                override fun onInterstitialClicked(ad: InterstitialAd) {}
                override fun onInterstitialDismissed(ad: InterstitialAd) = closed()
                override fun onInterstitialFailedToLoad(ad: InterstitialAd, error: AdError) { AdUtils.log(NetworkIds.WORTISE, "interstitial: $error") }
                override fun onInterstitialFailedToShow(ad: InterstitialAd, error: AdError) = closed()
                override fun onInterstitialImpression(ad: InterstitialAd) {}
                override fun onInterstitialLoaded(ad: InterstitialAd) {}
                override fun onInterstitialRevenuePaid(ad: InterstitialAd, data: RevenueData) {}
                override fun onInterstitialShown(ad: InterstitialAd) {}
            }
        }
        if (!ad.isAvailable) ad.loadAd()
    }

    private fun closed() {
        onInterstitialClosed?.invoke()
        onInterstitialClosed = null
    }

    override fun showInterstitial(activity: Activity, onClosed: () -> Unit): Boolean {
        val ad = interstitial?.takeIf { it.isAvailable } ?: return false
        onInterstitialClosed = onClosed
        ad.showAd(activity)
        return true
    }

    override fun showRewarded(activity: Activity, onResult: (Boolean?) -> Unit) {
        if (!supports(AdFormat.REWARDED) || !WortiseSdk.isInitialized) return onResult(null)
        val ad = RewardedAd(activity, config.rewarded)
        var earned = false
        ad.listener = object : RewardedAd.Listener {
            override fun onRewardedClicked(ad: RewardedAd) {}
            override fun onRewardedCompleted(ad: RewardedAd, reward: Reward) { earned = true }
            override fun onRewardedDismissed(ad: RewardedAd) { onResult(earned); ad.destroy() }
            override fun onRewardedFailedToLoad(ad: RewardedAd, error: AdError) { AdUtils.log(NetworkIds.WORTISE, "rewarded: $error"); onResult(null) }
            override fun onRewardedFailedToShow(ad: RewardedAd, error: AdError) = onResult(null)
            override fun onRewardedImpression(ad: RewardedAd) {}
            override fun onRewardedLoaded(ad: RewardedAd) = ad.showAd(activity)
            override fun onRewardedRevenuePaid(ad: RewardedAd, data: RevenueData) {}
            override fun onRewardedShown(ad: RewardedAd) {}
        }
        ad.loadAd()
    }

    override fun loadAppOpen(activity: Activity, onLoaded: () -> Unit) {
        if (!supports(AdFormat.APP_OPEN) || !WortiseSdk.isInitialized) return
        val ad = appOpen ?: AppOpenAd(activity, config.appOpen).also { ad ->
            appOpen = ad
            ad.listener = object : AppOpenAd.Listener {
                override fun onAppOpenClicked(ad: AppOpenAd) {}
                override fun onAppOpenDismissed(ad: AppOpenAd) = appOpenClosed()
                override fun onAppOpenFailedToLoad(ad: AppOpenAd, error: AdError) { AdUtils.log(NetworkIds.WORTISE, "app open: $error") }
                override fun onAppOpenFailedToShow(ad: AppOpenAd, error: AdError) = appOpenClosed()
                override fun onAppOpenImpression(ad: AppOpenAd) {}
                override fun onAppOpenLoaded(ad: AppOpenAd) = onLoaded()
                override fun onAppOpenRevenuePaid(ad: AppOpenAd, data: RevenueData) {}
                override fun onAppOpenShown(ad: AppOpenAd) {}
            }
        }
        if (ad.isAvailable) onLoaded() else ad.loadAd()
    }

    private fun appOpenClosed() {
        onAppOpenClosed?.invoke()
        onAppOpenClosed = null
    }

    override fun showAppOpen(activity: Activity, onClosed: () -> Unit): Boolean {
        val ad = appOpen?.takeIf { it.isAvailable } ?: return false
        onAppOpenClosed = onClosed
        ad.showAd(activity)
        return true
    }

    override fun banner(activity: Activity, size: BannerSize, onFailed: () -> Unit): View? {
        val mrec = size == BannerSize.MREC
        val unit = if (mrec) config.mrec else config.banner
        if (unit.isBlank() || !WortiseSdk.isInitialized) return null
        return BannerAd(activity).apply {
            adSize = AdSize(width = size.width, height = size.height)
            adUnitId = unit
            listener = object : BannerAd.Listener {
                override fun onBannerClicked(ad: BannerAd) {}
                override fun onBannerFailedToLoad(ad: BannerAd, error: AdError) { AdUtils.log(NetworkIds.WORTISE, "banner: $error"); onFailed() }
                override fun onBannerImpression(ad: BannerAd) {}
                override fun onBannerLoaded(ad: BannerAd) {}
                override fun onBannerRevenuePaid(ad: BannerAd, data: RevenueData) {}
            }
            loadAd()
        }
    }

    override fun destroyBanner(view: View) {
        (view as? BannerAd)?.destroy()
    }
}
