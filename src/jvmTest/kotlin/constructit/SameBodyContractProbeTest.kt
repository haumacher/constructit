package constructit

import kotlin.test.Test
import kotlin.test.assertFails

/**
 * The orchestrator's probe of session 89's restated contract (OP-4): the helpers that forgive the default
 * binding's import noise must still see every change that is a change of the construction — a choice, a
 * reference, a count, or a micron of geometry.
 */
class SameBodyContractProbeTest {
    private val line = "tool filletedge els=e13 clicks=-31.252365457721638,11.31587462139538 scalar=\"r\" signs=13;-1;1;0;1 -> e14,e15"

    @Test
    fun aFlippedChoiceIsNotNoise() {
        assertFails { assertSameFile(line.replace("signs=13;-1", "signs=13;1"), line, "flipped sign") }
    }

    @Test
    fun aDifferentReferenceIsNotNoise() {
        assertFails { assertSameFile(line.replace("els=e13", "els=e12"), line, "other element") }
        assertFails { assertSameFile(line.replace("-> e14,e15", "-> e14"), line, "fewer elements") }
    }

    @Test
    fun aMovedClickByAMicronIsNotNoise() {
        assertFails { assertSameFile(line.replace("-31.252365457721638", "-31.253365457721638"), line, "click moved 1 µm") }
    }

    @Test
    fun aMicronOfDepthIsNotTheSameBody() {
        // The keyhole's 15000 mm³ over a ~750 mm² footprint: one micron deeper is ~0.75 mm³.
        val v = 15000.062802156805
        assertFails { assertSameBody(v + 750.0 * 0.001, v, "a micron deeper") }
        assertFails { assertSameBody(v + 1e-4, v, "a ten-thousandth of a cubic millimetre") }
        assertSameBody(15000.06280217351, v, "the binding's own two answers")
    }
}
