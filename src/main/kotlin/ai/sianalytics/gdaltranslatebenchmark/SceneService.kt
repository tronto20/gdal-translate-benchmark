package ai.sianalytics.gdaltranslatebenchmark

import ai.sianalytics.gdaltranslatebenchmark.data.Scene
import kotlinx.serialization.SerializationException
import kotlinx.serialization.csv.Csv
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.gdal.gdal.Dataset
import org.gdal.gdal.gdal
import org.gdal.gdalconst.gdalconst
import java.nio.file.Path
import kotlin.io.path.*

class SceneService(
    val resultDir: Path,
    val testDir: Path,
) {
    private val logger = org.slf4j.LoggerFactory.getLogger(javaClass)
    private val csvWithHeader = Csv {
        this.hasHeaderRecord = true
    }
    private val csvWithoutHeader = Csv.Default
    private val sceneResultPath = resultDir.resolve("scenes.csv")
    private fun Dataset.nbit() = GetRasterBand(1)
        .GetMetadataItem("NBITS", "IMAGE_STRUCTURE")?.ifBlank { null }?.toInt()

    private fun dataType(dataset: Dataset) = when (dataset.GetRasterBand(1).dataType) {
        gdalconst.GDT_Byte -> "Byte"
        gdalconst.GDT_Int16 -> dataset.nbit()?.let { "Int$it" } ?: "Int16"
        gdalconst.GDT_UInt16 -> dataset.nbit()?.let { "UInt$it" } ?: "UInt16"
        gdalconst.GDT_Int32 -> dataset.nbit()?.let { "Int$it" } ?: "Int32"
        gdalconst.GDT_UInt32 -> dataset.nbit()?.let { "UInt$it" } ?: "UInt32"
        gdalconst.GDT_Float32 -> "Float32"
        gdalconst.GDT_Float64 -> "Float64"
        else -> "Unknown"
    }

    private fun createTargetGTiff(dataset: Dataset, path: Path): Path {
        val targetPath = if (dataset.GetDriver().shortName != "GTiff" || dataset.GetMetadataItem("COMPRESSION")
                ?.lowercase() != "none"
        ) {
            val newPath = testDir.resolve("${path.nameWithoutExtension}_none.tiff")
            createNonCompressGTiff(dataset, newPath)?.Close()
            newPath.toFile().deleteOnExit()
            newPath
        } else {
            path
        }
        return targetPath
    }

    private fun createEmptySceneResultFile() {
        sceneResultPath.createParentDirectories().createFile()
            .writeLines(listOf(
                csvWithHeader.encodeToString(
                    listOf<Scene>(Scene("", 0, 0, 0, "", 0))
                ).lines().first())
            )
    }

    fun initializeSceneResults(): List<Scene> {
        return if (sceneResultPath.exists()) {
            try {
                csvWithHeader.decodeFromString<List<Scene>>(sceneResultPath.readText())
            } catch (e: SerializationException) {
                sceneResultPath.deleteIfExists()
                createEmptySceneResultFile()
                emptyList<Scene>()
            }

        } else {
            createEmptySceneResultFile()
            emptyList()
        }
    }

    fun normalize(name: String, scenePath: Path): Pair<Scene, Path>? {
        val dataset = gdal.Open(scenePath.toString()) ?: return null

        val targetPath = try {
            createTargetGTiff(dataset, scenePath)
        } catch (e: GdalException) {
            logger.warn("failed to create target GTiff for $name")
            return null
        }
        return try {
            val targetFileSize = targetPath.fileSize()
            Scene(
                name,
                dataset.GetRasterXSize(),
                dataset.GetRasterYSize(),
                targetFileSize,
                dataType(dataset),
                dataset.GetRasterCount()
            ) to targetPath
        } finally {
            dataset.Close()
        }
    }

    fun saveResult(scene: Scene) {
        val result = csvWithoutHeader.encodeToString(scene)
        sceneResultPath.appendLines(listOf(result))
    }

}
