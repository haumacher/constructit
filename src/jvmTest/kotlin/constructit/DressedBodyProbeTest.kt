package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.RegionRef
import constructit.dsl.SolidRef
import constructit.dsl.scalar
import constructit.dsl.solid
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Geom3
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **Orchestrator's probe of OP-30 (one dressed body, many roundings)** on what the delivery never saw: the
 * reporter's migrated file with its concave entry removed against the same file written without that step, a
 * dressing of two sizes and two kinds built in two orders, a section that follows the body as entries come and
 * go, and undo across an added entry.
 */
class DressedBodyProbeTest {
    private val fixture = """constructit 5
orthostart -26.875,-32.375 -> e1
orthovertex -26.875,15.375 -> e2,e3
orthovertex 41.999800864975384,15.375 -> e4,e5
orthovertex 41.999800864975384,-11.775083491926196 -> e6,e7
orthovertex -5.521648428788623,-11.775083491926196 -> e8,e9
orthovertex -5.521648428788623,-32.375 -> e10,e11
orthoclose -> e12
param "h" = 20mm
tool extrude els=e11 clicks=-48.125,37.875 scalar="h" -> e13
param "r" = 5mm
tool filletedge els=e13 clicks=-42.670739764447546,-4.867038301721209 scalar="r" signs=12;-1;1;0;1 -> e14
tool filletedge els=e14 clicks=-31.533048614089623,14.504582242265968 scalar="r" signs=13;-1;1;0;1 -> e15
tool filletedge els=e15 clicks=-15.209120508301623,-22.09480593584297 scalar="r" signs=1;-1;1;0;1 -> e16
tool filletedge els=e16 clicks=-1.6336097588108203,35.97358839564461 scalar="r" signs=14;-1;1;0;1 -> e17
tool filletedge els=e17 clicks=-11.301657028615722,8.858099327956722 scalar="r" signs=2;-1;1;0;-1 -> e18
tool filletedge els=e18 clicks=56.88568755568988,21.122250050431774 scalar="r" signs=3;-1;1;0;1 -> e19
tool filletedge els=e19 clicks=52.78762484641989,32.49678119098172 scalar="r" signs=15;-1;1;0;1 -> e20
"""

    private fun solids(doc: Document) = doc.elements.filter { it.kind == ElementKind.SOLID }

    private fun entries(doc: Document) = doc.elements.filter { it.kind == ElementKind.DRESSING }

