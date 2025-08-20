package ai.sianalytics.gdaltranslatebenchmark

import ai.sianalytics.gdaltranslatebenchmark.data.Record
import ai.sianalytics.gdaltranslatebenchmark.data.Scene
import kotlinx.serialization.SerializationException
import kotlinx.serialization.csv.Csv
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.gdal.gdal.Dataset
import org.gdal.gdal.gdal
import org.slf4j.LoggerFactory
import oshi.SystemInfo
import oshi.hardware.CentralProcessor
import java.nio.file.Path
import kotlin.io.path.appendLines
import kotlin.io.path.createFile
import kotlin.io.path.createParentDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.nameWithoutExtension
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
            .writeLines(
                listOf(
                    csvWithHeader.encodeToString(
                        listOf<Record>(Record("", "", "", false, "", null, null, null, null, null, null, null, null))
                    ).lines().first()
                )
            )
    }

    fun initRecordResults() {
        if (recordResultPath.exists()) {
            try {
                csvWithHeader.decodeFromString<List<Record>>(recordResultPath.readText())
            } catch (e: SerializationException) {
                recordResultPath.deleteIfExists()
                createEmptyResultFile()
            }
        } else {
            createEmptyResultFile()
        }
    }

    fun benchmark(scene: Scene, targetPath: Path, compression: String): Record? {
        logger.info("start ${scene.name} $compression")
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
            val compressResult = measureCompress(compressPath, dataset, options)
            val decompressingResult = measureDecompressTime(compressPath, decompressPath)

            val compressedFileSize = compressPath.fileSize()
            logger.info("finished ${scene.name} $compression")
            return Record(
                "${scene.name}-${compression}-${runId}",
                compression,
                scene.name,
                false,
                runId,
                compressedFileSize,
                decompressingResult.value.inWholeMilliseconds,
                compressResult.value.inWholeMilliseconds,
                null,
                compressResult.cpuUsage,
                decompressingResult.cpuUsage,
                compressResult.cpuFullLoad,
                decompressingResult.cpuFullLoad
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
                lastErrorMessage,
                null,
                null,
                null,
                null
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
            if (it.startsWith("Q")) {
                options.add("-co")
                options.add("QUALITY=${it.drop(1)}")
            }
        }
        return options
    }

    data class CpuUsageWithValue<T>(
        val value: T,
        val cpuUsage: Double,
        val cpuFullLoad: Double
    )

    companion object {
        private const val CPU_COLLECT_DELAY = 500L
        private const val CPU_FULL_LOAD_THRESHOLD = 0.9
    }
    private val processor = SystemInfo().hardware.processor
    private fun <T> measureCpuUsage(block: () -> T): CpuUsageWithValue<T> {
        val cpuUsages = mutableListOf<Double>()
        var running = true
        val thread = Thread({
            var prevTotal : Long? = null
            var prevIdle: Long? = null
            while (running) {
                val loads = processor.processorCpuLoadTicks
                val total = loads.sumOf { it.sum() }
                val idle = loads.sumOf { it[CentralProcessor.TickType.IDLE.index] + it[CentralProcessor.TickType.IOWAIT.index] }
                if (prevTotal != null && prevIdle != null) {
                    val idleDiff = idle - prevIdle
                    val totalDiff = total - prevTotal
                    val load = (totalDiff - idleDiff).toDouble() / totalDiff
                    cpuUsages.add(load)
                }

                prevTotal = total
                prevIdle = idle

                Thread.sleep(CPU_COLLECT_DELAY)
            }
        })
        thread.contextClassLoader = javaClass.classLoader
        thread.isDaemon = true
        thread.start()

        val result = block()
        running = false
        thread.join(CPU_COLLECT_DELAY * 2)
        val usages = cpuUsages
        return CpuUsageWithValue(
            result,
            usages.average(),
            usages.count { it > CPU_FULL_LOAD_THRESHOLD }.toDouble() / cpuUsages.size
        )
    }

    private fun measureCompress(
        destPath: Path,
        dataset: Dataset,
        options: List<String>,
    ): CpuUsageWithValue<Duration> {
        return measureCpuUsage {
            measureTime {
                translate(dataset, destPath, options)?.Close()
            }
        }
    }

    private fun measureDecompressTime(destPath: Path, returnPath: Path): CpuUsageWithValue<Duration> {
        val compressedDataset = gdal.Open(destPath.toString())
        return try {
            measureCpuUsage {
                measureTime {
                    createNonCompressGTiff(compressedDataset, resultPath = returnPath)?.Close()
                }
            }
        } finally {
            compressedDataset.Close()
        }
    }
}