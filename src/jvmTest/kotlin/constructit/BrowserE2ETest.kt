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
            browser.close()
        }
    }
}
