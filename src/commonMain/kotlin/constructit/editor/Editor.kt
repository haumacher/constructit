package constructit.editor

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.FrameValue
import constructit.core.PointValue
import constructit.core.ScalarValue
import constructit.dsl.PointRef
import constructit.dsl.valueOf
import constructit.exchange.ImportResult
import constructit.exchange.Imports
import constructit.expr.Expr
import constructit.expr.ExprError
import constructit.expr.ExprParser
import constructit.geom.Geom3
import constructit.geom.GeomMath
import constructit.geom.Justification
import constructit.geom.MeshQuality
import constructit.geom.Vec2
import constructit.units.Dimension
import constructit.units.Quantity
import constructit.units.mm

/** Undo depth bound: snapshots are whole scripts, so the stack must not grow with the session. */
private const val UNDO_CAP = 100

/** Below this screen distance a press-and-release is a *click*, not a drag. */
private const val CLICK_SLOP_PX = 3.0

/**
 * How near the previous click a new one must land to count as **the same spot** — the repeat that steps the
 * pick cycle on instead of starting a new pick (see [Editor.pickAt]).
 *
 * Deliberately *the same notion* as [CLICK_SLOP_PX], and one named constant: within it the pointer has not
 * travelled, so "click again" means exactly what it says, and click-vs-drag can never drift apart from
 * repeat-vs-new.
 */
private const val REPEAT_CLICK_PX = CLICK_SLOP_PX

/** What the status line says when the active parameter is switched off (see [Editor.clickScalar]). */
private const val NO_ACTIVE_PARAMETER = "no parameter active — tools use their defaults"

/**
 * What a gesture in the 3D view says when its ray never reaches the working plane (see [Editor.enter]) —
 * the plane is edge-on to the cursor there, or it lies behind the eye.
 *
 * A refusal with a reason rather than a silent no-op, and never a coordinate: this is the one place where
 * "where did the user point" genuinely has no answer, and inventing one would place geometry nowhere
 * anybody pointed.
 */
private const val OFF_PLANE_NOTE =
    "Nothing to point at there: the cursor's ray misses the working plane (it is edge-on, or the plane " +
        "is behind the view). Orbit until the plane faces you."

/** How many panel picks are remembered — no tool asks for more scalars than this. */
private const val SCALAR_PICK_MEMORY = 4

/**
 * Upper bound on a structural count, so a slip of the keyboard cannot build a million nodes — and, since a
 * whole group can fill a geometry slot (OP-16), the same bound on the **copies** one step may build:
 * `members × (count-1)`. For a single element the two are the same number, so nothing about the
 * single-element case changes.
 */
private const val MAX_COUNT = 512

/**
 * How every ending of an ortho run ends its sentence. One wording for weld, attach and close, because the
 * thing the user has to read off it is the same in all three: drawing stopped, and the next click starts
 * something new rather than continuing this (see [Editor.markTerminal]).
 */
private const val RUN_FINISHED = " — the run is finished; click a point or leg to start the next one"

/**
 * Which button a pointer gesture uses. In SELECT mode they mean different things (OP-16): PRIMARY
 * picks, rubber-bands a marquee and drags geometry; MIDDLE pans, in *every* tool — panning is a
 * button, not a mode, so the view can be moved without putting the tool down. The browser shell
 * reports MIDDLE for Space+drag as well, so a one-button mouse can still pan.
 */
enum class PointerButton { PRIMARY, MIDDLE }

/**
 * One thing a SELECT click could mean — an entry of the ranked pile [Editor.pickAt] collects.
 *
 * There is **one** cycle machine over this list, replacing the two hand-built two-element cycles that grew
 * separately: the group/member reach (OP-16) and jamb-vs-leg (OP-21). Each entry is a *complete* answer to
 * "what is selected", so applying one is history-free — which is what lets a click apply any of them without
 * a rule of its own.
 */
private sealed class Candidate {
    /** A whole group, as a click on one of its members addresses it first (OP-16), with the clicked member. */
    class Whole(
        val group: Group,
        val primary: Element,
    ) : Candidate()

    /** One element alone — ungrouped geometry, or a member deliberately reached past its group. */
    class One(
        val element: Element,
    ) : Candidate()

    /** An opening's jamb (OP-21): a selection that owns no [Element] at all. */
    class Opening(
        val jamb: Jamb,
    ) : Candidate()
}

/**
 * What one SELECT press found at a position: every [Candidate] within the pick tolerance, ranked, plus the
 * two facts the *press* needs and only it can decide — what a drag from here would move, and whether a jamb
 * took the grab.
 */
private class PickPile(
    val candidates: List<Candidate>,
    /** What a drag would move: a draggable point, an ortho leg, a dimension's offset — today's rule verbatim. */
    val movable: Element?,
    /** The jamb that took the grab, i.e. nothing movable outranked it (OP-21); null when one did. */
    val grabJamb: Jamb?,
    /** The element the press addresses at all, movable or merely selectable; null when it hit nothing. */
    val hit: Element?,
)

/**
 * Pure interaction controller. In SELECT mode it drags free points (live recompute), rubber-bands a
 * selection, or pans on the middle button; otherwise it runs the active [ToolDef] as a generic
 * slot-collector, picking existing geometry or creating points per click, and consuming the active
 * parameter for scalar slots. No platform APIs — fully headless-testable by simulating gestures.
 */
