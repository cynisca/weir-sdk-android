package studio.aldric.weir

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.aldric.weir.persistence.BundleManager
import java.io.File
import java.nio.file.Files

class WeirFlowConfigResolutionTest {
    @Test fun resolvesPromotedRemoteShapeAtRoot() = withRoot { root ->
        File(root, "config.json").writeText(config("remote"))
        val result = resolve("remote", root)
        assertEquals("remote", result.getOrThrow().first.id)
        assertEquals(root, result.getOrThrow().second)
    }

    @Test fun mismatchedRootConfigFallsThroughToMatchingFlowsFile() = withRoot { root ->
        File(root, "config.json").writeText(config("different"))
        writeFlow(root, "wanted", config("wanted", "Embedded winner"))
        assertEquals("Embedded winner", resolve("wanted", root).getOrThrow().first.name)
    }

    @Test fun resolvesEmbeddedMultiFlowShape() = withRoot { root ->
        writeFlow(root, "embedded", config("embedded"))
        assertEquals("embedded", resolve("embedded", root).getOrThrow().first.id)
    }

    @Test fun neitherShapeReturnsFlowNotFound() = withRoot { root ->
        val error = resolve("missing", root).exceptionOrNull()
        assertTrue(error is WeirFlowConfigError.FlowNotFound)
        assertEquals("missing", (error as WeirFlowConfigError.FlowNotFound).flowId)
    }

    @Test fun malformedRootConfigFailsWithoutFallingThrough() = withRoot { root ->
        File(root, "config.json").writeText("{")
        writeFlow(root, "wanted", config("wanted"))
        assertTrue(resolve("wanted", root).exceptionOrNull() is WeirFlowConfigError.ConfigDecodeFailed)
    }

    private fun resolve(flowId: String, root: File) = WeirFlowConfigResolution.resolve(
        flowId,
        root,
        null,
        BundleManager(rootDirectory = File(root, "fallback")),
    )

    private fun writeFlow(root: File, id: String, text: String) {
        File(root, "flows").mkdirs()
        File(File(root, "flows"), "$id.json").writeText(text)
    }

    private fun config(id: String, name: String = "Fixture") =
        """{"specVersion":4,"id":"$id","name":"$name","screens":[{"id":"welcome","type":"welcome","title":"Hello","cta":{"label":"Done"}}]}"""

    private fun withRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("weir-flow-resolution").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
}
