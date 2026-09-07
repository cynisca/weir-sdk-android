package studio.aldric.weir

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeirSdkTest {
    // RC-P0 (release-gate re-review): an external re-review caught the
    // server's stock-type floor (RFC-010 §4.3, packages/spec's
    // V4_FLOOR_SDK_VERSION) sitting at 1.0.0 while this SDK reported 0.1.1 —
    // every gated stock-screen config would be unservable to Android, the
    // same class of bug task #48 already caught and fixed on
    // sdk-react-native (see that package's version.test.ts). Pin it here so
    // it can't silently drift again.
    @Test
    fun version_isTheServersCurrentStockTypeFloor() {
        assertEquals("1.0.0", WeirSdk.WEIR_SDK_VERSION)
    }

    @Test
    fun greeting_includesVersionAndName() {
        val result = WeirSdk.greeting("Niyat")
        assertTrue(result.contains(WeirSdk.WEIR_SDK_VERSION))
        assertTrue(result.contains("Niyat"))
    }
}
