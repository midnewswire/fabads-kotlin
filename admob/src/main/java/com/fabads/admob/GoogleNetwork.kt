package com.fabads.admob

import android.app.Activity
import android.content.Context
import android.view.View
import com.fabads.AdEnvironment
import com.fabads.AdFormat
import com.fabads.AdNetwork
import com.fabads.AdProvider
import com.fabads.AdUtils
import com.fabads.BannerSize
import com.fabads.NativeStyle
import com.fabads.NativeTemplate
import com.fabads.NetworkConfig
import com.fabads.NetworkIds
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.BaseAdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.google.android.gms.ads.admanager.AdManagerAdRequest
import com.google.android.gms.ads.admanager.AdManagerAdView
import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.nativead.MediaView
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdView
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback

class AdMobProvider : AdProvider {
    override val id = NetworkIds.ADMOB
    override fun create(context: Context, config: NetworkConfig, env: AdEnvironment) = GoogleNetwork(context, config, env, adManager = false)
}

class AdManagerProvider : AdProvider {
    override val id = NetworkIds.AD_MANAGER
    override fun create(context: Context, config: NetworkConfig, env: AdEnvironment) = GoogleNetwork(context, config, env, adManager = true)
}

/**
 * Google AdMob or Google Ad Manager: banner, medium rectangle, native, interstitial, rewarded and app open.
 * The AdMob app id goes in the app's manifest (`com.google.android.gms.ads.APPLICATION_ID`), not in the config.
 */
