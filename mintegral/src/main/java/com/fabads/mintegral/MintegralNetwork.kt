package com.fabads.mintegral

import android.app.Activity
import android.content.Context
import android.view.View
import android.widget.FrameLayout
import com.fabads.AdEnvironment
import com.fabads.AdFormat
import com.fabads.AdNetwork
import com.fabads.AdProvider
import com.fabads.AdUtils
import com.fabads.BannerSize
import com.fabads.NetworkConfig
import com.fabads.NetworkIds
import com.mbridge.msdk.MBridgeConstans
import com.mbridge.msdk.newinterstitial.out.MBNewInterstitialHandler
import com.mbridge.msdk.newinterstitial.out.NewInterstitialWithCodeListener
import com.mbridge.msdk.out.BannerAdWithCodeListener
import com.mbridge.msdk.out.BannerSize as MBBannerSize
import com.mbridge.msdk.out.MBBannerView
import com.mbridge.msdk.out.MBRewardVideoHandler
import com.mbridge.msdk.out.MBridgeIds
import com.mbridge.msdk.out.MBridgeSDKFactory
import com.mbridge.msdk.out.RewardInfo
import com.mbridge.msdk.out.RewardVideoWithCodeListener
import com.mbridge.msdk.out.SDKInitStatusListener

class MintegralProvider : AdProvider {
    override val id = NetworkIds.MINTEGRAL

    override fun create(context: Context, config: NetworkConfig, env: AdEnvironment): AdNetwork? =
        if (config.appId.isBlank() || config.appKey.isBlank()) null else MintegralNetwork(context, config, env)
}

/**
 * Mintegral: banner, medium rectangle, interstitial and rewarded. Every Mintegral ad needs a placement id and a
 * unit id, so each unit in the config is written as `PLACEMENT_ID:UNIT_ID`.
 */
class MintegralNetwork(private val context: Context, private val config: NetworkConfig, private val env: AdEnvironment) : AdNetwork {
    override val id = NetworkIds.MINTEGRAL
    private var started = false
    private var interstitial: MBNewInterstitialHandler? = null
    private var interstitialReady = false
    private var onInterstitialClosed: (() -> Unit)? = null

    /** "placement:unit" -> placement to unit, or null when the value is not in that form. */
    private fun ids(value: String): Pair<String, String>? {
        val parts = value.split(':', '|', ',').map { it.trim() }
        return if (parts.size == 2 && parts.all { it.isNotEmpty() }) parts[0] to parts[1] else null
    }

    override fun supports(format: AdFormat) = when (format) {
        AdFormat.BANNER -> ids(config.banner) != null
        AdFormat.MREC -> ids(config.mrec) != null
        AdFormat.INTERSTITIAL -> ids(config.interstitial) != null
        AdFormat.REWARDED -> ids(config.rewarded) != null
        AdFormat.NATIVE, AdFormat.APP_OPEN -> false
    }

    override fun init(activity: Activity, onReady: () -> Unit) {
        val sdk = MBridgeSDKFactory.getMBridgeSDK()
        sdk.setConsentStatus(context) // reads the IAB TCF string saved by the consent form
        sdk.setDoNotTrackStatus(env.privacy.doNotSell)
        if (env.privacy.childDirected) sdk.setCoppaStatus(context, true)
        val map = sdk.getMBConfigurationMap(config.appId, config.appKey)
        sdk.init(map, context, object : SDKInitStatusListener {
            override fun onInitSuccess() {
                started = true
                onReady()
            }

            override fun onInitFail(message: String) {
                AdUtils.log(NetworkIds.MINTEGRAL, "init: $message")
                onReady()
            }
        })
    }

