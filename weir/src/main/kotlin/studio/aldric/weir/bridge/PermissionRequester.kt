package studio.aldric.weir.bridge

/**
 * The seam behind a `permissionPrime` screen's permission request. On iOS the
 * equivalent calls the real `UNUserNotificationCenter`/ATT/AVCapture/
 * CLLocation APIs directly with `async`; on Android a runtime permission
 * needs an `Activity` in the resumed state and an `ActivityResultLauncher`,
 * which `WeirFlowActivity` owns. Injecting this interface keeps callers
 * testable and lets a real Activity-backed implementation slot in without
 * coupling to the Activity itself.
 */
interface PermissionRequester {
    suspend fun requestPermission(type: PermissionType): PermissionStatus
}

/**
 * Phase 1 default: answers every permission honestly with `unavailable` rather
 * than fabricating `granted`. BRIDGE.md is explicit that native must never
 * fabricate a result without showing the real system prompt — and Phase 1 has
 * no Activity host to show one from. Phase 3 replaces this with the real
 * Activity-backed requester.
 */
object UnavailablePermissionRequester : PermissionRequester {
    override suspend fun requestPermission(type: PermissionType): PermissionStatus =
        PermissionStatus.UNAVAILABLE
}
