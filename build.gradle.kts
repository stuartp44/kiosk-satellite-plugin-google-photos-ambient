import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

plugins { java }
repositories { mavenCentral() }

val androidHome = providers.environmentVariable("ANDROID_HOME")
    .orElse(providers.environmentVariable("ANDROID_SDK_ROOT"))
val androidJar = providers.provider {
    val root = file(androidHome.orNull ?: error("Set ANDROID_HOME or ANDROID_SDK_ROOT"))
    root.resolve("platforms").listFiles()!!.filter { it.resolve("android.jar").isFile }
        .maxByOrNull { it.name.removePrefix("android-").toInt() }!!.resolve("android.jar")
}
val sdk = sourceSets.create("sdk") { java.srcDir("sdk/src") }
sourceSets.main { compileClasspath += sdk.output }
sourceSets.test {
    compileClasspath += sdk.output
    runtimeClasspath += sdk.output
}
dependencies {
    "sdkCompileOnly"(files(androidJar))
    compileOnly(files(androidJar))
    testImplementation("org.json:json:20250517")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
    options.encoding = "UTF-8"
}
tasks.compileTestJava { options.release.set(17) }
tasks.test { useJUnitPlatform() }
tasks.jar { isPreserveFileTimestamps = false; isReproducibleFileOrder = true }

val dexDir = layout.buildDirectory.dir("dex")
val dexPlugin = tasks.register<Exec>("dexPlugin") {
    dependsOn(tasks.jar, tasks.named(sdk.classesTaskName))
    inputs.file(tasks.jar.flatMap { it.archiveFile })
    inputs.files(sdk.output)
    outputs.dir(dexDir)
    doFirst {
        val tools = file(androidHome.get()).resolve("build-tools").listFiles()!!
            .filter { it.resolve("d8").isFile }.maxByOrNull { it.name }!!
        dexDir.get().asFile.mkdirs()
        commandLine(tools.resolve("d8"), "--min-api", "24", "--lib", androidJar.get(),
            "--classpath", sdk.output.classesDirs.singleFile,
            "--output", dexDir.get().asFile, tasks.jar.get().archiveFile.get().asFile)
    }
}
val packagePlugin = tasks.register("packagePlugin") {
    dependsOn(dexPlugin, tasks.test)
    inputs.file("kiosk-satellite-plugin.json")
    inputs.file("LICENSE")
    inputs.property("pluginVersion", providers.gradleProperty("pluginVersion").orElse("0.1.0"))
    inputs.dir(dexDir)
    outputs.dir("dist")
    doLast {
        val manifest = file("kiosk-satellite-plugin.json")
        @Suppress("UNCHECKED_CAST")
        val data = JsonSlurper().parse(manifest) as MutableMap<String, Any?>
        val version = providers.gradleProperty("pluginVersion").orNull ?: data["version"].toString()
        require(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[a-zA-Z0-9.-]+)?").matches(version)) { "Invalid plugin version" }
        data["version"] = version
        val manifestBytes = (JsonOutput.prettyPrint(JsonOutput.toJson(data)) + "\n").toByteArray(Charsets.UTF_8)
        val dist = file("dist").apply { mkdirs() }
        val dex = dexDir.get().asFile.resolve("classes.dex")
        val jar = layout.buildDirectory.file("plugin.jar").get().asFile
        fun zip(target: File, entries: Map<String, ByteArray>) {
            ZipOutputStream(target.outputStream()).use { out ->
                entries.forEach { (name, bytes) ->
                    out.putNextEntry(ZipEntry(name).apply { time = 0 })
                    out.write(bytes)
                    out.closeEntry()
                }
            }
        }
        zip(jar, mapOf("classes.dex" to dex.readBytes()))
        val entries = linkedMapOf("kiosk-satellite-plugin.json" to manifestBytes,
            "plugin.jar" to jar.readBytes(), "LICENSE" to file("LICENSE").readBytes())
        val artifact = dist.resolve("${data["id"]}-${data["version"]}.zip")
        zip(artifact, entries)
        require(artifact.length() <= 4 * 1024 * 1024 && entries.values.sumOf { it.size.toLong() } <= 4 * 1024 * 1024)
        dist.resolve(manifest.name).writeBytes(manifestBytes)
        val hash = MessageDigest.getInstance("SHA-256").digest(artifact.readBytes())
            .joinToString("") { "%02x".format(it) }
        dist.resolve("${artifact.name}.sha256").writeText("$hash  ${artifact.name}\n")
    }
}
tasks.assemble { dependsOn(packagePlugin) }
