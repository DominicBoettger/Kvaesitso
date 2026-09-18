package de.mm20.launcher2.ui.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * L3 smoke test: validates the Roborazzi + Robolectric Native Graphics
 * pipeline. Real goldens land with the Liquid Glass work (Phase 4, ADR 0004).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChipScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun chip() {
        composeRule.setContent {
            MaterialTheme {
                Chip(text = "Screenshot")
            }
        }
        composeRule.onRoot().captureRoboImage()
    }
}
