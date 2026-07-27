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
// Daemon JVM: Gradle 8.12.1 cannot run on JDK 25 (its embedded Kotlin script compiler throws
// `IllegalArgumentException: 25` when compiling this build.gradle.kts). Maven's exec plugin launches
// gradlew with whatever JAVA_HOME the outer build uses, which locally may be JDK 25. gradle/
// gradle-daemon-jvm.properties pins the daemon to an auto-detected JDK 21 (also CI's JDK) so the
// nested build always runs on a supported JVM regardless of the outer JAVA_HOME. Requires a JDK
// 17-24 to be installed/discoverable; regenerate with: ./gradlew updateDaemonJvm --jvm-version=21
//
// Run manually with: ./gradlew kspKotlin

plugins {
    kotlin("jvm") version "2.2.0"
    id("com.google.devtools.ksp") version "2.2.20-2.0.4"
}

group = "com.embabel.dice.storage"
version = "0.1.1-SNAPSHOT"

// Comes from the Maven build via -PdrivineVersion (see the exec-maven-plugin in dice-storage/pom.xml),
// so the pom is the single source of truth and the KSP DSL is generated against the *same* Drivine it
// is later compiled against. This was previously hardcoded and had already drifted from the pom
// (0.0.57 here vs 0.0.58 there). The fallback keeps a standalone `./gradlew kspKotlin` working.
val drivineVersion = (findProperty("drivineVersion") as String?) ?: "0.0.73"

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