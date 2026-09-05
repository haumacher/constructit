package constructit.editor

// User-defined macros — the UI half of OP-6, and the headline capability of the paradigm: record a
// sub-construction, designate which of its free sources are the macro's *input ports*, and get a
// reusable tool in the palette.
//
// Nothing here duplicates the engine: a MacroDef is a *designation* over elements the document already
// has (definition by example, OP-6), and an instance is built from path-addressed InstanceNode views
// over that same subgraph — see Document.instantiateMacro.

/** The palette id of a macro's tool is prefixed, so a custom tool can never shadow a built-in one. */
const val MACRO_TOOL_PREFIX = "macro:"

/**
 * The create dialog's one-click closure (OP-16), named once: the refusal quotes it, the dialog labels its
 * checkbox with it, and the browser shell renders that label — so the way out is worded the same wherever
 * the user meets it.
 */
const val INCLUDE_CLOSURE_LABEL = "include everything these points are built on"

/**
 * A macro **definition** (OP-6): the sub-construction behind [elements], with the free sources
 * designated as inputs.
 *
 * The definition is not a copy of anything — it *is* the original construction, still editable on
 * canvas, which is what makes editing the definition propagate to every instance. What the definition
 * adds is the split between input ports and internal (captured) sources:
 *
 * - [pointInputs] become the tool's click slots, in order. The **first is the anchor**: every internal
 *   free point of the definition is captured relative to it, so an instance lands where it is clicked.
 * - [scalarInputs] become the tool's ordered scalar inputs, taken from the panel like any other tool's.
 * - anything else free inside the definition is a **captured default shared by all instances** (OP-6):
 *   its node is read live, so editing it re-propagates rather than drifting per instance.
 */
class MacroDef(
    val id: String,
    val name: String,
    val elements: List<Element>,
    val pointInputs: List<Element>,
    val scalarInputs: List<ScalarEntry>,
) {
    /** The tool id recorded in an instance's `tool` step (OP-18) and used by the palette. */
    val toolId: String get() = "$MACRO_TOOL_PREFIX$name"

    /** The journal step that declared this macro — what removing the tool drops again. */
    var step: Step? = null

    /**
     * The definition elements an instance materializes: all of them except the input points, which the
     * *arguments* already supply (drawing them twice would put two dots in one place).
     */
    val outputs: List<Element> get() = elements.filter { el -> pointInputs.none { it === el } }

    /**
     * The palette tool this macro is. A [ToolDef] like any other — which is the whole point of the
     * data-driven registry: a user-defined tool needs no controller code, only an entry (see
     * [Document.toolDef], which serves the document's macros beside the static [Tools.all]).
     */
    val tool: ToolDef by lazy {
        ToolDef(
            toolId,
            ToolCategory.CUSTOM,
            slots = pointInputs.map { SlotKind.POINT },
            // the dimension comes from the wired parameter's own value, so a custom tool's scalar can be
            // typed exactly like a built-in one's (OP-13) without the definition declaring anything extra
            scalars = scalarInputs.map { ScalarSlot(it.name, dimensionOf(it.ref)) },
            // A macro's words are the *document's*, not the message bundle's (OP-29): its name is what the
            // user called it and its help is composed from the ports it declares, so there is no ARB key to
            // look up — which is exactly what [ToolDef.labelText] exists for. Slice 2 turns the composed
            // sentence below into a message with placeholders, like every other composed sentence.
            labelText = name,
            helpText =
                "Custom tool $name: click ${pointInputs.size} point${if (pointInputs.size == 1) "" else "s"}" +
                    " (the first places the instance)" +
                    if (scalarInputs.isEmpty()) "." else ", with ${scalarInputs.joinToString(", ") { it.name }} from the panel.",
        ) { d, p, s -> d.instantiateMacro(this, p.points, s) }
    }
}

