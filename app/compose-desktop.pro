# OkHttp ships optional platform integrations for Android and alternate TLS providers.
# They are probed reflectively and are safe to ignore on the packaged desktop runtime.
-dontwarn android.**
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
