package constructit.editor

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.FrameValue
import constructit.core.ScalarValue
import constructit.core.SourceNode
import constructit.dsl.PointRef
import constructit.geom.Justification
import constructit.geom.Vec2
import constructit.units.Dimension
import constructit.units.Quantity

/** An argument of a recorded construction [Step]. */
sealed interface Arg {
    class El(val el: Element) : Arg

    class Sc(val entry: ScalarEntry) : Arg

    /**
     * Several scalars in one argument, in the order the tool consumes them (x, then y). A list of one
     * encodes exactly as [Sc] does, so the step text of every single-scalar tool is unchanged by this.
     */
    class Scs(val entries: List<ScalarEntry>) : Arg

    class Pos(val p: Vec2) : Arg

    class Num(val q: Quantity) : Arg

    class Text(val s: String) : Arg

    /** A quoted user-given name (a group's), so it stays one word and cannot read as a keyword. */
    class Label(val s: String) : Arg

    class Els(val els: List<Element>) : Arg

    class Positions(val ps: List<Vec2>) : Arg

    /** Several numbers in one argument — a tool's own degrees of freedom, in the order it created them. */
    class Nums(val qs: List<Quantity>) : Arg

    /**
     * A pick of a **replicated gesture** (OP-23), written `e2@1`: the member of [el]'s orbit at index
     * [offset], where [el] is that orbit's member 0 — the one member every count has.
     *
     * An element reference, deliberately: it is what makes an orbit's picks travel through the delete
     * cascade and the name map like every other reference, while the offset says the gesture's *rule*
     * rather than one of its copies.
     */
    class Member(val el: Element, val offset: Int) : Arg

    /** A comma-separated list of mixed references — a replicated gesture's picks (`e2@0,e1`). */
    class Refs(val items: List<Arg>) : Arg

    /** `key=value`, so a step with several optional parts stays readable. */
    class Keyed(val key: String, val value: Arg) : Arg
}

/**
 * One recorded construction step, and the elements it created (in order).
 *
 * Recording *steps* rather than nodes is what makes the whole synthetic layer — handles, styles, path
 * and thick-path bookkeeping — free: replaying a step runs the same code that built it, so all of that
 * is reconstructed rather than stored.
 */
class Step(val kind: String, val args: List<Arg>) {
    val creates = ArrayList<Element>()

    /** Scalars this step introduced (a parameter, a measurement) — a dependency unit for delete too. */
    val createsScalars = ArrayList<ScalarEntry>()
}

/**
 * Reads and writes a document as a **construction script**: the drawing *is* a construction (OP-5),
 * so the file is the sequence of steps that built it, and loading replays them.
 *
 * What is deliberately *not* in the file:
 * - **Nodes.** A step rebuilds its own sub-graph, so no op needs a name or a rebuild path.
 * - **Handles, styles, path and thick-path structure.** All synthetic: created by the same methods that
 *   create the geometry, hence recreated by replay.
 * - **A separate values section.** Instead, a step's positional literals are written as the *current*
 *   value of what that step introduced, so the script always describes the drawing as it is now. That
 *   keeps the file purely a construction, with no naming scheme for internal nodes leaking into it.
 *
 * Elements are referred to by script-local names (`e1`, `e2`, …) declared by the step that creates
 * them, so the format does not depend on runtime id generation. A count mismatch on load is an error
 * rather than a silently different drawing.
 */
object DocumentFormat {
    /**
     * The version this build **writes**. See *Versioning & migration* in DESIGN.md (OP-18): a stored literal's
     * meaning is frozen the moment a build that could have written it shipped, so changing what one means is a
     * version bump plus a migration — never an edit to the reader.
     */
    const val VERSION = 2

    /** The oldest version this build can still read. Every version in between is migrated on load. */
    const val OLDEST_READABLE = 1

    const val HEADER = "constructit $VERSION"

    private const val MAGIC = "constructit"

    // ---- writing ----

    fun save(doc: Document): String {
        val ev = Evaluator()
        val present = doc.elements.toHashSet()
        val names = HashMap<String, String>() // element id -> script name
        val out = StringBuilder(HEADER).append('\n')
        for ((index, step) in doc.journal.withIndex()) {
            val args = restate(doc, step, index, ev, present, names).joinToString(" ") { encode(it, names) }
            // asked of the document, which derives it from this very journal ([Document.nameOf]) — so the name
            // written here and the name the panel, the status line and every dialog show cannot drift apart.
            // The local map is still what [encode] resolves *references* through, because a reference no
            // earlier step declared has to stand out as one (`?e17`) rather than read as a name.
            val created = step.creates.map { el -> doc.nameOf(el).also { names[el.id] = it } }
            out.append(step.kind)
            if (args.isNotEmpty()) out.append(' ').append(args)
            if (created.isNotEmpty()) out.append(" -> ").append(created.joinToString(","))
            out.append('\n')
        }
        return out.toString()
    }