class Editor(
    doc: Document = Document(),
    var canvasW: Double = 800.0,
    var canvasH: Double = 600.0,
) {
    var doc: Document = doc
        private set

    init {
        // a document handed to the controller is where the drawing *starts*, exactly as a loaded file is:
        // whatever it cannot build, it arrived unable to build (OP-3, see [rebaseValidity])
        rebaseValidity()
    }

    /** Swap in a loaded document, dropping every reference into the old one and its history. */
    fun replaceDocument(fresh: Document) {
        // another drawing entirely: its spaces are its own, so the remembered views go with the old one
        spaceCameras.clear()
        adopt(fresh, keepSpace = false)
        // a loaded file starts its own history — earlier snapshots describe a different document
        undoStack.clear()
        redoStack.clear()
        lastCommitted = DocumentFormat.save(fresh)
        // What the *load* had to decide says so here, or it says it nowhere: a migration finding
        // (`Document.loadNotes`, OP-18's *Versioning & migration*) names an element whose stored literal was
        // ambiguous, and until now it was published into the document's one-shot note that only a *tool* run
        // reads — so it was overwritten by this very line. It stays until the next user action, like every
        // other status line.
        statusHint = loadNote(fresh) ?: ""
        // a load is not an edit: what the file cannot build is stated, not announced as a transition
        rebaseValidity()
        onChange()
    }

    /** The loaded document's migration findings as one line — the first, and how many more (OP-18). */
    private fun loadNote(fresh: Document): String? {
        val notes = fresh.loadNotes
        fresh.takeNote() // it is being said here instead, so no later tool run repeats it
        if (notes.isEmpty()) return null
        val more = if (notes.size == 1) "" else " (and ${notes.size - 1} more)"
        return "Loaded with a note: ${notes.first()}$more"
    }

    /** Swap [fresh] in, resetting every transient reference into the old document (selection, picks). */
    private fun adopt(
        fresh: Document,
        keepSpace: Boolean = true,
    ) {
        // Stay in the space the user is looking at (OP-17). A replayed script ends in whatever space its
        // last step was built in, which for an undo is *not* where the user is: undoing a circle drawn on a
        // face must not also throw the view back to the plan. So the view wins where it still exists — and
        // when it does not (the undo removed the space itself) the replayed answer stands, with the camera
        // following it.
        val looking = doc.activeSpace.name
        doc = fresh
        if (keepSpace && looking != fresh.activeSpace.name) fresh.spaceNamed(looking)?.let { fresh.activeSpace = it }
        if (fresh.activeSpace.name != looking) {
            spaceCameras[looking] = camera
            camera = spaceCameras[fresh.activeSpace.name] ?: cameraFor(fresh.activeSpace)
        }
        clearSelection()
        // it names elements of the document being replaced (and a tool of it), so it cannot survive
        createDialog = null
        activeScalar = null
        scalarPicks.clear() // they name entries of the document being replaced
        resetPicks()
    }

    // ---- undo/redo: the saved construction script is the undo substrate (OP-18) ----
    //
    // One snapshot of the saved script per committed user-level operation; undo replays the previous
    // snapshot into a fresh document. Prefix-replay over the journal was rejected: a drag or a typed
    // value mutates a source node's literal without adding a step, so journal length does not delimit
    // an operation — the saved text (which restates those literals) does.
    //
    // [checkpoint] is called where an operation *commits*, not where the document mutates: a tool's
    // build, a drag's release (welds, attaches and joins included), a typed field write, a path's
    // finish (its start/vertex/close steps are one gesture), a break click, an opening insertion, a
    // delete, and the panel edits the browser shell routes here. A snapshot is pushed only when the
    // text changed, so a cancelled or no-op gesture never becomes an undo step.
    //
    // **A value typed into an armed tool is half of that tool's operation, never a step of its own.**
    // Typing it creates an ordinary parameter (OP-13, see [commitTypedScalar]) — but "circle of radius 7"
    // is *one* thing the user did, so the parameter is held pending and whichever checkpoint commits next
    // seals it: the tool's own build takes parameter and geometry as one snapshot, and one undo removes
    // both. Abandon the gesture instead — Esc, or arming another tool — and [resetPicks] **retracts** it,
    // so it leaves no step and no panel row, exactly as a cancelled tool's stray points leave none. The
    // invariant both halves protect is that no snapshot ever contains half an operation, and no
    // parameter that only existed to feed one can outlive it unaccounted for.
    private val undoStack = ArrayList<String>()
    private val redoStack = ArrayList<String>()
    private var lastCommitted: String = DocumentFormat.save(doc)

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Record the document as one committed operation — the seam every user-level edit funnels through. */
    fun checkpoint() {
        // whatever operation is committing now owns any parameters typed on the way to it: sealing them
        // here is what makes "type 7, click" one undo step (see commitTypedScalar / resetPicks)
        pendingTypedParams.clear()
        val now = DocumentFormat.save(doc)
        if (now == lastCommitted) return
        undoStack.add(lastCommitted)
        if (undoStack.size > UNDO_CAP) undoStack.removeAt(0)
        redoStack.clear()
        lastCommitted = now
    }

    /**
     * Run a tool's build as **one transaction** (OP-27): what comes out of a gesture is what its step
     * created, or the document is exactly what it was before the gesture — never something in between.
     *
     * The invariant this enforces is [Document.steplessElements]'s: *every element was created by exactly
     * one journal step.* Two things can break it, and both are handled here by the same restore, because
     * both leave the same wreckage:
     *
     * - **a build that throws.** [Document.recording] can only write its step after the body has run, so a
     *   throw part-way through leaves whatever was already added with nothing to own it. That is not
     *   hypothetical: a user picked a dimensionless parameter for a tube's radius and the tool's own status
     *   message — a `Quantity.mm` read (OP-7) — threw *after* the solid had been added. The tube appeared,
     *   could not be deleted, was in no saved file, and drew nothing;
     * - **a build that adds without recording.** Nothing in the shipped shell does this today, but the only
     *   thing that stopped it was every author of a `ToolDef` remembering to build through the recording
     *   path. Now the runner checks instead of trusting, so a new row cannot reintroduce it.
     *
     * The restore is the **undo substrate** (OP-18), which is the complete answer and already proven: the
     * saved script of the last checkpoint, replayed into a fresh document. So the refused gesture also takes
     * back the points its earlier clicks placed, which is what "the gesture is refused as a whole" means.
     *
     * Returns the refusal to show, or null when the gesture stands.
     */
    private fun transacted(
        what: String,
        body: () -> Unit,
    ): String? {
        val before = doc.elements.toHashSet()
        val failure =
            try {
                body()
                // OP-27's check, in the invariant's own terms and only over what *this* gesture made: a
                // document may hold older stepless elements (nothing in the shell makes one, but a direct
                // `Document` call in a test does), and this gesture is not the place to answer for them.
                doc.elements.filter { it !in before && doc.creatingStep(it) == null }
                    .takeIf { it.isNotEmpty() }
                    ?.let { orphans ->
                        "built ${orphans.joinToString(", ") { doc.nameOf(it) }} without recording a step, " +
                            "which no file could keep — so it was taken back"
                    }
            } catch (t: Throwable) {
                "could not be built: ${t.message ?: t.toString()}"
            } ?: return null
        restore(lastCommitted)
        return "$what $failure"
    }

    /**
     * Go back to the committed snapshot [text] — and **re-derive the baseline from the document that comes
     * back**, which is the whole of it.
     *
     * `lastCommitted` answers one question, asked at the top of [undo]: *is there uncommitted work in the
     * document right now?* It answers it by comparing the document's serialization against a stored string,
     * so the string has to be **what this document serializes to**, not merely the text it was built from.
     * Those are the same thing only while `save ∘ load` is the identity, and it is not: a derived position
     * that the file **restates** — a point `attach`ed to a curve writes the current foot of the perpendicular
     * — is re-derived on load and can come back a ULP away (recorded in the queue, ~1e-13 mm, settling in
     * four passes).
     *
     * Storing the *input* text made that tiny drift fatal to a mechanism that has nothing to do with
     * geometry. After one restore the baseline disagreed with its own document for ever, so every later undo
     * took the "discard uncommitted work" branch — restoring the same state, popping nothing, reporting
     * success. **The undo stack wedged permanently after the first undo**, on any drawing containing such a
     * position, whatever the gestures were (two segments on a ten-line drawing do it). Re-deriving here makes
     * the invariant structural instead of arithmetic: *the baseline is a property of the document, never of
     * the text that produced it*, so no future restatement can reach undo at all.
     *
     * Not folded into [adopt], deliberately: the other callers adopt a **new** state and then [checkpoint] it,
     * and a baseline moved under them would make that checkpoint a no-op — an undo layer lost instead of a
     * spurious one. This is the restore path only.
     */
    private fun restore(text: String) {
        adopt(DocumentFormat.load(text))
        lastCommitted = DocumentFormat.save(doc)
    }

    fun undo(): Boolean {
        if (undoStack.isEmpty()) return false
        if (DocumentFormat.save(doc) != lastCommitted) {
            // uncommitted work in progress — a half-drawn path, a cancelled tool's stray points —
            // is discarded as the first undo, not popped past: it was never a step, so it has no
            // snapshot of its own and cannot be redone
            restore(lastCommitted)
        } else {
            redoStack.add(lastCommitted)
            lastCommitted = undoStack.removeLast()
            restore(lastCommitted)
        }
        statusHint = "Undone"
        changed()
        return true
    }

    fun redo(): Boolean {
        if (redoStack.isEmpty()) return false
        undoStack.add(lastCommitted)
        lastCommitted = redoStack.removeLast()
        restore(lastCommitted)
        statusHint = "Redone"
        changed()
        return true
    }

    /**
     * Delete the whole selection at the granularity of the steps that created it (OP-18): each
     * selected element's creating step is dropped together with every later step depending on what the
     * dropped steps made, and the remaining script is replayed into a fresh document — so what
     * survives is exactly what still constructs, never a graph with holes in it.
     *
     * A multi-selection is **one** closure over all those steps (not a union of per-element closures —
     * see [Document.dependentSteps]), dropped and replayed once, so it is one operation and one undo
     * step. All-or-nothing: if any selected element has no step to remove, the delete is refused rather
     * than half-performed, and says which element that was.
     */
    fun deleteSelection(): Boolean {
        val targets = selectedElements
        if (targets.isEmpty()) {
            // A selected **opening** (OP-21) owns no element, so Delete cannot reach it — and it has to say
            // so rather than do nothing, because the jamb is highlighted and its fields are live, which makes
            // the selection plainly there. Removing an interval is an ordinary step-unit delete and the
            // journal already supports it independently of its siblings (see [Document.dependentSteps]); what
            // is missing is only the route from a selection with no element to that step. Recorded as a cut.
            selectedJamb?.let {
                statusHint =
                    "Delete can't reach an opening yet: it has no element of its own. Delete the wall " +
                    "(${doc.nameOf(it.path.footprint)}) to remove it with its openings."
                changed()
            }
            return false
        }
        val roots = ArrayList<Step>()
        for (el in targets) {
            val root = doc.creatingStep(el)
            if (root == null) {
                statusHint = "${doc.nameOf(el)} has no construction step to remove"
                changed()
                return false
            }
            roots.add(root)
        }
        // one closure over all the roots at once — see [Document.dependentSteps]
        val droppedSteps = doc.dependentSteps(roots.toHashSet())
        // A macro definition with live instances is **refused**, not cascaded (OP-6): an instance's step
        // names the *tool*, not the definition's elements, so taking the instances too would delete work
        // the user did not select — and leaving them is not an option either, since their element count
        // is structural (replay would reject the file, which is not the same as OP-3 invalidity). So the
        // honest answer is to say which instances are in the way.
        val losses = doc.macroLosses(roots.toHashSet(), droppedSteps)
        if (losses.isNotEmpty()) {
            val (def, instances) = losses.first()
            statusHint =
                "Can't delete that: it defines tool ${def.name}, used by ${instances.size} instance " +
                "element${if (instances.size == 1) "" else "s"} (${instances.take(4).joinToString(", ") { doc.nameOf(it) }}) — " +
                "delete the instances first"
            changed()
            return false
        }
        val removed = droppedSteps.flatMapTo(HashSet()) { it.creates }
        val dependents = removed.count { r -> targets.none { it === r } && r in doc.elements }
        val journalBefore = doc.journal.toList()
        doc.journal.removeAll(droppedSteps)
        val fresh =
            try {
                DocumentFormat.load(DocumentFormat.save(doc))
            } catch (e: Exception) {
                doc.journal.clear()
                doc.journal.addAll(journalBefore)
                statusHint = "Delete failed: ${e.message}"
                changed()
                return false
            }
        val what = if (targets.size == 1) doc.nameOf(targets[0]) else "${targets.size} elements"
        adopt(fresh)
        checkpoint()
        statusHint = if (dependents == 0) "Deleted $what" else "Deleted $what and $dependents dependent${if (dependents == 1) "" else "s"}"
        changed()
        return true
    }

    /**
     * **Import** [bytes] as reference bodies (the JT import, OP-9): one call, **one checkpoint**, so one
     * undo removes every body a file brought in.
     *
     * The whole of the editor's part. Which bodies a file offers, what they are called, what units they are
     * in and every refusal are [Imports]'s, in `commonMain` — the mirror of the export flow, and the reason
     * both are covered headlessly. A refused import changes nothing, so the checkpoint sees no change and
     * pushes no undo step, exactly as a no-op gesture does.
     */
    fun importFile(
        bytes: ByteArray,
        fileName: String,
    ): ImportResult {
        val result = Imports.import(doc, bytes, fileName)
        checkpoint()
        statusHint = result.message
        changed()
        return result
    }

    /**
     * The pattern the current selection addresses (OP-23) — the one a selected member belongs to.
     *
     * A pattern is reached through its geometry, exactly as an ortho leg or a jamb is: there is no separate
     * tree to hunt in, and clicking any member of any of its orbits is enough to name the rule.
     */
    fun selectedPattern(): Pattern? = selectedElements.firstNotNullOfOrNull { doc.patternOf(it) }

    /**
     * **Re-stamp** [p] at [n] instances: rebuild the ring and re-run every gesture that rides it (OP-23).
     *
     * A journal rewrite plus a replay — the delete machinery's move, with one literal changed instead of a
     * step removed. Nothing is copied and nothing is patched: each `orbit` step *is* its gesture's rule, so
     * running the script again at the new count is the whole of the update, and a traced outline re-follows
     * the boundary it has now. Refused before anything happens when a gesture cannot mean the same thing at
     * the new count, and whatever a smaller count genuinely loses is named.
     */
    fun setPatternCount(
        p: Pattern,
        n: Int,
    ): Boolean {
        val why = doc.restampRefusal(p, n)
        if (why != null) {
            statusHint = why
            changed()
            return false
        }
        val was = p.count
        val name = p.name
        val result =
            try {
                DocumentFormat.restamp(DocumentFormat.save(doc), name, n)
            } catch (e: Exception) {
                statusHint = "Re-stamp failed: ${e.message}"
                changed()
                return false
            }
        val (fresh, notes) = result
        adopt(fresh)
        checkpoint()
        val lost = if (notes.isEmpty()) "" else " — ${notes.first()}${if (notes.size == 1) "" else " (and ${notes.size - 1} more)"}"
        statusHint = "Pattern $name: $was -> $n instances$lost"
        changed()
        return true
    }

    var camera: Camera = Camera.centered(canvasW, canvasH)

    /**
     * **Who maps screen pixels to the active plane's own (u, v)** — null for the 2D canvas, whose [camera]
     * *is* that mapping, and a [PlanePerspective] while the 3D view is doing the editing (edit-in-3D
     * slice 1).
     *
     * This is the whole of what the controller learns about the 3D view, and it is deliberately not about
     * 3D at all: the editor goes on receiving gestures in plane coordinates, as the jvmTest suite has
     * always driven it, and what varies is only who computed them. One field, because the *same* projection
     * has to answer for the events and for the drawing ([render]) — a cursor that lands somewhere the
     * drawing does not agree with would be a bug no test could see.
     */
    var pointing: PlaneProjection? = null

    /** [pointing] when the 3D view is editing, the 2D camera otherwise — the one accessor both paths use. */
    private fun proj(): PlaneProjection = pointing ?: camera

    var toolId: String = Tools.SELECT
        private set

    /**
     * The scalar most recently picked in the panel — the "active parameter". Setting it also appends to
     * [scalarPicks], which is what lets a tool ask for **several** scalars in order without a second
     * mechanism: one pick is one click on a parameter row, whatever the tool needs.
     */
    var activeScalar: ScalarEntry? = null
        set(value) {
            field = value
            // The *same* entry picked again is one pick, not two: the panel selects a scalar on every click
            // and on every focus of its value field, so counting a re-visit would silently corrupt a
            // half-collected pair. A point whose x and y are meant to be one value is made by wiring the two
            // parameters together, which is how sharing a DOF is expressed everywhere else (OP-5).
            if (value != null && scalarPicks.lastOrNull() !== value) {
                scalarPicks.add(value)
                // bounded: only the last few can ever be consumed, and an unbounded list would grow with
                // the session for no benefit
                while (scalarPicks.size > SCALAR_PICK_MEMORY) scalarPicks.removeAt(0)
                // A tool whose slots are all clicked was waiting for exactly this, so picking the row
                // finishes it — the same completion a typed number reaches (see [commitTypedScalar]).
                // Guarded on there being picks to finish, so arming a tool *after* picking a parameter (the
                // usual order) is untouched.
                if (filledSlots > 0) maybeCompleteTool(null)
            }
            // the value in effect is part of what the preview promises (a radius, an angle), so a pick of a
            // parameter re-draws it where the cursor last was
            refreshToolPreviewAtHover()
        }

    /**
     * A click on a parameter (or measurement) row — the panel's route into a scalar slot (OP-13: the panel is
     * as much an input as the canvas). One entry point, so the shell only routes and the decision stays here.
     *
     * Clicking the **active** row again switches it **off**. Without that there is no way to un-pick a
     * parameter, and a pick of the wrong dimension is not merely idle: a *defaulted* slot adopts any pick of
     * its own kind (see [toolScalars]), so one stray dimensionless pick shadows every dimensionless default
     * for the rest of the session — Midpoint keeps building the ratio point of the factor picked long ago.
     * Escape does the same with no gesture pending (see [key]).
     */
    fun clickScalar(entry: ScalarEntry?) {
        if (entry != null && activeScalar !== entry) {
            activeScalar = entry
            changed()
            return
        }
        clearActiveScalar()
        changed()
    }

    /**
     * Drop the active parameter, and forget it as a *pick* too — leaving it in the memory would keep it
     * feeding the next tool that wants a scalar of its kind, which is the whole thing being switched off.
     */
    private fun clearActiveScalar(): Boolean {
        val entry = activeScalar ?: return false
        activeScalar = null
        scalarPicks.removeAll { it === entry }
        statusHint = NO_ACTIVE_PARAMETER
        return true
    }

    /**
     * The panel picks, oldest first. A tool needing *k* scalars consumes the **last k** in pick order, so
     * a single-scalar tool means exactly "the active parameter" as before, a two-scalar tool means "x, then
     * y", and picking a wrong row is corrected by simply picking the right ones again. Deliberately not
     * cleared by [setTool]: the usual order is to pick the parameter and then the tool.
     */
    private val scalarPicks = ArrayList<ScalarEntry>()

    /**
     * How many instances a structural-count tool builds (a polygon's sides, an array's copies).
     *
     * **Structural, not a parameter**: it decides how many nodes the tool creates, exactly as an ortho
     * path's vertex count does, so it belongs to the *gesture* and is recorded in the tool step (OP-18)
     * rather than being a value that can be edited afterwards. Editing it later means re-running the tool.
     */
    var count: Int = 6
        set(value) {
            field = value.coerceIn(2, MAX_COUNT)
            // the count *is* part of what an array's or a pattern's preview promises, so it redraws with it
            refreshToolPreviewAtHover()
        }

    var onChange: () -> Unit = {}
    var showGrid: Boolean = false

    /**
     * The corner scale bar (see [SceneRenderer.scaleBarLength]). A **view** setting with exactly the same
     * shape as [showGrid], and off by default for the same reason: the SVG goldens are goldens of geometry,
     * and the shell switches it on where a person is looking at the drawing.
     */
    var showScaleBar: Boolean = false

    /**
     * The element the pointer is over **in the panel** — a name in the inspector's *built from* / *used by*
     * rows, or a row of the element list. Purely transient: it is not a selection, nothing acts on it, and
     * the next repaint after the pointer leaves clears it (see [SceneRenderer]'s spotlight).
     *
     * Deliberately a separate concept from the selection, and this is the line session 13 drew about hover:
     * *the selection is decided on press and on release only*. A spotlight decides nothing.
     */
    var spotlight: Element? = null
        private set

    /** Point the spotlight at [el] (null clears it). True when the highlight actually changed. */
    fun setSpotlight(el: Element?): Boolean {
        if (el === spotlight) return false
        spotlight = el
        changed()
        return true
    }

    /**
     * Dim the construction that the results are built from (OP-14), so the drawing reads on its own.
     * A *view* setting: which elements are scaffolding is derived from the graph, so nothing is
     * flagged and nothing can drift out of date.
     */
    var dimScaffolding: Boolean = false

    /**
     * Draw the elements the user has **hidden**, as ghosts, so they can be found and shown again (OP-18's
     * visibility reversal, on a user report: *"if you hide some elements, it's almost impossible to find them
     * later on to show them again"*). A hidden element was neither drawn nor pickable, so the recorded *Show*
     * step had nothing to click.
     *
     * [dimScaffolding]'s exact twin, and deliberately so: a **view** setting. It records no step, it is in no
     * file, no undo touches it, and turning it on or off cannot change the drawing by so much as a byte — the
     * hide and the show stay the recorded steps they have been since the reversal. What it changes is what the
     * canvas draws and, while it is on, what a click can reach ([ghostElements]).
     */
    var showHidden: Boolean = false

    /**
     * The elements [showHidden] is ghosting right now: hidden **by the user**, never by construction.
     *
     * The exception is the whole of the rule. A welded alias (and a duplicate joint marker) is hidden because
     * the construction says so — showing it would draw a second point on top of its master — which is why
     * [Document.setElementsVisible] refuses to show one. A toggle that resurrected those markers would be that
     * same refusal broken from the other side, so the ghost set asks the document the same question the *Show*
     * action asks ([Document.hiddenByConstruction]).
     */
    fun ghostElements(): Set<Element> =
        if (!showHidden) {
            emptySet()
        } else {
            doc.elements.filterTo(HashSet()) { !it.visible && !doc.hiddenByConstruction(it) }
        }

    /**
     * **An interaction is live** — the pointer is moving or the wheel is turning — so the 3D picture may be
     * drawn coarsely until it settles ([viewQuality], slice B of the responsiveness item).
     *
     * **The policy is here and the scheduling is not**, which is the same split `showGrid` and the repaint
     * coalescing already make (OP-12). *What* a live interaction means for the picture is a decision about
     * the drawing, so it belongs in the pure controller where the headless suite drives it: this flag, and
     * the one line below that reads it. *When* an interaction has settled, and on which callback the fine
     * mesh is then built, is a platform question — `requestAnimationFrame` and `requestIdleCallback` are the
     * shell's, exactly as they are for the paint itself — so the shell raises this flag around the two events
     * that outrun the display and lowers it when they stop coming.
     *
     * It is deliberately **not** a gesture flag (`dragging`), because a wheel notch has no release and is
     * every bit as streaming as a drag; and deliberately not something the [Editor] sets for itself on
     * `pointerMove`, because then a headless test could not tell the two states apart and nothing would ever
     * settle in a suite that never idles.
     *
     * A drag that never involves the 3D view builds nothing either way: the plan asks for no triangles at
     * all, so the promise slice A made is untouched by this flag rather than restated by it.
     */
    var interacting: Boolean = false

    /**
     * The quality the 3D picture is drawn at right now — **coarse while an interaction is live, fine the
     * moment it settles** ([MeshQuality]).
     *
     * The whole of the policy, in one line, and it reaches nothing but the picture: `Scene3.extract` takes
     * it, and every other consumer of triangles takes the fine mesh by not asking. In particular a **volume
     * readout during a gesture shows the stale fine number** rather than a fresh coarse one — that is the law
     * choosing which kind of wrong it prefers, and it prefers late to false.
     */
    val viewQuality: MeshQuality get() = if (interacting) MeshQuality.COARSE else MeshQuality.FINE

    /**
     * Which side of its carrier a new thick path's material sits on (OP-21). A property of the *tool*
     * rather than of a pick, like the active parameter: the WALL tool has no slot to click it into.
     */
    var justification: Justification = Justification.CENTER

    /**
     * While set, a drag is restricted to the single axis its gesture is dominated by, measured from
     * where the drag started — so a corner can be moved in x *or* y without disturbing the other.
     * Read live, so it can be engaged or released mid-drag.
     */
    var axisLock: Boolean = false

    /**
     * While set, a click being *placed* resolves through [Snap] — so geometry can be put onto other
     * geometry as it is drawn, with a real dependency, instead of only being attached afterwards by
     * dragging. Cleared (Alt in the browser shell) to place at the raw cursor.
     */
    var snapEnabled: Boolean = true

    /** Where the last hover would land, for the snap marker and the status bar. */
    var snapHint: SnapResult? = null
        private set

    // ---- selection: a set with a primary element (OP-16 step 0) ----
    //
    // A set, because bulk delete / hide / grouping all operate on "what is selected"; a *primary*,
    // because the inspector addresses exactly one handle (OP-13) and a click has to say which one it
    // meant. Click replaces the set, Shift+click toggles one member, a marquee replaces it wholesale.
    private val selected = LinkedHashSet<Element>()

    /**
     * The primary element of the selection — the one a click landed on, hence the one whose handle the
     * inspector addresses: the drag is on the canvas, the typed form is in the inspector, and both
     * write the same nodes (OP-13). Null when nothing is selected.
     */
    var selection: Element? = null
        private set

    /** Everything selected, in pick order. */
    val selectedElements: List<Element> get() = selected.toList()
    val selectionCount: Int get() = selected.size

    fun isSelected(el: Element): Boolean = el in selected

    /**
     * The group the selection currently addresses *as a whole* — set when a click (or the panel) selected
     * every member, cleared when a second click reached a member alone.
     *
     * Which of the two the selection means is a fact about the gesture, not about the set: a one-member
     * group selects the same elements either way, and for a **placed** group the difference is what the
     * inspector shows and what a drag writes (the frame, or the member's local point).
     */
    var selectedGroup: Group? = null
        private set

    /**
     * The **opening jamb** the selection addresses (OP-21), or null.
     *
     * A selection that owns no [Element]: an opening is a description carried by a thick path, and its jambs
     * are a drawing, so there is nothing to put in [selected]. What it buys is exactly what selecting
     * anything else buys — the inspector's typed fields (OP-13) and an emphasis on the canvas — and it is
     * deliberately *not* accompanied by selecting the wall, because Delete would then remove the whole wall
     * after a click that pointed at one opening.
     */
    var selectedJamb: Jamb? = null
        private set

    /** Select the opening [jamb] addresses, replacing any element selection: they are alternatives. */
    private fun selectJamb(jamb: Jamb) {
        selected.clear()
        selection = null
        selectedGroup = null
        selectedJamb = jamb
    }

    /** Replace the selection with [els], making [primary] the inspector's subject. */
    private fun select(
        els: Collection<Element>,
        primary: Element?,
    ) {
        selected.clear()
        selected.addAll(els)
        selection = primary?.takeIf { it in selected } ?: selected.firstOrNull()
        selectedGroup = null
        selectedJamb = null
    }

    fun clearSelection() {
        selected.clear()
        selection = null
        selectedGroup = null
        selectedJamb = null
        // nothing is selected, so no pick cycle stands: the next click is a first click
        resetCycle()
    }

    /** The frame the selection addresses: a **placed** group selected as a whole (OP-16 step 2). */
    fun selectedFrame(): Group? = selectedGroup?.takeIf { it.placed }

    var statusHint: String = ""
        private set

    // ---- what cannot be built right now, and what just changed about that (OP-3, *refusals speak*) ----
    //
    // The defect this closes, in the reporter's words: *"if I then modify the swept outline in some way, the
    // 3D solid vanishes and re-occurs, if I change parameters further. However, I do not understand, why a
    // solid cannot be drawn in this situation."* The reason was there the whole time — `EvalResult.Invalid`
    // carries a sentence naming the stations, the clearance and the way out — but no route **said** it on the
    // live-edit path: the describe routes read it on demand, while a drag or a panel edit that made a body
    // invalid simply stopped drawing it.
    //
    // Three decisions shape what is below, and each was a fork:
    //
    // - **it is a surfacing, never a refusal.** The edit is legal; the *value* is what is wrong (OP-3), so
    //   nothing is declined, nothing is modal, and the gesture completes exactly as before;
    // - **it never writes [statusHint].** A drag's own note (a height, a join offer) and the fact that a body
    //   cannot be built are two different things, and the one must not overwrite the other — which is what a
    //   single string would have done, flashing the reason for one pointer frame and then hiding it again. So
    //   this is its own channel, [validityNote], and [statusLine] composes the two. That also means no
    //   existing message changes, and a gesture test asserting a hint keeps asserting the same string;
    // - **the fact stands, the change is announced.** While anything is unbuildable the note *stays* — the
    //   user who edits three more things before looking up still finds it — and the panel marks the element
    //   itself with the reason ([Document.invalidElements]). Only healing is transient, and even that outlives
    //   the frame it happened in (see [healingSpoken]).
    //
    // The diff is keyed on the drawing's **name** for an element, not on object identity: undo replays the
    // saved script into a *fresh* document (OP-18), so identities do not survive it and names do — which is
    // exactly what makes an undo that heals a body able to say so.

    /** What cannot be built right now, in document order — the panel's rows and this line's subject (OP-3). */
    var invalidElements: List<Document.InvalidElement> = emptyList()
        private set

    /**
     * The standing sentence about validity, or null when everything builds: which element cannot be built and
     * the node's own reason for it, or — just after it heals — that it is back.
     *
     * One line, however many elements flipped: the first by name with its reason, and a count of the rest.
     * A wall of text on a status line is not read, and the panel is where each of them is listed.
     */
    var validityNote: String? = null
        private set

    /** What the status bar shows: the operation's own note and the validity note, whichever there are. */
    val statusLine: String
        get() = listOfNotNull(statusHint.ifEmpty { null }, validityNote).joinToString(" · ")

    /** Element name -> reason, as of the last recompute — the baseline the next one is compared against. */
    private var invalidBefore: Map<String, String> = emptyMap()

    /**
     * The healing sentence, and the [statusHint] it was spoken alongside.
     *
     * Healing has nothing standing to show — the body is simply there again — so it would vanish on the very
     * next pointer frame if it were regenerated like the rest. It therefore survives until the operation
     * *after* the one that healed it says something of its own, which is what the remembered hint detects.
     */
    private var healingSpoken: Pair<String, String>? = null

    /**
     * Read the document's validity, say what **changed** about it, and keep saying what still stands (OP-3).
     *
     * Called from [changed], i.e. after every route that can have moved the model. Cheap by construction:
     * an untouched node answers from its memo, and an invalid one is recomputed every pass regardless,
     * because that is how OP-3 promises healing.
     */
    private fun speakValidity() {
        val now = doc.invalidElements()
        invalidElements = now
        val byName = now.associate { it.name to it.reason }
        val subjects = toName(now)
        // "newly" means an element that could be built a moment ago, by name — *not* a reason whose numbers
        // moved. A drag re-measures the clearance every frame, and re-leading with it would make one situation
        // look like a stream of new ones
        val appeared = subjects.filter { it.name !in invalidBefore }
        // …and healing means the element is **back**, which is not the same as gone from the list: deleting an
        // unbuildable element also takes its name off it, and announcing that as a heal would be a lie (the
        // delete has its own sentence, and it says what it removed)
        val healed =
            invalidBefore.keys.filter { it !in byName }
                .mapNotNull { name -> doc.elements.firstOrNull { doc.nameOf(it) == name } }
        invalidBefore = byName
        if (subjects.isNotEmpty()) {
            // something is unbuildable: that is the news whether it just happened or has been true for a
            // while, and a heal in the same edit is not worth crowding it out
            healingSpoken = null
            validityNote = standingNote(subjects, appeared.firstOrNull())
            return
        }
        if (healed.isNotEmpty()) {
            val lead = healed.first()
            val rest = healed.size - 1
            val sentence =
                "${doc.nameOf(lead)} is ${doc.kindWord(lead)} again" + if (rest > 0) " — and $rest more" else ""
            healingSpoken = sentence to statusHint
            validityNote = sentence
            return
        }
        // nothing flipped: keep a heal that has not been talked over yet, and otherwise say nothing
        val spoken = healingSpoken
        validityNote = if (spoken != null && spoken.second == statusHint) spoken.first else null
        if (validityNote == null) healingSpoken = null
    }

    /**
     * The elements a sentence may **name**: the ones that failed here, or — when nothing did, so every
     * invalid element is only hidden by the cascade — what there is. Pointing at a dependent when a root
     * exists would send the user to the wrong element (see [Document.InvalidElement.own]).
     */
    private fun toName(all: List<Document.InvalidElement>): List<Document.InvalidElement> =
        all.filter { it.own }.ifEmpty { all }

    /** One line about [subjects]: [lead] (or the first) by name with its reason, and a count of the rest. */
    private fun standingNote(
        subjects: List<Document.InvalidElement>,
        lead: Document.InvalidElement? = null,
    ): String? {
        val first = lead ?: subjects.firstOrNull() ?: return null
        val rest = subjects.size - 1
        return "${first.name} can't be built right now: ${first.reason}" + if (rest > 0) " — and $rest more" else ""
    }

    /**
     * Re-read validity where something **outside the graph** changed what a node can compute (OP-3).
     *
     * One caller, and it is the reason this is public: the general boolean engine is WASM and arrives after
     * the first paint (OP-9), so solids that were invalid *because it was not loaded yet* heal on a repaint
     * that no gesture triggered. Without this the standing note would go on naming them. It speaks, and it
     * should: "e9 is a solid again" is exactly what happened.
     */
    fun revalidate() {
        speakValidity()
    }

    /**
     * Take the validity of the document as it now stands as the **baseline**, without speaking (OP-3).
     *
     * Loading a drawing is not an edit: a file that arrives with something unbuildable in it did not just
     * turn that way under the user's hand, and announcing it as a transition would put the file's own state
     * in the same voice as their last gesture. The standing note still shows it — the fact is visible from
     * the first frame — and a *load* has its own channel for what the load had to decide ([loadNote]).
     */
    private fun rebaseValidity() {
        val now = doc.invalidElements()
        invalidElements = now
        invalidBefore = now.associate { it.name to it.reason }
        healingSpoken = null
        validityNote = standingNote(toName(now))
    }

    /**
     * The one seam every change inside the controller goes through: read what the change did to the
     * drawing's validity ([speakValidity]), then tell the shell to repaint ([onChange]).
     *
     * A view-only change (a pan, a spotlight) comes through here too and costs a memo hit per node, which is
     * what an unchanged pass already costs the renderer.
     */
    private fun changed() {
        speakValidity()
        onChange()
    }

    /** How near a click has to land, in **screen pixels** — the one number the pick tolerance is stated in. */
    val tolPx = 10.0

    /**
     * When two candidates for one click count as **equally near** — a rounding tolerance and nothing else
     * (see [pickSharedPoint]): a point standing on a curve is the same arithmetic away as the curve is, and
     * this only keeps that equality from falling the wrong side of a floating-point comparison.
     */
    private val TIE_EPS = 1e-9

    /**
     * Where the current gesture is, in plane coordinates — the position the *local* pick tolerance is taken
     * at. Written once per pointer event by [enter], which is the one door every gesture comes through.
     */
    private var gestureAt: Vec2? = null

    /**
     * **The tolerance rule**: a click counts as landing on geometry within [tolPx] *pixels* of it, so the
     * plane-space radius is those pixels divided by the local scale at [at].
     *
     * Under the 2D camera the local scale is one number and this is the constant it always was. Under
     * perspective it varies across the plane — the far end of a wall can be a third of the scale of the
     * near end — so it is read at the cursor, once per event, and clamped by the projection so a nearly
     * edge-on plane cannot silently give a pick a reach of metres ([PlaneProjection.scaleClampedAt] is what
     * the status line then says out loud).
     *
     * Public because it is the assertable half of the rule: a test can compute the very number the pick
     * used instead of naming a constant of its own.
     */
    fun pickToleranceAt(at: Vec2): Double = tolPx / proj().scaleAt(at)

    private fun tolWorld() = pickToleranceAt(gestureAt ?: Vec2(0.0, 0.0))

    private fun ev() = Evaluator()

    /** Resolve a click position through [Snap] (grid included only while the grid is shown). */
    private fun snap(
        world: Vec2,
        exclude: (Element) -> Boolean = { false },
    ): SnapResult =
        if (!snapEnabled) {
            SnapResult(world, SnapKind.FREE)
        } else {
            // the grid a snap rounds to is the one that is *drawn*, so it follows the same local scale the
            // tolerance does — which in the 3D view is the scale where the cursor is
            Snap.resolve(doc, ev(), world, tolWorld(), if (showGrid) SceneRenderer.gridStep(proj().scaleAt(world)) else null, exclude)
        }

    /**
     * The plane point under [screen] — **the one door every pointer gesture comes through**, and the one
     * place a projection with no answer is turned into a refusal instead of a NaN.
     *
     * Three things happen here, once per event: the position is resolved through [proj], remembered as
     * where the local tolerance is taken ([gestureAt]), and — when the projection had to clamp its scale
     * there — reported, because a pick that has quietly stopped following the perspective is exactly the
     * kind of thing a user would otherwise blame on the geometry.
     */
    private fun enter(screen: Vec2): Vec2? {
        val p = proj()
        val at = p.toPlane(screen)
        if (at == null) {
            statusHint = OFF_PLANE_NOTE
            changed()
            return null
        }
        gestureAt = at
        if (p.scaleClampedAt(at)) {
            statusHint =
                "That part of ${doc.activeSpace.name} is too far away or too edge-on to point at precisely — " +
                "the pick tolerance is capped at ${Format.num(pickToleranceAt(at))} mm. Orbit or zoom in."
        }
        return at
    }

    /**
     * [enter] for a *release*: a gesture that ends off the plane still has to be tidied up (a drag released
     * past the horizon must not leave the editor mid-drag), so the last position on the plane stands in.
     */
    private fun leave(screen: Vec2): Vec2 = proj().toPlane(screen)?.also { gestureAt = it } ?: gestureAt ?: Vec2(0.0, 0.0)

    /** The geometry of the path being drawn — never a snap target for its own next vertex. */
    private fun activePathParts(): Set<Element> {
        val p = activePath ?: return emptySet()
        return (p.legs + p.vertices.mapNotNull { doc.elementFor(it.ref) }).toSet()
    }

    /**
     * Resolve a path click. For a vertex after the first, a curve snap is refined to where the *leg*
     * meets the curve: the leg can't bend to reach the cursor's projection, it runs on until it hits —
     * which is also the endpoint the attach then derives, so preview and result agree.
     */
    private fun pathSnap(raw: Vec2): SnapResult {
        val own = activePathParts()
        val s = snap(raw) { it in own }
        val last = activePath?.let { if (pathAtEnd) it.vertices.lastOrNull() else it.vertices.firstOrNull() } ?: return s
        if (s.kind != SnapKind.ON_CURVE) return s
        val from = (ev().valueOf(last.ref) as? PointValue)?.p ?: return s
        val axis = if (kotlin.math.abs(raw.x - from.x) >= kotlin.math.abs(raw.y - from.y)) 0 else 1
        val hit = Snap.axisCrossing(ev(), s.target!!, from, axis, raw) ?: return s
        return SnapResult(hit, SnapKind.ON_CURVE, s.target)
    }

    /**
     * Why the connection a path click asked for was refused, as a sentence. The document owns the reason
     * ([Document.connectRefusal]) so the same words serve the drag magnet, a release and this click; the
     * fallback covers a refusal the predicate cannot foresee (a geometric one, such as a leg parallel to
     * the curve it reached), because no route may end in silence.
     */
    private fun refusal(
        vertex: PointRef,
        s: SnapResult,
    ): String {
        val el = doc.elementFor(vertex)
        val target = s.target
        if (el == null || target == null) return "could not join here."
        val why = doc.connectRefusal(el, target)
        return "can't join ${doc.nameOf(el)} onto ${doc.nameOf(target)}${if (why == null) "" else " — $why"}."
    }

    /**
     * The point a placing click should use: reuse the snapped point, materialize the intersection, or
     * attach to the curve — so the placed point *depends* on what it was placed on. Only a miss (or a
     * grid snap) makes a new free point.
     */
    private fun placePoint(world: Vec2): PointRef {
        val s = snap(world)
        val ref =
            when (s.kind) {
                SnapKind.POINT -> s.target?.ref as? PointRef
                SnapKind.INTERSECTION -> doc.intersectNear(s.target!!, s.other!!, s.pos)
                SnapKind.ON_CURVE -> doc.pointOnCurve(s.target!!, s.pos)
                // a corner of the section: the accessor is materialized here, so what gets drawn hangs off
                // the solid and the plane and follows every edit to either (OP-17's section inputs)
                SnapKind.SECTION_CORNER ->
                    doc.sectionInput(doc.activeSpace, Document.SectionInput.CORNER, s.sectionCorner, s.sectionSolid)?.ref as? PointRef
                else -> null
            }
        if (ref != null) statusHint = "Snapped to ${s.label}"
        return ref ?: doc.freePoint(s.pos.x.mm, s.pos.y.mm)
    }

    // transient state
    private var dragTarget: Element? = null // a point, or a whole ortho leg
    private var dragFrame: Group? = null // a placed group being moved by its frame (OP-16 step 2)

    /**
     * The **jamb** of an opening this drag is sliding (OP-21). Held apart from [dragTarget] for the one
     * reason the whole feature turns on: a jamb is a line the plan *draws*, so there is no element to point
     * at — the pick resolved it into a handle over the thick path's own parameters instead.
     */
    private var dragJamb: Jamb? = null

    /**
     * The placed group whose **frame** the current jamb grab outranked (OP-16), for as long as that gesture
     * lasts. Held because the press clears the group selection — so by the time the drag reports itself, the
     * fact worth naming is no longer readable anywhere else.
     */
    private var jambStoleFrame: Group? = null
    private var dragStart: Vec2? = null // where on the geometry the drag began — the axis-lock origin
    private var grabOffset: Vec2 = Vec2(0.0, 0.0) // cursor minus that, so a grab never jumps

    /**
     * The same thing one axis further, for a handle whose DOF **leaves the working plane** (OP-25): what the
     * press held back so the drag does not jump ([Handle.grabHold]). Zero for every in-plane handle, whose
     * offset is [grabOffset] — one field rather than a second kind of drag, because a grab is a grab.
     */
    private var dragHold: Double = 0.0

    /**
     * Where every carrier-anchored rider stood when this drag began (OP-20). Captured at the press and never
     * refreshed: the compensation each move applies is measured from the *grab*, so a gesture is a pure
     * function of where it started and where the cursor is now — see [Document.compensateRiders].
     */
    private var dragRiders: List<Document.RiderAnchor> = emptyList()
    private var joinHints: List<Vec2> = emptyList() // corners this drag has flattened, marked on canvas
    private var terminalHint: Vec2? = null // the vertex a run just ended *on* — see [markTerminal]
    private var weldTarget: Element? = null // a point to weld onto
    private var attachTarget: Element? = null // a curve to attach onto
    private var haloPos: Vec2? = null // where the magnet ring is drawn
    private var panning = false
    private var lastScreen = Vec2(0.0, 0.0)
    private var downScreen: Vec2? = null // where the press landed, so a release can tell click from drag
    private var pendingToggle: Element? = null // Shift+click's toggle, applied on release (see [pointerUp])

    // ---- the pick cycle: one machine, applied on release exactly as the group/member cycle always was ----
    //
    // A SELECT click collects **every** candidate within tolerance ([pickAt]) and selects the first — which
    // is, by construction, what the press has always selected. Clicking the same spot again steps to the
    // next, wrapping, so nothing under the cursor is unreachable: the group *and* the member, the leg *and*
    // the jamb that crosses it, the area *and* the outline over it. The ranking is unchanged (a jamb still
    // wins off the centreline and loses along it) — cycling only makes the loser reachable.
    //
    // Applied **on release and only when the gesture did not move**, which is the discipline the group cycle
    // already used and the reason it exists: deciding a drag's subject after re-picking made "click a member,
    // then drag it" move the member instead of the group (OP-16).
    private var pendingPile: PickPile? = null // what the press found, for the release to apply
    private var pendingIndex = 0 // which of it this gesture will select
    private var pendingApplied = false // whether the press already selected it (the release then keeps its note)
    private var pendingNote = "" // what the press said, so a first click's status survives the release
    private var cycleAt: Vec2? = null // where the click owning the live cycle landed, in screen pixels
    private var cycleWorld: Vec2? = null // …and in world coordinates, so Tab can re-pick there
    private var cycleIndex = 0 // which candidate that click selected
    private var cycleCount = 0 // how many it found there
    private var marqueeFrom: Vec2? = null // rubber-band origin in world coordinates
    private var marqueeTo: Vec2? = null
    private var marqueeAdds = false // Shift+marquee adds to the selection instead of replacing it
    private val pickedPoints = ArrayList<PointRef>()
    private val pickedElements = ArrayList<Element>()
    private val pickedClicks = ArrayList<Vec2>()

    /**
     * What each click landed *on*, in slot order — see [Picks.landings]. Resolved at click time because that
     * is when the cursor exists; a tool that does not join simply never reads it.
     */
    private val pickedLandings = ArrayList<SnapResult?>()

    /**
     * The **wall side in effect at each pick**, for a tool that declares [ToolDef.sidePerPick] (the OP-21
     * extension). Collected here rather than read at build time because the option applies to the *next*
     * click, so a run whose curves take different sides is one gesture — and it rides the step's existing
     * `signs=`, which is where a scored discrete choice already belongs (OP-1/OP-18).
     */
    private val pickedSides = ArrayList<Int>()
    private var filledSlots = 0

    /**
     * The group that filled the armed tool's geometry slot, if a whole group did (OP-16) — kept only so
     * the status line can name it, since the *step* records the members and nothing else.
     */
    private var pickedGroup: Group? = null

    /**
     * The wall this *Thicken* application is **extending** (GitHub #7), when its first pick was one.
     *
     * The picks themselves are the ordinary ones — the wall's existing carrier curves are seeded into
     * [pickedElements] and every later click appends to them exactly as for a new wall — so what this holds is
     * only *where the result goes*: committing re-stamps this wall's own step instead of building a second
     * element (see [Document.thickNetworkExtension]). Gesture state, hence dropped by [resetPicks].
     */
    private var extending: ThickNetwork? = null

    /** The wall the armed tool is extending, for the shell and for tests. */
    val extendingWall: ThickNetwork? get() = extending

    /** Why the last pick was refused, when there is a better reason than "that click hit nothing". */
    private var pickRefusal: String? = null

    // ortho-path (turtle) state — the path itself is retained in the document while being drawn
    private var activePath: OrthoPath? = null
    private var pathAtEnd = true // which end of a resumed path is growing
    private var pathClosed = false
    private var previewSeg: Pair<Vec2, Vec2>? = null
    private var closePreview: List<Pair<Vec2, Vec2>> = emptyList() // the shape a closing click will make
    private var pathThickness: constructit.dsl.ScalarRef? = null // set for the WALL tool
    private var hoverWorld: Vec2? = null // last cursor position, so a typed length keeps its direction

    /**
     * What the armed tool would build if the next click happened where the cursor is (`ToolDef.preview`).
     *
     * Held here because it is *view* state of the gesture, exactly as the ortho band ([previewSeg]) is: it is
     * recomputed on every hover, on every typed value and on a count change, and it is dropped with the picks.
     * Nothing about it enters the document — see [refreshToolPreview].
     */
    private var toolPreview: List<PreviewShape> = emptyList()

    /** The live preview of the armed tool, for tests and for a shell that wants to inspect it. */
    val previewShapes: List<PreviewShape> get() = toolPreview

    /**
     * Digits typed into the drawing flow, for the two things a number can mean here (OP-13):
     *
     * - **direct distance entry**, while a leg is being previewed: the mouse supplies the leg's
     *   *direction*, the keyboard its *length*, so a leg placed by typing is indistinguishable from one
     *   placed by clicking;
     * - **a scalar a tool wants**, when a path is not being drawn: Enter turns the number into an
     *   ordinary parameter and hands it to the slot (see [commitTypedScalar]).
     *
     * One buffer, because the two can never be pending at once — a path is either active or it is not.
     */
    var numericEntry: String = ""
        private set

    /**
     * How many scalars the armed tool has been *typed* since it was armed, so the next number is read as
     * the next slot ("depth", then "angle"). Reset with the picks, like every other per-application state.
     */
    private var typedScalars = 0

    val pendingCount: Int get() = filledSlots

    /**
     * The geometry the armed tool has collected so far — what the canvas draws as *picked* (OP-14).
     *
     * A pick is not a selection: it is a half-finished operation, so it gets a mark of its own rather than
     * the selection highlight (see [SceneRenderer]). Exposed read-only, because the collector owns it.
     */
    val toolPicks: List<Element> get() = pickedElements

    fun setTool(id: String) {
        // an active path is a pending operation: switching tools finishes it (its thickness included)
        // rather than silently abandoning half-drawn state that no gesture ever committed
        finishPath()
        // **Sketch on face picks in the plan, so arming it goes there** (OP-17). A side face is named by a
        // solid's *footprint edge*, and only a solid extruded **vertically** has a planar side face at all
        // (`Geom3.sideFace` refuses every other axis) — which means the only space such a solid's plan is
        // ever drawn in is the plan itself. Refusing the click where the user stands would refuse a pick
        // that *cannot* succeed there; switching is therefore the honest answer, and it is said out loud.
        // It also closes a silent trap: in a face view the tool used to find nothing, and the drawing that
        // followed went quietly into the old space.
        val backToPlan = id == Tools.SKETCH_ON_FACE && !doc.activeSpace.isPlan
        if (backToPlan) setActiveSpace(Document.PLAN_SPACE)
        // **Arming the tool that is already armed keeps what it has collected.** Arming a *different* tool
        // abandons the half-finished one, which is honest — it is a different operation. Re-arming the same
        // one is not an operation at all: the palette button of the live tool is still there to be clicked,
        // the keyboard shortcut is still there to be pressed, and a user who does either mid-gesture means
        // "yes, this tool" and not "throw away my picks". Silently throwing them away also broke a promise
        // the editor makes out loud — *Sweep*'s "switch the plane between clicks and the picks are kept" —
        // since one stray click on the armed tool undid the very picks the switch had just reported keeping.
        // Abandoning stays reachable and stays explicit: that is what Escape is for ([key]).
        val rearming = id == toolId && !backToPlan && filledSlots > 0
        toolId = id
        if (!rearming) resetPicks()
        statusHint =
            if (backToPlan) {
                "Plan view — Sketch on face picks a solid's footprint edge, which is drawn here; click the edge you want"
            } else if (rearming) {
                // …and it says where the gesture stands, because a click that deliberately does nothing must
                // still answer "what happened?" — with the way to start over
                "${doc.toolDef(id)?.label ?: id}: $filledSlots pick${if (filledSlots == 1) "" else "s"} so far — " +
                    "press Escape to start over"
            } else {
                ""
            }
        if (applyToSelection()) return // it ran, and said what happened
        changed()
    }

    /**
     * Run the just-armed tool on **what is already selected**, if it declares [ToolDef.fromSelection] and
     * exactly one element is selected. True when it ran (successfully or refused out loud).
     *
     * The one generic thing a tool needed to reach a point no click can: a welded alias is hidden by
     * construction, and a merged dot names no single one of the points under it — see [ToolDef.fromSelection]
     * for the whole argument. Handled here, in the slot collector, rather than in the tool: what fills a slot
     * has always been the editor's business, and the tool still sees an ordinary [Picks].
     */
    private fun applyToSelection(): Boolean {
        val tool = doc.toolDef(toolId) ?: return false
        if (!tool.fromSelection || tool.slots.size != 1 || tool.scalars.isNotEmpty()) return false
        val el = selection?.takeIf { selected.size == 1 } ?: return false
        // whatever is selected, not only what the slot would accept: a wrong pick is refused *by name* in the
        // build, which is the whole difference between a refusal and a tool that quietly waits instead
        pickedElements.add(el)
        // and **no click is recorded**, because none happened: a click in a step is a choice a replay must
        // repeat (OP-18), and inventing a position here would state a choice nobody made
        filledSlots = 1
        return maybeCompleteTool(null)
    }

    // ---- sketch spaces: which 2D space this canvas is showing (OP-17) ----
    //
    // The active space is the document's (tools draw into it), and the *view* of each space is the
    // editor's: one camera per space, so switching back to the plan comes back to the plan's own zoom and
    // pan rather than to wherever the face view happened to be. Switching is view state — no step, no undo
    // entry (the file records which space each step was *built* in, see Document.noteSpace).

    private val spaceCameras = HashMap<String, Camera>()

    val activeSpace: SketchSpace get() = doc.activeSpace

    /**
     * Show [name] and draw into it. The selection and any half-collected picks are dropped, because they
     * name elements of a space whose coordinates this one does not share — the same reason a canvas shows
     * one space at a time.
     *
     * **Unless the armed tool spans spaces** ([ToolDef.crossSpace]), which the tools whose operands legitimately
     * live on different planes declare: a loft's sections, a sweep's run and profile, and — since a solid is a
     * body rather than a drawing — the two operands of a **boolean** (OP-22), which is the cut session 16 parked
     * for want of a gesture. Their picks are kept and each keeps the space it was made in. The status line says
     * so, because picks surviving a switch is otherwise invisible — the canvas is showing a different drawing
     * and the earlier picks are not in it.
     *
     * **And the drop says so too, for exactly the same reason the keep does.** Half a gesture vanishing was the
     * one event here that changed the editor's state and left nothing on screen to show it: the canvas was
     * going to look different anyway, so the user read a new drawing rather than a lost pick, and the next
     * click landed in a slot they thought was the second one. So a switch that drops picks now names the tool,
     * says that its picks do not span planes, and says the gesture starts over here — composed with the space
     * note, which is the sentence that was standing alone before (OP-3's discipline for a *value*, applied to
     * the one piece of gesture state that could disappear without a word).
     */
    fun setActiveSpace(name: String): Boolean {
        if (name == doc.activeSpace.name) return true
        val target = doc.spaceNamed(name) ?: return false
        val tool = doc.toolDef(toolId)
        val spanning = tool != null && tool.crossSpace && filledSlots > 0
        // …and what a drop costs: the picks, and the **typed scalars** that belong to them ([resetPicks]
        // retracts those too). A depth typed for an Extrude and then abandoned by a space switch vanished with
        // no pick to speak for it, which is the same silence one slot earlier.
        val keptTyped = pendingTypedParams.size
        val keptPoints = pickedPoints.toList()
        val keptElements = pickedElements.toList()
        val keptClicks = pickedClicks.toList()
        val keptLandings = pickedLandings.toList()
        val keptSlots = filledSlots
        spaceCameras[doc.activeSpace.name] = camera
        finishPath()
        doc.activeSpace = target
        clearSelection()
        resetPicks()
        camera = spaceCameras[name] ?: cameraFor(target)
        statusHint = spaceNote(target)
        if (!spanning && tool != null && (keptSlots > 0 || keptTyped > 0)) {
            val what =
                when {
                    keptSlots == 0 -> "the number you typed was dropped"
                    keptTyped == 0 -> "$keptSlots pick${if (keptSlots == 1) "" else "s"} dropped"
                    else -> "$keptSlots pick${if (keptSlots == 1) "" else "s"} and the number you typed dropped"
                }
            statusHint =
                "${tool.label}: $what — its picks do not span planes, so the gesture starts over here. " +
                spaceNote(target)
        }
        if (spanning) {
            pickedPoints.addAll(keptPoints)
            pickedElements.addAll(keptElements)
            pickedClicks.addAll(keptClicks)
            pickedLandings.addAll(keptLandings)
            filledSlots = keptSlots
            statusHint =
                "${tool!!.label}: $keptSlots pick${if (keptSlots == 1) "" else "s"} kept across the switch to " +
                "${doc.spaceLabel(target)} — carry on picking here. ${tool.help}"
        }
        changed()
        return true
    }

    /** How a space introduces itself — the frame's convention, said where the user is about to use it. */
    private fun spaceNote(space: SketchSpace): String =
        when {
            space.isPlan -> "Plan view — the drawing's own space (world XY)."
            // a station (OP-26, step 4): the run, how far along it, and the one number that moves it
            space.isStation ->
                "Sketching on ${space.name}, a station across ${space.station?.let { doc.nameOf(it) }} " +
                    "${Format.num(doc.spaceAlongMm(space))} mm along it (the run is " +
                    "${Format.num(doc.stationRunMm(space, ev()))} mm long): the origin is on the curve, the normal " +
                    "runs along it, and the axes are the moving frame's. Extrude builds along this plane's " +
                    "normal, Cut the other way. Retype the distance to slide the station along the run with " +
                    "everything on it." +
                    (if (space.anchor == null) " Nothing here to cut into: this plane passes through no solid." else "") +
                    sectionNote(space)
            // a plane at a height (GitHub #9): no hinge to name, so what it says is the height and what it cuts
            space.parallel ->
                "Sketching on ${space.name}, a plane parallel to ${space.from}, " +
                    "${Format.num(doc.spaceOffsetMm(space))} mm along its normal: the same u and v as ${space.from}, " +
                    "moved. Extrude builds along this plane's normal, Cut the other way. Retype the height to slide " +
                    "the plane and everything on it." +
                    (if (space.anchor == null) " Nothing here to cut into: this plane passes through no solid." else "") +
                    sectionNote(space)
            // a datum plane (GitHub #6): the hinge, the angle, and the one thing its sign decides
            space.isDatum ->
                "Sketching on ${space.name}, a datum plane on ${space.hinge?.let { doc.nameOf(it) }} at " +
                    "${Format.num(doc.spaceAngleDeg(space))}° from ${space.from}" +
                    (space.offset?.let { ", offset ${Format.num(doc.spaceOffsetMm(space))} mm along its own normal" } ?: "") +
                    ": u runs along that line, v rises out of " +
                    "${space.from} as the angle grows. Extrude builds along this plane's normal, Cut the other way — " +
                    "a negative angle swaps them. Retype the angle to tilt the plane and everything on it." +
                    facingNote(space) +
                    // what it can *cut* and what it can be *anchored on* are two questions since GitHub #9: a
                    // hinge that belongs to no solid still has every ancestor it passes through as context
                    (if (space.anchor == null) " Nothing here to cut into: its line is part of no solid." else "") +
                    sectionNote(space)
            else ->
                "Sketching on ${space.name}, the face of ${space.anchor?.let { doc.nameOf(it) }}: " +
                    doc.faceFrameNote(space) +
                    (space.originCorner?.let { " (moved onto section corner #${it + 1})" } ?: "") +
                    ". Cut here drills into the material; Extrude builds outward, as a boss." +
                    sectionNote(space)
        }

    /**
     * Which way this plane **fronts**, as a bearing in the space it was hinged out of ([Document.spaceFacing]
     * argues why a bearing rather than a side of the hinge).
     *
     * The gap this closes was reported as a revolve that swept the wrong way (session 61): a positive
     * *Extrude* or *Revolve* builds toward the front, the front is decided by which end of the hinge line the
     * user happened to draw first, and no view showed it. Said in the space's own note because that is where
     * the space introduces itself — and said **only** for a datum, because every other kind of space already
     * names its normal against something visible: a face's front points out of the material, a station's runs
     * along its curve, a plane at a height inherits the one it is parallel to, and the plan's is up.
     */
    private fun facingNote(space: SketchSpace): String {
        val facing = doc.spaceFacing(space, ev()) ?: return ""
        val which =
            facing.bearingDeg?.let { " Its front faces ${Format.num(it)}° in ${space.from}" }
                ?: if (facing.outward) {
                    " It lies flat on ${space.from}, fronting the same way"
                } else {
                    " It lies flat on ${space.from}, fronting the opposite way"
                }
        return "$which — that is where a positive Extrude or Revolve builds, and the 3D view marks it with a " +
            "tick standing out of the plane's origin."
    }

    /**
     * What this plane's **sections** offer, in one sentence: how many curves and corners can be clicked as
     * construction inputs, whether they are exact, and — where there are none — why (OP-17's section inputs).
     *
     * Said when the space is entered because it is the one thing the canvas cannot show about a drawing on a
     * plane: that the grey curves under the cursor are *inputs*, not a picture.
     *
     * Counted over **every ancestor solid the plane cuts** (GitHub #9), and it says how many solids those
     * are — the fact that made the plane useful without a pick, and the one a single count would hide.
     */
    private fun sectionNote(space: SketchSpace): String {
        val sections = doc.spaceSections(space, ev())
        if (sections.isEmpty()) {
            return " This plane cuts nothing that was built before it, so there is no context to anchor on."
        }
        val refused = sections.mapNotNull { it.second.inputsRefusal }
        var curves = 0
        var corners = 0
        var sampled = 0
        for ((_, s) in sections) {
            if (s.inputsRefusal != null) continue
            curves += s.edges.count { it.curve != null }
            corners += s.corners.count { it.at != null }
            sampled += s.edges.count { it.approximated }
        }
        if (curves == 0 && corners == 0) {
            return refused.firstOrNull()?.let { " The section here draws but cannot be anchored on: $it." } ?: ""
        }
        val exact =
            if (sampled == 0) {
                "exact"
            } else {
                "$sampled of them approximated (a ruled face's cut has no name here; a cylinder's has, since conics)"
            }
        val whose =
            if (sections.size == 1) {
                "Its section is"
            } else {
                "It cuts ${sections.size} solids (${sections.joinToString(", ") { doc.nameOf(it.first) }}) — together"
            }
        return " $whose $curves curve${if (curves == 1) "" else "s"} and $corners corner" +
            "${if (corners == 1) "" else "s"} ($exact) — click one while a tool is collecting to use it as an input." +
            (if (refused.isEmpty()) "" else " One of them draws but cannot be anchored on: ${refused.first()}.")
    }

    /**
     * The first view of a space: the plan centred as always, a face or datum framed on the reference
     * context it *is* ([Document.spaceOutline]), so switching over never lands on an empty view with the
     * plane off screen.
     */
    private fun cameraFor(space: SketchSpace): Camera {
        // the sections count as context too (OP-17's section inputs): a datum whose hinge is off to one side
        // must still frame the material it cuts, or the first view shows an empty plane beside the part —
        // and since GitHub #9 that is every ancestor it cuts, which is what a plane at a height has instead
        // of a hinge
        val section = doc.spaceSections(space, ev()).flatMap { (_, s) -> s.drawn.flatMap { GeomMath.tessellatePiece(it) } }
        val r =
            ((doc.spaceOutline(space, ev()) ?: emptyList()) + section).takeIf { it.isNotEmpty() }
                ?: return Camera.centered(canvasW, canvasH)
        val lo = Vec2(r.minOf { it.x }, r.minOf { it.y })
        val hi = Vec2(r.maxOf { it.x }, r.maxOf { it.y })
        val w = hi.x - lo.x
        val h = hi.y - lo.y
        // a datum's hinge is a *line*: it has extent along u and none across it, so the extent it does have
        // sets the zoom for both axes rather than the view collapsing
        val ew = if (w > 0.0) w else h
        val eh = if (h > 0.0) h else w
        if (ew <= 0.0 || eh <= 0.0) return Camera.centered(canvasW, canvasH)
        val scale = minOf(canvasW / (ew * 1.6), canvasH / (eh * 1.6)).coerceIn(0.02, 400.0)
        return Camera(canvasW / 2 - (lo.x + w / 2) * scale, canvasH / 2 + (lo.y + h / 2) * scale, scale)
    }

    /** One click of the *Sketch on face* tool: a solid's footprint edge names a side face (OP-17). */
    private fun faceClick(world: Vec2) {
        val from = doc.activeSpace.name
        val hit = doc.solidEdgeNear(world, tolWorld(), ev())
        if (hit == null) {
            statusHint = "Click a solid's footprint edge — that edge is the side face, seen from above"
        } else {
            val (solid, piece) = hit
            val why = doc.faceRefusal(solid, piece)
            val space = if (why == null) doc.createFaceSpace(solid, piece) else null
            if (space == null) {
                statusHint = "No sketch space on that edge of ${doc.nameOf(solid)}: ${why ?: "it has no planar side face there"}"
            } else {
                // the document already made it active; the *view* follows it here
                spaceCameras[from] = camera
                camera = cameraFor(space)
                clearSelection()
                checkpoint()
                statusHint = spaceNote(space)
            }
        }
        changed()
    }

    /** Parameters typed for the pending tool, not yet sealed by its checkpoint — see [commitTypedScalar]. */
    private val pendingTypedParams = ArrayList<ScalarEntry>()

    private fun resetPicks() {
        // a typed parameter whose tool never completed is retracted with the picks it belonged to —
        // left in place, the next unrelated checkpoint would silently absorb it into a foreign undo step
        for (e in pendingTypedParams) {
            if (doc.retractParameter(e)) {
                scalarPicks.removeAll { it === e }
                if (activeScalar === e) activeScalar = scalarPicks.lastOrNull()
            }
        }
        pendingTypedParams.clear()
        pickedPoints.clear()
        pickedElements.clear()
        pickedClicks.clear()
        pickedLandings.clear()
        pickedSides.clear()
        filledSlots = 0
        pickedGroup = null
        extending = null
        pickRefusal = null
        dragTarget = null
        dragFrame = null
        dragJamb = null
        jambStoleFrame = null
        dragStart = null
        grabOffset = Vec2(0.0, 0.0)
        joinHints = emptyList()
        terminalHint = null
        weldTarget = null
        attachTarget = null
        haloPos = null
        panning = false
        downScreen = null
        pendingToggle = null
        pendingPile = null
        resetCycle()
        marqueeFrom = null
        marqueeTo = null
        marqueeAdds = false
        activePath = null
        pathAtEnd = true
        pathClosed = false
        previewSeg = null
        closePreview = emptyList()
        pathThickness = null
        hoverWorld = null
        toolPreview = emptyList()
        numericEntry = ""
        typedScalars = 0
        snapHint = null
    }

    /**
     * The typed views of the selection's handle — the same writes its drag performs (OP-13). Only for a
     * selection of *one*: a field addresses one node, and with several elements selected there is no
     * single answer to show (nor to write back), so the inspector stays empty.
     */
    fun selectionFields(): List<HandleField> {
        // an opening's jamb addresses the interval's own parameters (OP-21) — position, width and the two
        // heights — which are what its drag writes, so the panel and the canvas stay one operation
        selectedJamb?.let { return it.handle(doc).fields() }
        // a placed group selected as a whole addresses its *frame* — the group's three degrees of freedom
        // (OP-16 step 2), which is exactly what dragging it writes, so drag and panel stay one operation
        selectedFrame()?.frameHandle?.let { return it.fields() }
        val one = selection?.takeIf { selected.size == 1 } ?: return emptyList()
        // …and the freedoms the *step* that made it owns (OP-13 × OP-18): a defaulted scalar nobody typed —
        // a coil's turn count, a tube's roll — which used to be an anonymous constant nothing could reach.
        // Appended, so a handle's own fields keep the order and the indices the panel has always shown.
        return (one.handle?.fields() ?: emptyList()) + doc.ownFields(one)
    }

    /** Short name for the selection, for the inspector header. */
    fun selectionLabel(): String {
        selectedJamb?.let { return "opening on leg ${it.interval.legIndex + 1} of ${doc.nameOf(it.path.footprint)}" }
        selectedFrame()?.let { return "frame of ${it.name}" }
        if (selected.size > 1) return "${selected.size} elements"
        return elementLabel(selection ?: return "")
    }

    /**
     * What one element is called in a sentence — the same words for the inspector and for the pick cycle.
     *
     * A **state** the element is in rides along here rather than in chrome of its own, which is the cheapest
     * honest place there is: the inspector's header and the pick cycle's status line are the two sentences a
     * user reads about a selection, and both are this one. Today there is exactly one such state — an
     * imported **open shell** (the JT import note under OP-9), which a user has to know about *before*
     * reaching for a boolean or a print export rather than from their refusal.
     */
    private fun elementLabel(el: Element): String {
        val kind =
            when (el.handle) {
                is OrthoEdgeHandle -> "leg"
                is OrthoCornerHandle -> "corner"
                else -> el.kind.name.lowercase()
            }
        return "$kind ${doc.displayName(el)}${doc.stateOf(el)?.let { " — $it" } ?: ""}"
    }

    /**
     * Why a press did not grab what it landed on — the drag's own refusal, or, for a **ghost**, the fact that
     * matters more (OP-18's *Show hidden*): the element is hidden. "It is fully determined by the
     * construction" is a true sentence about a hidden circle and the wrong one to read, because what the user
     * has just found is something that is not in the drawing at all.
     */
    private fun immovableNote(el: Element): String =
        if (!el.visible) {
            "${elementLabel(el)} selected — a ghost is not dragged"
        } else {
            // …and where what drives it is an **expression**, the refusal quotes it: "driven by the
            // construction" is true of a wired height (OP-25) and says nothing about which formula to go
            // and change (OP-7, session 71)
            explainImmovable(el, doc.nameOf(el), doc.ownFields(el)) +
                el.handle?.dragNodes.orEmpty().mapNotNull { doc.expressionDriving(it) }.distinct()
                    .joinToString("") { " Here $it." }
        }

    // ---- what the selection is built from, and what is built on it (see [Dependencies]) ----

    /**
     * The single element the dependency rows and the canvas highlights are about, or null.
     *
     * One element only, exactly as [selectionFields] is: two selections have two answers and the inspector
     * has one place to put them, and a highlight over the union of two cones is a highlight of everything.
     */
    private fun dependencySubject(): Element? = selection?.takeIf { selected.size == 1 }

    /** The elements the selection is built from, with their roles — the inspector's *built from* row. */
    fun selectionInputs(): List<InputRole> = dependencySubject()?.let { Dependencies.inputsOf(doc, it) } ?: emptyList()

    /** The elements built on the selection — the inspector's *used by* row. */
    fun selectionDependents(): List<Element> = dependencySubject()?.let { Dependencies.dependentsOf(doc, it) } ?: emptyList()

    /**
     * Name the selected element, or clear the name with a blank string (OP-7 one level up). One user-level
     * operation, hence one checkpoint; returns the name it actually **took**, or null when refused.
     */
    fun nameElement(
        el: Element,
        name: String,
    ): String? {
        val was = doc.userNameOf(el)
        val now = doc.nameElement(el, name)
        if (now == null) {
            statusHint = "${doc.nameOf(el)} was not built by a step of its own, so the file has nowhere to put a name for it"
            changed()
            return null
        }
        if (now != (was ?: "")) {
            checkpoint()
            statusHint =
                when {
                    now.isEmpty() -> "${doc.nameOf(el)} is back to its script name"
                    else -> "${doc.nameOf(el)} is now \"$now\""
                }
        }
        changed()
        return now
    }

    /**
     * Give a solid a **material** (Tier 1 of the appearance package) — the panel's own operation, shaped
     * exactly as [nameElement] is: one user-level change, one checkpoint, and what it actually took comes back
     * so the row can show the result rather than the request. Null clears it, back to the default.
     */
    fun setMaterial(
        el: Element,
        material: Appearance?,
    ): Appearance? {
        val was = doc.assignedMaterial(el)
        val now = doc.setMaterial(el, material)
        if (now == null) {
            statusHint =
                if (el.kind != ElementKind.SOLID) {
                    "${doc.nameOf(el)} is not a solid — a material is what a solid is made to look like"
                } else {
                    "${doc.nameOf(el)} was not built by a step of its own, so the file has nowhere to put a material for it"
                }
            changed()
            return null
        }
        if (now != (was ?: Appearance.DEFAULT)) {
            checkpoint()
            statusHint =
                if (material == null) {
                    "${doc.nameOf(el)} is back to the default material"
                } else {
                    "${doc.nameOf(el)}: ${now.color}, roughness ${Format.num(now.roughness)}, metallic ${Format.num(now.metallic)}"
                }
        }
        changed()
        return now
    }

    /**
     * Rename group [g] (OP-16 × OP-7). The same shape as [renameParameter]: uniquified, one checkpoint, and
     * the name it actually took comes back so the field can show the result rather than the request.
     */
    fun renameGroup(
        g: Group,
        name: String,
    ): String? {
        val was = g.name
        val now = doc.renameGroup(g, name)
        if (now == null) {
            statusHint = "$was has no step of its own in the file, so renaming it could not be saved"
            changed()
            return null
        }
        if (now != was) {
            checkpoint()
            val asked = name.trim()
            statusHint = "Renamed group $was to $now" + if (asked.isNotEmpty() && now != asked) " (\"$asked\" was taken or not one word)" else ""
        }
        changed()
        return now
    }

    /**
     * Hide or show the selected elements — **one recorded step per gesture** (OP-18's visibility reversal;
     * see [Document.setElementsVisible]), so it survives save/load and undoes like any other operation.
     * A welded alias stays hidden: it is hidden *by construction*, and showing it would draw a second point
     * on top of its master.
     */
    fun setSelectionVisible(visible: Boolean): Int {
        val n = doc.setElementsVisible(selectedElements, visible)
        if (n > 0) checkpoint()
        statusHint = if (n == 0) "Nothing to ${if (visible) "show" else "hide"}" else "${if (visible) "Shown" else "Hidden"} $n element${if (n == 1) "" else "s"}"
        changed()
        return n
    }

    // ---- flat named groups (OP-16 step 1) ----

    /**
     * Group the selection under [name] (auto-numbered when blank). Organizational only: no geometry,
     * no node and no handle changes — a member drags exactly as it did before.
     */
    fun groupSelection(
        name: String = "",
        commit: Boolean = true,
    ): Group? {
        if (selected.isEmpty()) {
            statusHint = "Select the elements to group first (Shift+click to add, or drag a box)"
            changed()
            return null
        }
        val clash = selected.firstNotNullOfOrNull { el -> doc.groupOf(el)?.let { el to it } }
        if (clash != null) {
            statusHint = "${doc.nameOf(clash.first)} is already in group ${clash.second.name} — an element is in at most one group; ungroup it first"
            changed()
            return null
        }
        val g = doc.createGroup(name, selectedElements)
        if (g == null) {
            statusHint = "Could not group that selection"
            changed()
            return null
        }
        selectedGroup = g // the selection now addresses the group it just became
        // [commit] is false when the caller is going to *place* this group as part of the same operation
        // (the create dialog's default): creating and placing are then one checkpoint, and one undo removes
        // both — see [confirmCreate].
        if (commit) checkpoint()
        statusHint = "Grouped ${g.members.size} elements as ${g.name}"
        changed()
        return g
    }

    // ---- the shared create dialog: group, or tool (OP-16 / OP-6's UI half) ----
    //
    // One dialog with two defaults, because the two ask the same question about the same closure —
    // which of the free sources the selection reaches belong to the thing being made. Group → macro is
    // the promotion path, so duplicating the record UI would be duplicating the analysis behind it.

    /** The open create dialog, or null. The shell renders it; every decision in it is [CreateDialog]'s. */
    var createDialog: CreateDialog? = null
        private set

    /**
     * Open the create dialog over the current selection. Refused only when nothing is selected — a
     * *tool* may still be impossible (a dimension in the selection, a placed group), which the dialog
     * reports rather than the button refusing, so the user can see why.
     */
    fun beginCreate(mode: CreateMode): CreateDialog? {
        if (selected.isEmpty()) {
            statusHint = "Select the elements first (Shift+click to add, or drag a box)"
            changed()
            return null
        }
        val members = selectedElements
        val d =
            CreateDialog.of(
                mode,
                members,
                doc.analyseMacro(members),
                { doc.nameOf(it) },
                { doc.placementClosure(it) },
                doc.elements.size,
            )
        createDialog = d
        statusHint = d.help
        changed()
        return d
    }

    fun cancelCreate() {
        createDialog = null
        statusHint = ""
        changed()
    }

    /**
     * The create dialog's **one-click closure** (OP-16): pull in everything the ticked freedoms are built on,
     * so the group can move independently. The shell's checkbox routes here and the dialog decides — the same
     * discipline every other control of that dialog follows, so it is driven headlessly too.
     *
     * Nothing is created yet: this grows the *prospective* membership, which Create then records as it records
     * any membership (OP-18).
     */
    fun includeCreateClosure(): Boolean {
        val d = createDialog ?: return false
        val n = d.closure.size
        if (!d.includeClosure()) {
            statusHint = "Nothing more to include — this group already moves as one"
            changed()
            return false
        }
        statusHint = "Added $n element${if (n == 1) "" else "s"} the group is built on — ${d.members.size} in all, and it can be placed"
        changed()
        return true
    }

    /**
     * Confirm the open dialog: a group of the selection (plus any ticked point, which is OP-16's
     * "membership = closure or inputs" answered the group way), or a macro whose ticked candidates are
     * its input ports. One checkpoint either way — declaring a tool is one user-level operation, and so is
     * making a movable part, even though that is a `group` step *and* a `place` step (see [CreateDialog]).
     */
    fun confirmCreate(): Boolean {
        val d = createDialog ?: return false
        if (!d.ready) {
            statusHint = d.blocker ?: "Nothing to create"
            changed()
            return false
        }
        val ok =
            if (d.mode == CreateMode.GROUP) {
                val members = d.members + d.checkedPoints.filter { p -> d.members.none { it === p } }
                select(members, d.members.firstOrNull())
                // held back: with the frame ticked, the placement below belongs to the same checkpoint
                val g = groupSelection(d.name, commit = false)
                // **Say at creation time what placement would refuse** (OP-16's honest-failure rule): the
                // freedom an unticked row leaves outside the group is exactly what makes the group immovable,
                // and it is invisible on canvas — so the reason belongs to the gesture that caused it, not to
                // a Place click much later.
                if (g != null) {
                    val n = doc.groupMembers(g).size
                    d.warnings = doc.placementWarnings(g)
                    val made = "Grouped $n element${if (n == 1) "" else "s"} as ${g.name}"
                    // The frame is the default, so the everyday group is movable the moment it exists. A
                    // refusal is **not** a failure of the gesture: the group is created flat and the reason is
                    // shown, which is the same honest answer Place gives — and a flat group is a purpose of
                    // its own (an array original), not a consolation.
                    //
                    // **The message therefore leads with what succeeded** (the user's report: the refusal was
                    // appended to a creation that had worked, so it read as total failure). The reason is
                    // asked for *before* placing so the two readings share one sentence ([placementRefusal]).
                    val refusal = if (d.framed) placementRefusal(g) else null
                    val placeNote =
                        if (!d.framed || refusal != null) {
                            null
                        } else {
                            placeGroup(g, commit = false)
                            statusHint
                        }
                    statusHint =
                        when {
                            refusal != null -> "$made — flat: $refusal"
                            placeNote != null -> "$made. $placeNote"
                            d.warnings.isEmpty() -> "$made — a named set, with no frame"
                            else -> "$made, but " + d.warnings.joinToString("; ")
                        }
                    checkpoint() // the whole operation: the group, and the frame it was born with
                }
                g != null
            } else {
                makeTool(d.name, d.members, d.checkedPoints, d.checkedScalars)
            }
        if (ok) createDialog = null
        changed()
        return ok
    }

    /**
     * Declare [members] a macro named [name] and switch to the tool it becomes (OP-6). Separated from
     * the dialog so a test — and a future scripted path — can make a tool without one.
     */
    fun makeTool(
        name: String,
        members: List<Element>,
        pointInputs: List<Element>,
        scalarInputs: List<ScalarEntry>,
    ): Boolean {
        val def = doc.defineMacro(name, members, pointInputs, scalarInputs)
        if (def == null) {
            statusHint = "Could not make a tool from that selection"
            changed()
            return false
        }
        checkpoint()
        setTool(def.toolId)
        val scalarNote = if (scalarInputs.isEmpty()) "" else " and ${scalarInputs.joinToString(", ") { it.name }} from the panel"
        statusHint =
            "Tool ${def.name}: click ${pointInputs.size} point${if (pointInputs.size == 1) "" else "s"}$scalarNote " +
            "to place an instance — editing the original updates every instance"
        changed()
        return true
    }

    /**
     * Retire a custom tool. Refused while instances exist, naming them: an instance is a function of its
     * definition (OP-6), so there is no consistent document with the definition gone and the instances
     * left — the same rule delete follows.
     */
    fun deleteMacro(def: MacroDef): Boolean {
        val instances = doc.instancesOf(def).flatMap { it.elements }
        if (instances.isNotEmpty()) {
            statusHint =
                "Can't remove tool ${def.name}: ${instances.size} instance element${if (instances.size == 1) "" else "s"} " +
                "still use it (${instances.take(4).joinToString(", ") { doc.nameOf(it) }}) — delete the instances first"
            changed()
            return false
        }
        if (!doc.removeMacro(def)) return false
        if (toolId == def.toolId) setTool(Tools.SELECT)
        checkpoint()
        statusHint = "Removed tool ${def.name} — the construction it was made from stays"
        changed()
        return true
    }

    /** Dissolve [g] — its elements stay, and stay selected. A placed group is unplaced first. */
    fun ungroup(g: Group): Boolean {
        val n = doc.groupMembers(g).size
        val wasPlaced = g.placed
        if (!doc.ungroup(g)) return false
        checkpoint()
        val note = if (wasPlaced) " (its frame went with it)" else ""
        statusHint = "Ungrouped ${g.name}$note — its $n element${if (n == 1) "" else "s"} stay"
        changed()
        return true
    }

    // ---- placed groups (OP-16 step 2) ----

    /**
     * Why [g] cannot carry a frame, in the user's words — null when it can (OP-16's honest-failure rule).
     *
     * **One sentence, two gestures.** Placing from the panel prefixes it with *"Can't place kitchen: "*;
     * creating a framed group that the same reason refuses prefixes it with what nevertheless *succeeded*
     * ("Grouped 12 elements as base — flat: …"), because the group is created either way and a message that
     * opens with a refusal reads as total failure — which is exactly how the reported one read.
     *
     * Three properties the wording owes the user, all of them what the report was about:
     *
     * - **every position is named as the drawing names it** (OP-18's naming authority — [Document.labelOf]
     *   answers for an ortho vertex, a shared coordinate and a junction alike, and never with a node id);
     * - the lists are **summarized** ([summarizeNames]): a refusal naming 27 consumers is a wall;
     * - and it says what to *do*, naming the create dialog's one-click closure rather than asking for a
     *   hand-pick of dozens of elements.
     */
    fun placementRefusal(
        g: Group,
        analysis: Placement = doc.analysePlacement(g),
    ): String? {
        if (analysis.conflicts.isNotEmpty()) {
            val points = analysis.conflicts.map { it.point }.distinct()
            val consumers = analysis.conflicts.map { doc.nameOf(it.consumer) }.distinct()
            val verb = if (points.size == 1) "is" else "are"
            return "${summarizeNames(points, POINTS_NAMED)} $verb also used by " +
                "${summarizeNames(consumers, CONSUMERS_NAMED, "more of the drawing")} outside it, " +
                "so this group cannot move independently — tick \"$INCLUDE_CLOSURE_LABEL\" in the Group " +
                "dialog to take ${if (points.size == 1) "what it carries" else "what they carry"} in with it, or leave it flat"
        }
        // the refusal survives only for a group that owns no freedom **at all** — of any kind: ortho paths and
        // the walls riding them are carried (OP-16's ortho-path bonus), and so are riders, polar offsets and
        // on-circle angles (see [Document.analysePlacement]), so owning no free *point* is not a reason
        if (!analysis.carriesSomething) {
            return "it owns no degree of freedom, so a frame would have nothing to move" +
                if (analysis.uncapturable.isEmpty()) "" else " (${analysis.uncapturable.joinToString("; ")})"
        }
        return null
    }

    /**
     * Place [g]: give it a frame and make the free points it owns frame-relative, so moving the group is
     * one write on the frame. Geometry is unchanged by construction — the retrofit is world-invariant.
     *
     * Refused, with the ambiguity named, when a free point the group owns is also used from outside: that
     * group cannot move independently, and which of the two should own the point is a modelling decision
     * the editor must not make silently (OP-16) — see [placementRefusal] for the words.
     */
    fun placeGroup(
        g: Group,
        commit: Boolean = true,
    ): Boolean {
        if (g.placed) {
            statusHint = "${g.name} is already placed — drag any member to move it, or Unplace it first"
            changed()
            return false
        }
        val analysis = doc.analysePlacement(g)
        placementRefusal(g, analysis)?.let {
            statusHint = "Can't place ${g.name}: $it"
            changed()
            return false
        }
        val result = doc.placeGroup(g)
        if (result == null) {
            statusHint = "Could not place ${g.name}"
            changed()
            return false
        }
        // false only when the group was *created* by the same operation — one checkpoint for both halves
        if (commit) checkpoint()
        // the deformable boundary is stated at placement time, because it is invisible on canvas: those
        // members are driven from outside the group and the frame does not move them (OP-16)
        val deforms =
            if (result.unfollowed.isEmpty()) {
                ""
            } else {
                " — ${result.unfollowed.joinToString(", ") { doc.nameOf(it) }} " +
                    "${if (result.unfollowed.size == 1) "is" else "are"} driven from outside and will not follow it"
            }
        val carried =
            listOfNotNull(
                "${result.captured} point${if (result.captured == 1) "" else "s"}".takeIf { result.captured > 0 },
                "${result.capturedPaths} path${if (result.capturedPaths == 1) "" else "s"}".takeIf { result.capturedPaths > 0 },
                // a rider is carried by being measured from its own carrier instead of from the world, which is
                // a change of *anchor* rather than of coordinates — so it is named as such (OP-4 case b)
                "${result.capturedRiders} rider${if (result.capturedRiders == 1) "" else "s"} re-anchored to their carrier"
                    .takeIf { result.capturedRiders > 0 },
                // a junction is a connection's own freedom, carried the same way and worth naming for the
                // same reason: what moved is the *anchor*, not a coordinate (OP-16 × OP-20)
                "${result.capturedJunctions} connection${if (result.capturedJunctions == 1) "" else "s"} re-anchored to the wall they meet"
                    .takeIf { result.capturedJunctions > 0 },
            ).joinToString(" and ")
        // the other honest boundary, stated where it is decided: a group whose runs follow the frame through
        // their connections moves rigidly but cannot be *turned* (OP-16 × OP-20), and that is invisible until
        // someone types an angle
        val turn = doc.turnRefusal(g)?.let { " — it moves as one, but cannot be turned (${it.substringAfter("cannot be turned: ")})" } ?: ""
        statusHint = "Placed ${g.name}: ${carried.ifEmpty { "its own freedom" }} now frame-relative$deforms$turn"
        changed()
        return true
    }

    /**
     * Unplace [g]: what it holds becomes free again where the frame's origin puts it, and the frame goes.
     *
     * A **turned** group comes back un-turned: an ortho path's legs are axis-aligned by construction, so
     * nothing but a frame can hold them at an angle, and unplacing gives back exactly what placing took
     * rather than tearing the group into the parts that can stay turned and the parts that cannot. Said out
     * loud, because it is the one part of unplacing that is not world-invariant (OP-16).
     */
    fun unplaceGroup(g: Group): Boolean {
        val unturns = doc.unturnsGroup(g)
        if (!doc.unplaceGroup(g)) {
            statusHint = "${g.name} is not placed"
            changed()
            return false
        }
        checkpoint()
        statusHint =
            if (unturns) {
                "Unplaced ${g.name} — it is unturned again, exactly as the frame took it (only a frame can hold a group turned)"
            } else {
                "Unplaced ${g.name} — its points are free again, exactly where they were"
            }
        changed()
        return true
    }

    /**
     * Select [el] outright — the panel's way in, as [selectGroup] is for a group.
     *
     * Needed because two elements can share a position exactly: a solid's footprint hint *is* the area
     * it was extruded from (OP-17), so a canvas click can only ever reach the topmost of the two. The
     * element tree addresses either one by name, which is the honest answer — biasing the pick would
     * just make the other one unreachable instead.
     *
     * Selecting an element of **another sketch space** (OP-17) shows the space it is in rather than a
     * selection the canvas cannot draw: the tree lists the whole document, so the honest answer to "that
     * one" is where to go and look at it.
     */
    fun selectElement(el: Element) {
        select(listOf(el), el)
        resetCycle() // the tree picked this, not a click on the canvas
        val space = doc.spaceOf(el)
        statusHint =
            // …and "another space" is asked as the panel asks it (OP-17, issue #2): a solid is listed in every
            // space because it is in none, so telling the user to switch spaces to see one would be wrong —
            // the 3D viewport is already showing it
            if (doc.listedIn(el)) {
                "${selectionLabel()} selected"
            } else {
                "${selectionLabel()} selected — it is drawn in ${space.name}, so switch the space to see it"
            }
        changed()
    }

    /**
     * The groups panel's row click. A tool waiting for a geometry slot takes the **group** (OP-16 — the
     * panel is as much an input as the canvas, OP-13); otherwise the row selects it, as it always did.
     *
     * One entry point, so the shell only routes: which of the two a click means depends on the armed tool
     * and on how many slots are already filled, neither of which the DOM knows anything about.
     */
    fun clickGroup(g: Group) {
        if (!feedGroupToSlot(g)) selectGroup(g)
    }

    /**
     * Why a whole group must not fill [tool]'s geometry slot, as a sentence — or null when it may.
     *
     * The one reason is size: a group multiplies a structural count by its member count, so the bound that
     * protects a single element from a mistyped count ([MAX_COUNT]) has to be applied to the *copies*. Said
     * rather than clamped, because the count is structural: quietly building a different number of copies
     * from the one asked for would be a different construction (OP-18).
     */
    private fun groupFanRefusal(
        tool: ToolDef,
        g: Group,
        members: Int,
    ): String? {
        val instances = toolCount(tool)
        val copies = members * (instances - 1)
        if (instances < 2 || copies <= MAX_COUNT) return null
        return "Group ${g.name} has $members elements, so $instances instances would build $copies copies — " +
            "more than $MAX_COUNT; lower the count, or array fewer elements"
    }

    /**
     * Fill the armed tool's pending [SlotKind.GEOMETRY] slot with the whole of [g], or return false when no
     * such slot is waiting (or the tool cannot take a group — see [ToolDef.groupOperand]).
     *
     * The naming *is* the pick, so unlike the canvas route this needs no prior selection: it is the panel's
     * answer to the same question a click on a member answers by position. The remaining slots then proceed
     * by canvas clicks exactly as usual — and a tool whose slots this completes builds on the spot, which is
     * why the completion path is the shared one.
     *
     * A pick is not a selection, so the selection is deliberately left alone: the members get the *picked*
     * mark (see [toolPicks]), which is what every other half-finished operation gets.
     */
    private fun feedGroupToSlot(g: Group): Boolean {
        val tool = doc.toolDef(toolId) ?: return false
        if (!tool.groupOperand || tool.slots.getOrNull(filledSlots) != SlotKind.GEOMETRY) return false
        val members = doc.groupMembers(g)
        // the group's own centre stands in for the click this pick did not have — see [Document.groupCentre]
        val at = doc.groupCentre(g)
        if (members.isEmpty() || at == null) return false
        // a refusal still *consumes* the click: falling through to selecting the group would replace the
        // reason with a selection note, and the row would look as if it had done nothing
        groupFanRefusal(tool, g, members.size)?.let {
            statusHint = it
            changed()
            return true
        }
        pickedElements.addAll(members)
        pickedGroup = g
        filledSlots++
        pickedClicks.add(at)
        if (filledSlots >= tool.slots.size) {
            if (!maybeCompleteTool(at)) statusHint = scalarPrompt(tool)
        } else {
            statusHint = "${groupFedNote(g)} ${tool.help} (${stillNeeded(tool)} more)"
        }
        changed()
        return true
    }

    /** How the status line says a whole group filled a geometry slot — one wording for both routes. */
    private fun groupFedNote(g: Group): String {
        val n = doc.groupMembers(g).size
        return "Group ${g.name} ($n element${if (n == 1) "" else "s"}) is the geometry — every member is copied."
    }

    /** Select every member of [g] — what clicking a member on the canvas does. */
    fun selectGroup(g: Group) {
        val members = doc.groupMembers(g)
        select(members, members.firstOrNull())
        selectedGroup = g
        resetCycle() // the panel picked this, not a click on the canvas
        val what = if (g.placed) " (placed — the panel shows its frame)" else ""
        statusHint = "Group ${g.name}: ${members.size} element${if (members.size == 1) "" else "s"} selected$what"
        changed()
    }

    /**
     * Hide/show a whole group — the same **per-element** step as [setSelectionVisible], over the group's
     * members.
     *
     * One rule rather than two: a group flag would be a second thing a file could say about visibility, and
     * the two would then have to be reconciled every time membership changed. As members, the state also
     * stays with the elements when the group is dissolved.
     */
    fun setGroupVisible(
        g: Group,
        visible: Boolean,
    ) {
        if (doc.setElementsVisible(doc.groupMembers(g), visible) > 0) checkpoint()
        statusHint = "Group ${g.name} ${if (visible) "shown" else "hidden"}"
        changed()
    }

    /** Whether every live member of [g] is currently drawn — the panel's toggle state. */
    fun isGroupVisible(g: Group): Boolean = doc.groupMembers(g).all { it.visible || doc.hiddenByConstruction(it) }

    /**
     * Write [value] (in the display unit of the field's dimension) into selection field [index].
     * False when there is no such field or it is driven by another node, which is the same answer
     * dragging gives.
     */
    fun writeSelectionField(
        index: Int,
        value: Double,
    ): Boolean {
        val f = selectionFields().getOrNull(index) ?: return false
        if (!f.writable) {
            // a refusal explains itself where the model has words for it (OP-16, OP-20): a frame that cannot
            // be turned is the one refusal a *frame* field has, and it is invisible on canvas
            selectedFrame()?.let { g ->
                doc.turnRefusal(g)?.let {
                    statusHint = "Can't turn: $it"
                    changed()
                }
            }
            return false
        }
        compensating { f.write(quantityOf(f.dim, value)) }
        checkpoint()
        // a write the geometry **bounded** has something to say — an opening clamped to its leg (OP-21) — and
        // it is the same note the drag reports, since typing and dragging are one operation (OP-13)
        doc.takeNote()?.let { statusHint = it }
        changed()
        return true
    }

    /**
     * Run [edit] with rider compensation around it — the **discrete twin** of a drag's compensation (OP-13:
     * typing and dragging are one operation, so a typed value that turns a host must not catapult what rides
     * it either). The reference is where the riders stand before the write, which for a single write is the
     * same thing a drag's grab time is.
     */
    private fun <T> compensating(edit: () -> T): T {
        val anchors = doc.riderAnchors()
        val result = edit()
        doc.compensateRiders(anchors)
        return result
    }

    // ---- panel parameter edits (OP-7): the shell routes, the controller decides ----

    /**
     * Write [value] (in the display unit of the parameter's dimension) into [e] — the parameters panel's
     * value field, spinner included.
     *
     * [commit] is the **undo-granularity seam**. A native number field fires an event per spinner tick, and
     * the geometry has to follow every one of them (that is what makes a spinner useful: the drawing moves
     * while the value is nudged) — but an undo step is worth taking only where the edit *commits*, on
     * change/blur/Enter. So a tick writes with [commit] false, changing the model and repainting without a
     * snapshot, and the committing event takes the checkpoint: one undo step per committed change, however
     * many ticks it took. The intermediate values were never operations, exactly as the intermediate
     * positions of a drag are not (a drag checkpoints on release too).
     *
     * False when the entry is not writable — a measurement, or a parameter wired to another one — which is
     * the same answer the field's disabled state shows.
     */
    fun setParameter(
        e: ScalarEntry,
        value: Double,
        commit: Boolean = true,
    ): Boolean {
        if (!e.editable || doc.isBound(e)) {
            // a derived value refuses the write **in the wired height's own words** (OP-25): the refusal
            // names what drives it, which for an expression is the expression
            if (e.editable) {
                statusHint =
                    doc.expressionOf(e)?.let { "${e.name} is derived: ${e.name} = $it — change what it reads, or clear the formula" }
                        ?: "${e.name} follows ${doc.boundEntry(e)?.name ?: "another value"} — free it first to type a number of its own"
                changed()
            }
            return false
        }
        // a parameter can drive a host's geometry (a leg length, an angle), so the same compensation applies
        compensating { doc.setParameter(e, quantityOf(dimensionOf(e.ref), value)) }
        if (commit) checkpoint()
        changed()
        return true
    }

    /**
     * What the panel's **formula** field does with what was typed into it (OP-7, the session-71 entry).
     * One entry point, three answers, and the rule between them is stated rather than guessed:
     *
     * - **blank** frees the parameter where it stands (the `unbind` step), and says so;
     * - a text that is **one number** — `12`, `10mm`, `15°`, and the negative of one — is *today's plain
     *   value edit*, not a binding: it writes the literal exactly as the value field does, so typing a
     *   number keeps precisely the behaviour it has always had and nobody freezes a constant into a
     *   formula by accident. A unit may be written, and must then be the parameter's own dimension;
     * - **anything else** is an expression, bound through [Document.bindParameter].
     *
     * The alternative rule considered was a leading `=` marking an expression. It was rejected because it
     * makes the *common* case carry the syntax, and because the number field beside this one is a native
     * spinner (OP-7's own decision) that this field must not become: what is typed here is a formula unless
     * it happens to be nothing but a number.
     */
    fun bindParameter(
        e: ScalarEntry,
        text: String,
    ): Boolean {
        val t = text.trim()
        if (!e.editable) {
            statusHint = "${e.name} is measured by the construction (OP-4), so it has no formula of its own"
            changed()
            return false
        }
        if (t.isEmpty()) {
            if (!doc.isBound(e)) return false
            val was = doc.expressionOf(e) ?: doc.boundEntry(e)?.name
            doc.unwireParameter(e)
            checkpoint()
            statusHint = "${e.name} is a free value again, where it stands" + (was?.let { " — it no longer follows $it" } ?: "")
            changed()
            return true
        }
        bareNumber(t)?.let { lit ->
            val dim = dimensionOf(e.ref)
            // a bare number is read in the panel's own display unit (OP-7), which is exactly what the value
            // field does with it; a unit written out is taken at its word, and must be this parameter's
            val q = if (lit.hadUnit) lit.q else quantityOf(dim, lit.q.value)
            if (q.dim != dim) {
                statusHint = "${e.name} is $dim, and $t is ${q.dim} — a plain number here is read in ${displayUnitName(dim)}"
                changed()
                return false
            }
            // a number typed at a value that is *derived* is the same refusal the value field gives, in the
            // same words — the formula field is not a back door around the binding
            if (doc.isBound(e)) return setParameter(e, 0.0)
            compensating { doc.setParameter(e, q) }
            checkpoint()
            changed()
            return true
        }
        if (!doc.bindParameter(e, t)) {
            statusHint = doc.takeNote() ?: "Can't bind ${e.name} to '$t'"
            changed()
            return false
        }
        checkpoint()
        // a binding whose *values* do not agree is legal and invalid, not refused (OP-3): it says so and heals
        val why = (Evaluator().eval(e.ref.node) as? EvalResult.Invalid)?.reason
        statusHint = "${e.name} = ${doc.expressionOf(e) ?: t}" + (why?.let { " — but $it" } ?: "")
        changed()
        return true
    }

    /**
     * [text] as the single literal it is, or null when it is an expression — the rule that keeps a typed
     * number exactly what it has always been. A leading minus is part of the number here, since `-3` is a
     * number a user writes and not an expression he composed.
     */
    private fun bareNumber(text: String): Expr.Lit? {
        val ast =
            try {
                ExprParser.parse(text)
            } catch (e: ExprError) {
                return null
            }
        (ast as? Expr.Lit)?.let { return it }
        val neg = (ast as? Expr.Apply)?.takeIf { it.op == "neg" }?.args?.singleOrNull() as? Expr.Lit ?: return null
        return Expr.Lit(-neg.q, neg.hadUnit)
    }

    private fun displayUnitName(dim: Dimension): String =
        when (dim) {
            Dimension.ANGLE -> "degrees"
            Dimension.LENGTH -> "millimetres"
            else -> "plain numbers"
        }

    /**
     * Rename parameter [e] to what was typed into the panel's name field (OP-7). One user-level operation,
     * hence one checkpoint; returns the name it actually **took** — uniquified, or the old one when the
     * field was blank — so the shell can show the result rather than the request.
     */
    fun renameParameter(
        e: ScalarEntry,
        name: String,
    ): String? {
        val was = e.name
        val now = doc.renameParameter(e, name)
        if (now == null) {
            statusHint =
                if (!e.editable) {
                    "${e.name} is a measurement — its name comes from the step that measures it, so it cannot be renamed"
                } else {
                    "${e.name} belongs to the step that created it, which has no place for a name in the file — " +
                        "so renaming it could not be saved"
                }
            changed()
            return null
        }
        if (now != was) {
            checkpoint()
            val asked = name.trim()
            statusHint = "Renamed $was to $now" + if (asked.isNotEmpty() && now != asked) " (\"$asked\" was taken or not one word)" else ""
        }
        changed()
        return now
    }

    /** Set a transient status-bar note (e.g. panel feedback). */
    fun note(message: String) {
        statusHint = message
    }

    /** Help line for the active tool — shown in the status bar whenever there's no transient hint. */
    fun currentHelp(): String {
        snapHint?.let { if (it.linked) return "Snap: ${it.label} — Alt to place freely" }
        if (toolId == Tools.SELECT) return Tools.SELECT_HELP
        val tool = doc.toolDef(toolId) ?: return ""
        // the panel and the keyboard are as much an input as the canvas (OP-13), so a tool still waiting
        // for a scalar says which one it wants next rather than describing clicks it cannot use yet
        if (toolScalars(tool) == null) return scalarPrompt(tool)
        val n = toolCount(tool)
        // Name the values it *will* consume. A tool takes the last picks in order, so with a parameter
        // already picked it is silently ready — which is convenient and invisible, and the invisible half
        // is what made people mis-size a feature and blame the tool.
        val using =
            if (tool.scalars.isEmpty()) {
                ""
            } else {
                val entries = toolScalars(tool).orEmpty()
                // **A structural default silences the slots behind it** ([ScalarSlot.structural]): an
                // unstated one names a *different construction*, and that construction has no inputs for
                // them — a *Revolve* with no angle is a complete revolution, which has no start, so it never
                // receives the offset slot at all. Naming a value the build will never see would be a promise
                // the tool cannot keep, which is the same reason a refusal has to name its own reason.
                val silent = tool.scalars.indices.firstOrNull { tool.scalars[it].structural && entries.getOrNull(it) == null }
                val named = if (silent == null) tool.scalars else tool.scalars.take(silent + 1)
                // a defaulted slot with nothing picked names the **default**, because that is what the tool
                // will use — the same promise as naming a picked parameter (see [ScalarSlot.default])
                " Using " +
                    named.mapIndexed { i, s ->
                        val e = entries.getOrNull(i)
                        "${s.name} = " + (e?.name ?: s.default?.let { "${Format.quantity(it)} (default)" } ?: "?")
                    }.joinToString(", ") +
                    // …and the whole contract for stating another, because "type a number" left out the half
                    // that finishes the gesture: a click uses what is typed (see [pointerDown])
                    " — to use another, type it and click (or press Enter)."
            }
        return (if (n == 0) tool.help else "${tool.help} (count $n)") + using
    }

    fun render(target: DrawTarget) {
        target.begin(canvasW, canvasH)
        draw(target)
        target.end()
    }

    /**
     * The drawing without opening a frame of its own — what the 3D view lays over the shaded solids it has
     * just painted (edit-in-3D slice 1, see [Viewport3.render]). Identical content either way, because the
     * projection is the only difference between the two views ([pointing]).
     */
    fun draw(
        target: DrawTarget,
        wPx: Double = canvasW,
        hPx: Double = canvasH,
    ) {
        val ev = ev()
        SceneRenderer.draw(
            doc, ev, proj(), target, wPx, hPx, showGrid, haloPos, previewSeg, selected,
            snapHint?.pos, joinHints, closePreview, terminalHint,
            previews = toolPreview,
            dimmed = if (dimScaffolding) doc.scaffoldingElements().toHashSet() else emptySet(),
            marquee = marqueeFrom?.let { f -> marqueeTo?.let { t -> f to t } },
            frames = selectedFrames(),
            picked = pickedElements.toHashSet(),
            // the selected opening's own drawing (OP-21), since it has no element to emphasize
            emphasis = selectedJamb?.let { doc.intervalOutline(it.path, it.interval, ev) } ?: emptyList(),
            inputs = selectionInputs().map { it.element }.toHashSet(),
            dependents = selectionDependents().toHashSet(),
            spotlight = spotlight?.let { setOf(it) } ?: emptySet(),
            // what the user hid, while the *Show hidden* toggle is on (OP-18) — drawn as ghosts, so it can
            // be found again
            ghosted = ghostElements(),
            scaleBar = showScaleBar,
            // the digits being typed, drawn at the cursor — see [pendingEntryEcho]
            entry = pendingEntryEcho(),
        )
    }

    /**
     * The frames to draw: those of placed groups with a member that is selected **and drawn** (OP-16
     * step 2). Shown on selection only — the frame is a handle, not geometry, so it appears when it is
     * addressable; and gated on visibility because a marker for a group nothing of which is on screen is
     * an orphan: hiding a group (or the selected member alone) must take its origin marker with it.
     */
    private fun selectedFrames(): List<FrameValue> {
        val ev = ev()
        return doc.groups
            .filter { g -> g.placed && doc.groupMembers(g).any { it in selected && it.visible } }
            .mapNotNull { g -> g.frameNode?.let { (ev.eval(it) as? EvalResult.Ok)?.value as? FrameValue } }
    }

    fun wheel(
        screen: Vec2,
        deltaY: Double,
    ) {
        // The wheel is the *view's*, and while the 3D view is doing the editing the view it belongs to is
        // that one ([Viewport3.wheel] never forwards it here). Zooming the invisible 2D camera instead would
        // silently move where the next 2D click lands.
        if (pointing != null) return
        camera = camera.zoomAt(screen, if (deltaY < 0) 1.1 else 1.0 / 1.1)
        changed()
    }

    /**
     * Press. [additive] is Shift held: in SELECT mode it makes the *click* toggle one element's
     * membership — which is why the toggle is applied on release and not here, since Shift held during
     * a drag means axis lock and must leave the selection alone.
     */
    fun pointerDown(
        screen: Vec2,
        button: PointerButton = PointerButton.PRIMARY,
        additive: Boolean = false,
    ) {
        downScreen = screen
        pendingToggle = null
        // the terminal cue lasts until the user's next *action*, which this is. Deliberately not cleared by
        // a hover: the mouse always moves right after a click, and a mark a stray move erases is not a mark
        terminalHint = null
        // panning is a button, not a mode: it works in every tool, and the shell reports Space+drag here
        if (button == PointerButton.MIDDLE) {
            // ...except in the 3D view, where "move the drawing under the cursor" is the 3D camera's own pan
            // (Space+drag there too — one habit, two views) and panning a 2D camera nobody is looking through
            // would do nothing visible
            if (pointing != null) return
            panning = true
            lastScreen = screen
            return
        }
        val world = enter(screen) ?: return
        // **A click takes the digits that are already typed** (OP-13, amended — see [commitTypedScalar]).
        //
        // Reported as *"type 20, click — and nothing happens at all"*: the press published its pick, the tool
        // was still waiting for the very number sitting in the buffer, and the status line asked for it again.
        // Typing and clicking were two gestures that had to be joined by a keystroke nobody was told about.
        // A number typed for an armed tool is a *statement about the click that follows*, so the click uses it
        // — the same [commitTypedScalar] Enter runs, which keeps it one parameter, one checkpoint, one undo.
        //
        // A **leg's** length is deliberately untouched (see [commitTypedLeg]): there the click states the
        // endpoint itself, so the two readings conflict rather than compose, and [typedScalarSlot] already
        // answers null while a path is being drawn. Escape still cancels either.
        if (button == PointerButton.PRIMARY && numericEntry.isNotEmpty() && typedScalarSlot() != null) {
            val waiting = filledSlots
            commitTypedScalar()
            // …unless that value *finished* the tool on the spot, which it does when every slot was already
            // clicked: then this press was the "use it", and reading it a second time would open a new gesture
            // with a stray pick in it.
            if (waiting > 0 && filledSlots == 0) return
        }
        if (toolId == Tools.SELECT) {
            // one search and one ranking: what this press grabs and what a repeat click cycles through are
            // the same list, so the precedence is written down once (see [pickAt])
            val pile = pickAt(world)
            // Where the live cycle stands here, if this press continues it — the one fact both halves of the
            // machine turn on: the *next* candidate is what a click takes, and the *standing* one is what a
            // drag moves (**the selection primes the drag**, see below).
            val standing = if (additive) null else standingIndex(screen, pile)
            // a Shift+click is a toggle, not a step of any cycle, so it takes no part in one
            pendingPile = if (additive) null else pile
            pendingIndex = if (standing == null) 0 else (standing + 1) % pile.candidates.size
            pendingApplied = false
            pendingNote = ""
            if (additive) resetCycle()
            // **Selection primes the drag.** A press that continues the cycle drags what the cycle selected,
            // overriding the ranking — which is what makes cycling worth having: step to the thing you want,
            // then drag exactly it. A *placed group selected as a whole* is the same rule and predates it (the
            // frame branch below), so it is left where it is rather than duplicated here.
            //
            // Predictability over convenience: when the primed selection cannot move, the press says why and
            // moves **nothing** — falling through to a different target would move something the user did not
            // point at. Clicking elsewhere, or Esc, gives the ranking back.
            //
            // Only the **primary** primes: a multi-selection has no single drag subject, and a bulk drag is a
            // separate feature (OP-16's "no bulk move" — that is the frame's job).
            val primed = standing?.let { pile.candidates[it] }
            val primedElement =
                when (primed) {
                    is Candidate.One -> primed.element
                    is Candidate.Whole -> primed.primary
                    else -> null
                }
            val jamb = (primed as? Candidate.Opening)?.jamb ?: pile.grabJamb.takeIf { primed == null }
            // The jamb took the grab. It is 1-DOF along its leg, so the offset the grab holds is the *along*
            // component of the cursor — the perpendicular one is projected onto the jamb's own line, which is
            // what stops the opening from jumping when the grab lands beside the line rather than on it.
            if (jamb != null) {
                // A jamb outranks even a *placed* group's frame (OP-16), which every other member drag moves
                // when the group is selected as a whole. It has to, or an opening in a placed wall would be
                // editable only after deselecting the group — the feature would work differently for the same
                // wall depending on how it was reached. What that costs is one invisible half, so it is said.
                jambStoleFrame = doc.placedGroupOf(jamb.path.footprint)?.takeIf { selectedGroup === it }
                selectJamb(jamb)
                dragJamb = jamb
                val anchor = onJambLine(jamb, world)
                grabOffset = world - anchor
                dragStart = anchor
                pendingApplied = true
                pendingNote = describeJamb(jamb)
                // where in the pile this jamb stands: the first candidate on a fresh press, and the one the
                // cycle is standing on when the press was primed by it
                statusHint = cycleNote(pendingNote, pile, standing ?: 0)
                changed()
                return
            }
            val movable = pile.movable
            // an immovable element is still selectable, so its values can be read and the reason shown
            val hit = pile.hit
            // a press on nothing starts a rubber band; what it covers is selected on release (OP-16)
            if (hit == null) {
                marqueeFrom = world
                marqueeTo = world
                marqueeAdds = additive
                pendingPile = null
                resetCycle() // a press on empty space is nobody's repeat
                changed()
                return
            }
            // A member of a **placed** group is addressed by the *gesture*, not by what the press does to
            // the selection, and the two halves of that split are decided at the two ends of the gesture:
            //
            // - **the drag's subject, here at press time**, from the selection this press *found* — the
            //   frame only when the group is already selected as a whole, otherwise the member's own
            //   handle. Grouping is invisible until something of it is selected (OP-16), so a drag on a
            //   member of a group nobody selected must move that member, exactly as if it were ungrouped;
            //   and a member deliberately reached alone keeps moving alone, as before. Decided *here* and
            //   never re-decided, because a release cannot undo what the drag has already moved.
            // - **the selection, at release** ([pointerUp]): a click applies the pick (cycling on a repeat), a
            //   drag leaves the member it moved selected. Both are click-vs-drag semantics, and only the
            //   release knows which of the two the gesture was (CLICK_SLOP_PX).
            val placedGroup = if (additive) null else doc.placedGroupOf(hit)
            var note = ""
            when {
                additive -> pendingToggle = hit
                // primed: what the press found is what the drag will move, so the press leaves it alone (the
                // release still steps the cycle on, exactly as for a group's deferred pick)
                primed != null -> {}
                // deferred whole: the frame decision below must read the selection this press *found*
                placedGroup != null -> {}
                else -> {
                    // the press selects the pile's **first** candidate — which is what it has always
                    // selected; a repeat click steps past it at release, never here
                    note = selectPick(pile.candidates.first())
                    pendingApplied = true
                }
            }
            // dragging the frame is one literal write, whatever the group contains, and the whole of it
            // follows rigidly — derived geometry included, since it is downstream (OP-16 step 2)
            val frameGroup = placedGroup?.takeIf { selectedGroup === it }
            if (frameGroup != null) {
                val anchor = frameGroup.frameHandle?.origin(ev()) ?: world
                dragFrame = frameGroup
                grabOffset = world - anchor
                dragStart = anchor
                statusHint = note
                pendingNote = note
                changed()
                return
            }
            // the primed subject, when the frame did not already take it: the cycled selection moves, or says
            // why it cannot — and either way nothing else is grabbed instead
            if (primedElement != null) {
                if (!primedElement.hasFreeDof || !primedElement.visible) {
                    statusHint = immovableNote(primedElement)
                    pendingNote = statusHint
                    changed()
                    return
                }
                dragTarget = primedElement
                val anchor = grabAnchor(primedElement, world)
                grabOffset = world - anchor
                dragHold = primedElement.handle?.grabHold(world, proj(), ev()) ?: 0.0
                dragStart = anchor
                dragRiders = doc.riderAnchors()
                pendingNote = "Dragging ${elementLabel(primedElement)} — what is selected takes the grab"
                statusHint = cycleNote(pendingNote, pile, standing ?: 0)
                changed()
                return
            }
            if (movable != null) {
                // a member of a placed group whose group is *not* what is selected drags on its own, which
                // is invisible on canvas until something moves — so it is said out loud, together with the
                // way to get the frame instead (OP-16)
                if (placedGroup != null && (selected.size != 1 || selection !== hit)) {
                    note =
                        "Dragging ${doc.nameOf(hit)} alone — group ${placedGroup.name} is not selected as a whole; " +
                        "click without moving to select it, then drag to move the frame"
                }
                dragTarget = movable
                // drag by the *offset* from where the grab landed, not to the cursor outright:
                // picking has a tolerance, so writing the cursor position made the geometry jump to
                // it on the first move and then follow from there
                val anchor = grabAnchor(movable, world)
                grabOffset = world - anchor
                dragHold = movable.handle?.grabHold(world, proj(), ev()) ?: 0.0
                dragStart = anchor
                // …and where everything riding a host stands right now, for the same reason: the drag may turn
                // a host, and a rider measured along that host has to be re-solved against where it *was*
                dragRiders = doc.riderAnchors()
                statusHint = note
            } else {
                statusHint = immovableNote(hit)
            }
            // …and what the press said is kept for the release: a first click must read exactly as it always
            // did (the reason an immovable element cannot be dragged included), with only the pile's position
            // appended when there is more than one thing here
            pendingNote = statusHint
            if (pendingApplied) statusHint = cycleNote(statusHint, pile, 0)
            changed()
            return
        }
        if (toolId == Tools.ORTHO_PATH || toolId == Tools.WALL) {
            if (toolId == Tools.WALL && activePath == null && activeScalar == null) {
                statusHint = "Wall: type a thickness (or click a parameter in the panel) first"
                changed()
                return
            }
            pathClick(world)
            return
        }
        if (toolId == Tools.OPENING) {
            openingClick(world)
            return
        }
        // one click, and a step of its own (OP-17): see [faceClick]
        if (toolId == Tools.SKETCH_ON_FACE) {
            faceClick(world)
            return
        }
        if (toolId == Tools.BREAK_LEG) {
            breakClick(world)
            return
        }
        runToolClick(world)
    }

    /** Curve kinds a plain break splits — an ortho leg is a segment too, and is handled before these. */
    private fun breakableKind(el: Element): Boolean =
        el.kind == ElementKind.SEGMENT || el.kind == ElementKind.ARC || el.kind == ElementKind.BEZIER ||
            el.isElliptic

    /**
     * One click of the Break tool, **dispatched by what it landed on**: an ortho leg keeps the jog logic
     * verbatim (OP-19 — a leg's break is a topology edit on its path, inserting a zero-length corner), and any
     * other segment, arc or Bézier is split as a construction ([Document.breakCurve]).
     *
     * The leg case is decided first and by the same search, so nothing about it changed: a leg *is* a segment,
     * so ranking the two searches together is the only way the two meanings cannot fight over one click.
     */
    private fun breakClick(world: Vec2) {
        val tol = tolWorld()
        val hit = HitTest.nearest(doc, ev(), world, tol) { doc.legOf(it) != null || breakableKind(it) }
        statusHint =
            when {
                hit == null -> "Click a segment, an arc or a Bézier to split it there (or a leg of an ortho path)"
                doc.legOf(hit) != null ->
                    if (doc.breakOrthoLegNear(world, tol)) {
                        "Segment broken — drag either half to open the corner"
                    } else {
                        // a break *replaces* the leg, so it is refused on a leg a tool is defined from
                        // (OP-6) — say which, since "click a segment" would be a lie there
                        if (doc.definesAMacro(listOf(hit))) {
                            "${doc.nameOf(hit)} is part of a tool's definition — breaking it would replace it; retire the tool first"
                        } else {
                            "${doc.nameOf(hit)} can't be broken there"
                        }
                    }
                else -> breakCurveAt(hit, world)
            }
        checkpoint()
        changed()
    }

    /**
     * Split the plain curve [el] at [world], and — when the break made the original redundant — **replace the
     * step that drew it**: drop that step and replay the remaining script (OP-18), so the file reads as the
     * two halves the drawing now has, exactly as a delete leaves a script that still constructs.
     *
     * The rewrite is the caller's half of the consumer rule because replaying swaps the whole document
     * ([adopt]); the document decides *whether* to (see [Document.BreakResult.replacesOriginal]) and, when
     * not, hides the original itself with a recorded step. Returns the status line to show.
     */
    private fun breakCurveAt(
        el: Element,
        world: Vec2,
    ): String {
        val res = doc.breakCurve(el, world) ?: return doc.takeNote() ?: "${doc.nameOf(el)} can't be broken there"
        val note = doc.takeNote() ?: ""
        if (!res.replacesOriginal) return note
        val root = doc.creatingStep(el) ?: return note
        val journalBefore = doc.journal.toList()
        doc.journal.removeAll(doc.dependentSteps(setOf(root)))
        val fresh =
            try {
                DocumentFormat.load(DocumentFormat.save(doc))
            } catch (e: Exception) {
                // the same all-or-nothing discipline a delete keeps: put the script back and say so, rather
                // than leaving a document that cannot be saved
                doc.journal.clear()
                doc.journal.addAll(journalBefore)
                return "Break failed: ${e.message}"
            }
        adopt(fresh)
        return note
    }

    /** One click of a path tool (ortho path / wall): start a chain, append a leg, or close the loop. */
    private fun pathClick(raw: Vec2) {
        val path = activePath
        val s = pathSnap(raw)
        val world = s.pos
        val what = if (toolId == Tools.WALL) "Wall" else "Ortho path"
        if (path == null) {
            // clicking an open end of an existing path *continues* that path. Starting a separate path
            // welded there instead left a phantom corner: two paths cannot coalesce a straight-on step,
            // so extending produced a corner where the drawing read as one straight run.
            val resume = if (s.kind == SnapKind.POINT) s.target?.let { doc.resumableEnd(it) } else null
            if (resume != null) {
                val (existing, atEnd) = resume
                activePath = doc.resumeOrthoPath(existing, atEnd)
                pathAtEnd = atEnd
                if (toolId == Tools.WALL) pathThickness = activeScalar?.ref
                statusHint = "$what: extending this path — a step straight on lengthens the last segment"
            } else {
                val started = doc.startOrthoPath(world)
                activePath = started
                pathAtEnd = true
                if (toolId == Tools.WALL) pathThickness = activeScalar?.ref
                // starting *on* something should mean starting *at* it — link, so the path follows that
                // geometry instead of merely beginning at its coordinates
                val linked = s.linked && doc.linkPathEnd(started.vertices.first().ref, s)
                // a placed path is not extended in place (OP-16): its coordinates are its group's local
                // ones, so this click starts a new run joined to it — said out loud, since the same click
                // continues an unplaced path
                val placedEnd = s.target?.let { t -> (t.handle as? OrthoCornerHandle)?.let { doc.pathFrameOf(it) != null } } == true
                statusHint =
                    if (linked && placedEnd) {
                        "$what starts on ${s.target?.let { doc.nameOf(it) }} — a placed path is not extended in place; " +
                            "this is a new run joined to it (unplace its group to extend it)"
                    } else if (linked) {
                        "$what starts on ${s.target?.let { doc.nameOf(it) }} (${s.label}); click the next point"
                    } else if (s.linked) {
                        // the click landed on geometry and the link was refused: say why here too, or the
                        // run would start looking joined and only come apart when something else moved
                        "$what: ${refusal(started.vertices.first().ref, s)} The run starts here unjoined; click the next point."
                    } else {
                        "$what: click the next point; click the start to close (Esc/double-click to finish)"
                    }
            }
        } else {
            // clicked the far end -> close the loop. Through the shared search like every other pick,
            // with a doubled tolerance because closing is a deliberate act worth making easy to hit.
            val far = (if (pathAtEnd) path.vertices.first() else path.vertices.last()).ref
            val onFar = HitTest.nearest(doc, ev(), world, tolWorld() * 2) { it.ref === far } != null
            if (onFar && path.vertices.size >= 3) {
                pathClosed = true
                finishPath()
                return
            }
            // a click back on the growing end is a repeat — the second half of a double-click, whose
            // dblclick then finishes the path — and must not leave a hairline leg behind
            val growing = (if (pathAtEnd) path.vertices.last() else path.vertices.first()).ref
            if (HitTest.nearest(doc, ev(), world, tolWorld()) { it.ref === growing } != null) {
                changed()
                return
            }
            val v = doc.extendOrthoPath(path, pathAtEnd, world)
            // reaching other geometry ends the run: connect this end and finish, the open analogue of
            // closing a loop by clicking the start
            if (v != null && s.linked) {
                if (doc.linkPathEnd(v.ref, s)) {
                    snapHint = null
                    finishPath() // which marks the terminus and says the run is over — see [markTerminal]
                    // the same sentence, naming what was reached rather than the vertex that reached it
                    statusHint = "$what ends on ${s.target?.let { doc.nameOf(it) }} (${s.label})$RUN_FINISHED"
                    changed()
                    return
                }
                // A connection that cannot be made **says so** (OP-20): the click looked exactly like the
                // end of a run, so refusing in silence left a leg placed, unjoined and still growing —
                // "the ending did not snap and did not finish the path". The rubber band is the honest
                // cue that drawing continues, and it is still there, so the sentence explains why.
                statusHint = "$what: ${refusal(v.ref, s)} The leg is placed but not joined; the run continues (Esc to finish)."
                hoverWorld = world
                previewSeg = null
                snapHint = null
                changed()
                return
            }
        }
        hoverWorld = world
        previewSeg = null
        snapHint = null
        changed()
    }

    /**
     * A key pressed while the canvas has focus, as a pure controller entry point. Digits feed the
     * numeric entry — a leg's length while a path is being drawn, otherwise the scalar the armed tool
     * wants; Enter commits it; Escape cancels a pending entry first, then finishes; a single letter arms
     * the tool that letter belongs to. Returns true when consumed.
     */
    fun key(key: String): Boolean {
        terminalHint = null // an action, like a press — see [markTerminal]
        val pathActive = activePath != null
        val digit = key.length == 1 && (key[0].isDigit() || key == ".")
        return when {
            pathActive && digit -> {
                numericEntry += key
                refreshPreview()
                changed()
                true
            }
            // the same digits, for the scalar a tool is missing: one mechanism for every scalar slot
            // (OP-13), so no tool has to know that its value can be typed
            digit && typedScalarSlot() != null -> {
                numericEntry += key
                statusHint = typedScalarPrompt()
                // the number is already in effect as far as the *picture* is concerned: the preview redraws
                // with it and the entry is echoed at the cursor, so what the next click will build is visible
                // where the user is looking rather than only in the status bar (OP-13)
                refreshToolPreviewAtHover()
                changed()
                true
            }
            key == "Backspace" && numericEntry.isNotEmpty() -> {
                numericEntry = numericEntry.dropLast(1)
                if (pathActive) {
                    refreshPreview()
                } else {
                    statusHint = typedScalarPrompt()
                    refreshToolPreviewAtHover()
                }
                changed()
                true
            }
            key == "Escape" && numericEntry.isNotEmpty() -> {
                numericEntry = ""
                if (pathActive) {
                    refreshPreview()
                } else {
                    statusHint = ""
                    refreshToolPreviewAtHover()
                }
                changed()
                true
            }
            key == "Enter" && numericEntry.isNotEmpty() -> if (pathActive) commitTypedLeg() else commitTypedScalar()
            // a repeating tool (Outline) commits on Enter and abandons on Escape
            key == "Enter" && !pathActive -> finishRepeatingTool()
            // Escape abandons the pending tool application — its picks *and* the parameters typed for it,
            // which [resetPicks] retracts, so cancelling leaves nothing behind (see [checkpoint])
            key == "Escape" && !pathActive && (filledSlots > 0 || pendingTypedParams.isNotEmpty()) -> {
                resetPicks()
                statusHint = ""
                changed()
                true
            }
            // With nothing pending, Escape drops what a click would otherwise have to un-click: the active
            // parameter, and the selection. The parameter belongs here because a pick that cannot be dropped
            // shadows every *defaulted* scalar slot for the rest of the session (see [clickScalar]).
            key == "Escape" && !pathActive && (selected.isNotEmpty() || activeScalar != null) -> {
                val had = selected.isNotEmpty()
                val dropped = clearActiveScalar()
                if (had) clearSelection()
                statusHint =
                    when {
                        had && dropped -> "Selection cleared, and $NO_ACTIVE_PARAMETER"
                        had -> "Selection cleared"
                        else -> NO_ACTIVE_PARAMETER
                    }
                changed()
                true
            }
            // The keyboard twin of clicking the same spot again (OP-13: nothing is reachable one way and not
            // the other). Only while a click's cycle is live — there is nothing to step otherwise.
            key == "Tab" && cycleWorld != null -> advanceCycle()
            key == "Escape" || key == "Enter" -> {
                finishPath()
                true
            }
            // a tool's own key: the palette without the round trip to it. Last, so nothing that was
            // already being typed loses a character to it.
            key.length == 1 && key[0].isLetter() -> {
                val id = Tools.byShortcut(key[0]) ?: return false
                if (id != toolId) setTool(id)
                statusHint = currentHelp()
                changed()
                true
            }
            else -> false
        }
    }

    /**
     * The scalar slot a typed number would fill, or null when the armed tool takes none (and while a path
     * is being drawn, where digits are the leg's length).
     *
     * Deliberately offered **even when the panel picks would already satisfy the tool**: a tool consumes
     * the last picks in order, so without this a value could never be *overridden* by typing once anything
     * had been picked — and silently reusing yesterday's radius is the friction, not the fix. Typing simply
     * creates the parameter and makes it the newest pick.
     */
    private fun typedScalarSlot(): ScalarSlot? {
        if (activePath != null) return null
        val tool = doc.toolDef(toolId) ?: return null
        if (tool.scalars.isEmpty()) return null
        return tool.scalars[minOf(typedScalars, tool.scalars.size - 1)]
    }

    /**
     * What the status line says while digits are pending — and it states **the whole contract**: the value,
     * and that the next click uses it (see [pointerDown]). "Enter to use it" was the whole sentence before,
     * which made a click read as *nothing happened* the one time it mattered.
     */
    private fun typedScalarPrompt(): String {
        val slot = typedScalarSlot() ?: return ""
        if (numericEntry.isEmpty()) return currentHelp()
        return "${slot.name} = ${entryText(slot)} — click to use it (or press Enter), Esc to cancel"
    }

    /** The pending entry as the status line and the canvas both say it: `20 mm`, `30°`, a bare `3`. */
    private fun entryText(slot: ScalarSlot): String = "$numericEntry ${unitWord(slot.dim)}".trim()

    /**
     * The digits typed so far, and where to draw them: **at the cursor**, because that is where the user is
     * looking when a click is the next thing they will do (OP-13 — the number and the click are one gesture,
     * so the number belongs beside the pointer and not only in a bar at the edge of the window).
     *
     * Null while a path is being drawn, where the rubber band already draws the typed length to scale, and
     * null with no cursor position yet — nothing is promised about a click that has no place.
     */
    private fun pendingEntryEcho(): Pair<Vec2, String>? {
        if (numericEntry.isEmpty()) return null
        val slot = typedScalarSlot() ?: return null
        val at = hoverWorld ?: return null
        return at to "${slot.name} = ${entryText(slot)}"
    }

    private fun unitWord(dim: Dimension): String =
        when (dim) {
            Dimension.LENGTH -> "mm"
            Dimension.ANGLE -> "°"
            else -> ""
        }

    /**
     * Turn the typed number into an ordinary **parameter** and hand it to the tool's scalar slot — the
     * generalization of direct distance entry to every scalar input (OP-13: typing and picking are the
     * same operation, so a value that was typed is afterwards editable, wireable and shareable like any
     * other).
     *
     * It is named after the slot and uniquified exactly as a panel parameter is (`depth`, `depth2`), so
     * nothing downstream can tell it was typed: it appears in the panel, rides the `param` step (OP-18)
     * and can be dragged into by anything that consumes a scalar.
     */
    private fun commitTypedScalar(): Boolean {
        val slot = typedScalarSlot() ?: return false
        val value = numericEntry.toDoubleOrNull()
        numericEntry = ""
        if (value == null) {
            statusHint = "That is not a number"
            changed()
            return true
        }
        typedScalars++
        val entry = doc.newParameter(slot.name, quantityOf(slot.dim, value))
        // Deliberately NOT a checkpoint of its own: the value was typed to feed the armed tool, so it is
        // *half* of that operation — the tool's own commit seals both as one snapshot, and an abandoned
        // gesture retracts it (see [checkpoint] for the whole rule). Registered here, before the pick is
        // published, because publishing may complete the tool outright when the clicks are already in —
        // and that completion's checkpoint is exactly what has to seal it.
        pendingTypedParams.add(entry)
        // through the ordinary setter, so a typed scalar *is* a panel pick as far as every tool is
        // concerned — including its completing a tool that was only waiting for this value
        activeScalar = entry
        // the typed value is now in effect, so the preview says what it will build with it
        refreshToolPreviewAtHover()
        // still pending means the tool has not consumed it yet, so say the value is in; a tool that
        // completed on the spot has already said its own thing
        if (pendingTypedParams.any { it === entry }) {
            statusHint = "${slot.name} = $value ${unitWord(slot.dim)} (parameter ${entry.name}) — edit it in the panel any time"
        }
        changed()
        return true
    }

    /**
     * Place the previewed leg. [previewSeg] already carries the exact typed length, so this is the
     * very same call a click makes — only the endpoint came from the keyboard.
     */
    private fun commitTypedLeg(): Boolean {
        val path = activePath ?: return false
        val end = previewSeg?.second ?: return false
        numericEntry = ""
        val placed = doc.addOrthoVertex(path, end) != null
        refreshPreview()
        statusHint = if (placed) "" else "That length would make a zero-length leg"
        changed()
        return true
    }

    /**
     * Recompute the armed tool's live preview for a cursor at [world] (`ToolDef.preview`).
     *
     * The **one** call site of the mechanism, and the whole of the controller's share in it: no tool has a
     * case here, and a tool without a preview simply leaves the list empty. Three rules are applied here
     * rather than in the tools, because they are properties of the *gesture* and not of any one tool:
     *
     * - it runs **from the first filled slot onward**, so an armed tool paints nothing until the user has
     *   committed to something (see [ToolDef.preview] for what that costs and why it is worth it);
     * - the cursor handed over is the **snapped** one where a snap is in effect, since that is where the click
     *   will land (OP-13's "the preview matches the result", already the ortho path's rule);
     * - the scalars handed over are the values **in effect** — picked, typed, or the slot's default — so the
     *   picture includes them.
     *
     * It cannot touch the graph: what it passes is a [PreviewContext], which holds no `Construction`. That is
     * the invariant `PreviewTest` asserts generically (`nodesCreated` flat across a sweep of hovers).
     */
    private fun refreshToolPreview(world: Vec2?) {
        hoverWorld = world
        val tool = doc.toolDef(toolId)
        val preview = tool?.preview
        if (tool == null || preview == null || world == null || filledSlots == 0) {
            toolPreview = emptyList()
            return
        }
        val ev = ev()
        val picks =
            Picks(
                pickedPoints.toList(),
                pickedElements.toList(),
                world,
                pickedClicks.toList(),
                count = toolCount(tool),
                landings = pickedLandings.toList(),
                // which view this gesture was made through — see [Picks.view]
                view = proj(),
            )
        toolPreview = preview(PreviewContext(doc, ev, picks, previewScalars(tool, ev), world, tolWorld(), justification))
    }

    /** Recompute the preview where the cursor last was — for a typed value or a count change. */
    private fun refreshToolPreviewAtHover() = refreshToolPreview(hoverWorld)

    /**
     * The value of each of [tool]'s scalar slots **as the next click would use it**: the digits **pending** for
     * it, else the parameter picked or typed for it, else the slot's declared default ([ScalarSlot.default]),
     * else null for a slot the tool is still waiting for. The same answer [currentHelp] words for the status
     * line.
     *
     * The pending half is the one that was missing, and it is the difference between a preview that promises
     * what the click will build and one that promises what it would have built before the number was typed —
     * *"the preview matches the result"* (OP-13) applies to a value being typed exactly as it applies to a
     * cursor being moved. Uncommitted, so nothing about it reaches the graph: it is one [Quantity] in a
     * [PreviewContext], which holds no `Construction` at all.
     */
    private fun previewScalars(
        tool: ToolDef,
        ev: Evaluator,
    ): List<Quantity?> {
        val entries = toolScalars(tool)
        // …and only where the digits *are* a scalar: while a path is being drawn they are a leg's length
        // ([typedScalarSlot] answers null there), which is no tool's scalar slot
        val pendingSlot =
            if (numericEntry.isEmpty() || activePath != null || tool.scalars.isEmpty()) {
                -1
            } else {
                minOf(typedScalars, tool.scalars.size - 1)
            }
        val pending = numericEntry.toDoubleOrNull()
        return tool.scalars.mapIndexed { i, slot ->
            val typing = if (i == pendingSlot) pending?.let { quantityOf(slot.dim, it) } else null
            val picked = entries?.getOrNull(i)?.let { (ev.eval(it.ref.node) as? EvalResult.Ok)?.value as? ScalarValue }
            typing ?: picked?.q ?: slot.default
        }
    }

    /**
     * The rubber-band leg: direction from the cursor (snapped to an axis), length from the typed
     * entry when there is one, so the preview shows exactly what Enter will place.
     */
    private fun refreshPreview() {
        val path = activePath
        val hover = hoverWorld
        if (path == null || hover == null) {
            previewSeg = null
            return
        }
        // hovering the far end offers to close: preview the *closed* shape, because closing moves a
        // corner into line and a band merely reaching for the start would promise something else
        val far = (if (pathAtEnd) path.vertices.first() else path.vertices.last()).ref
        if (path.vertices.size >= 3 && HitTest.nearest(doc, ev(), hover, tolWorld() * 2) { it.ref === far } != null) {
            closePreview = doc.orthoClosePreview(path)
            previewSeg = null
            if (closePreview.isNotEmpty()) statusHint = "Click to close the loop — the last corner moves into line with the start"
            return
        }
        closePreview = emptyList()
        val base = doc.orthoLegPreview(path, hover, pathAtEnd) ?: return
        val typed = numericEntry.toDoubleOrNull()
        previewSeg =
            if (typed == null) {
                base
            } else {
                val (from, to) = base
                val horizontal = kotlin.math.abs(to.x - from.x) >= kotlin.math.abs(to.y - from.y)
                val sign = if ((if (horizontal) to.x - from.x else to.y - from.y) < 0) -1.0 else 1.0
                from to if (horizontal) Vec2(from.x + sign * typed, from.y) else Vec2(from.x, from.y + sign * typed)
            }
        if (numericEntry.isNotEmpty()) statusHint = "Leg length $numericEntry mm — Enter to place, Esc to cancel"
    }

    /**
     * One click of the opening tool: add an interval feature to the thick path under the cursor. It adds
     * a description, not geometry — the footprint stays whole and the plan gap is drawn from it (OP-21).
     */
    private fun openingClick(world: Vec2) {
        val w = activeScalar
        if (w == null) {
            statusHint = "Opening: type a width (or click a parameter in the panel) first"
            changed()
            return
        }
        statusHint = if (doc.addIntervalAt(world, w.ref, tolWorld() * 2)) "Opening added" else "Click on a wall to place an opening"
        checkpoint()
        changed()
    }

    /** Finish the current path (Esc / double-click / close / tool switch); for WALL, thicken it. */
    fun finishPath() {
        val path = activePath ?: return
        if (pathClosed) doc.closeOrthoPath(path) // snaps the last coordinate to fit, adds the closing leg
        val t = pathThickness
        if (t != null && path.vertices.size >= 2) doc.buildThickPath(path, t, justification)
        val ended = markTerminal(path)
        doc.discardOrthoPath(path) // a path that never got a second vertex isn't a path
        activePath = null
        pathAtEnd = true
        pathClosed = false
        previewSeg = null
        closePreview = emptyList()
        pathThickness = null
        checkpoint() // the whole path — start, legs, close, footprint — commits as one operation
        statusHint = ended ?: ""
        changed()
    }

    /**
     * Mark the vertex a run **ended on** and say so, when it ended on something at all.
     *
     * A run that reaches geometry stops there by design, and the only signal used to be a quiet status
     * line — so a click meant as an intermediate corner silently ended the drawing, and the *next* click
     * (which then started a fresh path) read as nothing having happened. The cue is therefore generic:
     * whatever ended the run — welded to a point, attached to a curve, or closed — the terminus is marked
     * and the words say the run is finished, because what has to be noticed is that drawing stopped, not
     * which construction stopped it. The rubber band disappearing is the second half of the same signal:
     * a band means "still drawing", and there is none after a terminal click.
     *
     * Returns the status line for that ending, or null when the run merely stopped in the air (Esc,
     * double-click, tool switch), which needs no mark: nothing was reached.
     */
    private fun markTerminal(path: OrthoPath): String? {
        terminalHint = null
        if (path.vertices.size < 2) return null
        val what = if (toolId == Tools.WALL) "Wall" else "Ortho path"
        val closed = pathClosed
        val v = if (closed || !pathAtEnd) path.vertices.first() else path.vertices.last()
        val el = doc.elementFor(v.ref) ?: return null
        // a still-dangling end is not a terminus — it is exactly the end a later click may continue
        if (!closed && doc.orthoEndpoint(el) != null) return null
        terminalHint = (ev().valueOf(v.ref) as? PointValue)?.p
        return "$what ${if (closed) "closed on" else "ends on"} ${doc.nameOf(el)}$RUN_FINISHED"
    }

    // ---- the one pick cycle (OP-16 / OP-13 / OP-21) ----

    /**
     * Everything a SELECT click at [world] could mean, **ranked by the precedence a press has always used**,
     * with the two facts only the press can decide beside them (see [PickPile]).
     *
     * The order, written down once instead of grown three times:
     *
     * 1. **every point first**, by precedence rather than by distance, and the reason is the user's: *a point
     *    cannot dodge, a curve can be clicked elsewhere.* That holds however the point was born, so a derived
     *    one — an intersection, a midpoint, a ratio point, a key point — outranks the curve it sits on just as
     *    a free vertex does. Among the points the **draggable** ones come first, which is the grab rule read as
     *    a ranking (a dead handle must not steal a grab from what drives it).
     * 2. then the draggable **curves** (an ortho leg), then a draggable **annotation** — last of the three,
     *    so a dimension lying over the geometry it names never steals its grab.
     * 3. then an opening's **jamb** (OP-21), which competes with the curves *by distance* instead of by
     *    precedence — the [reach] cap: along the wall the leg is nearer, across it the jamb is. That
     *    remains the **ranking**; what cycling adds is that the loser is now reachable.
     * 4. then everything else the pointer can address at all, nearest first, so nothing drawn is unreachable.
     *
     * A hit that belongs to a group contributes **two consecutive entries** — the whole group, then that
     * member alone (OP-16: a group is what a click means first, and a member must stay reachable past it).
     *
     * **Selection rank and drag rank are not the same thing, deliberately.** This ranks what a *click*
     * addresses; [PickPile.movable] is what a *drag* moves, and it keeps preferring a movable handle exactly
     * as the press always has. So a derived point now takes the click while the curve under it still takes
     * the drag, and a point with no freedom simply has no drag — which is the honest answer either way: a
     * selection is for reading and deleting (OP-13's fields), a grab is for moving.
     */
    private fun pickAt(world: Vec2): PickPile {
        val ev = ev()
        val tol = tolWorld()
        // **A ghost is consulted only where nothing live is** (OP-18's *Show hidden*). The broadest search runs
        // first, exactly as it always did; only when it comes back empty does the same search run again with
        // the ghosts joined in, and the ghost set the rest of this pick uses is that one. So a hidden element
        // can never take a click from geometry that is really there — not even across the searches below,
        // which each ask a different question and would otherwise each answer it on their own.
        val liveHits = HitTest.nearestAll(doc, ev, world, tol, proj()) { it.selectable }
        val ghosts = if (liveHits.isEmpty()) ghostElements() else emptySet()
        val all =
            (
                if (ghosts.isEmpty()) {
                    liveHits
                } else {
                    HitTest.nearestAll(doc, ev, world, tol, proj(), ghosts) { it.selectable }
                }
            ).map { it.first }
        val points = all.filter { it.isPoint }.sortedBy { !it.draggable }
        val draggablePoint = points.firstOrNull { it.draggable }
        // An opening's jamb is not an element, so it is picked on its own and then *competes by distance* with
        // the curves. It has to: a jamb crosses its own carrier leg, so a rule of precedence would make one of
        // the two unreachable near the crossing — which is why the curve searches are capped at the jamb's
        // distance rather than at the tolerance. A *draggable* point still wins the grab outright, so the cap
        // does not apply when one is there — the ranking the press has always used, unchanged.
        val jamb = HitTest.nearestJamb(doc, ev, world, tol)
        val reach =
            if (draggablePoint == null && jamb != null) minOf(tol, HitTest.distanceToSegment(world, jamb.seg)) else tol
        val curves = HitTest.nearestAll(doc, ev, world, reach, proj(), ghosts) { it.isCurve && it.hasFreeDof }.map { it.first }
        val annotations = HitTest.nearestAll(doc, ev, world, reach, proj(), ghosts) { it.annotation != null && it.hasFreeDof }.map { it.first }
        // **A ghost is selected, never grabbed** (OP-18's *Show hidden*). Dragging is an edit, and the toggle
        // exists to find what was hidden and show it again — moving geometry that is not in the drawing would
        // change the model through a picture of it. The same line the tool slots draw, one gesture along.
        val movable = (draggablePoint ?: curves.firstOrNull() ?: annotations.firstOrNull())?.takeIf { it.visible }
        val ranked = points + (curves + annotations).filter { el -> points.none { it === el } }
        // everything else that can be addressed at all — an area, a solid's footprint hint, a curve the jamb's
        // cap kept out of the grab. Immovable here means "selectable but not draggable", which is a reason to
        // *say* something (see [explainImmovable]), not a reason to be unreachable.
        val rest = all.filter { el -> ranked.none { it === el } }
        val out = ArrayList<Candidate>()

        fun offer(el: Element) {
            doc.groupOf(el)?.let { out.add(Candidate.Whole(it, el)) }
            out.add(Candidate.One(el))
        }
        // **The body the pointer is on in the 3D view** ([solidUnderRay]) — the same route the `SOLID` slot
        // takes, and ranked *exactly where a body was already ranked*: in front of the other solids and behind
        // everything else. A point still cannot dodge, a curve still outranks what it lies on, and a curve in
        // space is still nearer the click than the body it runs over; all that changes is **which** solid
        // "that solid" means, which is the one question depth is better evidence for than distance. Slotted in
        // at the first solid rather than at the head, because taking a rank the ray never held would make this
        // a new precedence instead of a better answer inside the old one.
        //
        // …with one exception, which is the ghost rule read the other way (OP-18's *Show hidden*): where the
        // live search came back empty, everything above is a **ghost**, and a body that is really there may
        // never rank behind a picture of something that is not. Then the ray hit goes first outright.
        val rayed = solidUnderRay(world, ev)
        val liveBodyOverGhosts = rayed != null && ghosts.isNotEmpty()
        val tail = if (rayed == null || liveBodyOverGhosts) rest else rankedAmongSolids(rest, rayed)
        if (liveBodyOverGhosts) offer(rayed!!)
        ranked.forEach { offer(it) }
        if (jamb != null) out.add(Candidate.Opening(jamb))
        tail.forEach { offer(it) }
        // what the press addresses is what the first candidate names; what it *drags* may be something else —
        // and never a solid: a body has no freedom of its own here, exactly as in the plan ([movable] is
        // untouched, so a ray hit is selected and never grabbed)
        val primary = if (liveBodyOverGhosts) rayed else (ranked.firstOrNull() ?: tail.firstOrNull())
        return PickPile(out, movable, if (movable == null) jamb else null, primary)
    }

    /**
     * Where the live cycle **stands** in [pile] — the candidate the previous click selected — or null when
     * this press does not continue it, which is the ordinary first click.
     *
     * A press continues the cycle when it lands within [REPEAT_CLICK_PX] of the click that owns it *and* that
     * click's selection still stands: if anything else has selected in between (a marquee, a drag, the panel),
     * the cursor is over a pile the cycle no longer describes, and starting over is the honest answer.
     *
     * This is the whole of the first-click invariant — a press with nothing standing selects and drags exactly
     * what it always did — and it is also what makes cycling *useful*: the standing candidate is what the drag
     * is primed with (see [pointerDown]).
     */
    private fun standingIndex(
        screen: Vec2,
        pile: PickPile,
    ): Int? {
        if (pile.candidates.isEmpty()) return null
        val at = cycleAt ?: return null
        if ((screen - at).length() > REPEAT_CLICK_PX) return null
        if (cycleIndex !in pile.candidates.indices || !addresses(pile.candidates[cycleIndex])) return null
        return cycleIndex
    }

    /** Whether the selection currently addresses [c] — how the cycle knows where it stands. */
    private fun addresses(c: Candidate): Boolean =
        when (c) {
            is Candidate.Whole -> selectedGroup === c.group && selection === c.primary
            is Candidate.One -> selectedGroup == null && selected.size == 1 && selection === c.element
            // a Jamb is derived fresh from the drawing on every pass, so it is identified by what it *is*
            is Candidate.Opening ->
                selectedJamb?.let { it.interval === c.jamb.interval && it.atEnd == c.jamb.atEnd } ?: false
        }

    /**
     * Select exactly what [c] names and return the note for the status bar.
     *
     * History-free by construction: each candidate is a complete answer, so this is the whole of "what a
     * click selects" — the group/member reach and the jamb-vs-leg alternative are entries in one list rather
     * than two remembered states (OP-16, OP-21).
     */
    private fun selectPick(c: Candidate): String =
        when (c) {
            is Candidate.Whole -> {
                val members = doc.groupMembers(c.group)
                select(members, c.primary)
                selectedGroup = c.group
                if (c.group.placed) {
                    "Group ${c.group.name} is placed: dragging moves its frame (x / y / angle in the panel)"
                } else {
                    "Group ${c.group.name}: ${members.size} element${if (members.size == 1) "" else "s"}"
                }
            }
            is Candidate.One -> {
                select(listOf(c.element), c.element)
                val g = doc.groupOf(c.element)
                when {
                    g == null -> ""
                    g.placed -> "${doc.nameOf(c.element)} alone (of group ${g.name}) — dragging it moves it inside the frame"
                    else -> "${doc.nameOf(c.element)} alone (of group ${g.name})"
                }
            }
            is Candidate.Opening -> {
                selectJamb(c.jamb)
                describeJamb(c.jamb)
            }
        }

    /**
     * How a pick says where it stands in the pile it came from — `segment e12 (3 of 5 here — click again for
     * the next)`. A lone candidate gets nothing appended, so the everyday click reads exactly as before.
     */
    private fun cycleNote(
        base: String,
        pile: PickPile,
        i: Int,
    ): String {
        val n = pile.candidates.size
        if (n <= 1) return base
        val head = base.ifEmpty { pickLabel(pile.candidates[i]) }
        return "$head (${i + 1} of $n here — click again for the next)"
    }

    /** What one candidate is called, for the cycle's own note. */
    private fun pickLabel(c: Candidate): String =
        when (c) {
            is Candidate.Whole -> "group ${c.group.name}"
            is Candidate.One -> elementLabel(c.element)
            is Candidate.Opening -> "opening on leg ${c.jamb.interval.legIndex + 1} of ${doc.nameOf(c.jamb.path.footprint)}"
        }

    /** Step the cycle on, from wherever the last click left it. */
    private fun advanceCycle(): Boolean {
        val world = cycleWorld ?: return false
        val pile = pickAt(world)
        if (pile.candidates.isEmpty()) return false
        val i =
            if (cycleIndex in pile.candidates.indices && addresses(pile.candidates[cycleIndex])) {
                (cycleIndex + 1) % pile.candidates.size
            } else {
                0
            }
        statusHint = cycleNote(selectPick(pile.candidates[i]), pile, i)
        cycleIndex = i
        cycleCount = pile.candidates.size
        changed()
        return true
    }

    /** Forget the live cycle: the next click is a first click. */
    private fun resetCycle() {
        cycleAt = null
        cycleWorld = null
        cycleIndex = 0
        cycleCount = 0
    }

    /** How many things the last click found under the cursor — 0 when no cycle is live. */
    val pickCycleSize: Int get() = if (cycleAt == null) 0 else cycleCount

    /** Which of them it selected, counting from 1 — 0 when no cycle is live. */
    val pickCyclePosition: Int get() = if (cycleAt == null) 0 else cycleIndex + 1

    /** Take everything the rubber band covers (OP-16); Shift adds it to what was already selected. */
    private fun finishMarquee(
        from: Vec2,
        to: Vec2,
    ) {
        val hits = HitTest.within(doc, ev(), from, to, proj(), ghostElements())
        val kept = if (marqueeAdds) selectedElements else emptyList()
        select(kept + hits.filter { it !in kept }, hits.lastOrNull() ?: selection)
        statusHint =
            when {
                selected.isEmpty() -> "Nothing in the box"
                selected.size == 1 -> selectionLabel() + " selected"
                else -> "${selected.size} elements selected"
            }
    }

    fun pointerMove(screen: Vec2) {
        // panning first: it is a button, so it works under every tool — including the ones whose own
        // move handler returns early to show a preview
        if (panning) {
            camera = camera.pan(screen.x - lastScreen.x, screen.y - lastScreen.y)
            lastScreen = screen
            changed()
            return
        }
        val world = enter(screen) ?: return
        if (toolId == Tools.ORTHO_PATH || toolId == Tools.WALL) {
            val s = pathSnap(world)
            snapHint = s.takeIf { it.linked }
            // a lingering note would otherwise outrank the snap label, which is about the *next* click
            if (snapHint != null) statusHint = ""
            hoverWorld = snapHint?.pos ?: world
            refreshPreview()
            changed()
            return
        }
        if (placesAPoint()) {
            snapHint = snap(world).takeIf { it.linked }
            if (snapHint != null) statusHint = ""
            // the click will land on the snap, so that is where the preview is drawn from
            refreshToolPreview(snapHint?.pos ?: world)
            changed()
            return
        }
        // Every other previewing tool: the same live preview. Handled before the drag cases and returning,
        // exactly as the point-placing branch does — a drag belongs to SELECT, which has no tool to preview,
        // so there is nothing below this that a previewing tool could also want.
        if (doc.toolDef(toolId)?.preview != null) {
            refreshToolPreview(world)
            changed()
            return
        }
        when {
            dragFrame != null -> {
                // one write on the frame source moves the whole group, derived geometry included — the
                // O(1) move OP-16 is built around, and axis lock applies to it exactly as to a point
                val g = dragFrame!!
                g.frameHandle?.drag(axisLockedFrom(world - grabOffset), ev())
                changed()
            }
            // one opening slides along its leg (OP-21). No axis lock: the handle already has a single
            // direction of its own, exactly as an ortho leg does, so a lock could only make it inert
            dragJamb != null -> {
                val j = dragJamb!!
                j.handle(doc).drag(world - grabOffset, ev())
                // the clamp is the document's to explain, and it appears and disappears as the drag crosses
                // the leg's end rather than lingering afterwards
                statusHint = doc.takeNote() ?: describeJamb(j)
                changed()
            }
            dragTarget != null -> {
                val el = dragTarget!!
                val at = axisLocked(world - grabOffset, el)
                // one call for both kinds of handle: the view and the grab's hold are what a DOF that leaves
                // the plane needs (OP-25), and every in-plane handle ignores them (see [Handle.drag])
                el.handle?.drag(at, proj(), dragHold, ev())
                heightDragNote(el, at)
                // one seam for every route that can turn a host — an endpoint, a whole leg, a junction the
                // handle delegated to (OP-20): whatever the drag wrote, the riders are re-solved after it
                doc.compensateRiders(dragRiders)
                // a free point and an open path end can connect on release; nothing else can
                if (canConnect(el)) updateMagnet(el, at) else clearMagnet()
                // a jog dragged shut is *visually* already a single leg, so nothing needs to change
                // yet — but mark the corners that releasing will remove, and say so (OP-19)
                val flattened = flattenedEnds(el)
                joinHints = flattened.mapNotNull { (path, i) -> legPoint(path.legs[i]) }
                if (flattened.isNotEmpty()) {
                    val n = flattened.size
                    statusHint = "Release to join — ${if (n == 1) "the flattened corner" else "$n flattened corners"} will be removed (Alt to keep)"
                }
                changed()
            }
            marqueeFrom != null -> {
                marqueeTo = world
                changed()
            }
        }
    }

    fun pointerUp(screen: Vec2) {
        val from = marqueeFrom
        marqueeFrom = null
        marqueeTo = null
        if (from != null) {
            // a press-and-release on empty space is a click, and a plain click on nothing deselects —
            // a Shift+click there adds nothing and so leaves the selection alone
            when {
                movedSince(screen) -> finishMarquee(from, leave(screen))
                !marqueeAdds -> clearSelection()
            }
            resetCycle() // neither a marquee nor a deselect is a step of any pick cycle
            downScreen = null
            changed()
            return
        }
        // whether the gesture *moved* — read while [downScreen] still holds where the press landed, since
        // the deferred click semantics below clear it
        val moved = movedSince(screen)
        val toggle = pendingToggle
        val pile = pendingPile
        // whether the press deferred its pick — a member of a placed group, whose frame decision had to read
        // the selection the press *found* (OP-16)
        val deferred = pile != null && !pendingApplied
        pendingToggle = null
        pendingPile = null
        val dragged = dragTarget
        val movedFrame = dragFrame
        val movedJamb = dragJamb
        val weld = weldTarget
        val attach = attachTarget
        dragTarget = null
        dragFrame = null
        dragJamb = null
        dragStart = null
        dragRiders = emptyList()
        joinHints = emptyList()
        clearMagnet() // clear before rendering so the magnet halo doesn't linger
        panning = false
        // Shift+click toggles membership — but only when the gesture was a *click*: the same Shift is
        // axis lock, so a Shift-drag reshapes geometry and leaves the selection exactly as it was
        if (toggle != null && !moved) {
            if (toggle in selected) {
                selected.remove(toggle)
                if (selection === toggle) selection = selected.lastOrNull()
            } else {
                selected.add(toggle)
                selection = toggle
            }
            statusHint = if (selected.isEmpty()) "Nothing selected" else "${selected.size} element${if (selected.size == 1) "" else "s"} selected"
            changed() // a release otherwise repaints only when the drag changed the model
        }
        // The click half of the pick: this is where the cycle steps (and where a placed group's deferred pick
        // is applied at all). A press that *moved* is a drag and takes none of it — the same discipline
        // Shift's toggle uses, and the reason the group/member cycle was a release semantic to begin with.
        if (pile != null && pile.candidates.isNotEmpty() && !moved) {
            val i = pendingIndex.coerceIn(0, pile.candidates.size - 1)
            // The press already selected the first candidate and said its own sentence, which may be more
            // specific than any label here (why an element cannot be dragged, for one) — so a first click's
            // note is kept and only the deferred pick and the cycle's steps produce a new one.
            if (deferred || i != 0) {
                statusHint = cycleNote(selectPick(pile.candidates[i]), pile, i)
                changed()
            }
            cycleAt = screen
            cycleWorld = leave(screen)
            cycleIndex = i
            cycleCount = pile.candidates.size
        } else if (moved) {
            // a drag is not a click, so it leaves no cycle behind: the next click starts from the top
            resetCycle()
        }
        // …and the drag half of the deferred decision (OP-16): a press that *moved* never reaches the group —
        // its subject was the member's own handle (see [pointerDown]) — so what stays selected is the
        // element it moved, exactly as dragging ungrouped geometry leaves the dragged element selected. A
        // later weld or join may still replace this with what it produced; both run below.
        if (deferred && moved && dragged != null) select(listOf(dragged), dragged)
        downScreen = null
        if (dragged != null) {
            val ortho = dragged.handle is OrthoCornerHandle
            if (weld != null) {
                val ok = if (ortho) doc.weldOrthoEndpointToPoint(dragged, weld) else doc.weld(dragged, weld)
                // the magnet promised this join, so a release that quietly does nothing is the worst of the
                // three outcomes — the reason is the document's, and the same one a path click reports
                // the document's own sentence when it has one (it does since the weld speaks for the *tool*
                // too), so the note is consumed here rather than surfacing on the next unrelated operation
                statusHint =
                    if (ok) doc.takeNote() ?: "Joined ${doc.nameOf(dragged)} onto ${doc.nameOf(weld)}" else joinRefused(dragged, weld)
                changed()
            } else if (attach != null) {
                val ok = if (ortho) doc.attachOrthoEndpointToCurve(dragged, attach) else doc.attachToCurve(dragged, attach)
                statusHint = if (ok) "Attached ${doc.nameOf(dragged)} to ${doc.nameOf(attach)}" else joinRefused(dragged, attach)
                changed()
            }
        }
        if (movedFrame != null && moved) {
            // a frame move commits like any other drag: one operation, one undo step. It also *selects* the
            // group it moved — the press deferred its selection change, and having moved a group without it
            // ending up selected would leave the frame's fields unreachable right after using them.
            val members = doc.groupMembers(movedFrame)
            select(members, members.firstOrNull())
            selectedGroup = movedFrame
            checkpoint()
            statusHint = "Moved ${movedFrame.name}"
            changed()
        }
        // an opening's slide commits like every other drag: one operation, one undo step. What the status
        // says is left as the last move left it — either the opening's values or the clamp that stopped them
        if (movedJamb != null && moved) {
            checkpoint()
            changed()
        }
        jambStoleFrame = null
        if (dragged != null) {
            joinFlattenedEnds(dragged)?.let {
                select(listOf(it), it)
                statusHint = "Joined into ${doc.nameOf(it)} — the flattened corner is gone"
                changed()
            }
            // the release is where a drag commits — moves, welds, attaches and joins are one operation
            checkpoint()
        }
    }

    /** What to say when a release could not make the connection its magnet offered — see [refusal]. */
    private fun joinRefused(
        dragged: Element,
        target: Element,
    ): String {
        val why = doc.connectRefusal(dragged, target)
        return "Can't join ${doc.nameOf(dragged)} onto ${doc.nameOf(target)}${if (why == null) "" else ": $why"}"
    }

    /** Whether the pointer travelled far enough since the press for the gesture to count as a drag. */
    private fun movedSince(screen: Vec2): Boolean = downScreen?.let { (screen - it).length() > CLICK_SLOP_PX } ?: false

    /**
     * Apply [axisLock]: keep only the component the gesture is dominated by, relative to where the
     * drag started. A leg already moves on a single axis of its own, so the lock leaves it alone
     * rather than making it inert when the cursor happens to travel along it.
     */
    private fun axisLocked(
        world: Vec2,
        el: Element,
    ): Vec2 {
        if (el.handle is OrthoEdgeHandle) return world
        // ...and a height point's drag is a *ray*, not a position (OP-25): the plane point only aims it, so
        // pinning that point to an axis would bend the aim rather than restrict the height. The jamb's rule
        // for the same reason — a handle with a single direction of its own has nothing a lock could add.
        if (el.handle is HeightPointHandle) return world
        // ...and a rider on a coil (OP-26) is aimed too: the pointer names an *angle* along the curve — in the
        // 3D view through its own viewing ray — so pinning the plane point to an axis would bend the aim
        // instead of restricting the freedom, which is exactly the height point's case one dimension over.
        if (el.handle is OnHelixHandle) return world
        return axisLockedFrom(world)
    }

    /** [axisLocked] without an element: what a frame drag (OP-16) is restricted to. */
    private fun axisLockedFrom(world: Vec2): Vec2 {
        val start = dragStart
        if (!axisLock || start == null) return world
        return if (kotlin.math.abs(world.x - start.x) >= kotlin.math.abs(world.y - start.y)) {
            Vec2(world.x, start.y)
        } else {
            Vec2(start.x, world.y)
        }
    }

    /**
     * The flattened jogs at [el]'s own ends — see [Document.collapseCandidates]. Both ends can be
     * flattened by one drag (reverting a section that was broken out twice), so this is a list.
     * Suppressed while [snapEnabled] is off: Alt means "leave the model as I put it".
     */
    private fun flattenedEnds(el: Element): List<Pair<OrthoPath, Int>> =
        if (!snapEnabled) emptyList() else doc.collapseCandidates(el).filter { (path, i) -> doc.canJoinLeg(path, i, tolWorld()) }

    /**
     * Join away the jogs the drag flattened, at the dragged segment's own ends only. A leg has two
     * ends, so at most two joins; each one replaces the dragged leg with the merged one, which is then
     * what the other end is checked against.
     */
    private fun joinFlattenedEnds(dragged: Element): Element? {
        var target = dragged
        var merged: Element? = null
        var guard = 0
        while (guard++ < 2) {
            val (path, i) = flattenedEnds(target).firstOrNull() ?: break
            // keep the value of the half that did *not* move, so the dragged section snaps to it
            val draggedLeg = doc.legOf(target)?.second
            val stationary = listOf(i - 1, i + 1).firstOrNull { it != draggedLeg && it in 0 until path.legCount }
            val m = doc.joinCollapsedLeg(path, i, if (draggedLeg == null) null else stationary?.let { legPerp(path, it) }) ?: break
            merged = m
            target = m
        }
        return merged
    }

    /**
     * Leg [i]'s perpendicular coordinate — the value a join keeps when this is the stationary half.
     *
     * Read from the path's own node ([Document.legPerpValue]), not off the drawn segment: under a frame
     * (OP-16) the drawn position is a world one while the node a join writes holds a local coordinate, and
     * asking the node is right in both cases.
     */
    private fun legPerp(
        path: OrthoPath,
        i: Int,
    ): Double? = doc.legPerpValue(path, i)

    /**
     * What a **height point**'s drag has to say for itself (OP-25): the height it now stands at, or — when
     * the pointer's ray is too nearly in line with the height line to read one — that the change is held.
     *
     * Nothing at all for every other handle, which is why it is one line in the drag branch rather than a
     * case in it. The clamp is said out loud for the same reason a clamped pick tolerance is: a drag that
     * has quietly stopped following the cursor reads as a bug unless the view says why it cannot.
     */
    private fun heightDragNote(
        el: Element,
        at: Vec2,
    ) {
        val h = el.handle as? HeightPointHandle ?: return
        val ev = ev()
        statusHint =
            if (h.liftFrom(at, proj(), ev) == null) {
                "${doc.nameOf(el)}: the view looks along its height line, so the height cannot be read here " +
                    "(it is held). Orbit a little and drag again."
            } else {
                "${doc.nameOf(el)} height ${Format.num(h.fields().firstOrNull()?.read(ev)?.mm ?: 0.0)} mm"
            }
    }

    /**
     * Where on [el] a grab at [world] landed: the point itself, or the point of the curve under the
     * cursor. The difference between that and the cursor is held for the rest of the drag, so geometry
     * moves *with* the pointer instead of snapping to it.
     */
    private fun grabAnchor(
        el: Element,
        world: Vec2,
    ): Vec2 =
        (ev().valueOf(el.ref) as? PointValue)?.p
            ?: el.annotation?.anchor(ev())
            ?: Snap.legPoint(ev(), el, world)
            ?: world

    /**
     * [world] projected onto [jamb]'s own (infinite) line — where the grab holds on.
     *
     * The jamb runs *across* the carrier, so this keeps the perpendicular component of the cursor and pins
     * the along-leg one to the jamb itself: the offset the grab then holds is purely along the leg, which is
     * the only component the drag can write anyway.
     */
    private fun onJambLine(
        jamb: Jamb,
        world: Vec2,
    ): Vec2 {
        val across = jamb.seg.b - jamb.seg.a
        if (across.length() < Vec2.EPS) return jamb.seg.a
        val dir = across.normalized()
        return jamb.seg.a + dir * (world - jamb.seg.a).dot(dir)
    }

    /** What an opening reads as in the status bar: its two leg-relative values, in the panel's units. */
    private fun describeJamb(jamb: Jamb): String {
        val ev = ev()
        val pos = ev.valueOf(jamb.interval.position) as? ScalarValue
        val width = ev.valueOf(jamb.interval.width) as? ScalarValue
        val where = "${Format.num(pos?.q?.mm ?: 0.0)} mm along leg ${jamb.interval.legIndex + 1}"
        val edge = if (jamb.atEnd) "far jamb sets the width" else "near jamb slides the opening"
        // what the grab took the gesture *from*, for the whole gesture — see [jambStoleFrame]
        val instead =
            jambStoleFrame?.let { " (this opening, not group ${it.name} — grab the wall away from a jamb to move the frame)" }
                ?: ""
        return "Opening at $where, ${Format.num(width?.q?.mm ?: 0.0)} mm wide — $edge$instead"
    }

    /** Where a (possibly zero-length) leg sits, for marking it on the canvas. */
    private fun legPoint(el: Element): Vec2? =
        (ev().valueOf(el.ref) as? constructit.core.SegmentValue)?.seg?.let { Vec2((it.a.x + it.b.x) / 2, (it.a.y + it.b.y) / 2) }

    /**
     * Whether dropping [el] can join it to something: a point that **still owns its own coordinates**, or an
     * open path end.
     *
     * The test is exactly the one the release performs — a bound point has nothing left to bind. That covers
     * every way a point can already follow something in one predicate: held frame-relative by a placed group
     * (OP-16), welded, attached, or *re-parameterized onto an anchor* (OP-4 case b). The last of those was the
     * hole: a relative point kept `ElementKind.POINT`, so the magnet offered an attach the release then
     * refused — and since a refused attach was still recorded, dragging such a point round the circle it
     * defines wrote a junk `attach` step per drag (the duplicated steps in the reported file). No halo may
     * offer a join that the release would refuse (OP-20's rule for the drag magnet).
     *
     * An end of a **placed** path is out for the same reason one level up: a junction is a world position and
     * that end's coordinates are its group's local ones (see `Document.bindCornerToJunction`).
     */
    private fun canConnect(el: Element): Boolean =
        (el.kind == ElementKind.POINT && isFreeSource(el.ref.node)) ||
            (el.handle as? OrthoCornerHandle)?.let { it.isEndpoint && doc.pathFrameOf(it) == null } == true

    /**
     * True when the active tool's next slot creates a point — the case a snap marker is useful for.
     *
     * Resolved through the same index [runToolClick] uses, repeating tools included: a repeating tool's one
     * slot repeats, so `filledSlots` runs past the list after the first pick and the marker used to go out
     * exactly where the gesture goes on placing. The marker is how the user *sees* that an empty click will
     * place, so it and the click must read the slot the same way ([Tools.placesPoint]).
     */
    private fun placesAPoint(): Boolean {
        val tool = doc.toolDef(toolId) ?: return false
        val slot = (if (tool.repeating) tool.slots.lastOrNull() else tool.slots.getOrNull(filledSlots)) ?: return false
        return Tools.placesPoint(slot)
    }

    private fun clearMagnet() {
        weldTarget = null
        attachTarget = null
        haloPos = null
    }

    /**
     * Magnet: prefer a nearby point to weld onto; otherwise a nearby curve to attach onto. Sets
     * [haloPos] to the point (or the projection onto the curve) where the drag will land.
     */
    private fun updateMagnet(
        dragged: Element,
        world: Vec2,
    ) {
        val ev = ev()
        // points win over curves at equal distance, so they are asked for first. Both go through the
        // one shared search, so a position past a segment's end is as much a miss here as it is when
        // placing geometry — the magnet used to measure to the infinite carrier line and match anyway.
        HitTest.nearest(doc, ev, world, tolWorld()) { it !== dragged && it.isPoint }?.let { point ->
            // a circular join is refused on release, so it must not be offered here either — and say why,
            // because a point that lights up for everything else and not for this one reads as a glitch
            if (doc.joinWouldCycle(dragged, point)) {
                clearMagnet()
                statusHint = "Can't join ${doc.nameOf(dragged)} onto ${doc.nameOf(point)}: ${doc.nameOf(point)} already follows ${doc.nameOf(dragged)}."
                return
            }
            weldTarget = point
            attachTarget = null
            haloPos = (ev.valueOf(point.ref) as? PointValue)?.p
            return
        }
        val curve =
            HitTest.nearest(doc, ev, world, tolWorld()) {
                it !== dragged && it.isCurve && doc.curveProjection(dragged, it) != null
            }
        if (curve != null) {
            attachTarget = curve
            weldTarget = null
            haloPos = doc.curveProjection(dragged, curve)
        } else {
            clearMagnet()
        }
    }

    /**
     * Try to begin an **extension** at [world]: the *Thicken* tool's first click landing on a wall that is
     * already there (GitHub #7). Returns whether the click was consumed — by entering extension mode, or by a
     * refusal that says why, since a silent no-op on a click that plainly hit a wall is the worst answer.
     *
     * The wall's existing carrier curves and sides are seeded into the ordinary pick lists, so every later
     * click appends exactly as it does for a new wall and nothing downstream of here has a second case.
     */
    private fun startExtension(world: Vec2): Boolean {
        // Alt has always meant *leave the model as I put it* (it declines a pattern's orbit), and it means the
        // same thing here: build a new wall over these curves rather than growing the one already on them.
        if (!snapEnabled) return false
        val hit = HitTest.nearest(doc, ev(), world, tolWorld()) { doc.thickNetworkOf(it) != null } ?: return false
        val tn = doc.thickNetworkOf(hit) ?: return false
        val base =
            doc.thickNetworkBase(tn) ?: run {
                // an ortho-carrier wall names the tool that *does* extend it — never a silent refusal
                statusHint = doc.takeNote() ?: "That wall cannot be extended with ${doc.toolDef(toolId)?.label}"
                changed()
                return true
            }
        extending = tn
        pickedElements.addAll(base.curves)
        pickedClicks.addAll(base.clicks)
        base.curves.forEach { pickedLandings.add(null) }
        pickedSides.addAll(base.sides.map { it.ordinal })
        filledSlots = base.curves.size
        // the wall's own thickness, not whatever is in the tool's field: shown in the panel, so the number the
        // user reads is the number the wall has (the step keeps it either way)
        activeScalar = base.thickness
        val n = base.curves.size
        statusHint =
            "Extending wall ${doc.displayName(tn.footprint)} ($n carrier curve${if (n == 1) "" else "s"}, " +
            "thickness ${base.thickness.name}): click the curves to add — Enter (or the wall again) re-stamps " +
            "it, Esc leaves it as it is"
        changed()
        return true
    }

    /** Why a click that hit no curve was not the wall pick it looks like (GitHub #7). */
    private fun midSequenceWallPick(
        tool: ToolDef,
        world: Vec2,
    ): String? {
        if (!tool.extendsResult || filledSlots == 0) return null
        val hit = HitTest.nearest(doc, ev(), world, tolWorld()) { doc.thickNetworkOf(it) != null } ?: return null
        if (doc.thickNetworkOf(hit) === extending) return null
        return "Pick the wall first to extend it: ${doc.displayName(hit)} is a wall, not a carrier curve. " +
            "Press Enter to finish this one, then click ${doc.displayName(hit)} as the first pick."
    }

    /**
     * Commit an extension: the wall's own `tool thicken` step, **re-stamped** over the grown carrier set and
     * replayed (GitHub #7). No element is created, so every dependent — an opening, a dimension, a solid —
     * follows the enlarged footprint by recompute, and the whole gesture is one undo step.
     */
    private fun finishWallExtension(tn: ThickNetwork): Boolean {
        val was = tn.legCount
        val name = doc.nameOf(tn.footprint)
        val text =
            doc.thickNetworkExtension(tn, pickedElements.toList(), pickedSides.map { Tools.sideOf(it) }, pickedClicks.toList())
        val said = doc.takeNote() // read before the adopt below replaces the document that said it
        if (text == null) {
            statusHint = said ?: "Extending $name was refused"
            resetPicks()
            changed()
            return true
        }
        val fresh =
            try {
                DocumentFormat.load(text)
            } catch (e: Exception) {
                statusHint = "Extending $name failed: ${e.message}"
                resetPicks()
                changed()
                return true
            }
        adopt(fresh)
        checkpoint()
        statusHint = said ?: "Wall $name: $was -> ${tn.legCount} carrier curves"
        changed()
        return true
    }

    /**
     * Finish a repeating tool (Enter, or clicking the first pick again). Builds from whatever has been
     * collected; too few picks just cancels, since a boundary of one curve is not a boundary.
     */
    fun finishRepeatingTool(): Boolean {
        val tool = doc.toolDef(toolId) ?: return false
        if (!tool.repeating || filledSlots == 0) return false
        // a wall picked first is being *extended*: the result goes back into its own step (GitHub #7)
        extending?.let { return finishWallExtension(it) }
        val picks =
            Picks(
                pickedPoints.toList(),
                pickedElements.toList(),
                pickedClicks.lastOrNull() ?: Vec2(0.0, 0.0),
                pickedClicks.toList(),
                signs = pickedSides.toList(),
                view = proj(),
            )
        val scalars = toolScalars(tool)
        when {
            scalars == null -> statusHint = scalarPrompt(tool)
            filledSlots >= tool.minPicks -> {
                // one transaction, exactly as the single-shot runner's build is (OP-27) — a repeating tool
                // has collected more clicks, so it has more to take back, not less
                val refusal =
                    transacted(tool.label) {
                        doc.runTool(tool, picks, scalars)
                    }
                if (refusal != null) {
                    statusHint = refusal
                    changed()
                    return true
                }
                checkpoint()
                statusHint = doc.takeNote() ?: ""
            }
            // in the tool's *own* word for what it collects ([ToolDef.roleOf]), not the hardcoded "curve" this
            // used to say: a repeating tool need not gather curves at all — a curve in space gathers points.
            // The role goes in parentheses rather than being pluralized, because a slot name is a phrase
            // ("point in space") and there is no rule that puts an s in the right place inside one.
            else ->
                statusHint =
                    "${tool.label}: needs at least ${tool.minPicks} pick${if (tool.minPicks == 1) "" else "s"} (${tool.roleOf(0)})"
        }
        resetPicks()
        changed()
        return true
    }

    /**
     * **Follow the boundary** from the picks already collected, appending every piece whose continuation is
     * unique, and closing the outline when it comes back to the first piece (OP-14).
     *
     * Two picks fix a direction; from there, at the end of the current piece the document is asked which
     * pieces hand over there ([Document.continuationsFrom] — constructed joints and coincident endpoints,
     * never an intersection and never a seed point). Exactly one answer is not a decision, so it is taken;
     * anything else stops the follow and the user keeps clicking.
     *
     * **What this does *not* do is discover the loop at replay time.** The pieces it appends are appended to
     * the pick list, so the recorded step carries the full ordered boundary and replay re-runs the same
     * `ToolDef.build` over the same list — OP-14's rejection of *"the loop's identity would be discovered
     * rather than constructed"* is untouched, because nothing follows anything on load. The follow lives
     * strictly in the *gesture*: it saves clicks, and it is the click log that is stored.
     *
     * Stop conditions, all reported: a dead end (nothing continues), a fork (two or more do), a piece
     * already in the chain, a whole circle (which of its two arcs is meant is a genuine choice — OP-1), and
     * a pair with no constructed joint at all (two crossing lines, say), where there is nothing to follow.
     */
    private fun extendBoundaryPicks() {
        val ev = ev()
        val first = pickedElements.firstOrNull() ?: return
        val start = pickedElements.last()
        var entered = doc.handoverPosition(pickedElements[pickedElements.size - 2], start, ev) ?: return
        // (piece, where the boundary enters it) — collected first, because a piece's *click* needs both of
        // its joints and the second one is only known once the next piece is known
        val chain = ArrayList<Pair<Element, Vec2>>()
        var closed = false
        var stop: String? = null
        // a chain cannot visit more pieces than the document has
        var budget = doc.elements.size + 1
        while (budget-- > 0) {
            val cur = chain.lastOrNull()?.first ?: start
            val next = doc.continuationsFrom(cur, entered, ev)
            if (next.isEmpty()) {
                stop = "nothing continues past ${doc.nameOf(cur)}"
                break
            }
            if (next.size > 1) {
                stop = "${next.size} pieces meet past ${doc.nameOf(cur)}, so the boundary forks there — pick the one you mean"
                break
            }
            val step = next.single()
            if (step.piece === first) {
                // back where it started: that is the boundary closed — unless only two pieces are in, which
                // is the tracer's own two-meetings special case and stays a deliberate Enter ([buildOutline])
                if (pickedElements.size + chain.size >= 3) closed = true
                break
            }
            if (pickedElements.any { it === step.piece } || chain.any { it.first === step.piece }) {
                stop = "the boundary rejoins ${doc.nameOf(step.piece)}, which is already in it"
                break
            }
            if (step.piece.kind == ElementKind.CIRCLE || step.piece.kind == ElementKind.ELLIPSE) {
                stop = "${doc.nameOf(step.piece)} continues there, but which piece of a closed curve the boundary takes is a choice — click it"
                break
            }
            chain.add(step.piece to step.at)
            entered = step.at
        }
        if (chain.isEmpty()) {
            // a fork is worth saying even when nothing was followed: it is why the tool went quiet
            if (stop != null && stop.contains("forks")) statusHint = "$stop ($filledSlots picked)"
            return
        }
        for ((i, link) in chain.withIndex()) {
            val (piece, enter) = link
            // the far joint: the next piece's, the first piece's when the loop closed, else simply the far
            // end of the piece — which is where the user will click next anyway
            val exit =
                chain.getOrNull(i + 1)?.second
                    ?: (if (closed) doc.handoverPosition(piece, first, ev) else null)
                    ?: doc.farEndOf(piece, enter, ev)
                    ?: enter
            pickedElements.add(piece)
            pickedClicks.add(doc.pointBetweenOn(piece, enter, exit, ev) ?: enter)
            filledSlots++
        }
        if (closed) {
            val n = filledSlots
            finishRepeatingTool()
            statusHint = "Followed the boundary round through $n pieces and closed it"
            return
        }
        val last = pickedElements.last()
        val why = stop?.let { " — $it" } ?: ""
        statusHint =
            "Followed ${chain.size} piece${if (chain.size == 1) "" else "s"} to ${doc.nameOf(last)} " +
            "($filledSlots picked)$why; click the next piece, or Enter to close"
    }

    /**
     * The scalars [tool] consumes: the last of the panel picks, in pick order. Null when too few have been
     * picked — the caller then says which ones are still wanted ([scalarPrompt]).
     */
    private fun toolScalars(tool: ToolDef): List<ScalarEntry>? {
        val need = tool.scalars.size
        // **A tool waits for the scalars it cannot do without, not for all of them.** A slot with a declared
        // default is not waiting for anything (ScalarSlot.default), so a tool that mixes the two — a tube's
        // radius, which it must have, before its roll and twist, which mean zero unless stated — completes on
        // the number it actually needs. Before this the count was all-or-nothing, so the first such tool would
        // have made the user type three numbers to get the one that matters.
        val required = tool.requiredScalars
        if (scalarPicks.size < required) return null
        // a tool whose optional scalars cannot be told apart by dimension takes only what was typed *for it*
        // (see [ToolDef.scalarsTypedOnly]) — the memory below would otherwise fill them with a stranger
        val remembered = if (tool.scalarsTypedOnly) scalarPicks.filter { e -> pendingTypedParams.any { it === e } } else scalarPicks
        val picks = remembered.takeLast(need)
        // A **defaulted** slot is not waiting for anything (ScalarSlot.default), so it must not silently
        // adopt a pick that was meant for something else — and a length picked into a dimensionless ratio
        // would only make the point invalid (OP-7). So the longest **prefix** of picks whose dimensions are
        // what the slots ask for is what the tool gets, and every slot past it means "use the default".
        //
        // A prefix rather than all-or-nothing, which is what it used to be: with *two* defaulted slots (the
        // datum plane's angle and its offset) all-or-nothing threw away a typed angle whenever no offset
        // followed it, so the tool silently built with its default instead of with the number just typed.
        if (required == need) return picks
        // Matched from the **most recent** pick backwards, because a panel pick made for an earlier tool may
        // still be sitting in the list: the longest suffix of the picks that fits the slots from the first one
        // on is what was meant here, and everything past it takes its default. Never shorter than the slots
        // the tool is actually waiting for, since those have no default to fall back on.
        for (k in minOf(need, picks.size) downTo required) {
            val tail = picks.takeLast(k)
            if (tail.indices.all { dimensionOf(tail[it].ref) == tool.scalars[it].dim && statedFor(tool.scalars[it], tail[it]) }) return tail
        }
        // Nothing fits, and the tool still has slots it cannot do without: it gets what it was given and the
        // dimension error lands where it belongs, in the node (OP-7) — the same answer an all-required tool
        // has always given a pick of the wrong dimension.
        return picks.takeLast(required)
    }

    /**
     * Whether [e] may fill [slot] — always, unless the slot takes only what was **typed for this gesture**
     * ([ScalarSlot.typedOnly]), in which case a parameter left in the panel by an earlier one may not drift
     * into it. Its default then applies, which is what an unstated value means everywhere else.
     */
    private fun statedFor(
        slot: ScalarSlot,
        e: ScalarEntry,
    ): Boolean = !slot.typedOnly || pendingTypedParams.any { it === e }

    /** What is still missing for [tool]'s scalar inputs, in the user's terms. */
    private fun scalarPrompt(tool: ToolDef): String {
        val have = scalarPicks.size
        val wanted = tool.scalars
        // only the slots that have no default: a defaulted one is not what the tool is waiting for, and
        // asking for it would read as an instruction rather than an offer
        val missing = wanted.drop(have).filter { it.default == null }
        val had = if (have == 0 || wanted.size == 1) "" else " (${wanted.take(have).joinToString(", ") { it.name }} picked)"
        return "${tool.label}: type ${missing.joinToString(", then ") { it.name }}, then click (or press Enter) — " +
            "or click a parameter or measurement in the panel$had"
    }

    /** The structural count [tool] will build with — see [count]. Zero for a tool that needs none. */
    private fun toolCount(tool: ToolDef): Int = if (tool.minCount == 0) 0 else maxOf(count, tool.minCount)

    private fun runToolClick(world: Vec2) {
        val tool = doc.toolDef(toolId) ?: return
        // a tool that cuts *the part of this face space* (OP-17) has nothing to cut in the plan — refused
        // here rather than at completion, so the reason is the one the user reads
        if (tool.facePartOperand && doc.facePartTip() == null) {
            statusHint =
                if (doc.activeSpace.parallel || doc.activeSpace.isStation) {
                    // a plane that passes through nothing (GitHub #9, and a station the same way, OP-26): the
                    // same sentence, without a line to name
                    "${tool.label} needs a part to cut into, and ${doc.activeSpace.name} passed through no solid " +
                        "when it was made — Extrude builds a solid on this plane instead, or Subtract one from a part"
                } else if (doc.activeSpace.isDatum) {
                    // a datum plane with no part (GitHub #6): honest, and it names the operation that does work
                    "${tool.label} needs a part to cut into, and ${doc.activeSpace.name}'s line " +
                        "(${doc.activeSpace.hinge?.let { doc.nameOf(it) }}) is part of no solid — Extrude builds a solid on this plane instead"
                } else {
                    "${tool.label} works on a face: use Sketch on face to pick a solid's edge first, " +
                        "then draw and cut there"
                }
            changed()
            return
        }
        pickRefusal = null
        // **The first pick may be an existing result**, which extends it instead of building a new one
        // (GitHub #7). Only the first: a wall clicked mid-sequence is not a carrier curve, and says so.
        if (tool.extendsResult && filledSlots == 0 && startExtension(world)) return
        // a repeating tool closes when the first pick is clicked again — the boundary is complete
        //
        // Through `proj()` like every other pick ([pickElement]): a height point has no image in the plan and
        // is measured against the pointer's *viewing ray* (OP-25), so without the projection this search could
        // not find one in the 3D view — and a curve through height points (OP-26) could never be closed there.
        if (tool.repeating && (filledSlots >= 2 || extending != null)) {
            val again =
                HitTest.nearest(doc, ev(), world, tolWorld(), proj()) {
                    it === pickedElements.firstOrNull() || (extending != null && it === extending?.footprint)
                }
            if (again != null) {
                // For a tool that *closes* on it, this click is a statement and not merely "done": the first
                // pick is appended, so the pick list — and therefore the recorded step — ends with the element
                // it began with. See [ToolDef.closesOnFirstPick].
                if (tool.closesOnFirstPick) {
                    pickedElements.add(again)
                    pickedClicks.add(world)
                    pickedLandings.add(null)
                    filledSlots++
                }
                finishRepeatingTool()
                return
            }
        }
        // A tool may have *no* geometry slots at all: its inputs are scalars (point from coordinates), so
        // the click only says "now". Handled by the same completion path below rather than as a special
        // case, which is why the slot lookup is allowed to come back empty.
        //
        // A repeating tool's one slot repeats, so [filledSlots] runs past it — resolved as an *index* rather
        // than as a bare slot because the refusal below wants the tool's own word for that slot ([roleOf]).
        val waiting = if (tool.repeating) tool.slots.lastIndex else filledSlots
        // **An optional slot is skipped by the very click that fills the slot behind it**
        // ([SlotKind.OPTIONAL_POINT]): tried here first, and where it takes nothing the click goes on to the
        // next slot. Only *tried*, because nothing is spent yet — the skip is committed below, together with
        // the pick that landed, so a click that lands nowhere at all leaves the gesture exactly where it
        // stood rather than quietly using up the option.
        val optional = Tools.isOptionalSlot(tool.slots.getOrNull(waiting)) && !pickSharedPoint(world)
        val slotIndex = if (optional) waiting + 1 else waiting
        val slot = tool.slots.getOrNull(slotIndex)
        val picked =
            when (slot) {
                null -> true
                // an optional point slot that *was* filled: the point is already in, by the same sharing rule
                // [POINT] follows — see [pickSharedPoint]
                SlotKind.OPTIONAL_POINT -> true
                SlotKind.PLACE_POINT, SlotKind.POINT -> {
                    pickedPoints.add(placePoint(world))
                    true
                }
                // the two element-valued point slots, told apart by what a *miss* does and by nothing else:
                // both share the node of a point they hit (see the placing route below the `when`)
                SlotKind.EXISTING_POINT, SlotKind.INPUT_POINT -> pickElement(world) { it.isPoint }
                SlotKind.CURVE -> pickElement(world) { it.isCurve }
                // a curve's defining points, an area's corners (the OP-21 extension's key points), or a curve
                // in space's own start, end and centre (OP-26) — the one slot that reaches across the 2D/3D
                // partition, for the reason [SlotKind.EXTRACTABLE] states
                SlotKind.EXTRACTABLE -> pickElement(world) { it.isCurve || it.isArea || it.kind == ElementKind.SPACE_CURVE }
                SlotKind.LINE -> pickElement(world) { it.isLinear } // a segment or ray also carries a line
                // ...and an arc also carries a circle: the twin coercion, so a circle slot takes one
                SlotKind.CIRCLE -> pickElement(world) { it.isCentric }
                // ...and the same coercion a third time, for the conics (OP-24)
                SlotKind.CONIC -> pickElement(world) { it.isElliptic }
                SlotKind.CENTERED -> pickElement(world) { it.hasCentre }
                SlotKind.MEASURABLE ->
                    pickElement(world) { it.kind == ElementKind.SEGMENT || it.kind == ElementKind.ARC || it.isElliptic }
                SlotKind.SEGMENT -> pickElement(world) { it.kind == ElementKind.SEGMENT }
                SlotKind.GEOMETRY -> pickGeometry(world, tool)
                SlotKind.ON_CIRCLE_POINT -> pickElement(world) { it.handle is OnCircleHandle }
                SlotKind.CENTRIC -> pickElement(world) { it.isCentric }
                // a fillet leg: either carrier will do, since all the rounding needs is something to be
                // tangent to
                SlotKind.CARRIER -> pickElement(world) { it.isLinear || it.isCentric }
                // the seam's slot (OP-17): a traced outline or a thick path's footprint, both of which
                // bound an area — the coercion between them is the document's, not the pick's
                SlotKind.AREA -> pickElement(world, doc.areaPickFilter(ev()))
                // The boolean slot (OP-22): a solid, picked in 2D by **either** drawing the canvas makes of it —
                // the footprint hint in the space its sketch was drawn in (OP-17), and failing that the
                // **section** a working plane cuts through it (`pickSectionSolid`, the route [SECTION_CURVE]
                // already takes). Footprint first, for two reasons that are the same reason: it is the solid's
                // own geometry rather than a derivation of it, and a face space's part outline is the *tip* of
                // its feature chain ([Document.partOutlineOf]) — a section pick, which names whichever ancestor
                // it lands on, must not overrule that. This is what retires session 16's parked cut: one canvas
                // still shows one space, but a body sketched elsewhere is drawn here and therefore pickable here.
                //
                // …and **ahead of both of them, the body itself where a 3D view is driving** ([pickSolidRay]).
                // That precedence is the user's own case rather than a preference: a handle standing on the rim
                // of a plate is within a pick tolerance of the *plate's* footprint circle, so footprint-first
                // answered a click aimed squarely at the handle with the plate. Both flat drawings are pictures
                // of a body taken in a plane the user is not looking through; the ray is the one route that
                // uses depth, so where it lands on a body that is the body meant, and the flat pictures answer
                // where it misses — a silhouette edge, or a click beside the part. In the 2D canvas
                // [PlaneProjection.eyeRay] is null and this route does not exist, so the plan's own picking is
                // untouched, to the click.
                SlotKind.SOLID ->
                    pickSolidRay(world) || pickElement(world) { it.kind == ElementKind.SOLID } || pickSectionSolid(world)
                // a cutting chain (OP-22's extension), or anything closed — the coercion between them is
                // the document's (`Document.chainOf`), exactly as it is for an area slot
                SlotKind.CHAIN -> pickElement(world) { doc.isChainCandidate(it, ev()) }
                // a loft's slot (OP-17): a section, an apex point or a guide curve, told apart by the document
                // (`loftRoleOf`) rather than by the click — so one repeating slot collects the whole feature
                SlotKind.LOFT_PART -> pickElement(world) { doc.loftRoleOf(it) != null }
                // a curve's slot (OP-26): any point of the drawing, taken as the point in space it is — a
                // height point as it stands, a plain 2D point lifted by nothing (`Document.curveThroughPoints`)
                SlotKind.POINT3 -> pickElement(world) { it.isPoint }
                // a sweep's slot (OP-26): the curve in space itself, picked where it is drawn — its plan
                // projection here, the curve itself in the 3D view (`Document.sweepAlongCurve`) — **or a
                // drawing**, which is read as the run it already is (the lift, `Document.spaceCurveRef`), so
                // that sweeping a foundation round a footprint's own outline is the one click it looks like
                SlotKind.PATH3 -> pickElement(world) { it.kind == ElementKind.SPACE_CURVE || doc.isLiftable(it) }
                // the lift's own slot: the drawing, and nothing that is already a run
                SlotKind.DRAWN_RUN -> pickElement(world) { doc.isLiftable(it) }
                // an intersection curve's slot (OP-26, step 6): a click on what the working plane *draws* —
                // the section of an ancestor solid (GitHub #9's one enumeration, read a fourth time). The
                // element it yields is the solid; where the click landed is the branch, and the tool scores
                // that once (`Document.intersectionCurve`)
                // a sphere locus's slot (OP-28): the locus itself, picked where it is drawn — its outline
                // circle in the plan, its great circles in the 3D view (`HitTest.distanceToSphere`). One rule,
                // the standing one: what is visible is pickable.
                SlotKind.SPHERE -> pickElement(world) { it.kind == ElementKind.SPHERE_LOCUS }
                SlotKind.SECTION_CURVE -> pickSectionSolid(world)
                SlotKind.SIDE -> true // captures the click position only; creates nothing
            }
        // …and a slot the ordinary pick missed may still have landed on the working plane's **section**, whose
        // curves and corners are inputs (OP-17). Tried second, so a real element always wins.
        val onSection = !picked && slot != null && pickSectionInput(world, slot)
        // …and last, an *input* point slot that hit nothing at all **states a point there** (see [SlotKind]).
        // Third and not first, so an existing point is still shared and a section corner is still
        // materialized; and not at all after a refusal, because a pick that hit something and was declined
        // has already said why — placing a point on top of that would answer a different question.
        val placed =
            !picked && !onSection && pickRefusal == null && Tools.placesPointElement(slot) && placePointElement(world)
        val landed = picked || onSection || placed
        // subject slots do NOT create anything on a miss — just hint and wait
        if (!landed) {
            // A miss must *say* it missed, and say where the operation stands. Silently keeping the old
            // count is the worst of the three possible answers: the drawing does not change, so nothing on
            // screen distinguishes "that curve is in" from "that click landed in space". A pick that hit
            // something and was *refused* has a better reason of its own, and says that instead.
            val refused = pickRefusal ?: midSequenceWallPick(tool, world)
            // …and a click that landed on nothing but a **ghost** says so, by name (OP-18's *Show hidden*).
            // A tool slot deliberately does not take one: the toggle exists to *find* what was hidden, and
            // building on an element the user took out of the drawing would put the new geometry's input in a
            // state the drawing does not show. So the ghost is not silently skipped either — the one thing a
            // refusal owes is a name and a way forward, and both are here.
            val ghost = ghostUnder(world)
            statusHint =
                when {
                    refused != null -> refused
                    ghost != null ->
                        "${doc.nameOf(ghost)} is hidden — a tool builds only on what is in the drawing; " +
                            "Show it first. ${tool.help}"
                    // A **subject** slot ([SlotKind]) is the one kind of miss that has to explain itself
                    // rather than merely report itself: every other point slot would have placed something
                    // here, so "nothing happened" is the surprise. Said in the tool's own role word, and
                    // ending in the help, which is where each of these tools states *why* it cannot place.
                    Tools.needsExistingPoint(slot) ->
                        "${tool.label} needs an existing ${tool.roleOf(slotIndex)} — click one; nothing was placed. ${tool.help}"
                    // …in the tool's own word for what it wants, so a curve in space says "hit no point in
                    // space" rather than asking for a curve it does not collect
                    tool.repeating -> "That click hit no ${tool.roleOf(0)} — $filledSlots picked so far. ${tool.help}"
                    // A **solid** slot's miss has something better to say than "nothing pickable", and it is
                    // the one thing the canvas cannot show: *where* a body is clickable (OP-22, retiring
                    // session 16's parked cut). One canvas shows one space, so a solid is reached either by
                    // its footprint — drawn in the space its sketch was drawn in — or by its section, where a
                    // working plane cuts it. The generic sentence stays for every slot with nothing better.
                    slot == SlotKind.SOLID ->
                        "That click hit no ${tool.roleOf(slotIndex)} — a solid is clicked by its footprint in the " +
                            "space it was sketched in, by its section where a working plane cuts it, or on the body " +
                            "itself in the 3D view." +
                            // …and the way out is only offered by a tool that has one: a switch drops the picks
                            // of every other ([setActiveSpace]), so promising otherwise would be a lie.
                            (if (tool.crossSpace) " Switch the sketch plane — the picks are kept — and click it there." else "") +
                            " ${tool.help}"
                    else -> "That click hit nothing pickable — ${tool.help}"
                }
            changed()
            return
        }
        if (slot != null) {
            // …and here the skip is spent, together with the pick that landed: one click, two slots settled,
            // and **no click recorded for the one that was skipped** — a click in a step is a choice a replay
            // must repeat, so inventing one for a pick nobody made would state a choice nobody stated
            // (the rule [applyToSelection] follows for the same reason)
            if (optional) filledSlots++
            filledSlots++
            pickedClicks.add(world)
            // …and what this click landed on, for a build that joins to it (see [Picks.landings]). Recorded
            // beside the click rather than *instead* of it: what a click position means is the tool's business
            // (a side, a quadrant, a sector), and only a tool that joins reads the landing.
            pickedLandings.add(snap(world).takeIf { it.linked })
            // the wall side this click was made under (the OP-21 extension) — a choice, hence a sign
            if (tool.sidePerPick) pickedSides.add(justification.ordinal)
        }

        if (tool.repeating) {
            statusHint = "${tool.help} ($filledSlots picked)"
            // two picks fix the direction, so from there the boundary can be followed wherever it is not
            // a choice — see [extendBoundaryPicks]
            if (tool.followsBoundary && filledSlots >= 2) extendBoundaryPicks()
            changed()
            return
        }
        if (filledSlots >= tool.slots.size) {
            // A tool still missing a scalar **keeps its picks** and says what it wants: the number can then
            // be typed (or a parameter picked) and the tool finishes with the clicks already in. Throwing
            // the picks away was the older answer, and it made the geometry pay for a value's absence.
            if (!maybeCompleteTool(world)) statusHint = scalarPrompt(tool)
        } else {
            val fed = pickedGroup?.let { groupFedNote(it) + " " } ?: ""
            statusHint = "${optionalTaken(tool, slot, slotIndex)}$fed${tool.help} (${stillNeeded(tool)} more)"
        }
        changed()
    }

    /**
     * How the status line **acknowledges an optional pick that landed** — in the tool's own word for that slot
     * and the name of what was picked, or nothing at all when this click filled an ordinary slot.
     *
     * The one pick that is otherwise invisible: an optional slot costs no *required* one, so the count in the
     * hint reads exactly as it did before the click ([stillNeeded] counts what the tool cannot do without).
     * A click whose whole effect is a pick nobody can see is a click that declined silently, which is the one
     * thing no route here may do.
     */
    private fun optionalTaken(
        tool: ToolDef,
        slot: SlotKind?,
        slotIndex: Int,
    ): String {
        if (!Tools.isOptionalSlot(slot)) return ""
        val el = pickedPoints.lastOrNull()?.let { doc.elementFor(it) } ?: return ""
        return "${tool.roleOf(slotIndex).replaceFirstChar { it.uppercaseChar() }}: ${doc.nameOf(el)}. "
    }

    /**
     * How many **more picks** the armed tool cannot do without — the slots it still has to fill, not counting
     * the optional ones ([SlotKind.OPTIONAL_POINT]), which is what "2 more" has to mean if the number is to be
     * a promise about the clicks left rather than about the slots left.
     */
    private fun stillNeeded(tool: ToolDef): Int = tool.slots.drop(filledSlots).count { !Tools.isOptionalSlot(it) }

    /**
     * A geometry pick, which is the one slot a **whole group** can fill (OP-16).
     *
     * The rule is the one the drag subject already follows: *a group acts as a whole only when selected as a
     * whole.* With [selectedGroup] naming the group the click landed in, the slot takes every member and the
     * tool fans over them; without it — a member reached alone, or a group nobody selected — the click means
     * that element and nothing else, exactly as before. Grouping is invisible until something of it is
     * selected, so this is the only reading under which a click cannot copy more than the user can see.
     */
    private fun pickGeometry(
        world: Vec2,
        tool: ToolDef,
    ): Boolean {
        val hit = HitTest.nearest(doc, ev(), world, tolWorld()) { true } ?: return false
        val whole = if (tool.groupOperand) selectedGroup?.takeIf { doc.groupOf(hit) === it } else null
        val members = whole?.let { doc.groupMembers(it) }
        if (whole == null || members.isNullOrEmpty()) {
            pickedElements.add(hit)
            return true
        }
        // too many copies is a refusal, not a quiet fallback to the one element clicked: the user asked for
        // the group, and arraying one member instead would be a different construction
        val why = groupFanRefusal(tool, whole, members.size)
        if (why != null) {
            pickRefusal = why
            return false
        }
        pickedElements.addAll(members)
        pickedGroup = whole
        return true
    }

    /**
     * Build the armed tool if everything it needs is in: every slot picked and every scalar available.
     * The one place a non-repeating tool applies, so a click and a typed value reach it the same way.
     *
     * [at] is the click that completed it, for [Picks.at]; null when the *value* completed it, where the
     * last click is the honest answer instead.
     */
    private fun maybeCompleteTool(at: Vec2?): Boolean {
        val tool = doc.toolDef(toolId) ?: return false
        if (tool.repeating || filledSlots < tool.slots.size) return false
        val scalars = toolScalars(tool) ?: return false
        val where = at ?: pickedClicks.lastOrNull() ?: Vec2(0.0, 0.0)
        // OP-17's sequential-feature rule: the part this face space belongs to, as it stands *now*, fed in
        // as the first element so the step records which solid was cut (and replay never re-resolves it)
        val part = if (tool.facePartOperand) doc.facePartTip() ?: return false else null
        val picks =
            Picks(
                pickedPoints.toList(),
                listOfNotNull(part) + pickedElements.toList(),
                where,
                pickedClicks.toList(),
                count = toolCount(tool),
                landings = pickedLandings.toList(),
                // which view this gesture was made through — see [Picks.view]
                view = proj(),
            )
        // read before [resetPicks] drops it: a group operand is worth reporting, because the one thing the
        // canvas cannot show is *how much* the tool just took (OP-16)
        val fedGroup = pickedGroup
        val members = picks.elements.size
        // **The replication trigger** (OP-23): a gesture whose inputs touch a pattern's members is stamped
        // round it by index shift. Decided *before* the build, from the picks alone, so the answer is the same
        // whether the gesture ends up replicated, refused or suppressed — and so the refusal can name the
        // input that stopped it rather than being discovered halfway through a fan.
        val plan = doc.replicationOf(tool, picks)
        var orbitNote: String? = plan?.refusal
        var replicated = false
        // a tool may *create a sketch space* and make it active (Sketch plane, GitHub #6) — the view then has
        // to follow it here, exactly as [faceClick] does for Sketch on face, because switching the canvas is
        // the editor's half of a space (one camera each, selection dropped)
        val spaceBefore = doc.activeSpace
        // …and the whole of it runs as one transaction (OP-27): every route below either leaves a step that
        // owns what it made, or leaves the document as it was — see [transacted]
        val refusal =
            transacted(tool.label) {
                // a tool that records its own steps is *not* wrapped in a `tool` step (OP-18): what it builds has
                // degrees of freedom of its own that the steps it emits restate — see [ToolDef.recordsSteps]
                if (tool.recordsSteps) {
                    tool.build(doc, picks, scalars.map { it.ref })
                } else if (plan?.gesture != null && !snapEnabled) {
                    // Alt has always meant *leave the model as I put it*, and declining the orbit is the same
                    // sentence one level up: this feature is a one-off (a keyway, a single flat).
                    orbitNote = "not replicated: Alt keeps it a one-off on pattern ${plan.pattern.name}"
                    doc.runTool(tool, picks, scalars)
                } else if (plan?.gesture != null && doc.buildOrbit(plan, tool, scalars) != null) {
                    replicated = true
                    orbitNote = "${tool.label}: ${plan.copies} copies round pattern ${plan.pattern.name}"
                } else {
                    doc.runTool(tool, picks, scalars)
                }
            }
        if (refusal != null) {
            statusHint = refusal
            changed()
            return true
        }
        checkpoint() // the tool application — earlier slot clicks were only halves of it
        resetPicks()
        val entered = doc.activeSpace.takeIf { it !== spaceBefore }
        if (entered != null) {
            spaceCameras[spaceBefore.name] = camera
            camera = cameraFor(entered)
            clearSelection()
        }
        // A tool that only *rewires* changes nothing the canvas can show (OP-4 case b: a point made relative
        // sits exactly where it did), so a silent success reads the same as a silent refusal. The document says
        // what happened — one channel, not a case per tool here.
        val note = doc.takeNote()
        val group =
            if (fedGroup == null) {
                null
            } else {
                // the copies are deliberately *not* grouped (see OP-16's as-built note), so say it here
                // rather than leave the user to discover it by clicking one
                "${tool.label}: ${picks.count} instances of group ${fedGroup.name}'s $members element" +
                    "${if (members == 1) "" else "s"} — the copies are not grouped"
            }
        // an orbit's own note comes second to what the document said about the geometry it built, except when
        // the document said nothing — and a *refusal* to replicate leads, since it is the surprise
        statusHint =
            listOfNotNull(
                orbitNote.takeIf { !replicated },
                note,
                group,
                orbitNote.takeIf { replicated },
                // ...and, last, where the user now *is*: a tool that opened a space says its conventions,
                // which is the one thing the canvas cannot show about a plane it is looking straight at
                entered?.let { spaceNote(it) },
            ).joinToString(" — ")
        changed()
        return true
    }

    /**
     * Fill an **input** point slot that hit nothing by *stating a point there* — [SlotKind]'s law for the
     * element-valued half.
     *
     * Deliberately the very same [placePoint] a `POINT` slot uses, rather than a second and poorer creation
     * path: a click on a curve becomes a rider, an intersection materializes, a section corner is taken as
     * an input (OP-17), the grid snap applies, and the `point` step lands in the journal *before* the tool's
     * own step, so a replay re-runs it and discovers nothing (OP-18).
     *
     * What the tool then receives is that point's **element**, looked up in the document rather than guessed
     * at as "the last one added": a placement may add more than one element (a rider hangs off its host),
     * and index alignment with `Picks.elements` is what the slot order means.
     */
    private fun placePointElement(world: Vec2): Boolean {
        val ref = placePoint(world)
        val el = doc.elementFor(ref) ?: return false
        pickedElements.add(el)
        return true
    }

    /**
     * Fill an **optional** point slot from the point [world] hits, **sharing its node**, or take nothing —
     * which is how that slot is skipped ([SlotKind.OPTIONAL_POINT]).
     *
     * Nothing is ever placed here, and nothing is refused either: a click that means the slot behind this one
     * is simply not this slot's click.
     *
     * **An optional slot's candidates are exactly what its build can use** — and that is a rule about optional
     * slots rather than about anchors. Every *required* point slot may take a wrong pick and have the build
     * refuse it **by name** (a dimension handed a point in space says so — OP-25/OP-26, session 53), because
     * there the click has nowhere else to go and a refusal is the whole of the answer. Here it has: the slot
     * behind this one is offered the same click, so a candidate this slot cannot use must be declined at the
     * *pick*, or one click would both spend the option and kill the gesture. So what counts is a point whose
     * value **is** a 2D position of this plane — a corner, a key point of a 2D curve, a rider on one, a welded
     * alias — while a point in **space** ([Element.inSpace]: a height point, a key point of a curve in space, a
     * point riding a coil) is not one, however exactly its projection lands under the cursor. That is session
     * 53's own rule read one slot further: a space point's plan image is *where it projects*, which is not the
     * point, so no placing, snapping or plane-reading route may quietly take it for the plane point there.
     *
     * **How the one click is read is the canvas's own law, not a new one**: *nearest wins*, and a point wins a
     * **tie**. The two candidates overlap by nature — the corner an anchor wants is a point standing exactly
     * *on* the outline the next slot wants — so a click aimed at a corner finds both at the same distance and
     * the more specific of the two takes it (the rule that already gives a circle's centre the click a
     * geometry slot would have taken). A click that is genuinely nearer the outline than to any point of it is
     * the outline's, which is what keeps clicking an edge a few tenths from its corner the *section's* pick.
     */
    @Suppress("UNCHECKED_CAST")
    private fun pickSharedPoint(world: Vec2): Boolean {
        val hits = HitTest.nearestAll(doc, ev(), world, tolWorld(), proj()) { true }
        val nearest = hits.firstOrNull()?.second ?: return false
        val point = hits.firstOrNull { it.first.isPoint && !it.first.inSpace } ?: return false
        // a rounding tolerance, not a modelling one: the two distances at a corner are the same arithmetic
        if (point.second > nearest + TIE_EPS) return false
        pickedPoints.add(point.first.ref as PointRef)
        return true
    }

    /**
     * The ghost a click landed on when it landed on nothing else (OP-18's *Show hidden*), or null.
     *
     * Asked only *after* every ordinary pick has missed, which is what makes it a report rather than a rule:
     * a ghost never competes with anything, here or in [pickAt].
     */
    private fun ghostUnder(world: Vec2): Element? {
        val ghosts = ghostElements()
        if (ghosts.isEmpty()) return null
        return HitTest.nearest(doc, ev(), world, tolWorld(), proj(), ghosts) { it in ghosts }
    }

    private fun pickElement(
        world: Vec2,
        filter: (Element) -> Boolean,
    ): Boolean {
        val el = HitTest.nearest(doc, ev(), world, tolWorld(), proj(), filter = filter) ?: return false
        pickedElements.add(el)
        return true
    }

    /**
     * A pick that lands on a working plane's **section**, taken as the *solid* it is the section of (OP-26,
     * step 6) — nothing is materialized, because what the tool builds is the whole curve rather than one
     * named member of the section.
     */
    private fun pickSectionSolid(world: Vec2): Boolean {
        val el = doc.sectionSolidNear(doc.activeSpace, world, tolWorld(), ev()) ?: return false
        pickedElements.add(el)
        return true
    }

    /**
     * The solid the pointer is **actually pointing at in the 3D view** — ray ∩ mesh, nearest hit — or null:
     * no 3D view is driving, or the ray passes everything by (`Geom3.rayMesh`).
     *
     * This is 3D picking, and it is the *picking* half only: what comes back is the **body**, never a face of
     * it. Naming a face durably is Manifold's face-ID provenance, which stays parked exactly where it was —
     * so nothing here has to invent an identity that could not survive a recompute.
     *
     * **What it may offer is what the 3D view draws, and that is one line of code rather than a rule to keep
     * in step**: the candidates come from [Scene3.extract] itself, with no ghosts. So a hidden solid is never
     * offered whether the *Show hidden* toggle is on or off (a ghost contributes no faces at all — OP-18's
     * *Show hidden* — and a ray must not hit a picture of something the user took out of the drawing); an
     * **invalid** solid has no value and therefore no mesh, so it is not there to be hit (OP-3); and a solid
     * already consumed as another feature's material is not offered either, because it is not on screen. The
     * same sentence session 55 made law — *what is drawn is what is pickable* — applied to the one picture a
     * body has that the 2D canvas cannot show.
     *
     * **Nearest wins**, which is the whole reason a ray is better evidence here than a distance: two bodies
     * one behind the other are told apart by depth, which is exactly what the flat pictures cannot do.
     *
     * **The ray consults the FINE mesh, and the cost is accepted with the reason** (slice B). The tempting
     * answer was *whatever the picture shows*, since that is the sentence the paragraph above makes law — but
     * a pick is a **choice with consequences the fine body has to agree with**, and the two are not the same
     * kind of thing. A picked body becomes a tool's operand, a boolean's material, a selection something is
     * then built on; a picture is looked at. Three things settle it and they all point one way. First, a
     * **press is not a streaming event**: the two events the coarse picture exists for are a pointer *move*
     * and a wheel notch, and picking happens on press, when the interaction has settled and the fine mesh is
     * what is on screen anyway — so this costs nothing that the very next frame was not going to pay. Second,
     * the coarse shell is *inscribed* in the fine one, so a coarse ray is wrong in both directions near a
     * silhouette — it can miss a body the fine picture does contain, and on the inside of a chorded bore it
     * can hit one the fine picture does not. Third, the **2D** pick target for a swept body
     * ([constructit.geom.Silhouette.ofSwept]) is exact and quality-free, so a coarse ray would put the two
     * views' answers about where a tube's edge is a chord apart. Session 55's law is therefore kept where it
     * was made — *what is drawn is what is pickable* is about **which bodies** are offered, and they still
     * come from [Scene3.extract] itself, one line, no list to keep in step.
     */
    private fun solidUnderRay(
        world: Vec2,
        ev: Evaluator = ev(),
    ): Element? {
        val ray = pointing?.eyeRay(world) ?: return null
        // the caller's own memo pass where there is one ([pickAt] already holds one): a fresh [Evaluator] here
        // would re-evaluate every solid in the drawing on every press, which is a cost the triangle loop's own
        // argument does not cover
        val drawn = Scene3.extract(doc, ev).solids.associateBy { it.elementId }
        if (drawn.isEmpty()) return null
        var best: Element? = null
        var bestT = Double.POSITIVE_INFINITY
        for (el in doc.elements) {
            val item = drawn[el.id] ?: continue
            val t = Geom3.rayMesh(ray, item.mesh) ?: continue
            if (t < bestT) {
                bestT = t
                best = el
            }
        }
        return best
    }

    /**
     * [rest] with [solid] put where the solids are: ahead of the first of them, or last where there are none.
     *
     * The one line that keeps a ray hit a *better answer to the same question* rather than a new precedence —
     * see the call site in [pickAt].
     */
    private fun rankedAmongSolids(
        rest: List<Element>,
        solid: Element,
    ): List<Element> {
        val others = rest.filter { it !== solid }
        val at = others.indexOfFirst { it.kind == ElementKind.SOLID }
        if (at < 0) return others + solid
        return others.subList(0, at) + solid + others.subList(at, others.size)
    }

    /**
     * A `SOLID` slot's **third route, and the first one tried where a 3D view is driving**: the body the
     * pointer is actually on.
     *
     * OP-13's split, said for solids: *the ray answers what the plan cannot* — and in the 3D view what the
     * plan cannot answer includes the questions it answers **wrongly**. The two flat routes are pictures of a
     * body taken in a plane the user is not looking through, and the user's own drawing is the proof: a handle
     * standing on the rim of a plate lies within a pick tolerance of the *plate's* footprint circle, so
     * footprint-first came back with the plate for a click aimed squarely at the handle. Depth is the evidence
     * the flat pictures do not have, so where a ray lands on a body that is the body meant.
     *
     * **It takes nothing away from the 2D canvas.** [PlaneProjection.eyeRay] is null there — a plan looks
     * along its own normal, where every body over the drawing is on one ray and depth decides nothing — so
     * this route does not exist in the plan and the footprint keeps every click it ever had.
     */
    private fun pickSolidRay(world: Vec2): Boolean {
        val el = solidUnderRay(world, ev()) ?: return false
        pickedElements.add(el)
        return true
    }

    /**
     * A slot pick that landed on the working plane's **section**: the addressed curve or corner is
     * materialized as a real element and fed to the slot (OP-17's section inputs).
     *
     * The precedent is the rider's: a click on something the canvas only *draws* creates the accessor it
     * addresses and records the choice, so no tool needs a case for sections — a `LINE` slot gets a segment, a
     * point slot gets a point, and from then on it is an ordinary element. Tried only *after* the ordinary
     * pick, so a real element on top of the section still wins, and only for slots the section can fill: a
     * section curve is scaffolding, not an area or a solid.
     */
    private fun pickSectionInput(
        world: Vec2,
        slot: SlotKind,
    ): Boolean {
        val want =
            when (slot) {
                // …and a curve in space takes a corner as a point in space (OP-26), lifted by nothing on
                // the plane that cut it — the same materialization, one slot further
                SlotKind.EXISTING_POINT, SlotKind.INPUT_POINT, SlotKind.POINT3 -> Document.SectionInput.CORNER
                SlotKind.LINE, SlotKind.SEGMENT, SlotKind.CURVE, SlotKind.CARRIER, SlotKind.CIRCLE, SlotKind.CENTRIC,
                SlotKind.EXTRACTABLE, SlotKind.CONIC, SlotKind.CENTERED, SlotKind.MEASURABLE,
                -> Document.SectionInput.EDGE
                SlotKind.GEOMETRY -> null
                else -> return false
            }
        val cand = doc.sectionCandidateNear(world, tolWorld(), ev(), want) ?: return false
        if (cand.refusal != null) {
            pickRefusal = "${cand.provenance} cannot be an input: ${cand.refusal}"
            return false
        }
        // A straight-curve slot must not silently take an arc, and vice versa — so the kind is checked against
        // the slot *before* anything is created, using the coercion rules the element filters already state
        // (a segment carries a line, an arc carries a circle).
        val k = cand.elementKind
        val fits =
            when (slot) {
                SlotKind.LINE, SlotKind.SEGMENT -> k == ElementKind.SEGMENT
                SlotKind.CIRCLE, SlotKind.CENTRIC -> k == ElementKind.CIRCLE || k == ElementKind.ARC
                SlotKind.CONIC -> k == ElementKind.ELLIPSE || k == ElementKind.ELLIPTIC_ARC
                SlotKind.CENTERED ->
                    k == ElementKind.CIRCLE || k == ElementKind.ARC || k == ElementKind.ELLIPSE || k == ElementKind.ELLIPTIC_ARC
                SlotKind.MEASURABLE -> k != ElementKind.DERIVED_POINT && k != ElementKind.LINE
                SlotKind.CARRIER, SlotKind.CURVE, SlotKind.EXTRACTABLE -> k != ElementKind.DERIVED_POINT
                // a geometry slot (mirror, rotate, array) takes whatever the section offers: a corner is a
                // point and a section curve is a curve, and both are ordinary operands once materialized
                SlotKind.GEOMETRY -> true
                SlotKind.EXISTING_POINT, SlotKind.INPUT_POINT, SlotKind.POINT3 -> k == ElementKind.DERIVED_POINT
                else -> true
            }
        if (!fits) {
            pickRefusal =
                "${cand.provenance} is cut as ${if (k == ElementKind.SEGMENT) "a straight edge" else "a curve"} " +
                "there, which this pick cannot use — click another piece of the section"
            return false
        }
        val el = doc.takeSectionInput(cand)
        if (el == null) {
            pickRefusal = doc.takeNote() ?: "${cand.provenance} cannot be taken as an input here"
            return false
        }
        pickedElements.add(el)
        // the solid is named too since a plane cuts every ancestor (GitHub #9): "corner #2 of the top face"
        // says which corner, not of what, and with two sections on screen that is the ambiguous half
        statusHint = "Anchored on ${cand.provenance} of ${doc.nameOf(cand.solid)}"
        return true
    }
}
