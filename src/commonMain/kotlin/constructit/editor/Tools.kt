package constructit.editor

import constructit.dsl.PointRef
import constructit.dsl.ScalarRef
import constructit.geom.Axis3
import constructit.geom.BoolOp
import constructit.geom.CarryMode
import constructit.geom.Continuity
import constructit.geom.Handedness
import constructit.geom.Justification
import constructit.geom.Vec2
import constructit.units.Dimension
import constructit.units.Quantity

/**
 * What the next click of a tool must supply. SIDE just captures a click position (creates nothing).
 *
 * **The point slots split by *why* the slot names a point, and that split is the whole law** (session 50,
 * the user's rule: *"every tool that requires points as inputs should either select an existing point or
 * create a new free point"*). A slot names a point either because the result is **built from** it — a
 * helix's centre and start point, a curve's waypoints, a dimension's two ends, the anchor a point is made
 * to follow — or because the tool **changes the point it names**: Join welds one point onto another, Make
 * absolute and Unlink give a following point its coordinates back. An *input* slot places a new free point
 * where the click hit nothing, exactly as [POINT] does; a *subject* slot has nothing to place and says so.
 * Which of the two a slot is, is the slot kind and nothing else, so no tool carries a case for it:
 * [PLACE_POINT], [POINT], [INPUT_POINT] and [POINT3] place ([Tools.placesPoint]), [EXISTING_POINT] and
 * [ON_CIRCLE_POINT] do not ([Tools.needsExistingPoint]).
 *
 * AREA is the 2D→3D seam's slot (OP-17): it takes anything that *bounds an area* — a traced `Outline`
 * (one loop), a thick path's footprint (a region with holes), a **closed curve** (a circle), or a
 * **closed chain of curves one step built** (a rectangle, a rounded rectangle, a polygon). One slot for
 * all of them, because the difference is a coercion the document performs (`Document.regionOf`), not a
 * different pick — see `Document.boundaryPiecesOf`.
 *
 * SOLID is the boolean slot (OP-22). A solid is pickable in the 2D canvas **two ways, and both are
 * drawings the canvas already makes**: by its footprint hint, in the space its sketch was drawn in
 * (OP-17), and by its **section**, on a working plane that cuts it (GitHub #9's enumeration, the fifth
 * reader — `Document.sectionSolidNear`). The footprint is tried first, so the part a face space stands
 * for is still the tip it always was; the section answers where the body has no footprint here at all,
 * which is what makes a column sketched in the plan reachable from an upright datum. Neither is new
 * picking machinery — one filter and one fallback, which is what a slot kind is.
 */
enum class SlotKind {
    PLACE_POINT,
    POINT,

    /**
     * A point the result is **built from**, handed to the tool as its [Element] rather than as a `PointRef`
     * — a dimension's two ends, the anchor a relative point is made to follow.
     *
     * The twin of [POINT] and it behaves like one at the gesture: clicking an existing point **shares its
     * node**, and an empty click **states a new free point** there through the same snap-aware route, so a
     * click on a curve makes a rider and a click on a section corner materializes it (OP-17). What is
     * different is only what the tool receives — an element, because these tools name their operand (a
     * dimension annotates *that point*, and `Picks.elements` is what a recorded step writes).
     */
    INPUT_POINT,

    /**
     * A point the tool **changes**, and it must therefore already stand in the drawing: Join's two points,
     * Make absolute, Unlink, Make relative's *subject*, a space origin's anchor corner.
     *
     * The name is the promise — this slot never places one, because there is nothing to place. A point
     * created by the very gesture that is to weld it, free it, or measure a frame from it would be a
     * degree of freedom added and removed in one click, which is not the operation the user asked for. So a
     * miss says what it needs, in the tool's own word for it, and leaves the drawing alone.
     */
    EXISTING_POINT,

    /**
     * An **optional** point pick (OP-26, the anchored sweep): an existing point of the drawing, shared by
     * node exactly as [POINT] shares one — or *nothing at all*.
     *
     * The one slot kind that may be left unfilled, and the whole of the mechanism is how it is skipped: **a
     * click that hits no point here is offered to the slot behind it**, and the optional slot is spent only
     * when that one takes it (`Editor.runToolClick`). So the gesture without the option is exactly the
     * gesture that existed before — no Enter, no extra click, not one keystroke more — and the gesture with
     * it is one further click, in the place a click already goes. A click that lands on nothing at all
     * spends nothing and says so, which is what makes the skip a reading of the click rather than a guess.
     *
     * Two rules follow, and both are declarations rather than checks. It **never places a point**
     * ([Tools.placesPointElement] excludes it): a placed point is indistinguishable from a miss, so an
     * empty click could not mean "skip" any more. And it must **never be a tool's last slot**, for the same
     * reason a defaulted scalar must not stand in front of a required one ([ToolDef.requiredScalars]):
     * there would be no slot behind it for the skipping click to fill.
     */
    OPTIONAL_POINT,
    CURVE,

    /** Anything carrying an infinite line: a line, a segment or a ray (`Document.carrierLine`). */
    LINE,

    /**
     * Anything carrying a whole circle: a circle **or an arc** (`Document.carrierCircle`).
     *
     * The exact twin of [LINE], and it accepts an arc for the same reason [LINE] accepts a segment — every
     * circle op is about the carrier. The result may land off the arc's swept range, which is honest and is
     * said in the help of the tools that can do it, exactly as an intersection on a segment's carrier may
     * land beyond its ends.
     */
    CIRCLE,

    /**
     * Anything carrying a whole **ellipse**: an ellipse or an elliptic arc (`Document.carrierEllipse`) —
     * the third member of the [LINE]/[CIRCLE] family (OP-24), and it accepts an arc for the same reason
     * they do: every conic op is about the carrier.
     */
    CONIC,

    /** Anything with a **centre**: a circle, an arc, an ellipse or an elliptic arc. */
    CENTERED,

    /**
     * Anything with a **measurable length**: a segment, an arc, an ellipse or an elliptic arc. A slot of
     * its own rather than [CURVE], because an infinite line and a ray have no length to measure.
     */
    MEASURABLE,
    SEGMENT,
    GEOMETRY,
    ON_CIRCLE_POINT,
    SIDE,
    CENTRIC,

    /**
     * Either carrier: a line/segment/ray **or** a circle/arc — a *fillet leg*, whose only requirement is
     * that the leg determine a curve the rounding can be tangent to.
     */
    CARRIER,

    /**
     * Anything whose **defining points** can be materialized: a curve (its endpoints, its centre, a
     * spline's controls), an **area** — whose defining points are its corners (the OP-21 extension's
     * *key points*, which is how a wall footprint's corners become pickable and snappable) — or a **curve in
     * space** (OP-26), whose start and end, plus a coil's centre, are points in space.
     *
     * The one slot that reaches across the 2D/3D partition, and deliberately: [Element.isCurve] still excludes
     * a curve in space, because every *other* curve slot wants a value stated in some plane's coordinates
     * ([PATH3] exists for that reason). What key points ask of their operand is only that it *have* defining
     * points, which is as true of a coil as of an arc — so this slot takes both and `Document.extractPoints`
     * answers in the frame each one lives in.
     */
    EXTRACTABLE,
    AREA,
    SOLID,

    /**
     * A **cutting chain** (OP-22's extension): a drawn chain, **or anything closed** — a circle, a traced
     * outline, a rectangle, a wall footprint.
     *
     * The second half is the slot doing the same job [AREA] does: a closed loop separates its plane exactly
     * as a two-ended chain does, so the through-slot and the through-bore are the ordinary cut rather than
     * features of their own, and the coercion is the document's (`Document.chainOf`) rather than a second
     * pick.
     */
    CHAIN,

    /**
     * A **part of a loft** (OP-17): anything that bounds an area (a section), a point (an apex), or an *open*
     * curve (a guide). One slot for the three, because the loft tool collects them in one repeating gesture and
     * which one a pick is is a question about the element, not about the click — `Document.loftRoleOf` answers
     * it structurally, so a replay classifies exactly as the click did.
     */
    LOFT_PART,

    /**
     * A **point in space** (OP-26): a height point (OP-25) taken as it is, or an ordinary 2D point, which is
     * lifted by a zero height on its own space's plane exactly as a loft's apex is (`Document.loftSolid`).
     *
     * **An empty click states a new point there** ([INPUT_POINT]'s rule, one axis up) — reversing step 3's
     * *"Existing points only — this slot never places one. That is what makes the parenting rule visible in
     * the gesture: a curve is routed through things that are already in the drawing"*. The parenting argument
     * survives untouched, because it was only ever about what a click **on a point** does: that still shares
     * the node, and the curve still follows it. What the old reading got wrong is the other half — the point
     * an empty click places is an ordinary point of the drawing, so the curve is routed through something
     * that *is* in the drawing and everything else can share it in turn. Refusing to place made the helix the
     * one point-taking tool that could not be used on an empty sheet, which is the user's report.
     */
    POINT3,

    /**
     * A **curve in space** (OP-26) — the path a sweep rides, picked in either view exactly as it is drawn
     * there: on its plan projection in the 2D canvas, on the curve itself in the 3D one.
     *
     * A slot of its own rather than [CURVE], and the reason is OP-17's: the 2D curve slots all want a value
     * stated in some plane's coordinates, and a curve in space is not one. The type system already keeps the
     * two apart at the value level (`Path3Value` beside the plane-curve values), and this is that same
     * partition where a click meets it.
     */
    PATH3,

    /**
     * A **drawing to lift into space** (OP-26, step 1's missing source): a bounded drawn curve, a traced
     * outline or an area — what `Document.isLiftable` names, and what the *Lift drawing into space* tool
     * collects.
     *
     * A slot of its own rather than [CURVE], because what it accepts is wider in exactly one direction that
     * matters: an **outline or an area**, which is what a footprint's boundary is and is the everyday thing a
     * route runs round. [PATH3] accepts everything this does *as well as* a curve in space, which is the
     * coercion stated once and used everywhere; this slot is the tool that names the result.
     */
    DRAWN_RUN,

    /**
     * A **curve of the working plane's section** (OP-26, step 6): the pick names the solid the section
     * belongs to, and *where it lands* is the branch choice — one click doing both, which is exactly OP-1's
     * creation UX ("clicking near one intersection sets the `Select` sign to that side") one dimension up.
     *
     * A slot of its own rather than [SOLID], and the reason is GitHub #9's: a working plane draws the section
     * of every solid built before it, and that drawing is the only place those bodies are visible in these
     * coordinates — a solid's own footprint hint belongs to the space it was sketched in. So the pick is
     * resolved against what the plane draws (`Document.sectionSolidNear`), which keeps the standing rule that
     * what is visible is what is pickable.
     */
    SECTION_CURVE,
}

/**
 * One scalar input of a tool: the [name] the status line asks for, and the [dim] a typed number is read
 * in (OP-7 — mm for a length, degrees for an angle, a plain number otherwise).
 *
 * The dimension is what makes **typing** generic (OP-13): with it, digits typed in the status flow can
 * become a parameter for *any* scalar slot, so no tool needs to know that a value can be typed and the
 * mechanism has no per-tool half. It used to be a bare name, and a bare name cannot be turned into a
 * quantity without guessing.
 */
class ScalarSlot(
    val name: String,
    val dim: Dimension = Dimension.LENGTH,
    /**
     * The value this slot **means when nothing was typed or picked** — null for a slot the tool cannot do
     * without.
     *
     * A default is what lets a tool gain a scalar input *without gaining a step*: with every slot defaulted
     * the tool never waits (see [ToolDef.scalarsOptional]), so Midpoint stays one click plus one click and
     * only *also* accepts a factor. The declared number is what the status line names and what [ToolDef.build]
     * does when it is handed no value — for the ratio slot, 0.5, which is exactly `cx.midpoint`.
     */
    val default: Quantity? = null,
    /**
     * Whether the [default] chooses a **construction** rather than merely a value — and therefore cannot
     * become a degree of freedom the step owns (see [ToolDef.ownedSlots]).
     *
     * The distinction is the no-solver stance's own: *Midpoint* with no factor builds `cx.midpoint`, a
     * derived point with **no** freedom at all, while a factor makes it a ratio point that can be dragged
     * off centre — two different constructions, and 0.5 is the name of the first, not a value of the second.
     * A polygon's corner radius is the same sentence about structure (OP-21): 0 builds plain segments and a
     * radius builds OP-23's rounded pattern, so the number decides *how many nodes exist*.
     *
     * Declared, never inferred: what a build does when handed no value is the build's own business, so the
     * table says which kind of default this is rather than the runner guessing from the number.
     */
    val structural: Boolean = false,
    /**
     * Whether this slot accepts **only a value typed for this gesture** — never a parameter left in the
     * panel by an earlier one.
     *
     * [ToolDef.scalarsTypedOnly]'s reason, said per slot instead of per tool, and for the case that tool-wide
     * flag cannot serve: a *Revolve* takes an angle **and** an offset, both angles, so the picks cannot be
     * told apart by dimension — but its angle must stay pickable from the panel, because sharing a parameter
     * node is how this program says "the same angle" (OP-5). Marking the *offset* alone is what keeps a stray
     * angle from a previous gesture out of it: an unstated offset then means zero, which is what it says.
     */
    val typedOnly: Boolean = false,
) {
    /**
     * Whether a *step* can own this slot's value as a freedom when nobody stated one: it has a default to
     * stand at, and that default is a value rather than a choice of construction ([structural]).
     */
    val ownable: Boolean get() = default != null && !structural
}

/**
 * CUSTOM is where a document's **user-defined macros** land (OP-6): a macro *is* a [ToolDef], so the
 * palette needs no second kind of button — only a category whose contents come from the document rather
 * than from [Tools.all]. See [Document.toolDef]: the registry is static plus the open document's macros.
 *
 * PLANES is where every tool that **creates a working plane** lands (GitHub #9, the user's rule: *"all plane
 * creation tools should go to one section in the UI — the section-plane tool is not a solid creation tool"*).
 * *Section* is not among them: it makes 2D geometry from a solid, so it stays with the solids.
 */
enum class ToolCategory { POINTS, CURVES, CONSTRUCT, TRANSFORM, MEASURE, ANNOTATE, RESULT, SOLIDS, PLANES, CUSTOM }

/**
 * Geometry picked so far for the active tool (split by kind), [at] = the last click's world
 * position, and [clicks] = the world position of every click in slot order.
 */
class Picks(
    val points: List<PointRef>,
    val elements: List<Element>,
    val at: Vec2,
    val clicks: List<Vec2>,
    /**
     * State a replay hands back to a tool that owns degrees of freedom of its own — a dimension's offset
     * (OP-13), restated on save (OP-18). Empty for a live click, where the DOF is seeded from [clicks]
     * instead; the clicks keep encoding the *choices* (which side, which sector), which never change.
     */
    val dofs: List<Quantity> = emptyList(),
    /**
     * How many copies/vertices the tool is to build — a **structural** number, not a parameter: it
     * decides how many nodes exist, exactly as an ortho path's vertex count does, so it is recorded in
     * the tool step and re-run rather than edited afterwards (see *Structural count* in DESIGN.md).
     */
    val count: Int = 0,
    /**
     * Discrete choices a replay hands back **verbatim** — a fillet's variant, a chamfer's quadrant (OP-1,
     * OP-18). Empty for a live click, where they are scored from [clicks] once and then restated by the step
     * for every load after; a tool that is given them must not score again, since the corner the clicks were
     * scored against moves with the legs.
     */
    val signs: List<Int> = emptyList(),
    /**
     * What each click **landed on**, in slot order — the snap the editor resolved there, or null where the
     * click found nothing (and for every replayed step, which has no cursor to snap).
     *
     * A tool whose build connects to existing geometry reads it and calls [Document.linkPathEnd], the one
     * helper every joining route already goes through: an ortho-path click, the drag magnet's release, and the
     * rectangle's two corners. Nothing about it is recorded here, because a connection is recorded by *its own*
     * step (`weldortho` / `attachortho`, OP-18) — so replay rebuilds the links without ever re-snapping, which
     * is the same discipline [signs] follows for a discrete choice.
     */
    val landings: List<SnapResult?> = emptyList(),
    /**
     * **Who mapped the pointer onto the plane** when this gesture was made — the 2D canvas's camera (a
     * similarity) or the working plane seen in the 3D view ([PlanePerspective]), and null for a replay, which
     * has no cursor at all.
     *
     * Here for the one thing a click position cannot say on its own: **which winding of a coil** the pointer
     * meant (OP-26). In the plan every winding is drawn on the same image, so a click there can only state a
     * bearing; in the 3D view the pointer's ray meets one particular winding. That is the very split
     * `HitTest.distanceToPath` already makes to *find* the curve, so the build that puts a rider on it asks the
     * same question of the same authority rather than a second one — and a replay needs none of it, because
     * what it hands back is the resolved angle itself ([dofs]).
     */
    val view: PlaneProjection? = null,
)

/**
 * A data-driven tool: geometry [slots] to pick by clicking, [scalars] to take from the panel, a [help]
 * line for the status bar, then a [build] action.
 */
