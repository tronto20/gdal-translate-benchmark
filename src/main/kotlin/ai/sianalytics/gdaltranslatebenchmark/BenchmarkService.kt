package ai.sianalytics.gdaltranslatebenchmark

import jakarta.annotation.PostConstruct
import org.gdal.gdal.Dataset
import org.gdal.gdal.TranslateOptions
import org.gdal.gdal.gdal
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.Path
import java.util.*
import kotlin.io.path.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.notExists
import kotlin.io.path.walk
import kotlin.streams.asStream
import kotlin.time.measureTimedValue

@Service
class BenchmarkService(
    private val properties: BenchmarkProperties,
) {

    private val logger = LoggerFactory.getLogger("benchmark")
    private val compressions = properties.compressions.map { it.uppercase() }

    private fun findAllRasterFiles(): List<Path> {
        val extensionFilter = properties.extensions.map { it.uppercase() }
        val paths = properties.files.flatMap {
            Path(it).walk().asStream().parallel()
                .filter { it.isRegularFile() }
                .filter { it.extension.uppercase() in extensionFilter || extensionFilter.isEmpty() }
                .filter {
                    try {
                        val dataset = gdal.Open(it.toString())
                        if (dataset == null) {
                            false
                        } else {
                            dataset.Close()
                            true
                        }
                    } catch (e: RuntimeException) {
                        false
                    }
                }.toList()
        }
        return paths
    }

    private fun doTest() {
        gdal.AllRegister()
        gdal.SetConfigOption("GDAL_NUM_THREADS", "ALL_CPUS")
        gdal.UseExceptions()
        val runId = properties.runId ?: UUID.randomUUID().toString()
        val sceneFilesTimedValue = measureTimedValue {
            findAllRasterFiles()
        }
        val sceneFiles = sceneFilesTimedValue.value

        logger.info(
            "Running benchmark for {} files with {} compressions with runId {} (took {})",
            sceneFiles.size,
            compressions.size,
            runId,
            sceneFilesTimedValue.duration,
        )
        val benchmarkRunner = BenchmarkRunner(properties.tmpFilePath, properties.resultPath, runId, compressions)
        benchmarkRunner.runTest(sceneFiles)
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

private val translateLogger = LoggerFactory.getLogger("translate")

fun translate(dataset: Dataset, resultPath: Path, options: List<String>): Dataset? {
    val result = try {
        translateLogger.info("translating with options {}", options)
        gdal.Translate(
            resultPath.toString(),
            dataset,
            TranslateOptions(Vector(options))
        )
    } catch (e: RuntimeException) {
        throw GdalException(e.message ?: "", e)
    }
    if (resultPath.notExists()) {
        throw GdalException("translation failed", null)
    }
    return result
}

fun createNonCompressGTiff(dataset: Dataset, resultPath: Path): Dataset? {
    return translate(dataset, resultPath, listOf("-co", "COMPRESS=NONE", "-of", "GTiff", "-co", "BIGTIFF=YES"))
}