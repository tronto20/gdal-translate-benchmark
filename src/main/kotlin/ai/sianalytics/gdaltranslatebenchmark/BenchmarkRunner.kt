package ai.sianalytics.gdaltranslatebenchmark

import ai.sianalytics.gdaltranslatebenchmark.data.SystemData
import kotlinx.serialization.csv.Csv
import kotlinx.serialization.encodeToString
import org.gdal.gdal.gdal
import org.slf4j.LoggerFactory
import oshi.SystemInfo
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.Path
import kotlin.io.path.absolute
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.writeText

class BenchmarkRunner(
    val tmpFilePath: String,
    val resultPath: String,
    val runId: String,
    val compressions: List<String>,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val testDir = Path(tmpFilePath).resolve(runId).createDirectories()
    private val resultDir = Path(resultPath).resolve(runId).createDirectories()
    private val systemPath = resultDir.resolve("system.csv")

    private val csvWithHeader = Csv {
        this.hasHeaderRecord = true
    }

    private fun createSystemData(): SystemData {
        val systemInfo = SystemInfo()
        val platform = SystemInfo.getCurrentPlatform().name
        val totalMemory = systemInfo.hardware.memory.total
        val processor = systemInfo.hardware.processor
        val processorIdentifier = processor.processorIdentifier
        val physicalProcessors = processor.physicalProcessors
        val totalProcessorCount = physicalProcessors.size
        val efficiencyProcessorCount = physicalProcessors.count { it.efficiency > 0 }

        val disk = systemInfo.hardware.diskStores.find {
            it.partitions.any { partition ->
                val mountPath = Path(partition.mountPoint)
                testDir.absolute().startsWith(mountPath)
            }
        }
        val gdalVersion = gdal.VersionInfo("RELEASE_NAME")
        val arch = System.getProperty("os.arch").lowercase()
            .replace("x86_64", "amd64")
            .replace("aarch64", "arm64")
        return SystemData(
            runId,
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

    private fun writeSystemData() {
        if (systemPath.exists()) return
        systemPath.createFile()
            .writeText(
                csvWithHeader.encodeToString(createSystemData()),
                options = arrayOf(StandardOpenOption.WRITE)
            )
    }

    private val sceneService = SceneService(resultDir, testDir)
    private val recordService = RecordService(resultDir, testDir, runId)

    /**
     * 1. 정보 확인
     * 2. 영상 정보 파악
     * 3. 해당 영상이 이미 처리되었는지 확인
     * 4. 이미 처리되었다면 패스
     * 5. 처리되지 않은 영상은 벤치마크 시작
     * 6. 벤치마크 이후 결과 저장
     */
    fun runTest(files: List<Path>) {
        writeSystemData()
        logger.info("Starting processing (runId: {})", runId)
        val alreadySaved = sceneService.initializeSceneResults().map { it.name }.toSet()
        logger.info("Found {} already processed files", alreadySaved.size)
        recordService.initRecordResults()
        logger.info("Initialized record results")

        files.filterNot { it.nameWithoutExtension in alreadySaved }
            .forEachIndexed { idx, it ->
                val (scene, targetPath) = sceneService.normalize(it.nameWithoutExtension, it)
                    ?: run {
                        logger.info("Processed {}/{} files (skip)", idx + 1, files.size)
                        return@forEachIndexed
                    }


                val records = compressions.mapIndexedNotNull { idx, compression ->
                    val record = recordService.benchmark(scene, targetPath, compression)
                    val state = if (record == null) {
                        "skip"
                    } else if (record.failed) {
                        "fail"
                    } else {
                        "success"
                    }
                    val log =
                        "Processed ${idx + 1}/${compressions.size} benchmarks for ${scene.name} (${state} ${compression})"
                    logger.info(log)
                    record
                }

                records.forEach { record ->
                    recordService.saveResult(record)
                }

                sceneService.saveResult(scene)
                logger.info("Processed {}/{} files (success)", idx + 1, files.size)
            }
        logger.info("Finished processing (runId: {})", runId)
    }
}