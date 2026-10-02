package com.nabchat.app

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.*
import com.google.android.ump.*
import java.util.concurrent.atomic.AtomicBoolean

private const val PRODUCTION_BANNER_ID = "ca-app-pub-5870784629837288/3791124042"
private const val TEST_BANNER_ID = "ca-app-pub-3940256099942544/9214589741"

object AdvertisingPrivacy {
    private val canRequestMutable = mutableStateOf(false)
    private val privacyRequiredMutable = mutableStateOf(false)
    private val requestStarted = AtomicBoolean(false)
    private val adsInitialized = AtomicBoolean(false)
    val canRequestAds: State<Boolean> get() = canRequestMutable
    val privacyOptionsRequired: State<Boolean> get() = privacyRequiredMutable

    private fun initializeAdsIfAllowed(activity: Activity, information: ConsentInformation) {
        canRequestMutable.value = information.canRequestAds()
        privacyRequiredMutable.value = information.privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
        if (canRequestMutable.value && adsInitialized.compareAndSet(false, true)) {
            MobileAds.initialize(activity.applicationContext) {}
        }
    }

    fun request(activity: Activity) {
        if (!requestStarted.compareAndSet(false, true)) return
        val information = UserMessagingPlatform.getConsentInformation(activity)
        information.requestConsentInfoUpdate(activity, ConsentRequestParameters.Builder().build(), {
            initializeAdsIfAllowed(activity, information)
            UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                initializeAdsIfAllowed(activity, information)
            }
        }, { initializeAdsIfAllowed(activity, information) })
    }

    fun showPrivacyOptions(activity: Activity) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) {
            val information = UserMessagingPlatform.getConsentInformation(activity)
            initializeAdsIfAllowed(activity, information)
        }
    }
}

@Composable fun NabchatBannerAd(modifier: Modifier = Modifier, dismissible: Boolean = true, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as? Activity
    val canRequestAds by AdvertisingPrivacy.canRequestAds
    LaunchedEffect(activity) { activity?.let(AdvertisingPrivacy::request) }
    Surface(modifier = modifier.fillMaxWidth().height(50.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                if (canRequestAds) LoadedBannerAd() else Text("ADVERTISEMENT · PRIVACY CHECK", fontSize = 8.sp, color = Color.Gray)
            }
            if (dismissible) IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Icon(Icons.Outlined.Close, "Dismiss advertisement", Modifier.size(18.dp))
            }
        }
    }
}

@Composable private fun LoadedBannerAd() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val adView = remember {
        AdView(context).apply {
            adUnitId = if (BuildConfig.DEBUG) TEST_BANNER_ID else PRODUCTION_BANNER_ID
            setAdSize(AdSize.BANNER)
            loadAd(AdRequest.Builder().build())
        }
    }
    DisposableEffect(adView) { onDispose { adView.destroy() } }
    AndroidView(factory = { adView }, modifier = Modifier.wrapContentSize())
}
