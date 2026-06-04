package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.types.*
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ios.mirror.MirrorIOSDevice
import ios.mirror.MirroirMcpClient
import maestro.cli.session.MaestroSessionManager
import kotlinx.serialization.json.*
import maestro.device.DeviceService
import java.io.File
import java.util.concurrent.TimeUnit

object ListDevicesTool {
    private val mapper = jacksonObjectMapper()

    fun create(): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "list_devices",
                description = "List all available devices that can be launched for automation.",
                inputSchema = ToolSchema(
                    properties = buildJsonObject { },
                    required = emptyList()
                )
            )
        ) { _ ->
            try {
                val availableDevices = DeviceService.listAvailableForLaunchDevices(includeWeb = true)
                val connectedDevices = DeviceService.listConnectedDevices()
                
                val allDevices = buildJsonArray {
                    val connectedIds = connectedDevices.mapTo(mutableSetOf()) { it.instanceId }

                    // Add connected devices
                    connectedDevices.forEach { device ->
                        addJsonObject {
                            put("device_id", device.instanceId)
                            put("name", device.description)
                            put("platform", device.platform.name.lowercase())
                            put("type", device.deviceType.name.lowercase())
                            put("connected", true)
                        }
                    }
                    
                    // Add available devices that aren't already connected
                    availableDevices.forEach { device ->
                        val alreadyConnected = connectedDevices.any { it.instanceId == device.modelId }
                        if (!alreadyConnected) {
                            addJsonObject {
                                put("device_id", device.modelId)
                                put("name", device.description)
                                put("platform", device.platform.name.lowercase())
                                put("type", device.deviceType.name.lowercase())
                                put("connected", false)
                            }
                        }
                    }

                    mirrorDevices().forEach { target ->
                        if (connectedIds.add(target.id)) {
                            addJsonObject {
                                put("device_id", target.id)
                                put("name", target.description)
                                put("platform", "ios")
                                put("type", "real")
                                put("connected", true)
                            }
                        }
                    }
                }
                
                val result = buildJsonObject {
                    put("devices", allDevices)
                }
                
                CallToolResult(content = listOf(TextContent(result.toString())))
            } catch (e: Exception) {
                CallToolResult(
                    content = listOf(TextContent("Failed to list devices: ${e.message}")),
                    isError = true
                )
            }
        }
    }

    private fun mirrorDevices(): List<MirrorTarget> {
        if (!MaestroSessionManager.isIOSMirrorBackendEnabled()) return emptyList()

        val targets = runCatching {
            MirroirMcpClient().use { client ->
                parseMirrorTargets(client.callTool("list_targets").text)
            }
        }.getOrDefault(emptyList())

        if (targets.isEmpty()) return emptyList()

        val targetIds = targets.joinToString { it.id }
        return pairedCoreDevices()
            .map {
                MirrorTarget(
                    id = it.id,
                    description = "${it.name} (${it.model}, iPhone Mirroring target: $targetIds)",
                )
            }
            .ifEmpty { targets }
    }

    private fun parseMirrorTargets(text: String): List<MirrorTarget> {
        return text.lineSequence()
            .mapNotNull { line ->
                val match = MIRROR_TARGET_PATTERN.matchEntire(line.trim()) ?: return@mapNotNull null
                val id = match.groupValues[1]
                val suffix = match.groupValues[2].trim()
                MirrorTarget(
                    id = id,
                    description = "iPhone Mirroring target $id${if (suffix.isNotBlank()) " - $suffix" else ""}",
                )
            }
            .toList()
    }

    private data class MirrorTarget(
        val id: String,
        val description: String,
    )

    private fun pairedCoreDevices(): List<CoreDevice> {
        val jsonOutput = File.createTempFile("maestro-mirror-devicectl-devices", ".json")
        return try {
            val process = ProcessBuilder(
                "xcrun",
                "devicectl",
                "list",
                "devices",
                "--json-output",
                jsonOutput.absolutePath,
            )
                .redirectOutput(File(if (System.getProperty("os.name").startsWith("Windows")) "NUL" else "/dev/null"))
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .start()
            if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
                return emptyList()
            }

            val result = mapper.readTree(jsonOutput)["result"] ?: return emptyList()
            val selectedDeviceId = System.getenv(MirrorIOSDevice.DEVICE_ID_ENV)?.takeIf { it.isNotBlank() }
            result["devices"]
                ?.mapNotNull { device ->
                    val id = device["identifier"]?.asText()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val name = device["deviceProperties"]?.get("name")?.asText()?.takeIf { it.isNotBlank() } ?: id
                    val model = device["hardwareProperties"]?.get("productType")?.asText()?.takeIf { it.isNotBlank() }
                        ?: "iOS"
                    val pairingState = device["connectionProperties"]?.get("pairingState")?.asText()
                    if (pairingState != "paired" || !model.startsWith("iP")) return@mapNotNull null
                    if (selectedDeviceId != null && selectedDeviceId != id && selectedDeviceId != name) {
                        return@mapNotNull null
                    }
                    CoreDevice(id = id, name = name, model = model)
                }
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        } finally {
            jsonOutput.delete()
        }
    }

    private data class CoreDevice(
        val id: String,
        val name: String,
        val model: String,
    )

    private val MIRROR_TARGET_PATTERN = Regex("""-\s+([^\s(]+)(?:\s+\(active\))?:\s*(.*)""")
}
