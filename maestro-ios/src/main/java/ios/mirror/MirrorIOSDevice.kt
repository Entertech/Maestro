package ios.mirror

import com.github.michaelbull.result.Result
import com.github.michaelbull.result.runCatching as resultRunCatching
import device.IOSDevice
import device.IOSScreenRecording
import hierarchy.ViewHierarchy
import okio.Sink
import okio.buffer
import okio.source
import util.IOSLaunchArguments.toIOSLaunchArguments
import xcuitest.api.DeviceInfo
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.channels.Channels
import java.nio.file.Files
import java.util.Base64
import java.util.zip.ZipInputStream
import javax.imageio.ImageIO
import kotlin.io.path.absolutePathString
import kotlin.io.path.createTempDirectory

class MirrorIOSDevice(
    override val deviceId: String,
    private val mcpClient: MirroirMcpClient = MirroirMcpClient(),
    private val devicectl: DevicectlIOSClient = DevicectlIOSClient(devicectlDeviceId(deviceId)),
) : IOSDevice {

    private var cachedDeviceInfo: DeviceInfo? = null
    override fun open() {
        kotlin.runCatching { mcpClient.callTool("screenshot") }
        val health = mcpClient.callTool("check_health").text
        if (health.contains("[FAIL]") || health.contains("Issues detected")) {
            throw IllegalStateException(
                "Mirror iOS backend is not ready.\n$health\n" +
                    "Unlock the Mac and iPhone, open iPhone Mirroring, and resume the mirrored session."
            )
        }
    }

    override fun deviceInfo(): DeviceInfo {
        return cachedDeviceInfo ?: refreshDeviceInfo().also { cachedDeviceInfo = it }
    }

    override fun viewHierarchy(excludeKeyboardElements: Boolean): ViewHierarchy {
        val info = deviceInfo()
        val description = mcpClient.callTool("describe_screen").text
        return MirrorScreenParser.hierarchy(
            width = info.widthPoints,
            height = info.heightPoints,
            elements = MirrorScreenParser.parseElements(description),
        )
    }

    override fun tap(x: Int, y: Int) {
        mcpClient.callTool(
            "tap",
            mapOf(
                "x" to x,
                "y" to y,
                "cursor_mode" to "direct",
            )
        )
    }

    override fun longPress(x: Int, y: Int, durationMs: Long) {
        mcpClient.callTool(
            "long_press",
            mapOf("x" to x, "y" to y, "duration_ms" to durationMs)
        )
    }

    override fun scroll(xStart: Double, yStart: Double, xEnd: Double, yEnd: Double, duration: Double) {
        mcpClient.callTool(
            "swipe",
            mapOf(
                "from_x" to xStart,
                "from_y" to yStart,
                "to_x" to xEnd,
                "to_y" to yEnd,
                "duration_ms" to (duration * 1000).toLong(),
                "cursor_mode" to "direct",
            )
        )
    }

    override fun input(text: String) {
        mcpClient.callTool("type_text", mapOf("text" to text))
    }

    override fun install(stream: InputStream) {
        val extractDir = createTempDirectory("maestro-ios-mirror-install-")
        try {
            ZipInputStream(stream).use { zip ->
                generateSequence { zip.nextEntry }.forEach { entry ->
                    val output = extractDir.resolve(entry.name).normalize()
                    require(output.startsWith(extractDir)) {
                        "Invalid app archive entry outside extraction directory: ${entry.name}"
                    }
                    if (entry.isDirectory) {
                        Files.createDirectories(output)
                    } else {
                        Files.createDirectories(output.parent)
                        Files.copy(zip, output)
                    }
                }
            }
            val app = Files.walk(extractDir).use { paths ->
                paths
                    .filter { it.fileName.toString().endsWith(".app") }
                    .findFirst()
                    .orElseThrow { IllegalArgumentException("Archive did not contain a .app bundle") }
            }
            devicectl.install(app.absolutePathString())
        } finally {
            extractDir.toFile().deleteRecursively()
        }
    }

    override fun uninstall(id: String) {
        devicectl.uninstall(id)
    }

    override fun clearAppState(id: String) {
        devicectl.clearAppState(id)
    }

    override fun clearKeychain(): Result<Unit, Throwable> {
        return resultRunCatching {
            unsupported("clearKeychain is not available through the mirror iOS backend")
        }
    }

    override fun launch(id: String, launchArguments: Map<String, Any>) {
        devicectl.launch(id, launchArguments.toIOSLaunchArguments())
    }

    override fun stop(id: String) {
        devicectl.terminate(id)
    }

    override fun isKeyboardVisible(): Boolean {
        return false
    }

    override fun openLink(link: String): Result<Unit, Throwable> {
        return resultRunCatching {
            devicectl.openUrl(link)
        }
    }

    override fun takeScreenshot(out: Sink, compressed: Boolean) {
        val data = screenshotBytes()
        out.buffer().use { buffer ->
            buffer.write(data)
        }
    }

    override fun startScreenRecording(out: Sink): IOSScreenRecording {
        mcpClient.callTool("start_recording")
        return object : IOSScreenRecording {
            override fun close() {
                val stopResult = mcpClient.callTool("stop_recording").text
                val recordingPath = MirrorScreenParser.parseRecordingPath(stopResult)
                    ?: throw IllegalStateException("mirroir-mcp did not return a recording path: $stopResult")
                val file = File(recordingPath)
                Channels.newInputStream(Files.newByteChannel(file.toPath())).source().buffer().use { source ->
                    out.buffer().use { sink ->
                        sink.writeAll(source)
                    }
                }
                file.delete()
            }
        }
    }

    override fun setLocation(latitude: Double, longitude: Double): Result<Unit, Throwable> {
        return resultRunCatching {
            unsupported("setLocation is not available through the mirror iOS backend")
        }
    }

    override fun setOrientation(orientation: String) {
        devicectl.setOrientation(orientation)
    }

    override fun isShutdown(): Boolean {
        return false
    }

    override fun isScreenStatic(): Boolean {
        val first = kotlin.runCatching { screenshotBytes() }.getOrNull() ?: return false
        Thread.sleep(SCREEN_STATIC_DELAY_MS)
        val second = kotlin.runCatching { screenshotBytes() }.getOrNull() ?: return false
        return first.contentEquals(second)
    }

    override fun setPermissions(id: String, permissions: Map<String, String>) {
        // iOS permissions still surface as system prompts. The mirror backend can
        // interact with those prompts visually, but it cannot pre-grant privacy
        // permissions the way the XCTest runner does.
    }

    override fun pressKey(name: String) {
        mcpClient.callTool("press_key", mapOf("key" to name))
    }

    override fun pressButton(name: String) {
        when (name.lowercase()) {
            "home" -> mcpClient.callTool("press_home")
            else -> unsupported("Unsupported mirror iOS hardware button: $name")
        }
    }

    override fun eraseText(charactersToErase: Int) {
        repeat(charactersToErase) {
            pressKey("delete")
        }
    }

    override fun addMedia(path: String) {
        unsupported("addMedia is not available through the mirror iOS backend")
    }

    override fun close() {
        mcpClient.close()
    }

    private fun refreshDeviceInfo(): DeviceInfo {
        val targetInfo = mcpClient.callTool("list_targets").text
        val windowSize = MirrorScreenParser.parseWindowSize(targetInfo)
            ?: MirrorScreenParser.parseWindowSize(mcpClient.callTool("get_orientation").text)
            ?: throw IllegalStateException("Unable to read iPhone Mirroring window size: $targetInfo")

        val screenshot = kotlin.runCatching { screenshotBytes() }.getOrNull()
        val imageSize = screenshot?.let { pngSize(it) }
        val displayInfo = kotlin.runCatching { devicectl.displayInfo() }.getOrNull()
        return DeviceInfo(
            widthPixels = imageSize?.width ?: displayInfo?.widthPixels ?: windowSize.width * RETINA_SCALE,
            heightPixels = imageSize?.height ?: displayInfo?.heightPixels ?: windowSize.height * RETINA_SCALE,
            widthPoints = windowSize.width,
            heightPoints = windowSize.height,
        )
    }

    private fun screenshotBytes(): ByteArray {
        val result = mcpClient.callTool("screenshot")
        val imageBase64 = result.imageBase64
            ?: throw IllegalStateException("mirroir-mcp screenshot returned no image data: ${result.text}")
        return Base64.getDecoder().decode(imageBase64)
    }

    private fun pngSize(bytes: ByteArray): MirrorWindowSize {
        val image = ImageIO.read(ByteArrayInputStream(bytes))
            ?: throw IllegalStateException("mirroir-mcp screenshot was not a PNG image")
        return MirrorWindowSize(image.width, image.height)
    }

    private fun unsupported(message: String): Nothing {
        throw UnsupportedOperationException(message)
    }

    companion object {
        const val DEVICE_ID_ENV = "MAESTRO_IOS_MIRROR_DEVICE_ID"

        private const val RETINA_SCALE = 2
        private const val SCREEN_STATIC_DELAY_MS = 500L

        private fun devicectlDeviceId(deviceId: String): String {
            return System.getenv(DEVICE_ID_ENV)?.takeIf { it.isNotBlank() } ?: deviceId
        }
    }
}
