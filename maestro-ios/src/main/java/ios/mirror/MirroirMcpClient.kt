package ios.mirror

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class MirroirMcpClient(
    private val command: String = defaultCommand(),
    private val timeoutMs: Long = defaultTimeoutMs(),
) : AutoCloseable {

    private val mapper: ObjectMapper = jacksonObjectMapper()
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableFuture<JsonNode>>()

    private var process: Process? = null
    private var writer: BufferedWriter? = null

    @Synchronized
    fun callTool(name: String, arguments: Map<String, Any?> = emptyMap()): MirroirToolResult {
        ensureStarted()
        val params = mapper.createObjectNode()
            .put("name", name)
            .set<ObjectNode>("arguments", mapper.valueToTree(arguments))
        val response = sendRequest("tools/call", params)
        val result = response["result"] ?: error("mirroir-mcp returned no result for tool '$name': $response")
        val toolResult = MirroirToolResult.fromJson(result)
        if (toolResult.isError) {
            throw IllegalStateException(toolResult.text.ifBlank { "mirroir-mcp tool '$name' failed" })
        }
        return toolResult
    }

    @Synchronized
    private fun ensureStarted() {
        if (process?.isAlive == true && writer != null) return

        val startedProcess = ProcessBuilder("/bin/zsh", "-lc", command)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()
        process = startedProcess
        writer = BufferedWriter(OutputStreamWriter(startedProcess.outputStream, StandardCharsets.UTF_8))

        thread(name = "mirroir-mcp-stdout", isDaemon = true) {
            startedProcess.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                lines.forEach(::handleOutputLine)
            }
        }
        thread(name = "mirroir-mcp-stderr", isDaemon = true) {
            startedProcess.errorStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                lines.forEach { logger.debug("mirroir-mcp stderr: {}", it) }
            }
        }

        val initParams = mapper.createObjectNode()
            .put("protocolVersion", "2024-11-05")
            .set<ObjectNode>(
                "capabilities",
                mapper.createObjectNode()
            )
            .set<ObjectNode>(
                "clientInfo",
                mapper.createObjectNode()
                    .put("name", "maestro")
                    .put("version", "mirror-ios")
            )

        sendRequest("initialize", initParams)
        sendNotification("notifications/initialized", mapper.createObjectNode())
    }

    private fun handleOutputLine(line: String) {
        val trimmed = line.trim()
        if (!trimmed.startsWith("{")) {
            logger.debug("mirroir-mcp: {}", line)
            return
        }

        val node = runCatching { mapper.readTree(trimmed) }.getOrNull() ?: run {
            logger.debug("Ignoring non-JSON mirroir-mcp output: {}", line)
            return
        }
        val id = node["id"]?.takeIf { it.isInt }?.asInt() ?: return
        pending.remove(id)?.complete(node)
    }

    private fun sendRequest(method: String, params: JsonNode): JsonNode {
        val id = nextId.getAndIncrement()
        val request = mapper.createObjectNode()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
            .set<ObjectNode>("params", params)
        val future = CompletableFuture<JsonNode>()
        pending[id] = future
        writeJsonLine(request)
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            pending.remove(id)
            throw IllegalStateException(
                "Timed out waiting for mirroir-mcp method '$method'. Command: $command",
                e
            )
        } catch (e: ExecutionException) {
            pending.remove(id)
            throw IllegalStateException("mirroir-mcp method '$method' failed", e)
        }.also { response ->
            response["error"]?.let {
                throw IllegalStateException("mirroir-mcp request '$method' failed: $it")
            }
        }
    }

    private fun sendNotification(method: String, params: JsonNode) {
        val request = mapper.createObjectNode()
            .put("jsonrpc", "2.0")
            .put("method", method)
            .set<ObjectNode>("params", params)
        writeJsonLine(request)
    }

    private fun writeJsonLine(node: JsonNode) {
        val activeWriter = writer ?: error("mirroir-mcp process is not started")
        activeWriter.write(mapper.writeValueAsString(node))
        activeWriter.newLine()
        activeWriter.flush()
    }

    override fun close() {
        pending.values.forEach { it.cancel(true) }
        pending.clear()
        runCatching { writer?.close() }
        process?.destroy()
        if (process?.waitFor(2, TimeUnit.SECONDS) == false) {
            process?.destroyForcibly()
        }
        writer = null
        process = null
    }

    companion object {
        const val COMMAND_ENV = "MAESTRO_IOS_MIRROR_MCP_COMMAND"
        const val TIMEOUT_ENV = "MAESTRO_IOS_MIRROR_MCP_TIMEOUT_MS"

        private val logger = LoggerFactory.getLogger(MirroirMcpClient::class.java)

        fun defaultCommand(): String {
            return System.getenv(COMMAND_ENV)
                ?.takeIf { it.isNotBlank() }
                ?: if (commandExists("mirroir-mcp")) {
                    "mirroir-mcp"
                } else {
                    "npx -y mirroir-mcp@0.33.3"
                }
        }

        private fun defaultTimeoutMs(): Long {
            return System.getenv(TIMEOUT_ENV)?.toLongOrNull() ?: 60_000L
        }

        private fun commandExists(command: String): Boolean {
            return runCatching {
                val process = ProcessBuilder("/bin/zsh", "-lc", "command -v $command >/dev/null 2>&1")
                    .start()
                process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0
            }.getOrDefault(false)
        }
    }
}

data class MirroirToolResult(
    val text: String,
    val imageBase64: String?,
    val isError: Boolean,
) {
    companion object {
        fun fromJson(result: JsonNode): MirroirToolResult {
            val content = result["content"] ?: error("mirroir-mcp result has no content: $result")
            val text = buildString {
                content.forEach { item ->
                    if (item["type"]?.asText() == "text") {
                        if (isNotEmpty()) appendLine()
                        append(item["text"]?.asText().orEmpty())
                    }
                }
            }
            val imageBase64 = content.firstOrNull { it["type"]?.asText() == "image" }
                ?.let { it["data"]?.asText() ?: it["image"]?.asText() }
            return MirroirToolResult(
                text = text,
                imageBase64 = imageBase64,
                isError = result["isError"]?.asBoolean(false) ?: false,
            )
        }
    }
}
