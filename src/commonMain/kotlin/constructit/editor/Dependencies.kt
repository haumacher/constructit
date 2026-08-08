package constructit.editor

import constructit.core.Node

/**
 * One input of an element: the [element] it comes from, and the **role** it plays in the step that built
 * the consumer ("centre", "radius point"), or null when the step has no word for it.
 */
class InputRole(val element: Element, val role: String?)

/**
 * **What a selected element is built from, and what is built on it** — read straight off the DAG (OP-5),
 * because the graph is the only thing that knows.
 *
 * The user's question this answers is "*which* point is this circle's centre?": with a construction of any
 * size the inputs of a piece are not visible in the picture, and clicking through the journal to find out is
 * not an answer. So a selection publishes two more sets, drawn in styles of their own by [SceneRenderer] and
 * listed by name in the inspector.
 *
 * ### The depth rule — nearest element-bearing ancestors, honestly stated
 *
 * "Direct inputs" is not usable as it stands: most element nodes consume *op* nodes nothing displays (a
 * fillet's arc is built over derived tangency and centre nodes, a wall's region over a whole offset network),
 * so a literally-direct rule would report nothing for exactly the elements a user asks about. The whole cone
 * is not usable either — it is the entire drawing upstream, which highlights everything and says nothing.
 *
 * The rule is therefore: **walk up from the selection's node and stop at the first node an element displays.**
 * A node no element displays is *transparent* (the walk carries on through it); a node some element displays
 * is a barrier and that element is an input. So a circle reports its centre point and its radius point, a
 * fillet reports its two legs, an extrusion reports the area it was raised from — and nothing reports its
 * grandparents, because those are the parents of something the user can select and ask about in turn.
 *
 * [dependentsOf] is the **exact inverse** of that relation rather than a second walk with its own rule: `b`
 * is a dependent of `a` exactly when `a` is one of `b`'s inputs. That is what keeps the two arrows in the
 * inspector consistent — following "used by" and then "built from" always comes back.
 *
 * Scalars are deliberately not in either set: a parameter is not an element, it has its own panel row, and
 * the wiring dropdown there already says what it drives.
 */
object Dependencies {
    /**
     * The elements [el] is built from, in graph order, each with its role in the step that created [el]
     * where that step has a word for it (see [roles]).
     */
    fun inputsOf(
        doc: Document,
        el: Element,
    ): List<InputRole> {
        val role = roles(doc, el)
        return nearestAncestors(doc, el).map { InputRole(it, role[it.id]) }
    }

    /** The elements built (directly, by the rule above) on [el]. */
    fun dependentsOf(
        doc: Document,
        el: Element,
    ): List<Element> = doc.elements.filter { other -> other !== el && nearestAncestors(doc, other).any { it === el } }

    /**
     * The barrier walk itself: [el]'s inputs, skipping nodes nothing displays.
     *
     * An element displaying the *same* node as [el] (a welded alias, a re-pointed view) is not an input of
     * itself, so it is passed through rather than reported.
     */
    private fun nearestAncestors(
        doc: Document,
        el: Element,
    ): List<Element> {
        val owner = HashMap<String, Element>()
        for (e in doc.elements) owner.getOrPut(e.ref.node.id) { e }
        val out = ArrayList<Element>()
        val seen = HashSet<String>()
        val self = el.ref.node

        fun walk(n: Node) {
            for (input in n.inputs) {
                if (!seen.add(input.id)) continue
                val e = owner[input.id]
                if (e != null && e !== el && input !== self) {
                    if (out.none { it === e }) out.add(e)
                } else {
                    walk(input)
                }
            }
        }
        walk(self)
        return out
    }

    /**
     * What each pick of [el]'s creating step was *for*, by element id — the step records its picks, and a
     * tool declares what its slots mean ([ToolDef.slotNames]), so the words come from the construction
     * rather than from a guess about the picture.
     *
     * Only a `tool` step can say this: every other step kind either has one obvious operand or names its
     * arguments already. The mapping from slot to pick is the tool's own declaration, walked exactly as
     * [Document.replicationOf] walks it — points fill the point slots in order, everything else the rest.
     */
    private fun roles(
        doc: Document,
        el: Element,
    ): Map<String, String> {
        val step = doc.creatingStep(el) ?: return emptyMap()
        if (step.kind != "tool") return emptyMap()
        val toolId = (step.args.firstOrNull() as? Arg.Text)?.s ?: return emptyMap()
        val tool = doc.toolDef(toolId) ?: return emptyMap()
        val points = keyed(step, "pts")
        val elements = keyed(step, "els")
        val out = HashMap<String, String>()
        var pi = 0
        // a face-part tool's first element is the part the editor resolved, not a click (OP-17)
        var ei = if (tool.facePartOperand) 1 else 0
        if (tool.facePartOperand) elements.firstOrNull()?.let { out.getOrPut(it.id) { "part" } }
        for ((i, slot) in tool.slots.withIndex()) {
            val pick =
                when (slot) {
                    SlotKind.PLACE_POINT, SlotKind.POINT -> points.getOrNull(pi++)
                    // an optional slot spends a point only when the step recorded one (see
                    // [SlotKind.OPTIONAL_POINT]) — otherwise it names nothing and the slots behind it keep
                    // their own words
                    SlotKind.OPTIONAL_POINT -> points.getOrNull(pi)?.also { pi++ }
                    SlotKind.SIDE -> null
                    else -> elements.getOrNull(ei++)
                }
            if (pick != null) out.getOrPut(pick.id) { tool.roleOf(i) }
        }
        // …and an element the step built **over** a pick carries that pick's own word. *Extrude to point*
        // raises a height point over the apex the user clicked (OP-25), and that height point *is* the apex —
        // so the row must say "apex" rather than fall silent because no click landed on the element itself.
        // Stated once, over every tool: a step that builds an intermediate element over one of its picks has
        // no second word for it, and inventing one per tool is exactly the second table this map avoids.
        val fromPicks = out.toMap()
        for (made in step.creates) {
            if (made === el || made.id in out) continue
            nearestAncestors(doc, made).firstNotNullOfOrNull { fromPicks[it.id] }?.let { out[made.id] = it }
        }
        return out
    }

    private fun keyed(
        step: Step,
        key: String,
    ): List<Element> =
        step.args.filterIsInstance<Arg.Keyed>().firstOrNull { it.key == key }
            ?.let { (it.value as? Arg.Els)?.els } ?: emptyList()
}
