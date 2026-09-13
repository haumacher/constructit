package constructit

import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.geom.Blend3
import constructit.geom.FaceName
import constructit.geom.Feature3
import constructit.geom.Geom3
import constructit.geom.GeomMath
import constructit.geom.Loop
import constructit.geom.Section3
import constructit.geom.Vec3
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **A face address written by an older build still names the face it named** (OP-18, OP-31 slice 5p).
 *
 * *What moved, and why a version is what it costs.* Until this slice a rounding whose crease is one straight
 * run owned **no** flat-end face slot at all, on session 81's sentence: *"at a free end of a straight edge
 * that frame lies in the end face's plane — the cap is square to the edge and so is the face"*. That is true
 * at a **right angle** and nowhere else, so on a pentagonal prism and on a loft the band's flat cap was a
 * face of the body with no slot to be named in. Every entry owns two now, each either the cap it has or a
 * tombstone naming whoever owns that end — and every face slot after a straight-crease entry therefore
 * stands two further on.
 *
 * *So these three files are goldens of a different kind.* Each was written by the **version 9** build, each
 * stores an address that moves, and each is asserted here to come back meaning exactly what it meant: the
 * same face, at the same plane, enclosing the same area. They are never rewritten — a round trip inside one
 * build proves nothing about a build that has since changed its numbering, which is the whole reason OP-18
 * asks for files written by the build that is gone.
 */
class DressedCapSlotVersionTest {
    private fun resource(name: String): String = DressedCapSlotVersionTest::class.java.getResource("/$name")!!.readText()

