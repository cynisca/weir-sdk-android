package studio.aldric.weir

import studio.aldric.weir.bridge.WeirJson
import studio.aldric.weir.engine.FlowConfig
import studio.aldric.weir.persistence.BundleManager
import studio.aldric.weir.persistence.WeirBundleResolution
import studio.aldric.weir.persistence.WeirUpdateController
import java.io.File

sealed class WeirFlowConfigError : Exception() {
    data class FlowNotFound(val flowId: String) : WeirFlowConfigError()
    data class ConfigDecodeFailed(val flowId: String, override val cause: Throwable) : WeirFlowConfigError()
}

object WeirFlowConfigResolution {
    fun resolve(
        flowId: String,
        hostConfigRoot: File?,
        updateController: WeirUpdateController?,
        fallbackBundleManager: BundleManager,
    ): Result<Pair<FlowConfig, File>> {
        val root = WeirBundleResolution.resolve(
            flowId,
            hostConfigRoot,
            updateController,
            fallbackBundleManager,
        ).root

        val configJson = File(root, "config.json")
        if (configJson.isFile) {
            try {
                val parsed = WeirJson.decodeFromString(FlowConfig.serializer(), configJson.readText())
                if (parsed.id == flowId) return Result.success(parsed to root)
            } catch (error: Exception) {
                return Result.failure(WeirFlowConfigError.ConfigDecodeFailed(flowId, error))
            }
        }

        val specFile = File(File(root, "flows"), "$flowId.json")
        if (!specFile.isFile) return Result.failure(WeirFlowConfigError.FlowNotFound(flowId))
        return try {
            Result.success(WeirJson.decodeFromString(FlowConfig.serializer(), specFile.readText()) to root)
        } catch (error: Exception) {
            Result.failure(WeirFlowConfigError.ConfigDecodeFailed(flowId, error))
        }
    }
}