/**
 * One **instance** of a macro (OP-6): a composite of `{definition, argument bindings}` whose internal
 * nodes are path-addressed views into the definition. [elements] are its displayable outputs, one per
 * [MacroDef.outputs] entry.
 *
 * Purity (OP-6): there is nothing per-instance to override here. An instance has exactly the degrees of
 * freedom its arguments have — the clicked points and the wired scalars — and its internal freedom is
 * the *definition's*, reachable only on the original.
 */
class MacroInstance(val id: String, val def: MacroDef, val elements: List<Element>)

/**
 * What a selection could become (OP-6 definition-by-example / OP-16 group membership): the free sources
 * its closure reaches, plus what forbids making a tool of it at all.
 */
class MacroAnalysis(
    /** Candidate point inputs: free points in the selection's closure, **the ones it owns first**. */
    val points: List<Element>,
    /** Candidate scalar inputs: unbound parameters feeding the selection. */
    val parameters: List<ScalarEntry>,
    /** Why this selection cannot be a tool (empty when it can) — phrased for the user. */
    val problems: List<String>,
    /** Which of [points] the selection *displays* itself, as opposed to merely depending on. */
    val owned: Set<String> = points.mapTo(HashSet()) { it.id },
    /**
     * **Every** degree of freedom in the closure, of every kind (OP-16): a free point, a rider on a curve, a
     * polar offset from an anchor, an angle about a circle. [points] is the free-point subset, because only a
     * free point can be a macro's *input port* — a group, by contrast, can take any of them as a member, and
     * has to: a figure whose freedom is a rider plus an offset is exactly the one that could be grouped and
     * then never placed.
     */
    val freedoms: List<Freedom> = points.map { Freedom(it, FreedomKind.FREE_POINT, it.id, it.id in owned) },
)

/** Which of the two things the shared create dialog is making (OP-16: one dialog, two defaults). */
enum class CreateMode { GROUP, TOOL }

/**
 * One candidate row of the create dialog: a free point or a parameter the selection's closure reaches.
 *
 * What checking it means depends on the mode, which is exactly the difference OP-16 describes: in
 * [CreateMode.TOOL] a checked candidate becomes an **input port** and an unchecked one a captured
 * default; in [CreateMode.GROUP] a checked point is pulled **into the group** as a member and an
 * unchecked one stays outside it.
 */
class InputCandidate(
    val label: String,
    val element: Element?,
    val scalar: ScalarEntry?,
    var checked: Boolean,
) {
    val isPoint: Boolean get() = element != null
}

/**
 * The shared create dialog (OP-16): **group creation and macro definition are one dialog with a
 * different default**, which is what folds the macro-record UI in rather than duplicating it. Group →
 * macro is the promotion path, so the two must ask the same question — which of the closure's free
 * sources belong to the thing being made.
 *
 * Pure state + pure logic, so the shell only renders it: the defaults, the candidate list and the
 * validation are all testable headlessly.
 */