class ToolDef(
    val id: String,
    val label: String,
    val category: ToolCategory,
    val slots: List<SlotKind>,
    /**
     * The scalar inputs this tool consumes, **in order**, named and dimensioned for the status line and
     * for typed entry ("radius", a length). A list rather than a flag: a tool may need two (point from
     * coordinates wants x, then y), and the single-scalar majority is simply a list of one, so there is
     * one rule for collecting them and one rule for recording them (OP-18).
     */
    val scalars: List<ScalarSlot> = emptyList(),
    val help: String = "",
    /**
     * The single key that arms this tool, uppercase. A **tool option like any other**: the palette
     * renders it, `Editor.key` routes it, and nothing else knows it exists — so adding a shortcut stays
     * "edit the table", exactly as adding a tool is. Deliberately given to only a handful of tools: a
     * letter per tool would be a cipher, and the ones that carry a key are the ones a workflow uses over
     * and over (see the click budgets in DESIGN.md).
     */
    val shortcut: Char? = null,
    /**
     * When true the **last** slot repeats: the tool keeps collecting picks until the user finishes
     * (Enter, or clicking the first pick again to close a boundary). Keeps a variable-arity tool like
     * *Outline* data-driven instead of another special case in the controller.
     */
    val repeating: Boolean = false,
    /**
     * The fewest picks a [repeating] tool will build from. Two for a boundary (one curve is not a boundary);
     * **one** for *Thicken*, because a single curve is a perfectly good wall.
     */
    val minPicks: Int = 2,
    /**
     * Whether a [repeating] tool **follows the boundary** from its picks (OP-14's `extendBoundaryPicks`).
     *
     * *Outline* wants it — tracing a closed boundary is the whole gesture. *Thicken* does not: which curves
     * a wall runs over is a choice, and appending the ones that merely happen to continue would build a wall
     * the user did not draw. The connectivity machinery is the same either way; only the auto-append differs.
     */
    val followsBoundary: Boolean = false,
    /**
     * Whether clicking a [repeating] tool's **first pick again** is a *statement* rather than merely "done":
     * the pick is appended before the tool builds, so its pick list ends with the element it began with.
     *
     * Declared by the curve-through-points tools (OP-26) and by nothing else, and the reason is what the
     * click means to each tool. To *Outline* and *Loft* it means "the run is complete" — the boundary already
     * closes because tracing it round is the whole gesture — so appending the first pick would list one curve
     * twice. To a curve it means *"and it comes back here"*, which is a different curve from the open one
     * through the same points: the closure has to reach [build], and appending the pick is how it does so
     * without a flag beside the picks. The recorded step then states the closure by naming that point twice,
     * so replay closes for exactly the reason the gesture did (OP-18) and no new file argument exists.
     */
    val closesOnFirstPick: Boolean = false,
    /**
     * Whether each pick of this tool carries the **wall side** in effect when it was made (the OP-21
     * extension), collected into [Picks.signs] as one `Justification` ordinal per curve.
     *
     * A per-curve side is a *discrete choice scored at creation*, which is exactly what `signs=` already
     * persists for a fillet's variant and an intersection's branch (OP-1/OP-18) — so this needed no new file
     * argument, only a declaration that the tool's picks each have one.
     */
    val sidePerPick: Boolean = false,
    /**
     * Whether this [repeating] tool's **first** pick may be one of its own earlier results, which turns the
     * invocation into an *extension* of that result rather than a new one (GitHub #7, the OP-21 network
     * extension). For *Thicken*: click a wall, then click the curves to add, and the wall's own step is
     * **re-stamped** with the grown carrier set — one edit, one undo (OP-23's precedent).
     *
     * Declared here rather than inferred, exactly as [groupOperand] is: it is a promise about what the first
     * slot accepts and about the fact that committing must not create a second element.
     */
    val extendsResult: Boolean = false,
    /**
     * The smallest sensible [Picks.count] for this tool, or 0 when it needs no count at all — a polygon
     * needs three vertices, an array two instances. Non-zero is what makes the count field apply.
     */
    val minCount: Int = 0,
    /**
     * Whether a **whole group** may fill this tool's [SlotKind.GEOMETRY] slot (OP-16): the slot then holds
     * *every* member and [build] fans over the whole of [Picks.elements] instead of taking `elements[0]`.
     *
     * Declared per tool rather than inferred, for two reasons. A tool must *fan* to accept it, and only the
     * tool knows whether it can — and a tool with a second element slot (Mirror's axis) indexes
     * [Picks.elements] positionally, which a multi-element geometry slot would silently break. So opting in
     * is a promise about [build], made in the table like every other tool property.
     */
    val groupOperand: Boolean = false,
    /**
     * Whether this tool consumes **the part of the active face space** (OP-17's sequential-feature rule).
     * The editor resolves that part's current tip ([Document.facePartTip]) when the tool completes and puts
     * it in [Picks.elements] *before* the clicked ones, so [build] reads `elements[0]` as the part — and the
     * step records it by name, which is what makes replay exact and stops a second feature from forking the
     * model back onto the original base. Declared per tool rather than inferred, exactly as [groupOperand]
     * is: it is a promise about how [build] indexes its picks.
     */
    val facePartOperand: Boolean = false,
    /**
     * Whether [build] **records its own steps** instead of being wrapped in one `tool` step (OP-18).
     *
     * The rectangle needs it: what two clicks produce is a closed ortho path, and a path's degrees of freedom
     * are its corner positions — which the `orthostart`/`orthovertex`/`orthoclose` steps *restate* on every
     * save. A `tool` step restates only the clicks that started it, so every later drag of a corner or a leg
     * would be lost on reload. Recording the steps the ortho tool would have recorded also means the result
     * is not a special kind of path: the file cannot tell the two gestures apart, and needs no new step kind.
     *
     * One checkpoint still covers the whole gesture ([Editor.maybeCompleteTool] takes it), exactly as a break
     * that emits several steps does — one gesture, one undo.
     */
    val recordsSteps: Boolean = false,
    /**
     * Whether this tool's application is **replicated round a pattern** when its picks touch one (OP-23).
     *
     * Declared **true by default**, which is the opposite of every other opt-in here and deliberately so: the
     * rule of OP-23 is that *any* operation whose inputs touch pattern members fans out, and a table where
     * each tool had to remember to say so would be a rule with exceptions instead. So the exceptions are
     * where they belong — a handful of `false`s below, each with its reason. Two of them are structural and
     * enforced by [Document.replicationOf] rather than declared: a [repeating] tool already collects the whole
     * ring in one gesture, and a tool with no [slots] cannot touch a member.
     */
    val replicates: Boolean = true,
    /**
     * What this tool draws **under the cursor while it is being used**, or null for a tool that shows nothing.
     *
     * A picture of the next click, computed from the picks so far, the values in effect and the cursor — one
     * mechanism for every tool, and no controller code per tool: the editor calls this on every hover once
     * the tool is armed and at least the **first slot is filled**, and the renderer draws the result in the
     * preview style the ortho band already uses (see [Previews], and *Live tool previews* in DESIGN.md).
     *
     * Two rules, both structural rather than by convention. It **never touches the graph** — the context it
     * gets holds no `Construction`, so it can only compute with `GeomMath` over evaluated values, which is
     * what makes hovering free of nodes, elements and steps. And it is **honest**: it draws what the click
     * will build, with the typed scalars and defaults in effect, and nothing at all where the click would
     * build nothing.
     *
     * *From the first pick onward*, deliberately: a tool that painted with nothing picked would put geometry
     * under the cursor the moment it was armed, which reads as a click already made. What that costs is
     * named where it applies — Circle (centre, radius) completes on its first click and therefore has no
     * preview, and the rectangle's first corner is unpreviewed for the same reason.
     */
    val preview: ((PreviewContext) -> List<PreviewShape>)? = null,
    /**
     * What each slot **is for**, in slot order — "centre", "radius point", "axis". Optional, and given only
     * where the word is worth more than the slot's kind: the inspector's *built from* row uses it to say
     * `centre e4, radius point e5` instead of listing two anonymous points (see [Dependencies]).
     *
     * A declaration rather than a lookup elsewhere, for the reason every other tool property is one: the
     * table is where a tool says what it takes, and a second table keyed by tool id would drift from it.
     * [roleOf] falls back to the slot kind's own word, so an undeclared tool still says *something*.
     */
    val slotNames: List<String> = emptyList(),
    /**
     * This tool's palette **glyph**: inline SVG markup drawn into a 24×24 box (`stroke="currentColor"`,
     * no fill unless the shape is solid), or null for a tool that keeps a text row.
     *
     * Self-contained by construction — the markup is part of the build (see [Icons]), so there is no icon
     * font, no sprite sheet and nothing to fetch — and it draws the **operation**, not the result: two legs and
     * the arc that rounds them, because that is what distinguishes it from an arc tool at 24 pixels. Coverage
     * is deliberately partial (see DESIGN.md): a glyph nobody can read is worse than the label it replaced,
     * so a tool gets one only when its operation has a picture.
     */
    val icon: String? = null,
    /**
     * Whether this tool's picks **survive a change of sketch space** (OP-17).
     *
     * False for every tool but one, and that is the honest default: 2D coordinates only mean something in one
     * space, so a half-collected pick list normally names geometry the new space cannot address, and dropping it
     * is what keeps a canvas showing one space. A **loft** is the exception by nature — its sections live on
     * different planes, and a tool that could not span them could not build the frustum between two datum
     * planes at all — so it declares that it spans spaces, each pick keeps the space it was made in, and each
     * recorded click stays in *that* space's coordinates (see [Editor.setActiveSpace]).
     */
    val crossSpace: Boolean = false,
    /**
     * Whether this tool's optional scalars must have been **typed for it**, rather than remembered from the
     * panel picks a previous tool left behind ([Editor] keeps a short memory of those, deliberately, so a
     * parameter picked once can drive several gestures).
     *
     * Declared by exactly one tool so far, *Space origin*, and the reason is a rule about meanings rather
     * than a special case: both of its slots are lengths and both are defaulted, so *any* length lying in
     * that memory — the depth typed for the last cut — would fit them and silently move a drawing's origin.
     * Where a tool's optional scalars have no dimension of their own to tell them apart, the honest reading
     * of "nothing was typed" is *nothing*, and wiring the offset to an existing parameter afterwards is one
     * panel click away.
     */
    val scalarsTypedOnly: Boolean = false,
    /**
     * Whether arming this tool with **one element already selected** fills its first slot with that element
     * straight away, so the tool runs on the selection instead of waiting for a click.
     *
     * Declared by exactly one tool, *Unlink*, and the reason is a property of what it operates on rather than
     * a shortcut: a welded alias is **hidden by construction** ([Document.hiddenByConstruction]) — the whole
     * point of a weld is that the pair reads as one dot — so no click can ever reach it, and where three
     * points were joined into one no click could say which of them is meant either. The element tree can:
     * a selection *names* an element. So the gesture is "select the point, then Unlink", with the ordinary
     * click as the fallback for a point that is visible after all.
     *
     * The selection is fed **whatever it is**, without filtering by the slot kind, so a wrong pick reaches
     * [build] and is refused there *by name* — a tool that silently ignored the selection and waited for a
     * click would be a tool that declined without saying so.
     */
    val fromSelection: Boolean = false,
    val build: (Document, Picks, List<ScalarRef>) -> Unit,
) {
    /** What slot [i] is for: this tool's own word ([slotNames]) or the slot kind's generic one. */
    fun roleOf(i: Int): String = slotNames.getOrNull(i) ?: Tools.roleOfKind(slots.getOrNull(i))

    /**
     * How many of this tool's scalars it **cannot do without** — the ones with no [ScalarSlot.default].
     *
     * What the editor waits for, and nothing more: a tool whose slots are all defaulted completes on its last
     * click and [build] then receives *no* scalar refs unless the user typed or picked them, so gaining an
     * optional input costs the existing gesture nothing. A tool that **mixes** the two (a tube's radius, then
     * its roll and its twist) waits for the radius alone.
     *
     * The order matters and is a rule rather than a convention: the picks fill the slots as a **prefix**, so
     * a defaulted slot must never stand in front of one the tool is waiting for.
     */
    val requiredScalars: Int get() = scalars.count { it.default == null }

    /**
     * The scalar slots this tool's **step owns as freedoms** when [picked] of them were typed or picked in
     * order — the defaulted values nobody stated, which the step restates (`dofs=`, OP-18) and the result
     * element's fields let anybody edit for ever (OP-13).
     *
     * Before this, an untyped optional scalar was baked into the build as an anonymous constant, so a coil's
     * turn count was unreachable the moment the gesture ended — a degree of freedom the user could reach by
     * neither mouse nor number, which OP-13 calls a bug in the model.
     *
     * Two rules, both about keeping the positions the build reads by index honest:
     *
     * - the run is **contiguous** from [picked] onward and stops at the first slot that cannot own a value
     *   ([ScalarSlot.ownable]), because the refs are handed to [build] positionally and a hole in the middle
     *   would silently shift every slot behind it;
     * - a tool that **records its own steps** ([recordsSteps]) owns none: what it emits is `sketchspace` or
     *   `orthostart`, not a `tool` step, so there is no `dofs=` on it to restate one — the two tools this
     *   concerns (the datum plane's angle, a polygon's corner radius) are named in DESIGN.md.
     */
    fun ownedSlots(picked: Int): List<Int> {
        if (recordsSteps) return emptyList()
        var end = picked
        while (end < scalars.size && scalars[end].ownable) end++
        return (picked until end).toList()
    }
}

object Tools {
    const val SELECT = "select"
    const val SELECT_HELP =
        "Drag a point to reshape the construction; drag empty space for a selection box; Shift+click to add or remove; " +
            "click a grouped element again to reach it alone; middle-drag or Space+drag to pan; wheel to zoom."

    /** Select is not a [ToolDef] (it is the absence of one), so its key lives beside the id. */
    const val SELECT_KEY = 'S'

    // The three dimensions a scalar slot can have, as named constructors — so the table below reads as
    // "a length called depth" and a slot can never be declared without saying how a typed number is read.
    private fun len(name: String) = ScalarSlot(name)

    private fun ang(name: String) = ScalarSlot(name, Dimension.ANGLE)

    /**
     * An angle slot the tool can do without: [deg] is what it means with nothing typed.
     *
     * [typedOnly] for a slot that cannot be told apart from the one before it by dimension — see
     * [ScalarSlot.typedOnly].
     */
    private fun ang(
        name: String,
        deg: Double,
        typedOnly: Boolean = false,
    ) = ScalarSlot(name, Dimension.ANGLE, Quantity.deg(deg), typedOnly = typedOnly)

    /**
     * An angle slot whose default names **which construction** the tool builds rather than a value it uses
     * — see [ScalarSlot.structural]. A *Revolve* with no angle is a complete revolution, a body with no
     * ends at all; a stated angle is a partial one with two caps. Two different solids, and 360° is the
     * name of the first rather than a value of the second.
     */
    private fun angChoice(
        name: String,
        deg: Double,
    ) = ScalarSlot(name, Dimension.ANGLE, Quantity.deg(deg), structural = true)

    private fun num(name: String) = ScalarSlot(name, Dimension.NONE)

    /** A dimensionless slot the tool can do without — [ScalarSlot.default] names what it then means. */
    private fun num(
        name: String,
        default: Double,
    ) = ScalarSlot(name, Dimension.NONE, Quantity.number(default))

    /**
     * A dimensionless slot whose default names **which construction** the tool builds rather than a value
     * it uses — see [ScalarSlot.structural]. A ratio of 0.5 is `cx.midpoint`, a derived point with no
     * freedom; a stated factor is a draggable ratio point. The two are different geometry, not one geometry
     * at two values.
     */
    private fun choice(
        name: String,
        default: Double,
    ) = ScalarSlot(name, Dimension.NONE, Quantity.number(default), structural = true)

    /** A length slot whose default means *don't* — structural, for the same reason [choice] is. */
    private fun choiceLen(
        name: String,
        mm: Double,
    ) = ScalarSlot(name, Dimension.LENGTH, Quantity.mm(mm), structural = true)

    /** A length slot the tool can do without: [mm] is what it means with nothing typed (0 = "don't"). */
    private fun len(
        name: String,
        mm: Double,
    ) = ScalarSlot(name, Dimension.LENGTH, Quantity.mm(mm))

    // Points
    const val POINT = "point"
    const val MIDPOINT = "midpoint"
    const val INTERSECT = "intersect"
    const val PROJECT = "project"

    /** Project a point defined on another pane onto the active plane (GitHub #14). */
    const val PROJECT_TO_PLANE = "projectplane"
    const val POINT_ON_CIRCLE = "ptoncircle"
    const val POINT_ON_HELIX = "ptonhelix"
    const val POINT_ON_ELLIPSE = "ptonellipse"
    const val POINT_ON_LINE = "ptonline"
    const val POINT_AT_DIST = "ptatdist"
    const val POINT_XY = "ptxy"

    /**
     * A **height point** (OP-25): a base point on the working plane plus a height along its normal — the
     * apex of a pyramid, generalized into a point anyone can build.
     */
    const val HEIGHT_POINT = "heightpoint"
    const val CENTRE = "centre"
    const val KEY_POINTS = "keypoints"
    const val JOIN = "join"

    // OP-4 case (b): re-parameterize a free point onto an anchor, and the conversion back. Two tools rather
    // than one that guesses from what was clicked — a tool's slots say what it takes, and "make relative"
    // takes two points while "make absolute" takes one.
    const val MAKE_RELATIVE = "makerel"
    const val MAKE_ABSOLUTE = "makeabs"

    /**
     * **The inverse of [JOIN]** (GitHub issue #10): the point named leaves the weld and is a free degree of
     * freedom again, where it stands. See [Document.unlink] for what counts as a bond and what is refused.
     */
    const val UNLINK = "unlink"

    // Curves
    const val LINE = "line"
    const val SEGMENT = "segment"
    const val RAY = "ray"
    const val CIRCLE = "circle"
    const val CIRCLE_R = "circleR"
    const val ORTHO_PATH = "orthopath"
    const val BREAK_LEG = "breakleg"

