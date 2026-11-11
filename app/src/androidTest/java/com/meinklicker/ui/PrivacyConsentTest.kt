package com.meinklicker.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class PrivacyConsentTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val preferences = InstrumentationRegistry.getInstrumentation().targetContext
        .getSharedPreferences(PRIVACY_PREFERENCES, Context.MODE_PRIVATE)
    private var previousVersion: String? = null
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun resetConsent() {
        previousVersion = preferences.getString(PRIVACY_ACCEPTED_VERSION_KEY, null)
        preferences.edit().remove(PRIVACY_ACCEPTED_VERSION_KEY).commit()
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After fun restoreConsent() {
        scenario?.close()
        preferences.edit().putString(PRIVACY_ACCEPTED_VERSION_KEY, previousVersion).commit()
    }

    @Test fun readingPolicyDoesNotGrantConsentAndChoiceSurvivesRecreation() {
        compose.onNodeWithText("同意并进入").assertIsDisplayed()
        compose.onNodeWithText("方案").assertDoesNotExist()
        compose.onNodeWithText("阅读完整隐私政策").performClick()
        compose.onNodeWithText("MeinKlicker 隐私政策").assertIsDisplayed()
        assertNull(preferences.getString(PRIVACY_ACCEPTED_VERSION_KEY, null))
        scenario!!.recreate()
        compose.onNodeWithText("MeinKlicker 隐私政策").assertIsDisplayed()
        compose.onNodeWithText("返回").performClick()
        compose.onNodeWithText("同意并进入").performClick()
        compose.onNodeWithText("方案").assertIsDisplayed()
        assertEquals(PRIVACY_POLICY_VERSION, preferences.getString(PRIVACY_ACCEPTED_VERSION_KEY, null))
        scenario!!.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithText("同意并进入").assertDoesNotExist()
        compose.onNodeWithText("方案").assertIsDisplayed()
    }

    @Test fun rejectionExitsWithoutSavingConsentAndPromptsOnNextLaunch() {
        compose.onNodeWithText("不同意并退出").performClick()
        compose.waitUntil(5_000) { scenario!!.state == Lifecycle.State.DESTROYED }
        assertNull(preferences.getString(PRIVACY_ACCEPTED_VERSION_KEY, null))
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithText("同意并进入").assertIsDisplayed()
        compose.onNodeWithText("方案").assertDoesNotExist()
    }

    @Test fun backExitsWithoutGrantingConsent() {
        compose.onNodeWithText("同意并进入").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(
            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK,
        )
        compose.waitUntil(5_000) { scenario!!.state == Lifecycle.State.DESTROYED }
        assertNull(preferences.getString(PRIVACY_ACCEPTED_VERSION_KEY, null))
    }
}
