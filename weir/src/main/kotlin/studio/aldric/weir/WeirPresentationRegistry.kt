package studio.aldric.weir

import androidx.compose.runtime.Composable
import studio.aldric.weir.bridge.PermissionRequester
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal object WeirPresentationRegistry {
    private val requests = ConcurrentHashMap<String, WeirPresentationRequest>()

    fun register(request: WeirPresentationRequest): String =
        UUID.randomUUID().toString().also { requests[it] = request }

    fun get(token: String?): WeirPresentationRequest? = token?.let(requests::get)
    fun remove(token: String?) { token?.let(requests::remove) }
}

internal class WeirPresentationRequest(
    val systemBarStyle: WeirSystemBarStyle,
    val content: @Composable (PermissionRequester, (WeirFlowResult) -> Unit) -> Unit,
    private val completion: (WeirFlowResult) -> Unit,
) {
    private val delivered = AtomicBoolean(false)
    val hasDelivered: Boolean get() = delivered.get()

    fun deliver(result: WeirFlowResult) {
        if (delivered.compareAndSet(false, true)) completion(result)
    }
}