    // The generic model is a thick path with interval features (OP-21); the *tool* names stay the
    // domain words the user expects.
    const val WALL = "wall"
    const val OPENING = "opening"
    const val CIRCLE_3 = "circle3"
    const val ARC_3 = "arc3"
    const val ARC_CS = "arccs"

    /**
     * The conic family (OP-24): an ellipse from three points, an ellipse from two points and a typed
     * semi-axis, and an elliptic arc. Three ids because the *inputs* differ, exactly as `CIRCLE` and
     * `CIRCLE_R` are two ids for one shape.
     */
    const val ELLIPSE = "ellipse"
    const val ELLIPSE_AB = "ellipseab"
    const val ELLIPTIC_ARC = "ellipticarc"
    const val CONCENTRIC = "concentric"
    const val BEZIER = "bezier"

    /**
     * **Curves in space** (OP-26, step 1): a path through points that already stand in space. Two ids
     * because the *pieces* differ — straight runs or an interpolating cubic — exactly as [CIRCLE] and
     * [CIRCLE_R] are two ids for one shape, and because a tool id is what the file records (OP-18), so which
     * of the two a curve is needs no argument of its own.
     */
    const val CURVE3 = "curve3"
    const val CURVE3_SMOOTH = "curve3smooth"

    /**
     * **The lift** (OP-26, step 1's missing source): a curve already drawn, read as the run in space it
     * already is — the outline of a footprint as the route round it.
     *
     * One id and no argument, because there is nothing discrete to record: what is lifted is what was picked,
     * how it closes is its kind's business, and where the run starts is the drawing's own order. Every
     * `PATH3` slot performs the same coercion on a drawn pick without this tool at all
     * (`Document.spaceCurveRef`); the tool exists for the two things a coercion cannot do — give the run a
     * **name** of its own that several sweeps, stations and tubes then share, and chain **several** drawn
     * pieces into one route.
     */
    const val LIFT = "lift"

    /**
     * **The helix** (OP-26, step 3): a coil about the axis standing on the sketch plane at a picked point.
     *
     * Two ids because the **handedness** differs, and this is the same argument [CURVE3] makes one step
     * earlier rather than a new one: chirality is a *discrete* choice, so it is structural (OP-1) and must be
     * persisted rather than re-derived — and a tool id is exactly what the file records (OP-18). So a
     * right-hand spring reloads right-handed with no new file argument, and neither build has to read a sign
     * off a number that a later edit could change.
     *
     * These two state the radius as a **number** and start the coil along the space's own x. That is the
     * spelling that says no phase, and it keeps that convention for ever: the id is what files record, so
     * every drawing already written with it must go on meaning what it meant.
     */
    const val HELIX = "helix"
    const val HELIX_LEFT = "helixleft"

    /**
     * **The helix stated by two points** (OP-26, step 3, extended): a centre and the point the coil **starts**
     * at, which state its radius and its **phase** together.
     *
     * Two more ids for the same reason the first two are two — handedness is structural and a tool id is what
     * the file records (OP-18) — and a *second pair* rather than an argument on the first, for the reason
     * [CIRCLE] and [CIRCLE_R] are two ids for one shape: which inputs a gesture stated is not a value that
     * may drift, and a build that had to guess which reading was meant would be guessing about geometry.
     *
     * What the second point buys is a degree of freedom the drawing could not previously state at all: where
     * the coil begins. Before it, a thread that had to start at a particular bearing could only be had by
     * turning the space it was drawn in — compensation where an anchor belongs (OP-26's rule).
     */
    const val HELIX_PT = "helixpt"
    const val HELIX_PT_LEFT = "helixptleft"

    /**
     * **Combine two views** (OP-26, step 5): a plan and an elevation, and the run in space whose projection
     * into each of them is the curve drawn there.
     *
     * One id, one gesture and **no new editing surface**: both picks are ordinary sketch curves in ordinary
     * spaces, which is the whole claim of the step — routing was done this way on drawing boards, and the
     * two drawings are what a user already knows how to make.
     */
    const val COMBINE_VIEWS = "combineviews"

    /**
     * **Intersection curve** (OP-26, step 6): the curve in space where the working plane you are drawing on
     * meets a solid, as a first-class `Path3`.
     *
     * One click, and it does the two things a branch choice needs at once — it names the body (the section it
     * lands on) and it picks *which* of the curves that cut has, which is OP-1's own creation UX ("clicking
     * near one intersection sets the `Select` sign to that side") one dimension up. The index is then
     * persisted in the step's `signs=` and never scored again.
     */
    const val INTERSECTION_CURVE = "intersectioncurve"

    /**
     * **Connect** (OP-26, step 7): the joining piece between the end of one curve in space and the end of
     * another, derived from the two endpoint tangents plus two tensions.
     *
     * Two ids because the **continuity** differs — tangent-continuous or curvature-continuous — and that is a
     * discrete structural choice, so it is stated by which tool was used and recorded by recording the tool
     * (OP-1/OP-18). The same argument the helix's two handednesses and the smooth curve's second id make, and
     * the reason neither build has to read a mode out of a number that an edit could change.
     *
     * Which **end** of each curve is joined is the other discrete choice, and it cannot be a tool id — it is
     * per pick — so it rides the step's existing `signs=`, scored once from where each click landed.
     */
    const val CONNECT = "connect"
    const val CONNECT_G2 = "connectg2"

    /**
     * **Project onto a face** (OP-26, step 8): a drawing thrown onto a face of a solid along the normal of
     * the space it was drawn in — the engraved line, the trimmed edge, the route that follows a surface.
     *
     * Two ordinary picks, no scalar and no new slot kind, because there is nothing else to state: the
     * direction is the drawing's own space (a space *is* a direction, and datum planes take any angle), and
     * *which* face is scored once from where the drawing lands and then persisted in the step's `signs=`.
     */
    const val PROJECT_ON_FACE = "projectface"

    /**
     * **The sweep** (OP-26, step 2): a profile carried along a curve in space, on the rotation-minimizing
     * moving frame. Two ids because the *profile* differs — a radius typed for a tube, an area clicked for
     * anything else — exactly as [CIRCLE] and [CIRCLE_R] are two ids for one shape.
     */
    const val TUBE = "tube"
    const val SWEEP = "sweep"

    /**
     * The rectangle tool: two diagonally opposite clicks, and what comes out is a **closed ortho path**
     * (GitHub issue #4). Its own id, because [RECTANGLE_V1] still has to mean what it always meant.
     */
    const val RECTANGLE = "rectpath"

    /**
     * The rectangle as it was built until now: four segments over the two clicked points and two derived
     * corners. **Replay only** — never offered in the palette, and kept because a stored step's meaning is
     * frozen (OP-18): every file written before this carries `tool rect`, and the loader checks that the step
     * creates exactly the six elements the script declares.
     */
    const val RECTANGLE_V1 = "rect"
    const val ROUNDED_RECT = "roundrect"
    const val POLYGON = "polygon"

    // Result layer (OP-14)
    const val OUTLINE = "outline"

    /** Thicken an arbitrary connected curve network into a wall (the OP-21 extension). */
    const val THICKEN = "thicken"

    // Solids — the 2D->3D seam (OP-17)
    const val EXTRUDE = "extrude"
    const val REVOLVE = "revolve"

    /**
     * **The ball** (DESIGN.md's session-52 sphere queue, item 3): the half-disc profile and the complete
     * revolve, built as a compound gesture over primitives that already exist (`Document.ball`).
     *
     * Two ids for exactly the reason [CIRCLE] and [CIRCLE_R] are two ids for one shape — which inputs a
     * gesture stated is not a value that may drift, and a tool id is what the file records (OP-18) — and
     * that pairing is the point rather than a coincidence: *a ball is what a circle says one dimension up*,
     * so it is spelled the two ways the circle is spelled, in the same slot order and the same words.
     */
    const val SPHERE_R = "sphereR"
    const val SPHERE = "sphere"

    /**
     * The **loft**: a run of sections on their own sketch planes (OP-17's third feature), and its everyday
     * special case — an area run to a **point**, which is a pyramid or a cone.
     */
    const val LOFT = "loft"
    const val EXTRUDE_TO_POINT = "extrudepoint"

    // ...and back down again: a sketch on a solid's face, and a solid's section as 2D geometry
    const val EXTRUDE_ON_FACE = "extrudeface"
    const val SECTION = "section"

    // a named 2D sketch space on a solid's *side* face (OP-17), and the cut that space makes cheap
    const val SKETCH_ON_FACE = "sketchface"
    const val CUT = "cut"

    /**
     * A **datum** sketch space: any line, any angle (OP-17's datum extension, GitHub #6). *Sketch on face*
     * is its special case (a boundary segment at 90°) and [PLANE_AT_HEIGHT] is its parallel one.
     */
    const val SKETCH_PLANE = "sketchplane"

    /**
     * A sketch space **parallel to the one you are in, at a typed height** (GitHub #9) — no line, no solid,
     * no pick at all. The degenerate case of [SKETCH_PLANE] (0°, an offset) that the hinge form cannot state,
     * and the tool the user asked for: *"to create such plane, no solid selection is necessary"*.
     */
    const val PLANE_AT_HEIGHT = "planeheight"

    /**
     * A **station**: a sketch space standing across a curve in space, a stated distance along it (OP-26,
     * step 4) — a fitting at a place along a run, a mitre normal to the route, a section drawn where the
     * sweep should change, a branch path whose first point lives in the trunk's own frame.
     *
     * One row and not a family: *one* station at a time is the settled cut, and replication along a path is
     * OP-26's own to-be-discussed item. It lands with the plane tools because that is what it makes.
     */
    const val STATION = "station"

    /**
     * **A sketch made from an imported wireframe** (OP-26, step 9): a sketch space on a flat imported run's
     * own plane, with the run traced into it as ordinary points and segments.
     *
     * With the plane tools because a plane is the first thing it makes — and because what it *is* is the
     * station's construction with the number left out: a flat run states its plane completely. It records its
     * own steps for that reason, and does not replicate (a sketch space is organisation, not geometry).
     */
    const val SKETCH_FROM_WIRE = "wiresketch"

    /**
     * **Where a sketch space's origin sits** (OP-17, session 32): anchor it on a corner of the part's
     * section here, plus an in-plane (dx, dy). Generic over spaces that have a plane — a face's and a
     * datum's origin are moved by the same gesture and the same node.
     */
    const val SPACE_ORIGIN = "spaceorigin"

    // Booleans between same-axis prisms (OP-22), and the architectural application of them
    const val UNION = "union"
    const val SUBTRACT = "subtract"

    // not `INTERSECT`: that name is the point-intersection tool's, and a tool id is what the file
    // records (OP-18), so the solid one gets its own word rather than shadowing it
    const val INTERSECT_SOLIDS = "intersectsolids"
    const val CUT_OPENINGS = "cutopenings"

    /**
     * **Cutting with an unbounded chain** (OP-22's extension, step 1): the chain itself, then the two ways
     * to use it.
     *
     * Two ids for the use, and not because the operation differs — it is one node either way — but because
     * *how many halves become elements* is what the user is choosing, and a tool id is what the file records
     * (OP-18). [SPLIT_BY_CHAIN] keeps both; [CUT_BY_CHAIN] keeps the one that was clicked, which is split
     * with one side kept, and the side is a persisted sign (OP-1).
     */
    const val CHAIN = "chain"
    const val CUT_BY_CHAIN = "cutbychain"
    const val SPLIT_BY_CHAIN = "splitbychain"

    /**
     * **The swept cut** (OP-22's extension, step 2): the same two uses, with a **curve in space** picked as
     * the directrix the chain is carried along, and the **carry mode** stated by which row was used.
     *
     * Four ids where two would do arithmetically, and each half of the multiplication is a separate reason.
     * *Cut* against *Split* is what the two rows above already are — how many halves become elements.
     * *Rotating* against *translational* is a **discrete structural choice** (`CarryMode`), and this project
     * states such a choice by the tool that made it — the helix's two handednesses are two rows for exactly
     * the same reason (OP-1/OP-18) — so the file records it by recording the tool, and no replay ever has to
     * work out from the geometry which one was meant.
     *
     * The straight cut is *not* a fifth row: it is these rows with no curve picked, which is the degenerate
     * directrix, and `Chains.sweptTools` hands it back to the straight code.
     */
    const val CUT_ALONG_CURVE = "cutbychainalong"
    const val CUT_ALONG_CURVE_FLAT = "cutbychainalongflat"
    const val SPLIT_ALONG_CURVE = "splitbychainalong"
    const val SPLIT_ALONG_CURVE_FLAT = "splitbychainalongflat"

    /**
     * **Place a solid**: read its own coordinates in the active sketch space's frame, at a picked point,
     * turned by an angle. Generic over solids — an imported reference body and an extruded part place
     * identically — which is why it lives here with the other solid operations and not with the import.
     */
    const val PLACE_SOLID = "placesolid"

    /**
     * **Place a curve in space** — [PLACE_SOLID] one dimension down, and the gesture an imported wireframe's
     * position is stated by. Generic over runs for the same reason: the import is merely its first caller.
     */
    const val PLACE_CURVE = "placecurve"

    // Construct
    const val PERP_BISECTOR = "perpbis"
    const val ANGLE_BISECTOR = "anglebis"
    const val PERPENDICULAR = "perp"
    const val PARALLEL = "parallel"
    const val PARALLEL_AT = "parallelat"
    const val TANGENT = "tangent"
    const val TANGENT_AT = "tangentat"
    const val FILLET = "fillet"
    const val CHAMFER = "chamfer"
    const val OUTER_TANGENTS = "outertan"
    const val INNER_TANGENTS = "innertan"

    /**
     * The circle tangent to three lines — the **LLL** case of Apollonius' problem (see
     * [Document.circleFrom3Tangents]). A curve, so it lives in that category rather than beside the
     * tangent-line constructions: what it makes is a circle.
     */
    const val CIRCLE_LLL = "circle3tan"

    // Transform
    const val MIRROR = "mirror"

    /**
     * A **half turn about a point**, as its own tool rather than as [ROTATE] with 180° typed into it —
     * the capability was reachable all along, the *concept* was not (OP-14). A rotation's angle is a
     * freedom the panel offers for ever; this row has no scalar slot, so the drawing cannot be edited
     * into a 175° near-reflection.
     */
    const val POINT_REFLECT = "pointreflect"
    const val ROTATE = "rotate"
    const val SCALE = "scale"
    const val TRANSLATE_V = "translatev"
    const val ARRAY_LINEAR = "arraylinear"
    const val ARRAY_CIRCULAR = "arraycircular"

    // patterns as orbits (OP-23): not arrays — a pattern is a *rule* later gestures ride, and its members
    // are shared points that adjacent copies are built on
    const val PATTERN_CIRCULAR = "patterncircular"
    const val PATTERN_LINEAR = "patternlinear"

    // Measure
    const val DISTANCE = "mdist"
    const val ANGLE = "mangle"
    const val LENGTH = "mlen"
    const val RADIUS = "mradius"
    const val COORD_X = "mx"
    const val COORD_Y = "my"
    const val ANGLE_LINES = "manglelines"

    // 3D measurements (OP-4 forward): a solid's numbers, free to drive a new 2D construction. The axis of
    // an extent is a *discrete choice*, and the tool id is what stores it — hence three, not one (see
    // [Document.measureSolidExtent]).
    const val VOLUME = "mvolume"
    const val EXTENT_X = "mextentx"
    const val EXTENT_Y = "mextenty"
    const val EXTENT_Z = "mextentz"

    // Annotate (OP-4 + OP-14): a dimension *shows* a measurement, and drives nothing
    const val DIM_LINEAR = "dimlinear"
    const val DIM_RADIAL = "dimradial"
    const val DIM_ANGULAR = "dimangular"

