package studio.aldric.weir.bridge

import android.Manifest
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure mapping tests (no Robolectric) for the honest permission translation —
 * the load-bearing "never fabricate `granted`" contract from BRIDGE.md and
 * plan §3.2. Framework-free: the SDK-version input is passed explicitly, and
 * the android permission ids are compile-time-inlined constants.
 */
class PermissionMappingTest {

    @Test
    fun trackingIsAlwaysUnavailable() {
        // No ATT analogue on Android — must NOT fake granted.
        assertEquals(PermissionMapping.Plan.Unavailable, PermissionMapping.plan(PermissionType.TRACKING, 34))
        assertEquals(PermissionMapping.Plan.Unavailable, PermissionMapping.plan(PermissionType.TRACKING, 30))
    }

    @Test
    fun healthIsUnavailableForNow() {
        // Matches iOS's honest HealthKit stub; Health Connect is a later add.
        assertEquals(PermissionMapping.Plan.Unavailable, PermissionMapping.plan(PermissionType.HEALTH, 34))
    }

    @Test
    fun notificationsOnApi33PlusIsRuntimePostNotifications() {
        val plan = PermissionMapping.plan(PermissionType.NOTIFICATIONS, Build.VERSION_CODES.TIRAMISU)
        assertEquals(
            PermissionMapping.Plan.Runtime(listOf(Manifest.permission.POST_NOTIFICATIONS), anyOf = false),
            plan,
        )
    }

    @Test
    fun notificationsOnApi32AndBelowIsChannelAutoGrant() {
        assertEquals(
            PermissionMapping.Plan.NotificationChannelAutoGrant,
            PermissionMapping.plan(PermissionType.NOTIFICATIONS, 32),
        )
        assertEquals(
            PermissionMapping.Plan.NotificationChannelAutoGrant,
            PermissionMapping.plan(PermissionType.NOTIFICATIONS, 26),
        )
    }

    @Test
    fun cameraIsRuntimeAllOf() {
        val plan = PermissionMapping.plan(PermissionType.CAMERA, 34) as PermissionMapping.Plan.Runtime
        assertEquals(listOf(Manifest.permission.CAMERA), plan.permissions)
        assertTrue(!plan.anyOf)
    }

    @Test
    fun locationIsRuntimeAnyOfFineOrCoarse() {
        val plan = PermissionMapping.plan(PermissionType.LOCATION, 34) as PermissionMapping.Plan.Runtime
        assertEquals(
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            plan.permissions,
        )
        assertTrue("location grants on either coarse or fine", plan.anyOf)
    }

    @Test
    fun statusForAllOfRequiresEveryPermission() {
        val plan = PermissionMapping.Plan.Runtime(listOf("a", "b"), anyOf = false)
        assertEquals(PermissionStatus.GRANTED, PermissionMapping.statusFor(plan, mapOf("a" to true, "b" to true)))
        assertEquals(PermissionStatus.DENIED, PermissionMapping.statusFor(plan, mapOf("a" to true, "b" to false)))
        assertEquals(PermissionStatus.DENIED, PermissionMapping.statusFor(plan, mapOf("a" to false, "b" to false)))
    }

    @Test
    fun statusForAnyOfNeedsJustOne() {
        val plan = PermissionMapping.Plan.Runtime(listOf("fine", "coarse"), anyOf = true)
        assertEquals(PermissionStatus.GRANTED, PermissionMapping.statusFor(plan, mapOf("fine" to false, "coarse" to true)))
        assertEquals(PermissionStatus.GRANTED, PermissionMapping.statusFor(plan, mapOf("fine" to true, "coarse" to false)))
        assertEquals(PermissionStatus.DENIED, PermissionMapping.statusFor(plan, mapOf("fine" to false, "coarse" to false)))
    }

    @Test
    fun statusForMissingKeyIsDenied() {
        // A permission absent from the result map (system dropped it) counts as
        // not-granted, never silently granted.
        val plan = PermissionMapping.Plan.Runtime(listOf("a"), anyOf = false)
        assertEquals(PermissionStatus.DENIED, PermissionMapping.statusFor(plan, emptyMap()))
    }
}
