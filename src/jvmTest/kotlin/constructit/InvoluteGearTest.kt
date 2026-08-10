package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.FuncCurveValue
import constructit.core.ScalarValue
import constructit.dsl.valueOf
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.FuncCurves
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **The gear is the acceptance, not the feature** (the session-71 entry's own words, and the standing
 * everything-generic rule): with `r` a named parameter, an involute flank is
 * `x(t) = r·(cos t + t·sin t)`, `y(t) = r·(sin t − t·cos t)` — one function curve and no gear code
 * anywhere.
 *
 * What it proves is that the new curve kind is a **citizen**: it mirrors, it patterns, it traces into a
 * closed outline beside arcs, it extrudes to a watertight solid, and one number re-rounds all of it. Every
 * composition here is an existing feature meeting the new curve; none of them needed a case of its own.
 *
 * The tooth is the textbook one, with the tooth's angular width at the root set to the pitch so the flanks
 * of neighbouring teeth meet: `ψ(t) = t − arctan t` is the involute function, the flank runs from the base
 * circle (`t = 0`, radius `r`) out to `t = T` (radius `r·√(1+T²)`), and the mirror axis stands at `π/z`.
 */
class InvoluteGearTest {
    private val z = 6
    private val phi = PI / z
    private val bigT = 0.9

    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerDown(s)
        pointerUp(s)
    }

    /**
     * The tooth's four pieces arrayed round the centre with the ordinary **Circular array** tool — through
     * the gesture, so the pattern is a recorded step and the file carries the whole gear.
     */
    private fun Editor.arrayAround(
        onPiece: List<Vec2>,
        centre: Vec2,
    ) {
        for (p in onPiece) {
            count = 6
            setTool(Tools.ARRAY_CIRCULAR)
            click(p)
            click(centre)
        }
    }

    /** The involute's polar angle at [t] — `inv t`, computed here rather than by anything in the engine. */
    private fun psi(t: Double) = t - atan(t)

    private fun flankPoint(
        r: Double,
        t: Double,
    ) = Vec2(r * (cos(t) + t * sin(t)), r * (sin(t) - t * cos(t)))

    private class Gear(
        val ed: Editor,
        val flankA: Element,
        val flankB: Element,
        val tip: Element,
        val root: Element,
        val tooth: Element?,
        val centre: Element,
    )

    /** One tooth, built out of nothing but the ordinary vocabulary plus one function curve. */
    private fun tooth(
        r: Double = 20.0,
        trace: Boolean = true,
    ): Gear {
        val ed = Editor()
        ed.doc.newParameter("r", r.mm)
        val o = ed.doc.freePoint(0.0.mm, 0.0.mm)
        val centre = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.POINT })
        // the mirror axis: the ray of the tooth's own centre line, through the origin at π/z
        val axisEnd = ed.doc.freePoint((100.0 * cos(phi)).mm, (100.0 * sin(phi)).mm)
        val axis = ed.doc.line(o, axisEnd)

        val flankA =
            assertNotNull(
                ed.addFunctionCurve("r * (cos(t) + t * sin(t))", "r * (sin(t) - t * cos(t))", 0.0, bigT),
                "the involute flank must build",
            )
        val flankB = assertNotNull(ed.doc.mirror(flankA, axis), "the flank mirrors")

        // the two flanks' own ends — a function curve's key points, and what the arcs are built onto
        val endsA = ed.doc.extractPoints(flankA)
        val endsB = ed.doc.extractPoints(flankB)

        @Suppress("UNCHECKED_CAST")
        val tip = ed.doc.arcCenterStartEnd(o, endsA[1] as constructit.dsl.PointRef, endsB[1] as constructit.dsl.PointRef)

        @Suppress("UNCHECKED_CAST")
        val root = ed.doc.arcCenterStartEnd(o, endsB[0] as constructit.dsl.PointRef, endsA[0] as constructit.dsl.PointRef)

        val ev = Evaluator()
        val onA = assertNotNull(FuncCurves.pointAt(curve(ev, flankA), bigT / 2))
        val onB = assertNotNull(FuncCurves.pointAt(curve(ev, flankB), bigT / 2))
        val ra = r * sqrt(1 + bigT * bigT)
        val onTip = Vec2(ra * cos(phi), ra * sin(phi))
        val onRoot = Vec2(r * cos(phi), r * sin(phi))
        val outline =
            if (!trace) {
                null
            } else {
                assertNotNull(
                    ed.doc.buildOutline(listOf(flankA, tip, flankB, root), listOf(onA, onTip, onB, onRoot)),
                    "the four pieces must trace into one closed tooth: ${ed.doc.note}",
                )
            }
        return Gear(ed, flankA, flankB, tip, root, outline, centre)
    }

    private fun curve(
        ev: Evaluator,
        el: Element,
    ) = assertNotNull((ev.valueOf(el.ref) as? FuncCurveValue)?.curve)

    // ---- the compositions ----

    @Test
    fun theFlankIsTheTrueInvoluteAndItsMirrorIsExact() {
        val g = tooth(20.0)
        val ev = Evaluator()
        val a = curve(ev, g.flankA)
        val b = curve(ev, g.flankB)
        for (i in 0..20) {
            val t = bigT * i / 20.0
            val p = assertNotNull(FuncCurves.pointAt(a, t))
            assertClose(p.x, flankPoint(20.0, t).x, 1e-9, "the flank is the involute")
            assertClose(p.y, flankPoint(20.0, t).y, 1e-9)
            // the mirror is *composed with the function*, so it is exact rather than fitted: the image has
            // the same radius and the reflected polar angle
            val q = assertNotNull(FuncCurves.pointAt(b, t))
            assertClose(q.length(), p.length(), 1e-9, "a mirrored involute keeps its radii")
            assertClose(q.angle(), 2 * phi - psi(t), 1e-9, "…and reflects its angles about the tooth's axis")
        }
    }

    @Test
    fun theTraceCrossesTheFunctionCurveAndClosesTheTooth() {
        val g = tooth(20.0)
        val tooth = assertNotNull(g.tooth)
        assertEquals(ElementKind.OUTLINE, tooth.kind)
        val loop = assertNotNull((Evaluator().valueOf(tooth.ref) as? constructit.core.LoopValue)?.loop)
        assertEquals(4, loop.elements.size, "four pieces: two flanks and two arcs")
        assertEquals(
            2,
            loop.elements.count { it is constructit.geom.ProfileElement.FuncE },
            "and two of them are function curves",
        )
        val area = constructit.geom.GeomMath.signedArea(loop)
        assertTrue(kotlin.math.abs(area) > 1.0, "the tooth encloses an area ($area)")
    }

    @Test
    fun theToothExtrudesToAWatertightSolidAndTheModuleReRoundsIt() {
        val g = tooth(20.0)
        val depth = g.ed.doc.newParameter("depth", 5.0.mm)
        val solid =
            assertNotNull(g.ed.doc.extrudeSolid(assertNotNull(g.tooth), depth.ref), "the tooth extrudes: ${g.ed.doc.note}")
        val v1 = volume(g.ed, solid)
        assertManifold(mesh(g.ed, solid), "the involute tooth")
        // one number re-rounds the whole thing: the flank, both arcs, the trace and the solid under them.
        // A tooth is a plane figure scaled by r, so doubling r must quadruple the area and hence the volume.
        val r = assertNotNull(g.ed.doc.scalars.firstOrNull { it.name == "r" })
        g.ed.doc.setParameter(r, 40.0.mm)
        val v2 = volume(g.ed, solid)
        assertClose(v2 / v1, 4.0, 1e-6, "doubling r quadruples the tooth's volume — nothing was rebuilt")
        assertManifold(mesh(g.ed, solid), "the re-rounded involute tooth")
    }

    @Test
    fun aCircularPatternOfTheToothRidesTheFunctionCurve() {
        val g = tooth(20.0)
        val centreRef = g.centre.ref as constructit.dsl.PointRef
        val copies = g.ed.doc.circularArray(listOf(g.flankA, g.flankB, g.tip, g.root), centreRef, z)
        assertEquals(4 * (z - 1), copies.size, "every piece of the tooth is patterned")
        val ev = Evaluator()
        // the pattern turns the *function*, not a sampling of it: copy k of the flank is the original
        // rotated by k pitches, exactly, at every parameter
        val patternedFlanks = copies.filter { it.kind == ElementKind.FUNC_CURVE }
        assertEquals(2 * (z - 1), patternedFlanks.size)
        val a = curve(ev, g.flankA)
        val first = curve(ev, patternedFlanks[0])
        val pitch = 2 * PI / z
        for (i in 0..10) {
            val t = bigT * i / 10.0
            val p = assertNotNull(FuncCurves.pointAt(a, t))
            val q = assertNotNull(FuncCurves.pointAt(first, t))
            assertClose(q.x, p.x * cos(pitch) - p.y * sin(pitch), 1e-9, "the copy is the rotation, exactly")
            assertClose(q.y, p.x * sin(pitch) + p.y * cos(pitch), 1e-9)
        }
        // …and the neighbouring teeth meet at the root, which is what makes this a gear rather than a flower
        val bCurve = curve(ev, g.flankB)
        val endOfB = assertNotNull(FuncCurves.pointAt(bCurve, 0.0))
        val startOfNext = assertNotNull(FuncCurves.pointAt(first, 0.0))
        assertClose((endOfB - startOfNext).length(), 0.0, 1e-9, "tooth to tooth, with no gap at the root")
    }

    @Test
    fun theWholeGearSurvivesTheFileAndComesBackTheSameGear() {
        // built entirely through the **gestures**, so every piece of it is a recorded step (OP-18): the
        // flank, the axis it is mirrored about, the mirror, and the two circular arrays.
        val ed = Editor()
        ed.doc.newParameter("r", 20.0.mm)
        ed.setTool(Tools.LINE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0 * cos(phi), 100.0 * sin(phi)))
        val flankA =
            assertNotNull(ed.addFunctionCurve("r * (cos(t) + t * sin(t))", "r * (sin(t) - t * cos(t))", 0.0, bigT))
        val ev = Evaluator()
        val onA = assertNotNull(FuncCurves.pointAt(curve(ev, flankA), bigT / 2))
        ed.setTool(Tools.MIRROR)
        ed.click(onA)
        ed.click(Vec2(50.0 * cos(phi), 50.0 * sin(phi)))
        val flankB =
            assertNotNull(
                ed.doc.elements.lastOrNull { it.kind == ElementKind.FUNC_CURVE && it !== flankA },
                "the flank mirrors through the tool: ${ed.doc.note}",
            )
        val onB = assertNotNull(FuncCurves.pointAt(curve(Evaluator(), flankB), bigT / 2))
        for (p in listOf(onA, onB)) {
            ed.count = z
            ed.setTool(Tools.ARRAY_CIRCULAR)
            ed.click(p)
            ed.click(Vec2(0.0, 0.0))
        }
        val once = DocumentFormat.save(ed.doc)
        val back = DocumentFormat.load(once)
        assertEquals(once, DocumentFormat.save(back), "save -> load -> save is byte-equal")
        assertEquals(
            2 * z,
            back.elements.count { it.kind == ElementKind.FUNC_CURVE },
            "…and every flank of the gear came back:\n$once",
        )
        assertTrue(once.contains("\"r * (cos(t) + t * sin(t))\""), "with the text the user wrote:\n$once")
    }

    // ---- the mesh, read where every other test reads it ----

    private fun mesh(
        ed: Editor,
        solid: Element,
    ) = assertNotNull((Evaluator().valueOf(solid.ref) as? constructit.core.SolidValue)?.solid?.mesh)

    private fun volume(
        ed: Editor,
        solid: Element,
    ): Double {
        val e = assertNotNull(ed.doc.measureSolidVolume(solid))
        return assertNotNull((Evaluator().eval(e.ref.node) as? EvalResult.Ok)?.value as? ScalarValue).q.base
    }
}
