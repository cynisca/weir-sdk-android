package studio.aldric.weir

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Robolectric-backed port of `sdk-ios/Tests/WeirTests/IdentityTests.swift`.
 * `SharedPreferences` replaces `UserDefaults`; same keys, same sticky
 * semantics.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IdentityTest {

    private fun freshPrefs(name: String): SharedPreferences {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val prefs = ctx.getSharedPreferences("IdentityTest.$name.${UUID.randomUUID()}", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        return prefs
    }

    @Test
    fun assignedVariantIsNullBeforeFirstCall() {
        assertNull(WeirIdentity.assignedVariant(freshPrefs("nilBefore")))
    }

    @Test
    fun resolvedVariantIsStickyAcrossCalls() {
        val prefs = freshPrefs("sticky")
        val first = WeirIdentity.resolvedVariant(prefs)
        repeat(20) {
            assertEquals(first, WeirIdentity.resolvedVariant(prefs))
        }
        assertEquals(first, WeirIdentity.assignedVariant(prefs))
    }

    @Test
    fun resolvedVariantPersistsAcrossPrefsInstances() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val suite = "IdentityTest.persist.${UUID.randomUUID()}"
        val first = ctx.getSharedPreferences(suite, Context.MODE_PRIVATE)
        first.edit().clear().commit()
        val assigned = WeirIdentity.resolvedVariant(first)

        val second = ctx.getSharedPreferences(suite, Context.MODE_PRIVATE)
        assertEquals(assigned, WeirIdentity.resolvedVariant(second))
        assertEquals(assigned, WeirIdentity.assignedVariant(second))
    }

    @Test
    fun resolvedVariantBothArmsReachable() {
        val seen = mutableSetOf<WeirIdentity.ResolvedVariant>()
        for (i in 0 until 200) {
            seen.add(WeirIdentity.resolvedVariant(freshPrefs("bothArms.$i")))
            if (seen.size == 2) break
        }
        assertEquals(setOf(WeirIdentity.ResolvedVariant.WEIR, WeirIdentity.ResolvedVariant.NATIVE), seen)
    }

    @Test
    fun stableUserIdIsMintedAndStable() {
        val prefs = freshPrefs("stableId")
        val first = WeirIdentity.stableUserId(prefs)
        assertFalse(first.isEmpty())
        repeat(20) {
            assertEquals(first, WeirIdentity.stableUserId(prefs))
        }
    }

    @Test
    fun stableUserIdDiffersAcrossStores() {
        val a = WeirIdentity.stableUserId(freshPrefs("storeA"))
        val b = WeirIdentity.stableUserId(freshPrefs("storeB"))
        assertNotEquals(a, b)
    }

    @Test
    fun stableUserIdPersistsAcrossPrefsInstances() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val suite = "IdentityTest.stableIdPersist.${UUID.randomUUID()}"
        val first = ctx.getSharedPreferences(suite, Context.MODE_PRIVATE)
        first.edit().clear().commit()
        val minted = WeirIdentity.stableUserId(first)

        val second = ctx.getSharedPreferences(suite, Context.MODE_PRIVATE)
        assertEquals(minted, WeirIdentity.stableUserId(second))
    }

    @Test
    fun variantAndUserIdAreIndependentKeys() {
        val prefs = freshPrefs("independent")
        WeirIdentity.resolvedVariant(prefs)
        WeirIdentity.stableUserId(prefs)
        assertNotEquals(
            prefs.getString(WeirIdentity.RESOLVED_VARIANT_KEY, null),
            prefs.getString(WeirIdentity.STABLE_USER_ID_KEY, null),
        )
        assertTrue(prefs.contains(WeirIdentity.RESOLVED_VARIANT_KEY))
        assertTrue(prefs.contains(WeirIdentity.STABLE_USER_ID_KEY))
    }
}
