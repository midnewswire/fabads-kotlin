package com.fabads.applovin

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.view.View
import android.view.ViewGroup
import com.applovin.mediation.MaxAd
import com.applovin.mediation.MaxAdFormat
import com.applovin.mediation.MaxAdListener
import com.applovin.mediation.MaxAdViewAdListener
import com.applovin.mediation.MaxError
import com.applovin.mediation.MaxReward
import com.applovin.mediation.MaxRewardedAdListener
import com.applovin.mediation.ads.MaxAdView
import com.applovin.mediation.ads.MaxAppOpenAd
import com.applovin.mediation.ads.MaxInterstitialAd
import com.applovin.mediation.ads.MaxRewardedAd
import com.applovin.mediation.nativeAds.MaxNativeAdListener
import com.applovin.mediation.nativeAds.MaxNativeAdLoader
import com.applovin.mediation.nativeAds.MaxNativeAdView
import com.applovin.sdk.AppLovinPrivacySettings
import com.applovin.sdk.AppLovinSdk
import com.applovin.sdk.AppLovinSdkInitializationConfiguration
import com.fabads.AdEnvironment
import com.fabads.AdFormat
import com.fabads.AdNetwork
import com.fabads.AdProvider
import com.fabads.AdUtils
import com.fabads.BannerSize
import com.fabads.NativeStyle
import com.fabads.NetworkConfig
import com.fabads.NetworkIds

class AppLovinProvider : AdProvider {
    override val id = NetworkIds.APPLOVIN

    override fun create(context: Context, config: NetworkConfig, env: AdEnvironment): AdNetwork? {
        // The SDK key comes from the admin (`sdk_key`) or from the manifest (`applovin.sdk.key`).
        val manifestKey = runCatching {
            context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
                .metaData?.getString("applovin.sdk.key")
        }.getOrNull()
        val key = config.string("sdk_key").ifBlank { manifestKey.orEmpty() }
        return if (key.isBlank()) null else AppLovinNetwork(context, config, env, key)
    }
}