    val all: List<ToolDef> =
        listOf(
            // ----- Points -----
            // `replicates = false`: a point placed *on* a member already is that member, so there is nothing
            // for an orbit to fan (OP-23)
            ToolDef(POINT, "Point", ToolCategory.POINTS, listOf(SlotKind.PLACE_POINT), shortcut = 'P', replicates = false, help = "Click empty space to place a free point.", icon = Icons.POINT) { _, _, _ -> },
            // the factor is a *defaulted* scalar slot (0.5 = the midpoint), so the gesture is unchanged and
            // typing a number first turns the same two clicks into a ratio point (OP-13)
            ToolDef(MIDPOINT, "Midpoint / ratio point", ToolCategory.POINTS, listOf(SlotKind.POINT, SlotKind.POINT), scalars = listOf(choice("factor", 0.5)), help = "Click two points to place their midpoint — or type a factor first (0.3 = three tenths of the way, 1.5 = beyond the second point) and drag it along afterwards.", slotNames = listOf("from", "to"), icon = Icons.MIDPOINT) { d, p, s -> d.midpoint(p.points[0], p.points[1], s.firstOrNull()) },
            ToolDef(INTERSECT, "Intersect", ToolCategory.POINTS, listOf(SlotKind.CURVE, SlotKind.CURVE), help = "Click two curves to add their intersection point(s). Curves count as their carriers, so a segment reaches beyond its ends and an arc round its whole circle — the point may land off the drawn piece.", slotNames = listOf("curve", "curve"), icon = Icons.INTERSECT) { d, p, _ -> d.intersect(p.elements[0], p.elements[1]) },
            ToolDef(PROJECT, "Project to line", ToolCategory.POINTS, listOf(SlotKind.POINT, SlotKind.LINE), help = "Click a point, then a line, for the perpendicular foot.", slotNames = listOf("point", "line"), icon = Icons.PROJECT) { d, p, _ -> d.projectToLine(p.points[0], p.elements[0]) },
            // the rider's position along its host is **state** (dragged, typed, compensated, or re-anchored by a
            // placement), so it rides `dofs=` exactly as the `pointoncurve` step's does — the click stays the
            // *choice* it always was (which curve, which side). See [DocumentFormat.restate].
            ToolDef(POINT_ON_CIRCLE, "Point on circle", ToolCategory.POINTS, listOf(SlotKind.CIRCLE), replicates = false, help = "Click a circle or arc to add a point on it; drag it around the circle in Select mode (on an arc it rides the whole circle).", slotNames = listOf("circle"), icon = Icons.POINT_ON_CIRCLE) { d, p, _ -> d.pointOnCircle(p.elements[0], p.at, p.dofs.firstOrNull()) },
            // the conic twin of *Point on circle* (OP-24): the rider's freedom is the **parametric angle**
            // `t` (x = a·cos t, y = b·sin t in the ellipse's own frame), which is exact — nothing forces
            // arc length to be the parameter. Its `dofs=` rides the same seam every other rider's does.
            ToolDef(POINT_ON_ELLIPSE, "Point on ellipse", ToolCategory.POINTS, listOf(SlotKind.CONIC), replicates = false, help = "Click an ellipse or elliptic arc to add a point on it; drag it round the curve in Select mode. Its position is the parametric angle t, which is exact — the point, the tangent and the normal there are plain trigonometry.", slotNames = listOf("ellipse"), icon = Icons.POINT_ON_ELLIPSE) { d, p, _ -> d.pointOnEllipse(p.elements[0], p.at, p.dofs.firstOrNull()) },
            ToolDef(POINT_ON_LINE, "Point on line", ToolCategory.POINTS, listOf(SlotKind.LINE), replicates = false, help = "Click a line to add a point on it; drag it along the line in Select mode.", slotNames = listOf("line"), icon = Icons.POINT_ON_LINE) { d, p, _ -> d.pointOnLine(p.elements[0], p.at, p.dofs.firstOrNull()) },
            ToolDef(POINT_AT_DIST, "Point at distance", ToolCategory.POINTS, listOf(SlotKind.POINT, SlotKind.LINE), scalars = listOf(len("distance")), help = "Type a distance (or pick a parameter in the panel), click the reference point, then click the line on the side you want.", slotNames = listOf("reference point", "line")) { d, p, s -> d.pointAlongLine(p.elements[0], p.points[0], s[0], p.at) },
            // no slots at all: its inputs are both scalars, so it is complete as soon as the panel has
            // supplied x and y and a click merely says "now"
            ToolDef(POINT_XY, "Point (x, y)", ToolCategory.POINTS, emptyList(), scalars = listOf(len("x"), len("y")), help = "Type x, then y (or pick two parameters in the panel), then click anywhere: the point follows both, so editing either moves it.", icon = Icons.POINT_XY) { d, _, s -> d.pointFromCoordinates(s[0], s[1]) },
            // the height point (OP-25). A POINT slot, so a click reuses a point it lands on and creates one
            // where it does not — the same first half every point-taking tool has — plus one scalar. What it
            // adds to the vocabulary is the one thing the 2D canvas cannot show: the result is grabbed and
            // dragged in the *3D* view, where its height is read off the pointer's ray.
            ToolDef(HEIGHT_POINT, "Height point", ToolCategory.POINTS, listOf(SlotKind.POINT), scalars = listOf(len("height")), help = "Type a height (or pick a parameter in the panel), then click a base point — an existing one is shared, empty space places a new one: the result is that point lifted off the sketch plane, with the height an ordinary parameter. In the 3D view you can grab it and drag the height; the base stays draggable where it was drawn.", slotNames = listOf("base"), icon = Icons.HEIGHT_POINT) { d, p, s -> d.heightPointAt(p.points[0], s[0]) },
            ToolDef(CENTRE, "Centre", ToolCategory.POINTS, listOf(SlotKind.CENTERED), help = "Click a circle, arc, ellipse or elliptic arc to add its centre point.", slotNames = listOf("circle or ellipse"), icon = Icons.CENTRE) { d, p, _ -> d.centerOf(p.elements[0]) },
            // **Projected point** (GitHub #14). Two picks that span spaces (`crossSpace`): the point to project —
            // shared by node, any point kind, drawn on whatever pane it lives on, so switch there to click it —
            // then a click on the pane you want it on, which is what says *which* plane the projection lands in
            // (the result belongs to the space this last click was made in). It records its own step (a `project`
            // step naming the source, OP-18) rather than a `tool` step, and does not replicate.
            ToolDef(PROJECT_TO_PLANE, "Projected point", ToolCategory.POINTS, listOf(SlotKind.POINT3, SlotKind.SIDE), crossSpace = true, recordsSteps = true, replicates = false, help = "Anchor a construction on this pane to a point defined on another. Click the point you want to project — it can live on any pane, so switch the sketch plane to reach it (the picks are kept), and an existing point is shared so the projection follows every edit to it. Then switch to the pane you want it projected onto and click anywhere on it: the point lands at the foot of the perpendicular dropped onto that plane, in the plane's own coordinates — not where you click. Use it as a circle's centre, a coil's axis, a weld target. Make absolute frees it in place if you want to detach it.", slotNames = listOf("point to project", "plane to project onto")) { d, p, _ -> d.projectToPlane(p.elements[0]) },
            // **A point riding a coil** (OP-26, the queue's own design). One PATH3 pick and no number at all,
            // because the angle *is* what the click states — and which winding that angle is on is what the two
            // views answer differently (see the help, and `HitTest.helixAngleAt`).
            ToolDef(POINT_ON_HELIX, "Point on helix", ToolCategory.POINTS, listOf(SlotKind.PATH3), replicates = false, help = "Click a coil to add a point riding it, at an angle measured from where the coil starts, the way it turns. The angle is not modular: 450 deg is the second winding, one pitch above 90 deg, and typing a bigger number walks up the spring. In the plan every winding is drawn on top of the same circle, so a click there can only mean the first winding (0-360 deg) and a drag keeps the winding the point is on; in the 3D view the pointer meets one particular winding, so a click there states the angle straight away and a drag slides the point along the whole coil. Type the angle in the panel to reach any winding from either view. An angle past the end of the coil says so and comes back when you raise the turn count. Left-hand coils count the same way — the angle follows the curve.", slotNames = listOf("coil")) { d, p, _ -> d.pointOnHelix(p.elements[0], p.at, p.view, p.dofs.firstOrNull()) },
            ToolDef(KEY_POINTS, "Key points", ToolCategory.POINTS, listOf(SlotKind.EXTRACTABLE), help = "Click a curve to add its defining points (endpoints, centre) — or a wall footprint / traced area for its corners, which are then snappable and dimensionable like any point. Works on mirrored and derived geometry too. A curve in space gives its start and its end, and a coil its centre as well: those are points in space, drawn where they project in the plan and where they stand in the 3D view, and they follow the curve through every edit.", slotNames = listOf("curve or area"), icon = Icons.KEY_POINTS) { d, p, _ -> d.extractPoints(p.elements[0]) },
            // both slots are *subjects* (see [SlotKind]): a join takes a degree of freedom away from a point
            // that already stands, so neither click may place one — a point made by this very gesture would
            // be a freedom added and removed in one go, which is a drag and not a join
            ToolDef(JOIN, "Join points", ToolCategory.POINTS, listOf(SlotKind.EXISTING_POINT, SlotKind.EXISTING_POINT), replicates = false, help = "Click the point to keep, then a free point to weld onto it (they become one). Both must already exist — this tool joins points, it never places one.", slotNames = listOf("kept point", "welded point"), icon = Icons.JOIN) { d, p, _ -> d.weld(p.elements[1], p.elements[0]) },
            // the offset is the tool's own DOF, restated on save through `dofs=` exactly as a dimension's
            // placement is (OP-13/OP-18), so a dragged or typed distance comes back
            // …and the two halves of the point-slot law in one row: the *subject* is the point being
            // re-parameterized, so it must already stand; the **anchor** is an ordinary input, so an empty
            // click there states the point to follow
            ToolDef(MAKE_RELATIVE, "Make relative", ToolCategory.POINTS, listOf(SlotKind.EXISTING_POINT, SlotKind.INPUT_POINT), replicates = false, help = "Click a free point that already exists, then the point it should follow — an existing one is shared, empty space places a new anchor: it keeps its distance and angle to that anchor, so moving the anchor takes it along. Drag it (or type distance / angle) to change the offset; Make absolute undoes it.", slotNames = listOf("point", "anchor")) { d, p, _ -> d.makeRelative(p.elements[0], p.elements[1], p.dofs) },
            ToolDef(MAKE_ABSOLUTE, "Make absolute", ToolCategory.POINTS, listOf(SlotKind.EXISTING_POINT), replicates = false, help = "Click a point that follows something — relative to an anchor, welded, or riding a curve — to give it its own coordinates again, where it now stands. It changes the point you click, so that point must already exist; nothing is placed.", slotNames = listOf("point")) { d, p, _ -> d.makeAbsolute(p.elements[0], p.dofs) },
            // the inverse of *Join* (GitHub issue #10). `fromSelection`, because a welded alias is hidden by
            // construction and a merged dot names no one point — see [ToolDef.fromSelection].
            ToolDef(UNLINK, "Unlink", ToolCategory.POINTS, listOf(SlotKind.EXISTING_POINT), replicates = false, fromSelection = true, help = "Select a joined point (in the element tree, where a welded point is still listed) and press this: it becomes a free point again, right where it stands. Everything built on it keeps working and simply stops following. Where several points were joined into one, only the selected one leaves. It frees the point you name, so that point must already exist; nothing is placed.", slotNames = listOf("point"), icon = Icons.UNLINK) { d, p, _ -> d.unlink(p.elements[0], p.dofs) },
            // ----- Curves -----
            ToolDef(LINE, "Line", ToolCategory.CURVES, listOf(SlotKind.POINT, SlotKind.POINT), help = "Click two points to draw an infinite line.", preview = Previews::line, slotNames = listOf("through", "through"), icon = Icons.LINE) { d, p, _ -> d.line(p.points[0], p.points[1]) },
            ToolDef(SEGMENT, "Segment", ToolCategory.CURVES, listOf(SlotKind.POINT, SlotKind.POINT), shortcut = 'L', help = "Click two points to draw a segment.", preview = Previews::segment, slotNames = listOf("from", "to"), icon = Icons.SEGMENT) { d, p, _ -> d.segment(p.points[0], p.points[1]) },
            ToolDef(RAY, "Ray", ToolCategory.CURVES, listOf(SlotKind.POINT, SlotKind.POINT), help = "Click the origin, then a second point, to draw a ray.", preview = Previews::ray, slotNames = listOf("origin", "through"), icon = Icons.RAY) { d, p, _ -> d.ray(p.points[0], p.points[1]) },
            ToolDef(ORTHO_PATH, "Ortho path", ToolCategory.CURVES, emptyList(), help = "Click to chain axis-aligned segments (each leg snaps horizontal/vertical, length is a parameter). Esc or double-click to finish.", icon = Icons.ORTHO_PATH) { _, _, _ -> },
            ToolDef(BREAK_LEG, "Break curve", ToolCategory.CURVES, emptyList(), help = "Click a curve to split it where you clicked. On an ortho path's segment this inserts a zero-length corner you can pull into a jog; on a plain segment, an arc or a Bézier it makes two curves that together are the one you clicked, with the joint free to move (drag the split point, or type the Bézier's t).", icon = Icons.BREAK_LEG) { _, _, _ -> },
            ToolDef(WALL, "Wall", ToolCategory.CURVES, emptyList(), scalars = listOf(len("thickness")), shortcut = 'W', help = "Type a thickness (or pick a parameter in the panel), then click to chain an axis-aligned wall centerline; its footprint (mitred corners, end caps) is computed on finish. Esc or double-click to finish.", icon = Icons.WALL) { _, _, _ -> },
            ToolDef(OPENING, "Opening (door/window)", ToolCategory.CURVES, emptyList(), scalars = listOf(len("width")), shortcut = 'D', help = "Type a width (or pick a parameter in the panel), then click on a wall to place a door/window there (position, width, sill and head stay editable; in plan the gap is a drawing convention, the wall itself stays whole).", icon = Icons.OPENING) { _, _, _ -> },
            ToolDef(CIRCLE, "Circle (centre, point)", ToolCategory.CURVES, listOf(SlotKind.POINT, SlotKind.POINT), help = "Click the centre, then a point on the circle.", preview = Previews::circleCentrePoint, slotNames = listOf("centre", "radius point"), icon = Icons.CIRCLE) { d, p, _ -> d.circle(p.points[0], p.points[1]) },
            ToolDef(CIRCLE_R, "Circle (centre, radius)", ToolCategory.CURVES, listOf(SlotKind.POINT), scalars = listOf(len("radius")), shortcut = 'C', help = "Type a radius (or pick a parameter in the panel), then click the centre.", slotNames = listOf("centre"), icon = Icons.CIRCLE_R) { d, p, s -> d.circleCR(p.points[0], s[0]) },
            ToolDef(CIRCLE_3, "Circle (3 points)", ToolCategory.CURVES, listOf(SlotKind.POINT, SlotKind.POINT, SlotKind.POINT), help = "Click three points the circle passes through.", preview = Previews::circle3, slotNames = listOf("through", "through", "through"), icon = Icons.CIRCLE_3) { d, p, _ -> d.circle3(p.points[0], p.points[1], p.points[2]) },
            // The **LLL** Apollonius case (OP-1's stored-choice discipline, the fillet's precedent): three
            // line picks and a click near the circle wanted, because three lines admit four tangent circles —
            // the incircle and the three excircles. The click scores which one *once*; the two bisector
            // branches it resolves to ride the step's `signs=` and are replayed verbatim, so a line that moves
            // past that click later cannot re-decide it. Tangency is by construction, not asserted.
            ToolDef(CIRCLE_LLL, "Circle (3 tangents)", ToolCategory.CURVES, listOf(SlotKind.LINE, SlotKind.LINE, SlotKind.LINE, SlotKind.SIDE), preview = Previews::circle3Tangents, help = "Click three lines (or segments, rays, wall legs), then click near the circle you want: three lines have four tangent circles — the inscribed one and the three outside it.", slotNames = listOf("tangent line", "tangent line", "tangent line", "which circle"), icon = Icons.CIRCLE_LLL) { d, p, _ -> d.circleFrom3Tangents(p.elements[0], p.elements[1], p.elements[2], p.at, p.signs) },
            ToolDef(ARC_3, "Arc (3 points)", ToolCategory.CURVES, listOf(SlotKind.POINT, SlotKind.POINT, SlotKind.POINT), help = "Click start, a point on the arc, then the end.", preview = Previews::arc3, slotNames = listOf("start", "through", "end"), icon = Icons.ARC_3) { d, p, _ -> d.arc3(p.points[0], p.points[1], p.points[2]) },
            ToolDef(ARC_CS, "Arc (centre, ends)", ToolCategory.CURVES, listOf(SlotKind.POINT, SlotKind.POINT, SlotKind.POINT), help = "Click the centre, the start point, then the end (sweeps counter-clockwise).", preview = Previews::arcCentreEnds, slotNames = listOf("centre", "start", "end"), icon = Icons.ARC_CS) { d, p, _ -> d.arcCenterStartEnd(p.points[0], p.points[1], p.points[2]) },
            // ----- conics (OP-24): the drawing half of first-class ellipses. Every input a node — the
            // centre, the axis end (which fixes the orientation *and* the first semi-axis, so binding it to
            // a line's direction turns the ellipse with that line) and the second semi-axis, as a third
            // click or as a typed scalar. Two tools, exactly as Circle has two, so neither build has to
            // guess which reading a gesture meant.
            ToolDef(ELLIPSE, "Ellipse (centre, axis, point)", ToolCategory.CURVES, listOf(SlotKind.POINT, SlotKind.POINT, SlotKind.POINT), preview = Previews::ellipse, help = "Click the centre, then the end of one axis (which sets that semi-axis and the ellipse's orientation), then a point giving the other semi-axis. Clicking existing points shares them, so an ellipse can follow the construction that placed them.", slotNames = listOf("centre", "axis end", "second axis point"), icon = Icons.ELLIPSE) { d, p, _ -> d.ellipse(p.points[0], p.points[1], p.points[2]) },
            ToolDef(ELLIPSE_AB, "Ellipse (centre, axis, semi-axis)", ToolCategory.CURVES, listOf(SlotKind.POINT, SlotKind.POINT), scalars = listOf(len("semi-axis")), preview = Previews::ellipseAB, help = "Type the second semi-axis (or pick a parameter in the panel), then click the centre and the end of the first axis — which sets that semi-axis and the ellipse's orientation.", slotNames = listOf("centre", "axis end"), icon = Icons.ELLIPSE) { d, p, s -> d.ellipseCAB(p.points[0], p.points[1], s[0]) },
            ToolDef(ELLIPTIC_ARC, "Elliptic arc", ToolCategory.CURVES, listOf(SlotKind.POINT, SlotKind.POINT, SlotKind.POINT, SlotKind.POINT), scalars = listOf(len("semi-axis")), preview = Previews::ellipticArc, help = "Type the second semi-axis, then click the centre, the end of the first axis, and the two points the arc runs between — they are projected onto the ellipse, so they need not sit exactly on it. The arc sweeps counter-clockwise in the parameter from the first to the second.", slotNames = listOf("centre", "axis end", "start", "end"), icon = Icons.ELLIPTIC_ARC) { d, p, s -> d.ellipticArc(p.points[0], p.points[1], s[0], p.points[2], p.points[3]) },
            ToolDef(BEZIER, "Bezier curve", ToolCategory.CURVES, listOf(SlotKind.POINT, SlotKind.POINT, SlotKind.POINT, SlotKind.POINT), help = "Click the start, two control points, then the end. Control points may be existing constructed points.", slotNames = listOf("start", "control", "control", "end"), icon = Icons.BEZIER) { d, p, _ -> d.bezierCurve(p.points[0], p.points[1], p.points[2], p.points[3]) },
            // ----- curves in space (OP-26). A repeating pick over points that already stand in space, which
            // is why it needs no new interaction concept at all: height points are draggable in the 3D view
            // already, and clicking one *shares* it. `crossSpace`, for the loft's own reason — the points may
            // have been lifted off different planes, and a tool that could not span them could not route a
            // curve between two storeys. What comes out is drawn in the 3D view and projected into the plan.
            //
            // Two tools, one value: which pieces the curve is made of is stated by which one was used.
            ToolDef(CURVE3, "Curve through points", ToolCategory.CURVES, listOf(SlotKind.POINT3), repeating = true, minPicks = 2, crossSpace = true, closesOnFirstPick = true, help = "Click the points in space the curve runs through — height points, or ordinary points, which lie in the plane they were drawn on; an existing one is shared, empty space places a new one. Press Enter to finish, or click the first point again to close the curve. The points are shared, so dragging one (or retyping its height) moves the curve; switch the sketch plane between clicks and the picks are kept.", slotNames = listOf("point in space")) { d, p, _ -> d.curveThroughPoints(p.elements, smooth = false) },
            ToolDef(CURVE3_SMOOTH, "Smooth curve through points", ToolCategory.CURVES, listOf(SlotKind.POINT3), repeating = true, minPicks = 2, crossSpace = true, closesOnFirstPick = true, help = "The same gesture as Curve through points, with the corners rounded off: an interpolating cubic that passes through every point you click and leaves each one along the line to its neighbours. At the ends it runs off along the first and last chord. Enter finishes; clicking the first point again closes it.", slotNames = listOf("point in space")) { d, p, _ -> d.curveThroughPoints(p.elements, smooth = true) },
            // **The helix** (OP-26 step 3), in two spellings and two handednesses — four rows, and rows are
            // the whole cost of both. `radius` and `pitch` are waited for; `turns` is defaulted, so
            // `requiredScalars` stops the gesture at two numbers and the third is there for the typing
            // without ever standing in the way (the prefix rule — a defaulted slot must never come before one
            // the tool waits for).
            //
            // The **start-point** pair comes first because it is the primary spelling, exactly as *Circle
            // (centre, point)* stands before *Circle (centre, radius)*: the second click states the radius
            // *and* the phase — where the coil begins, which is a real degree of freedom the typed spelling
            // cannot say (it starts along the space's own x). One number fewer to type, and the start point
            // is an ordinary pick, so clicking an existing one shares its node and the coil follows it.
            ToolDef(HELIX_PT, "Helix (centre, start point, right-hand)", ToolCategory.CURVES, listOf(SlotKind.POINT3, SlotKind.POINT3), scalars = listOf(len("pitch"), num("turns", 1.0)), preview = Previews::helixBase, help = "Type a pitch — the rise per turn — and, if you want more than one, a number of turns; then click the point the axis stands on and the point the coil starts at — an existing point is shared, empty space places a new one. Those two clicks state the radius and where the coil begins, so a spring can come off the edge of a hole or the side of a boss and follow it when that moves. The axis is this sketch plane's own normal through the centre, so the coil rises out of the plane you are drawing in and tilts with it. Everything stays live: drag either point, retype a height, or retype either number.", slotNames = listOf("centre", "start point")) { d, p, s -> d.helixThrough(p.elements[0], p.elements[1], s[0], s.getOrNull(1), Handedness.RIGHT) },
            ToolDef(HELIX_PT_LEFT, "Helix (centre, start point, left-hand)", ToolCategory.CURVES, listOf(SlotKind.POINT3, SlotKind.POINT3), scalars = listOf(len("pitch"), num("turns", 1.0)), preview = Previews::helixBase, help = "The same two clicks as Helix (centre, start point, right-hand) — an existing point is shared, empty space places a new one — turning the other way as it rises — a left-hand thread, a left-hand spring. Handedness is which tool you used, so it is what the file records and it never changes by itself; a negative pitch is refused, because a coil that descends while it turns right is this one.", slotNames = listOf("centre", "start point")) { d, p, s -> d.helixThrough(p.elements[0], p.elements[1], s[0], s.getOrNull(1), Handedness.LEFT) },
            ToolDef(HELIX, "Helix (centre, radius, right-hand)", ToolCategory.CURVES, listOf(SlotKind.POINT3), scalars = listOf(len("radius"), len("pitch"), num("turns", 1.0)), help = "Type a radius and a pitch — the rise per turn — and, if you want more than one, a number of turns; then click the point the axis stands on — an existing point is shared, empty space places a new one. The axis is this sketch plane's own normal through that point, so the coil rises out of the plane you are drawing in and tilts with it; the curve starts beside the point along the plane's x direction — this is the spelling that states no starting angle, and Helix (centre, start point) is the one that does. Everything stays live: drag the point, retype its height, or retype any of the three numbers. Sweep a tube along it for a spring.", slotNames = listOf("axis point")) { d, p, s -> d.helixAbout(p.elements[0], s[0], s[1], s.getOrNull(2), Handedness.RIGHT) },
            ToolDef(HELIX_LEFT, "Helix (centre, radius, left-hand)", ToolCategory.CURVES, listOf(SlotKind.POINT3), scalars = listOf(len("radius"), len("pitch"), num("turns", 1.0)), help = "The same gesture as Helix (centre, radius, right-hand) — an existing point is shared, empty space places a new one — turning the other way as it rises — a left-hand thread, a left-hand spring. Handedness is which tool you used, so it is what the file records and it never changes by itself; a negative pitch is refused, because a coil that descends while it turns right is this one.", slotNames = listOf("axis point")) { d, p, s -> d.helixAbout(p.elements[0], s[0], s[1], s.getOrNull(2), Handedness.LEFT) },
            // **Combine two views** (OP-26 step 5). Two ordinary curve picks and nothing else — no scalar, no
            // discrete choice, no new slot kind: the correspondence between the two drawings is the common
            // direction of their two spaces, which the drawing already contains. `crossSpace` for the loft's
            // own reason, and here it is the whole gesture: the second view *has* to be in another space, so
            // switching the sketch plane between the two clicks is the point rather than a convenience.
            ToolDef(COMBINE_VIEWS, "Combine two views", ToolCategory.CURVES, listOf(SlotKind.CURVE, SlotKind.CURVE), crossSpace = true, help = "Click the route drawn in one space — the plan — then switch the sketch plane and click the route drawn in the other — the elevation: the curve in space whose shadow in each of them is the curve you drew there. This is how a route was laid out on a drawing board, and both views stay ordinary drawings: drag a point of either, or tilt either space, and the run follows. The two spaces must meet (parallel ones have no common direction), each view must run one way along that common direction, and only the stretch both views cover becomes a run.", slotNames = listOf("first view", "second view")) { d, p, _ -> d.combineViews(p.elements[0], p.elements[1]) },
            // **Intersection curves** (OP-26 step 6), and one row is the whole cost. One click, on the drawn
            // section: it names the body and, by where it lands, which of the cut's curves is meant — the
            // branch is scored once and rides the step's existing `signs=` (OP-1/OP-18), so a reload never
            // scores again. No scalar, no second pick, no new file argument.
            ToolDef(INTERSECTION_CURVE, "Intersection curve", ToolCategory.CURVES, listOf(SlotKind.SECTION_CURVE), help = "On a working plane, click the section of the body you want: the curve in space where that plane meets that solid becomes a curve like any other — sweep a tube along it, stand a station on it, carry a cut along it. A plane cuts a body in several curves in general (a bent bar is cut twice, a tube gives two loops); the one you click is the one you get, and that choice is remembered, so moving the plane afterwards never swaps to another curve. Move the body or the plane and the curve follows. The plan draws no section — open a working plane first.", slotNames = listOf("section of a solid")) { d, p, _ -> d.intersectionCurve(p.elements[0], p.at, p.signs.firstOrNull()) },
            // **The lift** (OP-26, step 1's missing source): the drawing read as the run it already is. A
            // repeating slot, because a route may be several drawn pieces — and `closesOnFirstPick` for
            // [CURVE3]'s own reason, since "and it comes back here" is a different run from the open one
            // through the same pieces and has to reach `build` rather than sit beside the picks. `minPicks = 1`
            // because one outline is a perfectly good route, which is the whole of the user's report.
            ToolDef(LIFT, "Lift drawing into space", ToolCategory.CURVES, listOf(SlotKind.DRAWN_RUN), repeating = true, minPicks = 1, closesOnFirstPick = true, replicates = false, help = "Click a drawing — an outline, a wall footprint, a rounded rectangle's border, a circle, or a single segment, arc or curve — and it becomes a curve in space lying exactly where it is drawn: the route a sweep, a tube or a station then follows. Click several pieces in turn to chain them into one run (they must meet end to end), and click the first again to say the run comes back there; Enter finishes an open one. The run starts where the drawing starts and goes the way the drawing goes — for a traced outline that is its own counter-clockwise traversal — so it never changes because you clicked somewhere else. It stays live: drag a corner, retype a fillet radius or tilt the plane it is drawn on and the run follows. You do not need this tool to sweep: clicking the drawing itself where a curve in space is wanted reads it the same way. It is for naming the run, hiding it, or using one route for several bodies.", slotNames = listOf("drawn curve")) { d, p, _ -> d.liftCurves(p.elements) },
            // **Connect** (OP-26 step 7). Two PATH3 picks and two **defaulted dimensionless** tensions, so the
            // everyday gesture is two clicks and typing numbers first tightens or slackens the bend. Each
            // click says two things — which curve, and which of its two ends — and the second half is scored
            // once from where the click landed and then rides the step's `signs=` (OP-1/OP-18), never scored
            // again. `scalarsTypedOnly`, because two dimensionless slots cannot be told apart from a stray
            // number left in the panel by an earlier gesture, and a tension adopted by accident would silently
            // change the shape of the join. `crossSpace` for the loft's own reason, and here it is structural:
            // a curve in space is addressable only in the space it belongs to, so a helix on a datum and a run
            // in the plan could not otherwise be joined at all — switch the sketch plane between the clicks.
            ToolDef(CONNECT, "Connect two curves", ToolCategory.CURVES, listOf(SlotKind.PATH3, SlotKind.PATH3), crossSpace = true, scalars = listOf(num("tension", 1.0), num("far tension", 1.0)), scalarsTypedOnly = true, help = "Click near the end of one curve in space — or of a drawing, which is read as the run it already is — then near the end of another: a bend joins them, leaving each run along the way it was already going, so the route reads as one manufactured piece rather than two runs and a kink. Which end you click is which end it joins — that choice is remembered, so moving the curves afterwards never swaps ends. Type a tension first to pull the bend out along the first curve's direction (1, the default, makes it the straight segment when the two ends face each other), and a second number for the other end. Both stay parameters, and the join follows both curves through every edit.", slotNames = listOf("first curve", "second curve")) { d, p, s -> d.connectCurves(p.elements[0], p.elements[1], s.getOrNull(0), s.getOrNull(1), p.clicks, p.signs, Continuity.G1) },
            // **Projection onto a face** (OP-26, step 8), and one row is the whole cost of it. Two ordinary
            // picks: what is thrown, and what it is thrown at. The direction is the drawing's own space —
            // stating it again would be a second way to say what a space already says — and which face is
            // scored once from where the drawing lands and then persisted, exactly as step 6's chosen curve is.
            ToolDef(PROJECT_ON_FACE, "Project onto a face", ToolCategory.CURVES, listOf(SlotKind.CURVE, SlotKind.SOLID), crossSpace = true, help = "Click a curve you have drawn, then a solid: the curve is thrown onto that body's face along the way you are looking at it — straight down out of the plan, or out of whatever sketch plane you drew it on — and what comes back is a curve in space lying in that face. An engraved line, a groove to sweep, a route that has to follow the part. Both stay live: drag a point of the drawing, or stretch the body, and it follows. A segment and a Bézier land exactly; a circle lands as the ellipse it really is. A face standing edge-on to your drawing says so and heals when you tilt the plane, and an imported body has no named faces to land on.", slotNames = listOf("curve to project", "solid")) { d, p, _ -> d.projectOntoFace(p.elements[0], p.elements[1], p.signs) },
            ToolDef(CONNECT_G2, "Connect two curves (curvature)", ToolCategory.CURVES, listOf(SlotKind.PATH3, SlotKind.PATH3), crossSpace = true, scalars = listOf(num("tension", 1.0), num("far tension", 1.0)), scalarsTypedOnly = true, help = "The same gesture as Connect two curves, matching each run's curvature as well as its direction: the bend does not merely leave along the run, it leaves bending as hard as the run was bending, so a tube along the whole route has no visible break in it at all. It is three cubic pieces instead of one, and it is exact — nothing is fitted. Which continuity a join has is which tool you used, so it is what the file records and never changes by itself.", slotNames = listOf("first curve", "second curve")) { d, p, s -> d.connectCurves(p.elements[0], p.elements[1], s.getOrNull(0), s.getOrNull(1), p.clicks, p.signs, Continuity.G2) },
            ToolDef(OUTLINE, "Outline", ToolCategory.RESULT, listOf(SlotKind.CURVE), repeating = true, followsBoundary = true, shortcut = 'O', help = "Click the curves round the boundary in order, then click the first again (or press Enter) to close it.", slotNames = listOf("boundary curve"), icon = Icons.OUTLINE) { d, p, _ -> d.buildOutline(p.elements, p.clicks) },
            ToolDef(THICKEN, "Thicken (wall over curves)", ToolCategory.RESULT, listOf(SlotKind.CURVE), scalars = listOf(len("thickness")), repeating = true, minPicks = 1, sidePerPick = true, replicates = false, extendsResult = true, preview = Previews::thicken, help = "Type a thickness, set Wall side, then click the curves the wall follows — segments, arcs or Béziers that meet end to end, or end part-way along one another (a T joins with no seam). The side applies to the next click, so it can change per curve. Enter (or clicking the first curve again) builds it; a disconnected pick is refused. Click an existing wall first to *extend* it instead: its thickness stays its own and its openings, dimensions and solids follow (Alt on that first click starts a new wall there instead).", slotNames = listOf("carrier curve"), icon = Icons.THICKEN) { d, p, s -> d.buildThickNetwork(p.elements, p.signs.map { Tools.sideOf(it) }, s[0]) },
            ToolDef(CONCENTRIC, "Concentric circle", ToolCategory.CURVES, listOf(SlotKind.CIRCLE, SlotKind.SIDE), scalars = listOf(len("distance")), help = "Type a distance (or pick a parameter in the panel), click a circle or arc, then click inside or outside for the concentric circle.", slotNames = listOf("circle", "side"), icon = Icons.CONCENTRIC) { d, p, s -> d.concentricCircle(p.elements[0], s[0], p.at) },
            // rectangular *by construction* — the two other corners share the clicked corners' coordinates,
            // so no gesture and no parameter edit can shear it (see [Document.rectangle])
            // two SIDE slots, because the corners this tool wants are the path's own vertices: a POINT slot
            // would place two free points beside them that nothing reads (see [Document.orthoRectangle])
            ToolDef(RECTANGLE, "Rectangle", ToolCategory.CURVES, listOf(SlotKind.SIDE, SlotKind.SIDE), shortcut = 'R', recordsSteps = true, preview = Previews::rectangle, help = "Click two diagonally opposite corners. The result is a closed ortho path: drag a corner or a whole side, type either side's length, and thicken it into walls. A corner clicked on existing geometry joins it, exactly as an ortho-path click does.", slotNames = listOf("corner", "opposite corner"), icon = Icons.RECTANGLE) { d, p, _ -> d.orthoRectangle(p.clicks[0], p.clicks[1], p.landings.getOrNull(0), p.landings.getOrNull(1)) },
            ToolDef(ROUNDED_RECT, "Rounded rectangle", ToolCategory.CURVES, listOf(SlotKind.POINT, SlotKind.POINT), scalars = listOf(len("radius")), preview = Previews::roundedRect, help = "Type a corner radius (or pick a parameter in the panel), then click two diagonally opposite corners; centre and size follow those two points.", slotNames = listOf("corner", "opposite corner"), icon = Icons.ROUNDED_RECT) { d, p, s -> d.roundedRectangle(p.points[0], p.points[1], s[0]) },
            // the corner radius is a **defaulted** length slot (0 = don't round), and a non-zero one turns the
            // same two clicks into OP-23's composition — a circular pattern of the vertex, one replicated side
            // and one replicated fillet. So the everyday shortcut and the general mechanism are one
            // construction, and the tool records the steps that say which (see [Document.regularPolygonGesture]).
            // It does not itself replicate: what it builds *is* a pattern.
            ToolDef(POLYGON, "Regular polygon", ToolCategory.CURVES, listOf(SlotKind.POINT, SlotKind.POINT), scalars = listOf(choiceLen("corner radius", 0.0)), minCount = 3, recordsSteps = true, replicates = false, preview = Previews::polygon, help = "Set the number of sides, then click the centre and one vertex; the other vertices are that one rotated about the centre. Type a corner radius first to get a rounded polygon — a live pattern whose count you can re-stamp.", slotNames = listOf("centre", "vertex"), icon = Icons.POLYGON) { d, p, s -> d.regularPolygonGesture(p, s) },
            // ----- Solids: the 2D->3D seam (OP-17). The sketch plane is the world XY plane in this
            // slice; the depth/angle is a panel parameter, which is where the feature's DOF is edited
            // (OP-13) since the 3D view has no picking yet.
            ToolDef(EXTRUDE, "Extrude", ToolCategory.SOLIDS, listOf(SlotKind.AREA), scalars = listOf(len("depth")), shortcut = 'E', help = "Type a depth (or pick a parameter in the panel), then click an outline or wall footprint: it becomes a solid, shown in the 3D view. A positive depth builds toward this plane's front — the way the tick at its origin points.", slotNames = listOf("profile"), icon = Icons.EXTRUDE) { d, p, s -> d.extrudeSolid(p.elements[0], s[0]) },
            // **Full is the default, and it is structural** (session 63): with nothing typed the angle slot
            // names a *complete* revolution — a body with no ends, whose watertightness no later edit can
            // switch off, because the graph holds no angle node at all (OP-14's circle-vs-arc rule one
            // dimension up; see [constructit.geom.Turn3]). A typed angle builds the partial, and the offset
            // beside it says where about the axis that partial starts — `typedOnly`, because two angle slots
            // cannot be told apart by dimension and a stray angle must never become an offset nobody stated.
            ToolDef(REVOLVE, "Revolve", ToolCategory.SOLIDS, listOf(SlotKind.AREA, SlotKind.LINE), scalars = listOf(angChoice("angle", 360.0), ang("offset", 0.0, typedOnly = true)), help = "Click an outline or footprint, then a line to spin it about (the profile must not cross the axis): with no angle typed it goes the whole way round, a closed body with no ends. Type an angle first (or pick a parameter in the panel) for a partial turn — a positive one turns toward this plane's front, the way the tick at its origin points, and a negative one sweeps the other way — and a second number for the offset it starts at, so the body can straddle the drawing or stand clear of it.", slotNames = listOf("profile", "axis"), icon = Icons.REVOLVE) { d, p, s -> d.revolveSolid(p.elements[0], p.elements[1], s.getOrNull(0), s.getOrNull(1)) },
            // ----- the ball (the sphere queue, item 3). Two rows beside the revolve they *are*: each builds a
            // pole-to-pole arc, the diameter that closes it and the structural full turn about that diameter, in
            // the active sketch plane — so on a face or a datum it just works, the revolve being plane-anchored.
            // No kernel change, no `Sphere3`, no new refusal: the radius is a parameter (or, in the second
            // spelling, the derived centre-to-point distance), the arc and the axis stay live and draggable, and
            // the body is watertight *structurally* because a full turn is a kind (`Turn3.Full`).
            ToolDef(SPHERE_R, "Sphere (centre, radius)", ToolCategory.SOLIDS, listOf(SlotKind.POINT), scalars = listOf(len("radius")), help = "Type a radius (or pick a parameter in the panel), then click the centre: a ball, built the way you would build one by hand — a pole-to-pole arc, the diameter that closes it, and that half-disc turned the whole way round its own diameter. It is a revolve, so it has no ends and no caps — and its faces are named, so a working plane through the centre sections it as an exact circle (off-centre too, as the small circle it is) and that circle is a construction input like any other. The picture is still triangles, so the displayed volume reads a shade under the exact ball's. The centre and the radius stay live — drag the centre and the ball follows, retype the radius and it resizes — and the arc and the axis are ordinary curves you can dimension, hide or build on. It lands in the plane you are drawing on, so its equator lies there.", slotNames = listOf("centre"), icon = Icons.SPHERE_R) { d, p, s -> d.sphereCR(p.points[0], s[0]) },
            ToolDef(SPHERE, "Sphere (centre, surface point)", ToolCategory.SOLIDS, listOf(SlotKind.POINT, SlotKind.POINT), preview = Previews::circleCentrePoint, help = "Click the centre, then a point the surface passes through — the ball's spelling of Circle (centre, point). The radius is the distance between the two clicks rather than a number, so dragging the surface point resizes the ball; click an existing point and that point is shared, so the ball follows whatever moves it. It is the same body the radius spelling builds: a half-disc turned the whole way round its own diameter, a revolve with no ends — and a plane through the centre sections it as an exact circle, which can be built on.", slotNames = listOf("centre", "surface point"), icon = Icons.SPHERE) { d, p, _ -> d.sphereCP(p.points[0], p.points[1]) },
            // ----- the loft (OP-17): the one solid whose cross-section changes along the run. Two tools, one
            // feature: *Extrude to point* is the pyramid/cone gesture (an area, a height, an apex position, and
            // the apex is a real point element so it stays draggable), and *Loft* is the general one — a
            // repeating pick over sections that may live on **different sketch planes**, with an open curve
            // among the picks meaning "guide". Where each section is clicked scores its seam, once (OP-1).
            ToolDef(EXTRUDE_TO_POINT, "Extrude to point (pyramid, cone)", ToolCategory.SOLIDS, listOf(SlotKind.AREA, SlotKind.POINT), scalars = listOf(len("height")), preview = Previews::extrudeToPoint, help = "Type a height (or pick a parameter in the panel), click an outline, footprint or circle, then click where its apex belongs: the area runs to that point — a pyramid from a polygon, a cone from a circle. Clicking an existing point shares it; drag the apex afterwards for an oblique one, or retype the height.", slotNames = listOf("profile", "apex"), icon = Icons.EXTRUDE_TO_POINT) { d, p, s -> d.extrudeToPoint(p.elements[0], p.points[0], s[0], p.clicks.firstOrNull(), p.signs) },
            ToolDef(LOFT, "Loft (sections)", ToolCategory.SOLIDS, listOf(SlotKind.LOFT_PART), repeating = true, minPicks = 2, crossSpace = true, preview = Previews::loft, help = "Click the sections in order — an outline, a wall footprint, a circle, or a point for an apex end — then press Enter (or click the first section again). Sections may lie on different sketch planes: switch the plane between clicks and the picks are kept. An *open* curve among the picks is a guide the run follows, and it must pass through corresponding points of the sections it spans. Where you click on each section starts its boundary correspondence, so click near the corner that should meet the one you clicked before — the preview draws the rails.", slotNames = listOf("section, apex point or guide"), icon = Icons.LOFT) { d, p, _ -> d.loftSolid(p.elements, p.clicks, p.signs) },
            // ----- the sweep (OP-26, step 2): the one solid whose *axis* is a curve. Two tools, one feature
            // and one frame: *Tube* takes a radius, because a circular run is the everyday case and a circle
            // needs no drawing; *Sweep* takes any closed area, drawn wherever it was convenient and read in
            // the moving frame's own coordinates — by default with its origin on the path, so a section drawn
            // off the origin sweeps off the path by exactly that much.
            //
            // **And an optional third pick says which point of the section rides the run** (GitHub issue #15):
            // a worm thread drawn *in place* at the shaft's surface is 5 mm off the drawing's origin and
            // therefore orbited 5 mm off its own coil, which the node then refused by name for outgrowing the
            // bend — correctly, because what was stated was not what was meant. The cure is the anchor the
            // user can *state* (DESIGN.md's *"explicit anchors beat compensation"*) and never a compensating
            // offset the tool computes: an ordinary point pick, shared by node when it lands on an existing
            // point, so dragging it moves the swept body. Optional in the [SlotKind.OPTIONAL_POINT] sense —
            // a click that hits no point is the *profile's* click — so the two-pick gesture is untouched and
            // every drawing written before this means exactly what it meant.
            //
            // *Tube* deliberately gains nothing: a round section centred on the path has no off-origin
            // reading to correct, since its radius *is* its reach and its centre is the run.
            //
            // **Roll and twist are defaulted angle slots on both**, which is why neither gesture grew a step:
            // with nothing typed they are zero and the tool completes on its last click. The roll is a real
            // degree of freedom of the frame and is *stated* rather than chosen for the user (OP-26); the
            // twist is what closes a non-planar closed run, which is the one case the node refuses by name
            // and tells you the number to type.
            ToolDef(TUBE, "Tube along a curve", ToolCategory.SOLIDS, listOf(SlotKind.PATH3), scalars = listOf(len("radius"), ang("roll", 0.0), ang("twist", 0.0)), help = "Type a radius (or pick a parameter in the panel), then click a curve in space — or a drawing, which is read as the run it already is: a round tube follows it — a cable, a conduit, a handrail. Type a roll after the radius to turn the section about the run at its start, and a twist for the total turn from one end to the other. The curve stays live: drag a point it runs through and the tube follows. In the plan the tube shows the outline of its two sides, which is exactly where they are; an end face is closed by a straight line across it, and where the run points straight down into the plan you see the section itself.", slotNames = listOf("curve in space")) { d, p, s -> d.tubeAlongCurve(p.elements[0], s[0], s.getOrNull(1), s.getOrNull(2)) },
            ToolDef(SWEEP, "Sweep (profile along a curve)", ToolCategory.SOLIDS, listOf(SlotKind.PATH3, SlotKind.OPTIONAL_POINT, SlotKind.AREA), scalars = listOf(ang("roll", 0.0), ang("twist", 0.0)), crossSpace = true, help = "Click a curve in space, then a closed area — an outline, a wall footprint, a circle, a rounded rectangle: it is carried along the curve, read in the moving frame. The route may be a **drawing** rather than a curve in space: click an outline, a footprint's border, a segment or an arc and it is read as the run it already is, lying where it is drawn, arcs and all. Click no point between the two and the area is swept **from where it is drawn**, as long as the curve goes through the area's own sketch plane: the point the curve pierces that plane at is the point of the section that rides the run, and the section stands the way you drew it there — so a foundation drawn in a section through the building sweeps round the wall sitting on the ground, not floating. Where the curve crosses that plane more than once, the crossing nearest the area is taken and remembered, so a later edit never moves the body to the other crossing. Between the two clicks you may instead click one **point of the section that is to ride the run**, which states it yourself and wins: aim at that point (a click nearer the area's edge than to any point of it is the area's own click). Where the curve crosses the area's plane nowhere, the area's own origin rides the run, so an area drawn off the origin sweeps off it by exactly that much. A picked point is shared, so dragging it moves the body; it must be a point of the same sketch plane the area is drawn in. The curve and the area may live in different planes: switch the plane between clicks and the picks are kept. Type a roll first to turn the section about the run at its start, and a twist after it for the total turn from end to end. Both stay parameters. In the plan a swept body shows the outline of its two sides; an end face is closed by a straight line across it, and where the run points straight down into the plan you see the section itself.", slotNames = listOf("curve in space", "point of the section to ride the run", "profile")) { d, p, s -> d.sweepAlongCurve(p.elements[0], p.elements[1], s.getOrNull(0), s.getOrNull(1), p.points.firstOrNull(), p.signs.firstOrNull()) },
            // ----- and back down again (OP-17's downward direction). *Extrude on face* is the
            // sketch->feature->sketch loop as one gesture: the plan is drawn in the same 2D space, and the
            // tool only says which solid's top face it is stacked on (through `facePlane`, OP-8).
            // *Section* is the other direction: a solid's cross-section, as an ordinary 2D area.
            ToolDef(EXTRUDE_ON_FACE, "Extrude on face", ToolCategory.SOLIDS, listOf(SlotKind.SOLID, SlotKind.AREA), scalars = listOf(len("depth")), help = "Type a depth (or pick a parameter in the panel), click the solid to build on, then the area to raise: it is extruded from that solid's top face (an upper storey, a boss).", slotNames = listOf("base solid", "profile"), icon = Icons.EXTRUDE_ON_FACE) { d, p, s -> d.extrudeOnFace(p.elements[0], p.elements[1], s[0]) },
            ToolDef(SECTION, "Section", ToolCategory.SOLIDS, listOf(SlotKind.SOLID), scalars = listOf(len("height")), help = "Type a height (or pick a parameter in the panel), then click a solid: its cross-section at that height becomes an ordinary 2D area — dimension it, or extrude it again.", slotNames = listOf("solid"), icon = Icons.SECTION) { d, p, s -> d.sectionSolid(p.elements[0], s[0]) },
            // `facePartOperand` makes elements[0] the part being cut — the *tip* of its boolean chain as it
            // stands, resolved by the editor and recorded in the step, so cuts chain instead of forking
            // it **does** replicate, and as a *chain*: the part operand is re-resolved per copy, so a Cut on one
            // member of a face-space pattern becomes a bolt circle of pockets in one body (OP-23)
            ToolDef(CUT, "Cut", ToolCategory.SOLIDS, listOf(SlotKind.AREA), scalars = listOf(len("depth")), facePartOperand = true, help = "In a face view: type a depth (or pick a parameter in the panel), then click an area — it is extruded into the material and subtracted from the part this face belongs to (a drilled hole, a pocket, a slot).", slotNames = listOf("profile"), icon = Icons.CUT) { d, p, s -> d.cutOnFace(p.elements[0], p.elements[1], s[0]) },
            // ----- Booleans: exact via the slab algebra for solids extruded along the same axis (OP-22),
            // through the general mesh engine for every other pair (OP-9). Two solid picks and nothing
            // else — the dispatch is the op node's job, so these are data like every other tool.
            ToolDef(UNION, "Union", ToolCategory.SOLIDS, listOf(SlotKind.SOLID, SlotKind.SOLID), crossSpace = true, help = "Click two solids to fuse them into one. Any pair works: solids extruded along the same axis fuse exactly and the result keeps offering section inputs; any other pair — cross-axis, a revolve, a swept body — fuses through the general engine (Manifold) into a watertight body whose sections draw but offer no inputs to build on. Click each solid where the canvas draws it: its footprint in the space its sketch was drawn in, or its section where a working plane cuts it. The two may live in different planes — switch the sketch plane between clicks and the picks are kept.", slotNames = listOf("solid", "solid"), icon = Icons.UNION) { d, p, _ -> d.combineSolids(p.elements[0], p.elements[1], BoolOp.UNION) },
            ToolDef(SUBTRACT, "Subtract", ToolCategory.SOLIDS, listOf(SlotKind.SOLID, SlotKind.SOLID), shortcut = 'X', crossSpace = true, help = "Click the solid to keep, then the one to remove from it (a counterbore, a pocket, an opening). Click each where the canvas draws it: its footprint in the space its sketch was drawn in, or its section where a working plane cuts it. The two may live in different planes — switch the sketch plane between clicks and the picks are kept.", slotNames = listOf("kept solid", "removed solid"), icon = Icons.SUBTRACT) { d, p, _ -> d.combineSolids(p.elements[0], p.elements[1], BoolOp.SUBTRACT) },
            ToolDef(INTERSECT_SOLIDS, "Intersect solids", ToolCategory.SOLIDS, listOf(SlotKind.SOLID, SlotKind.SOLID), crossSpace = true, help = "Click two solids to keep only what they have in common. Click each where the canvas draws it: its footprint in the space its sketch was drawn in, or its section where a working plane cuts it. The two may live in different planes — switch the sketch plane between clicks and the picks are kept.", slotNames = listOf("solid", "solid"), icon = Icons.INTERSECT_SOLIDS) { d, p, _ -> d.combineSolids(p.elements[0], p.elements[1], BoolOp.INTERSECT) },
            // Placement (the JT-import package, OP-9): a solid, a point in the space you are looking at,
            // and a **defaulted** angle — so the everyday gesture is two clicks and typing a number first
            // turns the body. Every input is a node, which is the whole point: weld the point onto a
            // construction and the body follows it, wire the angle to a parameter and two bodies turn
            // together. It does not replicate — a pattern fans a *gesture*, and a placement's subject is one
            // named body (OP-23).
            ToolDef(PLACE_SOLID, "Place solid", ToolCategory.SOLIDS, listOf(SlotKind.SOLID, SlotKind.POINT), scalars = listOf(ang("angle", 0.0)), replicates = false, help = "Type an angle if you want one, then click a solid and the point it should sit at: the body is moved so its own coordinates are read from the sketch space you are in, at that point, turned by that angle. The point and the angle stay live — drag the point and the body follows, retype the angle and it turns. An imported reference body arrives already placed this way.", slotNames = listOf("solid", "at point")) { d, p, s -> d.placeSolid(p.elements[0], p.points[0], s.firstOrNull()) },
            // ...and the identical gesture one dimension down (OP-26, step 9): an imported **wireframe** is
            // moved by the same two live nodes a body is, through the same rigid map, so a run and a body
            // welded to one anchor point can never drift apart.
            ToolDef(PLACE_CURVE, "Place curve in space", ToolCategory.CURVES, listOf(SlotKind.PATH3, SlotKind.POINT), scalars = listOf(ang("angle", 0.0)), replicates = false, help = "Type an angle if you want one, then click a curve in space (or a drawing, which is read as the run it already is) and the point it should sit at: the run is moved so its own coordinates are read from the sketch space you are in, at that point, turned by that angle. The point and the angle stay live — drag the point and the run follows, retype the angle and it turns. An imported wireframe arrives already placed this way.", slotNames = listOf("curve in space", "at point")) { d, p, s -> d.placeCurve(p.elements[0], p.points[0], s.firstOrNull()) },
            ToolDef(CUT_OPENINGS, "Cut openings", ToolCategory.SOLIDS, listOf(SlotKind.SOLID), help = "Click a solid extruded from a wall footprint: every opening on that wall becomes a subtracted box, sill to head. Openings added later need the tool again.", slotNames = listOf("wall solid")) { d, p, _ -> d.cutOpenings(p.elements[0]) },
            // ----- Cutting with an *unbounded* tool (OP-22's extension). The chain is drawn like any other
            // run of points and is a value of its own; the two tools that use it are one node with a
            // different number of halves kept, and the kept side rides `signs=` like every other scored
            // discrete choice (OP-1).
            //
            // All six rows declare **`crossSpace`** — the boolean's own declaration (session 55), for the
            // boolean's own reason: a solid is a **body**, not a drawing, so the reason a switch drops picks
            // (they name elements whose coordinates the new space does not share) does not apply to one. The
            // cutting fence stands normal to the **chain's own space**, whichever space is showing, which is
            // what `Document.cutByChain` has always computed and now what the gesture can reach.
            ToolDef(CHAIN, "Chain (cutting curve)", ToolCategory.CURVES, listOf(SlotKind.POINT), repeating = true, minPicks = 2, help = "Click the points the cut runs through, then press Enter: the first and last become rays, so the chain runs to infinity at both ends and separates the drawing into two sides. Two clicks give an infinite line; each further click bends it. Cut or split a solid with it afterwards — or cut with a line you have already drawn, which is that same infinite line, or with any closed curve.", slotNames = listOf("point on the chain")) { d, p, _ -> d.chainThroughPoints(p.points) },
            ToolDef(CUT_BY_CHAIN, "Cut by chain", ToolCategory.SOLIDS, listOf(SlotKind.SOLID, SlotKind.CHAIN, SlotKind.SIDE), crossSpace = true, help = "Click the solid, then the chain to cut it with, then click the side to keep. The chain can be a drawn chain, an ordinary infinite line — including a mirrored one, so a symmetric pair of cuts costs no second drawing — or any closed curve (a circle cuts a through-bore). Click the solid where the canvas draws it: its footprint in the space it was sketched in, its section where a working plane cuts it, or the body itself in the 3D view. The cut runs square to the plane the chain is drawn in, whichever space is showing — so switch the sketch plane between clicks and the picks are kept. The side is remembered, so moving the chain afterwards never swaps which half survives.", slotNames = listOf("solid", "chain", "side to keep")) { d, p, _ -> d.cutByChain(p.elements[0], p.elements[1], p.clicks.lastOrNull(), p.signs) },
            ToolDef(SPLIT_BY_CHAIN, "Split by chain", ToolCategory.SOLIDS, listOf(SlotKind.SOLID, SlotKind.CHAIN), crossSpace = true, help = "Click the solid, then the chain — a drawn chain, an infinite line, or any closed curve: both halves become solids, so a clamshell housing is one gesture. Hide or cut either afterwards. The cut runs square to the plane the chain is drawn in, so the two picks may be made in different spaces — switch the sketch plane between them and the picks are kept.", slotNames = listOf("solid", "chain")) { d, p, _ -> d.splitByChain(p.elements[0], p.elements[1]) },
            // …and the same two uses with a **directrix** (step 2). The route is picked like any curve in
            // space and runs on past the body at both ends, so "long enough" is never a number anybody types;
            // the carry mode is the row, because it is a discrete structural choice and this project states
            // those by which tool was used.
            ToolDef(CUT_ALONG_CURVE, "Cut by chain along a curve", ToolCategory.SOLIDS, listOf(SlotKind.SOLID, SlotKind.CHAIN, SlotKind.PATH3, SlotKind.SIDE), crossSpace = true, help = "Click the solid, the chain (a drawn chain, an infinite line, or any closed curve), the route to carry it along — a curve in space, or a drawing read as the run it already is — then the side to keep: the chain is swept along the route and the swept surface cuts the body — the pocket where a pipe enters a housing, a curved relief, a channel that follows a route. The section turns with the route, staying square to it. Draw the chain about the sketch space's origin: it is read in the route's own frame with that origin on the route, so a chain drawn 20 mm off the origin cuts 20 mm off the route. The route runs on past the body at both ends, so it never has to be drawn long enough; only the part of it that meets the body is used.", slotNames = listOf("solid", "chain", "route", "side to keep")) { d, p, _ -> d.cutByChain(p.elements[0], p.elements[1], p.clicks.lastOrNull(), p.signs, p.elements[2], CarryMode.ROTATING) },
            ToolDef(CUT_ALONG_CURVE_FLAT, "Cut by chain along a curve (translational)", ToolCategory.SOLIDS, listOf(SlotKind.SOLID, SlotKind.CHAIN, SlotKind.PATH3, SlotKind.SIDE), crossSpace = true, help = "The same gesture as Cut by chain along a curve, with the section kept parallel to the plane it was drawn in instead of turning with the route — the cut a saw held at one angle makes while the work is moved along a curve. The two agree exactly while the route is straight and part company as soon as it bends; which one a cut uses is what the file records, so it never changes by itself.", slotNames = listOf("solid", "chain", "route", "side to keep")) { d, p, _ -> d.cutByChain(p.elements[0], p.elements[1], p.clicks.lastOrNull(), p.signs, p.elements[2], CarryMode.TRANSLATIONAL) },
            ToolDef(SPLIT_ALONG_CURVE, "Split by chain along a curve", ToolCategory.SOLIDS, listOf(SlotKind.SOLID, SlotKind.CHAIN, SlotKind.PATH3), crossSpace = true, help = "Click the solid, the chain (a drawn chain, an infinite line, or any closed curve) and the route to carry it along — a curve in space, or a drawing read as the run it already is: both halves of the swept cut become solids. The section turns with the route, and is read with its sketch space's origin on the route.", slotNames = listOf("solid", "chain", "route")) { d, p, _ -> d.splitByChain(p.elements[0], p.elements[1], p.elements[2], CarryMode.ROTATING) },
            ToolDef(SPLIT_ALONG_CURVE_FLAT, "Split by chain along a curve (translational)", ToolCategory.SOLIDS, listOf(SlotKind.SOLID, SlotKind.CHAIN, SlotKind.PATH3), crossSpace = true, help = "The same gesture as Split by chain along a curve, with the section kept parallel to the plane it was drawn in instead of turning with the route.", slotNames = listOf("solid", "chain", "route")) { d, p, _ -> d.splitByChain(p.elements[0], p.elements[1], p.elements[2], CarryMode.TRANSLATIONAL) },
            // ----- Planes: every tool that creates a working plane, in one group (GitHub #9, the user's
            // rule — "the section-plane tool is not a solid creation tool"). *Section* is not here: it makes
            // 2D geometry *from* a solid and stays with the solids. What they share is that each records its
            // own `sketchspace` step and none of them replicates — a sketch space is organisation, not
            // geometry an orbit could fan (OP-23).
            //
            // The parallel plane needs **no pick at all** (GitHub #9): a height, then a click that only says
            // "now" — the same no-slot shape *Point (x, y)* has. Its input geometry arrives on its own, being
            // the section of every solid created before it (`Document.spaceAncestors`).
            ToolDef(PLANE_AT_HEIGHT, "Plane at height", ToolCategory.PLANES, emptyList(), scalars = listOf(len("height")), recordsSteps = true, replicates = false, help = "Type a height (or pick a parameter in the panel), then click anywhere: a new sketch plane parallel to the one you are in, that far along its normal — no line and no solid to pick. Everything the plane cuts is drawn there and can be clicked as an input: the section of every solid built before it. Extrude builds along the plane's normal, Cut goes the other way. The height stays a parameter — retype it and the plane slides, with everything drawn on it.") { d, _, s -> d.createParallelSpace(s[0]) },
            // ----- ...and the general form of the same thing (GitHub #6): **any** line, **any** angle. One
            // LINE pick (a line, a segment, a ray or an ortho leg — the ordinary carrier coercion) plus a
            // *defaulted* angle slot, so the gesture is one click and typing a number first tilts the plane.
            // It records its own `sketchspace` step, like the face tool, and does not replicate: a sketch
            // space is organisation, not geometry an orbit could fan (OP-23).
            ToolDef(SKETCH_PLANE, "Sketch plane (line + angle)", ToolCategory.PLANES, listOf(SlotKind.LINE), scalars = listOf(ang("angle", 90.0), len("offset", 0.0)), recordsSteps = true, replicates = false, help = "Type an angle (90° if you type none) — and, if you want the plane moved off the line, an offset after it — then click a line, segment or wall leg: the 2D view switches to a new sketch plane through that line, tilted by that angle out of the space you are in and shifted along its own normal by the offset. So 0° with an offset is a plane *parallel* to the one you are in, which is what a stack of loft sections wants. u runs along the line, v rises out of the old plane; Extrude builds along the new plane's normal and Cut goes the other way, so a negative angle swaps them. Both numbers stay parameters — retype either and the plane moves, with everything drawn on it.", slotNames = listOf("hinge line")) { d, p, s -> d.createDatumSpace(p.elements[0], s.firstOrNull(), offset = s.getOrNull(1)) },
            // ----- ...and the third plane that is a face of nothing: a **station** across a curve in space
            // (OP-26, step 4). One PATH3 pick plus one length the tool waits for, because the distance *is*
            // the feature — "one, stated by a distance" — and a defaulted slot would take a length left in
            // the panel by the last gesture and put the plane somewhere nobody said. It records its own
            // `sketchspace` step like every other plane tool and does not replicate; a station **family** is
            // OP-26's own to-be-discussed item and is deliberately not this row.
            ToolDef(STATION, "Station (plane across a curve)", ToolCategory.PLANES, listOf(SlotKind.PATH3), scalars = listOf(len("distance")), recordsSteps = true, replicates = false, help = "Type a distance (or pick a parameter in the panel), then click a curve in space — or a drawing, which is read as the run it already is: the 2D view switches to a new sketch plane standing square across the run that far along it, measured from the curve's start. The origin is on the curve, the normal is the direction the curve is going, and the axes are the moving frame's — so what you draw there rides the run and stays aligned to itself along it. Extrude builds along the plane's normal, Cut goes the other way. The distance stays a parameter: retype it and the plane slides along the curve with everything drawn on it, and a distance past the end of the run makes the plane invalid until you bring it back.", slotNames = listOf("curve in space")) { d, p, s -> d.createStationSpace(p.elements[0], s[0]) },
            // ----- and the fourth plane that is a face of nothing: the plane of an imported **wireframe**
            // (OP-26, step 9). One PATH3 pick and no number at all, because a flat run states its plane
            // completely — it is the station's construction with the distance left out. It records its own
            // steps (a `sketchspace` and the traced geometry) and does not replicate.
            ToolDef(SKETCH_FROM_WIRE, "Sketch from wireframe", ToolCategory.PLANES, listOf(SlotKind.PATH3), recordsSteps = true, replicates = false, help = "Click an imported wireframe run that is flat: the 2D view switches to a new sketch plane on that run's own plane, with the run traced into it as ordinary points and segments you can drag, dimension, trace into an outline and extrude. The sketch rides the imported body — drag the body's point and the plane and everything on it follow. A run that is not flat is refused by name, saying how far off a plane it is: a curve in space is not a sketch, and it is never quietly flattened into one.", slotNames = listOf("imported wireframe")) { d, p, _ -> d.sketchFromWireframe(p.elements[0]) },
            // ----- sketch on a *side* face (OP-17). One click, on a solid's footprint edge: a side face
            // projects to exactly that edge, so the edge names the face and the solid at once. Like the
            // path and opening tools this one records a step of its own (`sketchspace`, naming the
            // boundary-piece index — a discrete choice, OP-18), so the Editor runs its click.
            ToolDef(SKETCH_ON_FACE, "Sketch on face", ToolCategory.PLANES, emptyList(), help = "Click a straight footprint edge of a solid: the 2D view switches to that face, where that edge lies on the x axis with the origin at its middle and v runs up into the face (Space origin moves it onto a corner). A flat face of a lofted solid works too — every face of a pyramid — and so does a turned part's flat end; a curved one (a barrel, a cone, a ball) says which surface it is and points at Sketch plane. On a partial revolve, clicking inside the profile rather than on an edge takes the cap standing in this plane. The part's section at that plane is drawn there, and its edges and corners can be clicked as inputs. Cut there drills into the material; Extrude builds a boss out of it.") { _, _, _ -> },
            // ----- where the coordinates on that plane start (OP-17's space origin). One pick — a corner of
            // the part's section on this plane — plus two defaulted offsets, so the gesture is one click and
            // typing numbers first shifts the origin off the corner. It does not replicate (a space is
            // organisation, not geometry) and it builds nothing: it re-points the space's own origin nodes,
            // which translates everything already drawn there (`Document.setSpaceOrigin`).
            ToolDef(SPACE_ORIGIN, "Space origin", ToolCategory.PLANES, listOf(SlotKind.EXISTING_POINT), scalars = listOf(len("dx", 0.0), len("dy", 0.0)), replicates = false, scalarsTypedOnly = true, help = "In a face or datum view: click a corner of any section on this plane — the part's, or any other solid the plane cuts — and the drawing's origin moves there — coordinates are then measured from that corner, and the origin follows it through every edit. Type dx (and dy) first to sit a fixed offset away from it; both stay parameters. Anchoring a plane that already carries a sketch moves that sketch with the frame — which is how a whole sketch is shifted on its face. The corner has to be there already: empty space places nothing here, because a point drawn on this plane would move with the frame it was defining.", slotNames = listOf("anchor corner")) { d, p, s -> d.setSpaceOriginAt(p.elements[0], s.getOrNull(0), s.getOrNull(1)) },
            // ----- Construct -----
            // the same defaulted factor as Midpoint: with none it is the bisector, with one it is the
            // perpendicular through that ratio point — composed from the ops that already exist
            ToolDef(PERP_BISECTOR, "Perp. bisector", ToolCategory.CONSTRUCT, listOf(SlotKind.POINT, SlotKind.POINT), scalars = listOf(choice("factor", 0.5)), help = "Click two points for their perpendicular bisector — or type a factor first for the perpendicular through that point of the span instead.", slotNames = listOf("from", "to"), icon = Icons.PERP_BISECTOR) { d, p, s -> d.perpBisector(p.points[0], p.points[1], s.firstOrNull()) },
            ToolDef(ANGLE_BISECTOR, "Angle bisector", ToolCategory.CONSTRUCT, listOf(SlotKind.POINT, SlotKind.POINT, SlotKind.POINT), help = "Click a point, the vertex, then another point.", slotNames = listOf("point", "vertex", "point")) { d, p, _ -> d.angleBisector(p.points[0], p.points[1], p.points[2]) },
            ToolDef(PERPENDICULAR, "Perpendicular", ToolCategory.CONSTRUCT, listOf(SlotKind.LINE, SlotKind.POINT), help = "Click a line, then a point, for the perpendicular through it.", slotNames = listOf("line", "through"), icon = Icons.PERPENDICULAR) { d, p, _ -> d.perpendicularThrough(p.elements[0], p.points[0]) },
            ToolDef(PARALLEL, "Parallel", ToolCategory.CONSTRUCT, listOf(SlotKind.LINE, SlotKind.POINT), help = "Click a line, then a point, for the parallel through it.", slotNames = listOf("line", "through"), icon = Icons.PARALLEL) { d, p, _ -> d.parallelThrough(p.elements[0], p.points[0]) },
            ToolDef(PARALLEL_AT, "Parallel at distance", ToolCategory.CONSTRUCT, listOf(SlotKind.LINE, SlotKind.SIDE), scalars = listOf(len("distance")), help = "Type a distance (or pick a parameter in the panel), click the base line, then click the side you want the parallel on.", slotNames = listOf("line", "side"), icon = Icons.PARALLEL_AT) { d, p, s -> d.parallelAtDistance(p.elements[0], s[0], p.at) },
            ToolDef(TANGENT, "Tangent from point", ToolCategory.CONSTRUCT, listOf(SlotKind.POINT, SlotKind.CIRCLE), help = "Click an external point, then a circle or arc (an arc counts as its whole circle).", slotNames = listOf("from", "circle"), icon = Icons.TANGENT) { d, p, _ -> d.tangentFromPoint(p.points[0], p.elements[0]) },
            ToolDef(TANGENT_AT, "Tangent at point", ToolCategory.CONSTRUCT, listOf(SlotKind.ON_CIRCLE_POINT), help = "Click a point that lies on a circle for the tangent there (use Point on circle).", slotNames = listOf("point on circle"), icon = Icons.TANGENT_AT) { d, p, _ -> d.tangentAtPointOnCircle(p.elements[0]) },
            ToolDef(FILLET, "Fillet", ToolCategory.CONSTRUCT, listOf(SlotKind.CARRIER, SlotKind.CARRIER), scalars = listOf(len("radius")), preview = Previews::fillet, help = "Type a radius (or pick a parameter in the panel), then click the two legs — lines, segments, circles or arcs — where you want the rounding to touch them.", slotNames = listOf("leg", "leg"), icon = Icons.FILLET) { d, p, s -> d.filletBetweenCurves(p.elements[0], p.elements[1], s[0], p.clicks[0], p.clicks[1], p.signs) },
            // line-only, deliberately: a bevel across a round leg has two honest readings (a chord, or an
            // arc of the same length), and until the convention is stated a tool that picked one silently
            // would be guessing — recorded in DESIGN.md rather than half-built here
            ToolDef(CHAMFER, "Chamfer", ToolCategory.CONSTRUCT, listOf(SlotKind.LINE, SlotKind.LINE), scalars = listOf(len("distance")), preview = Previews::chamfer, help = "Type a chamfer distance (or pick a parameter in the panel), then click the two straight legs on the sides of the corner you want bevelled.", slotNames = listOf("leg", "leg"), icon = Icons.CHAMFER) { d, p, s -> d.chamferBetweenLines(p.elements[0], p.elements[1], s[0], p.clicks[0], p.clicks[1], p.signs) },
            ToolDef(OUTER_TANGENTS, "Outer tangents", ToolCategory.CONSTRUCT, listOf(SlotKind.CIRCLE, SlotKind.CIRCLE), help = "Click two circles or arcs for their outer common tangents.", slotNames = listOf("circle", "circle"), icon = Icons.OUTER_TANGENTS) { d, p, _ -> d.commonTangents(p.elements[0], p.elements[1], inner = false) },
            ToolDef(INNER_TANGENTS, "Inner tangents", ToolCategory.CONSTRUCT, listOf(SlotKind.CIRCLE, SlotKind.CIRCLE), help = "Click two circles or arcs for their inner (crossing) common tangents.", slotNames = listOf("circle", "circle"), icon = Icons.INNER_TANGENTS) { d, p, _ -> d.commonTangents(p.elements[0], p.elements[1], inner = true) },
            // ----- Transform -----
            ToolDef(MIRROR, "Mirror", ToolCategory.TRANSFORM, listOf(SlotKind.GEOMETRY, SlotKind.LINE), preview = Previews::mirror, help = "Click geometry, then a line to mirror it across.", slotNames = listOf("geometry", "axis"), icon = Icons.MIRROR) { d, p, _ -> d.mirror(p.elements[0], p.elements[1]) },
            // *Point reflect* takes **no scalar** and that is the whole of it (OP-14): the half turn is a
            // constant inside the node, so two clicks are the entire gesture and the panel gains nothing a
            // later edit could turn back into an ordinary rotation.
            ToolDef(POINT_REFLECT, "Point reflect", ToolCategory.TRANSFORM, listOf(SlotKind.GEOMETRY, SlotKind.POINT), preview = Previews::pointReflect, help = "Click geometry, then the centre to reflect it through: every point lands as far on the other side. A half turn by construction — there is no angle to drift. Clicking an existing point shares it.", slotNames = listOf("geometry", "centre"), icon = Icons.POINT_REFLECT) { d, p, _ -> d.pointReflect(p.elements[0], p.points[0]) },
            ToolDef(ROTATE, "Rotate", ToolCategory.TRANSFORM, listOf(SlotKind.GEOMETRY, SlotKind.POINT), scalars = listOf(ang("angle")), preview = Previews::rotate, help = "Type a angle (or pick a parameter in the panel), click geometry, then the centre.", slotNames = listOf("geometry", "centre"), icon = Icons.ROTATE) { d, p, s -> d.rotate(p.elements[0], p.points[0], s[0]) },
            ToolDef(SCALE, "Scale", ToolCategory.TRANSFORM, listOf(SlotKind.GEOMETRY, SlotKind.POINT), scalars = listOf(num("factor")), preview = Previews::scale, help = "Type a factor (or pick a parameter in the panel), click geometry, then the centre.", slotNames = listOf("geometry", "centre"), icon = Icons.SCALE) { d, p, s -> d.scale(p.elements[0], p.points[0], s[0]) },
            ToolDef(TRANSLATE_V, "Translate by vector", ToolCategory.TRANSFORM, listOf(SlotKind.GEOMETRY, SlotKind.POINT, SlotKind.POINT), preview = Previews::translate, help = "Click geometry, then two points defining the translation vector.", slotNames = listOf("geometry", "from", "to"), icon = Icons.TRANSLATE_V) { d, p, _ -> d.translateByVector(p.elements[0], p.points[0], p.points[1]) },
            // arrays: the interactive generalization of the boltCircle / holePattern macros (OP-6) — the
            // count is structural, so a different count is a different construction, not an edited value.
            // Their geometry slot takes a **whole group** as one operand (`groupOperand`, OP-16), which is
            // why both build from the whole of `p.elements`: one element is the list of one.
            ToolDef(ARRAY_LINEAR, "Linear array", ToolCategory.TRANSFORM, listOf(SlotKind.GEOMETRY, SlotKind.POINT, SlotKind.POINT), minCount = 2, groupOperand = true, replicates = false, preview = Previews::linearArray, help = "Set the number of instances, then click the geometry and two points giving the step vector; every copy follows both. With a whole group selected, clicking any member arrays the whole group.", slotNames = listOf("geometry", "from", "to"), icon = Icons.ARRAY_LINEAR) { d, p, _ -> d.linearArray(p.elements, p.points[0], p.points[1], p.count) },
            ToolDef(ARRAY_CIRCULAR, "Circular array", ToolCategory.TRANSFORM, listOf(SlotKind.GEOMETRY, SlotKind.POINT), minCount = 2, groupOperand = true, replicates = false, preview = Previews::circularArray, help = "Set the number of instances, then click the geometry and the centre; the copies are spaced evenly round it. With a whole group selected, clicking any member arrays the whole group.", slotNames = listOf("geometry", "centre"), icon = Icons.ARRAY_CIRCULAR) { d, p, _ -> d.circularArray(p.elements, p.points[0], p.count) },
            // patterns (OP-23). A pattern is **not** an array: an array copies geometry, a pattern states a
            // rule that later gestures ride, and its members are shared points the copies are built *on*. Both
            // record their own `pattern` step, because a pattern is a named object whose count can be
            // re-stamped — and neither replicates, since what it builds is the pattern itself.
            ToolDef(PATTERN_CIRCULAR, "Circular pattern", ToolCategory.TRANSFORM, listOf(SlotKind.POINT, SlotKind.POINT), minCount = 2, recordsSteps = true, replicates = false, preview = Previews::circularPattern, help = "Set the number of instances, then click the centre and one reference point: the point is repeated evenly round the centre. Anything you build on its members afterwards is repeated round it too — one segment makes every side, one fillet rounds every corner.", slotNames = listOf("centre", "reference point"), icon = Icons.PATTERN_CIRCULAR) { d, p, _ -> d.createPattern(PatternKind.CIRCULAR, p.points[1], p.points[0], p.count) },
            ToolDef(PATTERN_LINEAR, "Linear pattern", ToolCategory.TRANSFORM, listOf(SlotKind.POINT, SlotKind.POINT), minCount = 2, recordsSteps = true, replicates = false, preview = Previews::linearPattern, help = "Set the number of instances, then click the base point and the step vector's end: the base is repeated along that vector. Anything you build on its members afterwards is repeated along it too (a row of holes, one circle).", slotNames = listOf("base point", "step to"), icon = Icons.PATTERN_LINEAR) { d, p, _ -> d.createPattern(PatternKind.LINEAR, p.points[0], p.points[1], p.count) },
            // ----- Measure -----
            // a measurement is a **reading**, not geometry: six of the same number is clutter where one is the
            // answer, so the measure and annotate tools decline the orbit (OP-23)
            ToolDef(DISTANCE, "Distance", ToolCategory.MEASURE, listOf(SlotKind.POINT, SlotKind.POINT), replicates = false, help = "Click two points to measure their distance.", slotNames = listOf("from", "to"), icon = Icons.DISTANCE) { d, p, _ -> d.measureDistance(p.points[0], p.points[1]) },
            ToolDef(ANGLE, "Angle", ToolCategory.MEASURE, listOf(SlotKind.POINT, SlotKind.POINT, SlotKind.POINT), replicates = false, help = "Click a point, the vertex, then another point.", slotNames = listOf("point", "vertex", "point")) { d, p, _ -> d.measureAngle(p.points[0], p.points[1], p.points[2]) },
            ToolDef(LENGTH, "Length", ToolCategory.MEASURE, listOf(SlotKind.MEASURABLE), replicates = false, help = "Click a segment, an arc, an ellipse or an elliptic arc to measure its length. Exact for the first two; a conic's length is computed numerically to a stated tolerance, and the status line says so (OP-15).", slotNames = listOf("curve")) { d, p, _ -> d.measureLength(p.elements[0]) },
            ToolDef(RADIUS, "Radius", ToolCategory.MEASURE, listOf(SlotKind.CIRCLE), replicates = false, help = "Click a circle or arc to measure its radius.", slotNames = listOf("circle")) { d, p, _ -> d.measureRadius(p.elements[0]) },
            ToolDef(COORD_X, "X coordinate", ToolCategory.MEASURE, listOf(SlotKind.POINT), replicates = false, help = "Click a point to read its x coordinate.", slotNames = listOf("point")) { d, p, _ -> d.measureX(p.points[0]) },
            ToolDef(COORD_Y, "Y coordinate", ToolCategory.MEASURE, listOf(SlotKind.POINT), replicates = false, help = "Click a point to read its y coordinate.", slotNames = listOf("point")) { d, p, _ -> d.measureY(p.points[0]) },
            ToolDef(ANGLE_LINES, "Angle (2 lines)", ToolCategory.MEASURE, listOf(SlotKind.LINE, SlotKind.LINE), replicates = false, help = "Click two lines to measure the angle between them.", slotNames = listOf("line", "line")) { d, p, _ -> d.measureAngleLines(p.elements[0], p.elements[1]) },
            // 3D measurements (OP-4): the solid is picked in plan by its footprint hint, like any other
            // solid pick, and the number lands in the panel as a read-only scalar — usable downstream.
            ToolDef(VOLUME, "Volume", ToolCategory.MEASURE, listOf(SlotKind.SOLID), replicates = false, help = "Click a solid to measure its volume.", slotNames = listOf("solid")) { d, p, _ -> d.measureSolidVolume(p.elements[0]) },
            ToolDef(EXTENT_X, "Extent (X)", ToolCategory.MEASURE, listOf(SlotKind.SOLID), replicates = false, help = "Click a solid to measure how far it reaches along X.", slotNames = listOf("solid")) { d, p, _ -> d.measureSolidExtent(p.elements[0], Axis3.X) },
            ToolDef(EXTENT_Y, "Extent (Y)", ToolCategory.MEASURE, listOf(SlotKind.SOLID), replicates = false, help = "Click a solid to measure how far it reaches along Y.", slotNames = listOf("solid")) { d, p, _ -> d.measureSolidExtent(p.elements[0], Axis3.Y) },
            ToolDef(EXTENT_Z, "Extent (Z)", ToolCategory.MEASURE, listOf(SlotKind.SOLID), replicates = false, help = "Click a solid to measure its height along Z.", slotNames = listOf("solid")) { d, p, _ -> d.measureSolidExtent(p.elements[0], Axis3.Z) },
            // ----- Annotate: dimensions (OP-4) — the graphic shows a measurement, and drives nothing -----
            ToolDef(DIM_LINEAR, "Linear dimension", ToolCategory.ANNOTATE, listOf(SlotKind.INPUT_POINT, SlotKind.INPUT_POINT, SlotKind.SIDE), shortcut = 'M', replicates = false, preview = Previews::linearDimension, help = "Click the two points to measure between — an existing one is shared, empty space places a new one — then click where the dimension line should sit (drag it later, or type the offset).", slotNames = listOf("from", "to", "placement"), icon = Icons.DIM_LINEAR) { d, p, _ -> d.linearDimension(p.elements[0], p.elements[1], p.at, p.dofs) },
            ToolDef(DIM_RADIAL, "Radial dimension", ToolCategory.ANNOTATE, listOf(SlotKind.CENTERED, SlotKind.SIDE), replicates = false, preview = Previews::radialDimension, help = "Click a circle or arc, then click where the leader and its radius should sit. An ellipse declines by name — it has no single radius.", slotNames = listOf("circle", "placement"), icon = Icons.DIM_RADIAL) { d, p, _ -> d.radialDimension(p.elements[0], p.at, p.dofs) },
            ToolDef(DIM_ANGULAR, "Angular dimension", ToolCategory.ANNOTATE, listOf(SlotKind.LINE, SlotKind.LINE, SlotKind.SIDE), replicates = false, preview = Previews::angularDimension, help = "Click two lines, then click inside the angle you mean — that sector is what the dimension names.", slotNames = listOf("line", "line", "placement"), icon = Icons.DIM_ANGULAR) { d, p, _ -> d.angularDimension(p.elements[0], p.elements[1], p.at, p.dofs) },
        )

