package com.meinklicker.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meinklicker.container
import com.meinklicker.ui.theme.MeinKlickerTheme

/** 首页、方案设置和关于页通过可保存的状态切换。 */
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
        setContent {
            MeinKlickerTheme {
                var openId by rememberSaveable { mutableStateOf<String?>(null) }
                var aboutOpen by rememberSaveable { mutableStateOf(false) }
                val schemes by repository.schemes.collectAsStateWithLifecycle()
                val open = openId?.let { id -> schemes.find { it.id == id } }
                if (aboutOpen) {
                    BackHandler { aboutOpen = false }
                    AboutScreen(onBack = { aboutOpen = false })
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
