package com.fabads.meta

import android.app.Activity
import android.content.Context
import android.view.View
import com.facebook.ads.Ad
import com.facebook.ads.AdError
import com.facebook.ads.AdOptionsView
import com.facebook.ads.AdSettings
import com.facebook.ads.AdSize
import com.facebook.ads.AdView
import com.facebook.ads.AudienceNetworkAds
import com.facebook.ads.InterstitialAd
import com.facebook.ads.InterstitialAdListener
import com.facebook.ads.MediaView
import com.facebook.ads.NativeAd
import com.facebook.ads.NativeAdBase
import com.facebook.ads.NativeAdLayout
import com.facebook.ads.NativeAdListener
import com.facebook.ads.NativeBannerAd
import com.facebook.ads.RewardedVideoAd
import com.facebook.ads.RewardedVideoAdListener
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

class MetaProvider : AdProvider {
    override val id = NetworkIds.META
    override fun create(context: Context, config: NetworkConfig, env: AdEnvironment) = MetaNetwork(context, config, env)
}

/**
 * Meta Audience Network: banner, medium rectangle, native, native banner (for the small styles), interstitial and
 * rewarded video. Placement ids look like `123456789_987654321`.
 */
class MetaNetwork(private val context: Context, private val config: NetworkConfig, private val env: AdEnvironment) : AdNetwork {
    override val id = NetworkIds.META
    private var interstitial: InterstitialAd? = null
    private var onInterstitialClosed: (() -> Unit)? = null
    private val nativeBanner get() = config.string("native_banner")

    override fun supports(format: AdFormat) = when (format) {
        AdFormat.BANNER -> config.banner.isNotBlank()
        AdFormat.MREC -> config.mrec.isNotBlank()
        AdFormat.INTERSTITIAL -> config.interstitial.isNotBlank()
        AdFormat.REWARDED -> config.rewarded.isNotBlank()
        AdFormat.NATIVE -> config.native.isNotBlank() || nativeBanner.isNotBlank()
        AdFormat.APP_OPEN -> false
    }

    override fun init(activity: Activity, onReady: () -> Unit) {
        AdSettings.setTestMode(env.testMode)
        AdSettings.setMixedAudience(env.privacy.childDirected)
        if (env.privacy.doNotSell) AdSettings.setDataProcessingOptions(arrayOf("LDU"), 0, 0)
        if (AudienceNetworkAds.isInitialized(context)) return onReady()
        AudienceNetworkAds.buildInitSettings(context).withInitListener { onReady() }.initialize()
    }

    override fun loadInterstitial(activity: Activity) {
        if (interstitial != null || !supports(AdFormat.INTERSTITIAL)) return
        val ad = InterstitialAd(context, config.interstitial)
        interstitial = ad
        ad.loadAd(ad.buildLoadAdConfig().withAdListener(object : InterstitialAdListener {
            override fun onInterstitialDisplayed(a: Ad) {}
            override fun onInterstitialDismissed(a: Ad) {
                ad.destroy()
                interstitial = null
                onInterstitialClosed?.invoke()
                onInterstitialClosed = null
            }
            override fun onError(a: Ad, error: AdError) {
                AdUtils.log(NetworkIds.META, "interstitial: ${error.errorMessage}")
                ad.destroy()
                interstitial = null
                onInterstitialClosed?.invoke()
                onInterstitialClosed = null
            }
            override fun onAdLoaded(a: Ad) {}
            override fun onAdClicked(a: Ad) {}
            override fun onLoggingImpression(a: Ad) {}
        }).build())
    }

    override fun showInterstitial(activity: Activity, onClosed: () -> Unit): Boolean {
        val ad = interstitial?.takeIf { it.isAdLoaded && !it.isAdInvalidated } ?: return false
        onInterstitialClosed = onClosed
        return ad.show().also { if (!it) onInterstitialClosed = null }
    }

