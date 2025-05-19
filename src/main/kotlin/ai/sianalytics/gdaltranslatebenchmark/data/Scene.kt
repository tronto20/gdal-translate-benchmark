package ai.sianalytics.gdaltranslatebenchmark.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Scene(
    @SerialName("Name")
    val name: String,
    @SerialName("width (pixels)")
    val widthPixels: Int,
    @SerialName("height (pixels)")
    val heightPixels: Int,
    @SerialName("GTIFF file size (bytes)")
    val fileSize: Long,
    val dataType: String,
    val bandCount: Int
)