    /**
     * A step's literals as they are *now*, not as they were typed.
     *
     * A drag or a typed value changes a source node that some step introduced, so re-reading the live
     * value here is what lets the script carry current state without a separate values section. Only
     * literals that are state are replaced; a literal that encodes a *choice* — which side of a line,
     * which intersection branch — is kept verbatim, since replay must make the same choice.
     */
    private fun restate(
        doc: Document,
        step: Step,
        stepIndex: Int,
        ev: Evaluator,
        present: Set<Element>,
        names: Map<String, String>,
    ): List<Arg> {
        // where this element is *as this step replays*: its world position, or — for a vertex of a path a
        // later `place` step captures — the position it has before that capture (OP-16, see
        // [Document.restatedPosition]). One rule for points and path vertices alike.
        fun posOf(el: Element): Vec2? = doc.restatedPosition(el, stepIndex, ev)
        // A step whose creations have since been *removed* — by a join collapsing a jog — keeps its
        // recorded literals. Re-reading a deleted vertex would describe the state after the edit that
        // deleted it, whereas replay has to rebuild the geometry that existed before, so the later join
        // has something to collapse. What survives the join is described by the steps that still own it.
        if (step.creates.any { it !in present }) return step.args
        return when (step.kind) {
            // membership is state (OP-16): a member whose creating step is gone is simply not written,
            // so a delete leaves a consistent group and one whose members are all gone leaves no step
            // at all — [Document.dependentSteps] drops it. Naming, not presence, is the test: a script
            // can only refer to what an earlier step declared.
            // hide/show follow the same rule for the same reason (OP-18's visibility reversal — see
            // [Document.setElementsVisible]): the step states which elements are hidden *now*, so a member
            // the script no longer declares is simply not written, and a step left with none is dropped by
            // [Document.dependentSteps] before it can be
            "group", "hide", "show" ->
                step.args.map { arg ->
                    val els = (arg as? Arg.Keyed)?.value as? Arg.Els
                    when {
                        arg is Arg.Keyed && els != null -> Arg.Keyed(arg.key, Arg.Els(els.els.filter { it.id in names }))
                        // a group's name is state too (OP-7's rename pattern one level up): the step that
                        // *declares* it writes the name it has now, and load resolves the `place` step by it
                        arg is Arg.Label && step.kind == "group" -> doc.groupDeclaredBy(step)?.let { Arg.Label(it.name) } ?: arg
                        else -> arg
                    }
                }
            // the name a user gave an element ([Document.nameElement]): restated, because a rename must not
            // add a second step — the name is state, exactly as a parameter's value is
            "name" -> {
                val el = (step.args.firstOrNull() as? Arg.El)?.el
                val given = el?.let { doc.userNameOf(it) }
                if (given == null) step.args else listOf(step.args[0], Arg.Label(given))
            }
            // the material a user assigned to a solid (Tier 1 of the appearance package): restated for the
            // same reason a name is, since re-picking a colour must not add a second step. A *new step kind*
            // needs no version bump — no stored literal changed its meaning (OP-18's versioning rule), and a
            // drawing written before materials existed simply carries none and loads with the defaults.
            "material" -> {
                val el = (step.args.firstOrNull() as? Arg.El)?.el
                val m = el?.let { doc.assignedMaterial(it) }
                if (m == null) {
                    step.args
                } else {
                    listOf(
                        step.args[0],
                        Arg.Keyed("color", Arg.Text(m.color)),
                        Arg.Keyed("rough", Arg.Num(Quantity.number(m.roughness))),
                        Arg.Keyed("metal", Arg.Num(Quantity.number(m.metallic))),
                    )
                }
            }
            // a placement's frame is state (OP-16 step 2): the origin and angle are re-read from the frame
            // source, so a dragged or typed group comes back where it now is. The members' own steps are
            // replayed *before* this one retrofits them, and each restates the position that retrofit
            // expects — a free point's world position, a captured path vertex's pre-capture one — so the
            // script needs no local coordinates in it at all (see [Document.restatedPosition]).
            // …and the placement names the group it places. Resolved by **step identity**, never by the
            // recorded label: a renamed group would otherwise stop matching, and the frame — the one thing
            // this step exists to restate — would silently revert to where it was first placed.
            "place" -> {
                val g = doc.groupPlacedBy(step)
                val f = g?.frameNode?.let { (ev.eval(it) as? EvalResult.Ok)?.value as? FrameValue }
                if (f == null) {
                    step.args
                } else {
                    listOf(Arg.Label(g.name), Arg.Keyed("at", Arg.Pos(f.origin)), Arg.Keyed("angle", Arg.Num(Quantity.rad(f.angle))))
                }
            }
            "param" -> {
                val e = (step.args[0] as Arg.Sc).entry
                listOf(step.args[0], Arg.Text("="), Arg.Num(value(e, ev)))
            }
            // a rider's position along its host is **state**: it is dragged, typed, and compensated while the
            // host turns (OP-20). What the step restates is therefore the rider's own parameter (`dofs=`,
            // the same seam a dimension's placement uses) and not the click, since *which* curve and which
            // side of it are choices replay must repeat — see [Document.pointOnCurve].
            // Asked of the document rather than read off the handle, because a rider's parameter and what its
            // *handle* writes are two different nodes once the position has been re-anchored to a base of the
            // same carrier (OP-4 case b): the handle then writes the offset, while the step must restate the
            // position along the carrier that replaying it reproduces — see [Document.restatedRiderParam].
            "pointoncurve" -> {
                val q = step.creates.singleOrNull()?.let { doc.restatedRiderParam(it, ev) }
                if (q == null) step.args else step.args + Arg.Keyed("dofs", Arg.Nums(listOf(q)))
            }
            // the same rule for a re-parameterization recorded on its own rather than through a tool
            // (OP-4 case b): the offset is state, so it is restated — one distance and one angle for a polar
            // offset, one signed distance for a rider measured along its carrier
            // and its inverse, for the one case that hands a *literal* back rather than taking one away: a
            // rider freed from its host (OP-16's view re-pointed — [Document.detachRider]) owns its two
            // coordinates from then on and may be dragged anywhere, so they are state on this step. Every
            // other reading of *Make absolute* restates nothing, because what it restores was already stated
            // by the step that created the point.
            "relative", "absolute" -> {
                val dofs = relativeDofs(doc, step, ev)
                if (dofs.isEmpty()) step.args else step.args + Arg.Keyed("dofs", Arg.Nums(dofs))
            }
            "point", "orthostart", "orthovertex", "orthoprepend" ->
                step.creates.firstOrNull()?.let { posOf(it) }?.let { listOf(Arg.Pos(it)) } ?: step.args
            // both of a break's positions are state: where the leg was split, and how far the jog has
            // since been pulled open
            "orthobreak" -> {
                val m = step.creates.getOrNull(0)?.let { posOf(it) }
                val n = step.creates.getOrNull(1)?.let { posOf(it) }
                if (m == null || n == null) step.args else listOf(step.args[0], Arg.Pos(m), Arg.Pos(n))
            }
            // an arc break's split **angle** is state (the rider slides round the carrier), so it is restated
            // from the rider the step created; the arc it names and the `ccw` it stored are choices, kept
            // verbatim — see [Document.breakArc]
            "breakarc" -> {
                val q = step.creates.firstOrNull()?.let { doc.restatedRiderParam(it, ev) }
                if (q == null) step.args else listOf(step.args[0], Arg.Num(q), step.args[2])
            }
            // an interval feature's position and carried heights live in the parameters the step created,
            // in that order (see [Document.addInterval]). They are state, so a value typed in the panel
            // comes back on reload — which is the whole point of recording the interval as a description
            // rather than as the click that placed it (OP-21).
            //
            // Matched to the keys **positionally**, not by the parameters' names: a panel name is the
            // user's (OP-7) and a renamed `pos` must not quietly stop restating its position. The `width=`
            // key names a scalar the step did not create, so it is written by name as usual.
            "opening" -> {
                val created = listOf("pos", "sill", "head").zip(step.createsScalars).toMap()
                step.args.map { arg ->
                    val own = if (arg is Arg.Keyed) created[arg.key] else null
                    if (arg is Arg.Keyed && own != null) Arg.Keyed(arg.key, Arg.Num(value(own, ev))) else arg
                }
            }
            "tool" -> {
                // **State is restated as a value, never as a rewritten click** — one rule for every tool, and
                // the clicks always stay verbatim because what a click encodes is a *choice* (which curve,
                // which side, which sector) that replay must repeat (see this object's header).
                //
                // Three kinds of state ride the one `dofs=` argument, and a step owns exactly one of them:
                // - a **rider's own parameter** (`Document.restatedRiderParam`), for the point-on-line and
                //   point-on-circle tools. This used to be restated by *rewriting the last click* to the
                //   rider's current position, which is wrong twice over: replay re-projects that position onto
                //   the geometry as it stands **before** the placement that turns the group (so a turned figure
                //   came back with its rider somewhere else, and `save → load → save` was not byte-equal), and
                //   the position of a rider that sits at a turned origin prints as `-6.12E-16`. The
                //   `pointoncurve` step has restated its parameter since session 9; this is the same rule,
                //   without the special case.
                // - a **dimension's placement** (OP-13), which is dragged and typed.
                // - a **re-parameterization's offset** (OP-4 case b) — for a tool that creates nothing at all.
                val riderDof = step.creates.singleOrNull { it.kind == ElementKind.ON_CURVE }?.let { doc.restatedRiderParam(it, ev) }
                val dofs =
                    listOfNotNull(riderDof) +
                        step.creates.mapNotNull { it.annotation }.flatMap { it.dofValues() } +
                        relativeDofs(doc, step, ev)
                step.args + signsOf(doc, step) + if (dofs.isEmpty()) emptyList() else listOf(Arg.Keyed("dofs", Arg.Nums(dofs)))
            }
            // the branch this step's click chose, restated so replay never scores it again (OP-1) — see
            // [Document.intersectNear]
            "intersectnear" -> step.args + signsOf(doc, step)
            else -> step.args
        }
    }