class CreateDialog(
    val mode: CreateMode,
    var name: String,
    members: List<Element>,
    val candidates: List<InputCandidate>,
    /** Set when a *tool* is impossible; a group is still offered, since it needs none of this. */
    val problem: String? = null,
    /**
     * What the group would still have to contain to be placeable (`Document.placementClosure`) — a function
     * because a dialog is built from an analysis and holds no document, exactly as the naming hook is.
     */
    private val closureOf: (List<Element>) -> List<Element> = { emptyList() },
    /** How many elements the drawing has, so the closure can say honestly when it swallows all of it. */
    private val drawingSize: Int = 0,
) {
    private val memberList = ArrayList(members)

    /** The elements the thing being made is built of — grown by [includeClosure], never by anything else. */
    val members: List<Element> get() = memberList

    val title: String get() = if (mode == CreateMode.TOOL) "Make a tool" else "Group"

    /**
     * **Give the group a frame** (OP-16 step 2), so confirming places it in the same breath — ticked by
     * default, because a group is nearly always a *part* and a part is something you move. Creating and
     * placing then commit as **one** checkpoint, so one undo removes both: they are one thing the user did.
     *
     * Unticking is first-class rather than a fallback, and [flatMeaning] says why: a flat group is the natural
     * **array original**, since the copies an array makes of it derive frame-free.
     *
     * Ignored in [CreateMode.TOOL] — a macro's placement is its anchor input (OP-6), not a frame.
     */
    var framed: Boolean = mode == CreateMode.GROUP

    /** The tick's label. */
    val framedLabel: String get() = "movable (with frame)"

    /** What ticking it makes — an intent, not a success. */
    val framedMeaning: String get() =
        "a movable part: it gets a frame, so the whole of it moves as one — drag any member, or type x / y / angle"

    /** What leaving it unticked makes — the other intent, and the one a user found the use for. */
    val flatMeaning: String get() =
        "a named set: no frame, e.g. an array original — the copies an array makes of it derive frame-free"

    /**
     * The dialog's own help line. Naming the default is the point: a group takes the freedom it is built on
     * and becomes movable, while a tool starts with everything free ticked, because a tool with no inputs is
     * a copy and not a function.
     */
    val help: String get() =
        when {
            problem != null -> problem
            mode == CreateMode.TOOL ->
                "Ticked sources become the tool's inputs (clicked, or taken from the panel). " +
                    "The first point places the instance; unticked ones are captured, shared by every instance."
            candidates.isEmpty() ->
                "A group of the ${members.size} selected element(s). Ticked \"$framedLabel\": $framedMeaning. " +
                    "Unticked: $flatMeaning."
            else ->
                "A group of the ${members.size} selected element(s), plus the ticked degrees of freedom it is " +
                    "built on — those are what the frame moves. Untick one to leave it outside, and the group " +
                    "will not move independently. Ticked \"$framedLabel\": $framedMeaning. Unticked: $flatMeaning."
        }

    /**
     * Which of the ticked members' freedoms the group would **not** be able to carry, in the user's words —
     * read at creation time so an unticked shared point is reported *here*, where it can still be ticked,
     * rather than only when Place refuses much later (OP-16's honest-failure rule).
     */
    var warnings: List<String> = emptyList()
        internal set

    fun toggle(index: Int): Boolean {
        val c = candidates.getOrNull(index) ?: return false
        c.checked = !c.checked
        closureCache = null // a different membership is built on different things
        return true
    }

    // ---- the one-click closure (OP-16's honest failure, with a way through it) ----

    private var closureCache: List<Element>? = null

    /**
     * The elements this group would **also** have to contain to move independently — the answer to the
     * refusal the user could not act on (*"include them in the group"*, over dozens of elements, "almost
     * impossible to do"). Computed over the membership as it stands, ticks included, since a ticked freedom
     * is already on its way in.
     *
     * Empty in [CreateMode.TOOL]: a macro's inputs are *ports*, so what its closure reaches is captured by
     * design rather than pulled in (OP-6).
     */
    val closure: List<Element> get() =
        closureCache ?: (if (mode == CreateMode.TOOL) emptyList() else closureOf(members + checkedPoints.filter { p -> members.none { it === p } }))
            .also { closureCache = it }

    /** The action's label — one sentence, in the words the refusal uses. */
    val closureLabel: String get() = INCLUDE_CLOSURE_LABEL

    /**
     * What ticking it costs, **as a count, before confirming** — and honestly when that is everything: a
     * closure that swallows the drawing is a decision the user should be able to decline, not a surprise.
     */
    val closureNote: String get() =
        when {
            closure.isEmpty() -> "nothing more is needed — this group already moves as one"
            members.size + closure.size >= drawingSize && drawingSize > 0 ->
                "+ ${closure.size} elements — that is the whole drawing; leaving it flat may be the better answer"
            else -> "+ ${closure.size} element${if (closure.size == 1) "" else "s"}"
        }

    /** Whether the affordance is worth showing at all — still true once taken, so the tick does not vanish. */
    val hasClosure: Boolean get() = closureTaken || closure.isNotEmpty()

    /**
     * Whether the closure has been taken in. Membership only ever **grows** here, so the tick is one-way and
     * the shell renders it as such; the way back is Cancel, which is the way back from every other choice in
     * this dialog too.
     */
    var closureTaken: Boolean = false
        private set

    /**
     * Take the closure in — the one click. Membership grows exactly as a ticked candidate's does and is
     * recorded exactly as any membership is (a `group` step's `els=`, OP-18): no new step semantics.
     */
    fun includeClosure(): Boolean {
        val extra = closure
        if (extra.isEmpty()) return false
        memberList.addAll(extra.filter { e -> memberList.none { it === e } })
        closureCache = null
        closureTaken = true
        return true
    }

    val checkedPoints: List<Element> get() = candidates.filter { it.checked }.mapNotNull { it.element }
    val checkedScalars: List<ScalarEntry> get() = candidates.filter { it.checked }.mapNotNull { it.scalar }

    /** Whether Create would do anything: a tool needs at least one point input to place instances by. */
    val ready: Boolean get() = if (mode == CreateMode.TOOL) problem == null && checkedPoints.isNotEmpty() else members.isNotEmpty()

    /** What is missing, when [ready] is false. */
    val blocker: String? get() =
        when {
            ready -> null
            problem != null -> problem
            mode == CreateMode.TOOL -> "Tick at least one point: the first one is where an instance is placed"
            else -> "Select the elements to group first"
        }

    companion object {
        /**
         * The dialog for [members] with the mode's default ticks applied — **everything, in both modes**, for
         * the same reason read two ways.
         *
         * For a **tool**, every free source it reaches is an input unless the user says otherwise: a tool
         * with no inputs is a copy and not a function.
         *
         * For a **group** the default used to be *nothing*, which made the everyday case fail late: a naive
         * group of the visible geometry left the freedom it is built on outside, and Place then refused it
         * ("… is also used by …"), or worse the group could not move at all. Ticking the closure by default
         * makes a group movable by default, and unticking is still there for the case where a point genuinely
         * belongs to something else — which the dialog now *says*, at creation time (see [warnings]).
         */
        fun of(
            mode: CreateMode,
            members: List<Element>,
            analysis: MacroAnalysis,
            // how to name an element to the user — its script-local name (OP-18, [Document.nameOf]); a
            // function because a dialog is built from an analysis and has no document to ask
            name: (Element) -> String = { it.id },
            // what a membership would still have to contain to be placeable, and how big the drawing is —
            // the one-click closure's two inputs (OP-16), both asked of the document
            closureOf: (List<Element>) -> List<Element> = { emptyList() },
            drawingSize: Int = 0,
        ): CreateDialog {
            val tool = mode == CreateMode.TOOL
            // A tool's point rows are the *free points* only — an input port is clicked, and only a free point
            // can be placed by a click. A group's rows are every degree of freedom in the closure, labelled by
            // kind, because any of them can be a member (see [MacroAnalysis.freedoms]).
            val anyOwned = analysis.points.any { it.id in analysis.owned }
            val rows =
                if (tool) {
                    // Ticked by default: the free points the selection *owns*. When it owns none — the user
                    // selected only derived geometry — its ancestors are ticked instead, since otherwise there
                    // would be no input at all and nothing to place an instance by.
                    analysis.points.map { InputCandidate(name(it), it, null, !anyOwned || it.id in analysis.owned) } +
                        analysis.parameters.map { InputCandidate(it.name, null, it, true) }
                } else {
                    analysis.freedoms.map { InputCandidate(it.label, it.element, null, true) }
                }
            return CreateDialog(mode, "", members, rows, analysis.problems.firstOrNull()?.takeIf { tool }, closureOf, drawingSize)
        }
    }
}
