package studio.aldric.weir.registry

import androidx.compose.runtime.Composable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import studio.aldric.weir.BuildConfig
import studio.aldric.weir.bridge.EventParams
import studio.aldric.weir.bridge.EventSink
import studio.aldric.weir.engine.CustomScreenAvailability
import studio.aldric.weir.persistence.BundleManager
import java.util.UUID

/**
 * A tiny JSON-Schema-subset builder — enough to describe a native component's
 * props shape (RFC-011 §8). Its canonical JSON Schema form is hashed exactly
 * like `@x/spec/registry-hash`, allowing a live Android registration to agree
 * with the CLI-generated registry entry and the server-side registry hash.
 */
sealed class WeirPropSchema {
    object StringType : WeirPropSchema()
    object NumberType : WeirPropSchema()
    object BoolType : WeirPropSchema()
    data class EnumType(val values: List<String>) : WeirPropSchema()
    data class ObjectType(val properties: Map<String, WeirPropSchema>, val required: List<String>) : WeirPropSchema()
    data class ArrayType(val of: WeirPropSchema) : WeirPropSchema()

    /** This shape as the RFC-010 JSON Schema wire representation. */
    val jsonSchema: JsonElement
        get() = when (this) {
            StringType -> JsonObject(mapOf("type" to JsonPrimitive("string")))
            NumberType -> JsonObject(mapOf("type" to JsonPrimitive("number")))
            BoolType -> JsonObject(mapOf("type" to JsonPrimitive("boolean")))
            is EnumType -> JsonObject(mapOf(
                "type" to JsonPrimitive("string"),
                "enum" to JsonArray(values.map(::JsonPrimitive)),
            ))
            is ObjectType -> buildMap {
                put("type", JsonPrimitive("object"))
                put("properties", JsonObject(properties.mapValues { it.value.jsonSchema }))
                // Omission (rather than `required: []`) is significant to the
                // canonical wire schema and matches hand-authored manifests.
                if (required.isNotEmpty()) put("required", JsonArray(required.map(::JsonPrimitive)))
            }.let(::JsonObject)
            is ArrayType -> JsonObject(mapOf(
                "type" to JsonPrimitive("array"),
                "items" to of.jsonSchema,
            ))
        }

    /** SHA-256 of [jsonSchema]'s canonical JSON form, lowercase hexadecimal. */
    val schemaHash: String
        get() = BundleManager.sha256Hex(canonicalize(jsonSchema).toByteArray(Charsets.UTF_8))

    companion object {
        /**
         * Convenience matching Swift's `.object(_:)`: every property is
         * required, in sorted key order. That order is load-bearing because
         * arrays retain their input order during canonicalization.
         */
        fun objectOf(properties: Map<String, WeirPropSchema>): ObjectType =
            ObjectType(properties, properties.keys.sorted())

        /**
         * Byte-for-byte port of `canonicalizeSchema` in
         * `packages/spec/src/registry-hash.ts`: object keys sort recursively,
         * arrays retain order, and no whitespace is emitted.
         */
        fun canonicalize(value: JsonElement): String = when (value) {
            JsonNull -> "null"
            is JsonObject -> value.entries.sortedBy { it.key }.joinToString(prefix = "{", postfix = "}", separator = ",") {
                jsonStringLiteral(it.key) + ":" + canonicalize(it.value)
            }
            is JsonArray -> value.joinToString(prefix = "[", postfix = "]", separator = ",", transform = ::canonicalize)
            is JsonPrimitive -> canonicalizePrimitive(value)
            else -> error("Unsupported JsonElement: $value")
        }

        private fun canonicalizePrimitive(value: JsonPrimitive): String = when {
            value.isString -> jsonStringLiteral(value.content)
            value.booleanOrNull != null -> value.booleanOrNull.toString()
            else -> canonicalizeNumber(value.content.toDouble())
        }

        // The schema DSL only creates structural strings/booleans, but this
        // follows JSON.stringify's number presentation for direct callers too.
        private fun canonicalizeNumber(value: Double): String {
            if (!value.isFinite()) return "null"
            if (value == 0.0) return "0"
            val absolute = kotlin.math.abs(value)
            if (absolute >= 1e-6 && absolute < 1e21) {
                return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
            }
            return value.toString()
                .replace('E', 'e')
                .replace("e+", "e")
                .replace(Regex("\\.0e"), "e")
        }

        /** Kotlinx's JSON encoder supplies JSON's required string escaping. */
        private fun jsonStringLiteral(value: String): String = Json.encodeToString(String.serializer(), value)
    }
}

/**
 * Marker for a custom component's typed props. Kotlin cannot express Swift's
 * static protocol requirement (`Props.propSchema`) on a generic type, so
 * [ComponentRegistry.register] deliberately receives both schema and decoder
 * explicitly rather than silently weakening the registration contract.
 */
interface WeirComponentProps { companion object }

/** One `(name, schemaHash)` entry embedded by a generated registry file. */
data class GeneratedRegistryEntry(val name: String, val schemaHash: String)

/**
 * Implemented by the registry generated by `weir components sync`.
 *
 * `packages/mcp`'s `kotlinFile()` codegen, as of this writing, emits an
 * object that does **not** implement this interface. A host consuming today's
 * `WeirComponentRegistry.generated.kt` needs a short adapter, e.g.
 * `object GeneratedAdapter : WeirGeneratedComponentRegistry { override val
 * registryManifestVersion = WeirComponentRegistry.manifestVersion; override
 * val registryHash = WeirComponentRegistry.registryHash; override val entries
 * = WeirComponentRegistry.entries.map { GeneratedRegistryEntry(it.component,
 * it.schemaHash) } }`, until the separately-tracked `packages/mcp` follow-up
 * makes `kotlinFile()` generate this conformance directly.
 */
