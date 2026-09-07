package studio.aldric.weir.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class EmphasisTextTest {
    @Test fun plainText() = assertEquals(listOf(EmphasisSegment("plain", false)), parseEmphasisSegments("plain"))

    @Test fun oneSpan() = assertEquals(
        listOf(EmphasisSegment("before ", false), EmphasisSegment("bright", true), EmphasisSegment(" after", false)),
        parseEmphasisSegments("before *bright* after"),
    )

    @Test fun multipleSpans() = assertEquals(
        listOf(EmphasisSegment("one", true), EmphasisSegment(" + ", false), EmphasisSegment("two", true)),
        parseEmphasisSegments("*one* + *two*"),
    )

    @Test fun unterminatedStarIsLiteral() = assertEquals(
        listOf(EmphasisSegment("before ", false), EmphasisSegment("*after", false)),
        parseEmphasisSegments("before *after"),
    )

    @Test fun emptySpanIsSkipped() = assertEquals(
        listOf(EmphasisSegment("remaining", false)),
        parseEmphasisSegments("**remaining"),
    )
}
