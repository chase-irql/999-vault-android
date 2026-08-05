package com.vault999.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun topLevelDestinationsRemainReachable() {
        compose.onNodeWithText("THE ARCHIVE, IN YOUR POCKET").assertIsDisplayed()
        compose.onNodeWithContentDescription("Listen tab").performClick()
        compose.onNodeWithText("Eight tracks ahead. Eight recent tracks behind. No immediate repeats.").assertIsDisplayed()
        compose.onNodeWithContentDescription("My Music tab").performClick()
        compose.onNodeWithText("On this device").assertIsDisplayed()
        compose.onNodeWithContentDescription("Search tab").performClick()
        compose.onNodeWithText("Archive · Songs · Lyrics").assertIsDisplayed()
    }

}
