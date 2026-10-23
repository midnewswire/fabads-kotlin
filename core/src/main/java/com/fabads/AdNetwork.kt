package com.fabads

import android.app.Activity
import android.content.Context
import android.view.View

/**
 * One ad network, made by that network's module. [FabAds] asks the networks in the admin's order and uses the
 * first one that has an ad. Every callback may arrive on any thread; [FabAds] moves them to the main thread.
 */
interface AdNetwork {
    val id: String

    /** True when the admin gave this network an id for [format]. */
    fun supports(format: AdFormat): Boolean

    /** Starts the SDK once; [onReady] runs when ads can be requested (also when starting failed). */
    fun init(activity: Activity, onReady: () -> Unit)

    fun loadInterstitial(activity: Activity) {}

    /** Shows a loaded interstitial and returns true, or returns false when none is ready. */
    fun showInterstitial(activity: Activity, onClosed: () -> Unit): Boolean = false

    /**
     * Loads and shows a rewarded ad. [onResult] gets true when the reward was earned, false when the ad was shown
     * but not finished, and null when there was no ad (so the next network is tried).
     */
    fun showRewarded(activity: Activity, onResult: (Boolean?) -> Unit) = onResult(null)

    /** Loads an app open ad; [onLoaded] runs once it can be shown. */
    fun loadAppOpen(activity: Activity, onLoaded: () -> Unit) {}

    fun showAppOpen(activity: Activity, onClosed: () -> Unit): Boolean = false

    /**
     * Starts loading a banner and returns its view right away, or null when there is no unit for [size].
     * [onFailed] runs when the network has no ad, so the next network can try.
     */
    fun banner(activity: Activity, size: BannerSize, onFailed: () -> Unit): View? = null

    fun destroyBanner(view: View) {}

    /** Loads a native ad and hands [onResult] the finished view, or null when there is no ad. */
    fun native(activity: Activity, style: NativeStyle, dark: Boolean, onResult: (View?) -> Unit) = onResult(null)

    fun destroyNative(view: View) {}
}

/**
 * Creates a network. Each network module lists its provider in
 * `META-INF/services/com.fabads.AdProvider`, so adding the module's dependency is all an app does to enable it.
 */
interface AdProvider {
    val id: String

    /** Returns null when [config] lacks what the network needs (an app id, for example). */
    fun create(context: Context, config: NetworkConfig, env: AdEnvironment): AdNetwork?
}

class AdEnvironment(
    /** Request test ads. */
    val testMode: Boolean,
    val debug: Boolean,
    val privacy: Privacy,
)

/** What the user agreed to, read from the Google UMP consent form (IAB TCF / US privacy strings). */
data class Privacy(
    val gdprApplies: Boolean = false,
    /** Personalized ads allowed. Always true where GDPR does not apply. */
    val consent: Boolean = true,
    val doNotSell: Boolean = false,
    val childDirected: Boolean = false,
)

enum class AdFormat { BANNER, MREC, INTERSTITIAL, REWARDED, NATIVE, APP_OPEN }

enum class BannerSize(val width: Int, val height: Int) {
    /** 320 x 50 */
    BANNER(320, 50),

    /** Medium rectangle, 300 x 250 */
    MREC(300, 250),
}

/** Native ad layouts, one per kind of product screen. */
enum class NativeStyle {
    /** Icon, title and button in one row. For lists. */
    SMALL,

    /** Title row, 160dp media, text and a full width button. */
    MEDIUM,

    /** Like [MEDIUM] with 240dp media. For detail screens. */
    LARGE,

    /** Thumbnail on the left, headline on the right. Fits news and blog lists. */
    NEWS,

    /** Compact row for players and station lists. */
    RADIO,

    /** Media first, wide. For video and wallpaper grids. */
    VIDEO,
}
