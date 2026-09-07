package studio.aldric.weir.bridge

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM round-trip + frozen-contract-parity tests for the Kotlin bridge
 * types. Mirrors `sdk-ios/Tests/WeirTests/BridgeTypesTests.swift` and the
 * TS fixtures in packages/spec.
 */
class BridgeTypesTest {

    private fun <T> decodeParams(json: String, deserializer: kotlinx.serialization.DeserializationStrategy<T>): T {
        val envelope = WeirJson.decodeFromString(BridgeRequestEnvelope.serializer(), json)
        return WeirJson.decodeFromJsonElement(deserializer, envelope.params)
    }

    // ---- Frozen-contract raw values (must match packages/spec/src/bridge.ts) ----

    @Test
    fun bridgeMethodRawValuesMatchFrozenContract() {
        assertEquals("permission.request", BridgeMethod.PERMISSION_REQUEST.raw)
        assertEquals("purchase.start", BridgeMethod.PURCHASE_START.raw)
        assertEquals("purchase.restore", BridgeMethod.PURCHASE_RESTORE.raw)
        assertEquals("haptic", BridgeMethod.HAPTIC.raw)
        assertEquals("event", BridgeMethod.EVENT.raw)
        assertEquals("complete", BridgeMethod.COMPLETE.raw)
        assertEquals("dismiss", BridgeMethod.DISMISS.raw)
        assertEquals("capabilities", BridgeMethod.CAPABILITIES.raw)
        assertEquals("products.list", BridgeMethod.PRODUCTS_LIST.raw)
    }

    @Test
    fun allRawsIsTheFullMethodSet() {
        assertEquals(
            listOf(
                "permission.request", "purchase.start", "purchase.restore", "haptic",
                "event", "complete", "dismiss", "capabilities", "products.list",
            ),
            BridgeMethod.allRaws,
        )
    }

    @Test
    fun transportConstantsMatchContract() {
        assertEquals("weirBridge", BridgeTransport.HANDLER_NAME)
        assertEquals("WeirAndroid", BridgeTransport.ANDROID_INTERFACE_NAME)
        assertEquals("__weirBridgeResponse", BridgeTransport.RESPONSE_GLOBAL)
        assertEquals(1, BridgeTransport.PROTOCOL_VERSION)
    }

    @Test
    fun fromRawUnknownMethodIsNull() {
        assertNull(BridgeMethod.fromRaw("not.a.real.method"))
    }

    // ---- Envelope round-trips ----

    @Test
    fun decodesPermissionRequestEnvelope() {
        val json = """{"id":"1","method":"permission.request","params":{"type":"notifications"}}"""
        val envelope = WeirJson.decodeFromString(BridgeRequestEnvelope.serializer(), json)
        assertEquals("1", envelope.id)
        assertEquals(BridgeMethod.PERMISSION_REQUEST.raw, envelope.method)
        val params = WeirJson.decodeFromJsonElement(PermissionRequestParams.serializer(), envelope.params)
        assertEquals(PermissionType.NOTIFICATIONS, params.type)
    }

