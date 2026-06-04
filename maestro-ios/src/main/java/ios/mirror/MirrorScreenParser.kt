package ios.mirror

import hierarchy.AXElement
import hierarchy.AXFrame
import hierarchy.ViewHierarchy
import kotlin.math.max
import kotlin.math.min

object MirrorScreenParser {

    private val windowSizePattern = Regex("""window:\s*(\d+)x(\d+)""")
    private val targetSizePattern = Regex("""\([^)]*?(\d+)x(\d+)[^)]*?\)""")
    private val elementPattern = Regex("""^-\s+"(.*)"\s+at\s+\((-?\d+(?:\.\d+)?),\s*(-?\d+(?:\.\d+)?)\)""")
    private val recordingPathPattern = Regex("""(/[^\n\r]+\.mov)""")

    fun parseWindowSize(text: String): MirrorWindowSize? {
        val match = windowSizePattern.find(text)
            ?: targetSizePattern.find(text)
            ?: return null
        return MirrorWindowSize(
            width = match.groupValues[1].toInt(),
            height = match.groupValues[2].toInt(),
        )
    }

    fun parseElements(text: String): List<MirrorScreenElement> {
        return text
            .lineSequence()
            .mapNotNull { line ->
                val match = elementPattern.find(line.trim()) ?: return@mapNotNull null
                MirrorScreenElement(
                    text = match.groupValues[1],
                    x = match.groupValues[2].toFloat(),
                    y = match.groupValues[3].toFloat(),
                )
            }
            .toList()
    }

    fun parseRecordingPath(text: String): String? {
        return recordingPathPattern.find(text)?.groupValues?.get(1)?.trim()
    }

    fun hierarchy(width: Int, height: Int, elements: List<MirrorScreenElement>): ViewHierarchy {
        val children = elements.map { element ->
            AXElement(
                label = element.text,
                elementType = 0,
                identifier = "",
                horizontalSizeClass = 0,
                windowContextID = 0,
                verticalSizeClass = 0,
                selected = false,
                displayID = 0,
                hasFocus = false,
                placeholderValue = null,
                value = element.text,
                frame = frameAround(element.x, element.y, width, height),
                enabled = true,
                title = element.text,
                children = arrayListOf(),
            )
        }

        val root = AXElement(
            label = "",
            elementType = 0,
            identifier = "",
            horizontalSizeClass = 0,
            windowContextID = 0,
            verticalSizeClass = 0,
            selected = false,
            displayID = 0,
            hasFocus = false,
            placeholderValue = null,
            value = null,
            frame = AXFrame(0f, 0f, width.toFloat(), height.toFloat()),
            enabled = true,
            title = null,
            children = ArrayList(children),
        )
        return ViewHierarchy(
            axElement = root,
            depth = if (children.isEmpty()) 1 else 2,
        )
    }

    private fun frameAround(x: Float, y: Float, width: Int, height: Int): AXFrame {
        val left = max(0f, x - ELEMENT_HALF_SIZE)
        val top = max(0f, y - ELEMENT_HALF_SIZE)
        val right = min(width.toFloat(), x + ELEMENT_HALF_SIZE)
        val bottom = min(height.toFloat(), y + ELEMENT_HALF_SIZE)
        return AXFrame(
            x = left,
            y = top,
            width = max(1f, right - left),
            height = max(1f, bottom - top),
        )
    }

    private const val ELEMENT_HALF_SIZE = 1f
}

data class MirrorWindowSize(
    val width: Int,
    val height: Int,
)

data class MirrorScreenElement(
    val text: String,
    val x: Float,
    val y: Float,
)
