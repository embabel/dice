// Gradle build for Drivine KSP code generation.
//
// Why a separate Gradle build: Drivine's KSP processor (drivine4j-codegen) targets the
// Kotlin 2.2 / KSP2 toolchain, while dice-storage is compiled by Maven with Kotlin 2.1.10
// (pinned via the tuProlog constraint). This build runs the processor under Kotlin 2.2 and
// emits plain Kotlin DSL sources that the Maven build then compiles with 2.1.10.
//
// Scope is deliberately narrow: it reads ONLY the annotated graph model package
// (com/embabel/dice/storage/model), which depends on nothing but Drivine annotations and the
// Kotlin/JDK stdlib. The fromDice/toDice mappers and the repository live elsewhere and are
// compiled by Maven, so this build needs no dice-core / embabel-agent dependencies.
//
// Run manually with: ./gradlew kspKotlin

plugins {
    kotlin("jvm") version "2.2.0"
    id("com.google.devtools.ksp") version "2.2.20-2.0.4"
}

group = "com.embabel.dice.storage"
version = "0.1.0-SNAPSHOT"

val drivineVersion = "0.0.57"

repositories {
    mavenCentral()
    mavenLocal()
    maven { url = uri("https://repo.embabel.com/artifactory/libs-snapshot") }
    maven { url = uri("https://repo.embabel.com/artifactory/libs-release") }
}

dependencies {
    // Drivine annotations the model classes are annotated with
    implementation("org.drivine:drivine4j:$drivineVersion")
    // KSP processor that generates the where{} / query DSL
    ksp("org.drivine:drivine4j-codegen:$drivineVersion")
}

kotlin {
    compilerOptions {
        // Drivine's generated DSL uses context parameters
        freeCompilerArgs.addAll("-Xcontext-parameters")
    }

    sourceSets {
        main {
            // Read only the pure graph-model package from the Maven module, plus our own output.
            kotlin.srcDirs(
                "../src/main/kotlin/com/embabel/dice/storage/model",
                "build/generated/ksp/main/kotlin",
            )
        }
    }
}