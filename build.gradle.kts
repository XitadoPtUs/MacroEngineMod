plugins {
    base
}

val windows = System.getProperty("os.name").lowercase().contains("windows")

fun wrapper(path: String): String {
    return file("$path/${if (windows) "gradlew.bat" else "gradlew"}").absolutePath
}

fun Exec.versionTask(path: String, vararg args: String) {
    workingDir = file(path)
    commandLine(wrapper(path), *args)
}

fun Exec.applyJava8IfConfigured() {
    val java8Home = providers.gradleProperty("java8Home").orElse(providers.environmentVariable("JAVA8_HOME")).orNull
    if (!java8Home.isNullOrBlank()) {
        environment("JAVA_HOME", java8Home)
    } else {
        try {
            val javaToolchains = project.extensions.getByType<JavaToolchainService>()
            val compiler = javaToolchains.compilerFor {
                languageVersion.set(JavaLanguageVersion.of(8))
            }
            environment("JAVA_HOME", compiler.get().metadata.installationPath.asFile.absolutePath)
        } catch (e: Exception) {

        }
    }
}

val versionsDir = file("versions")
if (versionsDir.exists() && versionsDir.isDirectory) {
    versionsDir.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
        val parts = dir.name.split("-")
        if (parts.size == 2) {
            val version = parts[0]
            val loader = parts[1]
            val loaderCapitalized = loader.substring(0, 1).uppercase() + loader.substring(1)
            val versionClean = version.replace(".", "")
            val suffix = "$loaderCapitalized$versionClean"
            
            val isForge = loader.lowercase() == "forge"
            
            tasks.register<Exec>("build$suffix") {
                group = "versions"
                description = "Builds the $loader mod for Minecraft $version"
                if (isForge) applyJava8IfConfigured()
                versionTask(dir.path, "build")
            }
            
            tasks.register<Exec>("clean$suffix") {
                group = "versions"
                description = "Cleans the $loader project build directory for Minecraft $version"
                if (isForge) applyJava8IfConfigured()
                versionTask(dir.path, "clean")
            }
            
            tasks.register<Exec>("run$suffix") {
                group = "versions"
                description = "Runs the $loader client for Minecraft $version"
                if (isForge) applyJava8IfConfigured()
                versionTask(dir.path, "runClient")
            }
            
            if (isForge) {
                tasks.register<Exec>("setup$suffix") {
                    group = "versions"
                    description = "Sets up the Forge decomp workspace for Minecraft $version"
                    applyJava8IfConfigured()
                    versionTask(dir.path, "setupDecompWorkspace")
                }
            } else {
                tasks.register<Exec>("test$suffix") {
                    group = "versions"
                    description = "Runs tests for the Fabric project on Minecraft $version"
                    versionTask(dir.path, "test")
                }
            }
        }
    }
}

tasks.register("cleanAll") {
    group = "versions"
    description = "Runs clean on all configured version sub-projects"
    dependsOn(tasks.matching { it.name.startsWith("clean") && it.name != "cleanAll" })
}

