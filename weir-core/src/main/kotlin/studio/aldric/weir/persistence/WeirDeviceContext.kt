package studio.aldric.weir.persistence

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * Mirrors `packages/spec/src/events.ts`'s `DeviceContext` — sent once per
 * flush batch ([EventBatch.device]), never per event, since a session almost
 * never spans an app update mid-batch. Faithful port of iOS
 * `WeirDeviceContext` (`sdk-ios/Sources/WeirCore/Persistence/WeirDeviceContext.swift`);
 * named `WeirDeviceContext` (not the TS name verbatim) to match this file's
 * sibling `WeirIngestConfig`/`WeirUpdateConfig` naming convention — the wire
 * shape (`platform`/`appVersion`/`sdkVersion`/`locale`/`deviceClass`) is what
 * has to match, and does.
 */
@Serializable
data class WeirDeviceContext(
    val platform: String,
    val appVersion: String,
    val sdkVersion: String,
    val locale: String,
    val deviceClass: String,
) {
    companion object {
        /**
         * Best-effort real values for the current process. [sdkVersion] isn't
         * derivable from anything on-device (this module has no bundled
         * version resource), so it's the caller's job to pass this SDK's own
         * released version string — mirrors iOS
         * `WeirDeviceContext.current(sdkVersion:)`.
         */
        fun current(context: Context, sdkVersion: String): WeirDeviceContext {
            val appVersion = try {
                val info = context.packageManager.getPackageInfo(context.packageName, 0)
                info.versionName ?: "unknown"
            } catch (_: Exception) {
                "unknown"
            }
            val isTablet =
                (context.resources.configuration.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK) >=
                    Configuration.SCREENLAYOUT_SIZE_LARGE
            return WeirDeviceContext(
                platform = "android",
                appVersion = appVersion,
                sdkVersion = sdkVersion,
                locale = Locale.getDefault().toLanguageTag(),
                deviceClass = if (isTablet) "tablet" else "phone",
            )
        }
    }
}
