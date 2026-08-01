package constructit

import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import constructit.editor.Tools
import constructit.geom.Vec2
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
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
            // **A live tool preview, in real Chrome** (`ToolDef.preview`): with the centre in, the growing
            // circle has to be on the canvas *before* the second click, and gone again after it. Asserted on
            // the pixels, because that is the only thing a browser can be asked about a canvas — and against
            // the *idle* count rather than against zero, since the accent colour is a palette colour that
            // other marks (here the rider dot the centre click landed on) also use.
            val idle = previewPixels(page)
            page.mouse().move(p2x, y)
            page.screenshot(Page.ScreenshotOptions().setPath(Paths.get("build/e2e/02-preview.png")))
            val hovering = previewPixels(page)
            assertTrue(hovering > idle + 50, "the growing circle should be painted while hovering ($idle -> $hovering)")
            page.mouse().click(p2x, y)
            assertTrue(previewPixels(page) <= idle, "…and gone once the click has built it")
            page.screenshot(Page.ScreenshotOptions().setPath(Paths.get("build/e2e/02-built.png")))

            // A **defaulted** scalar slot, in the real shell (OP-13): Midpoint still takes exactly two clicks,
            // and a number typed before them turns the same gesture into a ratio point whose factor is an
            // ordinary panel parameter. Only a browser can show that the digits reach the tool through the
            // document's own keydown seam while the palette stays where it was.
            page.click("#tool-midpoint")
            page.keyboard().type(".25")
            assertTrue(
                page.querySelector("#status").textContent().contains("factor = .25"),
                "the typed factor echoes; got: ${page.querySelector("#status").textContent()}",
            )
            page.keyboard().press("Enter")
            page.mouse().click(p1x, y)
            page.mouse().click(p2x, y)
            @Suppress("UNCHECKED_CAST")
            val names =
                (
                    page.evaluate(
                        "() => [...document.querySelectorAll('#params-list .pname')].map(e => e.value ?? e.textContent)",
                    ) as List<Any?>
                ).map { it?.toString() ?: "" }
            assertTrue(names.contains("factor"), "typing it created the parameter; got $names")

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
            page.click("#g-add") // opens the shared create dialog (OP-16)
            page.fill("#cd-name", "shell")
            // …whose frame tick is **on** by default. Untick it here: what this flow goes on to do is make a
            // *tool* out of the same geometry, and a placed group's members cannot be one (OP-6). That is the
            // flat group's other purpose, so unticking is the ordinary route, not a workaround.
            assertTrue(page.querySelector("#cd-framed").isChecked(), "the frame tick defaults to on (OP-16)")
            page.click("#cd-framed")
            page.click("#cd-ok")
            assertTrue(page.querySelectorAll("#groups-list .grow").size == 1, "the group should appear in the panel")
            val note = page.querySelector("#status").textContent()
            assertTrue(note == "Grouped $itemsBuilt elements as shell — a named set, with no frame", "got: $note")
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

            // ---- and the opening is editable where it is drawn (OP-21 + OP-13) ----
            //
            // One drag of a jamb in real Chrome: the wall runs down the screen at 4 px/mm, so the opening's
            // two jambs cross it 10 mm (40 px) either side of the click that placed it, and the wall's 10 mm
            // thickness puts its faces 20 px out. Grabbing at +14 px is therefore on a jamb and clear of the
            // carrier leg, which is the pick rule this exercises through the real DOM.
            page.click("#tool-select")
            val jambY = (wy1 + wy2) / 2 - 40.0
            page.mouse().move(wx + 14.0, jambY)
            page.mouse().down()
            page.mouse().move(wx + 14.0, jambY - 20.0)
            page.mouse().move(wx + 14.0, jambY - 40.0)
            page.mouse().up()
            val jambNote = page.querySelector("#status").textContent()
            assertTrue(jambNote.startsWith("Opening at"), "dragging a jamb should report the opening; got: $jambNote")
            val openingFields = page.querySelectorAll(".flabel").map { it.textContent() }
            assertTrue(
                openingFields.containsAll(listOf("position", "width", "sill", "head")),
                "the opening's own values are the inspector's rows; got: $openingFields",
            )
            page.screenshot(Page.ScreenshotOptions().setPath(Paths.get("build/e2e/07-opening-dragged.png")))

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

            // ---- the drawing's name, and Save through the fallback (OP-18: the name is shell state) ----
            //
            // Over `file:` the File System Access API is deliberately off (see Main.kt), so this exercises
            // exactly the path every other browser takes: the anchor download, named from the field. Only a
            // browser has an anchor, a download attribute or that field at all.
            assertTrue(page.querySelector("#f-name").inputValue() == "drawing", "the default name is in the topbar")
            page.fill("#f-name", " plans/west wing ")
            page.click("#f-download")
            assertTrue(
                page.querySelector("#f-anchor").getAttribute("download") == "west wing.cit",
                "Save names the file from the field, normalised; got: ${page.querySelector("#f-anchor").getAttribute("download")}",
            )
            assertTrue(
                page.querySelector("#file-note").textContent() == "Saved west wing.cit",
                "and says so; got: ${page.querySelector("#file-note").textContent()}",
            )
            assertTrue(page.querySelector("#f-name").inputValue() == "west wing", "the field shows the name it took")

            // ---- a circular array over the whole group, fed from the groups panel (OP-16) ----
            //
            // The panel route, end to end in the DOM: with the tool armed, the group's *row* fills the
            // geometry slot, and the remaining click lands on the canvas as usual.
            val itemsBeforeArray = page.querySelectorAll("#tree .item").size
            val members = page.querySelector("#groups-list .grow .gcount").textContent().toInt()
            page.fill("#t-count", "2")
            page.querySelector("#t-count").press("Enter")
            page.click("#tool-arraycircular")
            assertTrue(
                page.querySelector("#status").textContent().contains("count 2"),
                "the structural count is what the field says; got: ${page.querySelector("#status").textContent()}",
            )
            page.click("#groups-list .grow .gname") // the row feeds the armed geometry slot
            assertTrue(
                page.querySelector("#status").textContent().contains("is the geometry"),
                "the row should feed the slot; got: ${page.querySelector("#status").textContent()}",
            )
            page.mouse().click(box.x + box.width * 0.5, box.y + box.height * 0.05) // the centre of rotation
            assertTrue(
                page.querySelectorAll("#tree .item").size == itemsBeforeArray + members + 1,
                "one copy of every member (plus the centre point); " +
                    "got ${page.querySelectorAll("#tree .item").size - itemsBeforeArray} for $members members",
            )
            assertTrue(
                page.querySelector("#status").textContent().contains("not grouped"),
                "and the copies land ungrouped, out loud; got: ${page.querySelector("#status").textContent()}",
            )
            page.screenshot(Page.ScreenshotOptions().setPath(Paths.get("build/e2e/11-group-array.png")))

            // ---- Break on a plain segment, in the real shell ----
            //
            // Here for the one thing headless cannot check: an unconsumed break **replays the whole script**
            // (its creating step is replaced, OP-18), which swaps the document under the running shell — so
            // the tree, the panel and the canvas all have to come back rather than keep stale references.
            val bx1 = box.x + box.width * 0.12
            val bx2 = box.x + box.width * 0.34
            val by = box.y + box.height * 0.88
            page.click("#tool-point")
            page.keyboard().down("Alt") // raw clicks: no snapping onto the figure above
            page.mouse().click(bx1, by)
            page.mouse().click(bx2, by)
            page.keyboard().up("Alt")
            page.click("#tool-segment")
            page.mouse().click(bx1, by)
            page.mouse().click(bx2, by)
            val itemsBeforeBreak = page.querySelectorAll("#tree .item").size
            page.click("#tool-breakleg")
            page.mouse().click((bx1 + bx2) / 2, by)
            assertTrue(
                page.querySelector("#status").textContent().contains("split into"),
                "the break should replace the segment; got: ${page.querySelector("#status").textContent()}",
            )
            assertTrue(
                page.querySelectorAll("#tree .item").size == itemsBeforeBreak + 2,
                "one segment out, a joint point and two halves in; got " +
                    "${page.querySelectorAll("#tree .item").size - itemsBeforeBreak}",
            )
            // and the joint really is free: drag it off the line and the shell redraws
            val bent = page.evaluate("() => document.querySelector('#canvas').toDataURL()") as String
            page.click("#tool-select")
            page.mouse().move((bx1 + bx2) / 2, by)
            page.mouse().down()
            page.mouse().move((bx1 + bx2) / 2, by - 40.0)
            page.mouse().up()
            assertTrue(
                (page.evaluate("() => document.querySelector('#canvas').toDataURL()") as String) != bent,
                "dragging the joint should bend the pair",
            )
            page.screenshot(Page.ScreenshotOptions().setPath(Paths.get("build/e2e/12-broken-segment.png")))

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

            // ---- a parameter pick can be switched off, from the row that made it ----
            //
            // The DOM half of the deselect: the row is one click target for both meanings. Clicked away from
            // its own inputs — those are for typing, and focusing one *picks* the parameter rather than
            // toggling it, which is a distinction only a real browser can vouch for.
            val prow = page.querySelector("#params-list input.pname[value='wall-t']").getAttribute("data-sid")
            page.click("#params-list .prow[data-sid='$prow'] .punit")
            assertTrue(
                page.querySelector("#params-list .prow[data-sid='$prow']").getAttribute("class").contains("active"),
                "the row it was clicked on is the active parameter",
            )
            page.click("#params-list .prow[data-sid='$prow'] .punit")
            assertTrue(status().contains("no parameter active"), "clicking it again switches the pick off; got: ${status()}")
            assertFalse(
                page.querySelector("#params-list .prow[data-sid='$prow']").getAttribute("class").contains("active"),
                "and the row stops being highlighted",
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
            page.click("#cd-ok") // the frame tick is on, so this creates *and* places: one frame for the lot
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

    /**
     * **The panel polish, end to end** (queue #18): the icon palette, the inspector that does not move the
     * panel under the cursor, the dependency rows, and the two renames.
     *
     * Four of the five items are only observable in a real shell, which is why they are here rather than in
     * the headless suite. (1) The palette is really made of icon buttons, and every id and every shortcut
     * badge every other flow addresses is still there. (2) The elements list does not shift by a pixel when
     * something is selected or deselected — the assertion the fixed-height inspector exists for, and one no
     * unit test can make. (3) Hovering a name in *built from* changes the canvas, which is the whole point of
     * a spotlight. (4) A group's name and an element's name are both editable in place, and both stick.
     */
    @Test
    fun panelPolishInBrowser() {
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

            fun status(): String = page.querySelector("#status").textContent()

            fun tree(): List<String> = page.querySelectorAll("#tree .item").map { it.textContent() }

            // ---- (2) the icon palette ----
            assertTrue(page.querySelectorAll("#palette .tool.icon").size >= 30, "most tools carry a glyph")
            assertTrue(page.querySelector("#tool-point svg") != null, "and a glyph is inline SVG, not an asset")
            assertTrue(page.querySelector("#tool-fillet svg") != null, "including the operations, not just the shapes")
            assertTrue(page.querySelectorAll("#palette .tkey").size >= 10, "the shortcut badges survive the icons")
            val tip = page.querySelector("#tool-circleR").getAttribute("title")
            assertTrue(
                tip.startsWith("Circle (centre, radius)") && tip.contains("shortcut C"),
                "an icon button's tooltip carries the words: label, help and key; got: $tip",
            )
            // a tool with no legible glyph keeps its text row, which is the stated cut
            assertTrue(page.querySelectorAll("#palette .tool:not(.icon)").size > 0, "and the rest stay text rows")
            page.screenshot(Page.ScreenshotOptions().setPath(Paths.get("build/e2e/18-icon-palette.png")))

            // ---- a circle over two points, so there is something with inputs ----
            val box = page.querySelector("#canvas").boundingBox()
            val ax = box.x + box.width * 0.35
            val bx = box.x + box.width * 0.55
            val y = box.y + box.height * 0.5
            page.click("#tool-point")
            page.keyboard().down("Alt")
            page.mouse().click(ax, y)
            page.mouse().click(bx, y)
            page.keyboard().up("Alt")
            page.click("#tool-circle")
            page.mouse().click(ax, y)
            page.mouse().click(bx, y)
            page.click("#tool-select")
            assertTrue(tree().size == 3, "two points and a circle; got ${tree()}")

            // ---- (3) the inspector never moves the lists below it ----
            fun treeTop(): Double =
                (page.evaluate("() => document.querySelector('#tree').getBoundingClientRect().top") as Number).toDouble()
            val idle = treeTop()
            page.querySelectorAll("#tree .item").first { it.textContent().startsWith("circle") }.click()
            val selected = treeTop()
            assertTrue(idle == selected, "selecting must not reflow the panel ($idle -> $selected)")
            page.mouse().click(box.x + box.width * 0.9, box.y + box.height * 0.9) // deselect on empty space
            assertTrue(treeTop() == idle, "and neither must deselecting (${treeTop()} vs $idle)")

            // ---- (1) built from / used by, and the hover spotlight ----
            page.querySelectorAll("#tree .item").first { it.textContent().startsWith("circle") }.click()
            val chips = page.querySelectorAll("#inspector .drow .dep").map { it.textContent() }
            assertTrue(
                chips == listOf("centre e1", "radius point e2"),
                "the inspector names the circle's inputs with the roles the tool declares; got $chips",
            )
            val quiet = page.evaluate("() => document.querySelector('#canvas').toDataURL()") as String
            page.hover("#inspector .drow .dep")
            assertTrue(
                (page.evaluate("() => document.querySelector('#canvas').toDataURL()") as String) != quiet,
                "hovering a name should light that element up on the canvas",
            )
            page.screenshot(Page.ScreenshotOptions().setPath(Paths.get("build/e2e/19-dependencies.png")))
            page.querySelectorAll("#inspector .drow .dep").first().click()
            assertTrue(
                page.querySelector("#inspector .selname").textContent().contains("e1"),
                "…and clicking it goes there; got: ${page.querySelector("#inspector .selname").textContent()}",
            )

            // ---- (4a) an element's own name ----
            page.fill("#insp-name", "bore-axis")
            page.querySelector("#insp-name").press("Enter")
            assertTrue(
                tree().any { it.contains("bore-axis (e1)") },
                "the named element shows its label *and* its script name; got ${tree()}",
            )
            assertTrue(status().contains("\"bore-axis\""), "and the shell says so; got: ${status()}")

            // ---- (4b) a group's name, editable in the groups panel ----
            page.mouse().move(box.x + box.width * 0.05, box.y + box.height * 0.95)
            page.mouse().down()
            page.mouse().move(box.x + box.width * 0.95, box.y + box.height * 0.05)
            page.mouse().up()
            page.click("#g-add")
            page.fill("#cd-name", "kitchen")
            page.click("#cd-ok")
            assertTrue(page.querySelectorAll("#groups-list .grow").size == 1, "the group is in the panel")
            page.fill("#groups-list .grow input.gname", "larder")
            page.querySelector("#groups-list .grow input.gname").press("Enter")
            assertTrue(
                page.querySelector("#groups-list .grow input.gname").inputValue() == "larder",
                "the field shows the name it took",
            )
            assertTrue(status().contains("Renamed group kitchen to larder"), "and the shell says so; got: ${status()}")
            // the drawing still saves and the name went with it (the `place` step names the group too)
            page.click("#f-copy")
            page.screenshot(Page.ScreenshotOptions().setPath(Paths.get("build/e2e/20-renamed-group.png")))

            assertTrue(errors.isEmpty(), "the shell threw: $errors")
            browser.close()
        }
    }

    /**
     * How many canvas pixels are painted in the **preview colour** (`#ff7f0e`, `SceneRenderer.previewStyle`).
     *
     * A canvas has no DOM to query, so a preview can only be observed as pixels; the match is loose per
     * channel because the stroke is antialiased, and requires near-opacity so a faint edge does not count.
     */
    private fun previewPixels(page: Page): Int =
        page.evaluate(
            """
            () => {
              const c = document.getElementById('canvas');
              const d = c.getContext('2d').getImageData(0, 0, c.width, c.height).data;
              let n = 0;
              for (let i = 0; i < d.length; i += 4) {
                if (d[i + 3] > 200 && Math.abs(d[i] - 255) < 8 && Math.abs(d[i + 1] - 127) < 8 && Math.abs(d[i + 2] - 14) < 8) n++;
              }
              return n;
            }
            """.trimIndent(),
        ).let { (it as Number).toInt() }

    /**
     * The distribution over **http**, from the JDK's own server — because one thing in this app only works
     * over http: the general boolean engine is a WASM ES module, and a browser refuses ES modules on
     * `file:` outright (OP-9). The other test deliberately exercises that unavailable path; this one
     * exercises the engine actually running in Chrome.
     */

    private fun serve(root: File): com.sun.net.httpserver.HttpServer {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val path = ex.requestURI.path.removePrefix("/").ifEmpty { "index.html" }
            val f = File(root, path)
            if (!f.isFile) {
                ex.sendResponseHeaders(404, -1)
                ex.close()
                return@createContext
            }
            // the MIME type matters here: emscripten's glue compiles the module by streaming, which the
            // browser only does for application/wasm
            val type =
                when (f.extension) {
                    "html" -> "text/html; charset=utf-8"
                    "js" -> "text/javascript; charset=utf-8"
                    "wasm" -> "application/wasm"
                    "css" -> "text/css"
                    else -> "application/octet-stream"
                }
            val bytes = f.readBytes()
            ex.responseHeaders.add("Content-Type", type)
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        return server
    }

    /**
     * **The drill on a side face, in the real browser** (OP-17): a plate drawn and extruded in plan, the
     * *Sketch on face* tool clicked on one of its footprint edges, a circle drawn in the face view that
     * appears, and *Cut* turning it into a bore — then the 3D view, which is where the hole is visible.
     *
     * What only a browser can vouch for: the space indicator in the topbar really lists the new space and
     * switches back to the plan, the 2D canvas really shows *different* pixels per space (that is the whole
     * point of a space), and — served over http, so the WASM module can load at all — the **cross-axis
     * boolean really runs in the shell**, asynchronously, with the drawing appearing when the engine comes
     * up (OP-3's auto-heal, OP-9's seam). That last part had no browser coverage before, because until a
     * face could be named there was no way to reach a cross-axis boolean by clicking.
     */
    @Test
    fun drillOnASideFaceInBrowser() {
        assumeTrue(System.getProperty("e2e") == "1", "browser E2E disabled (run with -De2e=1)")

        val dist = File("build/dist/js/productionExecutable")
        assertTrue(File(dist, "index.html").exists(), "run ./gradlew jsBrowserDistribution first")
        File("build/e2e").mkdirs()
        val server = serve(dist)
        val url = "http://127.0.0.1:${server.address.port}/index.html"

        Playwright.create().use { pw ->
            val browser = pw.chromium().launch(BrowserType.LaunchOptions().setChannel("chrome").setHeadless(true))
            val page = browser.newPage()
            val errors = ArrayList<String>()
            page.onPageError { errors.add(it) }
            val meshBoolLines = ArrayList<String>()
            page.onConsoleMessage { if (it.text().startsWith("[MeshBool]")) meshBoolLines.add(it.text()) }
            page.setViewportSize(1000, 700)
            page.navigate(url)
            page.waitForSelector("#canvas")
            // the engine comes up after the first paint (OP-9), and a cut before it would be an ordinary
            // invalid node with that as its reason — so wait for its own line before drilling
            page.waitForCondition { meshBoolLines.isNotEmpty() }
            assertTrue(meshBoolLines.first().contains("ready"), "over http the WASM engine should come up: $meshBoolLines")

            fun tree() = page.querySelectorAll("#tree .item").map { it.textContent() }

            fun solids() = tree().count { it.startsWith("solid") }

            fun status() = page.querySelector("#status").textContent()

            val box = page.querySelector("#canvas").boundingBox()
            // the plate: a rectangle in the middle of the canvas, then 20 mm of thickness
            val rx1 = box.x + box.width * 0.30
            val rx2 = box.x + box.width * 0.70
            val ry1 = box.y + box.height * 0.35
            val ry2 = box.y + box.height * 0.60
            // the rectangle draws a closed ortho path now (GitHub issue #4), and a tool's button id is its
            // tool id — so the palette selector moved with it, while the *file's* `tool rect` did not
            page.click("#tool-${Tools.RECTANGLE}")
            page.mouse().click(rx1, ry1)
            page.mouse().click(rx2, ry2)
            page.fill("#p-name", "thickness")
            page.fill("#p-value", "20")
            page.click("#p-add")
            page.click("#tool-extrude")
            page.mouse().click((rx1 + rx2) / 2, ry2) // the plate's front edge, in plan
            assertTrue(solids() == 1, "the plate should be one solid; tree: ${tree()}")

            // ---- sketch on that edge: the 2D view becomes the side face ----
            val planPixels = page.evaluate("() => document.querySelector('#canvas').toDataURL()") as String
            page.click("#tool-sketchface")
            page.mouse().click((rx1 + rx2) / 2, ry2)
            assertTrue(
                page.querySelector("#v-space").inputValue() == "face1",
                "the topbar indicator should name the new space; got ${page.querySelector("#v-space").inputValue()}",
            )
            assertTrue(status().contains("u along the edge"), "and the convention is said out loud; got: ${status()}")
            val facePixels = page.evaluate("() => document.querySelector('#canvas').toDataURL()") as String
            assertTrue(facePixels != planPixels, "a face view draws its own space, not the plan's")

            // ---- the bore: a circle drawn *on the face*, then Cut ----
            //
            // Centre at the canvas's middle (the face view frames the face, so that is the middle of the
            // material) and a rim point 30 px to the right — a screen-defined radius, so the very same pixel
            // picks the circle again for the area slot without this test knowing the face view's scale.
            val cx = box.x + box.width * 0.5
            val cy = box.y + box.height * 0.5
            page.click("#tool-circle")
            page.mouse().click(cx, cy)
            page.mouse().click(cx + 30.0, cy)
            assertTrue(tree().any { it.startsWith("circle") }, "the circle should be drawn in the face space; tree: ${tree()}")
            page.fill("#p-name", "bore")
            page.fill("#p-value", "8")
            page.click("#p-add")
            page.click("#tool-cut")
            page.mouse().click(cx + 30.0, cy)
            page.screenshot(Page.ScreenshotOptions().setPath(Paths.get("build/e2e/11-face-sketch-2d.png")))
            assertTrue(
                solids() == 3,
                "Cut should add the drill and the cut part; tree: ${tree()}, status: ${status()}",
            )

            // ---- and the hole, in the view that can show it ----
            //
            // SELECT first, deliberately: with a tool armed the 3D view is an *editing* view and a plain drag
            // belongs to that tool (edit-in-3D slice 1), so "drag to orbit" has to say which mode it means.
            page.click("#tool-select")
            page.click("#v-3d")
            page.waitForSelector("#canvas3:visible")
            val c3 = box.x + box.width * 0.5
            val cy3 = box.y + box.height * 0.5
            page.mouse().move(c3, cy3)
            page.mouse().down()
            page.mouse().move(c3 + 70.0, cy3 - 50.0)
            page.mouse().up()
            page.screenshot(Page.ScreenshotOptions().setPath(Paths.get("build/e2e/12-face-drill-3d.png")))
            val drawn = page.evaluate("() => document.querySelector('#canvas3').toDataURL()") as String
            val blank =
                page.evaluate(
                    "() => { const c = document.createElement('canvas'); " +
                        "c.width = document.querySelector('#canvas3').width; c.height = document.querySelector('#canvas3').height; " +
                        "return c.toDataURL(); }",
                ) as String
            assertTrue(drawn != blank, "the 3D view should have drawn the part")

            // ---- back to the plan through the topbar: the same control, the other way ----
            page.selectOption("#v-space", "plan")
            page.waitForSelector("#canvas:visible")
            assertTrue(page.querySelector("#v-space").inputValue() == "plan")
            val backPixels = page.evaluate("() => document.querySelector('#canvas').toDataURL()") as String
            assertTrue(backPixels != facePixels, "the plan is the plan again")
            assertTrue(status().contains("Plan view"), "and it says so; got: ${status()}")

            assertTrue(errors.isEmpty(), "the shell threw: $errors")
            browser.close()
        }
        server.stop(0)
    }

    /**
     * **Patterns as orbits, in the real browser** (OP-23): a circular pattern, then *one* segment gesture
     * that becomes every side, then the count field re-stamping the whole thing at another count.
     *
     * What only a browser can answer here: the palette really grew the two pattern buttons, one click pair
     * really produces the whole ring of sides (the status line says how many), and the count field — a tool
     * *option* everywhere else — really re-stamps the selected pattern in place, with the canvas showing
     * different pixels afterwards. A fresh page, so no earlier geometry can be snapped to by accident.
     */
    @Test
    fun patternOrbitsInBrowser() {
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

            fun status() = page.querySelector("#status").textContent()

            fun curves() = page.querySelectorAll("#tree .item").count { it.textContent().startsWith("segment") }

            val box = page.querySelector("#canvas").boundingBox()
            val cx = box.x + box.width * 0.5
            val cy = box.y + box.height * 0.5
            val r = 120.0

            fun member(k: Int) = Pair(cx + r * kotlin.math.cos(k * kotlin.math.PI / 3), cy - r * kotlin.math.sin(k * kotlin.math.PI / 3))

            // the ring: centre, then the reference member, with the count field as its instance count
            page.click("#tool-${Tools.PATTERN_CIRCULAR}")
            page.mouse().click(cx, cy)
            page.mouse().click(member(0).first, member(0).second)
            assertTrue(status().contains("Pattern P1"), "the ring should announce itself; got: ${status()}")
            page.screenshot(Page.ScreenshotOptions().setPath(Paths.get("build/e2e/13-pattern-ring.png")))

            // one segment gesture, six sides — the rule, not a copy
            page.click("#tool-${Tools.SEGMENT}")
            page.mouse().click(member(0).first, member(0).second)
            page.mouse().click(member(1).first, member(1).second)
            assertTrue(status().contains("6 copies round pattern P1"), "one gesture, six sides; got: ${status()}")
            assertTrue(curves() == 6, "the tree should list six segments; got ${curves()}")
            val hexagon = page.evaluate("() => document.querySelector('#canvas').toDataURL()") as String
            page.screenshot(Page.ScreenshotOptions().setPath(Paths.get("build/e2e/14-pattern-sides.png")))

            // the count field, in its other role: with a member selected it re-stamps the pattern (OP-23)
            page.click("#tool-select")
            page.mouse().click(member(2).first, member(2).second)
            assertTrue(page.querySelector("#t-pattern").textContent().contains("Pattern P1"), "the panel names the pattern")
            assertTrue(page.isEnabled("#t-restamp"), "selecting a member is what makes the count field re-stampable")
            page.fill("#t-count", "9")
            page.dispatchEvent("#t-count", "change")
            page.click("#t-restamp")
            assertTrue(status().contains("6 -> 9 instances"), "the re-stamp should say what it did; got: ${status()}")
            assertTrue(curves() == 9, "nine sides after the re-stamp; got ${curves()}")
            assertTrue(
                (page.evaluate("() => document.querySelector('#canvas').toDataURL()") as String) != hexagon,
                "the canvas should show the new count",
            )
            page.screenshot(Page.ScreenshotOptions().setPath(Paths.get("build/e2e/15-pattern-restamped.png")))

            assertTrue(errors.isEmpty(), "the shell threw: $errors")
            browser.close()
        }
    }

    /**
     * **A datum sketch plane, in the real browser** (OP-17's datum extension, GitHub #6): a plate, then a
     * sketch plane on one of its footprint edges at **45°** — the general case sketch-on-face is the special
     * one of — and a boss extruded on it.
     *
     * What only a browser can answer: the palette really grew the button, one click on a line really opens a
     * space the topbar names *by its angle and its line*, and a feature drawn there really comes out (the
     * tree lists a second solid, and the 3D view is not blank). The angle comes from the panel with its unit
     * selector, which is also the only place a *negative* one can be typed.
     */
    @Test
    fun datumSketchPlaneInBrowser() {
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

            fun status() = page.querySelector("#status").textContent()

            fun tree() = page.querySelectorAll("#tree .item").map { it.textContent() }

            fun solids() = tree().count { it.startsWith("solid") }

            val box = page.querySelector("#canvas").boundingBox()
            val rx1 = box.x + box.width * 0.30
            val rx2 = box.x + box.width * 0.70
            val ry1 = box.y + box.height * 0.35
            val ry2 = box.y + box.height * 0.60

            // the plate, exactly as the face-sketch flow builds it
            page.click("#tool-${Tools.RECTANGLE}")
            page.mouse().click(rx1, ry1)
            page.mouse().click(rx2, ry2)
            page.fill("#p-name", "thickness")
            page.fill("#p-value", "20")
            page.click("#p-add")
            page.click("#tool-${Tools.EXTRUDE}")
            page.mouse().click((rx1 + rx2) / 2, ry2)
            assertTrue(solids() == 1, "the plate should be one solid; tree: ${tree()}")

            // ---- the datum: 45 degrees on the plate's front footprint edge ----
            val planPixels = page.evaluate("() => document.querySelector('#canvas').toDataURL()") as String
            page.fill("#p-name", "tilt")
            page.fill("#p-value", "45")
            page.selectOption("#p-unit", "deg")
            page.click("#p-add")
            page.click("#tool-${Tools.SKETCH_PLANE}")
            page.mouse().click((rx1 + rx2) / 2, ry2)
            assertTrue(
                page.querySelector("#v-space").inputValue() == "plane1",
                "the topbar indicator should name the new plane; got ${page.querySelector("#v-space").inputValue()}",
            )
            assertTrue(status().contains("datum plane"), "and the conventions are said out loud; got: ${status()}")
            assertTrue(
                page.querySelector("#v-space").textContent().contains("45° on"),
                "the space list names a datum by its angle and its line; got: ${page.querySelector("#v-space").textContent()}",
            )
            val datumPixels = page.evaluate("() => document.querySelector('#canvas').toDataURL()") as String
            assertTrue(datumPixels != planPixels, "a datum view draws its own space, not the plan's")
            page.screenshot(Page.ScreenshotOptions().setPath(Paths.get("build/e2e/16-datum-plane-2d.png")))

            // ---- a boss on it: Extrude follows the plane's own normal ----
            val cx = box.x + box.width * 0.5
            val cy = box.y + box.height * 0.5
            page.click("#tool-${Tools.RECTANGLE}")
            page.mouse().click(cx - 100.0, cy - 60.0)
            page.mouse().click(cx + 100.0, cy + 40.0)
            page.fill("#p-name", "boss")
            page.fill("#p-value", "10")
            page.selectOption("#p-unit", "mm")
            page.click("#p-add")
            page.click("#tool-${Tools.EXTRUDE}")
            page.mouse().click(cx, cy + 40.0) // the rectangle's lower edge, in the plane's own coordinates
            assertTrue(solids() == 2, "the boss should be a solid of its own; tree: ${tree()}, status: ${status()}")

            // SELECT first: a plain drag in the 3D view belongs to an armed tool now (edit-in-3D slice 1)
            page.click("#tool-select")
            page.click("#v-3d")
            page.waitForSelector("#canvas3:visible")
            page.mouse().move(cx, cy)
            page.mouse().down()
            page.mouse().move(cx + 70.0, cy - 50.0)
            page.mouse().up()
            page.screenshot(Page.ScreenshotOptions().setPath(Paths.get("build/e2e/17-datum-plane-3d.png")))
            val drawn = page.evaluate("() => document.querySelector('#canvas3').toDataURL()") as String
            val blank =
                page.evaluate(
                    "() => { const c = document.createElement('canvas'); " +
                        "c.width = document.querySelector('#canvas3').width; c.height = document.querySelector('#canvas3').height; " +
                        "return c.toDataURL(); }",
                ) as String
            assertTrue(drawn != blank, "the 3D view should have drawn the tilted feature")

            assertTrue(errors.isEmpty(), "the shell threw: $errors")
            browser.close()
        }
    }

    /**
     * **Drawing in the 3D view, in real Chrome** (edit-in-3D slice 1): the rectangle tool armed, both corners
     * clicked *in the 3D viewport* on the plan, with a Ctrl-orbit in between — and the drawing that comes out
     * is the one the same two plane positions produce on the 2D canvas.
     *
     * Only a browser can answer the parts this is here for: the two canvases really stack (the sketch layer
     * paints over the WebGL one, and the pixels prove it), the 3D canvas really routes a plain drag to the
     * tool while Ctrl+drag really orbits instead, and the *document* that results is the app's own — read back
     * through its Copy button, which is `DocumentFormat.save` verbatim.
     *
     * Served over http, because the clipboard is how the app hands its script out and permissions cannot be
     * granted to a `file:` origin. The comparison is [assertSameConstruction]: the same steps, ids and names
     * exactly, the numbers to a tolerance far under anything the drawing can express, since one route to a
     * plane point is a similarity and the other a perspective divide, and because a synthetic mouse lands on
     * whole pixels.
     */
    @Test
    fun drawingOnTheWorkingPlaneInThe3DView() {
        assumeTrue(System.getProperty("e2e") == "1", "browser E2E disabled (run with -De2e=1)")

        val dist = File("build/dist/js/productionExecutable")
        assertTrue(File(dist, "index.html").exists(), "run ./gradlew jsBrowserDistribution first")
        File("build/e2e").mkdirs()
        val server = serve(dist)
        val url = "http://127.0.0.1:${server.address.port}/index.html"

        Playwright.create().use { pw ->
            val browser = pw.chromium().launch(BrowserType.LaunchOptions().setChannel("chrome").setHeadless(true))
            val context =
                browser.newContext(
                    com.microsoft.playwright.Browser.NewContextOptions()
                        .setViewportSize(1000, 700)
                        .setPermissions(listOf("clipboard-read", "clipboard-write")),
                )
            val page = context.newPage()
            val errors = ArrayList<String>()
            page.onPageError { errors.add(it) }

            /** The drawing as the app itself writes it: its own Copy button, read back off the clipboard. */
            fun script(): String {
                page.click("#f-copy")
                // the file actions report in their own line, and the wait is on *that*: a clipboard write is
                // asynchronous, so reading it back before the note appears would race the browser
                page.waitForCondition { (page.querySelector("#file-note").textContent() ?: "").contains("to the clipboard") }
                return page.evaluate("() => navigator.clipboard.readText()") as String
            }

            fun status() = page.querySelector("#status").textContent()

            page.navigate(url)
            page.waitForSelector("#canvas")
            val box = page.querySelector("#canvas").boundingBox()

            // ---- the 3D view, with a tool armed: an editing view ----
            page.click("#v-3d")
            page.waitForSelector("#canvas3:visible")
            page.click("#tool-${Tools.RECTANGLE}")
            assertTrue(status().contains("Hold Ctrl to orbit"), "the view says it is drawing now; got: ${status()}")
            val emptyOverlay = page.evaluate("() => document.querySelector('#canvas').toDataURL()") as String

            val cx = box.x + box.width * 0.5
            val cy = box.y + box.height * 0.5
            page.mouse().click(cx - 120.0, cy + 60.0)
            page.mouse().move(cx + 60.0, cy - 20.0)
            page.screenshot(Page.ScreenshotOptions().setPath(Paths.get("build/e2e/21-drawing-in-3d.png")))
            val previewing = page.evaluate("() => document.querySelector('#canvas').toDataURL()") as String
            assertTrue(previewing != emptyOverlay, "the sketch layer over the 3D canvas should be painting the preview")

            // ---- Ctrl+drag orbits mid-gesture, and the tool is still armed afterwards ----
            val posed = page.evaluate("() => document.querySelector('#canvas3').toDataURL()") as String
            page.keyboard().down("Control")
            page.mouse().move(cx + 200.0, cy)
            page.mouse().down()
            page.mouse().move(cx + 260.0, cy - 30.0)
            page.mouse().move(cx + 300.0, cy - 50.0)
            page.mouse().up()
            page.keyboard().up("Control")
            assertTrue(
                (page.evaluate("() => document.querySelector('#canvas3').toDataURL()") as String) != posed,
                "Ctrl+drag should orbit the 3D view",
            )
            assertTrue(status().contains("Hold Ctrl to orbit"), "…and hand the tool back; got: ${status()}")

            // ---- the second corner, through the camera the orbit left behind ----
            page.mouse().click(cx + 40.0, cy - 40.0)
            page.screenshot(Page.ScreenshotOptions().setPath(Paths.get("build/e2e/22-drawn-in-3d.png")))
            val drawnIn3d = script()
            assertTrue(drawnIn3d.contains("orthostart"), "the rectangle was drawn by clicking in the 3D view: $drawnIn3d")
            assertTrue(page.querySelectorAll("#tree .item").size >= 4, "…and its corners are in the tree")

            // the plane coordinates it recorded — the two clicks that made it, as the document has them
            val corners =
                Regex("ortho(?:start|vertex) (-?[0-9.eE+-]+),(-?[0-9.eE+-]+)")
                    .findAll(drawnIn3d)
                    .map { Vec2(it.groupValues[1].toDouble(), it.groupValues[2].toDouble()) }
                    .toList()
            assertTrue(corners.size == 4, "a closed rectangle has four corners: $corners")

            // ---- the same two positions, clicked on the 2D canvas of a fresh page ----
            page.navigate(url)
            page.waitForSelector("#canvas")
            val w = (page.evaluate("() => document.querySelector('#canvas').width") as Number).toDouble()
            val h = (page.evaluate("() => document.querySelector('#canvas').height") as Number).toDouble()

            // the shell's own camera: the origin centred, 4 px/mm (`Camera.centered`)
            fun screenOf(p: Vec2) = Pair(box.x + p.x * 4.0 + w / 2.0, box.y - p.y * 4.0 + h / 2.0)
            page.click("#tool-${Tools.RECTANGLE}")
            for (p in listOf(corners[0], corners[2])) {
                val (sx, sy) = screenOf(p)
                page.mouse().click(sx, sy)
            }
            page.screenshot(Page.ScreenshotOptions().setPath(Paths.get("build/e2e/23-same-drawn-in-2d.png")))
            // A millimetre of tolerance, and it is the *browser's*: a synthetic pointer lands on whole pixels,
            // which is a quarter of a millimetre at the canvas' 4 px/mm, and an ortho corner is made of two of
            // them. Everything else — the steps, the ids, the names, the structure — is compared exactly.
            assertSameConstruction(script(), drawnIn3d, tol = 1.0)

            assertTrue(errors.isEmpty(), "the shell threw: $errors")
            browser.close()
        }
        server.stop(0)
    }

    /**
     * **The export package in real Chrome**: a solid built by clicking, given a material in the panel, shown in
     * the realistic preview, and downloaded as a GLB.
     *
     * Served over http, and that is not incidental — the preview's three.js is a **lazily imported chunk**
     * (webpack code-splitting: the library is a separate file, so it rides only the sessions that open the
     * panel), and a browser will not fetch a chunk from a `file:` page any more than it will load the WASM
     * engine there. So this test is the only place that can vouch for four things at once: the chunk really
     * loads, WebGL really renders it, the material assigned in the panel really reaches the preview's shader,
     * and the export button really produces a downloadable file whose first four bytes spell `glTF`.
     *
     * The two editing views are checked to still work *after* the preview has been up, because "a third view
     * that breaks the other two" is the failure mode a display-only viewer is worth having only if it avoids.
     */
    @Test
    fun previewAndExportInBrowser() {
        assumeTrue(System.getProperty("e2e") == "1", "browser E2E disabled (run with -De2e=1)")

        val dist = File("build/dist/js/productionExecutable")
        assertTrue(File(dist, "index.html").exists(), "run ./gradlew jsBrowserDistribution first")
        File("build/e2e").mkdirs()
        val server = serve(dist)
        val url = "http://127.0.0.1:${server.address.port}/index.html"

        Playwright.create().use { pw ->
            val browser = pw.chromium().launch(BrowserType.LaunchOptions().setChannel("chrome").setHeadless(true))
            val page = browser.newPage()
            val errors = ArrayList<String>()
            page.onPageError { errors.add(it) }
            val previewLines = ArrayList<String>()
            page.onConsoleMessage { if (it.text().startsWith("[Preview]")) previewLines.add(it.text()) }
            page.setViewportSize(1000, 700)
            page.navigate(url)
            page.waitForSelector("#canvas")

            fun status() = page.querySelector("#status").textContent()

            fun fileNote() = page.querySelector("#file-note").textContent()

            fun tree() = page.querySelectorAll("#tree .item").map { it.textContent() }

            // ---- a plate: a rectangle in plan, then 20 mm of thickness ----
            val box = page.querySelector("#canvas").boundingBox()
            val rx1 = box.x + box.width * 0.32
            val rx2 = box.x + box.width * 0.68
            val ry1 = box.y + box.height * 0.35
            val ry2 = box.y + box.height * 0.62
            page.click("#tool-${Tools.RECTANGLE}")
            page.mouse().click(rx1, ry1)
            page.mouse().click(rx2, ry2)
            page.fill("#p-name", "thickness")
            page.fill("#p-value", "20")
            page.click("#p-add")
            page.click("#tool-${Tools.EXTRUDE}")
            page.mouse().click((rx1 + rx2) / 2, ry2) // a leg of the footprint, not the area inside it
            assertTrue(tree().any { it.startsWith("solid") }, "the plate became a solid: ${status()}")

            // ---- its material, in the inspector (appearance Tier 1) ----
            //
            // A colour well takes no keystrokes, so the value is written and committed the way the browser
            // itself would — which is also what proves the shell listens for `change` on that field.
            page.click("#tree .item:last-child")
            page.waitForSelector("#insp-color")
            page.evaluate(
                """
                () => {
                  const set = (id, v) => {
                    const f = document.getElementById(id);
                    f.value = v;
                    f.dispatchEvent(new Event('change', { bubbles: true }));
                  };
                  set('insp-color', '#b87333');
                  set('insp-rough', '0.35');
                  set('insp-metal', '0.9');
                }
                """.trimIndent(),
            )
            assertTrue(status().contains("#b87333"), "the panel says what the material now is: ${status()}")
            page.click("#f-copy")
            page.waitForCondition { fileNote().contains("clipboard") || fileNote().contains("Clipboard") }

            // ---- the realistic preview: the lazily-loaded chunk, and a frame on the canvas ----
            val blank = page.evaluate("() => document.querySelector('#canvas-preview').toDataURL()") as String
            page.click("#v-prev")
            page.waitForCondition { previewLines.any { it.startsWith("[Preview] ready") } }
            assertTrue(
                previewLines.any { it.contains("three.js r") },
                "the module reports itself, so the chunk really loaded: $previewLines",
            )
            assertTrue(previewLines.last().contains("1 solid"), "…with the drawing's one body in it: $previewLines")
            assertTrue(status().startsWith("Preview:"), "the status line says what the view is: ${status()}")
            page.waitForCondition {
                (page.evaluate("() => document.querySelector('#canvas-preview').toDataURL()") as String) != blank
            }
            page.screenshot(Page.ScreenshotOptions().setPath(Paths.get("build/e2e/24-preview.png")))
            val shown = page.evaluate("() => document.querySelector('#canvas-preview').toDataURL()") as String

            // it is a *view*: dragging orbits it, and nothing about the drawing changes
            val itemsBefore = tree().size
            page.mouse().move(box.x + box.width / 2, box.y + box.height / 2)
            page.mouse().down()
            page.mouse().move(box.x + box.width / 2 + 120, box.y + box.height / 2 + 40)
            page.mouse().up()
            assertTrue(
                (page.evaluate("() => document.querySelector('#canvas-preview').toDataURL()") as String) != shown,
                "dragging in the preview should orbit it and redraw",
            )
            assertEquals(itemsBefore, tree().size, "a display-only view must not build anything")
            page.screenshot(Page.ScreenshotOptions().setPath(Paths.get("build/e2e/25-preview-orbited.png")))

            // ---- the GLB export: a real download, and its first four bytes ----
            val download = page.waitForDownload { page.click("#x-glb") }
            assertEquals("drawing.glb", download.suggestedFilename(), "named after the drawing")
            val saved = File("build/e2e/export.glb")
            download.saveAs(saved.toPath())
            val bytes = saved.readBytes()
            assertTrue(bytes.size > 100, "the GLB has content: ${bytes.size} bytes")
            assertEquals("glTF", String(bytes, 0, 4, Charsets.US_ASCII), "a glTF binary container, by its magic")
            assertTrue(fileNote().contains("1 solid"), "the panel says what went out: ${fileNote()}")

            // ...and the same for the two print formats and the interchange one, so all four buttons are
            // known to be wired
            val threeMf = page.waitForDownload { page.click("#x-3mf") }
            assertEquals("drawing.3mf", threeMf.suggestedFilename())
            val stl = page.waitForDownload { page.click("#x-stl") }
            assertEquals("drawing.stl", stl.suggestedFilename())
            val jt = page.waitForDownload { page.click("#x-jt") }
            assertEquals("drawing.jt", jt.suggestedFilename())

            // ---- and the editing views still work, with the preview closed again ----
            page.click("#v-3d")
            page.waitForCondition { page.querySelector("#canvas-preview").isHidden }
            val solid3d = page.evaluate("() => document.querySelector('#canvas3').toDataURL()") as String
            page.click("#v-2d")
            page.click("#tool-${Tools.POINT}")
            page.mouse().click(rx1, ry1 - 40)
            assertEquals(itemsBefore + 1, tree().size, "the 2D view still draws: ${status()}")
            assertTrue(solid3d.length > 1000, "the 3D view still renders the part")

            assertTrue(errors.isEmpty(), "the shell threw: $errors")
            browser.close()
        }
        server.stop(0)
    }
}
