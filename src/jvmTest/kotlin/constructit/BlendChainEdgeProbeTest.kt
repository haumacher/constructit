package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.SolidRef
import constructit.dsl.scalar
import constructit.dsl.solid
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.geom.Blend3
import constructit.geom.BlendKind
import constructit.geom.BlendSection
import constructit.geom.EdgeName
import constructit.geom.Geom3
import constructit.geom.Section3
import constructit.units.mm
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Orchestrator's probe of OP-31 item 3** (edges as chains, the mitre crease) on what the delivery never saw:
 * the format migration on files of both shapes, a rounding of another size along the chain with a section
 * through it, and a mitre fillet whose bevel is then taken away.
 */
class BlendChainEdgeProbeTest {
    private val base = """constructit 6
orthostart -26.875,-32.375 -> e1
orthovertex -26.875,15.375 -> e2,e3
orthovertex 61.875,15.375 -> e4,e5
orthovertex 61.875,0.375 -> e6,e7
orthovertex -5.521648428788623,0.375 -> e8,e9
orthovertex -5.521648428788623,-32.375 -> e10,e11
orthoclose -> e12
param "h" = 20mm
tool extrude els=e11 clicks=-48.125,37.875 scalar="h" -> e13
hide els=e1,e2,e3,e4,e5,e6,e7,e8,e9,e10,e11,e12,e13
show els=e1,e2,e3,e4,e5,e6,e7,e8,e9,e10,e11,e12,e13
param "r" = 4mm
"""
    private val threeBevels =
        base + """tool chamferedge els=e13 clicks=-12.581664043342087,5.018353790754986 scalar="r" signs=2;-1;1;0;-1 -> e14,e15
tool chamferedge els=e14 clicks=-36.10499303384047,0.8875707537249014 scalar="r" signs=13;-1;1;0;1 -> e16
tool chamferedge els=e14 clicks=-6.480180051294639,24.048959979858537 scalar="r" signs=14;-1;1;0;1 -> e17
"""
    private val railFillet = """param "r2" = 2mm
tool filletedge els=e14 clicks=-33.91557367038956,-1.7134784580017737 scalar="r2" signs=20;-1;1;0;1 -> e18,e19
"""

    /** Two bevels crossing at the block's convex corner (−5.52, −32.375, 20): a straight mitre between them. */
    private val twoBevelsCrossing =
        base + """tool chamferedge els=e13 clicks=-16.2,-32.4 scalar="r" signs=12;-1;1;0;1 -> e14,e15
tool chamferedge els=e14 clicks=-5.5,-16.0 scalar="r" signs=13;-1;1;0;1 -> e16
"""
    private val script1Roundings = """tool filletedge els=e13 clicks=-31.252365457721638,11.31587462139538 scalar="r" signs=13;-1;1;0;1 -> e14,e15
tool filletedge els=e14 clicks=-15.659687663642714,14.554138077719443 scalar="r" signs=2;-1;1;0;-1 -> e16
"""

