package com.fabads.yandex

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
import com.yandex.mobile.ads.appopenad.AppOpenAd
import com.yandex.mobile.ads.appopenad.AppOpenAdEventListener
import com.yandex.mobile.ads.appopenad.AppOpenAdLoadListener
import com.yandex.mobile.ads.appopenad.AppOpenAdLoader
import com.yandex.mobile.ads.banner.BannerAdEventListener
import com.yandex.mobile.ads.banner.BannerAdSize
import com.yandex.mobile.ads.banner.BannerAdView
import com.yandex.mobile.ads.common.AdError
import com.yandex.mobile.ads.common.AdRequest
import com.yandex.mobile.ads.common.AdRequestError
import com.yandex.mobile.ads.common.ImpressionData
import com.yandex.mobile.ads.common.InitializationListener
import com.yandex.mobile.ads.common.YandexAds
import com.yandex.mobile.ads.interstitial.InterstitialAd
import com.yandex.mobile.ads.interstitial.InterstitialAdEventListener
import com.yandex.mobile.ads.interstitial.InterstitialAdLoadListener
import com.yandex.mobile.ads.interstitial.InterstitialAdLoader
import com.yandex.mobile.ads.rewarded.Reward
import com.yandex.mobile.ads.rewarded.RewardedAd
import com.yandex.mobile.ads.rewarded.RewardedAdEventListener
import com.yandex.mobile.ads.rewarded.RewardedAdLoadListener
import com.yandex.mobile.ads.rewarded.RewardedAdLoader

class YandexProvider : AdProvider {
    override val id = NetworkIds.YANDEX
    override fun create(context: Context, config: NetworkConfig, env: AdEnvironment) = YandexNetwork(context, config, env)
}

/** Yandex Ads: banner, medium rectangle, interstitial, rewarded and app open. Units look like `R-M-1234567-1`. */
class YandexNetwork(private val context: Context, private val config: NetworkConfig, private val env: AdEnvironment) : AdNetwork {
    override val id = NetworkIds.YANDEX
    private var interstitial: InterstitialAd? = null
    private var interstitialLoading = false
    private var appOpen: AppOpenAd? = null

    /** Yandex's demo units serve test ads. */
    private fun unit(value: String, demo: String) = if (env.testMode) demo else value

    override fun supports(format: AdFormat) = when (format) {
        AdFormat.BANNER -> env.testMode || config.banner.isNotBlank()
        AdFormat.MREC -> env.testMode || config.mrec.isNotBlank()
        AdFormat.INTERSTITIAL -> env.testMode || config.interstitial.isNotBlank()
        AdFormat.REWARDED -> env.testMode || config.rewarded.isNotBlank()
        AdFormat.APP_OPEN -> env.testMode || config.appOpen.isNotBlank()
        AdFormat.NATIVE -> false
    }

    override fun init(activity: Activity, onReady: () -> Unit) {
        YandexAds.setUserConsent(env.privacy.consent && !env.privacy.doNotSell)
        YandexAds.setAgeRestricted(env.privacy.childDirected)
        YandexAds.enableLogging(env.debug)
        YandexAds.initialize(context, object : InitializationListener {
            override fun onInitializationCompleted() = onReady()
        })
    }

    override fun loadInterstitial(activity: Activity) {
        if (interstitial != null || interstitialLoading || !supports(AdFormat.INTERSTITIAL)) return
        interstitialLoading = true
        InterstitialAdLoader(context).loadAd(
            AdRequest.Builder(unit(config.interstitial, "demo-interstitial-yandex")).build(),
            object : InterstitialAdLoadListener {
                override fun onAdLoaded(ad: InterstitialAd) { interstitialLoading = false; interstitial = ad }
                override fun onAdFailedToLoad(error: AdRequestError) { interstitialLoading = false; AdUtils.log(NetworkIds.YANDEX, "interstitial: ${error.description}") }
            },
        )
    }

    override fun showInterstitial(activity: Activity, onClosed: () -> Unit): Boolean {
        val ad = interstitial ?: return false
        interstitial = null
        ad.setAdEventListener(object : InterstitialAdEventListener {
            override fun onAdShown() {}
            override fun onAdFailedToShow(error: AdError) = onClosed()
            override fun onAdDismissed() = onClosed()
            override fun onAdClicked() {}
            override fun onAdImpression(data: ImpressionData?) {}
        })
        ad.show(activity)
        return true
    }

    override fun showRewarded(activity: Activity, onResult: (Boolean?) -> Unit) {
        if (!supports(AdFormat.REWARDED)) return onResult(null)
        RewardedAdLoader(context).loadAd(
            AdRequest.Builder(unit(config.rewarded, "demo-rewarded-yandex")).build(),
            object : RewardedAdLoadListener {
                override fun onAdFailedToLoad(error: AdRequestError) {
                    AdUtils.log(NetworkIds.YANDEX, "rewarded: ${error.description}")
                    onResult(null)
                }

                override fun onAdLoaded(ad: RewardedAd) {
                    var earned = false
                    ad.setAdEventListener(object : RewardedAdEventListener {
                        override fun onAdShown() {}
                        override fun onAdFailedToShow(error: AdError) = onResult(null)
                        override fun onAdDismissed() = onResult(earned)
                        override fun onAdClicked() {}
                        override fun onAdImpression(data: ImpressionData?) {}
                        override fun onRewarded(reward: Reward) { earned = true }
                    })
                    ad.show(activity)
                }
            },
        )
    }

    override fun loadAppOpen(activity: Activity, onLoaded: () -> Unit) {
        if (appOpen != null || !supports(AdFormat.APP_OPEN)) return
        AppOpenAdLoader(context).loadAd(
            AdRequest.Builder(unit(config.appOpen, "demo-appopenad-yandex")).build(),
            object : AppOpenAdLoadListener {
                override fun onAdLoaded(ad: AppOpenAd) { appOpen = ad; onLoaded() }
                override fun onAdFailedToLoad(error: AdRequestError) { AdUtils.log(NetworkIds.YANDEX, "app open: ${error.description}") }
            },
        )
    }

    override fun showAppOpen(activity: Activity, onClosed: () -> Unit): Boolean {
        val ad = appOpen ?: return false
        appOpen = null
        ad.setAdEventListener(object : AppOpenAdEventListener {
            override fun onAdShown() {}
            override fun onAdFailedToShow(error: AdError) = onClosed()
            override fun onAdDismissed() = onClosed()
            override fun onAdClicked() {}
            override fun onAdImpression(data: ImpressionData?) {}
        })
        ad.show(activity)
        return true
    }

    override fun banner(activity: Activity, size: BannerSize, onFailed: () -> Unit): View? {
        val mrec = size == BannerSize.MREC
        if (!supports(if (mrec) AdFormat.MREC else AdFormat.BANNER)) return null
        val unitId = unit(if (mrec) config.mrec else config.banner, "demo-banner-yandex")
        return BannerAdView(activity).apply {
            setAdSize(if (mrec) BannerAdSize.fixed(activity, size.width, size.height) else BannerAdSize.sticky(activity, size.width))
            setBannerAdEventListener(object : BannerAdEventListener {
                override fun onAdLoaded() {}
                override fun onAdFailedToLoad(error: AdRequestError) {
                    AdUtils.log(NetworkIds.YANDEX, "banner: ${error.description}")
                    onFailed()
                }
                override fun onAdClicked() {}
                override fun onImpression(data: ImpressionData?) {}
            })
            loadAd(AdRequest.Builder(unitId).build())
        }
    }

    override fun destroyBanner(view: View) {
        (view as? BannerAdView)?.destroy()
    }
}
