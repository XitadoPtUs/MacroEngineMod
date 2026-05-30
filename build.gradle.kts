plugins {
    base
    `java-base`
}

val windows = System.getProperty("os.name").lowercase().contains("windows")

fun wrapper(path: String): String {
    val gradlewName = if (windows) "gradlew.bat" else "gradlew"
    val wrapperFile = file("$path/$gradlewName")
    if (!wrapperFile.exists()) {
        throw GradleException(
            "Gradle wrapper not found at ${wrapperFile.absolutePath}.\n" +
            "Please ensure that the subproject has a Gradle wrapper configured (e.g. running 'gradle wrapper' inside it)."
        )
    }
    if (!windows) {
        try {
            wrapperFile.setExecutable(true)
        } catch (e: Exception) {
            project.logger.warn("Failed to set executable permissions on ${wrapperFile.absolutePath}: ${e.message}")
        }
    }
    return wrapperFile.absolutePath
}

fun Exec.versionTask(path: String, vararg args: String) {
    workingDir = file(path)
    commandLine(wrapper(path), *args)
}

fun getJavaVersionForMinecraft(mcVersion: String): Int {
    val parts = mcVersion.split(".")
    if (parts.isNotEmpty()) {
        val major = parts[0].toIntOrNull() ?: 1
        val minor = if (parts.size > 1) parts[1].toIntOrNull() ?: 0 else 0
        val patch = if (parts.size > 2) parts[2].toIntOrNull() ?: 0 else 0
        
        if (major == 1) {
            if (minor >= 21 || (minor == 20 && patch >= 5)) {
                return 21
            }
            if (minor >= 17) {
                return 17
            }
        }
    }
    return 8
}

fun Exec.applyJavaVersionIfConfigured(version: Int) {
    val javaHome = providers.gradleProperty("java${version}Home").orElse(providers.environmentVariable("JAVA${version}_HOME")).orNull
    if (!javaHome.isNullOrBlank()) {
        environment("JAVA_HOME", javaHome)
    } else {
        var foundJdk = false
        try {
            val javaToolchains = project.extensions.findByType<JavaToolchainService>()
            if (javaToolchains != null) {
                val compiler = javaToolchains.compilerFor {
                    languageVersion.set(JavaLanguageVersion.of(version))
                }
                val path = compiler.get().metadata.installationPath.asFile.absolutePath
                environment("JAVA_HOME", path)
                foundJdk = true
            }
        } catch (e: Exception) {

        }
        
        if (!foundJdk) {
            val currentJava = System.getProperty("java.version")
            val matches = when (version) {
                8 -> currentJava.startsWith("1.8") || currentJava.startsWith("8.")
                17 -> currentJava.startsWith("17.")
                21 -> currentJava.startsWith("21.")
                else -> currentJava.startsWith("$version.")
            }
            if (!matches) {
                project.logger.warn("--------------------------------------------------------------------------------")
                project.logger.warn("WARNING: Building this project requires Java $version.")
                project.logger.warn("Currently running with Java $currentJava.")
                project.logger.warn("Please install Java $version or set the 'java${version}Home' property / 'JAVA${version}_HOME' env variable.")
                project.logger.warn("The build might fail if the subproject cannot compile.")
                project.logger.warn("--------------------------------------------------------------------------------")
            }
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
            val javaVersion = getJavaVersionForMinecraft(version)
            
            tasks.register<Exec>("build$suffix") {
                group = "versions"
                description = "Builds the $loader mod for Minecraft $version"
                applyJavaVersionIfConfigured(javaVersion)
                versionTask(dir.path, "build")
            }
            
            tasks.register<Exec>("clean$suffix") {
                group = "versions"
                description = "Cleans the $loader project build directory for Minecraft $version"
                applyJavaVersionIfConfigured(javaVersion)
                versionTask(dir.path, "clean")
            }
            
            tasks.register<Exec>("run$suffix") {
                group = "versions"
                description = "Runs the $loader client for Minecraft $version"
                applyJavaVersionIfConfigured(javaVersion)
                versionTask(dir.path, "runClient")
            }
            
            if (isForge) {
                tasks.register<Exec>("setup$suffix") {
                    group = "versions"
                    description = "Sets up the Forge decomp workspace for Minecraft $version"
                    applyJavaVersionIfConfigured(javaVersion)
                    versionTask(dir.path, "setupDecompWorkspace")
                }
            } else {
                tasks.register<Exec>("test$suffix") {
                    group = "versions"
                    description = "Runs tests for the Fabric project on Minecraft $version"
                    applyJavaVersionIfConfigured(javaVersion)
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

