package ios.mirror

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class DevicectlIOSClient(
    private val deviceId: String,
) {
    private val mapper = jacksonObjectMapper()

    fun displayInfo(): MirrorDisplayInfo? {
        val result = runJson("device", "info", "displays", "--device", deviceId)["result"] ?: return null
        val display = result["displays"]?.firstOrNull { it["primary"]?.asBoolean(false) == true }
            ?: result["displays"]?.firstOrNull()
            ?: return null
        val bounds = display["bounds"] ?: return null
        val widthPixels = bounds[1]?.get(0)?.asInt() ?: return null
        val heightPixels = bounds[1]?.get(1)?.asInt() ?: return null
        val pointScale = display["pointScale"]?.asDouble() ?: 1.0
        return MirrorDisplayInfo(
            widthPixels = widthPixels,
            heightPixels = heightPixels,
            widthPoints = (widthPixels / pointScale).toInt(),
            heightPoints = (heightPixels / pointScale).toInt(),
        )
    }

    fun install(appPath: String) {
        runPlain("device", "install", "app", "--device", deviceId, appPath)
    }

    fun uninstall(bundleId: String) {
        runPlain("device", "uninstall", "app", "--device", deviceId, bundleId)
    }

    fun launch(bundleId: String, arguments: List<String> = emptyList()) {
        runPlain(
            listOf(
                "device",
                "process",
                "launch",
                "--terminate-existing",
                "--device",
                deviceId,
                bundleId,
            ) + arguments
        )
    }

    fun openUrl(url: String) {
        runPlain(
            "device",
            "process",
            "launch",
            "--terminate-existing",
            "--device",
            deviceId,
            "com.apple.mobilesafari",
            "--payload-url",
            url,
        )
    }

    fun terminate(bundleId: String) {
        val pids = runningPids(bundleId)
        if (pids.isEmpty()) return
        pids.forEach { pid ->
            runPlain("device", "process", "terminate", "--device", deviceId, "--pid", pid.toString())
        }
    }

    fun clearAppState(bundleId: String) {
        terminate(bundleId)
        val emptyDirectory = Files.createTempDirectory("maestro-ios-empty-app-data-")
        try {
            runPlain(
                "device",
                "copy",
                "to",
                "--device",
                deviceId,
                "--source",
                emptyDirectory.toAbsolutePath().toString(),
                "--destination",
                "/",
                "--domain-type",
                "appDataContainer",
                "--domain-identifier",
                bundleId,
                "--remove-existing-content",
                "true",
            )
        } finally {
            emptyDirectory.toFile().deleteRecursively()
        }
    }

    fun setOrientation(orientation: String) {
        runPlain("device", "orientation", "set", "--device", deviceId, orientation)
    }

    private fun runningPids(bundleId: String): List<Int> {
        val appBundleName = appBundleName(bundleId) ?: return emptyList()
        val appSegment = "/$appBundleName.app/"
        val result = runJson("device", "info", "processes", "--device", deviceId)["result"] ?: return emptyList()
        return result["runningProcesses"]
            ?.filter { process ->
                val executable = process["executable"]?.asText().orEmpty()
                executable.contains(appSegment) || executable.endsWith("/$appBundleName.app/$appBundleName")
            }
            ?.mapNotNull { it["processIdentifier"]?.asInt() }
            ?: emptyList()
    }

    private fun appBundleName(bundleId: String): String? {
        val result = runJson(
            "device",
            "info",
            "apps",
            "--device",
            deviceId,
            "--include-all-apps",
            "--bundle-id",
            bundleId,
        )["result"] ?: return null
        val appUrl = result["apps"]?.firstOrNull()?.get("url")?.asText() ?: return null
        val path = URI(appUrl).path.trimEnd('/')
        return path.substringAfterLast('/').removeSuffix(".app").ifBlank { null }
    }

    private fun runJson(vararg args: String): JsonNode {
        val jsonOutput = File.createTempFile("maestro-devicectl", ".json")
        return try {
            runPlain(listOf("--json-output", jsonOutput.absolutePath) + args)
            mapper.readTree(jsonOutput)
        } finally {
            jsonOutput.delete()
        }
    }

    private fun runPlain(vararg args: String) {
        runPlain(args.toList())
    }

    private fun runPlain(args: List<String>) {
        val process = ProcessBuilder(listOf("xcrun", "devicectl") + args)
            .redirectOutput(File(if (System.getProperty("os.name").startsWith("Windows")) "NUL" else "/dev/null"))
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()
        if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IllegalStateException("devicectl timed out: $args")
        }
        if (process.exitValue() != 0) {
            val error = process.errorStream.bufferedReader().readText()
            throw IllegalStateException("devicectl failed: $args\n$error")
        }
    }

    companion object {
        private const val PROCESS_TIMEOUT_SECONDS = 120L
    }
}

data class MirrorDisplayInfo(
    val widthPixels: Int,
    val heightPixels: Int,
    val widthPoints: Int,
    val heightPoints: Int,
)
