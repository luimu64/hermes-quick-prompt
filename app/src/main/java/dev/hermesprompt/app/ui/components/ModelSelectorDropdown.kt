package dev.hermesprompt.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.hermesprompt.app.data.models.ModelOption
import dev.hermesprompt.app.data.models.ModelRegistry
import dev.hermesprompt.app.data.models.Provider

/**
 * Responsive model selection combobox / dropdown component.
 *
 * Features:
 * - Groups available models by provider (Nous Research, OpenRouter, Anthropic, OpenAI, etc.).
 * - Fuzzy search & filtering across model names, technical IDs, and provider names.
 * - Displays friendly names alongside technical IDs and context window badges.
 * - Supports custom model input both dynamically via search query and via custom dialog.
 * - Clear affordance to revert to server default.
 *
 * @param selectedModel Technical model ID string (empty for server default).
 * @param onModelSelected Callback invoked when a model ID is selected or entered.
 * @param modifier Composable modifier.
 * @param label Field label.
 * @param enabled Whether the dropdown is interactive.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSelectorDropdown(
    selectedModel: String,
    onModelSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    availableModels: List<ModelOption> = emptyList(),
    isLoadingModels: Boolean = false,
    onRefreshModels: (() -> Unit)? = null,
    label: String = "Model (optional)",
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showCustomDialog by remember { mutableStateOf(false) }

    val baseModels = remember(availableModels) {
        if (availableModels.isNotEmpty()) {
            val list = mutableListOf<ModelOption>()
            if (availableModels.none { it.isDefault }) {
                list.add(ModelRegistry.SERVER_DEFAULT_MODEL)
            }
            list.addAll(availableModels)
            list
        } else {
            // Model selection is empty if connection isn't working
            listOf(ModelRegistry.SERVER_DEFAULT_MODEL)
        }
    }

    val currentOption = remember(selectedModel, availableModels) {
        availableModels.firstOrNull { it.id.equals(selectedModel.trim(), ignoreCase = true) }
            ?: ModelRegistry.resolveModel(selectedModel)
    }

    val filteredModels = remember(searchQuery, baseModels) {
        ModelRegistry.filterModels(searchQuery, baseModels)
    }

    val groupedModels = remember(filteredModels) {
        ModelRegistry.getModelsGroupedByProvider(filteredModels)
    }

    val isCustomExactMatch = remember(searchQuery, filteredModels) {
        searchQuery.isBlank() || filteredModels.any { it.id.equals(searchQuery.trim(), ignoreCase = true) }
    }

    var textFieldSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current

    Box(modifier = modifier) {
        OutlinedTextField(
            value = if (currentOption.isDefault) "" else currentOption.fullDisplayLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            placeholder = { Text("Server default (or select a model)") },
            supportingText = {
                if (currentOption.isDefault) {
                    Text("Leave blank to use the server default.")
                } else {
                    Text(
                        text = "Selected: ${currentOption.displayName} • ${currentOption.provider.displayName}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!currentOption.isDefault && enabled) {
                        IconButton(
                            onClick = { onModelSelected("") },
                            modifier = Modifier.testTag("model_selector_clear_btn"),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear model selection",
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    IconButton(
                        onClick = { if (enabled) expanded = !expanded },
                        modifier = Modifier.testTag("model_selector_toggle_btn"),
                    ) {
                        Icon(
                            imageVector = if (expanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                            contentDescription = if (expanded) "Close menu" else "Open menu",
                        )
                    }
                }
            },
            enabled = enabled,
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coordinates ->
                    textFieldSize = coordinates.size
                }
                .testTag("model_selector_field"),
        )

        // Click interceptor over read-only text field
        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(end = 56.dp)
                .clickable(
                    enabled = enabled,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    expanded = !expanded
                }
        )

        if (expanded) {
            Popup(
                onDismissRequest = {
                    expanded = false
                    searchQuery = ""
                },
                properties = PopupProperties(focusable = true),
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp,
                    modifier = Modifier
                        .width(with(density) { if (textFieldSize.width > 0) textFieldSize.width.toDp() else 360.dp })
                        .heightIn(max = 480.dp)
                        .testTag("model_selector_menu"),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // ── Search & Filter Input ──────────────────────────────────
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = "Search",
                                        modifier = Modifier.size(20.dp),
                                    )
                                },
                                trailingIcon = {
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(onClick = { searchQuery = "" }) {
                                            Icon(
                                                imageVector = Icons.Default.Clear,
                                                contentDescription = "Clear search",
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    }
                                },
                                placeholder = { Text("Search model, ID, or provider...") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(
                                    onSearch = {
                                        if (searchQuery.isNotBlank()) {
                                            onModelSelected(searchQuery.trim())
                                            expanded = false
                                            searchQuery = ""
                                        }
                                    }
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("model_selector_search_input"),
                            )

                            if (onRefreshModels != null) {
                                Spacer(Modifier.width(8.dp))
                                if (isLoadingModels) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.5.dp,
                                    )
                                } else {
                                    IconButton(
                                        onClick = onRefreshModels,
                                        modifier = Modifier.testTag("model_selector_refresh_btn"),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Refresh,
                                            contentDescription = "Refresh models from server",
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                        // ── Quick Action: Use Query as Custom Model ────────────────
                        if (searchQuery.isNotBlank() && !isCustomExactMatch) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        onModelSelected(searchQuery.trim())
                                        expanded = false
                                        searchQuery = ""
                                    }
                                    .testTag("model_use_custom_query_btn"),
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Use custom model",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        )
                                        Text(
                                            text = searchQuery.trim(),
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        }

                        // ── Direct Custom Model Dialog Option ──────────────────────
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    expanded = false
                                    showCustomDialog = true
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                                .testTag("model_enter_custom_option"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = "Enter custom model string...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium,
                            )
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                        // ── Virtualized Filtered Model Groups ──────────────────────
                        if (availableModels.isEmpty() && searchQuery.isBlank()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                            ) {
                                ModelItemRow(
                                    model = ModelRegistry.SERVER_DEFAULT_MODEL,
                                    isSelected = selectedModel.isBlank(),
                                    onClick = {
                                        onModelSelected("")
                                        expanded = false
                                    },
                                )
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    text = "No models loaded from server. Test connection above to fetch available models.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else if (filteredModels.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "No matching models found.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 340.dp),
                            ) {
                                groupedModels.forEach { (provider, modelsInGroup) ->
                                    item(key = "header_${provider.id}") {
                                        ProviderHeader(provider = provider)
                                    }

                                    items(modelsInGroup, key = { "${provider.id}_${it.id}" }) { model ->
                                        val isSelected = if (model.isDefault) {
                                            selectedModel.isBlank()
                                        } else {
                                            selectedModel.equals(model.id, ignoreCase = true)
                                        }

                                        ModelItemRow(
                                            model = model,
                                            isSelected = isSelected,
                                            onClick = {
                                                onModelSelected(model.id)
                                                expanded = false
                                                searchQuery = ""
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Custom Model Input Dialog ──────────────────────────────────────────
    if (showCustomDialog) {
        CustomModelInputDialog(
            initialValue = if (currentOption.isCustom) selectedModel else "",
            onConfirm = { customId ->
                onModelSelected(customId.trim())
                showCustomDialog = false
            },
            onDismiss = { showCustomDialog = false },
        )
    }
}

@Composable
private fun ProviderHeader(
    provider: Provider,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = provider.displayName.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            provider.description?.let { desc ->
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ModelItemRow(
    model: ModelOption,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = if (isSelected) {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("model_item_${model.id.ifBlank { "default" }}"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = model.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                    )
                    if (model.formattedContextWindow != null) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.padding(horizontal = 2.dp),
                        ) {
                            Text(
                                text = model.formattedContextWindow!!,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (model.isDefault) "Server default" else model.id,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = if (model.isDefault) FontFamily.Default else FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (isSelected) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
fun CustomModelInputDialog(
    initialValue: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initialValue) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom Model String") },
        text = {
            Column {
                Text(
                    text = "Enter the exact provider/model identifier accepted by your Hermes backend.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Model ID") },
                    placeholder = { Text("e.g. openrouter/meta-llama/llama-3.3-70b-instruct") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onConfirm(text) }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .testTag("custom_model_dialog_input"),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                modifier = Modifier.testTag("custom_model_dialog_confirm_btn"),
            ) {
                Text("Confirm")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("custom_model_dialog_dismiss_btn"),
            ) {
                Text("Cancel")
            }
        },
    )
}
