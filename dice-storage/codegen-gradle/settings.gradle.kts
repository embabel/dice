// The Kotlin and KSP plugin versions come from the Maven build (see the exec-maven-plugin in
// dice-storage/pom.xml), so there is one place to change them.
pluginManagement {
    fun version(name: String): String =
        settings.providers.gradleProperty(name).orNull
            ?: throw GradleException("Pass -P$name=<version>. The Maven build passes the pom's value.")

    plugins {
        id("org.jetbrains.kotlin.jvm") version version("kotlinVersion")
        id("com.google.devtools.ksp") version version("kspVersion")
    }
}

rootProject.name = "dice-storage-codegen"
