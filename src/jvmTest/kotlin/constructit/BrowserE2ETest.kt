package constructit

import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertFalse
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
            // ...and the general boolean engine's own line (OP-9), which says whether the WASM module
            // came up. This page is opened over `file:`, where the browser refuses to load an ES module
            // at all, so what is asserted is the *contract*: the engine reports itself either way and the
            // app carries on — an unavailable engine is a reason on a node, never a broken shell (OP-3).
            val meshBoolLines = ArrayList<String>()
            page.onConsoleMessage { if (it.text().startsWith("[MeshBool]")) meshBoolLines.add(it.text()) }
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
            page.click("#g-add") // opens the shared create dialog (OP-16), defaulting to a plain group
            page.fill("#cd-name", "shell")
            page.click("#cd-ok")
            assertTrue(page.querySelectorAll("#groups-list .grow").size == 1, "the group should appear in the panel")
            val note = page.querySelector("#status").textContent()
            assertTrue(note == "Grouped $itemsBuilt elements as shell", "got: $note")
            page.screenshot(Page.ScreenshotOptions().setPath(Paths.get("build/e2e/04-grouped.png")))

            // Visibility is a **recorded step** now (OP-18's reversal), and the shell's two routes into it
            // are the group's toggle and the selection buttons. Cheap to check here and only here for the
            // one thing headless cannot see: the pixels actually go away, and one undo brings them back.
            val shown = page.evaluate("() => document.querySelector('#canvas').toDataURL()") as String
            page.click("#groups-list .grow .gvis")
            assertTrue(page.querySelector("#status").textContent().contains("hidden"), "the toggle says so")
            assertTrue(
                (page.evaluate("() => document.querySelector('#canvas').toDataURL()") as String) != shown,
                "hiding a group must clear it from the canvas",
            )
            val gone = page.evaluate("() => document.querySelector('#canvas').toDataURL()") as String
            page.click("#e-undo")
            assertTrue(
                (page.evaluate("() => document.querySelector('#canvas').toDataURL()") as String) != gone,
                "and hiding is one undo step, like every other operation",
            )
            assertTrue(
                page.querySelector("#groups-list .grow .gvis").textContent() == "◉",
                "the panel's toggle follows the undone state",
            )

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

            // ---- and back down again (OP-17): a section into 2D, and a 3D measurement in the panel ----
            // Cheap, and only the shell can say it: the section must appear as an ordinary area in the
            // tree, and the measured height as a read-only row in the measurement list.
            val areasBefore = page.querySelectorAll("#tree .item").map { it.textContent() }.count { it.startsWith("area") }
            page.fill("#p-name", "cutz")
            page.fill("#p-value", "5")
            page.click("#p-add")
            page.click("#tool-section")
            page.mouse().click(wx + 20.0, (wy1 + wy2) / 2) // the solid, by its footprint hint
            assertTrue(
                page.querySelectorAll("#tree .item").map { it.textContent() }.count { it.startsWith("area") } == areasBefore + 1,
                "Section should add one area — a solid's cross-section is ordinary 2D geometry",
            )
            page.click("#tool-mextentz")
            page.mouse().click(wx + 20.0, (wy1 + wy2) / 2)
            val measured = page.querySelectorAll("#measure-list .mrow").map { it.textContent() }
            assertTrue(measured.any { it.startsWith("extz") && it.contains("20") }, "the solid's height should read in the panel; got: $measured")

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

            // ---- a user-defined tool (OP-6), recorded and stamped in the real browser ----
            //
            // The whole record → designate → reuse loop through the actual DOM: a small construction in an
            // empty corner, the *same* create dialog in its other mode (everything free ticked, so the two
            // points become the tool's slots), then the custom palette button stamping an instance where it
            // is clicked. Only a browser can say the palette really grew a button and the dialog really closed.
            page.click("#tool-select")
            val mx1 = box.x + box.width * 0.08
            val mx2 = box.x + box.width * 0.26
            val my = box.y + box.height * 0.62
            page.click("#tool-point")
            page.keyboard().down("Alt")
            page.mouse().click(mx1, my)
            page.mouse().click(mx2, my)
            page.keyboard().up("Alt")
            page.click("#tool-segment")
            page.mouse().click(mx1, my)
            page.mouse().click(mx2, my)

            page.click("#tool-select")
            page.mouse().move(mx1 - 25.0, my - 25.0)
            page.mouse().down()
            page.mouse().move(mx2 + 25.0, my + 25.0)
            page.mouse().up()
            page.click("#g-tool")
            page.fill("#cd-name", "widget")
            assertTrue(page.querySelectorAll("#create-dialog input[type=checkbox]").size >= 2, "both free points are candidates")
            page.click("#cd-ok")
            assertTrue(page.querySelector("#create-dialog").innerHTML().isEmpty(), "the dialog closes on Create")
            assertTrue(page.querySelectorAll("#macros-list .trow").size == 1, "the tool appears in the panel")
            assertTrue(page.querySelector("#tool-macro\\:widget") != null, "and gets a palette button of its own")

            val beforeInstance = page.querySelectorAll("#tree .item").size
            page.click("#tool-macro\\:widget")
            page.mouse().click(mx1, my + box.height * 0.12)
            page.mouse().click(mx2, my + box.height * 0.12)
            assertTrue(
                page.querySelectorAll("#tree .item").size > beforeInstance,
                "clicking the custom tool's slots should build an instance",
            )
            val row = page.querySelectorAll("#macros-list .trow").first().textContent()
            assertTrue(row.contains("1×"), "and the panel should count it; got: $row")
            page.screenshot(Page.ScreenshotOptions().setPath(Paths.get("build/e2e/07-custom-tool.png")))

            assertTrue(
                meshBoolLines.size == 1 && meshBoolLines[0].contains("Manifold"),
                "the general boolean engine should report itself exactly once (OP-9); got: $meshBoolLines",
            )
            assertTrue(errors.isEmpty(), "the shell threw: $errors")
            browser.close()
        }
    }

    /**
     * **The architect workflow (W2 of `ClickBudgetTest`) driven the improved way, in real Chrome:** tool
     * keys instead of palette trips, and every scalar *typed into the flow* instead of built in the panel
     * first.
     *
     * Only a browser can answer the two questions this is here for. (1) Do the shell's real focus rules let
     * the canvas have the keyboard — and do the panel's own inputs still get their characters, including the
     * letters that arm tools? (2) Does the whole chain still end in pixels: a plan drawn in Canvas2D and a
     * cut storey drawn by WebGL. Screenshots of both land in `build/e2e/`.
     */
    @Test
    fun architectFlowByKeyboardInBrowser() {
        assumeTrue(System.getProperty("e2e") == "1", "browser E2E disabled (run with -De2e=1)")

        val index = File("build/dist/js/productionExecutable/index.html")
        assertTrue(index.exists(), "run ./gradlew jsBrowserDistribution first")
        File("build/e2e").mkdirs()

        Playwright.create().use { pw ->
            val browser = pw.chromium().launch(BrowserType.LaunchOptions().setChannel("chrome").setHeadless(true))
            val page = browser.newPage()
            val errors = ArrayList<String>()
            page.onPageError { errors.add(it) }
            page.setViewportSize(1000, 700)
            page.navigate(index.toURI().toString())
            page.waitForSelector("#canvas")

            val box = page.querySelector("#canvas").boundingBox()
            val x0 = box.x + box.width * 0.2
            val x1 = box.x + box.width * 0.8
            val y0 = box.y + box.height * 0.2
            val y1 = box.y + box.height * 0.75
            val midX = (x0 + x1) / 2
            val midY = (y0 + y1) / 2

            fun status(): String = page.querySelector("#status").textContent()

            // a parameter's name is an editable field now (OP-7), so its *value* is the name — read through
            // one expression that also covers the rows whose name the file cannot carry (still spans)
            @Suppress("UNCHECKED_CAST")
            fun params(): List<String> =
                (
                    page.evaluate(
                        "() => [...document.querySelectorAll('#params-list .pname')].map(e => e.value ?? e.textContent)",
                    ) as List<Any?>
                ).map { it?.toString() ?: "" }

            fun tree(): List<String> = page.querySelectorAll("#tree .item").map { it.textContent() }

            fun activeTool(): String? = page.querySelector(".tool.active")?.getAttribute("data-tool")

            // the palette shows the keys, or nobody would know they exist
            assertTrue(page.querySelectorAll("#palette .tkey").size >= 10, "every shortcut is labelled on its button")

            // ---- the wall ring: one key for the tool, one typed number for the thickness ----
            page.keyboard().press("w")
            assertTrue(activeTool() == "wall", "W arms the wall tool; got ${activeTool()}")
            page.keyboard().type("10")
            assertTrue(status().contains("thickness = 10"), "the typed entry echoes in the status line; got: ${status()}")
            page.keyboard().press("Enter")
            assertTrue(params().contains("thickness"), "typing it created the parameter; got ${params()}")

            page.mouse().click(x0, y0)
            page.mouse().click(x1, y0 + 3.0)
            page.mouse().click(x1 - 3.0, y1)
            page.mouse().click(x0 + 3.0, y1)
            page.mouse().click(x0, y0) // back on the start: the ring closes and the footprint appears
            assertTrue(tree().any { it.startsWith("area") }, "the closed wall ring has a footprint; got ${tree()}")
            // a run that ends on something says so in the real shell too — the half of the terminal cue that
            // is words; the other half is the mark on the canvas, which the golden covers
            assertTrue(status().contains("the run is finished"), "closing a ring ends the run out loud; got: ${status()}")
            page.screenshot(Page.ScreenshotOptions().setPath(Paths.get("build/e2e/08-typed-plan.png")))

            // ---- the storey, and a door in the south wall ----
            page.keyboard().press("e")
            assertTrue(activeTool() == "extrude", "E arms extrude; got ${activeTool()}")
            page.keyboard().type("30")
            page.keyboard().press("Enter")
            page.mouse().click(x0 - 20.0, midY) // the footprint's outer west face
            assertTrue(tree().count { it.startsWith("solid") } == 1, "the storey is a solid; got ${tree()}")

            page.keyboard().press("d")
            assertTrue(activeTool() == "opening", "D arms the opening tool; got ${activeTool()}")
            page.keyboard().type("20")
            page.keyboard().press("Enter")
            page.mouse().click(midX, y1) // on the south wall's centreline
            assertTrue(status().contains("Opening added"), "got: ${status()}")

            page.click("#tool-cutopenings") // no key of its own: the palette is still the way in
            page.mouse().click(x0 - 20.0, midY)
            assertTrue(tree().count { it.startsWith("solid") } == 2, "cutting the opening makes one more solid; got ${tree()}")

            // ---- the panel must still receive typing, letters included ----
            //
            // The keydown seam is on the document, so this is the one thing that could not be checked
            // headlessly: a name field must take "wall" without W arming the wall tool underneath it.
            page.fill("#p-name", "") // focus it and clear the default, then type as a user would
            page.keyboard().type("wall")
            assertTrue(
                (page.querySelector("#p-name").inputValue()) == "wall",
                "the panel input keeps its characters; got ${page.querySelector("#p-name").inputValue()}",
            )
            assertTrue(activeTool() == "cutopenings", "and no letter reached the tool palette; got ${activeTool()}")
            page.fill("#p-value", "50")
            page.click("#p-add")
            assertTrue(params().contains("wall"), "a panel parameter still works exactly as before; got ${params()}")

            // ---- the panel's own two edits: rename in place, and the native spinner (OP-7 / OP-13) ----
            //
            // Real-DOM rules only a browser can state: a name field commits on Enter *without* the letters
            // in it arming tools underneath, and the browser's own number spinner reaches the geometry —
            // live per tick, with the committed change as one undo step.
            val nameField = page.querySelector("#params-list input.pname[value='thickness']")
            assertTrue(nameField != null, "the wall thickness is renameable in place; got ${params()}")
            val sid = nameField.getAttribute("data-sid")
            nameField.fill("wall-t")
            page.keyboard().press("Enter")
            assertTrue(params().contains("wall-t"), "the rename commits in place; got ${params()}")
            assertFalse(params().contains("thickness"), "and the old name is gone; got ${params()}")
            assertTrue(activeTool() == "cutopenings", "typing a name must arm no tool; got ${activeTool()}")

            val valField = page.querySelector("#params-list .pval[data-sid='$sid']")
            assertTrue(valField.getAttribute("type") == "number", "a value field is a native number field")
            assertTrue(valField.getAttribute("step") == "1", "nudged by 1 mm")
            val thin = page.evaluate("() => document.querySelector('#canvas').toDataURL()") as String
            valField.click()
            page.keyboard().press("ArrowUp")
            assertTrue(valField.inputValue() == "11", "ArrowUp nudges the field; got ${valField.inputValue()}")
            assertTrue(
                (page.evaluate("() => document.querySelector('#canvas').toDataURL()") as String) != thin,
                "a spinner tick must reach the drawing live",
            )
            // the button, not Ctrl+Z: the shortcut deliberately does not fire from inside a panel field
            page.click("#e-undo")
            val back = page.querySelector("#params-list input.pname[value='wall-t']").getAttribute("data-sid")
            assertTrue(
                page.querySelector("#params-list .pval[data-sid='$back']").inputValue() == "10",
                "one undo puts the nudged value back, and the rename before it stands",
            )

            // ---- the whole storey under one turned frame (OP-16's ortho-path bonus) ----
            //
            // Grouping a wall used to be as far as it went ("it owns no free point"); now the frame carries
            // the carrier path, so the plan turns as a rigid body — which only pixels can really vouch for.
            page.click("#tool-select")
            page.mouse().move(box.x + box.width * 0.05, box.y + box.height * 0.95)
            page.mouse().down()
            page.mouse().move(box.x + box.width * 0.95, box.y + box.height * 0.05)
            page.mouse().up()
            page.click("#g-add")
            page.fill("#cd-name", "storey")
            page.click("#cd-ok")
            page.click("#groups-list .grow .gplace") // ⌖ places it: one frame for the lot
            assertTrue(status().contains("path"), "the frame reports the captured path; got: ${status()}")
            val frameFields = page.querySelectorAll("#inspector .flabel").map { it.textContent() }
            assertTrue(frameFields == listOf("x", "y", "angle"), "the placed group addresses its frame; got $frameFields")

            val straight = page.evaluate("() => document.querySelector('#canvas').toDataURL()") as String
            page.querySelectorAll("#inspector .fval")[2].fill("30")
            page.keyboard().press("Enter")
            assertTrue(
                (page.evaluate("() => document.querySelector('#canvas').toDataURL()") as String) != straight,
                "typing the frame's angle should turn the plan on canvas",
            )
            page.screenshot(Page.ScreenshotOptions().setPath(Paths.get("build/e2e/10-turned-frame.png")))

            // ---- and it ends in pixels, in both views ----
            page.click("#v-3d")
            page.waitForSelector("#canvas3:visible")
            val drawn = page.evaluate("() => document.querySelector('#canvas3').toDataURL()") as String
            page.screenshot(Page.ScreenshotOptions().setPath(Paths.get("build/e2e/09-typed-storey-3d.png")))
            page.click("#v-2d")
            page.waitForSelector("#canvas:visible")
            val blank =
                page.evaluate(
                    "() => { const c = document.createElement('canvas'); " +
                        "c.width = document.querySelector('#canvas3').width; c.height = document.querySelector('#canvas3').height; " +
                        "return c.toDataURL(); }",
                ) as String
            assertTrue(drawn != blank, "the 3D view should have drawn the cut storey")

            assertTrue(errors.isEmpty(), "the shell threw: $errors")
            browser.close()
        }
    }
}
