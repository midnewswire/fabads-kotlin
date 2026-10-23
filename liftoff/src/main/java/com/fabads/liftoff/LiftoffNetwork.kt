package com.fabads.liftoff

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
import com.vungle.ads.AdConfig
import com.vungle.ads.BannerAdListener
import com.vungle.ads.BaseAd
import com.vungle.ads.InitializationListener
import com.vungle.ads.InterstitialAd
import com.vungle.ads.InterstitialAdListener
import com.vungle.ads.RewardedAd
import com.vungle.ads.RewardedAdListener
import com.vungle.ads.VungleAdSize
import com.vungle.ads.VungleAds
import com.vungle.ads.VungleBannerView
import com.vungle.ads.VungleError
import com.vungle.ads.VunglePrivacySettings

class LiftoffProvider : AdProvider {
    override val id = NetworkIds.LIFTOFF

    override fun create(context: Context, config: NetworkConfig, env: AdEnvironment): AdNetwork? =
        if (config.appId.isBlank()) null else LiftoffNetwork(context, config, env)
}

/** Liftoff Monetize (formerly Vungle): banner, medium rectangle, interstitial and rewarded. Units are placement ids. */
class LiftoffNetwork(private val context: Context, private val config: NetworkConfig, private val env: AdEnvironment) : AdNetwork {
    override val id = NetworkIds.LIFTOFF
    private var interstitial: InterstitialAd? = null
    private var interstitialReady = false
    private var onInterstitialClosed: (() -> Unit)? = null

    override fun supports(format: AdFormat) = when (format) {
        AdFormat.BANNER -> config.banner.isNotBlank()
        AdFormat.MREC -> config.mrec.isNotBlank()
        AdFormat.INTERSTITIAL -> config.interstitial.isNotBlank()
        AdFormat.REWARDED -> config.rewarded.isNotBlank()
        AdFormat.NATIVE, AdFormat.APP_OPEN -> false
    }

    override fun init(activity: Activity, onReady: () -> Unit) {
        if (env.privacy.gdprApplies) VunglePrivacySettings.setGDPRStatus(env.privacy.consent, "1.0.0")
        VunglePrivacySettings.setCCPAStatus(!env.privacy.doNotSell)
        if (env.privacy.childDirected) VunglePrivacySettings.setCOPPAStatus(true)
        if (VungleAds.isInitialized()) return onReady()
        VungleAds.init(context, config.appId, object : InitializationListener {
            override fun onSuccess() = onReady()
            override fun onError(vungleError: VungleError) {
                AdUtils.log(NetworkIds.LIFTOFF, "init: ${vungleError.errorMessage}")
                onReady()
            }
        })
    }

    /** Liftoff calls every listener method; only some matter for each format. */
    private open inner class Listener(private val name: String) : RewardedAdListener, BannerAdListener {
        override fun onAdLoaded(baseAd: BaseAd) {}
        override fun onAdStart(baseAd: BaseAd) {}
        override fun onAdImpression(baseAd: BaseAd) {}
        override fun onAdEnd(baseAd: BaseAd) {}
        override fun onAdClicked(baseAd: BaseAd) {}
        override fun onAdLeftApplication(baseAd: BaseAd) {}
        override fun onAdRewarded(baseAd: BaseAd) {}
        override fun onAdFailedToLoad(baseAd: BaseAd, adError: VungleError) { AdUtils.log(NetworkIds.LIFTOFF, "$name: ${adError.errorMessage}") }
        override fun onAdFailedToPlay(baseAd: BaseAd, adError: VungleError) { AdUtils.log(NetworkIds.LIFTOFF, "$name: ${adError.errorMessage}") }
    }

    override fun loadInterstitial(activity: Activity) {
        if (interstitialReady || !supports(AdFormat.INTERSTITIAL) || !VungleAds.isInitialized()) return
        val ad = InterstitialAd(context, config.interstitial, AdConfig())
        ad.adListener = object : Listener("interstitial"), InterstitialAdListener {
            override fun onAdLoaded(baseAd: BaseAd) { interstitialReady = true }
            override fun onAdEnd(baseAd: BaseAd) = closed()
            override fun onAdFailedToPlay(baseAd: BaseAd, adError: VungleError) = closed()
        }
        interstitial = ad
        ad.load()
    }

    private fun closed() {
        onInterstitialClosed?.invoke()
        onInterstitialClosed = null
    }

    override fun showInterstitial(activity: Activity, onClosed: () -> Unit): Boolean {
        val ad = interstitial ?: return false
        if (!interstitialReady || ad.canPlayAd() != true) return false
        interstitialReady = false
        onInterstitialClosed = onClosed
        ad.play(activity)
        return true
    }

    override fun showRewarded(activity: Activity, onResult: (Boolean?) -> Unit) {
        if (!supports(AdFormat.REWARDED) || !VungleAds.isInitialized()) return onResult(null)
        val ad = RewardedAd(context, config.rewarded, AdConfig())
        var earned = false
        ad.adListener = object : Listener("rewarded") {
            override fun onAdLoaded(baseAd: BaseAd) = ad.play(activity)
            override fun onAdRewarded(baseAd: BaseAd) { earned = true }
            override fun onAdEnd(baseAd: BaseAd) = onResult(earned)
            override fun onAdFailedToLoad(baseAd: BaseAd, adError: VungleError) {
                super.onAdFailedToLoad(baseAd, adError)
                onResult(null)
            }
            override fun onAdFailedToPlay(baseAd: BaseAd, adError: VungleError) {
                super.onAdFailedToPlay(baseAd, adError)
                onResult(null)
            }
        }
        ad.load()
    }

    override fun banner(activity: Activity, size: BannerSize, onFailed: () -> Unit): View? {
        val mrec = size == BannerSize.MREC
        val unit = if (mrec) config.mrec else config.banner
        if (unit.isBlank() || !VungleAds.isInitialized()) return null
        return VungleBannerView(activity, unit, if (mrec) VungleAdSize.MREC else VungleAdSize.BANNER).apply {
            adListener = object : Listener("banner") {
                override fun onAdFailedToLoad(baseAd: BaseAd, adError: VungleError) {
                    super.onAdFailedToLoad(baseAd, adError)
                    onFailed()
                }
            }
            load()
        }
    }

    override fun destroyBanner(view: View) {
        (view as? VungleBannerView)?.finishAd()
    }
}
