package dev.hermesprompt.app.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hermesprompt.app.ui.overlay.SkipWhenCoroutinesServiceLoaderBroken
import dev.hermesprompt.app.ui.theme.HermesPromptTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelSelectorDropdownTest {

    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain
        .outerRule(SkipWhenCoroutinesServiceLoaderBroken())
        .around(composeRule)

    @Test
    fun openingDropdownDoesNotCrashAndDisplaysModels() {
        var selectedModel by mutableStateOf("")

        composeRule.setContent {
            HermesPromptTheme {
                ModelSelectorDropdown(
                    selectedModel = selectedModel,
                    onModelSelected = { selectedModel = it },
                )
            }
        }

        // Click the dropdown field to expand menu
        composeRule.onNodeWithTag("model_selector_field").performClick()
        composeRule.waitForIdle()

        // Menu should be open and display search field without intrinsic measurement crash
        composeRule.onNodeWithTag("model_selector_menu").assertIsDisplayed()
        composeRule.onNodeWithTag("model_selector_search_input").assertIsDisplayed()
    }

    @Test
    fun selectingModelUpdatesState() {
        var selectedModel by mutableStateOf("")

        composeRule.setContent {
            HermesPromptTheme {
                ModelSelectorDropdown(
                    selectedModel = selectedModel,
                    onModelSelected = { selectedModel = it },
                )
            }
        }

        // Open menu
        composeRule.onNodeWithTag("model_selector_field").performClick()
        composeRule.waitForIdle()

        // Search for Hermes 3
        composeRule.onNodeWithTag("model_selector_search_input").performTextInput("Hermes 3")
        composeRule.waitForIdle()

        // Select the item
        composeRule.onNodeWithTag("model_item_nous/hermes-3-llama-3.1-70b").performClick()
        composeRule.waitForIdle()

        assertEquals("nous/hermes-3-llama-3.1-70b", selectedModel)
    }

    @Test
    fun clearingModelResetsToServerDefault() {
        var selectedModel by mutableStateOf("nous/hermes-3-llama-3.1-70b")

        composeRule.setContent {
            HermesPromptTheme {
                ModelSelectorDropdown(
                    selectedModel = selectedModel,
                    onModelSelected = { selectedModel = it },
                )
            }
        }

        // Clear button should be visible when a model is selected
        composeRule.onNodeWithTag("model_selector_clear_btn").performClick()
        composeRule.waitForIdle()

        assertEquals("", selectedModel)
    }
}
