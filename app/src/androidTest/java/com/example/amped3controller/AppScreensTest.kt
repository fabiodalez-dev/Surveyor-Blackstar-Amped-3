package com.example.amped3controller

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** The real screens with no pedal attached: offline behaviour and actual state from the debug demo. */
class AppScreensTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Before fun english() { lang = "en" }

    @Test fun cabinetCanBeSelectedOfflineWithoutEnablingUpload() {
        rule.onNodeWithText("CabRig").performClick()
        rule.onNodeWithTag("cabinet-selector").performScrollTo().assertIsEnabled().performClick()
        rule.onNodeWithText("1x10 Classic USA Combo").performClick()
        rule.onNodeWithTag("cabinet-selector").assertTextContains("1x10 Classic USA Combo")
        rule.onNodeWithTag("apply-cabinet").performScrollTo().assertIsNotEnabled()
    }

    @Test fun controllerSurvivesActivityRecreation() {
        lateinit var before: ControllerModel
        rule.activityRule.scenario.onActivity { before = ViewModelProvider(it)[ControllerModel::class.java] }
        rule.activityRule.scenario.recreate()
        rule.activityRule.scenario.onActivity { assertSame(before, ViewModelProvider(it)[ControllerModel::class.java]) }
    }

    @Test fun offlineAmpPageWaitsForThePedalAndOffersNoSave() {
        rule.onNodeWithText("Waiting for live values from the pedal.").assertExists()
        rule.onNodeWithText("Save to slot 1").performScrollTo().assertIsNotEnabled()
    }

    @Test fun offlinePresetsPageCannotWriteButCanExportAndImport() {
        rule.onNodeWithText("Preset").performClick()
        rule.onNodeWithText("Save to the phone").assertIsNotEnabled()
        rule.onNodeWithText("Export").performScrollTo().assertIsEnabled()
        rule.onNodeWithText("Import").performScrollTo().assertIsEnabled()
    }

    @Test fun languageSwitchTranslatesTheNavigation() {
        rule.onNodeWithText("Settings").performClick()
        rule.onNodeWithText("Italiano").performClick()
        // the tab label and the page heading both change
        rule.onAllNodesWithText("Impostazioni").assertCountEquals(2)
        rule.onNodeWithText("English").performClick()
        rule.onAllNodesWithText("Settings").assertCountEquals(2)
        rule.onAllNodesWithText("Impostazioni").assertCountEquals(0)
    }
}

/** The debug demo loads the bytes read from the pedal on 2026-09-21, so the pages show real state. */
class DemoScreensTest {
    @get:Rule val rule = createEmptyComposeRule()
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before fun launch() {
        lang = "en"
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java).putExtra("demo", true)
        scenario = ActivityScenario.launch(intent)
    }
    @After fun close() { scenario.close() }

    @Test fun ampPageShowsTheLiveChannelAndKnobs() {
        rule.onAllNodesWithText("CLEAN")[0].assertExists()
        rule.onNodeWithText("8.2").assertExists() // Gain byte 104 on a 0-10 scale
        rule.onNodeWithText("Waiting for live values from the pedal.").assertDoesNotExist()
        rule.onNodeWithText("Save to slot 1").performScrollTo().assertIsEnabled()
    }

    @Test fun cabPageShowsTheLoadedCabinetAndCalibratedLevels() {
        rule.onNodeWithText("CabRig").performClick()
        rule.onAllNodesWithText("4x12 Classic UK")[0].assertExists()
        rule.onNodeWithText("Loaded: 4x12 Classic UK · 160 Rib · Off Axis").performScrollTo().assertExists()
        rule.onNodeWithTag("apply-cabinet").performScrollTo().assertIsEnabled()
    }
}
