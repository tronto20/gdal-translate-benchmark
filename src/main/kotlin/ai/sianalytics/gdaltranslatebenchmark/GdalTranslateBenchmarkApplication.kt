package ai.sianalytics.gdaltranslatebenchmark

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class GdalTranslateBenchmarkApplication

fun main(args: Array<String>) {
    runApplication<GdalTranslateBenchmarkApplication>(*args)
}
