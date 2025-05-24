import org.springframework.boot.buildpack.platform.build.PullPolicy

plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.spring") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
    id("org.springframework.boot") version "3.4.5"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "dev.tronto"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    mavenCentral()
}

extra["springShellVersion"] = "3.4.0"

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("de.brudaswen.kotlinx.serialization:kotlinx-serialization-csv:2.1.0")
    implementation("org.gdal:gdal:3.10.1")
    implementation("com.github.oshi:oshi-core:6.8.0")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.shell:spring-shell-dependencies:${property("springShellVersion")}")
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

val runImageName = "docker.io/library/run-image:latest"
val buildRunImageTask = tasks.register("buildRunImage", Exec::class.java) {
    this.group = "build"
    workingDir = rootProject.projectDir.resolve("src/main/docker/runImage")
    executable = "docker"
    setArgs(
        listOf(
            "build",
            ".",
            "-t",
            runImageName,
        )
    )

    args("--build-arg")
    args("GDAL_VERSION=3.10.1")
}

val dockerImage = (property("image.name") as? String ?: "app") + ":$version"
val dockerPublish = (property("image.publish") as? String ?: "false").toBoolean()
val publishRegisterUsername = (property("image.registry.publish.username") as? String)
val publishRegisterPassword = (property("image.registry.publish.password") as? String)

tasks.bootBuildImage {
    dependsOn(buildRunImageTask)
    pullPolicy.set(PullPolicy.IF_NOT_PRESENT)
    runImage.set(runImageName)
    this.imageName.set(dockerImage)
    this.publish.set(dockerPublish)

    docker {
        if (!publishRegisterUsername.isNullOrBlank()) {
            publishRegistry {
                this.url.set(runImageName.substringBefore('/'))
                username.set(publishRegisterUsername)
                if (!publishRegisterPassword.isNullOrBlank()) {
                    password.set(publishRegisterPassword)
                }
            }
        }
    }
}

tasks.register("buildMultiArchImage", Exec::class.java) {
    dependsOn(tasks.bootJar)
    this.group = "build"
    workingDir = rootProject.projectDir.resolve("src/main/docker/application")
    doFirst {
        copy {
            from(tasks.bootJar.get().archiveFile)
            into(workingDir)
            rename { "application.jar" }
        }
    }


    executable = "docker"
    setArgs(
        listOf(
            "buildx",
            "build",
            ".",
            "--platform",
            "linux/amd64,linux/arm64/v8",
            "-t",
            dockerImage,
            "--build-arg",
            "runImage=${runImageName}"
        )
    )

    if (dockerPublish) {
        args("--push")
    }

}

tasks.register("buildImage") {
    dependsOn(tasks.bootBuildImage)
}

tasks.register("run") {
    this.group = "application"
    dependsOn(tasks.bootRun)
}