class GoogleNetwork(
    private val context: Context,
    config: NetworkConfig,
    private val env: AdEnvironment,
    private val adManager: Boolean,
) : AdNetwork {
    override val id = if (adManager) NetworkIds.AD_MANAGER else NetworkIds.ADMOB

    private val units: Map<AdFormat, String> = mapOf(
        AdFormat.BANNER to config.banner,
        AdFormat.MREC to config.mrec,
        AdFormat.INTERSTITIAL to config.interstitial,
        AdFormat.REWARDED to config.rewarded,
        AdFormat.NATIVE to config.native,
        AdFormat.APP_OPEN to config.appOpen,
    ).mapValues { (format, unit) -> if (env.testMode) testUnit(format) else unit }

    private var interstitial: InterstitialAd? = null
    private var interstitialLoading = false
    private var appOpen: AppOpenAd? = null

    override fun supports(format: AdFormat) = units[format].orEmpty().isNotBlank()

    private fun unit(format: AdFormat) = units[format].orEmpty()

    private fun request(): AdRequest = if (adManager) AdManagerAdRequest.Builder().build() else AdRequest.Builder().build()

    override fun init(activity: Activity, onReady: () -> Unit) {
        if (env.privacy.childDirected) {
            MobileAds.setRequestConfiguration(
                MobileAds.getRequestConfiguration().toBuilder()
                    .setTagForChildDirectedTreatment(RequestConfiguration.TAG_FOR_CHILD_DIRECTED_TREATMENT_TRUE)
                    .setMaxAdContentRating(RequestConfiguration.MAX_AD_CONTENT_RATING_G)
                    .build()
            )
        }
        Thread { MobileAds.initialize(context) { onReady() } }.start()
    }

    override fun loadInterstitial(activity: Activity) {
        if (interstitial != null || interstitialLoading || !supports(AdFormat.INTERSTITIAL)) return
        interstitialLoading = true
        InterstitialAd.load(context, unit(AdFormat.INTERSTITIAL), request(), object : InterstitialAdLoadCallback() {
            override fun onAdLoaded(ad: InterstitialAd) { interstitialLoading = false; interstitial = ad }
            override fun onAdFailedToLoad(error: LoadAdError) { interstitialLoading = false; AdUtils.log(id, "interstitial: ${error.message}") }
        })
    }

    override fun showInterstitial(activity: Activity, onClosed: () -> Unit): Boolean {
        val ad = interstitial ?: return false
        interstitial = null
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() = onClosed()
            override fun onAdFailedToShowFullScreenContent(error: com.google.android.gms.ads.AdError) = onClosed()
        }
        ad.show(activity)
        return true
    }

    override fun showRewarded(activity: Activity, onResult: (Boolean?) -> Unit) {
        if (!supports(AdFormat.REWARDED)) return onResult(null)
        RewardedAd.load(context, unit(AdFormat.REWARDED), request(), object : RewardedAdLoadCallback() {
            override fun onAdFailedToLoad(error: LoadAdError) {
                AdUtils.log(id, "rewarded: ${error.message}")
                onResult(null)
            }

            override fun onAdLoaded(ad: RewardedAd) {
                var earned = false
                ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                    override fun onAdDismissedFullScreenContent() = onResult(earned)
                    override fun onAdFailedToShowFullScreenContent(error: com.google.android.gms.ads.AdError) = onResult(null)
                }
                ad.show(activity) { earned = true }
            }
        })
    }

    override fun loadAppOpen(activity: Activity, onLoaded: () -> Unit) {
        if (appOpen != null || !supports(AdFormat.APP_OPEN)) return
        AppOpenAd.load(context, unit(AdFormat.APP_OPEN), request(), object : AppOpenAd.AppOpenAdLoadCallback() {
            override fun onAdLoaded(ad: AppOpenAd) { appOpen = ad; onLoaded() }
            override fun onAdFailedToLoad(error: LoadAdError) { AdUtils.log(id, "app open: ${error.message}") }
        })
    }

    override fun showAppOpen(activity: Activity, onClosed: () -> Unit): Boolean {
        val ad = appOpen ?: return false
        appOpen = null
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() = onClosed()
            override fun onAdFailedToShowFullScreenContent(error: com.google.android.gms.ads.AdError) = onClosed()
        }
        ad.show(activity)
        return true
    }

    override fun banner(activity: Activity, size: BannerSize, onFailed: () -> Unit): View? {
        val format = if (size == BannerSize.MREC) AdFormat.MREC else AdFormat.BANNER
        if (!supports(format)) return null
        val adSize = if (size == BannerSize.MREC) AdSize.MEDIUM_RECTANGLE else AdSize.BANNER
        val view: BaseAdView = if (adManager) AdManagerAdView(activity).apply { setAdSizes(adSize) } else AdView(activity).apply { setAdSize(adSize) }
        view.adUnitId = unit(format)
        view.adListener = object : AdListener() {
            override fun onAdFailedToLoad(error: LoadAdError) {
                AdUtils.log(id, "banner: ${error.message}")
                onFailed()
            }
        }
        view.loadAd(request())
        return view
    }

    override fun destroyBanner(view: View) {
        (view as? BaseAdView)?.destroy()
    }

    override fun native(activity: Activity, style: NativeStyle, dark: Boolean, onResult: (View?) -> Unit) {
        if (!supports(AdFormat.NATIVE)) return onResult(null)
        AdLoader.Builder(activity, unit(AdFormat.NATIVE))
            .forNativeAd { ad -> onResult(render(activity, ad, style, dark)) }
            .withAdListener(object : AdListener() {
                override fun onAdFailedToLoad(error: LoadAdError) {
                    AdUtils.log(id, "native: ${error.message}")
                    onResult(null)
                }
            })
            .build()
            .loadAd(request())
    }

    private fun render(activity: Activity, ad: NativeAd, style: NativeStyle, dark: Boolean): View {
        val template = NativeTemplate(activity, style, dark)
        template.bind(ad.headline, ad.body, ad.advertiser ?: ad.store, ad.callToAction, ad.icon?.drawable)
        val adView = NativeAdView(activity)
        adView.addView(template.root)
        template.media?.let {
            val media = MediaView(activity)
            template.useMediaView(media)
            adView.mediaView = media
        }
        adView.headlineView = template.headline
        adView.bodyView = template.body
        adView.advertiserView = template.advertiser
        adView.callToActionView = template.cta
        adView.iconView = template.icon
        adView.setNativeAd(ad)
        adView.tag = ad
        return adView
    }

    override fun destroyNative(view: View) {
        (view.tag as? NativeAd)?.destroy()
        (view as? NativeAdView)?.destroy()
    }

    private fun testUnit(format: AdFormat): String = if (adManager) {
        when (format) {
            AdFormat.BANNER, AdFormat.MREC -> "/21775744923/example/fixed-size-banner"
            AdFormat.INTERSTITIAL -> "/21775744923/example/interstitial"
            AdFormat.REWARDED -> "/21775744923/example/rewarded"
            AdFormat.NATIVE -> "/21775744923/example/native"
            AdFormat.APP_OPEN -> "/21775744923/example/app-open"
        }
    } else {
        when (format) {
            AdFormat.BANNER, AdFormat.MREC -> "ca-app-pub-3940256099942544/6300978111"
            AdFormat.INTERSTITIAL -> "ca-app-pub-3940256099942544/1033173712"
            AdFormat.REWARDED -> "ca-app-pub-3940256099942544/5224354917"
            AdFormat.NATIVE -> "ca-app-pub-3940256099942544/2247696110"
            AdFormat.APP_OPEN -> "ca-app-pub-3940256099942544/9257395921"
        }
    }
}
