package studio.aldric.weir.bridge

import android.Manifest
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Robolectric-backed behavior tests for the real Activity-backed requester.
 * The `ActivityResultLauncher` itself needs an instrumented test (a live
 * Activity + a real system dialog — see maestro/), so the system layer is
 * stubbed here via the injected `launchSystemRequest`; everything else (the
 * plan selection, the ≤32 channel auto-grant, the already-granted
 * short-circuit, the result → status mapping) is exercised end to end.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActivityPermissionRequesterTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun trackingReturnsUnavailableWithoutLaunching() = runTest {
        var launched = false
        val requester = ActivityPermissionRequester(
            context = context,
            launchSystemRequest = { launched = true; emptyMap() },
        )
        assertEquals(PermissionStatus.UNAVAILABLE, requester.requestPermission(PermissionType.TRACKING))
        assertFalse("must not show a dialog for tracking", launched)
    }

    @Test
    fun healthReturnsUnavailable() = runTest {
        val requester = ActivityPermissionRequester(context, { emptyMap() })
        assertEquals(PermissionStatus.UNAVAILABLE, requester.requestPermission(PermissionType.HEALTH))
    }

    @Test
    fun cameraGrantedMapsToGranted() = runTest {
        val requester = ActivityPermissionRequester(
            context = context,
            launchSystemRequest = { perms -> perms.associateWith { true } },
        )
        assertEquals(PermissionStatus.GRANTED, requester.requestPermission(PermissionType.CAMERA))
    }

    @Test
    fun cameraDeniedMapsToDenied() = runTest {
        val requester = ActivityPermissionRequester(
            context = context,
            launchSystemRequest = { perms -> perms.associateWith { false } },
        )
        assertEquals(PermissionStatus.DENIED, requester.requestPermission(PermissionType.CAMERA))
    }

    @Test
    fun alreadyGrantedShortCircuitsWithoutLaunching() = runTest {
        shadowOf(context.applicationContext as android.app.Application)
            .grantPermissions(Manifest.permission.CAMERA)
        var launched = false
        val requester = ActivityPermissionRequester(
            context = context,
            launchSystemRequest = { launched = true; emptyMap() },
        )
        assertEquals(PermissionStatus.GRANTED, requester.requestPermission(PermissionType.CAMERA))
        assertFalse("already-held permission must not re-prompt", launched)
    }

    @Test
    fun locationGrantsOnCoarseOnly() = runTest {
        val requester = ActivityPermissionRequester(
            context = context,
            launchSystemRequest = {
                mapOf(
                    Manifest.permission.ACCESS_FINE_LOCATION to false,
                    Manifest.permission.ACCESS_COARSE_LOCATION to true,
                )
            },
        )
        assertEquals(PermissionStatus.GRANTED, requester.requestPermission(PermissionType.LOCATION))
    }

    @Test
    @Config(sdk = [34])
    fun notificationsApi34RequestsRuntime() = runTest {
        val requested = mutableListOf<String>()
        val requester = ActivityPermissionRequester(
            context = context,
            launchSystemRequest = { perms -> requested.addAll(perms); perms.associateWith { true } },
        )
        assertEquals(PermissionStatus.GRANTED, requester.requestPermission(PermissionType.NOTIFICATIONS))
        assertTrue(requested.contains(Manifest.permission.POST_NOTIFICATIONS))
    }

    @Test
    @Config(sdk = [30])
    fun notificationsApi30AutoGrantsViaChannel() = runTest {
        var launched = false
        var channelEnsured = false
        val requester = ActivityPermissionRequester(
            context = context,
            launchSystemRequest = { launched = true; emptyMap() },
            ensureNotificationChannel = { channelEnsured = true },
            sdkInt = 30,
        )
        assertEquals(PermissionStatus.GRANTED, requester.requestPermission(PermissionType.NOTIFICATIONS))
        assertFalse("no runtime dialog exists for notifications on <=32", launched)
        assertTrue("channel must be ensured on the auto-grant path", channelEnsured)
    }
}
