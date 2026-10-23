package com.fabads

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import java.lang.ref.WeakReference
import java.util.ServiceLoader

/**
 * The one entry point apps use. Call [start] once from the first activity with the admin's [AdsConfig], then use
 * [FabBanner] / [FabNative] in Compose (or [showBanner] / [showNative] with views), [interstitial] and [rewarded].
 *
 * Only networks whose module is a dependency of the app exist here ([installedNetworks]). Networks the admin turns
 * on but the app does not include are skipped, and the next network in the admin's order is used.
 */
object FabAds {
    /** Logs every network's load results to Logcat (tag "FabAds") and forces test ads. */
    var debug: Boolean = false

    var config: AdsConfig = AdsConfig.Disabled
        private set

    /** Premium / "remove ads" users: turns every ad off except [rewarded]. */
    var noAds: Boolean by mutableStateOf(false)

    /** Networks the app was built with, found through each module's [AdProvider]. */
    val installedNetworks: List<String> get() = providers.keys.sorted()

    /** The provider of network [id] when its module is in the app. Used by the Flutter bridge. */
    fun provider(id: String): AdProvider? = providers[NetworkIds.normalize(id)]

    /** Networks that are running now, in the admin's order. */
    val activeNetworks: List<String> get() = networks.map { it.id }

    val enabled: Boolean get() = config.enabled && !noAds && networks.isNotEmpty()

    /** Items between two native ads in lists; 0 when native ads are off. */
    val nativeEvery: Int get() = if (enabled && config.placement("native")) config.nativeEvery else 0

    /** True when the placement named [name] should show an ad. */
    fun placement(name: String): Boolean = enabled && config.placement(name)

    private val providers: Map<String, AdProvider> by lazy {
        ServiceLoader.load(AdProvider::class.java, AdProvider::class.java.classLoader)
            .associateBy { NetworkIds.normalize(it.id) }
    }

    private var networks: List<AdNetwork> by mutableStateOf(emptyList())

    /** Bumped when a network finishes starting, so banners composed earlier try again. */
    internal var readyCount by mutableIntStateOf(0)
        private set

    private var started = false
    private var startedAt = 0L
    private var actions = 0
    private var lastInterstitialAt = 0L
    private var fullScreen = false
    private var coldStartAppOpenDone = false
    private var currentActivity: WeakReference<Activity>? = null

    /**
     * Asks for consent where needed, then starts the networks in [config]. [onReady] runs once every network has
     * started (or right away when ads are off). Calling it again with a new config later restarts nothing; use it
     * once per process.
     */
    fun start(activity: Activity, config: AdsConfig, debug: Boolean = false, onReady: () -> Unit = {}) {
        this.config = config
        this.debug = debug
        val ready = AdUtils.onceUnit(onReady)
        if (started) return ready()
        started = true
        startedAt = SystemClock.elapsedRealtime()
        trackActivities(activity.application)
        currentActivity = WeakReference(activity)
        if (!config.enabled && !config.rewarded) return ready()

        config.networks.filter { it !in providers }.forEach {
            AdUtils.log(it, "turned on in the admin but its module is not in this app; skipped")
        }
        val afterConsent: (Privacy) -> Unit = { privacy ->
            val env = AdEnvironment(testMode = config.testMode || debug, debug = debug, privacy = privacy.copy(childDirected = config.childDirected))
            networks = config.networks.mapNotNull { id ->
                runCatching { providers[id]?.create(activity.applicationContext, config.network(id), env) }
                    .onFailure { AdUtils.log(id, "could not start: ${it.message}") }
                    .getOrNull()
            }
            if (networks.isEmpty()) ready()
            var pending = networks.size
            networks.forEach { network ->
                val onStarted = AdUtils.onceUnit {
                    readyCount++
                    if (config.enabled && !noAds) {
                        if (config.interstitialEvery > 0) network.loadInterstitial(activity)
                        if (config.appOpen || config.appOpenOnResume) loadAppOpen(network, activity)
                    }
                    if (--pending == 0) ready()
                }
                runCatching { network.init(activity, onStarted) }.onFailure {
                    AdUtils.log(network.id, "init failed: ${it.message}")
                    onStarted()
                }
            }
        }
        if (config.consentForm) Consent.request(activity, debug) { afterConsent(it) } else afterConsent(Consent.read(activity))
    }

    /**
     * Call on each action that may show an interstitial (opening an item, changing screens). An ad shows on every
     * Nth call, at most once per `min_seconds`. [onDone] runs when the ad closes, or right away when none showed:
     * navigate there so the app never waits on an ad.
     */
    fun interstitial(activity: Activity, placement: String? = null, onDone: () -> Unit = {}) {
        val every = config.interstitialEvery
        if (!enabled || every <= 0 || (placement != null && !config.placement(placement))) return onDone()
        actions++
        val sinceLast = SystemClock.elapsedRealtime() - lastInterstitialAt
        if (actions % every != 0 || (lastInterstitialAt > 0 && sinceLast < config.interstitialMinSeconds * 1000L)) return onDone()
        showInterstitialNow(activity, onDone)
    }

    /** Shows an interstitial now if one is loaded, ignoring the frequency settings. */
    fun showInterstitialNow(activity: Activity, onDone: () -> Unit = {}) {
        if (!enabled || fullScreen) return onDone()
        for (network in networks) {
            val closed = AdUtils.onceUnit {
                fullScreen = false
                lastInterstitialAt = SystemClock.elapsedRealtime()
                network.loadInterstitial(activity)
                onDone()
            }
            if (runCatching { network.showInterstitial(activity, closed) }.getOrDefault(false)) {
                fullScreen = true
                return
            }
        }
        networks.forEach { it.loadInterstitial(activity) }
        onDone()
    }