    override fun showRewarded(activity: Activity, onResult: (Boolean?) -> Unit) {
        if (!supports(AdFormat.REWARDED)) return onResult(null)
        val ad = RewardedVideoAd(context, config.rewarded)
        var earned = false
        var shown = false
        ad.loadAd(ad.buildLoadAdConfig().withAdListener(object : RewardedVideoAdListener {
            override fun onAdLoaded(a: Ad) {
                shown = ad.show()
                if (!shown) { ad.destroy(); onResult(null) }
            }
            override fun onError(a: Ad, error: AdError) {
                AdUtils.log(NetworkIds.META, "rewarded: ${error.errorMessage}")
                ad.destroy()
                onResult(if (shown) false else null)
            }
            override fun onRewardedVideoCompleted() { earned = true }
            override fun onRewardedVideoClosed() { ad.destroy(); onResult(earned) }
            override fun onAdClicked(a: Ad) {}
            override fun onLoggingImpression(a: Ad) {}
        }).build())
    }

    override fun banner(activity: Activity, size: BannerSize, onFailed: () -> Unit): View? {
        val mrec = size == BannerSize.MREC
        val unit = if (mrec) config.mrec else config.banner
        if (unit.isBlank()) return null
        val view = AdView(activity, unit, if (mrec) AdSize.RECTANGLE_HEIGHT_250 else AdSize.BANNER_HEIGHT_50)
        view.loadAd(view.buildLoadAdConfig().withAdListener(object : com.facebook.ads.AdListener {
            override fun onError(a: Ad, error: AdError) { AdUtils.log(NetworkIds.META, "banner: ${error.errorMessage}"); onFailed() }
            override fun onAdLoaded(a: Ad) {}
            override fun onAdClicked(a: Ad) {}
            override fun onLoggingImpression(a: Ad) {}
        }).build())
        return view
    }

    override fun destroyBanner(view: View) {
        (view as? AdView)?.destroy()
    }

    override fun native(activity: Activity, style: NativeStyle, dark: Boolean, onResult: (View?) -> Unit) {
        val small = style == NativeStyle.SMALL || style == NativeStyle.RADIO
        // Layouts without media need a "native banner" placement; Meta does not allow native ads without media.
        val ad: NativeAdBase = when {
            small && nativeBanner.isNotBlank() -> NativeBannerAd(activity, nativeBanner)
            !small && config.native.isNotBlank() -> NativeAd(activity, config.native)
            else -> return onResult(null)
        }
        ad.loadAd(ad.buildLoadAdConfig().withAdListener(object : NativeAdListener {
            override fun onMediaDownloaded(a: Ad) {}
            override fun onError(a: Ad, error: AdError) {
                AdUtils.log(NetworkIds.META, "native: ${error.errorMessage}")
                onResult(null)
            }
            override fun onAdClicked(a: Ad) {}
            override fun onLoggingImpression(a: Ad) {}
            override fun onAdLoaded(a: Ad) {
                if (a != ad) return
                onResult(render(activity, ad, style, dark))
            }
        }).build())
    }

    private fun render(activity: Activity, ad: NativeAdBase, style: NativeStyle, dark: Boolean): View {
        val template = NativeTemplate(activity, style, dark)
        template.bind(ad.adHeadline ?: ad.advertiserName, ad.adBodyText, ad.sponsoredTranslation ?: "Sponsored", ad.adCallToAction)
        template.badge.text = "Ad"
        val layout = NativeAdLayout(activity)
        layout.addView(template.root)
        template.adChoices.addView(AdOptionsView(activity, ad, layout))
        val icon = MediaView(activity)
        template.useIconView(icon)
        val clickable = listOf(template.cta, template.headline)
        when (ad) {
            is NativeAd -> {
                val media = MediaView(activity)
                template.useMediaView(media)
                ad.registerViewForInteraction(template.root, media, icon, clickable)
            }
            is NativeBannerAd -> ad.registerViewForInteraction(template.root, icon, clickable)
        }
        layout.tag = ad
        return layout
    }

    override fun destroyNative(view: View) {
        (view.tag as? NativeAdBase)?.let { it.unregisterView(); it.destroy() }
    }
}
