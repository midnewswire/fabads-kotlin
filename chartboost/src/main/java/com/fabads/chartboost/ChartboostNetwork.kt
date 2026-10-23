package com.fabads.chartboost

import android.app.Activity
import android.content.Context
import android.view.View
import com.chartboost.sdk.Chartboost
import com.chartboost.sdk.ads.Banner
import com.chartboost.sdk.ads.Interstitial
import com.chartboost.sdk.ads.Rewarded
import com.chartboost.sdk.callbacks.BannerCallback
import com.chartboost.sdk.callbacks.InterstitialCallback
import com.chartboost.sdk.callbacks.RewardedCallback
import com.chartboost.sdk.callbacks.StartCallback
import com.chartboost.sdk.events.CacheError
import com.chartboost.sdk.events.CacheEvent
import com.chartboost.sdk.events.ClickError
import com.chartboost.sdk.events.ClickEvent
import com.chartboost.sdk.events.DismissEvent
import com.chartboost.sdk.events.ImpressionEvent
import com.chartboost.sdk.events.RewardEvent
import com.chartboost.sdk.events.ShowError
import com.chartboost.sdk.events.ShowEvent
import com.chartboost.sdk.events.StartError
import com.chartboost.sdk.privacy.model.COPPA
import com.chartboost.sdk.privacy.model.CCPA
import com.chartboost.sdk.privacy.model.GDPR
import com.fabads.AdEnvironment
import com.fabads.AdFormat
import com.fabads.AdNetwork
import com.fabads.AdProvider
import com.fabads.AdUtils
import com.fabads.BannerSize
import com.fabads.NetworkConfig
import com.fabads.NetworkIds

class ChartboostProvider : AdProvider {
    override val id = NetworkIds.CHARTBOOST

    override fun create(context: Context, config: NetworkConfig, env: AdEnvironment): AdNetwork? =
        if (config.appId.isBlank() || config.string("app_signature").isBlank()) null else ChartboostNetwork(context, config, env)
}

/**
 * Chartboost: banner, medium rectangle, interstitial and rewarded. Units are Chartboost locations
 * (any name, "Default" works).
 */
class ChartboostNetwork(private val context: Context, private val config: NetworkConfig, private val env: AdEnvironment) : AdNetwork {
    override val id = NetworkIds.CHARTBOOST
    private var interstitial: Interstitial? = null
    private var onInterstitialClosed: (() -> Unit)? = null

    override fun supports(format: AdFormat) = when (format) {
        AdFormat.BANNER -> config.banner.isNotBlank()
        AdFormat.MREC -> config.mrec.isNotBlank()
        AdFormat.INTERSTITIAL -> config.interstitial.isNotBlank()
        AdFormat.REWARDED -> config.rewarded.isNotBlank()
        AdFormat.NATIVE, AdFormat.APP_OPEN -> false
    }

    override fun init(activity: Activity, onReady: () -> Unit) {
        if (env.privacy.gdprApplies) {
            Chartboost.addDataUseConsent(context, GDPR(if (env.privacy.consent) GDPR.GDPR_CONSENT.BEHAVIORAL else GDPR.GDPR_CONSENT.NON_BEHAVIORAL))
        }
        Chartboost.addDataUseConsent(context, CCPA(if (env.privacy.doNotSell) CCPA.CCPA_CONSENT.OPT_OUT_SALE else CCPA.CCPA_CONSENT.OPT_IN_SALE))
        if (env.privacy.childDirected) Chartboost.addDataUseConsent(context, COPPA(true))
        if (Chartboost.isSdkStarted()) return onReady()
        Chartboost.startWithAppId(context, config.appId, config.string("app_signature"), object : StartCallback {
            override fun onStartCompleted(error: StartError?) {
                if (error != null) AdUtils.log(NetworkIds.CHARTBOOST, "init: ${error.code}")
                onReady()
            }
        })
    }

    /** Chartboost's callbacks; each format overrides the ones it needs. */
    private open inner class Callback(private val name: String) : InterstitialCallback, RewardedCallback, BannerCallback {
        override fun onAdLoaded(event: CacheEvent, error: CacheError?) {
            if (error != null) AdUtils.log(NetworkIds.CHARTBOOST, "$name: ${error.code}")
        }
        override fun onAdRequestedToShow(event: ShowEvent) {}
        override fun onAdShown(event: ShowEvent, error: ShowError?) {}
        override fun onAdClicked(event: ClickEvent, error: ClickError?) {}
        override fun onImpressionRecorded(event: ImpressionEvent) {}
        override fun onAdDismiss(event: DismissEvent) {}
        override fun onRewardEarned(event: RewardEvent) {}
    }

    override fun loadInterstitial(activity: Activity) {
        if (!supports(AdFormat.INTERSTITIAL) || !Chartboost.isSdkStarted()) return
        val ad = interstitial ?: Interstitial(config.interstitial, object : Callback("interstitial") {
            override fun onAdShown(event: ShowEvent, error: ShowError?) { if (error != null) closed() }
            override fun onAdDismiss(event: DismissEvent) = closed()
        }, null).also { interstitial = it }
        if (!ad.isCached()) ad.cache()
    }

    private fun closed() {
        onInterstitialClosed?.invoke()
        onInterstitialClosed = null
    }

    override fun showInterstitial(activity: Activity, onClosed: () -> Unit): Boolean {
        val ad = interstitial?.takeIf { it.isCached() } ?: return false
        onInterstitialClosed = onClosed
        ad.show()
        return true
    }

    override fun showRewarded(activity: Activity, onResult: (Boolean?) -> Unit) {
        if (!supports(AdFormat.REWARDED) || !Chartboost.isSdkStarted()) return onResult(null)
        var earned = false
        lateinit var ad: Rewarded
        ad = Rewarded(config.rewarded, object : Callback("rewarded") {
            override fun onAdLoaded(event: CacheEvent, error: CacheError?) {
                super.onAdLoaded(event, error)
                if (error == null) ad.show() else onResult(null)
            }
            override fun onAdShown(event: ShowEvent, error: ShowError?) { if (error != null) onResult(null) }
            override fun onRewardEarned(event: RewardEvent) { earned = true }
            override fun onAdDismiss(event: DismissEvent) = onResult(earned)
        }, null)
        ad.cache()
    }

    override fun banner(activity: Activity, size: BannerSize, onFailed: () -> Unit): View? {
        val mrec = size == BannerSize.MREC
        val location = if (mrec) config.mrec else config.banner
        if (location.isBlank() || !Chartboost.isSdkStarted()) return null
        lateinit var banner: Banner
        banner = Banner(activity, location, if (mrec) Banner.BannerSize.MEDIUM else Banner.BannerSize.STANDARD, object : Callback("banner") {
            override fun onAdLoaded(event: CacheEvent, error: CacheError?) {
                super.onAdLoaded(event, error)
                if (error == null) banner.show() else onFailed()
            }
        }, null)
        banner.cache()
        return banner
    }

    override fun destroyBanner(view: View) {
        (view as? Banner)?.detach()
    }
}