    /** True when the admin turned rewarded ads on and a network can serve them. */
    val rewardedAvailable: Boolean get() = config.rewarded && networks.any { it.supports(AdFormat.REWARDED) }

    /** Loads and shows a rewarded ad, trying each network in order. [onResult] gets true when the reward was earned. */
    fun rewarded(activity: Activity, onResult: (Boolean) -> Unit) {
        val order = networks.filter { it.supports(AdFormat.REWARDED) }
        if (!config.rewarded || order.isEmpty()) return onResult(false)
        fullScreen = true
        fun attempt(index: Int) {
            if (index >= order.size) {
                fullScreen = false
                return onResult(false)
            }
            order[index].showRewarded(activity, AdUtils.once { earned: Boolean? ->
                if (earned == null) attempt(index + 1) else {
                    fullScreen = false
                    onResult(earned)
                }
            })
        }
        attempt(0)
    }

    /**
     * Shows Google's consent form where required and returns what the user chose. [start] does this itself;
     * this is for hosts that drive the networks on their own, like the Flutter bridge.
     */
    fun requestConsent(activity: Activity, onDone: (Privacy) -> Unit) = Consent.request(activity, debug, onDone)

    /** The consent answers saved earlier, without showing anything. */
    fun savedPrivacy(context: android.content.Context): Privacy = Consent.read(context)

    /** Whether the app should show a "Privacy settings" item (users in the EEA, UK and some US states). */
    fun privacyOptionsRequired(activity: Activity): Boolean = Consent.optionsRequired(activity)

    /** Opens Google's consent form again so the user can change their choices. */
    fun showPrivacyOptions(activity: Activity, onDone: () -> Unit = {}) = Consent.showOptions(activity, onDone)

    /** Fills [container] with a banner from the first network that has one. For apps without Compose. */
    fun showBanner(activity: Activity, container: ViewGroup, size: BannerSize = BannerSize.BANNER, placement: String? = null) {
        container.removeAllViews()
        if (!enabled || (placement != null && !config.placement(placement))) return
        val order = bannerNetworks(size)
        fun attempt(index: Int) {
            val network = order.getOrNull(index) ?: return
            var view: View? = null
            view = network.banner(activity, size, AdUtils.onceUnit {
                view?.let { container.removeView(it); network.destroyBanner(it) }
                attempt(index + 1)
            })
            if (view == null) attempt(index + 1) else container.addView(view)
        }
        attempt(0)
    }

    /** Fills [container] with a native ad, falling back to a banner. For apps without Compose. */
    fun showNative(activity: Activity, container: ViewGroup, style: NativeStyle = NativeStyle.MEDIUM, dark: Boolean = false, placement: String? = null) {
        container.removeAllViews()
        if (!enabled || (placement != null && !config.placement(placement))) return
        loadNative(activity, style, dark) { result ->
            if (result != null) container.addView(result.second)
            else showBanner(activity, container, if (style == NativeStyle.SMALL || style == NativeStyle.RADIO) BannerSize.BANNER else BannerSize.MREC)
        }
    }

    internal fun bannerNetworks(size: BannerSize): List<AdNetwork> =
        networks.filter { it.supports(if (size == BannerSize.MREC) AdFormat.MREC else AdFormat.BANNER) }

    /** Tries each network with a native unit in order; [onResult] gets the network and its view, or null. */
    internal fun loadNative(activity: Activity, style: NativeStyle, dark: Boolean, onResult: (Pair<AdNetwork, View>?) -> Unit) {
        val order = networks.filter { it.supports(AdFormat.NATIVE) }
        fun attempt(index: Int) {
            val network = order.getOrNull(index) ?: return onResult(null)
            runCatching {
                network.native(activity, style, dark, AdUtils.once { view: View? ->
                    if (view == null) attempt(index + 1) else onResult(network to view)
                })
            }.onFailure { attempt(index + 1) }
        }
        attempt(0)
    }

    private fun loadAppOpen(network: AdNetwork, activity: Activity) {
        if (!network.supports(AdFormat.APP_OPEN)) return
        network.loadAppOpen(activity) {
            AdUtils.onMain {
                // Cold start: show the first app open ad that loads within a few seconds of launch.
                val fresh = SystemClock.elapsedRealtime() - startedAt < COLD_START_WINDOW_MS
                if (config.appOpen && !coldStartAppOpenDone && fresh) {
                    coldStartAppOpenDone = true
                    currentActivity?.get()?.let { showAppOpen(it) }
                }
            }
        }
    }

    private fun showAppOpen(activity: Activity) {
        if (!enabled || fullScreen) return
        for (network in networks) {
            val closed = AdUtils.onceUnit {
                fullScreen = false
                loadAppOpen(network, activity)
            }
            if (runCatching { network.showAppOpen(activity, closed) }.getOrDefault(false)) {
                fullScreen = true
                return
            }
        }
    }

    private fun trackActivities(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { currentActivity = WeakReference(activity) }
            override fun onActivityStarted(activity: Activity) { currentActivity = WeakReference(activity) }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        var backgrounded = false
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) { backgrounded = true }
            override fun onStart(owner: LifecycleOwner) {
                if (!backgrounded) return
                backgrounded = false
                // Ads themselves move the app to the background; their return must not trigger another ad.
                if (config.appOpenOnResume && !fullScreen) currentActivity?.get()?.let { showAppOpen(it) }
            }
        })
    }

    private const val COLD_START_WINDOW_MS = 8_000L
}