    private fun load(text: String): Editor {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(text))
        return ed
    }

    private fun body(ed: Editor): Element = ed.doc.elements.last { it.kind == ElementKind.SOLID }

    @Suppress("UNCHECKED_CAST")
    private fun featureOf(ed: Editor): Feature3 = Evaluator().solid(body(ed).ref as SolidRef).feature

    @Suppress("UNCHECKED_CAST")
    private fun volumeOf(ed: Editor): Double {
        val mesh = Evaluator().solid(body(ed).ref as SolidRef).mesh
        assertManifold(mesh, "the body the file draws")
        return Geom3.volume(mesh)
    }

    /** The area a face patch's own outline encloses, in that face's own plane — what the sketch space offers. */
    private fun areaOf(outline: List<constructit.geom.ProfileElement>): Double = abs(GeomMath.signedArea(Loop(outline)))

    /** The face the space named [name] stands on, with the address it now holds. */
    private fun spaceFace(
        ed: Editor,
        name: String,
    ): Pair<Int, constructit.geom.FacePatch> {
        val space = assertNotNull(ed.doc.spaces.firstOrNull { it.name == name }, "the file's own sketch space")
        val on = assertNotNull(space.anchor, "…stands on a solid")

        @Suppress("UNCHECKED_CAST")
        val feature = Evaluator().solid(on.ref as SolidRef).feature
        val (patch, why) = Section3.facePatchOfFootprintPiece(feature, space.piece)
        return space.piece to assertNotNull(patch, "…and that address is a face: ${why?.render()}")
    }

    private val moved = "each name their two flat ends now"

    /**
     * **The band face a version-9 file put a sketch on.** The address was 7 when the file was written and is
     * 9 now, because the rounding before it gained its own two flat-end slots; the face is the same 4 mm
     * chamfer band along the plate's front rim, 60 mm long and `4√2` wide.
     */
    @Test
    fun aVersionNineSketchSpaceOnABandFaceComesBackOnThatBand() {
        assertEquals(10, DocumentFormat.CAP_SLOT_VERSION, "the version at which every entry names its flat ends")
        assertEquals(DocumentFormat.CAP_SLOT_VERSION, DocumentFormat.VERSION, "…and this build writes it")
        val ed = load(resource("v9-band-face-space.cit"))
        assertTrue(ed.doc.loadNotes.any { moved in it }, "the load says the face numbering moved: ${ed.doc.loadNotes}")
        val (piece, patch) = spaceFace(ed, "onband")
        assertEquals(9, piece, "the address the file wrote as 7 is 9 under this numbering")
        assertEquals(FaceName.BlendBand(10, 0), patch.name, "…and it is the very band it named")
        assertEquals(Vec3(60.0, 36.0, 20.0), assertNotNull(patch.plane, "the band is a plane a sketch opens on").origin, "the same face, at the same place")
        assertClose(areaOf(patch.outline), 60.0 * 4.0 * kotlin.math.sqrt(2.0), 1e-9, "…enclosing the same area")
        assertFixedPointAndSilentSecondTime(ed)
    }

    /**
     * **The flat end of a *curved* crease's band, which had a slot already.** Slice 5e gave a band along an
     * arc two flat-end slots; the straight-crease rounding *before* it in this file had none, so this
     * address moves by exactly those two — from 10 to 14 — and still names the cap at the arc's start.
     */
    @Test
    fun aVersionNineSketchSpaceOnACurvedBandsCapComesBackOnThatCap() {
        val ed = load(resource("v9-curved-cap-space.cit"))
        assertTrue(ed.doc.loadNotes.any { moved in it }, "the load says so: ${ed.doc.loadNotes}")
        val (piece, patch) = spaceFace(ed, "oncap")
        assertEquals(14, piece, "the address the file wrote as 10 is 14 under this numbering")
        assertEquals(FaceName.BlendCap(12, true), patch.name, "…and it is the cap at that arc's own start")
        assertEquals(Vec3(60.0, 28.0, 20.0), assertNotNull(patch.plane, "a cap is a plane").origin, "the same face, at the same place")
        assertClose(areaOf(patch.outline), 0.5 * 3.0 * 3.0, 1e-9, "…enclosing the same area: the 3 mm chamfer's own triangle")
        assertFixedPointAndSilentSecondTime(ed)
    }

    /**
     * **A chain is numbered at every level.** The file rounds the plate's front rim at 4 mm and then stacks a
     * 1 mm chamfer on that band's own rail, so its sketch space stands on the **outer** dressing's band —
     * address 7 when the file was written, 9 now. It moves by two and not by four, which is the whole point
     * of the map being recursive: the inner dressing's own two new slots are *inside* the base the outer one
     * counts from, so a reading that stripped only the outer level's caps would hand back the inner
     * rounding's flat end instead of this band.
     */
    @Test
    fun aVersionNineSketchSpaceOnAChainsOuterBandComesBackOnThatBand() {
        val ed = load(resource("v9-chained-band-space.cit"))
        assertTrue(ed.doc.loadNotes.any { moved in it }, "the load says so: ${ed.doc.loadNotes}")
        val (piece, patch) = spaceFace(ed, "onchain")
        assertEquals(9, piece, "the address the file wrote as 7 is 9 under this numbering")
        assertEquals(FaceName.BlendBand(12, 0), patch.name, "…and it is the band on the 4 mm chamfer's own rail")
        assertEquals(Vec3(0.0, 5.0, 20.0), assertNotNull(patch.plane, "a bevel's band is a plane").origin, "the same face, at the same place")
        assertFixedPointAndSilentSecondTime(ed)
    }

    /**
     * **The other address space that moves: a whole-face pick's.** A `chamferfaceedges` step records the
     * *face* it was aimed at, in the face list's own numbering rather than the address space's, and
     * [Blend3.faceAddressBeforeCapSlots] is the map the load runs over it. Asserted on the very body the
     * band-face fixture draws, so the two maps are checked to agree on one shape.
     */
    @Test
    fun aWholeFacePicksStoredAddressIsMappedByTheSameNames() {
        val ed = load(resource("v9-band-face-space.cit"))
        val feature = featureOf(ed)
        val faces = assertNotNull(Section3.faces(feature).first, "it names its faces")
        assertEquals(FaceName.BlendBand(10, 0), faces[9].name, "the second band stands at 9 now")
        assertEquals(9, Blend3.faceAddressBeforeCapSlots(feature, 7), "…and a file that wrote 7 meant it")
        assertEquals(6, Blend3.faceAddressBeforeCapSlots(feature, 6), "the first band has not moved")
        for (i in 0 until 6) assertEquals(i, Blend3.faceAddressBeforeCapSlots(feature, i), "no base face moves (#${i + 1})")
    }

    /**
     * **An address the map cannot place is handed back, and refuses by name** (OP-3) — never silently bound
     * to whatever face has slid into that slot.
     */
    @Test
    fun anAddressPastTheEndIsRefusedByNameRatherThanBound() {
        val text = resource("v9-band-face-space.cit").replace("piece=7", "piece=97")
        val why =
            try {
                load(text)
                null
            } catch (e: DocumentFormat.LoadError) {
                e.message
            }
        assertTrue(why != null && "97" in why || why != null && "98" in why, "the load names the address it cannot place: $why")
    }

    /** Saved once at this version, the file says nothing more and is a fixed point of save (OP-18). */
    private fun assertFixedPointAndSilentSecondTime(ed: Editor) {
        val once = DocumentFormat.save(ed.doc)
        assertTrue(once.startsWith("constructit ${DocumentFormat.VERSION}"), "it is written at this version")
        val again = load(once)
        assertTrue(again.doc.loadNotes.none { moved in it }, "said once only: ${again.doc.loadNotes}")
        assertEquals(once, DocumentFormat.save(again.doc), "…and the file is a fixed point of save")
        assertClose(volumeOf(again), volumeOf(ed), abs(volumeOf(ed)) * 1e-12, "the mapped file and the re-saved one are one body")
    }
}