    /**
     * The **scored discrete choices** [step] must restate, as `signs=1;-1;1`, or nothing when it scored none.
     *
     * A choice is not state, so it is not re-read from the geometry — it is read from where the construction
     * that made it recorded it ([Document.storedSigns]) and written unchanged for every save after. This is the
     * half of OP-1 the file was missing: a `Select` sign the file drops is a sign the *load* has to re-decide,
     * and it re-decided against geometry that had moved since the click — reported as *"fillets inverted,
     * producing sharp corners"*. Written as plain integers rather than through `dofs=`, because they are the
     * opposite of a degree of freedom.
     */
    private fun signsOf(
        doc: Document,
        step: Step,
    ): List<Arg> =
        doc.storedSigns(step).let {
            if (it.isEmpty()) emptyList() else listOf(Arg.Keyed("signs", Arg.Text(it.joinToString(";"))))
        }

    private fun value(
        e: ScalarEntry,
        ev: Evaluator,
    ): Quantity = ((ev.eval(e.ref.node) as? EvalResult.Ok)?.value as? ScalarValue)?.q ?: Quantity.mm(0.0)

    /**
     * The re-parameterization state a `relative` / `tool makerel` step restates: the offsets of the elements
     * **that step itself** re-anchored (OP-4 case b).
     *
     * Deliberately ownership rather than reference. A step that merely *uses* a relative point — a circle
     * through it — would otherwise carry that point's distance and angle as its own `dofs=`, and in a chain
     * (a rider measured from a base that is itself measured from something) the base's offset would join the
     * list replay consumes positionally.
     */
    private fun relativeDofs(
        doc: Document,
        step: Step,
        ev: Evaluator,
    ): List<Quantity> = doc.reparamDofs(step, ev)

    private fun scalarValue(
        node: SourceNode,
        ev: Evaluator,
    ): Quantity? = ((ev.eval(node) as? EvalResult.Ok)?.value as? ScalarValue)?.q

    private fun encode(
        arg: Arg,
        names: Map<String, String>,
    ): String =
        when (arg) {
            is Arg.El -> names[arg.el.id] ?: "?${arg.el.id}"
            is Arg.Els -> arg.els.joinToString(",") { names[it.id] ?: "?${it.id}" }
            is Arg.Member -> "${names[arg.el.id] ?: "?${arg.el.id}"}@${arg.offset}"
            is Arg.Refs -> arg.items.joinToString(",") { encode(it, names) }
            is Arg.Sc -> quote(arg.entry.name)
            is Arg.Scs -> arg.entries.joinToString(",") { quote(it.name) }
            is Arg.Pos -> pos(arg.p)
            is Arg.Positions -> arg.ps.joinToString(";") { pos(it) }
            is Arg.Nums -> arg.qs.joinToString(";") { num(it) }
            is Arg.Num -> num(arg.q)
            is Arg.Text -> arg.s
            is Arg.Label -> quote(arg.s)
            is Arg.Keyed -> "${arg.key}=" + encode(arg.value, names)
        }

    private fun pos(p: Vec2) = "${trim(p.x)},${trim(p.y)}"

    private fun num(q: Quantity): String =
        when (q.dim) {
            Dimension.LENGTH -> "${trim(q.mm)}mm"
            Dimension.ANGLE -> "${trim(q.deg)}deg"
            else -> trim(q.value)
        }

    /**
     * **The one canonical number format of the file**: a plain decimal that reloads to the *same double*,
     * with no trailing noise and — the part that had to be added — **never in scientific notation**.
     *
     * `Double.toString` gives the shortest representation that round-trips, which is why it is the basis; but
     * for a very small or very large magnitude it gives `-6.123233995736766E-16`, and a script full of `E-16`
     * is not a canonical serialization (the rule the SVG goldens follow for the same reason). A turned placed
     * group produces exactly such a value — a coordinate that is zero up to the rounding of `sin 90°` — so this
     * stopped being cosmetic.
     *
     * The exponent is therefore expanded by **moving the decimal point in the digits themselves**: a pure
     * string transformation, so not one bit of the value is lost and the reloaded double is bit-identical.
     * Deliberately not fixed-precision rounding, which would be stable but would quietly *move* the drawing on
     * every reload.
     */
    private fun trim(v: Double): String {
        val r = (if (v == 0.0) 0.0 else v).toString() // normalise -0.0, which reads as noise
        val plain = if (r.contains('E') || r.contains('e')) expand(r) else r
        return if (plain.endsWith(".0")) plain.dropLast(2) else plain
    }

    /** `-6.12E-16` -> `-0.000000000000000612`: the same digits, the point moved by the exponent. */
    private fun expand(s: String): String {
        val sign = if (s.startsWith("-")) "-" else ""
        val body = s.removePrefix("-").removePrefix("+")
        val e = body.indexOfFirst { it == 'E' || it == 'e' }
        val exp = body.substring(e + 1).removePrefix("+").toInt()
        val mantissa = body.substring(0, e)
        val dot = mantissa.indexOf('.')
        val digits = if (dot < 0) mantissa else mantissa.removeRange(dot, dot + 1)
        // where the point sits in [digits] after applying the exponent
        val point = (if (dot < 0) mantissa.length else dot) + exp
        val out =
            when {
                point <= 0 -> "0." + "0".repeat(-point) + digits
                point >= digits.length -> digits + "0".repeat(point - digits.length)
                else -> digits.substring(0, point) + "." + digits.substring(point)
            }
        return sign + out.trimEnd('.')
    }

    private fun quote(s: String) = "\"" + s.replace("\"", "'") + "\""

    // ---- reading ----

    class LoadError(message: String) : Exception(message)

