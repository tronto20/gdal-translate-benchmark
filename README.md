# Simple COG Translation Benchmark Tool

## Usages

You can run the benchmark tool with gradle or docker.

### with gradle (gdal JNI required)

```shell
gradle run
```

### with docker (docker required)

```shell
docker run --rm -v ${data}:/workspace/data ${result}:/workspace/results ghcr.io/tronto20/gdal-translate-benchmark:0.1.0
```

## results

The benchmark tool stores the results in the `results` directory.
- system.csv: System information ([format](src/main/kotlin/ai/sianalytics/gdaltranslatebenchmark/data/SystemData.kt))
- scenes.csv: Benchmark scene information ([format](src/main/kotlin/ai/sianalytics/gdaltranslatebenchmark/data/Scene.kt))  
- records.csv: Benchmark results ([format](src/main/kotlin/ai/sianalytics/gdaltranslatebenchmark/data/Record.kt))


## parameters

These parameters can be used in the format `--${parameterName}=${value}`
- Example: `docker run --rm ghcr.io/tronto20/gdal-translate-benchmark:0.1.0 --benchmark.compressions=deflate,lzw`

| Parameter               | Description                           | Default Value                                                                     |
|-------------------------|---------------------------------------|-----------------------------------------------------------------------------------|
| benchmark.compressions  | List of compression algorithms        | deflate, lzw, zstd, deflate-p2, deflate-p3, lzw-p2, lzw-p3, zstd-p2, zstd-p3, jxl |
| benchmark.files         | List of target files for benchmarking | data                                                                              |
| benchmark.result-path   | Path to store result files            | results                                                                           |
| benchmark.tmp-file-path | Path to store temporary files         | tmp                                                                               |
| benchmark.run-times     | Number of benchmark executions        | 1                                                                                 |

### Additional Compression Options

The `benchmark.compressions` parameter supports additional options that can be specified using hyphen (-) as a
separator:

- **P(n)**: Predictor creation option
    - Example: `deflate-p2` sets deflate with predictor=2
    - Example: `lzw-p3` sets lzw with predictor=3

- **L(n)**: Level creation option
    - Example: `deflate-L1` sets deflate with Level=1
    - Example: `lzw-L9` sets lzw with Level=9