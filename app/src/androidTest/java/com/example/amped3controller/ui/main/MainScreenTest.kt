package com.example.amped3controller.ui.main

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import com.example.amped3controller.MainActivity
import com.example.amped3controller.ControllerModel
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertSame

class MainScreenTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

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
}
