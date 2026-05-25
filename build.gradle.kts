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
    }
}

tasks.register<Exec>("buildFabric1214") {
    group = "versions"
    versionTask("versions/1.21.4-fabric", "build")
}

tasks.register<Exec>("testFabric1214") {
    group = "versions"
    versionTask("versions/1.21.4-fabric", "test")
}

tasks.register<Exec>("runFabric1214") {
    group = "versions"
    versionTask("versions/1.21.4-fabric", "runClient")
}

tasks.register<Exec>("buildForge189") {
    group = "versions"
    applyJava8IfConfigured()
    versionTask("versions/1.8.9-forge", "build")
}

tasks.register<Exec>("setupForge189") {
    group = "versions"
    applyJava8IfConfigured()
    versionTask("versions/1.8.9-forge", "setupDecompWorkspace")
}

tasks.register<Exec>("runForge189") {
    group = "versions"
    applyJava8IfConfigured()
    versionTask("versions/1.8.9-forge", "runClient")
}
