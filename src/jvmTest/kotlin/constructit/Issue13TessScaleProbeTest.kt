package constructit

import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.BoolOp
import constructit.geom.Geom3
import constructit.geom.Mesh3
import constructit.geom.Vec2
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The reviewer's probe for scale-relative tessellation (GitHub #13, session 56): the properties the
 * delivery's own tests could not put together — an extreme radius, a boolean straddling the 20 mm
 * crossover, and a coaxial same-radius union whose shared **curved** wall must stay watertight though the
 * rule now coarsens it. The invariant claim is that a chord count is a pure function of radius; the sharpest
 * test of it is a body whose watertightness depends on two faces of the *same* radius meeting exactly.
 */
class Issue13TessScaleProbeTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.type(digits: String) {
        for (c in digits) key(c.toString())
        key("Enter")
    }

    @Suppress("UNCHECKED_CAST")
    private fun meshOf(el: Element): Mesh3 = Evaluator().solid(el.ref as SolidRef).mesh

    /** A cylinder of [r] and height [h] about the origin, in the plan. */
    private fun cylinder(
        ed: Editor,
        r: String,
        h: String,
        at: Vec2 = Vec2(0.0, 0.0),
    ): Element {
        ed.setTool(Tools.CIRCLE_R)
        ed.type(r)
        ed.click(at)
        ed.setTool(Tools.EXTRUDE)
        ed.type(h)
        ed.click(Vec2(at.x + r.toDouble(), at.y)) // on the circle boundary, which is how a bare circle is picked as an area
        return assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SOLID }, ed.statusHint)
    }

    private fun triCount(el: Element) = meshOf(el).triangles.size

    /**
     * **An extreme radius stays scale-invariant and watertight.** r=2000 (a two-metre disc) meshes to the
     * same triangle count as r=200 and r=20 — the count does not grow without bound with size, which is the
     * whole of #13 — and the body is still a closed manifold, so coarsening never opened a seam.
     */
    @Test
    fun anExtremeRadiusStaysScaleInvariantAndManifold() {
        val small = triCount(cylinder(Editor(), "20", "10"))
        val big = triCount(cylinder(Editor(), "200", "10"))
        val huge = triCount(cylinder(Editor(), "2000", "10"))
        assertEquals(small, big, "r=20 and r=200 mesh to the same count (scale-invariant)")
        assertEquals(small, huge, "and so does r=2000 — the count is bounded, not growing with size")
        assertManifold(meshOf(cylinder(Editor(), "2000", "10")), "the two-metre disc is watertight")
    }

    /**
     * **A boolean straddling the crossover is watertight.** One operand below the 20 mm floor (r=15, meshed at
     * the old absolute rule) and one well above it (r=25, meshed at the relative rule), coaxial and
     * overlapping: the two are tessellated by *different* tolerances, and the union is still a closed
     * manifold — because nothing in a mesh boolean needs the two input tessellations to match, which is the
     * delivery's own claim, put under load across the very boundary the floor introduces.
     */
    @Test
    fun aBooleanAcrossTheCrossoverIsWatertight() {
        val ed = Editor()
        val below = cylinder(ed, "15", "40", at = Vec2(0.0, 0.0))
        val above = cylinder(ed, "25", "20", at = Vec2(0.0, 0.0))
        val union = assertNotNull(ed.doc.combineSolids(below, above, BoolOp.UNION), "the union builds: ${ed.doc.note}")
        assertManifold(meshOf(union), "a union across the tessellation floor")
    }

    /**
     * **A coaxial same-radius union keeps its curved wall watertight, and fills the polygon it tessellates
     * to.** Two r=200 cylinders about one axis, overlapping in height: the union is one r=200 cylinder of the
     * combined extent, whose side wall is where the two solids' *same-radius* faces meet — the exact case the
     * pure-function-of-radius invariant is meant to protect. Its volume is the inscribed prism's, which
     * **undershoots** πr²h by the chord deficit and by no more than it: a coarser wall that had cracked or
     * mismatched would not sit cleanly inside that band.
     */
    @Test
    fun aCoaxialSameRadiusUnionFillsItsInscribedPrism() {
        val ed = Editor()
        val a = cylinder(ed, "200", "30", at = Vec2(0.0, 0.0))
        val b = cylinder(ed, "200", "50", at = Vec2(0.0, 0.0))
        // b starts at the same base and is taller, so the union is one r=200, h=50 cylinder
        val union = assertNotNull(ed.doc.combineSolids(a, b, BoolOp.UNION), "the union builds: ${ed.doc.note}")
        val mesh = meshOf(union)
        assertManifold(mesh, "the coaxial same-radius union")

        // the inscribed regular n-gon of a circle r has area (n/2) r² sin(2π/n); with n the chord count the
        // rule picks for r=200, the prism volume is that area × height. The mesh must fill exactly that — at
        // or just under the ideal πr²h, never over, and by no more than the chord deficit.
        val n = constructit.geom.GeomMath.chordSteps(200.0, 2.0 * PI, constructit.geom.GeomMath.TESS_TOL_MM)
        val inscribed = (n / 2.0) * 200.0 * 200.0 * kotlin.math.sin(2.0 * PI / n) * 50.0
        val ideal = PI * 200.0 * 200.0 * 50.0
        val vol = Geom3.volume(mesh)
        assertTrue(vol <= ideal + 1.0, "the inscribed wall does not exceed the true cylinder: $vol vs $ideal")
        assertClose(vol, inscribed, inscribed * 1e-6, "and it fills exactly the polygon the rule tessellates to")
        assertTrue(vol > ideal * 0.99, "and is within 1% of the true volume at this fineness ($n sides)")
    }
}