    /**
     * A step a **re-stamp** cannot carry over, and why — never thrown by an ordinary load (OP-23).
     *
     * A load is strict, because a script that does not replay exactly is a drawing that came back different.
     * A re-stamp is an *edit*: changing a pattern's count changes how many members there are, so a gesture
     * that named a member the new count does not have has genuinely lost its subject, and the honest answer
     * is to drop it and say so — the same shape of answer the delete cascade gives.
     */
    private class DropStep(val why: String) : Exception()

    /** What a re-stamp is doing, and what it had to leave behind (OP-23). */
    class Restamp internal constructor(
        val pattern: String,
        val count: Int,
    ) {
        val notes = ArrayList<String>()

        /**
         * Whether the step now replaying legitimately creates a **different number of elements** than the
         * script declares — the pattern being re-counted, one of its gestures, or a boundary re-followed
         * round more pieces. Everywhere else a count mismatch is still the load error it always was (OP-18),
         * which is what keeps this from becoming a licence for any drift.
         */
        internal var resized = false

        /**
         * Which end the surviving names line up from. A pattern's and an orbit's creations are copy-major, so
         * a name keeps meaning the same copy when counted from the **start**; a traced outline creates its
         * joints first and the loop **last**, so its names line up from the end — that way the name every
         * later step actually uses (the loop's) still names the loop.
         */
        internal var alignFromEnd = false
    }

    fun load(text: String): Document {
        val doc = Document()
        replay(doc, text)
        return doc
    }

    /**
     * Replay [text] with pattern [pattern] **re-stamped** at [count] instances (OP-23).
     *
     * A journal rewrite plus a replay — the delete machinery's move. The pattern step is replayed with the new
     * count, and every `orbit` step re-runs its own rule at that count, which is what makes the whole
     * downstream cone come out right without anything being copied. Names map positionally: a member the new
     * count adds is simply unnamed (nothing referred to it), and a member it removes leaves the steps that
     * named it to be dropped, each with a reason.
     */
    fun restamp(
        text: String,
        pattern: String,
        count: Int,
    ): Pair<Document, List<String>> {
        val doc = Document()
        val ctx = Restamp(pattern, count)
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        doc.replayingVersion = versionOf(lines.firstOrNull() ?: throw LoadError("empty document"))
        try {
            replaySteps(doc, lines, ctx)
        } finally {
            doc.replayingVersion = null
        }
        doc.publishLoadNotes()
        return doc to ctx.notes
    }

    /**
     * The format version [text] declares, checked against what this build can read (OP-18).
     *
     * A number rather than a whole-line match, so a *newer* file says so — "written by a newer version of
     * ConstructIt" is a fact the user can act on, where "unsupported format" is not.
     */
    private fun versionOf(head: String): Int {
        if (!head.startsWith("$MAGIC ")) throw LoadError("not a ConstructIt drawing: '$head'")
        val v = head.removePrefix("$MAGIC ").trim().toIntOrNull() ?: throw LoadError("unsupported format: '$head'")
        if (v > VERSION) {
            throw LoadError("this drawing is format $v; this build reads up to $VERSION — it was written by a newer version")
        }
        if (v < OLDEST_READABLE) throw LoadError("this drawing is format $v; this build reads $OLDEST_READABLE and newer")
        return v
    }

    /** Replay [text] into [doc]; throws [LoadError] on a malformed or inconsistent script. */
    fun replay(
        doc: Document,
        text: String,
    ) {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        val head = lines.firstOrNull() ?: throw LoadError("empty document")
        // the version is in force for the whole replay, and the *document* holds it, because the semantics that
        // differ belong to the steps' own code (see [Document.replayingVersion])
        doc.replayingVersion = versionOf(head)
        try {
            replaySteps(doc, lines)
        } finally {
            doc.replayingVersion = null
        }
        doc.publishLoadNotes()
    }

    private fun replaySteps(
        doc: Document,
        lines: List<String>,
        restamp: Restamp? = null,
    ) {
        val byName = HashMap<String, Element>()
        for ((lineNo, line) in lines.drop(1).withIndex()) {
            val (body, declared) = split(line)
            var words = body.split(' ').filter { it.isNotEmpty() }
            // the one literal a re-stamp rewrites: the count of the pattern being re-stamped (OP-23)
            if (restamp != null && words.firstOrNull() == "pattern" && words.getOrNull(1)?.let { unquote(it) } == restamp.pattern) {
                words = words.map { if (it.startsWith("count=")) "count=${restamp.count}" else it }
            }
            val before = doc.elements.toHashSet()
            restamp?.resized = false
            restamp?.alignFromEnd = false
            try {
                apply(doc, words, byName, restamp)
            } catch (e: DropStep) {
                restamp?.notes?.add("dropped ${describe(words)} — ${e.why}")
                continue
            } catch (e: LoadError) {
                // in a re-stamp a reference to a member the new count does not have is a *loss*, not a
                // malformed file: the step is dropped and named, and the rest of the drawing rebuilds
                if (restamp != null && e.message?.startsWith("unknown element") == true) {
                    restamp.notes.add("dropped ${describe(words)} — it named a member that ${restamp.count} instances do not have")
                    continue
                }
                throw LoadError("line ${lineNo + 2}: ${e.message} in '$line'")
            } catch (e: Exception) {
                throw LoadError("line ${lineNo + 2}: ${e.message ?: e.toString()} in '$line'")
            }
            val created = doc.elements.filter { it !in before }
            // only a step the re-stamp itself resized may create a different number of elements than the
            // script declares; everywhere else that is still the load error it always was (OP-18)
            val elastic = restamp?.resized == true
            if (created.size != declared.size && !elastic) {
                throw LoadError(
                    "line ${lineNo + 2}: '${words.firstOrNull()}' created ${created.size} element(s) " +
                        "but the script declares ${declared.size} (${declared.joinToString(",")}) — the file was " +
                        "written by a different version",
                )
            }
            // positional, so a name keeps meaning the same *copy*: what a bigger count adds is unnamed, and
            // what a smaller one removes leaves its name unmapped for the drop rule above to catch
            val shift = if (elastic && restamp?.alignFromEnd == true) created.size - declared.size else 0
            declared.forEachIndexed { i, n -> created.getOrNull(i + shift)?.let { byName[n] = it } }
        }
    }

    /** A step, in as many words as a note needs to identify it. */
    private fun describe(words: List<String>): String = words.take(3).joinToString(" ")

