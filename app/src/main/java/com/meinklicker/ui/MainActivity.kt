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

/** 两个页面用状态切换：方案列表 ↔ 方案设置。 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = container.repository
        setContent {
            MeinKlickerTheme {
                var openId by rememberSaveable { mutableStateOf<String?>(null) }
                val schemes by repository.schemes.collectAsStateWithLifecycle()
                val open = openId?.let { id -> schemes.find { it.id == id } }
                if (open != null) {
                    BackHandler { openId = null }
                    SchemeDetailScreen(open, onBack = { openId = null })
                } else {
                    HomeScreen(onOpenScheme = { openId = it })
                }
            }
        }
    }
}
