package com.fabads

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * A banner (or medium rectangle) from the first network that has one; shows nothing when ads are off or the
 * placement is turned off in the admin.
 */
@Composable
fun FabBanner(modifier: Modifier = Modifier, placement: String? = null, size: BannerSize = BannerSize.BANNER) {
    if (!FabAds.enabled || (placement != null && !FabAds.placement(placement))) return
    val activity = LocalActivity.current ?: return
    val ready = FabAds.readyCount
    val order = remember(ready, size) { FabAds.bannerNetworks(size) }
    var index by remember(ready, size) { mutableIntStateOf(0) }
    val network = order.getOrNull(index) ?: return
    key(network.id, index) {
        val view = remember { network.banner(activity, size) { AdUtils.onMain { index++ } } }
        if (view == null) {
            LaunchedEffect(Unit) { index++ }
        } else {
            Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                AndroidView(factory = { context -> FrameLayout(context).apply { addView(view.detached()) } })
            }
            DisposableEffect(view) { onDispose { network.destroyBanner(view) } }
        }
    }
}

/**
 * A native ad in one of the [NativeStyle] layouts. When no network has a native ad it shows a banner instead
 * (a medium rectangle for the bigger styles), unless [fallbackToBanner] is false.
 */
@Composable
fun FabNative(
    modifier: Modifier = Modifier,
    placement: String? = null,
    style: NativeStyle = NativeStyle.MEDIUM,
    dark: Boolean = isSystemInDarkTheme(),
    fallbackToBanner: Boolean = true,
) {
    if (!FabAds.enabled || (placement != null && !FabAds.placement(placement))) return
    val activity = LocalActivity.current ?: return
    val ready = FabAds.readyCount
    var loaded by remember(style, dark) { mutableStateOf<Pair<AdNetwork, View>?>(null) }
    var failed by remember(style, dark) { mutableStateOf(false) }
    LaunchedEffect(ready, style, dark) {
        if (loaded == null) FabAds.loadNative(activity, style, dark) { result ->
            loaded = result
            failed = result == null
        }
    }
    DisposableEffect(loaded) {
        val current = loaded
        onDispose { current?.let { (network, view) -> network.destroyNative(view) } }
    }
    val ad = loaded
    when {
        ad != null -> AndroidView(
            modifier = modifier.fillMaxWidth(),
            factory = { context -> FrameLayout(context).apply { addView(ad.second.detached()) } },
        )
        failed && fallbackToBanner -> FabBanner(
            modifier,
            size = if (style == NativeStyle.SMALL || style == NativeStyle.RADIO) BannerSize.BANNER else BannerSize.MREC,
        )
    }
}

private fun View.detached(): View = apply { (parent as? ViewGroup)?.removeView(this) }
