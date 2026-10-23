package com.fabads.unity

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
import com.unity3d.ads.IUnityAdsInitializationListener
import com.unity3d.ads.IUnityAdsLoadListener
import com.unity3d.ads.IUnityAdsShowListener
import com.unity3d.ads.UnityAds
import com.unity3d.ads.metadata.MetaData
import com.unity3d.services.banners.BannerErrorInfo
import com.unity3d.services.banners.BannerView
import com.unity3d.services.banners.UnityBannerSize

class UnityProvider : AdProvider {
    override val id = NetworkIds.UNITY

    override fun create(context: Context, config: NetworkConfig, env: AdEnvironment): AdNetwork? {
        val gameId = config.string("game_id")
        return if (gameId.isBlank()) null else UnityNetwork(context, config, env, gameId)
    }
}

/** Unity Ads: banner, medium rectangle, interstitial and rewarded video. Units are Unity placement ids. */
class UnityNetwork(
    private val context: Context,
    private val config: NetworkConfig,
    private val env: AdEnvironment,
    private val gameId: String,
) : AdNetwork {
    override val id = NetworkIds.UNITY
    private var interstitialReady = false
    private var interstitialLoading = false

    override fun supports(format: AdFormat) = when (format) {
        AdFormat.BANNER -> config.banner.isNotBlank()
        AdFormat.MREC -> config.mrec.isNotBlank()
        AdFormat.INTERSTITIAL -> config.interstitial.isNotBlank()
        AdFormat.REWARDED -> config.rewarded.isNotBlank()
        AdFormat.NATIVE, AdFormat.APP_OPEN -> false
    }

    override fun init(activity: Activity, onReady: () -> Unit) {
        MetaData(context).apply {
            if (env.privacy.gdprApplies) set("gdpr.consent", env.privacy.consent)
            set("privacy.consent", !env.privacy.doNotSell)
            if (env.privacy.childDirected) set("user.nonbehavioral", true)
            commit()
        }
        if (UnityAds.isInitialized) return onReady()
        UnityAds.initialize(context.applicationContext, gameId, env.testMode || config.bool("test_mode"), object : IUnityAdsInitializationListener {
            override fun onInitializationComplete() = onReady()
            override fun onInitializationFailed(error: UnityAds.UnityAdsInitializationError, message: String) {
                AdUtils.log(NetworkIds.UNITY, "init: $message")
                onReady()
            }
        })
    }

    override fun loadInterstitial(activity: Activity) {
        if (interstitialReady || interstitialLoading || !supports(AdFormat.INTERSTITIAL) || !UnityAds.isInitialized) return
        interstitialLoading = true
        UnityAds.load(config.interstitial, object : IUnityAdsLoadListener {
            override fun onUnityAdsAdLoaded(placementId: String) { interstitialLoading = false; interstitialReady = true }
            override fun onUnityAdsFailedToLoad(placementId: String, error: UnityAds.UnityAdsLoadError, message: String) {
                interstitialLoading = false
                AdUtils.log(NetworkIds.UNITY, "interstitial: $message")
            }
        })
    }

    override fun showInterstitial(activity: Activity, onClosed: () -> Unit): Boolean {
        if (!interstitialReady) return false
        interstitialReady = false
        UnityAds.show(activity, config.interstitial, showListener { onClosed() })
        return true
    }

    override fun showRewarded(activity: Activity, onResult: (Boolean?) -> Unit) {
        if (!supports(AdFormat.REWARDED) || !UnityAds.isInitialized) return onResult(null)
        UnityAds.load(config.rewarded, object : IUnityAdsLoadListener {
            override fun onUnityAdsAdLoaded(placementId: String) = UnityAds.show(activity, placementId, showListener(onResult))
            override fun onUnityAdsFailedToLoad(placementId: String, error: UnityAds.UnityAdsLoadError, message: String) {
                AdUtils.log(NetworkIds.UNITY, "rewarded: $message")
                onResult(null)
            }
        })
    }

    /** [onDone] gets true when the ad was watched to the end, false when skipped, null when it could not show. */
    private fun showListener(onDone: (Boolean?) -> Unit) = object : IUnityAdsShowListener {
        override fun onUnityAdsShowFailure(placementId: String, error: UnityAds.UnityAdsShowError, message: String) = onDone(null)
        override fun onUnityAdsShowStart(placementId: String) {}
        override fun onUnityAdsShowClick(placementId: String) {}
        override fun onUnityAdsShowComplete(placementId: String, state: UnityAds.UnityAdsShowCompletionState) =
            onDone(state == UnityAds.UnityAdsShowCompletionState.COMPLETED)
    }

    override fun banner(activity: Activity, size: BannerSize, onFailed: () -> Unit): View? {
        val mrec = size == BannerSize.MREC
        val placement = if (mrec) config.mrec else config.banner
        if (placement.isBlank() || !UnityAds.isInitialized) return null
        return BannerView(activity, placement, UnityBannerSize(size.width, size.height)).apply {
            listener = object : BannerView.IListener {
                override fun onBannerLoaded(view: BannerView) {}
                override fun onBannerShown(view: BannerView) {}
                override fun onBannerClick(view: BannerView) {}
                override fun onBannerFailedToLoad(view: BannerView, info: BannerErrorInfo) {
                    AdUtils.log(NetworkIds.UNITY, "banner: ${info.errorMessage}")
                    onFailed()
                }
                override fun onBannerLeftApplication(view: BannerView) {}
            }
            load()
        }
    }

    override fun destroyBanner(view: View) {
        (view as? BannerView)?.destroy()
    }
}