    private fun volumeOf(el: Element): Double {
        val res = Evaluator().eval(el.ref.node)
        assertTrue(res !is EvalResult.Invalid, "valid: ${(res as? EvalResult.Invalid)?.reason}")
        val mesh = Evaluator().solid(el.ref as SolidRef).mesh
        assertManifold(mesh, "the dressed body")
        return Geom3.volume(mesh)
    }

    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    @Test
    fun theConcaveEntryRemovedIsTheFileWrittenWithoutIt() {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(fixture))
        assertEquals(1, solids(ed.doc).count { ed.doc.dressingOf(it) != null }, "one dressed body")
        assertEquals(7, entries(ed.doc).size, "seven entries")
        val concave = entries(ed.doc)[4]
        ed.selectElement(concave)
        assertTrue(ed.deleteSelection(), "the concave rounding comes off: ${ed.statusHint}")
        assertEquals(6, entries(ed.doc).size)
        val six = volumeOf(solids(ed.doc).last())
        // the same drawing written without that step, migrated the same way
        val without = fixture.lines().filter { !it.contains("signs=2;-1;1;0;-1") }.joinToString("\n").replace("els=e18 ", "els=e17 ") + "\n"
        val fresh = DocumentFormat.load(without)
        assertEquals(6, entries(fresh).size, "six entries from the file: ${fresh.loadNotes}")
        val theirs = volumeOf(solids(fresh).last())
        assertTrue(abs(six - theirs) < 1e-6 * theirs, "removing an entry is the file without it: $six vs $theirs")
        // and the file is a fixed point at the new version
        val once = DocumentFormat.save(ed.doc)
        assertTrue(once.startsWith("constructit 6"), once.lines().first())
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)))
        assertTrue(DocumentFormat.load(once).loadNotes.isEmpty(), "a current file says nothing on load")
    }

    private fun plate(): Editor {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 40.0))
        ed.activeScalar = ed.doc.newParameter("depth", 20.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(30.0, 0.0))
        return ed
    }

    /** Three roundings of two kinds and two sizes on the plate's rim, in the given order of the three gestures. */
    private fun dressed(order: List<Int>): Editor {
        val ed = plate()
        val r = ed.doc.newParameter("r", 4.0.mm)
        val c = ed.doc.newParameter("c", 3.0.mm)
        val gestures =
            listOf(
                Triple(Tools.BLEND_EDGE, r, Vec2(30.0, 0.0)),
                Triple(Tools.CHAMFER_EDGE, c, Vec2(60.0, 20.0)),
                Triple(Tools.BLEND_EDGE, r, Vec2(30.0, 40.0)),
            )
        for (i in order) {
            val (tool, scalar, at) = gestures[i]
            ed.activeScalar = scalar
            ed.setTool(tool)
            ed.click(at)
            assertTrue(ed.doc.elements.count { it.kind == ElementKind.SOLID } == 2, "one plate, one dressed body: ${ed.statusHint}")
        }
        return ed
    }

    @Test
    fun twoKindsAndTwoSizesAreOneBodyWhateverTheOrder() {
        val a = dressed(listOf(0, 1, 2))
        val b = dressed(listOf(2, 0, 1))
        assertEquals(3, entries(a.doc).size)
        assertEquals(3, entries(b.doc).size)
        val va = volumeOf(solids(a.doc).last())
        val vb = volumeOf(solids(b.doc).last())
        assertTrue(abs(va - vb) < 1e-6 * va, "the same three roundings in another order: $va vs $vb")
        // closed form: two fillets r along 60, one chamfer c along 40, no shared corners (the chamfer's edge does not meet the rounded ones... it does at two vertices)
        val plate = 60.0 * 40.0 * 20.0
        assertTrue(va < plate && va > plate - 2 * (16 * (1 - Math.PI / 4)) * 60 - (4.5) * 40 - 1.0 && va > plate * 0.98, "a plausible body: $va")
    }

    private fun view(
        ed: Editor,
        cam: constructit.editor.Camera3,
    ): constructit.editor.Viewport3 {
        val vp = constructit.editor.Viewport3(camera = cam, widthPx = 800.0, heightPx = 600.0)
        vp.editor = ed
        vp.shown = true
        return vp
    }

    /** The plate's whole top face rounded (one entry) and one upright rounded (a second entry), one shared radius. */
    private fun capAndUpright(): Editor {
        val ed = plate()
        ed.activeScalar = ed.doc.newParameter("r", 4.0.mm)
        ed.setTool(Tools.BLEND_FACE)
        val t = constructit.geom.Vec3(30.0, 20.0, 10.0)
        val vp = view(ed, constructit.editor.Camera3(target = t, distance = 250.0, yaw = -1.0, pitch = 1.1))
        val s = vp.camera.project(constructit.geom.Vec3(30.0, 20.0, 20.0), vp.widthPx, vp.heightPx)!!
        vp.pointerDown(s)
        vp.pointerUp(s)
        assertEquals(1, entries(ed.doc).size, "the cap's rounding: ${ed.statusHint}")
        roundUpright(ed)
        assertEquals(2, entries(ed.doc).size, "the upright's rounding: ${ed.statusHint}")
        return ed
    }

    /** The upright at the plate's (0, 0) corner, picked in the 3D view from the first camera that sees it. */
    private fun roundUpright(ed: Editor) {
        val mid = constructit.geom.Vec3(0.0, 0.0, 10.0)
        val before = entries(ed.doc).size
        for (yaw in listOf(-2.3, -2.0, -2.7, 2.3, 3.5, -1.6, 4.0)) {
            ed.setTool(Tools.BLEND_EDGE)
            val vp = view(ed, constructit.editor.Camera3(target = mid, distance = 150.0, yaw = yaw, pitch = 0.25))
            val s = vp.camera.project(mid, vp.widthPx, vp.heightPx) ?: continue
            vp.pointerDown(s)
            vp.pointerUp(s)
            if (entries(ed.doc).size == before + 1) return
        }
        throw AssertionError("no camera reached the upright: ${ed.statusHint}")
    }

    @Test
    fun aSectionFollowsTheBodyAsEntriesComeAndGo() {
        val ed = capAndUpright()
        val body = solids(ed.doc).last()
        ed.activeScalar = ed.doc.newParameter("z", 18.0.mm)
        ed.setTool(Tools.SECTION)
        ed.click(Vec2(30.0, 0.0))
        val area = ed.doc.elements.last { it.kind == ElementKind.AREA }

        @Suppress("UNCHECKED_CAST")
        fun areaNow(): Double {
            val why = (Evaluator().eval(area.ref.node) as? EvalResult.Invalid)?.reason
            assertNull(why, "the section is valid: $why — ${ed.statusHint}")
            return Evaluator().scalar(ed.doc.cx.regionArea(area.ref as RegionRef)).base
        }
        val withBoth = areaNow()
        // the rim's bands take 4 − √12 off every side at z = 18, the ball at the rounded upright a little more
        val inset = 4.0 - Math.sqrt(12.0)
        val capOnly = (60.0 - 2 * inset) * (40.0 - 2 * inset) - (4 - Math.PI) * inset * inset
        assertTrue(withBoth < capOnly && withBoth > capOnly - 20.0, "under the rim with one rounded corner: $withBoth vs $capOnly")
        // remove the upright's entry: the same section element re-reads the re-stamped body
        val vBoth = volumeOf(body)
        ed.selectElement(entries(ed.doc)[1])
        assertTrue(ed.deleteSelection(), ed.statusHint)
        assertEquals(1, entries(ed.doc).size, "one entry left: ${ed.statusHint}")
        val vCap = volumeOf(solids(ed.doc).last())
        assertTrue(vCap > vBoth + 1.0, "the body itself has more material without the upright's rounding: $vCap vs $vBoth — ${ed.statusHint}")
        val capAlone = areaNow()
        assertTrue(capAlone > withBoth, "the section grew: $capAlone after $withBoth — body ${solids(ed.doc).last().id} vs section's source; ${ed.statusHint}")
        assertTrue(ed.doc.elements.any { it === area }, "the same section element, not a new one")
        // add it back: the section returns to what it was
        ed.activeScalar = ed.doc.scalars.first { it.name == "r" }
        roundUpright(ed)
        assertEquals(2, entries(ed.doc).size, ed.statusHint)
        assertTrue(abs(areaNow() - withBoth) < 1e-6, "back to both: ${areaNow()} vs $withBoth")
        assertNull((Evaluator().eval(body.ref.node) as? EvalResult.Invalid)?.reason, "the body stays valid throughout")
    }

    @Test
    fun undoOfAnAddedEntryRestoresTheBodyAndTheCount() {
        val ed = dressed(listOf(0, 1))
        val two = volumeOf(solids(ed.doc).last())
        ed.activeScalar = ed.doc.scalars.first { it.name == "r" }
        ed.setTool(Tools.BLEND_EDGE)
        ed.click(Vec2(30.0, 40.0))
        assertEquals(3, entries(ed.doc).size)
        assertTrue(ed.undo(), "one undo")
        assertEquals(2, entries(ed.doc).size, "the added entry is gone")
        assertClose(volumeOf(solids(ed.doc).last()), two, abs(two) * 1e-9, "and the body is what it was")
    }
}
