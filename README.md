# Simple COG Translation Benchmark Tool

## Usage

You can run the benchmark tool either with Gradle or Docker.

### Quick Start

- **With Gradle** (requires local GDAL JNI):
```shell
  gradle run
```

- **With Docker**:
```shell
  docker run --rm \
  -v data:/workspace/data \
  -v result:/workspace/results \
  ghcr.io/tronto20/gdal-translate-benchmark:0.1.3
```

## results

The benchmark tool stores the results in the `results` directory.

- system.csv: System information ([format](src/main/kotlin/ai/sianalytics/gdaltranslatebenchmark/data/SystemData.kt))
- scenes.csv: Benchmark scene
  information ([format](src/main/kotlin/ai/sianalytics/gdaltranslatebenchmark/data/Scene.kt))
- records.csv: Benchmark results ([format](src/main/kotlin/ai/sianalytics/gdaltranslatebenchmark/data/Record.kt))

## parameters

These parameters can be used in the format `--${parameterName}=${value}`

- Example: `docker run --rm ghcr.io/tronto20/gdal-translate-benchmark:0.1.0 --benchmark.compressions=deflate,lzw`

| Parameter               | Description                                                       | Default Value           |
|-------------------------|-------------------------------------------------------------------|-------------------------|
| benchmark.run-id        | Id of benchmark executions. (set to make benchmark **resumable**) | random UUID             |
| benchmark.compressions  | List of compression algorithms.                                   | deflate, lzw, zstd, jxl |
| benchmark.result-path   | Path to store result files.                                       | ./results               |
| benchmark.tmp-file-path | Path to store temporary files.                                    | ./tmp                   |
| benchmark.files         | List of target files for benchmarking.                            | ./data                  |
| benchmark.extensions    | Extension filters for target files. (case insensitive)            | empty (all files)       |

### Additional Compression Options

The `benchmark.compressions` parameter supports additional options that can be specified using hyphen (-) as a
separator:

- **P(n)**: Predictor creation option
    - Example: `deflate-p2` sets deflate with predictor=2
    - Example: `lzw-p3` sets lzw with predictor=3

- **L(n)**: Level creation option
    - Example: `deflate-L1` sets deflate with Level=1
    - Example: `lzw-L9` sets lzw with Level=9
- Example : `deflate-p2-l1` sets deflate with predictor=2 and Level=1

## Build & Run Guide

This project supports both Gradle and Docker. Below are the main build and run commands.

### Requirements
- Java 17 or higher
- GDAL JNI library (required for local Gradle runs)
- Docker (for building/running Docker images)

### Build/Run with Gradle

You can use the following Gradle commands with `./gradlew` (Linux/Mac) or `gradlew.bat` (Windows):

| Command                      | Description                                                      |
|------------------------------|------------------------------------------------------------------|
| `gradle build`               | Build the entire project                                         |
| `gradle run`                 | Run locally (requires GDAL JNI to be installed)                  |
| `gradle buildImage`          | Build a single-architecture Docker image using the local Docker  |
| `gradle buildMultiArchImage` | Build a multi-architecture Docker image (using Docker Buildx)    |