    /**
     * Tools that exist **only to replay older files** (OP-18): a build kept reachable because a step already
     * written down means what it meant, while the gesture that used to produce it has moved on. Resolved by
     * [byId] and so by [Document.toolDef], but deliberately *not* part of [all] — the palette shows [all],
     * so nothing here can be armed, and no new file can name one.
     */
    val legacy: List<ToolDef> =
        listOf(
            ToolDef(RECTANGLE_V1, "Rectangle (as built before)", ToolCategory.CURVES, listOf(SlotKind.POINT, SlotKind.POINT), help = "Four segments over two clicked corners and two derived ones — replaced by the ortho-path rectangle, and kept so older files replay.") { d, p, _ -> d.rectangle(p.points[0], p.points[1]) },
        )

    /**
     * The *built-in* tool of that id, including the replay-only [legacy] ones. Call [Document.toolDef]
     * instead wherever a document is at hand: a user-defined macro is a tool too (OP-6), and only the
     * document knows its own macros.
     */
    fun byId(id: String): ToolDef? = all.firstOrNull { it.id == id } ?: legacy.firstOrNull { it.id == id }

    /**
     * A wall side as it rides a step's `signs=` (the OP-21 extension): the [Justification] ordinal, and
     * CENTER for anything a file does not name — an older or hand-written script that omits a side means
     * the centred default, exactly as an absent `wall` justification always has.
     */
    fun sideOf(sign: Int): Justification = Justification.entries.getOrElse(sign) { Justification.CENTER }

