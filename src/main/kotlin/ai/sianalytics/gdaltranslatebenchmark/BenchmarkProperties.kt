package ai.sianalytics.gdaltranslatebenchmark

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("benchmark")
data class BenchmarkProperties(
    val runId: String? = null,
    val files: List<String>,
    val extensions: List<String> = emptyList(),
    val compressions: List<String> = listOf("lzw", "deflate", "zstd", "jxl"),
    val resultPath: String = "results",
    val tmpFilePath: String = "tmp"
)