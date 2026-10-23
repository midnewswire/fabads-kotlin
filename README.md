# FabAds for Android

One ads API for every fab app. Pick the ad networks you want, give FabAds the settings from your admin panel
(or a JSON file), and use the same few calls for banners, native ads, interstitials, rewarded and app open ads.
FabAds tries your networks in the order you set and moves to the next one when a network has no ad.

Networks: AdMob, Google Ad Manager, AppLovin MAX, Meta Audience Network, Unity Ads, Start.io, ironSource LevelPlay,
Pangle, Mintegral, Liftoff (Vungle), InMobi, Chartboost, Yandex Ads and Wortise.

## Choose your ad networks

**Ship only the networks you use.** Every network is a separate module, and an app contains only the network
SDKs it depends on. Bundling many ad SDKs you do not use makes the app bigger, and Google Play has removed apps
whose ad SDKs it found to break its policies, so a small, deliberate set is the safest choice. A common setup is
one network, or one main network plus one backup.

The `all` module pulls in every network. It is for the demo app and for trying networks out, not for release.

## Install

`settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
        // Only for the networks that need it:
        maven("https://artifact.bytedance.com/repository/pangle") // pangle, wortise
        maven("https://dl-maven-android.mintegral.com/repository/mbridge_android_sdk_oversea") // mintegral
        maven("https://maven.wortise.com/artifactory/public") // wortise
        maven("https://artifactory.bidmachine.io/bidmachine") // wortise
    }
}
```

`app/build.gradle.kts`, one line per network you want:

```kotlin
val fabads = "1.0.0"
dependencies {
    implementation("com.github.midnewswire.fabads-kotlin:admob:$fabads")
    // implementation("com.github.midnewswire.fabads-kotlin:applovin:$fabads")
}
```

Each network module brings `core` with it. Nothing else is needed to turn a network on: FabAds finds every network
module in the app by itself.

The fab apps go one step further and read the list from `app.properties`, so a buyer changes networks without
touching Gradle code:

```properties
# app.properties
adNetworks=admob,applovin
```

```kotlin
// app/build.gradle.kts
val adNetworks = appProps.getProperty("adNetworks", "admob").split(",").map { it.trim() }.filter { it.isNotEmpty() }
dependencies {
    adNetworks.forEach { implementation("com.github.midnewswire.fabads-kotlin:$it:$fabads") }
}
```

AdMob and Ad Manager also need your AdMob app id in `AndroidManifest.xml`:

```xml
<meta-data android:name="com.google.android.gms.ads.APPLICATION_ID" android:value="ca-app-pub-xxxxxxxx~yyyyyyyy" />
```

## Use

```kotlin
// First activity, once the admin's settings are loaded:
FabAds.start(activity, AdsConfig.fromJson(json), debug = BuildConfig.DEBUG)

// Compose
FabBanner(placement = "home_banner")
FabBanner(size = BannerSize.MREC)
FabNative(style = NativeStyle.NEWS)        // SMALL, MEDIUM, LARGE, NEWS, RADIO, VIDEO

// Interstitial on every Nth action (from the admin); navigate in onDone so the app never waits on an ad.
FabAds.interstitial(activity, placement = "open_item") { openItem() }

// Rewarded
FabAds.rewarded(activity) { earned -> if (earned) unlock() }

// Premium users
FabAds.noAds = user.isPremium

// Lists: a native ad after every N items
val every = FabAds.nativeEvery

// Privacy settings item for users in the EEA, UK and some US states
if (FabAds.privacyOptionsRequired(activity)) FabAds.showPrivacyOptions(activity)
```

Apps without Compose use `FabAds.showBanner(activity, container)` and `FabAds.showNative(activity, container)`.

App open ads show once at launch (`app_open.enabled`) and, if `app_open.on_resume` is on, each time the user
returns to the app. FabAds never shows one on top of another full screen ad.

## Consent

`FabAds.start` shows Google's consent form (UMP) first where the law requires it, then passes the answer to every
network. Set the form up once in the AdMob console under Privacy & messaging. Apps that do not use AdMob can turn
this off with `"consent_form": false` and handle consent themselves.

## Settings (the `ads` JSON)

The admin panel serves this object; apps without an admin load it from a file. Every key is optional.

```json
{
  "enabled": true,
  "networks": ["admob", "applovin"],
  "test_mode": false,
  "child_directed": false,
  "consent_form": true,
  "interstitial": { "every": 3, "min_seconds": 60 },
  "app_open": { "enabled": true, "on_resume": false },
  "rewarded": { "enabled": true },
  "native_every": 8,
  "placements": { "home_banner": true, "detail_banner": true, "native": true },
  "admob": { "banner": "", "mrec": "", "interstitial": "", "rewarded": "", "native": "", "app_open": "" },
  "applovin": { "sdk_key": "", "banner": "", "mrec": "", "interstitial": "", "rewarded": "", "native": "", "app_open": "" }
}
```

- `networks` is the waterfall order. A network listed here but not built into the app is skipped.
- `test_mode` (and every debug build) requests test ads.
- `placements` are names the app chooses. A placement the admin does not list is on.
- `mrec` falls back to `banner` when empty.
- Older payloads with `primary` / `fallback`, `interstitial_every`, `app_open: true` and `startapp` still work.

### Network ids

| Id | Required keys | Unit keys | Formats |
| --- | --- | --- | --- |
| `admob` | app id in the manifest | ad unit ids | banner, MREC, native, interstitial, rewarded, app open |
| `admanager` | app id in the manifest | ad unit paths | banner, MREC, native, interstitial, rewarded, app open |
| `applovin` | `sdk_key` (or `applovin.sdk.key` in the manifest) | ad unit ids | banner, MREC, native, interstitial, rewarded, app open |
| `meta` | none | placement ids; `native_banner` for the small native styles | banner, MREC, native, interstitial, rewarded |
| `unity` | `game_id` | placement ids | banner, MREC, interstitial, rewarded |
| `startio` | `app_id` | none; set a format to `"off"` to disable it | banner, MREC, interstitial, rewarded |
| `ironsource` | `app_key` | ad unit ids | banner, MREC, interstitial, rewarded |
| `pangle` | `app_id` | placement ids | banner, MREC, interstitial, rewarded, app open |
| `mintegral` | `app_id`, `app_key` | `PLACEMENT_ID:UNIT_ID` | banner, MREC, interstitial, rewarded |
| `liftoff` | `app_id` | placement ids | banner, MREC, interstitial, rewarded |
| `inmobi` | `account_id` | numeric placement ids | banner, MREC, interstitial, rewarded |
| `chartboost` | `app_id`, `app_signature` | locations | banner, MREC, interstitial, rewarded |
| `yandex` | none | `R-M-…` unit ids | banner, MREC, interstitial, rewarded, app open |
| `wortise` | `app_key` | ad unit ids | banner, MREC, interstitial, rewarded, app open |

When a network has no native format, `FabNative` falls back to a banner or medium rectangle.

## Demo

`demo/` shows every format with test ads from `demo/src/main/assets/ads.json`. Open the project in Android Studio
and run `demo`.

## Releases

Tag a version (`1.0.0`) and JitPack builds it on first request: `https://jitpack.io/#midnewswire/fabads-kotlin`.
Network SDK versions are in `gradle/libs.versions.toml`.
