# Axion — regras R8/ProGuard
#
# O app usa reflexão em duas frentes e ambas precisam de keep explícito agora
# que o release roda com minifyEnabled true:
#   1. org.json é resolvido via android.jar em runtime (no device) e via
#      org.json:json nos testes unitários; manter como-is evita renomear
#      campos que o código acessa dinamicamente.
#   2. OkHttp/Okio trazem bytecode Kotlin que o R8 já entende, mas os
#      warnings do META-INF (com.ryanharter etc.) poluem o log.

# ---- org.json (usado extensivamente; sem reflexão real, mas barato garantir) ----
-dontwarn org.json.**

# ---- OkHttp / Okio ----
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.*

# ---- Kotlin coroutines ----
-dontwarn kotlinx.coroutines.**

# ---- Firebase / AdMob (already consumer-rules'd; silence residual warnings) ----
-dontwarn com.google.android.gms.**
-dontwarn com.google.firebase.**

# ---- sora-editor / Markwon / androidsvg ----
-dontwarn io.github.rosemoe.**
-dontwarn io.noties.markwon.**
-dontwarn com.caverock.androidsvg.**

# ---- JavaPoet / anvi­ler lookalikes that surface via dependency metadata ----
-dontwarn com.squareup.javapoet.**
