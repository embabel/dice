// Generates Drivine's query DSL for the FalkorDB graph model with KSP.
//
// The processor needs Kotlin 2.2 and KSP2, so it runs in this small Gradle build and Maven compiles
// what it writes. It reads only the model package, which depends on nothing but Drivine and the
// standard library. The daemon JVM is pinned in gradle/gradle-daemon-jvm.properties for the same
// reason as dice-storage's codegen build: Gradle 8.12 cannot run on JDK 25.
//
// Run by hand with: ./gradlew kspKotlin

plugins {
    kotlin("jvm") version "2.2.0"
    id("com.google.devtools.ksp") version "2.2.20-2.0.4"
}

group = "com.embabel.dice.storage.falkordb"
version = "0.1.0-SNAPSHOT"

// The pom passes its Drivine version in, so the DSL is generated against the Drivine it is compiled
// against. The fallback only matters for a standalone run.
val drivineVersion = (findProperty("drivineVersion") as String?) ?: "0.1.8"

repositories {
    mavenCentral()
    mavenLocal()
}

dependencies {
    implementation("org.drivine:drivine4j:$drivineVersion")
    ksp("org.drivine:drivine4j-codegen:$drivineVersion")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xcontext-parameters")
    }

    sourceSets {
        main {
            kotlin.srcDirs(
                "../src/main/kotlin/com/embabel/dice/storage/falkordb/model",
                "build/generated/ksp/main/kotlin",
            )
        }
    }
}
