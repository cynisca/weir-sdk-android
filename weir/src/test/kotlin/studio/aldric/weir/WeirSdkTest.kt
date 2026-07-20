package studio.aldric.weir

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeirSdkTest {
    @Test
    fun version_isSkeletonPlaceholder() {
        assertEquals("0.0.1-skeleton", WeirSdk.WEIR_SDK_VERSION)
    }

    @Test
    fun greeting_includesVersionAndName() {
        val result = WeirSdk.greeting("Niyat")
        assertTrue(result.contains(WeirSdk.WEIR_SDK_VERSION))
        assertTrue(result.contains("Niyat"))
    }
}
