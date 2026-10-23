package com.fabads

import android.app.Activity
import android.content.Context
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

/**
 * GDPR / US state privacy consent through Google's User Messaging Platform. The form is set up once in the AdMob
 * console (Privacy & messaging) and then works for every network: the answers are saved as IAB TCF strings,
 * which [read] turns into a [Privacy] each network applies with its own API.
 */
internal object Consent {
    fun request(activity: Activity, debug: Boolean, done: (Privacy) -> Unit) {
        val finish = AdUtils.once<Unit> { done(read(activity)) }
        try {
            val params = ConsentRequestParameters.Builder().apply {
                if (debug) setConsentDebugSettings(ConsentDebugSettings.Builder(activity).build())
            }.build()
            UserMessagingPlatform.getConsentInformation(activity).requestConsentInfoUpdate(
                activity,
                params,
                { UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { finish(Unit) } },
                { error -> AdUtils.log("consent", error.message); finish(Unit) },
            )
        } catch (e: Exception) {
            // No AdMob app id in the manifest, for example. Ads still run, without personalization where GDPR applies.
            AdUtils.log("consent", e.message)
            finish(Unit)
        }
    }

    fun read(context: Context): Privacy {
        val prefs = context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)
        val gdprApplies = runCatching { prefs.getInt("IABTCF_gdprApplies", 0) }.getOrDefault(0) == 1
        // Purpose 1 (store and access information on a device) is what networks need for personalized ads.
        val purposes = prefs.getString("IABTCF_PurposeConsents", "").orEmpty()
        val usPrivacy = prefs.getString("IABUSPrivacy_String", "").orEmpty()
        return Privacy(
            gdprApplies = gdprApplies,
            consent = !gdprApplies || purposes.startsWith("1"),
            doNotSell = usPrivacy.length >= 3 && usPrivacy[2] == 'Y',
        )
    }

    fun optionsRequired(context: Context): Boolean = runCatching {
        UserMessagingPlatform.getConsentInformation(context).privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
    }.getOrDefault(false)

    fun showOptions(activity: Activity, done: () -> Unit) {
        runCatching { UserMessagingPlatform.showPrivacyOptionsForm(activity) { done() } }.onFailure { done() }
    }
}
