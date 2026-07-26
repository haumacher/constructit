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
            name,
            ToolCategory.CUSTOM,
            slots = pointInputs.map { SlotKind.POINT },
            scalars = scalarInputs.map { it.name },
            help =
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
    val members: List<Element>,
    val candidates: List<InputCandidate>,
    /** Set when a *tool* is impossible; a group is still offered, since it needs none of this. */
    val problem: String? = null,
) {
    val title: String get() = if (mode == CreateMode.TOOL) "Make a tool" else "Group"

    /**
     * The dialog's own help line. Naming the default is the point: a group is a plain named set unless
     * something is ticked, while a tool starts with everything free ticked, because a tool with no
     * inputs is a copy and not a function.
     */
    val help: String get() =
        when {
            problem != null -> problem
            mode == CreateMode.TOOL ->
                "Ticked sources become the tool's inputs (clicked, or taken from the panel). " +
                    "The first point places the instance; unticked ones are captured, shared by every instance."
            candidates.isEmpty() -> "A named set of the ${members.size} selected element(s)."
            else -> "A named set of the ${members.size} selected element(s). Tick a point to pull it in as a member too."
        }

    fun toggle(index: Int): Boolean {
        val c = candidates.getOrNull(index) ?: return false
        c.checked = !c.checked
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
         * The dialog for [members] with the mode's default ticks applied: **nothing** for a group (a
         * plain named set, exactly what the Group button always did) and **everything** for a tool (all
         * the free sources it reaches are inputs unless the user says otherwise).
         */
        fun of(
            mode: CreateMode,
            members: List<Element>,
            analysis: MacroAnalysis,
        ): CreateDialog {
            val tick = mode == CreateMode.TOOL
            // Ticked by default: the free points the selection *owns*. When it owns none — the user
            // selected only derived geometry — its ancestors are ticked instead, since otherwise there
            // would be no input at all and nothing to place an instance by.
            val anyOwned = analysis.points.any { it.id in analysis.owned }
            // a group can only take *elements*, so parameter rows appear for a tool only — the one
            // asymmetry between the two modes beyond the default tick
            val rows =
                analysis.points.map { InputCandidate(it.id, it, null, tick && (!anyOwned || it.id in analysis.owned)) } +
                    if (tick) analysis.parameters.map { InputCandidate(it.name, null, it, true) } else emptyList()
            return CreateDialog(mode, "", members, rows, analysis.problems.firstOrNull()?.takeIf { tick })
        }
    }
}
