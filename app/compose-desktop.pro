# OkHttp ships optional platform integrations for Android and alternate TLS providers.
# They are probed reflectively and are safe to ignore on the packaged desktop runtime.
-dontwarn android.**
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# Ktor loads its JSON extension provider through ServiceLoader at runtime.
# Keep the provider class name stable so the release DMG can start networking.
-keep class io.ktor.serialization.kotlinx.json.KotlinxSerializationJsonExtensionProvider { *; }
