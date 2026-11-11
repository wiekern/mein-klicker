package com.meinklicker.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meinklicker.container
import com.meinklicker.ui.theme.MeinKlickerTheme

/** 首页、方案设置、关于和隐私政策页通过可保存的状态切换。 */
class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        container.setMainUiVisible(true)
    }

    override fun onPause() {
        container.setMainUiVisible(false)
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = container.repository
        val privacyPreferences = getSharedPreferences(PRIVACY_PREFERENCES, MODE_PRIVATE)
        setContent {
            MeinKlickerTheme {
                var privacyAccepted by remember {
                    mutableStateOf(privacyPreferences.getString(PRIVACY_ACCEPTED_VERSION_KEY, null) == PRIVACY_POLICY_VERSION)
                }
                if (!privacyAccepted) {
                    PrivacyConsentScreen(
                        onAgree = {
                            privacyPreferences.edit()
                                .putString(PRIVACY_ACCEPTED_VERSION_KEY, PRIVACY_POLICY_VERSION).apply()
                            privacyAccepted = true
                        },
                        onReject = { finishAndRemoveTask() },
                    )
                    return@MeinKlickerTheme
                }
                var openId by rememberSaveable { mutableStateOf<String?>(null) }
                var aboutOpen by rememberSaveable { mutableStateOf(false) }
                var privacyPolicyOpen by rememberSaveable { mutableStateOf(false) }
                val schemes by repository.schemes.collectAsStateWithLifecycle()
                val open = openId?.let { id -> schemes.find { it.id == id } }
                if (privacyPolicyOpen) {
                    BackHandler { privacyPolicyOpen = false }
                    PrivacyPolicyScreen(onBack = { privacyPolicyOpen = false })
                } else if (aboutOpen) {
                    BackHandler { aboutOpen = false }
                    AboutScreen(
                        onBack = { aboutOpen = false },
                        onOpenPrivacyPolicy = { privacyPolicyOpen = true },
                    )
                } else if (open != null) {
                    BackHandler { openId = null }
                    SchemeDetailScreen(open, onBack = { openId = null })
                } else {
                    HomeScreen(
                        onOpenScheme = { openId = it },
                        onOpenAbout = { aboutOpen = true },
                    )
                }
            }
        }
    }
}
