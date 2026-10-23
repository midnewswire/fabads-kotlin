package com.fabads.demo

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fabads.AdsConfig
import com.fabads.BannerSize
import com.fabads.FabAds
import com.fabads.FabBanner
import com.fabads.FabNative
import com.fabads.NativeStyle

/**
 * Shows every format with test ads. The config comes from assets/ads.json here; in a product it comes from the
 * admin panel's API.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val config = AdsConfig.fromJson(assets.open("ads.json").bufferedReader().use { it.readText() })
        FabAds.start(this, config, debug = true)

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    var style by remember { mutableStateOf(NativeStyle.MEDIUM) }
                    Column(
                        Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("FabAds demo", style = MaterialTheme.typography.headlineSmall)
                        Text("Built with: ${FabAds.installedNetworks.joinToString()}")
                        Text("Running: ${FabAds.activeNetworks.joinToString().ifEmpty { "starting…" }}")

                        FabBanner(placement = "home_banner")

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { FabAds.showInterstitialNow(this@MainActivity) }) { Text("Interstitial") }
                            Button(onClick = {
                                FabAds.rewarded(this@MainActivity) { earned ->
                                    Toast.makeText(this@MainActivity, if (earned) "Reward earned" else "No reward", Toast.LENGTH_SHORT).show()
                                }
                            }) { Text("Rewarded") }
                        }
                        OutlinedButton(onClick = { FabAds.showPrivacyOptions(this@MainActivity) }) { Text("Privacy settings") }

                        Text("Native style: ${style.name.lowercase()}")
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            NativeStyle.entries.forEach { s ->
                                OutlinedButton(onClick = { style = s }) { Text(s.name.take(2)) }
                            }
                        }
                        FabNative(style = style)

                        Text("Medium rectangle")
                        FabBanner(size = BannerSize.MREC)
                    }
                }
            }
        }
    }
}
