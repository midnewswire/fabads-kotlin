package com.fabads

import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything the admin panel (or a local JSON file) decides about ads. See the README for the full schema.
 *
 * Older fabwallpaperadmin payloads (`primary` / `fallback`, `interstitial_every`, `app_open: true`, `startapp`)
 * are read too, so apps keep working while their admin is updated.
 */
class AdsConfig private constructor(private val json: JSONObject) {
    /** Master switch. */
    val enabled: Boolean = json.optBoolean("enabled", false)

    /** Networks in waterfall order: the first one with an ad ready wins. */
    val networks: List<String> = readNetworks(json)

    /** Use each network's test ads. Debug builds always do. */
    val testMode: Boolean = json.optBoolean("test_mode", false)

    /** Show an interstitial on every Nth [FabAds.interstitial] call. 0 turns interstitials off. */
    val interstitialEvery: Int = json.optJSONObject("interstitial")?.optInt("every", 3) ?: json.optInt("interstitial_every", 3)

    /** Minimum seconds between two interstitials, whatever [interstitialEvery] says. */
    val interstitialMinSeconds: Int = json.optJSONObject("interstitial")?.optInt("min_seconds", 0) ?: 0

    /** Show an app open ad when the app starts. */
    val appOpen: Boolean = json.optJSONObject("app_open")?.optBoolean("enabled", false) ?: json.optBoolean("app_open", false)

    /** Also show an app open ad when the user comes back to the app. */
    val appOpenOnResume: Boolean = json.optJSONObject("app_open")?.optBoolean("on_resume", false) ?: false

    /** Rewarded ads can stay on when other ads are off (they unlock content the user asked for). */
    val rewarded: Boolean = json.optJSONObject("rewarded")?.optBoolean("enabled", true) ?: true

    /** Put a native ad after every N items in lists. 0 = none. */
    val nativeEvery: Int = json.optInt("native_every", 0)

    /** Ask networks for ads suitable for children (COPPA / Families policy). */
    val childDirected: Boolean = json.optBoolean("child_directed", false)

    /** Show Google's consent form (UMP) before ads where the law needs it. */
    val consentForm: Boolean = json.optBoolean("consent_form", true)

    private val placements: JSONObject = json.optJSONObject("placements") ?: JSONObject()

    /** Whether the placement named [name] is on. Placements the admin does not list are on. */
    fun placement(name: String): Boolean =
        placements.optBoolean(name, if (name == "detail_banner") json.optBoolean("banner_on_detail", true) else true)

    /** The ids for one network. Empty when the admin left it blank. */
    fun network(id: String): NetworkConfig {
        val keys = listOf(id) + NetworkIds.aliases(id)
        val obj = keys.firstNotNullOfOrNull { json.optJSONObject(it) } ?: JSONObject()
        return NetworkConfig(id, obj)
    }

    fun toJson(): JSONObject = JSONObject(json.toString())

    override fun toString(): String = json.toString()

    companion object {
        fun fromJson(json: JSONObject?): AdsConfig = AdsConfig(json ?: JSONObject())

        fun fromJson(json: String?): AdsConfig = fromJson(json?.takeIf { it.isNotBlank() }?.let(::JSONObject))

        /** Ads off. */
        val Disabled: AdsConfig get() = fromJson(JSONObject())

        private fun readNetworks(json: JSONObject): List<String> {
            val list = json.optJSONArray("networks")?.strings()
                ?: listOf(json.optString("primary"), json.optString("fallback"))
            return list.map { NetworkIds.normalize(it) }.filter { it.isNotBlank() && it != "none" }.distinct()
        }

        private fun JSONArray.strings() = (0 until length()).map { optString(it) }
    }
}

/** One network's ids from the admin. Keys are snake_case, as in the JSON. */
class NetworkConfig(val id: String, private val json: JSONObject) {
    fun string(key: String): String = json.optString(key).trim().takeUnless { it == "null" }.orEmpty()

    fun bool(key: String, default: Boolean = false): Boolean = json.optBoolean(key, default)

    val appId get() = string("app_id")
    val appKey get() = string("app_key")
    val banner get() = string("banner")
    val mrec get() = string("mrec").ifBlank { banner }
    val interstitial get() = string("interstitial")
    val rewarded get() = string("rewarded")
    val native get() = string("native")
    val appOpen get() = string("app_open")

    val isEmpty get() = json.length() == 0
}

/** Network ids used in the config JSON and by each module's [AdProvider]. */
object NetworkIds {
    const val ADMOB = "admob"
    const val AD_MANAGER = "admanager"
    const val APPLOVIN = "applovin"
    const val META = "meta"
    const val UNITY = "unity"
    const val STARTIO = "startio"
    const val IRONSOURCE = "ironsource"
    const val PANGLE = "pangle"
    const val MINTEGRAL = "mintegral"
    const val LIFTOFF = "liftoff"
    const val INMOBI = "inmobi"
    const val CHARTBOOST = "chartboost"
    const val YANDEX = "yandex"
    const val WORTISE = "wortise"

    val all = listOf(ADMOB, AD_MANAGER, APPLOVIN, META, UNITY, STARTIO, IRONSOURCE, PANGLE, MINTEGRAL, LIFTOFF, INMOBI, CHARTBOOST, YANDEX, WORTISE)

    private val aliases = mapOf(
        "startapp" to STARTIO, "start.io" to STARTIO,
        "google_ad_manager" to AD_MANAGER, "gam" to AD_MANAGER, "ad_manager" to AD_MANAGER,
        "max" to APPLOVIN, "applovin_max" to APPLOVIN,
        "fan" to META, "facebook" to META,
        "levelplay" to IRONSOURCE,
        "vungle" to LIFTOFF,
    )

    fun normalize(id: String): String = id.trim().lowercase().let { aliases[it] ?: it }

    /** Older JSON keys that hold the same network's ids. */
    fun aliases(id: String): List<String> = aliases.filterValues { it == id }.keys.toList()
}
