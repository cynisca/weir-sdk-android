# Weir Android SDK — consumer ProGuard/R8 rules.
#
# These rules are packaged into the AAR (wired via
# `consumerProguardFiles("consumer-rules.pro")` in defaultConfig) and are
# automatically applied to any app that depends on Weir. They keep the pieces of
# the SDK that R8 cannot see are reachable, so Niyat's `minifyEnabled = true`
# release build does not strip or rename them.

# ---------------------------------------------------------------------------
# 1. JavascriptInterface bridge (BridgeRouter.postMessage).
#
# R8 has no way to know the WebView's JS calls `window.<name>.postMessage(...)`
# by reflection at runtime, so every @JavascriptInterface method must survive
# minification and renaming, or the web<->native bridge silently breaks.
# ---------------------------------------------------------------------------
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ---------------------------------------------------------------------------
# 2. kotlinx.serialization generated serializers.
#
# The bridge envelopes and OTA manifest types are @Serializable. The compiler
# generates a companion `$serializer` and a `serializer()` accessor that are
# looked up reflectively; keep them (and the enclosing types' members) for the
# whole SDK package so JSON encode/decode keeps working under R8.
# (Standard kotlinx-serialization keep rules, scoped to studio.aldric.weir.**)
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

# Keep the SerializersKt bootstrap the plugin relies on.
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswith members class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Named companions of @Serializable classes.
-if @kotlinx.serialization.Serializable class studio.aldric.weir.**
-keepclassmembers class studio.aldric.weir.** {
    static <1>$Companion Companion;
}

# Synthetic $serializer classes and their INSTANCE + serializer().
-if @kotlinx.serialization.Serializable class studio.aldric.weir.**
-keep class studio.aldric.weir.**$$serializer {
    *;
}
-keepclassmembers @kotlinx.serialization.Serializable class studio.aldric.weir.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# ---------------------------------------------------------------------------
# 3. Public API surface referenced by Niyat.
#
# The host app calls into these entry points and passes/receives these result
# and config types. Keep the public members so the linkage from Niyat's code
# resolves and so sealed-result subtypes (Completed/Dismissed/Failed) survive.
# ---------------------------------------------------------------------------
-keep public class studio.aldric.weir.Weir { public *; }
-keep public class studio.aldric.weir.Weir$* { public *; }
-keep public class studio.aldric.weir.WeirSdk { public *; }
-keep public class studio.aldric.weir.WeirFlowActivity { public *; }
-keep public class studio.aldric.weir.WeirPresentation { public *; }
-keep public class studio.aldric.weir.WeirIdentity { public *; }

# Sealed result hierarchy: WeirFlowResult + Completed/Dismissed/Failed.
-keep public class studio.aldric.weir.WeirFlowResult { *; }
-keep public class studio.aldric.weir.WeirFlowResult$* { *; }

# Presentation config value types passed by the host.
-keep public class studio.aldric.weir.WeirSystemBarStyle { *; }
-keep public class studio.aldric.weir.WeirSystemBarStyle$* { *; }

# WebView host + its error hierarchy (public signatures reference webkit types).
-keep public class studio.aldric.weir.webview.WeirWebView { public *; }
-keep public class studio.aldric.weir.webview.WeirWebViewError { *; }
-keep public class studio.aldric.weir.webview.WeirWebViewError$* { *; }

# Public bridge SPI the host implements/passes (EventSink, PurchaseProviding,
# HapticEngine, PermissionRequester) plus the enums / value types they exchange
# (PermissionType, PermissionStatus, WeirVariable).
-keep public interface studio.aldric.weir.bridge.** { *; }
-keep public enum studio.aldric.weir.bridge.** { *; }
-keep public class studio.aldric.weir.bridge.WeirVariable { *; }
-keep public class studio.aldric.weir.bridge.HapticEngine { public *; }
-keep public class studio.aldric.weir.billing.** { public *; }

# Ingest/update configuration objects the host constructs.
-keep public class studio.aldric.weir.persistence.WeirIngestConfig { *; }
-keep public class studio.aldric.weir.persistence.WeirUpdateConfig { *; }
