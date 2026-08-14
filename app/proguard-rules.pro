# Default ProGuard rules for Hermes Quick Prompt.
# No special rules needed — kotlinx-serialization and OkHttp are handled
# by their respective gradle plugins / bundled consumer proguard files.

# Keep the Application class and its public fields
-keep class dev.hermesprompt.app.HermesPromptApp { *; }
-keep class dev.hermesprompt.app.AppContainer { *; }

# Keep serializable data classes used by kotlinx-serialization
-keep @kotlinx.serialization.Serializable class * { *; }