interface WeirGeneratedComponentRegistry {
    val registryManifestVersion: Int
    val registryHash: String
    val entries: List<GeneratedRegistryEntry>
}

/** Process-wide custom-component registration and generated-registry drift check. */
object ComponentRegistry {
    private data class Entry(
        val minAppVersion: String,
        val schemaHash: String,
        val acceptsProps: (JsonElement) -> Boolean,
        val build: (JsonElement) -> (@Composable () -> Unit)?,
    )

    private val entries = mutableMapOf<String, Entry>()
    private var generated: WeirGeneratedComponentRegistry? = null
    private var driftChecked = false

    /**
     * Whether drift throws during debug development. Defaults to
     * [BuildConfig.DEBUG] (RC-P0, release-gate re-review) — NOT a hardcoded
     * `true` — so a release build can never crash a production app on
     * registry drift; only a debug build asserts hard by default. Settable
     * so tests can exercise the health-event path deterministically
     * regardless of which variant they happen to run under.
     */
    var debugAssertOnDrift = BuildConfig.DEBUG

    /** Registers an app-namespaced component and its fail-closed props decoder. */
    fun <T> register(
        name: String,
        minAppVersion: String = "1.0.0",
        propSchema: WeirPropSchema,
        decode: (JsonElement) -> T?,
        view: @Composable (T) -> Unit,
    ) {
        entries[name] = Entry(
            minAppVersion,
            propSchema.schemaHash,
            acceptsProps = { props ->
                try { decode(props) != null } catch (_: Exception) { false }
            },
            build = { props ->
                val decoded = try { decode(props) } catch (_: Exception) { null }
                decoded?.let { typed -> @Composable { view(typed) } }
            },
        )
    }

    /** Installs the CLI-generated registry conformer; no runtime hash is computed. */
    fun setGeneratedRegistry(registry: WeirGeneratedComponentRegistry) {
        generated = registry
    }

    /** The embedded registry hash, or null until a generated registry is installed. */
    fun registryFingerprint(): String? = generated?.registryHash

    /**
     * Fetch gating requires both manifest version and hash or neither: sending
     * a partial set of parameters can pair a config response with no registry
     * state at all, so this deliberately returns one atomic nullable pair.
     */
    fun registryGatingInfo(): Pair<Int, String>? = generated?.let { it.registryManifestVersion to it.registryHash }

    fun isRegistered(name: String): Boolean = entries.containsKey(name)

    fun availability(name: String, props: Map<String, JsonElement>): CustomScreenAvailability {
        val entry = entries[name] ?: return CustomScreenAvailability.unregistered
        return if (entry.acceptsProps(JsonObject(props))) CustomScreenAvailability.renderable else CustomScreenAvailability.invalidProps
    }

    /** Returns null for an unknown component or a throwing/failed props decode. */
    fun resolveView(name: String, props: Map<String, JsonElement>): (@Composable () -> Unit)? =
        try {
            entries[name]?.build(JsonObject(props))
        } catch (_: Exception) {
            null
        }

    /**
     * Runs the RFC-011 registry drift check at most once per process. The
     * `health_registry_drift` event is emitted UNCONDITIONALLY on a
     * mismatch (RC-P0, release-gate re-review) — before the debug-only
     * assertion below, so the event still reaches the sink even when the
     * assertion throws. Previously the assertion ran first and threw
     * immediately, so a debug build (where it asserted by default) never
     * actually emitted the health event at all — the only build type that
     * could observe it was the one where the assertion was manually
     * disabled.
     *
     * The throw itself is gated by the literal `BuildConfig.DEBUG` check
     * (round-2 re-review), not only [debugAssertOnDrift] — `BuildConfig
     * .DEBUG` is a `public static final boolean` R8/ProGuard can prove
     * `false` in a release build, so `if (BuildConfig.DEBUG && ...)`
     * dead-code-eliminates the entire `check(false)` branch (and its string
     * template) from release bytecode. [debugAssertOnDrift] alone (a
     * mutable var) can't give R8 that guarantee — it's kept as an
     * additional lever so a debug-variant test can still suppress the
     * assertion without needing to fake a compile-time constant.
     */
    fun checkDriftOnce(eventSink: EventSink) {
        val registry = generated ?: return
        if (driftChecked) return
        driftChecked = true

        val live = entries.map { "${it.key}@${it.value.schemaHash}" }.toSet()
        val expected = registry.entries.map { "${it.name}@${it.schemaHash}" }.toSet()
        if (live == expected) return

        eventSink.append(EventParams(
            flowId = "_weir_registry_health",
            screenId = null,
            variantId = null,
            sessionId = UUID.randomUUID().toString(),
            userId = null,
            ts = System.currentTimeMillis().toDouble(),
            seq = 0,
            payload = JsonObject(mapOf(
                "type" to JsonPrimitive("health_registry_drift"),
                "liveCount" to JsonPrimitive(live.size),
                "expectedCount" to JsonPrimitive(expected.size),
            )),
        ))

        if (BuildConfig.DEBUG && debugAssertOnDrift) {
            check(false) {
                "Weir component registry drift: live registrations (${live.sorted()}) don't match " +
                    "the generated registry (${expected.sorted()}). Re-run `weir components sync`."
            }
        }
    }

    /** Test-only reset for process-wide state shared by JUnit test cases. */
    fun resetForTesting() {
        entries.clear()
        generated = null
        driftChecked = false
        debugAssertOnDrift = BuildConfig.DEBUG
    }
}
