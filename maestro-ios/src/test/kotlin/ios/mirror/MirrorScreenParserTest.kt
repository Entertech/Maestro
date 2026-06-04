package ios.mirror

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class MirrorScreenParserTest {

    @Test
    fun `parses active target window size`() {
        val size = MirrorScreenParser.parseWindowSize(
            "- iphone (active): connected (318x701) [appSwitcher, home]"
        )

        assertThat(size).isEqualTo(MirrorWindowSize(width = 318, height = 701))
    }

    @Test
    fun `parses orientation window size`() {
        val size = MirrorScreenParser.parseWindowSize(
            "Orientation: portrait (window: 410x890)"
        )

        assertThat(size).isEqualTo(MirrorWindowSize(width = 410, height = 890))
    }

    @Test
    fun `parses described screen elements`() {
        val elements = MirrorScreenParser.parseElements(
            """
            Screen elements (tap coordinates in points):
            - "Email" at (45, 120)
            - "Sign In" at (207.5, 628.25)
            """.trimIndent()
        )

        assertThat(elements).containsExactly(
            MirrorScreenElement(text = "Email", x = 45f, y = 120f),
            MirrorScreenElement(text = "Sign In", x = 207.5f, y = 628.25f),
        ).inOrder()
    }

    @Test
    fun `synthesizes a hierarchy centered on tap coordinates`() {
        val hierarchy = MirrorScreenParser.hierarchy(
            width = 318,
            height = 701,
            elements = listOf(MirrorScreenElement(text = "Continue", x = 159f, y = 640f))
        )

        val child = hierarchy.axElement.children.single()
        assertThat(child.label).isEqualTo("Continue")
        assertThat(child.frame.boundsString).isEqualTo("[158,639][160,641]")
        assertThat(hierarchy.depth).isEqualTo(2)
    }
}