    /**
     * The tool [c] arms, case-insensitively, or null. Only built-in tools have keys: a macro's name is
     * the user's, so assigning it a letter would collide unpredictably with this table.
     */
    fun byShortcut(c: Char): String? {
        val k = c.uppercaseChar()
        if (k == SELECT_KEY) return SELECT
        return all.firstOrNull { it.shortcut == k }?.id
    }

    /** The key that arms [id], for the palette's label. */
    fun shortcutOf(id: String): Char? = if (id == SELECT) SELECT_KEY else byId(id)?.shortcut

    /**
     * Whether a click that hits nothing in a slot of [kind] **states a new point there** — the *input* half
     * of the point-slot law stated on [SlotKind].
     *
     * One predicate, read by the click ([Editor.runToolClick]) and by the snap marker
     * ([Editor.placesAPoint]), so what the cursor promises and what the click does cannot drift apart.
     */
    fun placesPoint(kind: SlotKind?): Boolean =
        kind == SlotKind.PLACE_POINT || kind == SlotKind.POINT || placesPointElement(kind)

    /** …and of those, the ones the tool receives as an [Element] rather than as a `PointRef`. */
    fun placesPointElement(kind: SlotKind?): Boolean = kind == SlotKind.INPUT_POINT || kind == SlotKind.POINT3

