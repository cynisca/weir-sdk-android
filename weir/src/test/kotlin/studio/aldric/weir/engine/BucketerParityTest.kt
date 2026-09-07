package studio.aldric.weir.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BucketerParityTest {
    @Serializable private data class FixtureVariant(val id: String, val weight: Double)
    @Serializable private data class FixtureRow(
        val userId: String, val experimentId: String, val holdout: Double,
        val variants: List<FixtureVariant>, val variant: String,
    )

    private fun repoRoot(): File {
        val moduleDir = File(System.getProperty("user.dir")!!)
        return moduleDir.parentFile!!.parentFile!!
    }

    @Test fun allTenThousandReferenceAssignmentsMatch() {
        val file = File(repoRoot(), "sdk-ios/Tests/WeirCoreTests/Fixtures/bucketer-parity.json")
        assertTrue("bucketer fixture missing at ${file.path}", file.isFile)
        val rows = Json.decodeFromString<List<FixtureRow>>(file.readText())
        assertEquals("committed parity row count", 10_000, rows.size)
        rows.forEachIndexed { index, row ->
            val experiment = Bucketer.Experiment(
                row.experimentId,
                row.variants.map { Bucketer.Variant(it.id, it.weight) },
                row.holdout,
            )
            assertEquals("row $index (${row.userId}/${row.experimentId})", row.variant, Bucketer.assignVariant(experiment, row.userId).variant)
        }
    }
}