/** AppLovin MAX: banner, MREC, native (MAX templates), interstitial, rewarded and app open. */
class AppLovinNetwork(
    private val context: Context,
    private val config: NetworkConfig,
    private val env: AdEnvironment,
    private val sdkKey: String,
) : AdNetwork {
    override val id = NetworkIds.APPLOVIN
    private val sdk get() = AppLovinSdk.getInstance(context)
    private var interstitial: MaxInterstitialAd? = null
    private var appOpen: MaxAppOpenAd? = null
    private var onInterstitialClosed: (() -> Unit)? = null
    private var onAppOpenClosed: (() -> Unit)? = null

    override fun supports(format: AdFormat) = when (format) {
        AdFormat.BANNER -> config.banner.isNotBlank()
        AdFormat.MREC -> config.mrec.isNotBlank()
        AdFormat.INTERSTITIAL -> config.interstitial.isNotBlank()
        AdFormat.REWARDED -> config.rewarded.isNotBlank()
        AdFormat.NATIVE -> config.native.isNotBlank()
        AdFormat.APP_OPEN -> config.appOpen.isNotBlank()
    }

    override fun init(activity: Activity, onReady: () -> Unit) {
        if (env.privacy.gdprApplies) AppLovinPrivacySettings.setHasUserConsent(env.privacy.consent)
        AppLovinPrivacySettings.setDoNotSell(env.privacy.doNotSell)
        if (sdk.isInitialized) return onReady()
        val init = AppLovinSdkInitializationConfiguration.builder(sdkKey).setMediationProvider("max").build()
        sdk.settings.setVerboseLogging(env.debug)
        sdk.initialize(init) { onReady() }
    }

    override fun loadInterstitial(activity: Activity) {
        if (!supports(AdFormat.INTERSTITIAL) || !sdk.isInitialized) return
        val ad = interstitial ?: MaxInterstitialAd(config.interstitial).also { ad ->
            interstitial = ad
            ad.setListener(fullScreenListener("interstitial", {}) { onInterstitialClosed?.invoke(); onInterstitialClosed = null })
        }
        if (!ad.isReady) ad.loadAd()
    }

    override fun showInterstitial(activity: Activity, onClosed: () -> Unit): Boolean {
        val ad = interstitial?.takeIf { it.isReady } ?: return false
        onInterstitialClosed = onClosed
        ad.showAd(activity)
        return true
    }

    override fun showRewarded(activity: Activity, onResult: (Boolean?) -> Unit) {
        if (!supports(AdFormat.REWARDED) || !sdk.isInitialized) return onResult(null)
        val ad = MaxRewardedAd.getInstance(config.rewarded)
        var earned = false
        var shown = false
        ad.setListener(object : MaxRewardedAdListener {
            override fun onAdLoaded(a: MaxAd) { if (!shown) { shown = true; ad.showAd(activity) } }
            override fun onAdLoadFailed(unit: String, e: MaxError) { AdUtils.log(NetworkIds.APPLOVIN, "rewarded: ${e.message}"); if (!shown) onResult(null) }
            override fun onUserRewarded(a: MaxAd, r: MaxReward) { earned = true }
            override fun onAdHidden(a: MaxAd) = onResult(earned)
            override fun onAdDisplayFailed(a: MaxAd, e: MaxError) = onResult(null)
            override fun onAdDisplayed(a: MaxAd) {}
            override fun onAdClicked(a: MaxAd) {}
        })
        if (ad.isReady) { shown = true; ad.showAd(activity) } else ad.loadAd()
    }

    override fun loadAppOpen(activity: Activity, onLoaded: () -> Unit) {
        if (!supports(AdFormat.APP_OPEN) || !sdk.isInitialized) return
        val ad = appOpen ?: MaxAppOpenAd(config.appOpen).also { ad ->
            appOpen = ad
            ad.setListener(fullScreenListener("app open", onLoaded) { onAppOpenClosed?.invoke(); onAppOpenClosed = null })
        }
        if (ad.isReady) onLoaded() else ad.loadAd()
    }

    override fun showAppOpen(activity: Activity, onClosed: () -> Unit): Boolean {
        val ad = appOpen?.takeIf { it.isReady } ?: return false
        onAppOpenClosed = onClosed
        ad.showAd()
        return true
    }

    private fun fullScreenListener(name: String, onLoaded: () -> Unit, onClosed: () -> Unit) = object : MaxAdListener {
        override fun onAdLoaded(ad: MaxAd) = onLoaded()
        override fun onAdDisplayed(ad: MaxAd) {}
        override fun onAdHidden(ad: MaxAd) = onClosed()
        override fun onAdClicked(ad: MaxAd) {}
        override fun onAdLoadFailed(unit: String, error: MaxError) { AdUtils.log(NetworkIds.APPLOVIN, "$name: ${error.message}") }
        override fun onAdDisplayFailed(ad: MaxAd, error: MaxError) = onClosed()
    }

    override fun banner(activity: Activity, size: BannerSize, onFailed: () -> Unit): View? {
        val mrec = size == BannerSize.MREC
        if (!supports(if (mrec) AdFormat.MREC else AdFormat.BANNER) || !sdk.isInitialized) return null
        return MaxAdView(if (mrec) config.mrec else config.banner, if (mrec) MaxAdFormat.MREC else MaxAdFormat.BANNER).apply {
            layoutParams = ViewGroup.LayoutParams(AdUtils.dp(activity, size.width), AdUtils.dp(activity, size.height))
            setListener(object : MaxAdViewAdListener {
                override fun onAdLoaded(ad: MaxAd) {}
                override fun onAdDisplayed(ad: MaxAd) {}
                override fun onAdHidden(ad: MaxAd) {}
                override fun onAdClicked(ad: MaxAd) {}
                override fun onAdLoadFailed(unit: String, e: MaxError) { AdUtils.log(NetworkIds.APPLOVIN, "banner: ${e.message}"); onFailed() }
                override fun onAdDisplayFailed(ad: MaxAd, e: MaxError) {}
                override fun onAdExpanded(ad: MaxAd) {}
                override fun onAdCollapsed(ad: MaxAd) {}
            })
            loadAd()
        }
    }

    override fun destroyBanner(view: View) {
        (view as? MaxAdView)?.destroy()
    }

    /** MAX renders its own native templates (small or medium) from the dashboard settings. */
    override fun native(activity: Activity, style: NativeStyle, dark: Boolean, onResult: (View?) -> Unit) {
        if (!supports(AdFormat.NATIVE) || !sdk.isInitialized) return onResult(null)
        val loader = MaxNativeAdLoader(config.native)
        loader.setNativeAdListener(object : MaxNativeAdListener() {
            override fun onNativeAdLoaded(view: MaxNativeAdView?, ad: MaxAd) {
                if (view == null) return onResult(null)
                view.tag = loader to ad
                onResult(view)
            }
            override fun onNativeAdLoadFailed(unit: String, e: MaxError) {
                AdUtils.log(NetworkIds.APPLOVIN, "native: ${e.message}")
                onResult(null)
            }
        })
        loader.loadAd()
    }

    override fun destroyNative(view: View) {
        @Suppress("UNCHECKED_CAST")
        (view.tag as? Pair<MaxNativeAdLoader, MaxAd>)?.let { (loader, ad) -> loader.destroy(ad); loader.destroy() }
    }
}
