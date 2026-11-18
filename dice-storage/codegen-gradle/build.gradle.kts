// Gradle build for Drivine KSP code generation.
//
// Why a separate Gradle build: Drivine's KSP processor (drivine4j-codegen) runs on the KSP2
// toolchain, which Maven cannot drive. This build runs the processor and emits plain Kotlin DSL
// sources that the Maven build then compiles. The Maven build passes its Kotlin, KSP and Drivine
// versions in as Gradle properties, so the DSL is generated with the same versions it is compiled
// with. settings.gradle.kts reads the first two.
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
// 17-24 to be installed/discoverable. Regenerate it with ./gradlew updateDaemonJvm --jvm-version=21,
// passing the same -P version properties as the kspKotlin command below.
//
// Run on its own with:
//   ./gradlew kspKotlin -PkotlinVersion=<kotlin.version> -PkspVersion=<ksp.version> -PdrivineVersion=<drivine.version>
// using the values in dice-storage/pom.xml and the root pom.xml.

plugins {
    kotlin("jvm")
    id("com.google.devtools.ksp")
}

group = "com.embabel.dice.storage"
version = "0.1.1-SNAPSHOT"

// The Drivine version the DSL is generated against, passed in by the Maven build.
val drivineVersion: String = findProperty("drivineVersion") as String?
    ?: throw GradleException("Pass -PdrivineVersion=<version>. The Maven build passes the pom's drivine.version.")

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