    private fun split(line: String): Pair<String, List<String>> {
        val i = line.indexOf("->")
        if (i < 0) return line to emptyList()
        return line.substring(0, i).trim() to line.substring(i + 2).split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun apply(
        doc: Document,
        words: List<String>,
        byName: Map<String, Element>,
        restamp: Restamp? = null,
    ) {
        fun el(i: Int): Element = byName[words[i]] ?: throw LoadError("unknown element '${words.getOrNull(i)}'")

        fun scalar(i: Int): ScalarEntry {
            val name = unquote(words.getOrElse(i) { throw LoadError("missing scalar name") })
            return doc.scalars.firstOrNull { it.name == name } ?: throw LoadError("unknown scalar '$name'")
        }

        when (val kind = words.firstOrNull() ?: throw LoadError("empty step")) {
            "param" -> doc.newParameter(unquote(words[1]), quantity(words[3]))
            "point" -> parsePos(words[1]).let { doc.freePoint(it.x.let(Quantity::mm), it.y.let(Quantity::mm)) }
            "orthostart" -> doc.startOrthoPath(parsePos(words[1]))
            "orthovertex" -> doc.addOrthoVertex(currentPath(doc), parsePos(words[1]))
            "orthoprepend" -> doc.prependOrthoVertex(currentPath(doc), parsePos(words[1]))
            "orthoresume" -> {
                val (path, atEnd) = doc.resumableEnd(el(1)) ?: throw LoadError("'${words[1]}' is not an open path end")
                doc.resumeOrthoPath(path, atEnd)
            }
            "orthoclose" -> doc.closeOrthoPath(currentPath(doc))
            "orthojoin" -> {
                val (path, i) = doc.legOf(el(1)) ?: throw LoadError("'${words[1]}' is not an ortho segment")
                doc.joinCollapsedLeg(path, i)
            }
            "orthobreak" -> {
                val (path, i) = doc.legOf(el(1)) ?: throw LoadError("'${words[1]}' is not an ortho segment")
                doc.breakOrthoLeg(path, i, parsePos(words[2]), parsePos(words[3]))
            }
            "orthodiscard" -> doc.discardOrthoPath(currentPath(doc))
            // splitting an arc: the carrier it names, the angle it splits at (state), the sweep it keeps (OP-1)
            "breakarc" -> doc.breakArc(el(1), quantity(words[2]), words.getOrNull(3) != "cw")
            // the step kinds keep their user-facing names; what they carry is the generic thick path and
            // its interval features (OP-21) — a pure description, never the geometry it computes
            "wall" -> doc.buildThickPath(currentPath(doc), scalar(1).ref, justification(words.getOrNull(2)))
            "opening" -> applyInterval(doc, words, el(1))
            // sketch spaces (OP-17). `sketchspace` *declares* one — the solid and the boundary-piece index
            // its plane is derived from (OP-8) — and makes it current, exactly as `orthostart` does for a
            // path; `space` switches back. Both create nothing, so they declare nothing, and steps belong
            // to the last space named — the ordering rule the writer relies on ([Document.noteSpace]).
            "sketchspace" -> applySketchSpace(doc, words, byName)
            // A **section input** (OP-17): the working plane's own section, addressed by index. The index is
            // the whole of the choice (OP-1/OP-18) — taken verbatim, never re-scored, so a curve that has
            // moved comes back as *that* curve and one the plane no longer cuts comes back invalid with a
            // reason (OP-3) instead of quietly meaning its neighbour.
            "sectioninput" -> applySectionInput(doc, words)
            "space" ->
                if (!doc.switchSpace(unquote(words.getOrElse(1) { throw LoadError("space is missing a name") }), record = true)) {
                    throw LoadError("unknown sketch space '${unquote(words[1])}'")
                }
            "weld" -> doc.weld(el(1), el(2))
            "attach" -> doc.attachToCurve(el(1), el(2))
            "weldortho" -> doc.weldOrthoEndpointToPoint(el(1), el(2))
            "attachortho" -> doc.attachOrthoEndpointToCurve(el(1), el(2))
            "unweld" -> doc.unweld(el(1))
            "wire" -> doc.wireParameter(scalar(1), scalar(3))
            // `sign=` is the branch the click chose, restated (OP-1); a format-1 script carries none, and the
            // click scores it once more — this time for good, since the save that follows writes it down
            "intersectnear" -> doc.intersectNear(el(1), el(2), parsePos(words[3]), keyedInts(words, "signs").firstOrNull())
            // `dofs=` is the rider's own parameter, restated (see [restate]); a script written before it was
            // recorded simply has none, and the click position places the rider as it always did
            "pointoncurve" -> doc.pointOnCurve(el(1), parsePos(words[2]), keyedNums(words, "dofs").firstOrNull())
            "relative" -> doc.makeRelative(el(1), el(2), keyedNums(words, "dofs"))
            // `dofs=` is the freed position of a rider taken off its host, restated (see [restate]); every
            // other form of *Make absolute* needs none, and an older script that has none frees the point
            // where the geometry puts it, exactly as the gesture did
            "absolute" -> doc.makeAbsolute(el(1), keyedNums(words, "dofs"))
            "tool" -> applyTool(doc, words, byName, restamp)
            // patterns as orbits (OP-23): `pattern` declares the rule, `orbit` is one gesture riding it.
            // Both are *descriptions* — the ring's members and every copy of every gesture are rebuilt from
            // them, which is why a count change is a rewrite of one literal and nothing else.
            "pattern" -> applyPattern(doc, words, byName, restamp)
            "orbit" -> applyOrbit(doc, words, byName, restamp)
            "macrodef" -> applyMacroDef(doc, words, byName)
            "group" -> applyGroup(doc, words, byName)
            // visibility as a recorded decision (OP-18's reversal): one step per gesture, whole selection
            "hide", "show" -> doc.setElementsVisible(visibilityMembers(words, byName), kind == "show")
            "place" -> applyPlace(doc, words)
            // a user-facing name for an element (OP-7): a decision about the drawing, so the file records it
            "name" ->
                doc.nameElement(el(1), unquote(words.getOrElse(2) { throw LoadError("name is missing a name") }))
                    ?: throw LoadError("element '${words[1]}' cannot carry a name")
            // the material assigned to a solid (appearance Tier 1): all three numbers stated, so a reader
            // never has to know what this build's defaults happen to be
            "material" ->
                doc.setMaterial(
                    el(1),
                    Appearance(
                        color = keyed(words, "color") ?: Appearance.DEFAULT_COLOR,
                        roughness = keyed(words, "rough")?.toDoubleOrNull() ?: Appearance.DEFAULT_ROUGHNESS,
                        metallic = keyed(words, "metal")?.toDoubleOrNull() ?: Appearance.DEFAULT_METALLIC,
                    ),
                ) ?: throw LoadError("element '${words[1]}' cannot carry a material")
            else -> throw LoadError("unknown step '$kind'")
        }
    }

    /**
     * Replay a sketch space (OP-17). Two variants, one step kind:
     *
     * - a **face**: `sketchspace "name" el=e7 piece=2` — which solid, which boundary piece;
     * - a **datum**: `sketchspace "name" line=e3 angle="tilt" offset="lift" part=e5` — which line it hinges
     *   on, which parameter holds its angle, which parameter (if any) moves it along its own normal — the
     *   parallel case a stack of loft sections needs — and which solid a *Cut* there subtracts from
     *   (GitHub #6). Told apart by `line=`, and no version bump goes with either argument: one that never
     *   existed cannot have meant something else, so no stored literal changes meaning (OP-18's doctrine).
     *
     * Either way the step carries the *description* of the frame and never the frame itself, so the plane is
     * re-derived on load and a part edited since comes back with its faces where they now are. The piece
     * index, and the part, are **discrete choices** recorded verbatim (OP-18) rather than re-derived from the
     * click or from the drawing as it stands: a position that lands on one edge today can land on its
     * neighbour after an edit, and which solid a datum cuts is a fact about the moment it was created. The
     * *angle*, by contrast, is state — it lives in the parameter this step names, and that parameter's own
     * `param` step restates it.
     */

    private fun applySketchSpace(
        doc: Document,
        words: List<String>,
        byName: Map<String, Element>,
    ) {
        val name = unquote(words.getOrElse(1) { throw LoadError("sketchspace is missing a name") })
        var solid: Element? = null
        var piece = -1
        var line: Element? = null
        var part: Element? = null
        var angle: ScalarEntry? = null
        var offset: ScalarEntry? = null
        for (w in words.drop(2)) {
            val v = w.substringAfter('=', "")
            when (w.substringBefore('=')) {
                "el" -> solid = byName[v] ?: throw LoadError("unknown element '$v'")
                "piece" -> piece = v.toIntOrNull() ?: throw LoadError("malformed piece index '$v'")
                "line" -> line = byName[v] ?: throw LoadError("unknown element '$v'")
                "part" -> part = byName[v] ?: throw LoadError("unknown element '$v'")
                "angle" -> angle = namedScalar(doc, v)
                // the parallel case (a datum moved along its own normal) — a *new* argument, so no stored
                // literal changes meaning and no version bump goes with it (OP-18's doctrine)
                "offset" -> offset = namedScalar(doc, v)
                else -> throw LoadError("unknown sketchspace argument '${w.substringBefore('=')}'")
            }
        }
        if (line != null) {
            val a = angle ?: throw LoadError("a datum sketch plane is missing 'angle='")
            doc.createDatumSpace(line, a.ref, named = name, part = part, offset = offset?.ref)
                ?: throw LoadError("'${doc.nameOf(line)}' carries no line to place a sketch plane on")
            return
        }
        val on = solid ?: throw LoadError("sketchspace is missing 'el='")
        doc.createFaceSpace(on, piece, named = name)
            ?: throw LoadError("'${words.getOrNull(2)}' has no planar side face #${piece + 1}")
    }

    /** `sectioninput "plane1" edge=2` / `corner=3` — one member of a working plane's section, by index. */
    private fun applySectionInput(
        doc: Document,
        words: List<String>,
    ) {
        val name = unquote(words.getOrElse(1) { throw LoadError("sectioninput is missing a sketch space") })
        val space = doc.spaceNamed(name) ?: throw LoadError("unknown sketch space '$name'")
        var kind: Document.SectionInput? = null
        var index = -1
        for (w in words.drop(2)) {
            val v = w.substringAfter('=', "")
            val key = w.substringBefore('=')
            when (key) {
                "edge", "corner" -> {
                    kind = if (key == "edge") Document.SectionInput.EDGE else Document.SectionInput.CORNER
                    index = v.toIntOrNull() ?: throw LoadError("malformed section index '$v'")
                }
                else -> throw LoadError("unknown sectioninput argument '$key'")
            }
        }
        if (kind == null) throw LoadError("sectioninput needs edge= or corner=")
        doc.sectionInput(space, kind, index) ?: throw LoadError("sketch space '$name' has no section to take an input from")
    }

    /** The panel entry a step names by name — a scalar is addressed by its name in the file (OP-18). */
    private fun namedScalar(
        doc: Document,
        v: String,
    ): ScalarEntry =
        unquote(v).let { n ->
            doc.scalars.firstOrNull { it.name == n } ?: throw LoadError("unknown scalar '$n'")
        }

    /**
     * Replay a **pattern** (OP-23): `pattern "P1" circular ref=e2 centre=e1 count=6`.
     *
     * The rule, and only the rule — the members are rebuilt from it, so `count=` is the one literal a
     * re-stamp rewrites and the loader's element-count check is what vouches for the result. The name is
     * taken from the file rather than regenerated, because the `orbit` steps that follow refer to it.
     */
    private fun applyPattern(
        doc: Document,
        words: List<String>,
        byName: Map<String, Element>,
        restamp: Restamp? = null,
    ) {
        val name = unquote(words.getOrElse(1) { throw LoadError("pattern is missing a name") })
        if (restamp != null && name == restamp.pattern) restamp.resized = true
        val kind =
            PatternKind.entries.firstOrNull { it.name.lowercase() == words.getOrNull(2) }
                ?: throw LoadError("unknown pattern kind '${words.getOrNull(2)}'")
        var reference: Element? = null
        var about: Element? = null
        var count = 0
        for (w in words.drop(3)) {
            val v = w.substringAfter('=', "")
            when (w.substringBefore('=')) {
                "ref" -> reference = byName[v] ?: throw LoadError("unknown element '$v'")
                "centre", "to" -> about = byName[v] ?: throw LoadError("unknown element '$v'")
                "count" -> count = v.toIntOrNull() ?: throw LoadError("malformed count '$v'")
                else -> throw LoadError("unknown pattern argument '${w.substringBefore('=')}'")
            }
        }
        val ref = (reference ?: throw LoadError("pattern is missing 'ref='")).ref as? PointRef
        val ab = (about ?: throw LoadError("pattern is missing its centre or step vector")).ref as? PointRef
        if (ref == null || ab == null) throw LoadError("a pattern is built on two points")
        doc.createPattern(kind, ref, ab, count, named = name) ?: throw LoadError("pattern '$name' cannot be built")
    }

    /**
     * Replay one **replicated gesture** (OP-23): `orbit "P1" segment pts=e2@0,e2@1 cells=…`.
     *
     * A pick is either `name@offset` — the member of that element's orbit at that index — or an ordinary
     * element reference, which the pattern's transform must leave alone. `cells=` are the gesture's clicks
     * carried back to the cell of member 0, so where each copy's click lands follows the pattern's *current*
     * shape; `signs=` are the choices its first copy scored, taken verbatim by all of them (OP-1).
     */
    private fun applyOrbit(
        doc: Document,
        words: List<String>,
        byName: Map<String, Element>,
        restamp: Restamp? = null,
    ) {
        val name = unquote(words.getOrElse(1) { throw LoadError("orbit is missing a pattern name") })
        if (restamp != null && name == restamp.pattern) restamp.resized = true
        val pattern = doc.patternNamed(name) ?: throw LoadError("unknown pattern '$name'")
        val tool = doc.toolDef(words.getOrElse(2) { throw LoadError("orbit is missing a tool") }) ?: throw LoadError("unknown tool '${words[2]}'")
        var points = emptyList<Pair<Element, Int?>>()
        var elements = emptyList<Pair<Element, Int?>>()
        var cells = emptyList<Vec2>()
        var scalars = emptyList<ScalarEntry>()
        var signs = emptyList<Int>()
        var count = 0
        var chainsPart = false

        fun specs(v: String): List<Pair<Element, Int?>> =
            v.split(',').filter { it.isNotEmpty() }.map { token ->
                val at = token.indexOf('@')
                val n = if (at < 0) token else token.substring(0, at)
                val el = byName[n] ?: throw LoadError("unknown element '$n'")
                el to if (at < 0) null else (token.substring(at + 1).toIntOrNull() ?: throw LoadError("malformed member index '$token'"))
            }
        for (w in words.drop(3)) {
            val v = w.substringAfter('=', "")
            when (w.substringBefore('=')) {
                "pts" -> points = specs(v)
                "els" -> elements = specs(v)
                "cells" -> cells = v.split(';').filter { it.isNotEmpty() }.map { parsePos(it) }
                "scalar" ->
                    scalars =
                        scalarNames(v).map { n ->
                            doc.scalars.firstOrNull { it.name == n } ?: throw LoadError("unknown scalar '$n'")
                        }
                "signs" -> signs = v.split(';').filter { it.isNotEmpty() }.map { it.toIntOrNull() ?: throw LoadError("malformed sign '$it'") }
                "count" -> count = v.toIntOrNull() ?: throw LoadError("malformed count '$v'")
                // the chained base of a face-part tool (OP-17/OP-23): resolved per copy, in index order, so
                // copy k subtracts from what copy k-1 left. Written as the rule, never as k names.
                "part" -> {
                    if (v != "tip") throw LoadError("unknown part reference '$v'")
                    chainsPart = true
                }
                else -> throw LoadError("unknown orbit argument '${w.substringBefore('=')}'")
            }
        }
        doc.replayOrbit(pattern, tool, points, elements, cells, scalars, signs, count, chainsPart)
            ?: throw LoadError("pattern '$name' cannot replicate '${tool.id}'")
    }

    /**
     * Replay an interval feature onto the thick path whose footprint is [footprint] (OP-21). The step
     * carries the *description* — which leg, how far along, how wide, sill and head — so nothing about
     * it has to be re-derived from a click, and the values a user has since typed come straight back.
     */
    private fun applyInterval(
        doc: Document,
        words: List<String>,
        footprint: Element,
    ) {
        val tp = doc.thickNetworkOf(footprint) ?: throw LoadError("'${words[1]}' is not a thick path's footprint")
        val keyed = HashMap<String, String>()
        for (w in words.drop(2)) {
            val key = w.substringBefore('=')
            if (!w.contains('=')) throw LoadError("malformed interval argument '$w'")
            keyed[key] = w.substringAfter('=')
        }

        fun need(key: String): String = keyed[key] ?: throw LoadError("interval is missing '$key='")
        val widthName = unquote(need("width"))
        val width = doc.scalars.firstOrNull { it.name == widthName } ?: throw LoadError("unknown scalar '$widthName'")
        val leg = need("leg").toIntOrNull() ?: throw LoadError("malformed leg index '${need("leg")}'")
        doc.addInterval(tp, leg, quantity(need("pos")), width.ref, quantity(need("sill")), quantity(need("head")))
            ?: throw LoadError("leg $leg is not a leg of '${words[1]}'")
    }

    /**
     * Replay a **macro definition** (OP-6): `macrodef "name" els=… pts=… scalar=…`.
     *
     * The step declares nothing but a designation over what earlier steps built — which elements are the
     * definition, which of their free points are the input slots (the first is the anchor) and which
     * panel scalars are the scalar inputs. So the custom **tool** is part of the file: replaying this
     * re-registers it, and the instance steps that follow (ordinary `tool macro:name …` steps) find it
     * because a definition is always recorded before any instance of it.
     */
    private fun applyMacroDef(
        doc: Document,
        words: List<String>,
        byName: Map<String, Element>,
    ) {
        val name = unquote(words.getOrElse(1) { throw LoadError("macrodef is missing a name") })
        var members = emptyList<Element>()
        var points = emptyList<Element>()
        var scalars = emptyList<ScalarEntry>()

        fun els(v: String) = v.split(',').filter { it.isNotEmpty() }.map { byName[it] ?: throw LoadError("unknown element '$it'") }
        for (w in words.drop(2)) {
            val v = w.substringAfter('=', "")
            when (w.substringBefore('=')) {
                "els" -> members = els(v)
                "pts" -> points = els(v)
                "scalar" ->
                    scalars =
                        scalarNames(v).map { n ->
                            doc.scalars.firstOrNull { it.name == n } ?: throw LoadError("unknown scalar '$n'")
                        }
                else -> throw LoadError("unknown macrodef argument '${w.substringBefore('=')}'")
            }
        }
        doc.defineMacro(name, members, points, scalars)
            ?: throw LoadError("macro '$name' has no elements, or an input point that is not free")
    }

    /**
     * The elements a `hide` / `show` step names. A load error when one is unknown, like every other
     * element reference: a visibility step that silently applied to nothing would reopen the drawing
     * looking different from the file, which is exactly what recording it was for.
     */
    private fun visibilityMembers(
        words: List<String>,
        byName: Map<String, Element>,
    ): List<Element> {
        val arg = words.drop(1).firstOrNull { it.startsWith("els=") } ?: throw LoadError("${words[0]} is missing 'els='")
        return arg.removePrefix("els=").split(',').filter { it.isNotEmpty() }
            .map { byName[it] ?: throw LoadError("unknown element '$it'") }
    }

    /**
     * Replay a flat group (OP-16): membership by element name, so nothing about the group depends on
     * runtime ids and a member the script no longer declares is simply not in it.
     */
    private fun applyGroup(
        doc: Document,
        words: List<String>,
        byName: Map<String, Element>,
    ) {
        val arg = words.drop(2).firstOrNull { it.startsWith("els=") } ?: throw LoadError("group is missing 'els='")
        val members =
            arg.removePrefix("els=").split(',').filter { it.isNotEmpty() }
                .map { byName[it] ?: throw LoadError("unknown element '$it'") }
        doc.createGroup(unquote(words.getOrElse(1) { throw LoadError("group is missing a name") }), members)
            ?: throw LoadError("group '${unquote(words[1])}' has no members, or one of them is already grouped")
    }

    /**
     * Replay a placement (OP-16 step 2): the group's frame is restored at the recorded origin and angle,
     * and the retrofit re-runs over the members' free points — which the earlier steps have just put back
     * at their **world** positions, so the locals come out the same and the geometry is unchanged.
     */
    private fun applyPlace(
        doc: Document,
        words: List<String>,
    ) {
        val name = unquote(words.getOrElse(1) { throw LoadError("place is missing a group name") })
        val g = doc.groups.firstOrNull { it.name == name } ?: throw LoadError("unknown group '$name'")
        var at = Vec2(0.0, 0.0)
        var angle = 0.0
        for (w in words.drop(2)) {
            val v = w.substringAfter('=', "")
            when (w.substringBefore('=')) {
                "at" -> at = parsePos(v)
                "angle" -> angle = quantity(v).base
                else -> throw LoadError("unknown place argument '${w.substringBefore('=')}'")
            }
        }
        doc.placeGroup(g, at, angle) ?: throw LoadError("group '$name' cannot be placed")
    }

    /** A thick path's justification, defaulting to centred for a script written before it was recorded. */
    private fun justification(word: String?): Justification =
        when (word) {
            null -> Justification.CENTER
            else ->
                Justification.entries.firstOrNull { it.name.lowercase() == word }
                    ?: throw LoadError("unknown justification '$word'")
        }

    private fun currentPath(doc: Document): OrthoPath =
        doc.currentOrthoPath ?: doc.orthoPaths.lastOrNull() ?: throw LoadError("no path is being drawn")

    /**
     * Replay a tool application through the very same [ToolDef.build] the click ran, so every tool —
     * present and future — round-trips without a per-tool case here.
     */
    private fun applyTool(
        doc: Document,
        words: List<String>,
        byName: Map<String, Element>,
        restamp: Restamp? = null,
    ) {
        // the document's registry, not the static one: a user-defined macro is a tool too (OP-6), and its
        // `macrodef` step has already been replayed by the time any instance of it is
        val tool = doc.toolDef(words[1]) ?: throw LoadError("unknown tool '${words[1]}'")
        var points = emptyList<PointRef>()
        var elements = emptyList<Element>()
        var clicks = emptyList<Vec2>()
        var dofs = emptyList<Quantity>()
        var signs = emptyList<Int>()
        var scalars = emptyList<ScalarEntry>()
        var count = 0
        // **A traced boundary is re-followed when a re-stamp changes how many pieces it has** (OP-23).
        // The tracer's follow is edit-time bookkeeping (OP-14/OP-18): the file keeps the whole ordered
        // boundary and a *load* discovers nothing, but a re-stamp is an edit, and re-running the same follow
        // is what lets the outline of a re-counted ring come back closed instead of coming back short.
        var retraced: Pair<List<Element>, List<Vec2>>? = null
        if (restamp != null && tool.id == Tools.OUTLINE) {
            val tokens = words.drop(2).firstOrNull { it.startsWith("els=") }?.removePrefix("els=")?.split(',')?.filter { it.isNotEmpty() }.orEmpty()
            val resolved = tokens.map { byName[it] }
            if (resolved.filterNotNull().any { doc.patternOf(it) != null }) {
                val a = resolved.getOrNull(0) ?: throw DropStep("the piece it started from is gone")
                val b = resolved.getOrNull(1) ?: throw DropStep("the piece it turned into is gone")
                retraced =
                    doc.followedLoop(a, b)
                        ?: throw DropStep("the pattern's boundary no longer closes by itself — trace it again (two clicks)")
                // the loop is this step's *last* creation, and the joints before it are its own scaffolding
                restamp.resized = true
                restamp.alignFromEnd = true
            }
        }
        for (w in words.drop(2)) {
            val key = w.substringBefore('=')
            val v = w.substringAfter('=', "")
            when (key) {
                "pts" ->
                    points =
                        v.split(',').filter { it.isNotEmpty() }.map {
                            @Suppress("UNCHECKED_CAST")
                            (byName[it] ?: throw LoadError("unknown element '$it'")).ref as PointRef
                        }
                "els" -> elements = retraced?.first ?: v.split(',').filter { it.isNotEmpty() }.map { byName[it] ?: throw LoadError("unknown element '$it'") }
                "clicks" -> clicks = retraced?.second ?: v.split(';').filter { it.isNotEmpty() }.map { parsePos(it) }
                "dofs" -> dofs = v.split(';').filter { it.isNotEmpty() }.map { quantity(it) }
                // the discrete choices this tool scored from its clicks, replayed verbatim (OP-1, OP-18)
                "signs" -> signs = v.split(';').filter { it.isNotEmpty() }.map { it.toIntOrNull() ?: throw LoadError("malformed sign '$it'") }
                // an ordered list, so a two-scalar tool needs no key of its own; names are quoted, hence
                // split on the quotes rather than on the comma (a name may contain one)
                "scalar" ->
                    scalars =
                        scalarNames(v).map { name ->
                            doc.scalars.firstOrNull { it.name == name } ?: throw LoadError("unknown scalar '$name'")
                        }
                // structural (OP-18): how many copies/vertices the tool built, replayed verbatim
                "count" -> count = v.toIntOrNull() ?: throw LoadError("malformed count '$v'")
                else -> throw LoadError("unknown tool argument '$key'")
            }
        }
        val at = clicks.lastOrNull() ?: Vec2(0.0, 0.0)
        val picks = Picks(points, elements, at, clicks, dofs, count, signs)
        // replay through the same recorder the click used, so the reloaded document can be saved again
        doc.recordingTool(tool.id, picks, scalars) { tool.build(doc, picks, scalars.map { it.ref }) }
    }

    /** The scalar names in a `scalar=` argument: `"a","b"` -> [a, b]. Names are quoted, so the quotes and
     *  not the comma delimit them — a name may contain a comma. A bare word is accepted defensively. */
    private fun scalarNames(v: String): List<String> =
        if (v.startsWith("\"")) Regex("\"([^\"]*)\"").findAll(v).map { it.groupValues[1] }.toList() else listOf(v)

    private fun unquote(s: String) = s.removeSurrounding("\"")

    /** The `key=a;b` numbers of a step, empty when it carries none (an older script, a tool with no DOF). */
    private fun keyedNums(
        words: List<String>,
        key: String,
    ): List<Quantity> =
        words.firstOrNull { it.startsWith("$key=") }
            ?.removePrefix("$key=")?.split(';')?.filter { it.isNotEmpty() }?.map { quantity(it) }
            ?: emptyList()

    /** The bare `key=value` word of a step, or null when it carries none. */
    private fun keyed(
        words: List<String>,
        key: String,
    ): String? = words.firstOrNull { it.startsWith("$key=") }?.removePrefix("$key=")

    /** The `key=1;-1` integers of a step — a discrete choice, so plain integers and no unit. */
    private fun keyedInts(
        words: List<String>,
        key: String,
    ): List<Int> =
        words.firstOrNull { it.startsWith("$key=") }
            ?.removePrefix("$key=")?.split(';')?.filter { it.isNotEmpty() }
            ?.map { it.toIntOrNull() ?: throw LoadError("malformed sign '$it'") }
            ?: emptyList()

    private fun parsePos(s: String): Vec2 {
        val parts = s.split(',')
        if (parts.size != 2) throw LoadError("malformed position '$s'")
        return Vec2(parseNum(parts[0]), parseNum(parts[1]))
    }

    private fun parseNum(s: String): Double =
        s.removeSuffix("mm").removeSuffix("deg").toDoubleOrNull() ?: throw LoadError("malformed number '$s'")

    private fun quantity(s: String): Quantity =
        when {
            s.endsWith("deg") -> Quantity.deg(parseNum(s))
            s.endsWith("mm") -> Quantity.mm(parseNum(s))
            else -> Quantity.number(parseNum(s))
        }
}
