package ai.sianalytics.gdaltranslatebenchmark

import ai.sianalytics.gdaltranslatebenchmark.data.Record
import ai.sianalytics.gdaltranslatebenchmark.data.Scene
import ai.sianalytics.gdaltranslatebenchmark.data.SystemData
import jakarta.annotation.PostConstruct
import kotlinx.serialization.csv.Csv
import kotlinx.serialization.encodeToString
import org.gdal.gdal.Dataset
import org.gdal.gdal.TranslateOptions
import org.gdal.gdal.gdal
import org.gdal.gdalconst.gdalconst
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import oshi.SystemInfo
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.*
import kotlin.io.path.*
import kotlin.time.Duration
import kotlin.time.measureTime

@Service
class BenchmarkService(
    private val properties: BenchmarkProperties,
) {

    private val logger = LoggerFactory.getLogger("benchmark")
    private val testPath = Path(properties.tmpFilePath).createDirectories()
    private val compressions = properties.compressions.map { it.uppercase() }

    private val csv = Csv {
        this.hasHeaderRecord = true
    }

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

    private fun doTest() {
        gdal.AllRegister()
        gdal.SetConfigOption("GDAL_NUM_THREADS", "ALL_CPUS")
        gdal.UseExceptions()
        (1..properties.runTimes).forEach { num ->
            runTest()
        }
    }

    private fun runTest() {
        val runId = UUID.randomUUID().toString()

        val system = getSystemData(runId)

        logger.info("run $runId start")
        val paths = findAllRasterFiles()
        logger.info("found ${paths.size} files")
        val sceneToRecords = paths.mapIndexedNotNull { idx, path ->
            val dataset = gdal.Open(path.toString()) ?: return@mapIndexedNotNull null

            val targetPath = createNoneCompressionGTiff(dataset, path)
            val scene = try {
                val targetFileSize = targetPath.fileSize()
                Scene(
                    path.nameWithoutExtension,
                    dataset.GetRasterXSize(),
                    dataset.GetRasterYSize(),
                    targetFileSize,
                    dataType(dataset),
                    dataset.GetRasterCount()
                )
            } finally {
                dataset.Close()
            }

            try {
                logger.info("sceneInfo : $scene")
                val records = compressions.mapNotNull { compression ->
                    benchmark(scene.name, targetPath, compression, runId).also {
                        logger.info("recordInfo : $it")
                    }
                }
                scene to records
            } finally {
                targetPath.deleteIfExists()
                logger.info("${path.name} processed ($runId : $idx / ${paths.size})")
            }
        }
        val scenes = sceneToRecords.map { it.first }
        val records = sceneToRecords.flatMap { it.second }
        val resultPath = Path(properties.resultPath).resolve(runId).createDirectories()

        resultPath.resolve("system.csv")
            .createFile()
            .writeText(csv.encodeToString(system), options = arrayOf(StandardOpenOption.WRITE))

        resultPath.resolve("scenes.csv")
            .createFile()
            .writeText(
                csv.encodeToString(scenes),
                options = arrayOf(StandardOpenOption.WRITE)
            )

        resultPath.resolve("records.csv")
            .createFile()
            .writeText(
                csv.encodeToString(records),
                options = arrayOf(StandardOpenOption.WRITE)
            )
        logger.info("run $runId end")
    }

    private fun findAllRasterFiles(): List<Path> {
        val paths = properties.files.flatMap {
            Path(it).walk().toList().filter { it.isRegularFile() }.filter {
                val dataset = gdal.Open(it.toString())
                if (dataset == null) {
                    false
                } else {
                    dataset.Close()
                    true
                }
            }
        }
        return paths
    }

    private val systemData by lazy {
        val systemInfo = SystemInfo()
        val platform = SystemInfo.getCurrentPlatform().name
        val totalMemory = systemInfo.hardware.memory.total
        val processor = systemInfo.hardware.processor
        val processorIdentifier = processor.processorIdentifier
        val physicalProcessors = processor.physicalProcessors
        val totalProcessorCount = physicalProcessors.size
        val efficiencyProcessorCount = physicalProcessors.sumOf { it.efficiency }

        val disk = systemInfo.hardware.diskStores.find {
            it.partitions.any { partition ->
                val mountPath = Path(partition.mountPoint)
                testPath.absolute().startsWith(mountPath)
            }
        }
        val gdalVersion = gdal.VersionInfo("RELEASE_NAME")
        val arch = System.getProperty("os.arch").lowercase()
            .replace("x86_64", "amd64")
            .replace("aarch64", "arm64")
        SystemData(
            "runId",
            platform,
            arch,
            totalMemory,
            "${processorIdentifier.name} by ${processorIdentifier.vendor}",
            totalProcessorCount,
            efficiencyProcessorCount,
            disk?.model ?: "unknown",
            gdalVersion
        )

    }

    private fun getSystemData(runId: String): SystemData {
        return systemData.copy(runId = runId)
    }

    /**
     *  1. 정보 확인
     *  2. 압축이 되어 있다면 GTIFF None 으로 압축 풀기 (압축 푼 영상은 종료시 제거)
     *  3. 압축 푼것을 바탕으로 영상 정보 쓰기
     *  4. 압축 푼것을 바탕으로 압축해보며 테스트하기
     *  5. 압축을 다시 풀어보며 테스트하기
     */
    fun benchmark(sceneName: String, targetPath: Path, compression: String, runId: String): Record? {
        logger.info("start ${sceneName} $compression")
        val dataset = gdal.Open(targetPath.toString())
        val compressionOptions = compression.split('-')
        val method = compressionOptions.first()
        val options = mutableListOf(
            "-co",
            "COMPRESS=$method",
            "-of",
            "COG",
            "-co",
            "BIGTIFF=YES"
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
        val destPath = testPath.resolve("${targetPath.nameWithoutExtension}_$method.tiff")
        val returnPath = testPath.resolve("${targetPath.nameWithoutExtension}_decompress.tiff")
        try {
            val encodingTime = measureCompressTime(destPath, dataset, options)
            val decompressingTime = measureDecompressTime(destPath, returnPath)

            val compressedFileSize = destPath.fileSize()
            logger.info("finished ${sceneName} $compression")
            return Record(
                "${sceneName}-${compression}-${runId}",
                compression,
                sceneName,
                false,
                runId,
                compressedFileSize,
                decompressingTime.inWholeMilliseconds,
                encodingTime.inWholeMilliseconds,
                null
            )

        } catch (e: GdalException) {
            val lastErrorMessage: String? = gdal.GetLastErrorMsg()
            logger.warn("error ${sceneName} $compression")
            return Record(
                "${sceneName}-${compression}-${runId}",
                compression,
                sceneName,
                true,
                runId,
                null,
                null,
                null,
                lastErrorMessage
            )

        } finally {
            dataset.Close()
            destPath.deleteIfExists()
            returnPath.deleteIfExists()
        }
    }

    private fun measureCompressTime(
        destPath: Path,
        dataset: Dataset?,
        options: MutableList<String>,
    ): Duration {
        val encodingTime = measureTime {
            try {
                gdal.Translate(
                    destPath.toString(),
                    dataset,
                    TranslateOptions(Vector(options))
                )?.Close()
            } catch (e: RuntimeException) {
                throw GdalException(e.message ?: "", e)
            }
        }
        if (destPath.notExists()) {
            throw GdalException("translation failed", null)
        }
        return encodingTime
    }

    private fun measureDecompressTime(destPath: Path, returnPath: Path): Duration {
        val destDs = gdal.Open(destPath.toString())
        val decompressingTime = measureTime {
            gdal.Translate(
                returnPath.toString(),
                destDs,
                TranslateOptions(Vector(listOf("-q")))
            )?.Close()
        }
        destDs.Close()
        return decompressingTime
    }

    private fun createNoneCompressionGTiff(dataset: Dataset, path: Path): Path {
        val targetPath = if (dataset.GetDriver().shortName != "GTiff" || dataset.GetMetadataItem("COMPRESSION")
                ?.lowercase() != "none"
        ) {
            val newPath = testPath.resolve("${path.nameWithoutExtension}_none.tiff")
            gdal.Translate(
                newPath.toString(),
                dataset,
                TranslateOptions(Vector(listOf("-co", "COMPRESS=NONE", "-of", "GTiff", "-co", "BIGTIFF=YES")))
            )?.Close()
            newPath.toFile().deleteOnExit()
            newPath
        } else {
            path
        }
        return targetPath
    }

    @PostConstruct
    fun runBenchmark() {
        val thread = object : Thread("Benchmark") {
            override fun run() {
                doTest()
            }
        }
        thread.contextClassLoader = javaClass.classLoader
        thread.isDaemon = false
        thread.start()
    }
}