    /**
     * Whether a slot of [kind] names a point that **must already stand in the drawing** — the *subject* half
     * of the same law, and therefore what a miss has to say out loud rather than heal by placing.
     */
    fun needsExistingPoint(kind: SlotKind?): Boolean = kind == SlotKind.EXISTING_POINT || kind == SlotKind.ON_CIRCLE_POINT

    /**
     * Whether a slot of [kind] may be left **unfilled** — see [SlotKind.OPTIONAL_POINT] for the whole of
     * what that means at the gesture.
     *
     * A predicate over the kind rather than a flag on the tool, deliberately: which slots a tool has is the
     * table's business, and *whether a pick can be absent* is a property of what the pick is for. A second
     * declaration beside the slot list would be a second table to keep in step.
     */
    fun isOptionalSlot(kind: SlotKind?): Boolean = kind == SlotKind.OPTIONAL_POINT

    /** The generic word for a slot of [kind] — [ToolDef.roleOf]'s fallback when a tool declares no name. */
    fun roleOfKind(kind: SlotKind?): String =
        when (kind) {
            null -> "input"
            SlotKind.PLACE_POINT, SlotKind.POINT, SlotKind.INPUT_POINT, SlotKind.EXISTING_POINT -> "point"
            SlotKind.OPTIONAL_POINT -> "point to ride on (optional)"
            SlotKind.ON_CIRCLE_POINT -> "point on circle"
            SlotKind.CURVE, SlotKind.EXTRACTABLE -> "curve"
            SlotKind.LINE -> "line"
            SlotKind.CIRCLE, SlotKind.CENTRIC -> "circle"
            SlotKind.CONIC -> "ellipse"
            SlotKind.CENTERED -> "circle or ellipse"
            SlotKind.MEASURABLE -> "curve"
            SlotKind.SEGMENT -> "segment"
            SlotKind.GEOMETRY -> "geometry"
            SlotKind.CARRIER -> "leg"
            SlotKind.SIDE -> "side"
            SlotKind.AREA -> "area"
            SlotKind.SOLID -> "solid"
            SlotKind.CHAIN -> "chain, line or closed curve"
            SlotKind.LOFT_PART -> "section, apex or guide"
            SlotKind.POINT3 -> "point in space"
            SlotKind.PATH3 -> "curve in space"
            SlotKind.DRAWN_RUN -> "drawn curve"
            SlotKind.SECTION_CURVE -> "section of a solid"
        }

    /** The glyph a palette button shows for [id] — [SELECT]'s included, which is not a [ToolDef]. */
    fun iconOf(id: String): String? = if (id == SELECT) Icons.SELECT else byId(id)?.icon
}
