package de.seqstack.blastmobile.model

enum class QcStatus { PASS, WARN, FAIL }

data class QcTile(val label: String, val value: String, val status: QcStatus)

data class FastqQcSummary(
    val inspectedPairs: Int,
    val pairIdMismatches: Int,
    val meanLengthR1: Double,
    val meanLengthR2: Double,
    val meanQualityR1: Double,
    val meanQualityR2: Double,
    val q30R1: Double,
    val q30R2: Double,
    val nFraction: Double,
    val gcFraction: Double,
    val adapterFractionR1: Double,
    val adapterFractionR2: Double,
    val tiles: List<QcTile>,
)

data class FastqPreparationResult(
    val r1Name: String,
    val r2Name: String,
    val inspectedPairs: Int,
    val selectedPairs: Int,
    val fastaBytes: Long,
    val fastaPath: String,
    val manifestPath: String,
    val qc: FastqQcSummary,
)
