package studio.aldric.weir.observe

import android.content.SharedPreferences
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import studio.aldric.weir.bridge.WeirJson

internal data class ObserveSession(
    val sessionId: String,
    val nextSeq: Int,
    val startedAtMs: Long,
    val lastActivityAtMs: Long,
    val flowId: String,
    val userId: String?,
    val openScreen: ObserveScreen?,
    val screensSeen: Set<String>,
)

internal data class ObserveScreen(
    val name: String,
    val index: Int?,
    val enteredAtMs: Long,
    val properties: Map<String, JsonElement>,
)

/** Synchronous persistence is intentional: a process kill after an event must
 * not rewind the session sequence or lose the screen needed for abandonment. */
internal class ObserveSessionStore(private val prefs: SharedPreferences) {
    fun load(): ObserveSession? {
        val sessionId = prefs.getString(KEY_SESSION_ID, null) ?: return null
        val props = prefs.getString(KEY_LAST_PROPERTIES, null)?.let {
            runCatching { WeirJson.decodeFromString(JsonObject.serializer(), it) }.getOrNull()
        }?.toMap().orEmpty()
        val lastScreen = prefs.getString(KEY_LAST_SCREEN, null)
        val openScreen = lastScreen?.let {
            ObserveScreen(
                name = it,
                index = if (prefs.contains(KEY_LAST_INDEX)) prefs.getInt(KEY_LAST_INDEX, 0) else null,
                enteredAtMs = prefs.getLong(KEY_LAST_ENTERED_AT, 0),
                properties = props,
            )
        }
        return ObserveSession(
            sessionId = sessionId,
            nextSeq = prefs.getInt(KEY_NEXT_SEQ, 0),
            startedAtMs = prefs.getLong(KEY_STARTED_AT, 0),
            lastActivityAtMs = prefs.getLong(KEY_LAST_ACTIVITY_AT, 0),
            flowId = prefs.getString(KEY_FLOW_ID, "onboarding") ?: "onboarding",
            userId = prefs.getString(KEY_USER_ID, null),
            openScreen = openScreen,
            screensSeen = prefs.getStringSet(KEY_SCREENS_SEEN, emptySet()).orEmpty().toSet(),
        )
    }

    fun save(session: ObserveSession) {
        val props = WeirJson.encodeToString(JsonObject.serializer(), JsonObject(session.openScreen?.properties.orEmpty()))
        prefs.edit()
            .putString(KEY_SESSION_ID, session.sessionId)
            .putInt(KEY_NEXT_SEQ, session.nextSeq)
            .putLong(KEY_STARTED_AT, session.startedAtMs)
            .putLong(KEY_LAST_ACTIVITY_AT, session.lastActivityAtMs)
            .putString(KEY_FLOW_ID, session.flowId)
            .apply {
                if (session.userId == null) remove(KEY_USER_ID) else putString(KEY_USER_ID, session.userId)
                val screen = session.openScreen
                if (screen == null) {
                    remove(KEY_LAST_SCREEN).remove(KEY_LAST_INDEX).remove(KEY_LAST_ENTERED_AT)
                } else {
                    putString(KEY_LAST_SCREEN, screen.name)
                    if (screen.index == null) remove(KEY_LAST_INDEX) else putInt(KEY_LAST_INDEX, screen.index)
                    putLong(KEY_LAST_ENTERED_AT, screen.enteredAtMs)
                }
            }
            .putString(KEY_LAST_PROPERTIES, props)
            .putStringSet(KEY_SCREENS_SEEN, session.screensSeen)
            .commit()
    }

    fun clear() {
        prefs.edit().clear().commit()
    }

    private companion object {
        const val KEY_SESSION_ID = "sessionId"
        const val KEY_NEXT_SEQ = "nextSeq"
        const val KEY_STARTED_AT = "startedAtMs"
        const val KEY_LAST_ACTIVITY_AT = "lastActivityAtMs"
        const val KEY_FLOW_ID = "flowId"
        const val KEY_USER_ID = "userId"
        const val KEY_LAST_SCREEN = "lastScreen"
        const val KEY_LAST_INDEX = "lastIndex"
        const val KEY_LAST_ENTERED_AT = "lastScreenEnteredAtMs"
        const val KEY_LAST_PROPERTIES = "lastProperties"
        const val KEY_SCREENS_SEEN = "screensSeen"
    }
}
