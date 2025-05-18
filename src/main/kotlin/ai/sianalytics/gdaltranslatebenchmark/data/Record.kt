package ai.sianalytics.gdaltranslatebenchmark.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Record(
    val id: String,
    @SerialName("Compression Method")
    val compressionMethod: String,
    @SerialName("Scene Name")
    val sceneName: String,
    val failed: Boolean,
    @SerialName("RunId")
    val runId: String,
    @SerialName("Compressed File Size (bytes)")
    val compressedFileSize: Long?,
    @SerialName("Decoding Time (ms)")
    val decodingTime: Long?,
    @SerialName("Encoding Time (ms)")
    val encodingTime: Long?,
    val failMessage: String?
)

