package dev.hermesprompt.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hermesprompt.app.ui.components.ModelSelectorDropdown

/**
 * Settings screen — the default launcher destination.
 *
 * Users configure their Hermes server address, API key, and optional model name.
 * A "Test connection" button pings /v1/health to verify connectivity.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showApiKey by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hermes Quick Prompt") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {

            // ── Info banner ────────────────────────────────────────────
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Quick Prompt for Hermes Agent",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Set this app as your default Digital Assistant (Settings → Apps → Default apps → Digital assistant) to summon the prompt sheet with a long-press of the power button.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }

            // ── Connection settings ────────────────────────────────────
            Text(
                text = "Connection",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )

            OutlinedTextField(
                value = uiState.serverUrl,
                onValueChange = viewModel::onServerUrlChange,
                label = { Text("Server address") },
                placeholder = { Text("https://hermes.example.com") },
                supportingText = {
                    if (uiState.serverUrlError != null) {
                        Text(uiState.serverUrlError!!, color = MaterialTheme.colorScheme.error)
                    } else {
                        Text("http:// or https://. Path is stripped on save.")
                    }
                },
                isError = uiState.serverUrlError != null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = uiState.apiKey,
                onValueChange = viewModel::onApiKeyChange,
                label = { Text("API key") },
                placeholder = { Text("your-api-key") },
                visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showApiKey = !showApiKey }) {
                        Icon(
                            imageVector = if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showApiKey) "Hide API key" else "Show API key",
                        )
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )

            ModelSelectorDropdown(
                selectedModel = uiState.model,
                onModelSelected = viewModel::onModelChange,
                availableModels = uiState.availableModels,
                isLoadingModels = uiState.isLoadingModels,
                onRefreshModels = viewModel::refreshModels,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = uiState.profile,
                onValueChange = viewModel::onProfileChange,
                label = { Text("Profile (optional)") },
                placeholder = { Text("coder") },
                supportingText = {
                    if (uiState.profileError != null) {
                        Text(uiState.profileError!!, color = MaterialTheme.colorScheme.error)
                    } else {
                        Text("Leave blank for the default profile. Lowercase letters, digits, - or _.")
                    }
                },
                isError = uiState.profileError != null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // ── Action buttons ─────────────────────────────────────────
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(
                    onClick = viewModel::testConnection,
                    enabled = !uiState.isTesting && !uiState.isSaving,
                    modifier = Modifier.weight(1f),
                ) {
                    if (uiState.isTesting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Test connection")
                }

                Button(
                    onClick = viewModel::save,
                    enabled = !uiState.isSaving && !uiState.isTesting,
                    modifier = Modifier.weight(1f),
                ) {
                    if (uiState.isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Save")
                }
            }

            // ── Test result ────────────────────────────────────────────
            uiState.testResult?.let { result ->
                when (result) {
                    is TestResult.Success -> {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = "✓ Connected successfully",
                                modifier = Modifier.padding(16.dp),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                    is TestResult.Failure -> {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = "✗ ${result.message}",
                                modifier = Modifier.padding(16.dp),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}