    override fun loadInterstitial(activity: Activity) {
        val (placement, unit) = ids(config.interstitial) ?: return
        if (!started || interstitialReady) return
        val handler = interstitial ?: MBNewInterstitialHandler(context, placement, unit).also { handler ->
            interstitial = handler
            handler.setInterstitialVideoListener(object : NewInterstitialWithCodeListener() {
                override fun onLoadCampaignSuccess(ids: MBridgeIds) {}
                override fun onResourceLoadSuccess(ids: MBridgeIds) { interstitialReady = true }
                override fun onResourceLoadFailWithCode(ids: MBridgeIds, code: Int, message: String) { AdUtils.log(NetworkIds.MINTEGRAL, "interstitial: $message") }
                override fun onAdShow(ids: MBridgeIds) {}
                override fun onAdClose(ids: MBridgeIds, info: RewardInfo) = closed()
                override fun onShowFailWithCode(ids: MBridgeIds, code: Int, message: String) = closed()
                override fun onAdClicked(ids: MBridgeIds) {}
                override fun onVideoComplete(ids: MBridgeIds) {}
                override fun onAdCloseWithNIReward(ids: MBridgeIds, info: RewardInfo) {}
                override fun onEndcardShow(ids: MBridgeIds) {}
            })
        }
        handler.load()
    }

    private fun closed() {
        onInterstitialClosed?.invoke()
        onInterstitialClosed = null
    }

    override fun showInterstitial(activity: Activity, onClosed: () -> Unit): Boolean {
        val handler = interstitial ?: return false
        if (!interstitialReady) return false
        interstitialReady = false
        onInterstitialClosed = onClosed
        handler.show()
        return true
    }

    override fun showRewarded(activity: Activity, onResult: (Boolean?) -> Unit) {
        val (placement, unit) = ids(config.rewarded) ?: return onResult(null)
        if (!started) return onResult(null)
        val handler = MBRewardVideoHandler(context, placement, unit)
        handler.playVideoMute(MBridgeConstans.REWARD_VIDEO_PLAY_NOT_MUTE)
        handler.setRewardVideoListener(object : RewardVideoWithCodeListener() {
            override fun onLoadSuccess(ids: MBridgeIds) {}
            override fun onVideoLoadSuccess(ids: MBridgeIds) = handler.show()
            override fun onVideoLoadFailWithCode(ids: MBridgeIds, code: Int, message: String) {
                AdUtils.log(NetworkIds.MINTEGRAL, "rewarded: $message")
                onResult(null)
            }
            override fun onAdShow(ids: MBridgeIds) {}
            override fun onAdClose(ids: MBridgeIds, info: RewardInfo?) = onResult(info?.isCompleteView == true)
            override fun onShowFailWithCode(ids: MBridgeIds, code: Int, message: String) = onResult(null)
            override fun onVideoAdClicked(ids: MBridgeIds) {}
            override fun onVideoComplete(ids: MBridgeIds) {}
            override fun onEndcardShow(ids: MBridgeIds) {}
        })
        handler.load()
    }

    override fun banner(activity: Activity, size: BannerSize, onFailed: () -> Unit): View? {
        val mrec = size == BannerSize.MREC
        val (placement, unit) = ids(if (mrec) config.mrec else config.banner) ?: return null
        if (!started) return null
        val view = MBBannerView(activity)
        view.init(MBBannerSize(if (mrec) MBBannerSize.MEDIUM_TYPE else MBBannerSize.STANDARD_TYPE, 0, 0), placement, unit)
        view.layoutParams = FrameLayout.LayoutParams(AdUtils.dp(activity, size.width), AdUtils.dp(activity, size.height))
        view.setBannerAdListener(object : BannerAdWithCodeListener() {
            override fun onLoadFailedWithCode(ids: MBridgeIds, code: Int, message: String) {
                AdUtils.log(NetworkIds.MINTEGRAL, "banner: $message")
                onFailed()
            }
            override fun onLoadSuccessed(ids: MBridgeIds) {}
            override fun onLogImpression(ids: MBridgeIds) {}
            override fun onClick(ids: MBridgeIds) {}
            override fun onLeaveApp(ids: MBridgeIds) {}
            override fun showFullScreen(ids: MBridgeIds) {}
            override fun closeFullScreen(ids: MBridgeIds) {}
            override fun onCloseBanner(ids: MBridgeIds) {}
        })
        view.load()
        return view
    }

    override fun destroyBanner(view: View) {
        (view as? MBBannerView)?.release()
    }
}
