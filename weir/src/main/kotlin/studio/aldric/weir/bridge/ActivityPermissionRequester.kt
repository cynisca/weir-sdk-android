package studio.aldric.weir.bridge

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Pure, framework-free permission mapping — the honest translation from a
 * bridge [PermissionType] to the concrete Android runtime permission(s) (or a
 * non-runtime resolution), keyed off the API level. Extracted from
 * [ActivityPermissionRequester] so it's unit-testable with plain JUnit (no
 * Robolectric, no Activity, no launcher).
 *
 * This is the load-bearing "never fabricate `granted`" contract from BRIDGE.md,
 * expressed as data — see plan §3.2's permission table:
 *
 *  - `notifications` → `POST_NOTIFICATIONS` runtime request on API 33+; on ≤32
 *    there is no runtime notification permission, so we ensure a channel exists
 *    and report `granted` (the honest answer: notifications *are* allowed).
 *  - `camera` → `CAMERA` runtime.
 *  - `location` → `ACCESS_FINE_LOCATION`/`ACCESS_COARSE_LOCATION` runtime,
 *    granted if the user grants *either* (coarse-only is still "location
 *    granted", the honest reading).
 *  - `tracking` → **unavailable**. There is no ATT analogue on Android; the
 *    advertising-ID / data-safety consent is a Play policy matter, not a
 *    runtime prompt. Faking `granted` would violate BRIDGE.md.
 *  - `health` → **unavailable** for now (Health Connect is a later add; this
 *    matches iOS's current honest HealthKit stub).
 */
object PermissionMapping {

    /** How a given [PermissionType] resolves on this device/API level. */
    sealed interface Plan {
        /** No runtime prompt exists / is appropriate — answer `unavailable`
         *  honestly (`tracking`, `health`). */
        data object Unavailable : Plan

        /** ≤32 notifications: no runtime permission; ensure a channel and
         *  report `granted`. */
        data object NotificationChannelAutoGrant : Plan

        /** A real runtime permission request. [permissions] is what the
         *  `ActivityResultLauncher` launches; [anyOf] chooses the grant policy
         *  (location = any-of coarse/fine; everything else = all-of). */
        data class Runtime(val permissions: List<String>, val anyOf: Boolean) : Plan
    }

    /**
     * Resolves [type] against [sdkInt]. Pure — the whole point of this object.
     */
    fun plan(type: PermissionType, sdkInt: Int = Build.VERSION.SDK_INT): Plan = when (type) {
        PermissionType.NOTIFICATIONS ->
            if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
                Plan.Runtime(listOf(Manifest.permission.POST_NOTIFICATIONS), anyOf = false)
            } else {
                Plan.NotificationChannelAutoGrant
            }

        PermissionType.CAMERA ->
            Plan.Runtime(listOf(Manifest.permission.CAMERA), anyOf = false)

        PermissionType.LOCATION ->
            Plan.Runtime(
                listOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
                anyOf = true,
            )

        // Honest per BRIDGE.md — no Android runtime prompt exists for these.
        PermissionType.TRACKING -> Plan.Unavailable
        PermissionType.HEALTH -> Plan.Unavailable
    }

    /**
     * Maps a system result map (androidPermission → wasGranted) back to a
     * bridge [PermissionStatus] for a [Plan.Runtime]. Pure so the grant policy
     * (any-of vs all-of) is unit-tested directly.
     */
    fun statusFor(plan: Plan.Runtime, results: Map<String, Boolean>): PermissionStatus {
        val granted = if (plan.anyOf) {
            plan.permissions.any { results[it] == true }
        } else {
            plan.permissions.all { results[it] == true }
        }
        return if (granted) PermissionStatus.GRANTED else PermissionStatus.DENIED
    }

    /** Default channel used by the ≤32 notifications auto-grant path and as a
     *  sane default the host can override. */
    const val DEFAULT_CHANNEL_ID = "weir_default"
    const val DEFAULT_CHANNEL_NAME = "Notifications"
}

/**
 * The real, Activity-backed [PermissionRequester] (plan §3, item 2). The Phase-1
 * default ([UnavailablePermissionRequester]) honestly answered `unavailable`
 * because there was no Activity to show a system prompt from; this is the
 * production implementation the [studio.aldric.weir.WeirFlowActivity] host wires
 * in.
 *
 * It suspends until the `ActivityResultLauncher` callback resolves — the
 * Android analogue of the iOS `async`/continuation shape — via the injected
 * [launchSystemRequest] suspend function (the host owns the launcher, since it
 * must be registered before the Activity is STARTED). Keeping that a plain
 * injected lambda (rather than holding the launcher here) is what lets the unit
 * tests stub the system layer and assert the mapping without a real Activity.
 *
 * @param context used for the already-granted short-circuit
 *   ([ContextCompat.checkSelfPermission]) and to ensure the notification channel
 *   on the ≤32 path.
 * @param launchSystemRequest suspends, launches the runtime request for the
 *   given android permissions, and returns each permission's granted flag once
 *   the user answers. The host serializes concurrent calls; this class also
 *   guards with a [Mutex] so a flow that fires two primes back-to-back can't
 *   interleave two launches on one single-slot launcher.
 */
class ActivityPermissionRequester(
    private val context: Context,
    private val launchSystemRequest: suspend (permissions: List<String>) -> Map<String, Boolean>,
    private val ensureNotificationChannel: () -> Unit = { defaultEnsureChannel(context) },
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : PermissionRequester {

    private val launchMutex = Mutex()

    override suspend fun requestPermission(type: PermissionType): PermissionStatus =
        when (val plan = PermissionMapping.plan(type, sdkInt)) {
            is PermissionMapping.Plan.Unavailable -> PermissionStatus.UNAVAILABLE

            is PermissionMapping.Plan.NotificationChannelAutoGrant -> {
                ensureNotificationChannel()
                PermissionStatus.GRANTED
            }

            is PermissionMapping.Plan.Runtime -> {
                // Already-granted short-circuit: never re-prompt for a permission
                // the app already holds (matches the iOS `.authorized` fast path).
                val alreadyGranted = if (plan.anyOf) {
                    plan.permissions.any { isGranted(it) }
                } else {
                    plan.permissions.all { isGranted(it) }
                }
                if (alreadyGranted) {
                    PermissionStatus.GRANTED
                } else {
                    val results = launchMutex.withLock { launchSystemRequest(plan.permissions) }
                    PermissionMapping.statusFor(plan, results)
                }
            }
        }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        /** Ensures the default notification channel exists (API 26+). No-op on
         *  older APIs (channels didn't exist). Used by the ≤32 notifications
         *  auto-grant path. */
        fun defaultEnsureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            if (manager.getNotificationChannel(PermissionMapping.DEFAULT_CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(
                    PermissionMapping.DEFAULT_CHANNEL_ID,
                    PermissionMapping.DEFAULT_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
    }
}
