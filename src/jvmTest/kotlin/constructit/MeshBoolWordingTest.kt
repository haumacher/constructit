package constructit

import constructit.geom.Mesh3
import constructit.geom.MeshCanon
import constructit.geom.Tri
import constructit.geom.Vec3
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **A refusal is a sentence, so nothing in it may be a Kotlin `toString`.**
 *
 * The two faults [MeshCanon] names at the general boolean's seam — a zero-thickness flap and a shell that
 * is not closed — both quote *where* the offending edge is, and both used to hand the reader
 * `Vec3(x=12.5, y=0.0, z=3.25)`: a debug print, in the status line and in the side panel, in every language.
 * They now read as positions in millimetres, through the same formatting every other refusal in this kernel
 * uses ([constructit.geom.Frames3.mm]).
 */
class MeshBoolWordingTest {
    /**
     * A closed, consistently wound shell with **no thickness**: one triangle and its own reverse, which uses
     * every directed edge once with exactly one opposite use and therefore passes every count.
     */
    private fun flapSheet(): Mesh3 =
        Mesh3(
            listOf(Vec3(0.0, 0.0, 0.0), Vec3(12.5, 0.0, 0.0), Vec3(12.5, 3.25, 0.0)),
            listOf(Tri(0, 1, 2), Tri(0, 2, 1)),
        )

    /** An open sheet: one triangle on its own, whose three edges have no opposite use at all. */
    private fun openSheet(): Mesh3 =
        Mesh3(
            listOf(Vec3(0.0, 0.0, 0.0), Vec3(12.5, 0.0, 0.0), Vec3(12.5, 3.25, 0.0)),
            listOf(Tri(0, 1, 2)),
        )

    @Test
    fun theFlapRefusalNamesAPositionInMillimetresAndNotAKotlinValue() {
        val said = assertNotNull(MeshCanon.flap(flapSheet()), "the gate names the fold").render()
        assertTrue("Vec3(" !in said, "a refusal is a sentence, not a debug print: $said")
        assertTrue("(0, 0, 0) mm" in said || "(12.5, 0, 0) mm" in said, "…and the position reads in millimetres: $said")
    }

    @Test
    fun theNotClosedRefusalNamesAPositionInMillimetresAndNotAKotlinValue() {
        val said = assertNotNull(MeshCanon.notClosed(openSheet()), "the gate names the crack").render()
        assertTrue("Vec3(" !in said, "a refusal is a sentence, not a debug print: $said")
        assertTrue(" mm" in said, "…and the position reads in millimetres: $said")
    }
}
