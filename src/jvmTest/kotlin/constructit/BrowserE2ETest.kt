package constructit

import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * End-to-end browser test of the real shell, driving the system Chrome via Playwright.
 * Gated behind -De2e=1 (and requires `./gradlew jsBrowserDistribution` first) so ordinary
 * `jvmTest` runs need no browser. Produces screenshots under build/e2e/ for visual inspection.
 */
class BrowserE2ETest {
    @Test
    fun buildAndDragInBrowser() {
        assumeTrue(System.getProperty("e2e") == "1", "browser E2E disabled (run with -De2e=1)")

        val index = File("build/dist/js/productionExecutable/index.html")
        assertTrue(index.exists(), "run ./gradlew jsBrowserDistribution first")
        File("build/e2e").mkdirs()

        Playwright.create().use { pw ->
            val browser =
                pw.chromium().launch(
                    BrowserType.LaunchOptions().setChannel("chrome").setHeadless(true),
                )
            val page = browser.newPage()
            // uncaught exceptions only, so an unrelated console warning cannot fail the run
            val errors = ArrayList<String>()
            page.onPageError { errors.add(it) }
            page.setViewportSize(1000, 700)
            page.navigate(index.toURI().toString())
            page.waitForSelector("#canvas")

            val box = page.querySelector("#canvas").boundingBox()
            val y = box.y + box.height * 0.5
            val p1x = box.x + box.width * 0.35
            val p2x = box.x + box.width * 0.65
            val midx = (p1x + p2x) / 2

            page.screenshot(Page.ScreenshotOptions().setPath(Paths.get("build/e2e/01-empty.png")))

            // Point tool: two base points
            page.click("#tool-point")
            page.mouse().click(p1x, y)
            page.mouse().click(p2x, y)
            assertTrue(page.querySelectorAll(".item").size >= 2, "two points should appear in the tree")

            // Line tool: reuse the two points
            page.click("#tool-line")
            page.mouse().click(p1x, y)
            page.mouse().click(p2x, y)

            // Circle tool: centre between them, through the right point
            page.click("#tool-circle")
            page.mouse().click(midx, y)
            page.mouse().click(p2x, y)
            page.screenshot(Page.ScreenshotOptions().setPath(Paths.get("build/e2e/02-built.png")))

            val itemsBuilt = page.querySelectorAll(".item").size

            // Select tool: drag the first base point upward — the line + circle must follow live
            page.click("#tool-select")
            page.mouse().move(p1x, y)
            page.mouse().down()
            page.mouse().move(p1x, y - 120.0)
            page.mouse().move(p1x, y - 160.0)
            page.mouse().up()
            page.screenshot(Page.ScreenshotOptions().setPath(Paths.get("build/e2e/03-dragged.png")))

            // dragging must not create/destroy elements
            assertTrue(page.querySelectorAll(".item").size == itemsBuilt, "drag must not change element count")

            // Space+drag pans (OP-16): the shell's own key mapping, so only a real browser can check it.
            // A drag from empty space *without* Space would rubber-band and change nothing on release, so
            // a canvas that differs afterwards means the view moved.
            val emptyX = box.x + box.width * 0.08
            val emptyY = box.y + box.height * 0.9
            val before = page.evaluate("() => document.querySelector('#canvas').toDataURL()") as String
            page.keyboard().down("Space")
            page.mouse().move(emptyX, emptyY)
            page.mouse().down()
            page.mouse().move(emptyX + 60.0, emptyY - 30.0)
            page.mouse().up()
            page.keyboard().up("Space")
            val after = page.evaluate("() => document.querySelector('#canvas').toDataURL()") as String
            assertTrue(before != after, "Space+drag should pan the view")
            assertTrue(page.querySelectorAll(".item").size == itemsBuilt, "panning must not change the drawing")

            // marquee + grouping through the panel, end to end: a box across the whole canvas takes
            // everything, and the Group button turns that selection into a named group (OP-16)
            page.mouse().move(box.x + box.width * 0.05, box.y + box.height * 0.95)
            page.mouse().down()
            page.mouse().move(box.x + box.width * 0.95, box.y + box.height * 0.05)
            page.mouse().up()
            page.fill("#g-name", "shell")
            page.click("#g-add")
            assertTrue(page.querySelectorAll("#groups-list .grow").size == 1, "the group should appear in the panel")
            val note = page.querySelector("#status").textContent()
            assertTrue(note == "Grouped $itemsBuilt elements as shell", "got: $note")
            page.screenshot(Page.ScreenshotOptions().setPath(Paths.get("build/e2e/04-grouped.png")))

            // A linear dimension over the two base points (OP-4). The canvas text primitive exists only
            // in the browser backend, so this is the one place it can be exercised at all — and the
            // dimension is placed *after* grouping, so clicking it addresses the annotation alone.
            val dx1 = box.x + box.width * 0.2
            val dx2 = box.x + box.width * 0.5
            val dy = box.y + box.height * 0.85
            // Alt places freely, so the two points land exactly under the cursor and the very same pixels
            // pick them again for the dimension's existing-point slots
            page.click("#tool-point")
            page.keyboard().down("Alt")
            page.mouse().click(dx1, dy)
            page.mouse().click(dx2, dy)
            page.keyboard().up("Alt")
            page.click("#tool-dimlinear")
            page.mouse().click(dx1, dy)
            page.mouse().click(dx2, dy)
            page.mouse().click((dx1 + dx2) / 2, dy - 60.0) // where the dimension line goes
            page.click("#tool-select")
            page.mouse().click((dx1 + dx2) / 2, dy - 60.0)
            val fieldLabels = page.querySelectorAll(".flabel").map { it.textContent() }
            assertTrue(fieldLabels.contains("offset"), "the dimension's own DOF is a panel field; got: $fieldLabels")
            assertTrue(fieldLabels.contains("distance"), "and its measured value reads beside it; got: $fieldLabels")
            page.screenshot(Page.ScreenshotOptions().setPath(Paths.get("build/e2e/05-dimension.png")))

            // ---- the 3D view (OP-17 + OP-12): a wall, extruded, then orbited in the WebGL canvas ----
            //
            // Only a real browser can say whether WebGL produced pixels at all, which is exactly the part
            // the headless painter's projector cannot vouch for. The construction itself is covered by
            // SolidToolTest; this checks the *shell*: the toggle, the second canvas, and an orbit drag.
            page.click("#tool-select")
            page.fill("#p-name", "t")
            page.fill("#p-value", "10")
            page.click("#p-add")
            page.click("#tool-wall")
            // well clear of everything drawn so far, so no vertex snaps onto it
            val wx = box.x + box.width * 0.85
            val wy1 = box.y + box.height * 0.15
            val wy2 = box.y + box.height * 0.45
            page.mouse().click(wx, wy1)
            page.mouse().click(wx + 3.0, wy2)
            page.keyboard().press("Escape")
            page.fill("#p-name", "depth")
            page.fill("#p-value", "20")
            page.click("#p-add")
            page.click("#tool-extrude")
            // an area is picked by its *boundary*, so the click goes on a face: half of the 10 mm
            // thickness off the centreline, at the default 4 px/mm
            page.mouse().click(wx + 20.0, (wy1 + wy2) / 2)
            assertTrue(
                page.querySelectorAll("#tree .item").map { it.textContent() }.any { it.startsWith("solid") },
                "the extrude tool should add a solid to the tree",
            )

            // ---- an opening, then Cut openings (OP-22): the boolean reached from the real shell ----
            page.fill("#p-name", "w")
            page.fill("#p-value", "20")
            page.click("#p-add")
            page.click("#tool-opening")
            page.mouse().click(wx + 2.0, (wy1 + wy2) / 2) // on the wall itself
            page.click("#tool-cutopenings")
            page.mouse().click(wx + 20.0, (wy1 + wy2) / 2) // the solid, by its footprint hint
            assertTrue(
                page.querySelectorAll("#tree .item").map { it.textContent() }.count { it.startsWith("solid") } == 2,
                "Cut openings should add one more solid: the wall with its opening subtracted",
            )

            page.click("#v-3d")
            page.waitForSelector("#canvas3:visible")
            val blank = page.evaluate("() => document.querySelector('#canvas3').toDataURL()") as String
            val cx3 = box.x + box.width * 0.5
            val cy3 = box.y + box.height * 0.5
            page.mouse().move(cx3, cy3)
            page.mouse().down()
            page.mouse().move(cx3 + 90.0, cy3 - 40.0)
            page.mouse().up()
            val orbited = page.evaluate("() => document.querySelector('#canvas3').toDataURL()") as String
            assertTrue(blank != orbited, "dragging in the 3D view should orbit the camera and redraw")
            page.screenshot(Page.ScreenshotOptions().setPath(Paths.get("build/e2e/06-solid-3d.png")))
            page.click("#v-2d")
            page.waitForSelector("#canvas:visible")

            assertTrue(errors.isEmpty(), "the shell threw: $errors")
            browser.close()
        }
    }
}
