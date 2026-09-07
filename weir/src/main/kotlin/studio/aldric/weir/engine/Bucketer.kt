package studio.aldric.weir.engine

/** Deterministic FNV-1a experiment bucketing, byte-faithful to the Swift/TS engines. */
object Bucketer {
    const val HOLDOUT = "__holdout__"

    data class Variant(val id: String, val weight: Double)
    data class Experiment(val id: String, val variants: List<Variant>, val holdout: Double)
    data class Assignment(val experiment: String, val variant: String)

    /** FNV-1a over UTF-16 code units (not UTF-8 bytes or Unicode code points). */
    fun hashUnit(key: String): Double {
        var h: Int = 0x811c9dc5.toInt()
        for (unit in key) {
            h = h xor unit.code
            h *= 0x01000193
        }
        return (h.toLong() and 0xFFFFFFFFL).toDouble() / 4294967296.0
    }

    fun assignVariant(experiment: Experiment, userId: String): Assignment {
        val u = hashUnit("$userId:${experiment.id}")
        if (experiment.holdout > 0 && u < experiment.holdout) {
            return Assignment(experiment.id, HOLDOUT)
        }

        val rescaled = if (experiment.holdout > 0) {
            (u - experiment.holdout) / (1 - experiment.holdout)
        } else {
            u
        }
        val total = experiment.variants.sumOf { it.weight }
        var accumulated = 0.0
        for (variant in experiment.variants) {
            accumulated += variant.weight / total
            if (rescaled < accumulated) return Assignment(experiment.id, variant.id)
        }
        return Assignment(experiment.id, experiment.variants.last().id)
    }

    fun assignAll(experiments: List<Experiment>, userId: String): List<Assignment> =
        experiments.map { assignVariant(it, userId) }
}
