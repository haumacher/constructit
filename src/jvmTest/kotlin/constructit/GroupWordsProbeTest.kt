package constructit

import constructit.editor.CreateMode
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Tools
import constructit.geom.Vec2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The reviewer's probe for the group-creation words (session 55): the package was built against a
 * *reconstruction* of the reported drawing — this probe runs the user's own script **verbatim**, whose
 * molding carries what the reconstruction did not: riders (`pointoncurve`), a relative point (`makerel`),
 * fillets and section inputs. Whatever node kinds their conflicts surface, the naming authority holds:
 * **no token a user sees is anything but a name the drawing gave.**
 */
class GroupWordsProbeTest {
    private fun Editor.marquee(
        a: Vec2,
        b: Vec2,
    ) {
        setTool(Tools.SELECT)
        pointerDown(camera.worldToScreen(a))
        pointerMove(camera.worldToScreen(b))
        pointerUp(camera.worldToScreen(b))
    }

    /** Every `word+digits` token of [text] that is not a name the document knows. */
    private fun strayIds(
        ed: Editor,
        text: String,
    ): List<String> {
        val known =
            ed.doc.elements.map { ed.doc.nameOf(it) }.toHashSet() +
                ed.doc.spaces.map { it.name } + ed.doc.groups.map { it.name } +
                ed.doc.scalars.map { it.name }
        return Regex("\\b[a-z]{1,4}\\d+\\b").findAll(text).map { it.value }.filter { it !in known }.toList()
    }

    @Test
    fun theUsersOwnMoldingGroupsInTheDrawingsLanguageAndTheClosureMakesItPlaceable() {
        val ed = Editor(DocumentFormat.load(MOLDING_CIT))
        ed.setActiveSpace("plane1")
        ed.marquee(Vec2(10.0, -10.0), Vec2(80.0, 45.0))
        assertTrue(ed.selectionCount > 3, "the marquee takes the molding: ${ed.selectionCount}")

        // round 1: framed by default, the frame honestly refused — flat, said in the drawing's words
        val d1 = assertNotNull(ed.beginCreate(CreateMode.GROUP))
        d1.name = "base"
        assertTrue(ed.confirmCreate(), ed.statusHint)
        val flat = ed.doc.groups.last()
        assertEquals("base", flat.name)
        assertTrue(ed.statusHint.startsWith("Grouped"), "creation leads with what succeeded: ${ed.statusHint}")
        assertEquals(emptyList(), strayIds(ed, ed.statusHint), "no internal id reaches the user: ${ed.statusHint}")
        for (w in ed.doc.placementWarnings(flat)) {
            assertEquals(emptyList(), strayIds(ed, w), "nor through the warnings: $w")
        }

        assertTrue(ed.undo(), "take the flat group back")

        // round 2: the same marquee, with the dialog's closure taken — the group places
        ed.setActiveSpace("plane1")
        ed.marquee(Vec2(10.0, -10.0), Vec2(80.0, 45.0))
        val d2 = assertNotNull(ed.beginCreate(CreateMode.GROUP))
        d2.name = "base"
        if (d2.hasClosure) {
            val before = ed.selectionCount
            assertTrue(ed.includeCreateClosure(), "the closure is one action: ${ed.statusHint}")
            assertTrue(d2.closureTaken, "and the dialog says it was taken")
            assertTrue(d2.members.size > before, "membership grew by the closure")
        }
        assertTrue(ed.confirmCreate(), ed.statusHint)
        val g = ed.doc.groups.last()
        assertEquals(emptyList(), strayIds(ed, ed.statusHint), "the second answer is also in the drawing's words: ${ed.statusHint}")
        assertTrue(g.placed, "with the closure in, the group is placeable: ${ed.statusHint}")

        // the frame moves the figure rigidly, and the drawing is a file
        val frame = assertNotNull(g.frameHandle, "a placed group has a frame")
        frame.drag(Vec2(30.0, 10.0), constructit.core.Evaluator())
        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "save → load → save is byte-equal after the frame moves")
    }

    companion object {
        /** The user's pillar-and-molding drawing, verbatim from the report of session 55. */
        val MOLDING_CIT = """constructit 2
orthostart -36,21.680818540354267 -> e1
orthovertex 15.395828291524595,21.680818540354267 -> e2,e3
orthovertex 15.395828291524595,-23.5 -> e4,e5
orthovertex -36,-23.5 -> e6,e7
orthoclose -> e8
param "r" = 200mm
tool extrude els=e7 clicks=-6.75,-23.5 scalar="r" -> e9
tool perpbis pts=e1,e2 clicks=-35,32.25;22.25,32.25 -> e10
param "angle" = 90deg
sketchspace "plane1" line=e10 angle="angle"
sectioninput "plane1" el=e9 edge=3 -> e11
tool keypoints els=e11 clicks=31.667210440456785,44.143556280587276 -> e12,e13
sectioninput "plane1" el=e9 edge=4 -> e14
tool keypoints els=e14 clicks=14.179445350734108,0.032626427406199025 -> e15,e16
tool line pts=e16,e15 clicks=-24.189233278955943,-0.4893964110929853;30.623164763458416,0.2936378466557912 -> e17
pointoncurve e17 54.37520391517131,0 dofs=33.16070346923052mm -> e18
tool segment pts=e15,e18 clicks=31.928221859706376,-0.4893964110929853;54.375203915171305,0.5546492659053834 -> e19
pointoncurve e11 31.5,24.30668841761826 dofs=-17.880689101491953mm -> e20
tool perp pts=e18 els=e17 clicks=83.08646003262645,0.032626427406199025;54.636215334420896,0.5546492659053834 -> e21
pointoncurve e21 54.37520391517131,14.38825448613377 dofs=10.055509683729293mm -> e22
tool segment pts=e18,e22 clicks=55.68026101141926,-0.7504078303425775;53.592169657422524,14.38825448613377 -> e23
tool segment pts=e22,e20 clicks=53.592169657422524,14.38825448613377;30.36215334420882,24.567699836867863 -> e24
tool segment pts=e20,e15 clicks=32.45024469820556,22.479608482871125;32.45024469820556,0.032626427406199025 -> e25
tool outline els=e25,e24,e23,e19 clicks=32.18923327895597,9.951060358890702;41.585644371941285,19.869494290375204;54.37520391517131,7.194127243066885;42.937601957585656,0 -> e26,e27,e28,e29,e30
space "plan"
tool curve3 els=e6,e4,e2,e1,e6 clicks=-35.75,-24.5;21.75,-23.75;22,31.5;-36.25,30.25;-36,-25 -> e31
space "plane1"
tool makerel els=e18,e15 clicks=43.18857981128731,0.2107641895467509;31.205913150809764,0.5235088309397611 dofs=11.479884928876253mm
space "plan"
name e31 "border"
"""
    }
}
