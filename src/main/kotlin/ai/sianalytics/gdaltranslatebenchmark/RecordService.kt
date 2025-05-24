package ai.sianalytics.gdaltranslatebenchmark

import ai.sianalytics.gdaltranslatebenchmark.data.Record
import ai.sianalytics.gdaltranslatebenchmark.data.Scene
import kotlinx.serialization.csv.Csv
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.gdal.gdal.Dataset
import org.gdal.gdal.TranslateOptions
import org.gdal.gdal.gdal
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.Vector
import kotlin.io.path.appendLines
import kotlin.io.path.createFile
import kotlin.io.path.createParentDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.notExists
import kotlin.io.path.readText
import kotlin.io.path.writeLines
import kotlin.time.Duration
import kotlin.time.measureTime

class RecordService(
    private val resultDir: Path,
    private val testDir: Path,
    private val runId: String,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val recordResultPath = resultDir.resolve("records.csv")

    private val csvWithHeader = Csv {
        this.hasHeaderRecord = true
    }
    private val csvWithoutHeader = Csv.Default

    private fun createEmptyResultFile() {
        recordResultPath.createParentDirectories().createFile()
            .writeLines(listOf(
                csvWithHeader.encodeToString(
                    listOf<Record>(Record("", "", "", false, "", null, null, null, null))
                ).lines().first())
            )
    }

    fun initRecordResults() {
        if (recordResultPath.exists()) {
            try {
                csvWithHeader.decodeFromString(recordResultPath.readText())
            } catch (e: IllegalStateException) {
                recordResultPath.deleteIfExists()
                createEmptyResultFile()
            }
        } else {
            createEmptyResultFile()
        }
    }

    fun benchmark(scene: Scene, targetPath: Path, compression: String): Record? {
        val compressionOptions = addCompressionOptions(compression)
        val options = listOf(
            "-of",
            "COG",
            "-co",
            "BIGTIFF=YES"
        ) + compressionOptions

        val dataset = gdal.Open(targetPath.toString()) ?: return null

        val compressPath = testDir.resolve("${targetPath.nameWithoutExtension}_$compression.tiff")
        val decompressPath = testDir.resolve("${targetPath.nameWithoutExtension}_${compression}_decompress.tiff")
        try {
            val encodingTime = measureCompressTime(compressPath, dataset, options)
            val decompressingTime = measureDecompressTime(compressPath, decompressPath)

            val compressedFileSize = compressPath.fileSize()
            logger.info("finished ${scene.name} $compression")
            return Record(
                "${scene.name}-${compression}-${runId}",
                compression,
                scene.name,
                false,
                runId,
                compressedFileSize,
                decompressingTime.inWholeMilliseconds,
                encodingTime.inWholeMilliseconds,
                null
            )

        } catch (e: GdalException) {
            val lastErrorMessage: String? = gdal.GetLastErrorMsg()
            logger.warn("failed ${scene.name} $compression")
            return Record(
                "${scene.name}-${compression}-${runId}",
                compression,
                scene.name,
                true,
                runId,
                null,
                null,
                null,
                lastErrorMessage
            )
        } finally {
            dataset.Close()
            compressPath.deleteIfExists()
            decompressPath.deleteIfExists()
        }
    }

    fun saveResult(record: Record) {
        val result = csvWithoutHeader.encodeToString(record)
        recordResultPath.appendLines(listOf(result))
    }


    private fun addCompressionOptions(compression: String): List<String> {
        val compressionOptions = compression.split('-')
        val method = compressionOptions.first()
        val options: MutableList<String> = mutableListOf(
            "-co",
            "COMPRESS=$method",
        )
        compressionOptions.drop(1).forEach {
            if (it.startsWith("P")) {
                options.add("-co")
                options.add("PREDICTOR=${it.drop(1)}")
            }
            if (it.startsWith("L")) {
                val level = it.drop(1)
                options.add("-co")
                if (method == "JXL") {
                    options.add("JXL_EFFORT=$level")
                } else {
                    options.add("LEVEL=$level")
                }
            }
        }
        return options
    }


    private fun measureCompressTime(
        destPath: Path,
        dataset: Dataset,
        options: List<String>,
    ): Duration {
        val encodingTime = measureTime {
            translate(dataset, destPath, options)?.Close()
        }
        return encodingTime
    }

    private fun measureDecompressTime(destPath: Path, returnPath: Path): Duration {
        val compressedDataset = gdal.Open(destPath.toString())
        val decompressingTime = try {
            measureTime {
                createNonCompressGTiff(compressedDataset, resultPath = returnPath)?.Close()
            }
        } finally {
            compressedDataset.Close()
        }
        return decompressingTime
    }
}