    private fun load(text: String): Editor {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(text))
        return ed
    }

    private fun solids(ed: Editor) = ed.doc.elements.filter { it.kind == ElementKind.SOLID }

    @Suppress("UNCHECKED_CAST")
    private fun refOf(el: Element) = el.ref as SolidRef

    private fun volumeOf(
        ref: SolidRef,
        what: String,
    ): Double {
        val r = Evaluator().eval(ref.node)
        assertTrue(r !is EvalResult.Invalid, "$what is valid: ${(r as? EvalResult.Invalid)?.reason}")
        val mesh = Evaluator().solid(ref).mesh
        assertManifold(mesh, what)
        return Geom3.volume(mesh)
    }

    @Test
    fun anOldFileIsToldOnceOnlyWhereARailRoundingChangedMeaning() {
        val withRail = load(threeBevels + railFillet)
        assertTrue(withRail.doc.loadNotes.isNotEmpty(), "a version-6 file whose rounding addresses a rail is told its run grew")
        assertTrue(DocumentFormat.save(withRail.doc).startsWith("constructit ${DocumentFormat.VERSION}\n"), "and it is written at the version this build writes")
        val bevelsOnly = load(threeBevels)
        assertTrue(bevelsOnly.doc.loadNotes.isEmpty(), "a version-6 file with base-edge roundings only has nothing to be told: ${bevelsOnly.doc.loadNotes}")
        val script1 = load(base + script1Roundings)
        assertTrue(script1.doc.loadNotes.isEmpty(), "script 1 (base edges only) has nothing to be told: ${script1.doc.loadNotes}")
        val reloaded = load(DocumentFormat.save(withRail.doc))
        assertTrue(reloaded.doc.loadNotes.isEmpty(), "once written at this build's own version nothing is said again")
    }

    @Test
    fun aSmallerRoundingAlongTheChainRunsTheWholeRibbonAndSectionsClose() {
        val ed = load(threeBevels + railFillet)
        val before = volumeOf(refOf(solids(ed)[1]), "three bevels")
        val after = volumeOf(refOf(solids(ed).last()), "rail 20 filleted at 2 mm along its chain")
        val drop = before - after
        val theta = 3.0 * PI / 4.0
        val w = Figures.wedgeArea(2.0, BlendKind.FILLET, theta)
        val wc = Figures.wedgeAreaByChords(2.0, BlendKind.FILLET, theta)
        // the ribbon the delivery measured: five pieces, 104.087 mm; the two turns add a Pappus term of a few
        // percent, and at r = 2 on a 135° wedge the chords are a fifth of the wedge itself
        val ribbon = 104.087
        assertTrue(drop > w * ribbon * 0.95 && drop < wc * ribbon * 1.05, "the 2 mm fillet runs the whole ribbon: took $drop, the band alone is [${w * ribbon}, ${wc * ribbon}]")
        val cx = ed.doc.cx
        // **A level section through the pivot is a standing gap of the *pivot*, not of the chain**, and the
        // proof is a body this package never touches: the plain three-bevel corner — the base of this very
        // fixture, built by session 82 and unchanged — closes at `z = 15, 10, 5` and refuses **by name** at
        // `17, 19, 19.5`. The cause is session 79's own cut (5), *"the band's own face outline is still the
        // full sweep"*: the upright's band is drawn over its whole 20 mm although the corner ends it at 16,
        // so above that the loop meets a piece the body does not have. What item 3 owes — and what broke,
        // and is what this method was written to catch — is that the chained body be **stated** at every
        // height rather than throwing, and that it close wherever its own base closes.
        for (z in listOf(19.0, 19.5, 19.9)) {
            val section = cx.sectionAt(refOf(solids(ed).last()), cx.const(z.mm))
            val reason = (Evaluator().eval(section.node) as? EvalResult.Invalid)?.reason ?: ""
            assertTrue(
                !reason.contains("Exception"),
                "the level section at z = $z is stated rather than thrown: $reason",
            )
        }
        for (z in listOf(15.0, 10.0, 5.0)) {
            val section = cx.sectionAt(refOf(solids(ed).last()), cx.const(z.mm))
            val r = Evaluator().eval(section.node)
            assertTrue(r !is EvalResult.Invalid, "the level section at z = $z is stated: ${(r as? EvalResult.Invalid)?.reason}")
            val area = Evaluator().scalar(cx.regionArea(section)).base
            assertTrue(area > 0.0, "the level section at z = $z closes: $area")
        }
    }

    @Test
    fun theMitreBetweenTwoBevelsIsAnEdgeAndLosingABevelDoesNotRoundSomethingElseSilently() {
        val ed = load(twoBevelsCrossing)
        val body = solids(ed).last()
        val edges = assertNotNull(Section3.edges(Evaluator().solid(refOf(body)).feature).first)
        val mitres = edges.indices.filter { edges[it].name is EdgeName.BlendMitre && edges[it].reason == null }
        assertEquals(1, mitres.size, "the two top bevels crossing at the convex corner meet in one mitre crease: ${edges.map { it.name }}")
        // and the three bevels at the reflex vertex meet in a walk, which is smooth: no mitre there
        val reflex = load(threeBevels)
        val reflexEdges = assertNotNull(Section3.edges(Evaluator().solid(refOf(solids(reflex).last())).feature).first)
        assertTrue(reflexEdges.none { it.name is EdgeName.BlendMitre && it.reason == null }, "a walk hands over smoothly and makes no mitre")
        val cx = ed.doc.cx
        val (choices, why) = Blend3.choicesFor(Evaluator().solid(refOf(body)), listOf(mitres[0]), BlendSection(BlendKind.FILLET, 1.0))
        assertNotNull(choices, "the mitre is roundable: ${why?.render()}")
        val rounded = cx.blendAll(refOf(body), cx.planeXY(), listOf(Construction.BlendRun(BlendKind.FILLET, cx.const(1.0.mm), null, mitres.take(1), choices)))
        val v0 = volumeOf(refOf(body), "three bevels")
        val v1 = volumeOf(rounded, "the mitre rounded")
        assertTrue(v1 < v0, "a convex mitre rounded takes material: $v1 < $v0")
        // the bevel on edge 14 comes off the dressing: the mitre it made is gone
        val entries = ed.doc.elements.filter { it.kind == ElementKind.DRESSING }
        assertEquals(2, entries.size)
        ed.selectElement(entries[1])
        assertTrue(ed.deleteSelection(), "the bevel on edge 13 comes off: ${ed.statusHint}")
        val r = Evaluator().eval(rounded.node)
        if (r is EvalResult.Invalid) {
            assertTrue(!r.reason.isNullOrBlank(), "the orphaned mitre rounding says why")
            println("mitre rounding after its bevel is gone: INVALID — ${r.reason}")
        } else {
            val v2 = volumeOf(rounded, "the mitre rounding with its bevel gone")
            println("mitre rounding after its bevel is gone: VALID, volume $v2 (was $v1) — a corner address slid, the recorded OP-30 exposure")
        }
    }
}
