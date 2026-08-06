package com.vault999.android

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vault999.android.designsystem.VaultTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun topLevelDestinationsRemainReachable() {
        compose.onNodeWithText("The Vault").assertIsDisplayed()
        compose.onNodeWithContentDescription("Listen tab").performClick()
        compose.onNodeWithContentDescription("Listen tab", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Library tab").performClick()
        compose.onNodeWithText("Your Library").assertIsDisplayed()
        compose.onNodeWithContentDescription("Search tab").performClick()
        compose.onNodeWithContentDescription("Search tab", substring = true).assertIsDisplayed()
    }

    @Test
    fun topLevelNavigationRemainsOperableAtTwoHundredPercentFontScale() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                    VaultTheme { VaultApp() }
                }
            }
        }
        compose.onNodeWithContentDescription("Listen tab").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Library tab").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Search tab").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Archive tab").assertIsDisplayed().performClick()
        compose.onNodeWithText("The Vault").assertIsDisplayed()
    }
}
