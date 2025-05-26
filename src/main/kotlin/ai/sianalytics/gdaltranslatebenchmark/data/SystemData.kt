package ai.sianalytics.gdaltranslatebenchmark.data

import kotlinx.serialization.Serializable

@Serializable
data class SystemData(
    val runId: String,
    val platform: String,
    val arch: String,
    val maxMemory: Long,
    val processorName: String,
    val core: Int,
    val efficiencyCore: Int,
    val diskModel: String,
    val gdalVersion: String,
)