package com.fabads.startio

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
import com.startapp.sdk.ads.banner.Banner
import com.startapp.sdk.ads.banner.BannerListener
import com.startapp.sdk.ads.banner.Mrec
import com.startapp.sdk.adsbase.Ad
import com.startapp.sdk.adsbase.StartAppAd
import com.startapp.sdk.adsbase.StartAppSDK
import com.startapp.sdk.adsbase.adlisteners.AdDisplayListener
import com.startapp.sdk.adsbase.adlisteners.AdEventListener

class StartIoProvider : AdProvider {
    override val id = NetworkIds.STARTIO

    override fun create(context: Context, config: NetworkConfig, env: AdEnvironment): AdNetwork? =
        if (config.appId.isBlank()) null else StartIoNetwork(context, config, env)
}

/**
 * Start.io (StartApp): banner, medium rectangle, interstitial, rewarded video and app open (its splash ad is not
 * used) from one app id. Start.io serves every format from the app id, so each format is on unless the admin
 * sets that format's key to "off".
 */
class StartIoNetwork(private val context: Context, private val config: NetworkConfig, private val env: AdEnvironment) : AdNetwork {
    override val id = NetworkIds.STARTIO
    private var ready = false
    private val interstitial by lazy { StartAppAd(context) }
    private var interstitialLoading = false

    override fun supports(format: AdFormat) = when (format) {
        AdFormat.BANNER -> config.banner != "off"
        AdFormat.MREC -> config.mrec != "off"
        AdFormat.INTERSTITIAL -> config.interstitial != "off"
        AdFormat.REWARDED -> config.rewarded != "off"
        AdFormat.NATIVE, AdFormat.APP_OPEN -> false
    }

    override fun init(activity: Activity, onReady: () -> Unit) {
        if (!ready) {
            if (env.privacy.gdprApplies) StartAppSDK.setUserConsent(context, "pas", System.currentTimeMillis(), env.privacy.consent)
            StartAppSDK.init(context.applicationContext, config.appId, false)
            StartAppSDK.setTestAdsEnabled(env.testMode)
            StartAppSDK.enableReturnAds(false) // FabAds decides when full screen ads show
            StartAppAd.disableSplash()
            ready = true
        }
        onReady()
    }

    override fun loadInterstitial(activity: Activity) {
        if (!ready || interstitialLoading || interstitial.isReady || !supports(AdFormat.INTERSTITIAL)) return
        interstitialLoading = true
        interstitial.loadAd(StartAppAd.AdMode.AUTOMATIC, object : AdEventListener {
            override fun onReceiveAd(ad: Ad) { interstitialLoading = false }
            override fun onFailedToReceiveAd(ad: Ad?) { interstitialLoading = false; AdUtils.log(NetworkIds.STARTIO, "interstitial: ${ad?.errorMessage}") }
        })
    }

    override fun showInterstitial(activity: Activity, onClosed: () -> Unit): Boolean {
        if (!ready || !interstitial.isReady) return false
        return interstitial.showAd(object : AdDisplayListener {
            override fun adHidden(ad: Ad) = onClosed()
            override fun adDisplayed(ad: Ad) {}
            override fun adClicked(ad: Ad) {}
            override fun adNotDisplayed(ad: Ad) = onClosed()
        })
    }

    override fun showRewarded(activity: Activity, onResult: (Boolean?) -> Unit) {
        if (!ready || !supports(AdFormat.REWARDED)) return onResult(null)
        val ad = StartAppAd(activity)
        var earned = false
        ad.setVideoListener { earned = true }
        ad.loadAd(StartAppAd.AdMode.REWARDED_VIDEO, object : AdEventListener {
            override fun onReceiveAd(loaded: Ad) {
                val shown = ad.showAd(object : AdDisplayListener {
                    override fun adHidden(a: Ad) = onResult(earned)
                    override fun adDisplayed(a: Ad) {}
                    override fun adClicked(a: Ad) {}
                    override fun adNotDisplayed(a: Ad) = onResult(null)
                })
                if (!shown) onResult(null)
            }
            override fun onFailedToReceiveAd(failed: Ad?) = onResult(null)
        })
    }

    override fun banner(activity: Activity, size: BannerSize, onFailed: () -> Unit): View? {
        if (!ready || !supports(if (size == BannerSize.MREC) AdFormat.MREC else AdFormat.BANNER)) return null
        val listener = object : BannerListener {
            override fun onReceiveAd(view: View) {}
            override fun onFailedToReceiveAd(view: View) { AdUtils.log(NetworkIds.STARTIO, "banner: no fill"); onFailed() }
            override fun onImpression(view: View) {}
            override fun onClick(view: View) {}
        }
        return if (size == BannerSize.MREC) Mrec(activity, listener) else Banner(activity, listener)
    }
}