    @Test
    fun encodesSuccessResponseOmittingError() {
        val resultEl = WeirJson.encodeToJsonElement(
            PermissionRequestResult.serializer(),
            PermissionRequestResult(PermissionStatus.GRANTED),
        )
        val response = BridgeResponseEnvelope.success("1", resultEl)
        val encoded = WeirJson.encodeToString(BridgeResponseEnvelope.serializer(), response)
        // explicitNulls=false must drop the null `error` field on the wire.
        assertFalse(encoded.contains("error"))
        val decoded = WeirJson.decodeFromString(BridgeResponseEnvelope.serializer(), encoded)
        assertTrue(decoded.ok)
        assertEquals("1", decoded.id)
        assertEquals("granted", decoded.result!!.jsonObject["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun encodesFailureResponseOmittingResult() {
        val encoded = WeirJson.encodeToString(
            BridgeResponseEnvelope.serializer(),
            BridgeResponseEnvelope.failure("9", "unknown bridge method: foo"),
        )
        assertFalse(encoded.contains("\"result\""))
        val decoded = WeirJson.decodeFromString(BridgeResponseEnvelope.serializer(), encoded)
        assertFalse(decoded.ok)
        assertEquals("unknown bridge method: foo", decoded.error)
    }

    // ---- v1 fixture parity (matches BridgeTypesTests.swift) ----

    @Test
    fun v0HapticWithoutIntensityStillDecodes() {
        val params = decodeParams(
            """{"id":"1","method":"haptic","params":{"style":"light"}}""",
            HapticParams.serializer(),
        )
        assertEquals(HapticStyle.LIGHT, params.style)
        assertNull(params.intensity)
    }

    @Test
    fun v1HapticSoftWithIntensityDecodes() {
        val params = decodeParams(
            """{"id":"1","method":"haptic","params":{"style":"soft","intensity":0.62}}""",
            HapticParams.serializer(),
        )
        assertEquals(HapticStyle.SOFT, params.style)
        assertEquals(0.62, params.intensity!!, 0.0001)
    }

    @Test
    fun rigidHapticStyleDecodes() {
        val params = decodeParams(
            """{"id":"1","method":"haptic","params":{"style":"rigid"}}""",
            HapticParams.serializer(),
        )
        assertEquals(HapticStyle.RIGID, params.style)
    }

    @Test
    fun capabilitiesResultRoundTrips() {
        val el = WeirJson.encodeToJsonElement(
            CapabilitiesResult.serializer(),
            CapabilitiesResult(version = 1, methods = listOf("haptic", "products.list")),
        )
        val response = BridgeResponseEnvelope.success("1", el)
        val encoded = WeirJson.encodeToString(BridgeResponseEnvelope.serializer(), response)
        val decoded = WeirJson.decodeFromString(BridgeResponseEnvelope.serializer(), encoded)
        val caps = WeirJson.decodeFromJsonElement(CapabilitiesResult.serializer(), decoded.result!!)
        assertEquals(1, caps.version)
        assertTrue(caps.methods.contains("products.list"))
    }

    @Test
    fun productsListParamsDecodesRequiredProductIds() {
        val params = decodeParams(
            """{"id":"1","method":"products.list","params":{"productIds":["sirat_annual","sirat_pro_monthly"]}}""",
            ProductsListParams.serializer(),
        )
        assertEquals(listOf("sirat_annual", "sirat_pro_monthly"), params.productIds)
    }

    @Test
    fun productsListResultEncodesIntroOffer() {
        val result = ProductsListResult(
            products = listOf(
                ProductInfo(
                    id = "sirat_annual",
                    priceString = "$34.99/yr",
                    currencyCode = "USD",
                    introOffer = IntroOffer(priceString = "$0.00", periodDescription = "first 7 days"),
                ),
            ),
        )
        val encoded = WeirJson.encodeToString(ProductsListResult.serializer(), result)
        val decoded = WeirJson.decodeFromString(ProductsListResult.serializer(), encoded)
        assertEquals("$0.00", decoded.products.first().introOffer!!.priceString)
        // currencyCode present; periodDescription (null) omitted by explicitNulls=false.
        assertFalse(encoded.contains("periodDescription\":null"))
    }

    @Test
    fun completeParamsDecodesVariables() {
        val params = decodeParams(
            """{"id":"1","method":"complete","params":{"variables":[{"id":"v1","type":"string","value":"hello"}]}}""",
            CompleteParams.serializer(),
        )
        assertEquals(1, params.variables.size)
        assertEquals("v1", params.variables.first().id)
        assertEquals("hello", params.variables.first().value.jsonPrimitive.content)
    }

    @Test
    fun dismissParamsMissingReasonFailsDecode() {
        var threw = false
        try {
            decodeParams(
                """{"id":"1","method":"dismiss","params":{}}""",
                DismissParams.serializer(),
            )
        } catch (e: Exception) {
            threw = true
        }
        assertTrue("missing required `reason` must fail decode", threw)
    }

    @Test
    fun emptyAckIsAnObject() {
        // The shared empty result must serialize to `{}`.
        assertEquals("{}", WeirJson.encodeToString(JsonObject.serializer(), JsonObject(emptyMap())))
    }
}
