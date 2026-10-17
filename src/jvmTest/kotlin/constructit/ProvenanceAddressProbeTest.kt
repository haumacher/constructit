package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.SolidValue
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Feature3
import constructit.geom.Geom3
import constructit.geom.Provenance
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **A novel composition** (OP-32 slice 3, OP-23): a **patterned** rounding — one fillet gesture riding a ring of
 * extruded squares, each the profile of one copy of a polygon ride — whose source profiles gain a vertex upstream:
 * the polygon ride is re-stamped from four sides to five. Every copy's faces are swept from that copy's own sides,
 * so the rounding is stated once in the terms of the ring's first copy (`e…|top`) and read by every copy in its
 * own ([constructit.editor.Document.shiftKey]); the re-stamp renumbers every copy's edges at once, and every copy's
 * rounding stays on the rim over its own first side, in the session and in the file the session saves.
 */
class ProvenanceAddressProbeTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    private fun solids(ed: Editor): List<Element> = ed.doc.elements.filter { it.kind == ElementKind.SOLID }

    private fun valueOf(el: Element) = ((Evaluator().eval(el.ref.node) as? EvalResult.Ok)?.value as? SolidValue)?.solid

    /** The keys a dressed body rounds, as its pass resolved them. */
    private fun rounded(el: Element): Set<String> {
        val f = valueOf(el)!!.feature as Feature3.Blend
        val keys = Provenance.edgeKeys(f.base)!!
        return f.targets.withIndex().filter { (i, _) -> i !in f.absent.keys }.map { (_, t) -> keys[t] }.toSet()
    }

    /** The side each copy's rounding stands over, by the copy's own element — the generator its key names. */
    private fun sideOf(key: String): String = key.split('|').first { it != "top" && it != "bottom" }

    @Test
    fun aPatternedRoundingStaysOnItsEdgeOnEveryCopyWhenItsProfilesGainAVertex() {
        val ed = Editor()
        // a ring of three centres about the origin
        ed.count = 3
        ed.setTool(Tools.PATTERN_CIRCULAR)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 0.0))
        // a square about each, through the ring's centre (the one point a rotation leaves where it is)
        ed.count = 4
        ed.setTool(Tools.POLYGON)
        ed.click(Vec2(100.0, 0.0))
        ed.click(Vec2(0.0, 0.0))
        // each square extruded, and the first one's rim rounded — both ride the ring
        ed.activeScalar = ed.doc.newParameter("h", 20.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(150.0, 50.0))
        assertEquals(3, solids(ed).size, "three prisms: ${ed.statusHint}")
        ed.activeScalar = ed.doc.newParameter("r", 4.0.mm)
        ed.setTool(Tools.BLEND_EDGE)
        ed.click(Vec2(149.0, 51.0))
        ed.setTool(Tools.SELECT)
        val orbit = saved(ed.doc).kind("orbit").last()
        assertEquals("filletedge", orbit.str("tool"), "the rounding rides the ring: ${ed.statusHint}")
        val dressed = { solids(ed).filter { valueOf(it)?.feature is Feature3.Blend } }
        assertEquals(3, dressed().size, "three rounded prisms: ${ed.statusHint}")
        val sides = dressed().map { b -> rounded(b).single().let { sideOf(it) } }
        assertEquals(3, sides.toSet().size, "each copy rounds the rim over its own side: $sides")
        // which side of its own polygon each copy rounds: its position among the sides its copy made
        val position = { side: String ->
            val all = ed.doc.creatingStep(ed.doc.elements.first { ed.doc.nameOf(it) == side })!!.creates.filter { it.kind == ElementKind.SEGMENT }
            all.indexOfFirst { ed.doc.nameOf(it) == side } % (all.size / 3)
        }
        val at = sides.map(position)
        val removed = dressed().map { b -> Geom3.volume(valueOf(b)!!.mesh) }

        // a vertex upstream of every copy: the polygon ride re-stamped from four sides to five
        val before = DocumentFormat.save(ed.doc)
        val ride = ed.doc.patterns.flatMap { it.gestures }.first { it.toolId == Tools.POLYGON }
        assertTrue(ed.setRideCount(ride, 5), ed.statusHint)
        val now = dressed()
        assertEquals(3, now.size, "every copy still builds: ${ed.statusHint}")
        val sidesNow = now.map { b -> rounded(b).single().let { sideOf(it) } }
        assertEquals(sides, sidesNow, "every copy rounds the rim over the side it rounded, by name")
        assertEquals(at, sidesNow.map(position), "…which is the same side of its own polygon: $sidesNow")
        for ((k, b) in now.withIndex()) {
            assertManifold(valueOf(b)!!.mesh, "copy $k")
            assertTrue(Geom3.volume(valueOf(b)!!.mesh) > removed[k], "copy $k is a pentagon now, and bigger")
        }
        // the file states the rounding once, by the first copy's side, and loads as the session shows
        val text = DocumentFormat.save(ed.doc)
        val back = DocumentFormat.load(text)
        V11CorpusTest.assertSameFingerprint(V11CorpusTest.fingerprint(ed.doc), V11CorpusTest.fingerprint(back), "the session and its reload")
        assertSameFile(DocumentFormat.save(back), text, "save -> load -> save")
        assertTrue(ed.undo())
        assertEquals(before, DocumentFormat.save(ed.doc), "one undo is the drawing before the re-stamp")
    }
}
