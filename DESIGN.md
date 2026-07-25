# ConstructIt — Design Notes

A parametric 2D/3D CAD tool for **mechanical engineering** (not 3D freeform / sculpting).

## Guiding idea

The model is **parametric through construction, not through a constraint solver.**

Geometry is built as a directed acyclic graph (DAG) of *constructions*. Each object is a
pure function of its inputs; inputs are either free parameters or earlier objects. Editing
means changing a parameter or a free object and recomputing everything downstream in
topological order — like a spreadsheet for geometry.

This is closer to **dynamic geometry** (GeoGebra, Cinderella, Cabri) generalized to
mechanical CAD than to constraint-based sketchers (SolidWorks/FreeCAD sketcher).

### Motivating example — perpendicular bisector

```
P1 (free point)  ─┐
P2 (free point)  ─┼─→ C1 = circle(center=P1, r=R) ─┐
R  (parameter)   ─┴─→ C2 = circle(center=P2, r=R) ─┼─→ {X1,X2} = intersect(C1,C2) ─→ line(X1,X2)
```

Key insight: the "same radius" is **not a constraint** — `R` is a single shared parameter
node feeding both circles. Sharing a node *is* the equality. Many things usually expressed
as constraints become **shared inputs or shared sub-constructions** here.

## Properties we get for free

- Acyclic by construction (can only reference already-existing objects) → no solver needed.
- Deterministic, fast, no convergence failures, no under/over-constrained states.
- Teachable; matches the "how do I *construct* this" mental model.

## What we trade away

- Some intents are natively constraints (post-hoc equality, mutual tangency between things
  not built to be tangent) and become awkward as constructions.
- Stance: design the workflow so equality/tangency is achieved *by construction*
  (shared parameters, tangent-construction primitives), not asserted after the fact.

## Construction primitive algebra (draft, keep small & closed)

- **Points:** free/draggable, from coordinates, relative, midpoint, intersection
- **Curves:** line/ray/segment, circle (center+r, center+point); later arcs/conics
- **Relational:** perpendicular/parallel through point, tangent, angle bisector
- **Transforms:** translate/rotate/mirror/scale (each parameterized)
- **Measurements:** length, angle, distance → numeric values that can feed back as parameters
- **Macros / custom constructions:** encapsulate a sub-DAG as a reusable tool,
  e.g. `perpBisector(P1, P2) → line`. First-class from day one.

## Node graph data model (OP-5 — RESOLVED)

One uniform, **strongly typed** dataflow DAG. Nodes produce typed values; numbers and
geometry live in the *same* graph, so measurements (OP-4) and expressions (OP-7) are just
ordinary nodes.

### Value types
- **v1 / 2D:** `Scalar`, `Angle`, `Point`, `Direction`, `Line`, `Ray`, `Segment`, `Circle`,
  `Arc`, and **`PointSet`** (ordered solution set — see below).
- **later / 3D:** `Point3`, `Plane`, `Axis`, `Sketch`, `Solid`, `Face`, `Edge`, …

### Node structure
- `id` — stable identity (references, files, undo)
- `op` — operation (`FreePoint`, `Parameter`, `LineThrough`, `CircleCenterRadius`,
  `IntersectCC`, `Select`, `Midpoint`, `Measure.*`, `Expr`, …)
- `inputs` — ordered references to other nodes' outputs (the edges)
- `literals` — stored constants for source nodes (free-point coords, parameter value/expr)
- `output` — cached typed value (**exactly one per node**)
- `validity` — valid / invalid (OP-3)

### One output per node — intersections via a solution-set type
Chosen over both "single-output + selector-on-node" and "multi-output ports":
- Intersection ops emit an **ordered solution set** value (e.g. `IntersectCC → PointSet`).
  The canonical ordering is fixed by the OP-1 orientation rule, so index/sign is stable.
- A separate **`Select(set, sign) → Point`** node picks the branch.
- **Benefits:** every node has exactly one output (uniform, simple edges); the set is
  computed **once** and shared by multiple `Select` nodes (efficient); clean separation of
  *solve* vs *choose branch*. The OP-1 branch selector lives on the `Select` node.
- **Cardinality via validity:** empty set → `Select` invalid (propagates); tangency
  (1 element) → both signs return the same point.
- **Generalizes** to higher-order intersections (conics: up to 4) via the same ordered set.

### Evaluation
- Topological order with **dirty-marking** for incremental recompute: a free-point drag or
  parameter edit mutates a literal, marks the node dirty, and recomputes only the affected
  downstream cone. Outputs are cached.

### How the coupled points attach (details deferred to their own OPs)
- **OP-4 (measurements):** `Measure.Distance(P1, P2) → Scalar` is an ordinary node; its
  `Scalar` output feeds any `Scalar` input. Graph stays acyclic.
- **OP-7 (expressions):** an `Expr` node takes `Scalar`/`Angle` inputs → `Scalar`.
- **OP-6 (macros):** a subgraph with designated input/output ports, instantiable as one
  composite node.

## Intersections — branch selection (OP-1 — RESOLVED)

`intersect(circle, circle)` yields 0, 1, or 2 points. **Decision: deterministic,
orientation-based branch selection; the model stays a pure function of its parameters.**
(Continuity tracking is explicitly rejected as the core mechanism because it is
path-dependent and would break reproducibility/undo/reload.)

- Intersection ops emit an **ordered solution set** value (see OP-5, `PointSet`); a separate
  **`Select(set, sign)`** node holds the discrete **branch selector** (sign `+1/-1` or index).
- The set's canonical ordering is fixed by a geometric rule, continuous everywhere except at
  genuine degeneracies:
  - **circle–circle:** side of the directed line `center(C1) → center(C2)`
    (sign of a cross product). Stable under any translate/rotate/scale.
  - **line–circle:** order of the two hits along the line's own direction (first/second).
  - **line–line:** unique — the set has one element.
- **Creation UX:** clicking near one intersection sets the `Select` sign to that side.
  Natural to create, stable to recompute.
- **Tangency:** the two solutions coincide → both signs return the same point.
- **No solution:** empty set → `Select` becomes invalid → hidden + flagged; dependents
  propagate invalid (see OP-3). No special-casing required.
- **Optional later hybrid:** during interactive drag *only*, continuity tracking may be used
  as a heuristic to auto-update the discrete selector — but the discrete selector is always
  persisted, so the stored model stays pure. Does not compromise the core.

## Platform & deployment architecture (OP-10, OP-12 — RESOLVED)

**Language/platform (OP-10):** **JVM, Kotlin** — specifically **Kotlin Multiplatform**, so one
engine codebase runs on JVM (server/desktop) *and* in the browser (Kotlin/JS; Kotlin/Wasm as
it matures). No hand-written JS. (Chosen after ruling out C/C++, TS/JS, and Rust — the last on
error-model ergonomics. Kotlin over plain Java for the shared browser story.)

**Layered architecture (OP-12):**
1. **Core engine** — a *pure Kotlin library* (model, type system, DAG eval, geometry ops,
   later the Manifold binding). **Zero** dependency on any UI, server framework, or TopLogic.
   This is the load-bearing rule: it makes the deployment/shell choice a later, reversible
   packaging decision.
2. **Client** — the interactive canvas runs **client-side in the browser** (non-negotiable for
   live drag-with-recompute at interactive rates). The engine is compiled to the browser via
   Kotlin Multiplatform; the canvas is **hand-rendered** (Canvas2D/SVG for 2D → WebGL for 3D).
   Surrounding chrome (toolbars, property panel, tree) also Kotlin.
3. **Persistence/format** — a construction is a **document** in a clean serialization format;
   this is the contract between engine and any shell.
4. **Shell — deferred/optional.** Start as a **fat browser client** (or a desktop JavaFX
   harness for fastest phase-1 iteration) with **file-based** persistence. Phase-1 (2D) needs
   no kernel and no server.

**Client-stack rationale:** Kotlin uniquely satisfies the shared-engine goal that justified
the JVM. GWT/J2CL was justified *only* by TopLogic alignment — and **TL-as-shell is an
explicit non-requirement** (a valid embedding option that must not guide the decision).
Flutter/Dart is the best pure-UI toolkit but would sacrifice the shared engine (separate
language + RPC boundary); the app is **canvas-centric, not widget-centric** (the canvas is
hand-rendered regardless), so Flutter's widget edge buys the least here.

**Open embeddings / later options (non-driving):**
- **TL module** as an enterprise shell (auth, projects, persistence, collaboration) *around*
  the canvas — managing constructions **as documents, not per-node model objects** (which
  sidesteps the fine-grained-node-graph concern). Valid, not a requirement.
- **Heavy 3D compute server-side** in phase-2 (Manifold via JNI on the JVM) if in-browser mesh
  booleans get too heavy — the shared engine makes this a deployment toggle, not a rewrite.

## Testing strategy (enabled by the architecture)

The **pure headless engine** + **SVG output** means tests can build a model *in code*,
evaluate, render, and assert — no browser, no UI. Crucially, the model is **deterministic**
(no solver, pure recompute, deterministic `Select` — OP-1), so output is reproducible and
golden/snapshot testing is reliable (a concrete dividend of the no-solver paradigm).

Two assertion levels, use both:
1. **Value-level (workhorse):** assert on evaluated node outputs — coordinates, distances,
   angles, validity states, branch chosen, unit/dimension results. Precise; robust to
   rendering changes. E.g. perpendicular bisector is equidistant; bolt-circle holes at the
   expected radius/angles.
2. **SVG golden/snapshot:** render to SVG, diff against a stored reference. Exercises the whole
   pipeline incl. rendering; artifacts are **human-inspectable**.

Requirements this imposes (design in from the start):
- A clean **programmatic construction/builder API** (also a scripting surface + documentation).
- A **canonical, deterministic SVG serializer**: fixed decimal precision, stable element
  ordering, stable ids — else floating-point formatting / iteration order causes spurious diffs.

The test cases double as the model's **worked spec examples** (perpendicular bisector,
rounded-rect macro, bolt circle, hole pattern). Same pattern extends to phase-2:
construct in code → export STL/3MF → assert manifold / volume / bbox.

## Canvas / editor architecture (implemented)

Elastic layering — everything except pixel-drawing and native events is pure Kotlin
(`commonMain`, portable to any target); only the last two layers are platform-specific/thin:

```
Platform shell (thin)  — jsMain: DOM toolbar/tree, native event plumbing, repaint
DrawTarget (interface) — screen-space draw ops; impls: SvgDrawTarget (tests), BrowserCanvas (jsMain)
InteractionController  — Editor: tool state machine, hit-testing, drag; abstract pointer events
Camera                 — world<->screen (pan/zoom about cursor)
SceneRenderer          — world->screen projection, arc tessellation, line/ray clipping, grid
Document               — retained construction + display metadata; enumerable; the file-format seam
Engine                 — Construction DAG + Evaluator (unchanged)
```

- **Headless-testable:** interaction is pure + event-driven, so gestures are simulated in
  `commonMain`/`jvmTest` and the scene snapshotted via `SvgDrawTarget` — same golden discipline.
- **Browser E2E:** Playwright (Java) drives system Chrome against the built distribution,
  gated behind `-De2e=1`; screenshots under `build/e2e/`.
- **To move to another platform** (desktop, etc.): add one `DrawTarget` impl + one event
  adapter; layers Document..InteractionController are untouched.
- Run locally: `./gradlew jsBrowserDevelopmentRun`. MVP tools: Select/drag, Point, Line,
  Circle, Intersect; live parametric recompute on drag; pan + zoom; grid + axes.

### Handles — dragging and typing are one operation (OP-13 — RESOLVED)

**There is no conceptual difference between dragging something and entering a number for it.** Both
are a write to the same free source nodes; a value entered numerically can afterwards be changed by
dragging, and vice versa. So the editor has exactly one notion — a **`Handle`**: the grabbable DOF of
an `Element`, with a *continuous* binding (`drag`) and a *discrete* one (`fields`).

Consequences, which is why this is worth stating as a principle rather than a UI detail:

- **No geometry is reachable by mouse but not by number**, or the reverse. A hidden internal
  parameter (the `t` of a point-on-line, the angle of a point-on-circle, an ortho vertex's
  coordinates) is a bug in the model, not an implementation detail — it means a DOF exists that the
  user can only reach by dragging.
- **A `HandleField` is a re-parameterization, not a new node.** It is affine in exactly one free
  node — a coordinate, a distance from an anchor, an angle — so writing it inverts by exact
  arithmetic. Nothing is asserted and solved; no DOF is consumed by typing a value; there is no
  "dimensioned" vs "free" state for an edge.
- **This is what resolves "which end moves?"** for a quantity spanning two vertices, like a leg
  length. There is no drag gesture called *drag the length* — there are drags of each endpoint — so
  there is no single length field either. The field belongs to the handle that moves: a leg is
  editable from either end, as two fields writing different nodes. Ambiguity dissolves instead of
  needing an anchor picker.
- A field whose node is **driven** (welded, attached, shared by a loop closure) reports itself
  unwritable, which is the same answer dragging gives: that DOF is gone by construction.
- Deliberately *not* called a constraint. A handle only writes free source nodes; what stays
  invariant (a leg's axis, a point's curve) is invariant because of the nodes it does *not* write,
  which are shared with the geometry that must follow.

### Editor tool roadmap

**Implemented** (data-driven `ToolDef` registry, categorized palette; a tool declares an ordered list
of scalar inputs taken from the panel; existing-only slots never create stray points; scalar names are
auto-uniquified so wiring is unambiguous):
- Points: Point, Midpoint, Intersect, Project-to-line, Point-on-circle & Point-on-line
  (1-DOF draggable), Point-at-distance (0-DOF, side by click), Point (x, y) (no clicks — two scalar
  inputs), Key points (sub-entity extract), Join points (weld two points into one — see *Welding* below)
- Curves: Line, Segment, Ray, Circle (c,pt), Circle (c,r), Circle (3pt), Arc (3pt),
  Arc (centre,ends), Concentric circle, Rectangle, Rounded rectangle (the `roundedRect` macro as a
  tool), Regular polygon
- Construct: Perp/Parallel-through, Perp-bisector, Angle-bisector, Parallel-at-distance,
  Tangent-from-point, Tangent-at-point (1 click), Fillet, Chamfer, Outer/Inner common tangents
- Transform: Mirror, Rotate, Scale, Translate-by-vector, Linear array, Circular array
- Measure: Distance, Angle (3pt), Angle (2 lines), Length, Radius, X/Y coordinate
- Parameter **wiring** (reduce DOF; equality by shared reference), measurement-as-scalar-input.
- Any `LINE` slot also accepts a segment/ray (carrier line).

#### Welding (joining two points) — point-level wiring

Joining two otherwise-unconstrained points is the **point-level analog of parameter wiring**,
not a constraint to be solved. A free point is a `SourceNode` holding a `PointValue`; giving it
the same optional `boundTo` that `ParameterNode` already has lets us **weld** one point (the
*alias*) onto another (the *master*): `alias.boundTo = master`. Because binding mutates the
alias node *in place*, every reference already pointing at it transparently follows the master —
no immutable input list is ever rewired. The alias loses its 2 DOF, is hidden so the pair reads
as a single dot, and the weld is fully reversible (`unweld` restores an independent free point
at the current position). Cycles are rejected via the existing `dependsOn` check.

Two interactions expose it: **drag-to-weld** (dragging a free point within snap tolerance of
another shows a magnet *halo*; releasing welds — the GeoGebra-like fluid path) and the **Join
points** tool (click the point to keep, then the one to weld — precise/discoverable). Master
survives; the drop target wins, so the resulting position is never ambiguous.

*Generalization — **drag-to-attach** (implemented for lines & circles):* the same magnet also
snaps onto **curves**. Dragging a free point onto a line/segment/ray attaches it as a
**point-on-line** (2→1 DOF), onto a circle as a **point-on-circle**; it then slides along the
curve. Realized on the same substrate: the point's `SourceNode.boundTo` is welded onto a fresh
`pointOnLineAt`/`pointOnCircle` node (so every reference follows) and its `Element` flips to
`ON_CURVE` in place with the matching `Handle`. Curves built *from* the point are
excluded (cycle check), and the point's own endpoints therefore never self-attach. Points win
over curves when both are in snap range. `unweld` detaches either kind back to a free point.
Remaining: attach onto **arcs** (needs the arc's carrier circle) and onto a **derived/intersection
point** (alias, 2→0 DOF).

#### Snapping while placing (`Snap`)

Attaching after the fact is not enough: a click that *lands on* geometry should link to it there and
then. One resolver serves every point-producing click — tool slots and ortho-path clicks alike — in
CAD osnap precedence: an existing **point** (reused, no new node) → the **intersection** of the two
curves under the cursor (a derived point, 0 DOF, keeping only the branch clicked as its persisted
`Select`) → the nearest attachable **curve** (an on-curve slider, 1 DOF) → the **grid** → the raw
cursor. Hovering previews the snap (marker + status line) and Alt places freely.

The point is the dependency, not the alignment: coinciding by coordinate alone would come apart the
moment the other geometry moved, which is what a construction exists to prevent. Hover-time
intersections are computed with `GeomMath`, so previewing never touches the graph.

For an **ortho path**, every vertex links, not just the first — the same weld/attach operations the
drag magnet performs, so a connection made while drawing is the same construction as one made
afterwards. Three consequences fall out of the shared-coordinate model:

- A vertex *after* the first cannot bend its leg to reach the cursor's projection, so a curve snap
  resolves to where the **leg** meets the curve (`Snap.axisCrossing`) — which is also the endpoint
  `attachOrthoEndpointToCurve` derives, so the preview matches the result.
- A path's **start** has no leg yet, hence no own/shared coordinate split to exploit: it is pinned to
  a slider along the curve, and the first leg then shares that driven coordinate, which is what keeps
  the leg axis-aligned while the start slides.
- Reaching other geometry **finishes the run** — the open analogue of closing a loop by clicking the
  start. The path being drawn is excluded from its own snap targets, since attaching a path to its own
  leg could only ever be refused as a cycle.

#### Tool inputs — scalar slots, and the structural count (as built)

A tool is a *declaration* (`ToolDef`), so finishing the everyday tool set was mostly a matter of the
declaration being able to say what these tools need. Two extensions, both deliberately generic:

**Several scalar inputs.** `ToolDef.scalars` is an ordered list of **named** inputs (`listOf("x", "y")`)
where there used to be a single `scalar = true` flag consuming the active parameter. Collecting them
needed no second mechanism: picking a parameter row appends to a short ordered memory of panel picks
(`Editor.activeScalar`'s setter), and a tool needing *k* scalars consumes the **last k in pick order**.
So a one-scalar tool means exactly "the active parameter" as it always did, a two-scalar tool means "x,
then y", the status line names what is still wanted (`click a parameter … for y (x picked)`), and
correcting a mis-pick needs no reset gesture — pick the right ones again. The `build` lambda receives the
list, so no scalar is privileged. Persistence needed only that the `tool` step's `scalar=` argument became
an ordered list of quoted names, which for a single scalar is byte-identical to what it wrote before.

*Point (x, y)* also has **no geometry slots at all** — both its inputs are scalars, so the click only
says "now". That falls out of the same completion path (an empty slot list is complete immediately)
rather than being a special case in the controller.

**A structural count.** A polygon's side count and an array's instance count decide **how many nodes
exist**, exactly as an ortho path's vertex count does. It is therefore a property of the *gesture*, not a
parameter: `Editor.count` is a tool option like the wall justification (there is no slot to click it
into), it is recorded in the tool step as `count=n`, and replay re-runs the tool with it verbatim — the
loader's element-count check then vouches for it. Changing a count later means using the tool again.

That is the honest answer here, and the alternative is worth naming: a **live** count — a parameter that
adds and removes copies as you edit it — cannot be one node per copy, because the number of nodes would
depend on a value. It needs a *compound* value (one node yielding a list of geometry) plus accessors into
it, i.e. a new value kind and an addressing scheme for "the k-th instance" — OP-8's territory, and a
possible OP of its own later. It is deliberately not smuggled in here: a half-live count would make the
document stop being a pure function of its parameters at exactly one point, which is the property
recompute, undo and reload all rest on.

**Shapes by construction.** The new tools add no ops and no geometry code — they compose what was there,
which is the test of whether the algebra is closed:

- *Rectangle* clicks two diagonal corners and derives the other two as `pointXY(x(a), y(c))` and
  `pointXY(x(c), y(a))`. It is rectangular **by construction** — dragging or typing either clicked corner
  reshapes the whole figure, and no gesture can shear it, because the shear is not expressible. Same trick
  as an ortho leg, one dimension up.
- *Rounded rectangle* is the existing `roundedRect` macro (OP-6) with its centre = the clicks' midpoint
  and width/height = their coordinate spans, so the two clicks keep driving it; the radius is an ordinary
  parameter, so editing it re-rounds live with no node replaced.
- *Regular polygon* is the general `rotate` applied *count-1* times to the clicked vertex.
- *Chamfer* shares the fillet's clicked-quadrant sign resolution (a stored discrete choice, OP-1) and is
  otherwise `intersectLL` + `Select` for the corner and `pointAlongLine` for each bevel end.
- *Arrays* are a **fan, not a chain**: copy *k* is `k·v` (or `k·360°/n`) from the original, so no copy
  depends on a sibling, every copy recomputes straight from the original, and the copies are the
  original's dependents — deleting it takes them (OP-18).

**Remaining — build order (all planned; ordered, not deferred):**

1. **Tool completions — done.** Point-from-coordinates, Chamfer, Rectangle, Regular polygon and
   Rounded-rectangle are built, together with the two input-model extensions they needed: an **ordered
   list of scalar inputs** per tool and a **structural count** (see *Tool inputs* above). Area
   measurement fell out of OP-14 (`loopArea` / `regionArea`) earlier. Remaining here: nothing —
   further tools are additions to the `ToolDef` table, not work on the model.
2. **Editing & persistence — done.** Save/load (OP-18, see *Document format* below), plus undo/redo
   and dependency-aware delete. Undo is a stack of **document-format snapshots** — one per committed
   user-level operation (a tool build, a drag's release with its welds/joins, a typed write, a whole
   path finish, a break, an opening, a delete, a panel edit), captured at the single
   `Editor.checkpoint()` seam and restored by replaying the script into a fresh document. A snapshot
   is pushed only when the saved text changed, so cancelled and no-op gestures are not steps; a new
   edit clears redo; the stack is capped at 100. The originally sketched prefix-replay undo ("an undo
   step is a prefix of the journal") was **rejected on contact**: drags and typed values mutate
   source-node literals without adding steps, so journal length does not delimit an operation — the
   saved text, which restates those literals, does. Delete's unit is the **step**: the step that
   created the selection is dropped together with every later step that transitively references
   anything the dropped steps made — explicit element/scalar arguments, plus two implicit chains: a
   path's topology steps (replay coalesces straight-on legs and a wall's face count follows the leg
   count, so per-vertex surgery would replay as a loader count mismatch) and wall openings (an
   opening step's creations are the wall's *regenerated* faces, so any dropped wall/opening step
   drops the later opening steps). The filtered journal is re-saved and replayed, so the survivors
   are exactly what still constructs, `save → load → save` stays byte-stable afterwards, and the
   status line reports what else went ("deleted e3 and 2 dependents").
3. **Productivity.** **Arrays are done** — linear (repeat N along a vector) and circular (evenly round a
   centre), the interactive generalization of the bolt-circle/hole-pattern macros: each copy is a
   transform node over the original (a fan, not a chain), any element kind works with no per-kind case,
   the copies recompute live and delete as the original's dependents, and the count is structural (see
   *Tool inputs* above). Remaining: snap modes — key points of *derived* geometry (endpoint/midpoint/
   quadrant, which need the derived point materialized, not just its coordinate) and arcs (no carrier
   circle yet); drag-to-attach onto **arcs** and onto **derived points** (the two cases the weld
   magnet doesn't yet cover — see *Welding*).
4. **Selection & grouping (OP-16).** **Multi-select, flat named groups and placed groups are done** — a
   selection set with a primary element, a marquee (panning moved to middle/Space+drag), bulk
   hide/delete, `group` steps that survive save/load and stay consistent when a member is deleted, and
   a **frame**: placing a group retrofits the free points it owns to frame-relative form, after which
   moving the group is one literal write on the frame (drag, or typed x/y/angle) and its derived
   geometry follows for free. Remaining: **relocate-origin / re-parent / constructed frames (mates)**,
   group → macro promotion, and capturing an **ortho path's** shared coordinate nodes under a frame
   (the rotated project frame — walls do not yet follow a frame, and say so).
5. **Result layer (OP-14) — done end to end.** Trim ops, `Loop`/`Region`, areas, the *Outline* tool,
   derived scaffolding + a dim toggle, and cubic Béziers (OP-15) taking part in a boundary. Remaining
   here: **rework the wall as an output feature** (OP-21), regions with holes from traced outlines
   (only single loops are traceable so far), and parametric spline trimming.
6. **User-defined macros UI.** Record a sub-construction, designate inputs, get a reusable
   tool (OP-6 `Macro` machinery exists in the engine; needs the record/parameterize UI). The
   headline capability of the paradigm. Shares its dialog with group creation (OP-16).
7. **Dimensions & annotations — done.** Linear (aligned), radial and angular dimensions, each a
   `ToolDef` in an *Annotate* category and each an element whose value **is** an ordinary measurement
   node (OP-4). See *Dimensions* under Measurements below. Remaining here: leaders with free text,
   baseline/chain dimensions, and dimensioning a wall footprint (which wants the footprint accessors
   listed under the architectural next steps).
8. **Splines (OP-15).** The general `CurveValue` refactor, then control-point Bézier/B-splines with
   *constructed* control points. Independent of 1–7; slot it wherever it fits.

### Document format — the file is a construction script (OP-18 — RESOLVED)

A drawing is saved as **the sequence of steps that built it** (`DocumentFormat`), and loading replays
them. Not a node dump: because a step re-runs the code that created it, everything derived comes back
for free, and nothing synthetic is stored —

- **no node kinds.** A step rebuilds its own sub-graph, so no op needs a serialized name or a rebuild
  path (~50 ops in `Construction` would otherwise each need both).
- **no handles, styles, or path/wall structure.** All of it is created by the methods that create the
  geometry, hence recreated by replay. Asking whether handles must be stored is the right question:
  they must not be, and choosing this format is what makes that true.
- **no separate values section.** A step's positional literals are written as the *current* value of
  what that step introduced, so a dragged point is saved where it now is. This keeps the file purely a
  construction, with no addressing scheme for internal nodes leaking into it. Literals that encode a
  *choice* rather than a state — which side of a line, which intersection branch — are kept verbatim,
  since replay must make the same choice.

One `tool <id>` step covers every tool, replayed through the same `ToolDef.build` the click ran, so
the format needs no per-tool case and new tools round-trip for free. Its arguments are all generic in
the same way: `pts=` / `els=` / `clicks=` for the picks, `scalar=` as an **ordered list** of the panel
scalars the tool consumed, `dofs=` for a tool's own degrees of freedom (a dimension's offset), and
`count=` for a structural count (how many copies or vertices were built — see *Tool inputs* above). Elements are named
script-locally (`e1`, `e2`, …) by the step that creates them, so the file does not depend on runtime
id generation, and a step that creates a different number of elements than the script declares is a
**load error** rather than a silently different drawing.

The load-bearing test is `save → load → save` byte-equality: it catches a step that fails to replay, a
literal that was not restated, and any drift in naming, in one assertion.

```
constructit 1
point 30,-60 -> e1
point 30,60 -> e2
tool segment pts=e1,e2 clicks=30,-60;30,60 -> e3
orthostart 30,10 -> e4
attachortho e4 e3
orthovertex 429.25,14.75 -> e5,e6
```

A leg *extension* (a step continuing the previous leg's axis) is deliberately **not** a step: it
changes no topology, only a value, and values already travel with the step that introduced the node.

A step need not create geometry: `group "kitchen" els=e1,e3` records flat-group membership (OP-16) by
element name and declares nothing. Membership is *state*, so the step is written with the members the
script still declares — which is how deleting a member leaves a consistent group, and how a group whose
members are all gone leaves no step at all.

`place "kitchen" at=30,50 angle=15deg` is the same kind of step one level up (OP-16 step 2): it names a
group and restates its frame, and replay re-runs the same retrofit. The members' `point` steps keep
restating **world** positions — they replay before the placement, which then derives the local
coordinates from world position and frame — so a placed group adds no local coordinates and no node
names to the file, and a `place` step whose group step is gone goes with it.

### Junctions own the freedom at a meeting point (OP-20 — RESOLVED)

Repeated reports of asymmetry — a leg draggable on one side of a junction but not the other, then a
corner with two degrees of freedom facing one with one — all had a single cause, and it was not the
individual gestures being fixed one at a time.

**The count was always right; the attribution was order-dependent.** A horizontal run ending on a
slanted segment with a vertical run hanging off that junction has three DOF: the junction slides along
the segment, and each run has a length. But the attach derived one of the endpoint's coordinates *from
the other*, which quietly handed the shared DOF to whichever run connected first. Every later arrival
inherited none. The editor exposes attribution, so the drawing behaved asymmetrically although the
geometry was not.

So a meeting point is now a **`Junction`**: an object that owns that point's freedom — one parameter
along a curve, or its two coordinates when it is a plain point — with *everything* meeting there bound
to it. No participant owns the shared freedom, and all of them reach it the same way, through the
junction's own handle, one structural hop away.

- **Dragging** a coordinate the corner does not own is delegated to the junction — but only *that
  coordinate*. A corner with one coordinate of its own asks the junction to place the other; a corner
  that owns nothing hands over the whole cursor, so the junction follows as closely as its curve allows;
  a *leg* asks the junction to place one coordinate exactly, so the leg lands under the cursor rather
  than at a projection of it.
  - Handing the whole cursor over whenever *either* coordinate was driven was a real stability bug: in a
    cross of four runs welded at one centre, pulling an outer corner along its own arm dragged the
    shared centre sideways after it and collapsed the figure. Per-axis delegation makes all four arms
    behave identically — along the arm lengthens it, across it moves the centre.
- **Typing** reaches exactly as far (OP-13): `Junction.place` solves for the junction's own parameter in
  closed form per curve kind — a line is affine in it, a circle has two solutions and the nearer is
  kept — so a driven coordinate is derived but *not* read-only.
- **A junction can own nothing**: welded to a derived point, the meeting place is fixed by construction.
  That is the one honestly immovable case, and it explains itself.
- **A connection is refused when it would be circular**, and the test asks about *what the connection
  binds*: for an ortho corner that is the **masters** of its two coordinate chains, not the corner's own
  point node, which sits downstream of them. Asking about the point let a real cycle through — and a
  cyclic graph is not a wrong drawing but a dead one, since `Evaluator` recurses until the stack dies. In
  the cross above, the first arm *introduced* the centre's y, so dropping that arm's far end anywhere near
  the figure (66 of 154 drop positions) welded it onto a point derived from itself and killed the editor;
  so did continuing that arm through the centre while drawing. One predicate now serves every path that
  can bind — weld, attach, path-vertex snap — *and* the drag magnet, so no halo offers a join that release
  would refuse; instead the status line says which point already follows the one being dragged.
- Attaching now **projects** the endpoint onto the curve — the same landing spot the drag magnet
  previews — where the old scheme slid it along one axis to meet the curve.

This supersedes an earlier attempt that had each handle search the graph upstream for a free DOF and
invert numerically. That worked, but its candidate choice was itself order-dependent — papering over
order-dependence with an order-dependent heuristic — and it assumed affine relationships, so it would
not have held on a circle. Junctions fix the attribution instead of compensating for it, and need no
probing at all.

### Break and join legs — topology by gesture (OP-19 — RESOLVED)

Two editing operations on an ortho path, inverses of each other. *Leg* here means a segment of an
ortho path, not the architectural wall (a `ThickPath` built over one).

- **Join** (done) — dragging a leg perpendicular until the adjacent perpendicular leg shrinks to
  roughly zero removes that leg and makes the two legs it separated into one. Committed **on release**,
  with a status hint while the jog is flat.
  - Only the **dragged segment's own ends** are considered. Scanning the whole path meant a jog left
    flat on purpose — a fresh break not yet pulled open — was joined away by an unrelated drag
    elsewhere on the same path. Both ends can flatten in one drag (reverting a section broken out
    twice), so up to two joins happen per release.
  - The joined run lands on the **stationary** half's value, so the dragged section fits to what it was
    aimed at. Left to the binding direction alone this was arbitrary: right when the dragged half
    happened to be the follower, wrong when it was the one being followed.
  - Feedback while dragging: the corners a release would remove are crossed out on the canvas and
    counted in the status bar, and **Alt** suppresses the join (the same modifier that places clicks
    raw — in both cases it means "leave the model as I put it").
  - Live merging was the plan, to keep the drawing looking like the model. It turns out there is
    nothing to fix: a **zero-length jog is already visually identical to a joined run**, so the drawing
    never shows anything the model is about to stop being. Merging mid-drag would in fact make the
    gesture *worse* — the drag holds the far leg's own DOF, so after a merge the same drag would move
    the whole run instead of just the half that was grabbed, and dragging on through the flat position
    would no longer re-open the jog. Deferring to release keeps the gesture's meaning fixed and needs
    no restore-the-jog state at all.
- **Break** (`Tools.BREAK_LEG`, done) — click a leg to split it there, inserting two vertices with a
  **zero-length perpendicular** leg between them, so the drawing does not change shape. The jog then
  opens by dragging either half. Works in either binding direction, so a loop's **closing** leg breaks
  like any other: there the *near* endpoint is the one following, so the jog is introduced on that side
  and the roles mirror. Leg axes are stored per leg (`OrthoPath.legAxes`) rather than derived from a
  vertex's introduced coordinate, because that derivation assumed every leg was drawn forward.

This is the same shape as **drag-to-weld** for points: a threshold-triggered topology edit committed
by the gesture. It is *not* continuity tracking (which OP-1 rejects for branch choice) — the result is
recorded structurally and reloads deterministically.

**Why this forces the coordinate representation to change.** Under the shared-coordinate model, a
maximal straight run shares *one* coordinate node across all of its vertices — that is exactly what
keeps it straight. Opening a jog means the two halves must hold *different* values, so one side needs
an independent node; and a vertex's `pointXY` inputs are never rewired (OP-5). Neither existing
endpoint can therefore acquire a new coordinate, and a newly created vertex cannot help: every leg
touching an old endpoint is pinned to that endpoint's node. Break is simply not expressible.

The fix is to **bind rather than share**: every vertex owns its own `x` and `y`, and a leg is
axis-aligned because one endpoint's coordinate is `boundTo` the other's. Geometry is unchanged (a bound
node evaluates to its master), but a *binding can be re-pointed in place*, which is precisely the
freedom sharing lacks:

```
run V0..V3 straight:     V3.y -> V0.y
break at x = m:          M(y -> V0.y, x = m)      N(x -> M.x, y free = V0.y's value)
                         V3.y re-pointed: V0.y  =>  N.y      (the jog can now open)
join (collapse):         V3.y re-pointed: N.y   =>  V0.y     (M, N dropped)
```

`closeOrthoPath` already works this way — it binds the last vertex's coordinate to the start's and
redirects the drag to write the master — so unifying on bindings removes the second mechanism rather
than adding one. Drags then write the master of a binding chain, which is that redirect generalised.

Scope: both operate on **interior** legs of the run. Collapsing an *end* leg would shorten the path
rather than join two legs — a different edit — and the closing leg of a loop is refused for the reason
above. Repeating the pair leaves both steps in the journal, which replays correctly: the break rebuilds
the jog and the join collapses it again.

Two things a join has to carry, if the carrier feeds a thick path: `ThickPath` holds a fixed vertex
list and must derive from its carrier instead, and intervals address their leg by *index* and measure
position from the leg start — a merged leg starts further back, so an interval on the second half must be
re-measured to keep its **absolute** position. Neither is done; a join on a thickened carrier is
therefore not yet supported.

## Validity & undefined propagation (OP-3 — RESOLVED)

- Every node has a **validity state** (valid / invalid).
- A node is *invalid* when its construction yields no result — e.g. an empty intersection,
  or degenerate inputs (concentric circles, zero-length direction).
- Invalidity **propagates transitively** through the DAG: any node depending (directly or
  indirectly) on an invalid node is itself invalid.
- Invalid objects are **hidden and flagged** in the UI; their definitions are **retained**.
- The model **heals automatically**: when inputs return to a valid range, nodes recompute and
  reappear. No manual repair, no deletion.

## Macros / custom constructions (OP-6 — RESOLVED)

A macro is a **subgraph** with typed **input ports**; it is a reusable, function-like
construction (e.g. `perpBisector(P1: Point, P2: Point)`). Phase-1 feature.

### Definition — by example
Build the construction concretely, then designate which internal nodes are the **inputs**
(bound to arguments at instantiation). No separate macro editor required. Type-checked
against the strong type system (OP-5), which also drives context-sensitive tools.

### Instances — reference/composite with derived path-IDs
- An instance is a **composite node** `{macroId, argument bindings}` — a real *function*.
  Editing the definition **propagates to all instances** (enables standard-part libraries).
- **No output declaration.** Only inputs are designated. The instance is a **namespace**:
  every internal node is addressable via a **qualified/path ID** `M/n1`, `M/n2`, …; any
  outside construction may reference `M/nk`. Nesting composes paths: `M/n3/m2`.
- A macro-instance node yields a **group ("drawing") value**; `M/nk` is a **structured
  accessor** into it — the *same* shape as `PointSet` + `Select`. One consistent principle:
  compound values with accessors; primitive nodes stay single-output.
- **Implementation = virtual addressing, not copying:** resolving `M/nk` evaluates
  definition-node `nk` under `M`'s bindings, cached per `(instanceId, internalNodeId)`. No
  duplicated nodes → edit-propagation is automatic.

### Transparent groups (encapsulation trade-off)
- Because any `M/nk` is referenceable, external constructions can depend on a macro's
  *internals*. Macros are **transparent groups, not black boxes**. Sound as long as internal
  IDs are **stable identities** (not positional): while `nk` persists across a definition
  edit, `M/nk` stays valid; deleting `nk` makes external refs to `M/nk` invalid (OP-3).

### Purity — no per-instance overrides
- An instance is a **pure function of its arguments**. You may *read* `M/nk` from outside but
  not *rewrite* an instance's internal node. Vary-per-instance ⇒ make it an **input**.
- Internal non-input source values (a free point/parameter inside the definition not
  designated an input) are **captured as fixed defaults**, shared by all instances.

### Specialization (partial application)
- Fixed variants are **derived macros**: e.g. `standardRect(width, height)` = `roundedRect`
  with `borderRadius` bound to a constant `2mm`, exposing only `width`/`height`. Definition-
  level partial application — needs no per-instance override. Editing the fixed value
  re-propagates to all instances of the derived macro.
- **Optional inputs with defaults** (`roundedRect(w, h, borderRadius=2mm)`) — a possible later
  convenience; named specialization is the primary mechanism.

### Other rules
- **No recursion** (macros form their own DAG at the definition level).
- **Parameter presentation flag** (orthogonal to graph semantics): a parameter/source node is
  either **adjustable** (interactive handle / slider, optional range+step) or
  **constant/locked** (editable value, no slider, not draggable).

## Expression language & units (OP-7 — RESOLVED)

### Expressions & named values
- `Parameter`/`Expr` nodes hold a **formula** referencing other values **by name**.
- Nodes get optional **user-facing names** (`width`, `boltCircleDia`); internally edges stay
  id-based (a name resolves to an id at parse time → a normal dependency edge).
- Free points keep **draggable literal** coordinates (an expression-bound coord isn't draggable).
- **v1 language:** arithmetic `+ - * / ^`; functions `sqrt`, `sin/cos/tan`, `atan2`, `abs`,
  `min`, `max`, `floor/ceil`, `mod`; constant `pi`; references. Pure, no side effects.
  **No conditionals in v1.**
- Errors (parse, unknown name, dimension mismatch, div-by-zero) → node **invalid** (OP-3).
  Name resolution respects the DAG invariant (no cyclic references).

### Units & dimensions
- **Unit-aware scalars with lightweight dimensional analysis.** Every quantity carries a
  **dimension**: `Length`, `Angle`, `Dimensionless`, plus derived `Area` (L²), `Volume` (L³).
- Stored internally in **canonical base units** (mm, radians); **display unit is a
  presentation attribute** only. Mixed-unit input (`1in`, `30°`) is parsed to base units.
- **Dimensional rules:** `*`/`/` combine dimensions; `+`/`-`/comparisons require matching
  dimensions; `sin()` etc. take `Angle` → `Dimensionless`. Mismatches → invalid, caught early.

## Measurements & value feedback (OP-4 — RESOLVED)

- Measurements are **first-class derived nodes** with `Scalar`/`Angle` outputs
  (`Measure.Distance`, `Measure.Angle`, `Measure.Length`, …), **in v1**.
- **Forward-only (driven).** A measured value can feed downstream inputs, but the flow stays
  one-directional. Cycles are impossible by construction (a measurement can only be consumed
  by nodes created after it). A quantity is therefore **driving XOR driven**, never both —
  wanting both is a constraint, which is exactly what we exclude.

### freeze / convert
- **(a) Freeze to constant — IN v1.** Replace a measurement's consumers with an editable
  `Parameter` initialized to the current value (a pure detach). Always possible.
- **(b) Re-parameterize a free source — DEFERRED (on-demand later; no further design now).**
  Not graph inversion but a coordinate change: replace a *free* source node (e.g. a free
  point, 2 DOF cartesian) with an equivalent construction that exposes the measured quantity
  as a driving parameter, capturing residual DOF from current geometry
  (e.g. `P2 = P1 + PolarVector(d, θ)`). Preserves DOF count → **no solver**. Refused when the
  quantity's endpoints are fully determined (no free DOF to absorb the input).
- **(c) General inversion** (solve for a determined quantity) — out of scope (needs a solver).

### Dimensions (as built)

A **dimension** is a displayable element (`ElementKind.DIMENSION`) whose value is one of these
measurement nodes — nothing new in the engine, and nothing asserted. The graphic is a *view* of the
node: drag a measured point and the number redraws, with no node created and none rebuilt. Making a
measured value drive geometry stays parameter wiring, i.e. the other side of driving-XOR-driven; a
dimension is always the driven side.

- **Three kinds, three `ToolDef`s** (`ToolCategory.ANNOTATE`, no controller code): *linear* between two
  points (aligned — the dimension line is parallel to the span), *radial* on a circle or arc (through
  the new `circleOfArc` carrier coercion), *angular* between two lines (through the LINE-slot carrier
  coercion that already existed).
- **Its placement is its own DOF and an ordinary `Handle`** (OP-13): the linear kind owns the *signed*
  offset from the span (so the sign *is* which side, and no discrete choice is needed for it), the
  radial kind a leader angle + reach, the angular kind the arc radius. Each is a source node, so a drag
  and a typed field write the same literal, and the inspector also shows the measured value as a
  read-only field — a `HandleField` with **no node**, which is how "derived by construction, cannot be
  written" is already expressed.
- **Which sector an angular dimension names is a stored discrete choice** (OP-1), resolved once from the
  placing click in the crossing's own basis and then fed to the measurement itself
  (`measureAngleSector(l1, l2, sign1, sign2)`), so the number shown and the arc drawn are the same
  sector however the lines later move. Testing dot products instead is wrong for non-perpendicular
  lines, which is the interesting case.
- **Persistence** rides the generic `tool` step (OP-18) with one addition that generalizes: a step's
  **`dofs=`** argument restates the literals a tool's own source nodes hold, exactly as `param` restates
  a parameter's value. The *clicks* stay verbatim, because what they encode is a **choice** (which side,
  which sector) — the format's existing state-vs-choice rule, now load-bearing for a second feature.
  Deriving the offset back from a restated click position was tried first and rejected: the derivation
  and its inverse are not bit-identical, so `save → load → save` drifted in the last digits.
- **One new `DrawTarget` primitive:** `text(at, string, style, anchor)` — the first thing a drawing needs
  that geometry does not. Both backends use one font (`12px sans-serif`) and the alphabetic baseline; the
  SVG writer keeps fixed attribute order and precision, so goldens stay byte-stable. Arrowheads and the
  gap that lifts the text off its line are sized in **pixels** by `SceneRenderer` (they are drawing marks
  and must not scale); everything else about a dimension is world-space and follows the geometry. Text is
  never rotated — a deliberate "unidirectional" reading, not a limitation of the seam.

## Scope, deliverables & phasing (OP-2 — RESOLVED)

- **Primary goal:** 3D geometries for **3D printing**.
- **Secondary, first-class goal:** pure 2D technical / architectural drawings.
- **2D is a valid sub-goal, not a toy** — the 2D construction engine becomes the sketcher
  inside the 3D tool, so it is the first slice of the 3D product, not a detour.
- **Design now covers 3D fully; implementation is phased.**

### Deliverables

- **3D:** watertight, manifold, orientable solids exported as **STL** and (preferably)
  **3MF** for slicers. Printability (closed, non-self-intersecting) is a hard requirement.
- **2D:** technical/architectural drawings; export likely **SVG/DXF/PDF** (TBD).

### Implementation phases

- **Phase 1 — 2D construction engine.** DAG evaluation, primitive algebra, intersections
  (branch/continuity), undefined propagation, macros, measurements-as-parameters, 2D export.
  Exercises the entire novel/risky part of the concept with no solid kernel.
- **Phase 2 — 3D.** Sketch→feature loop, solid representation/kernel, booleans, extrude/
  revolve/sweep, fillet/chamfer, STL/3MF export, topological identity.
- **Phase 3+** — assemblies, drawings-from-3D, standard-part macro libraries.

## Result layer — construction vs. output (OP-14 — RESOLVED)

Most elements of a construction are **technical scaffolding** and should not appear in the final
drawing. The obvious fix — a `construction: Boolean` flag per element, as in SolidWorks reference
geometry — is **not sufficient**, because the result is usually *not a subset of the construction
elements*: a drawn line is infinite, but the outline needs *the piece between two intersection
points*; a circle is whole, but the outline needs *one arc of it*.

**Decision: the result is not marked, it is constructed.** Two additions, both ordinary pure nodes:

1. **Trim ops** — `segmentBetween(curve, P, Q)`, `arcBetween(circle, P, Q, side)`, generally
   `subCurve(curve, tFrom, tTo)`. The first genuinely *result-shaped* nodes; no solver.
2. **A closed-loop value.** `Profile` (`geom/Geom.kt`) exists but is a passive chain. Promote it:
   - `Loop` — closed, **oriented** chain of trimmed curve pieces.
   - `Region(outer: Loop, holes: List<Loop>)` — with a fixed orientation convention (CCW outer).

The result is then a small set of `Region`/curve nodes, and everything else reads as scaffolding
**because nothing consumes it** — a graph property, not a flag.

### The Outline tool
Boundary tracing: click around the intended contour; each adjacent pick pair emits a trim node,
auto-intersecting neighbours. One tool converts a field of construction lines into a result.

### Rejected: region detection by interior seed point
Paint-bucket / planar-map region finding is tempting and **excluded**: the loop's identity would be
*discovered* rather than constructed, which re-imports the topological-naming problem (OP-8) into 2D
through the back door — drag a parameter and the region re-detects differently, so the model stops
being a pure function of its parameters. A `Loop` therefore stores **which curve nodes, in which
order** (stable node identity); only the trim parameters recompute. A loop that stops closing becomes
**invalid**, hides, and auto-heals (OP-3) — the correct behaviour, and free.

### Three orthogonal concepts — do not merge them

| concept | nature | mechanism |
|---|---|---|
| result vs. scaffolding | **semantic** | is it consumed by an output node |
| dimmed / hidden | presentation | per-element flag |
| layer (walls, dimensions, annotation) | organizational | named bucket + visibility |

**Annotation is the third column, and the dim toggle must respect that.** Scaffolding is *derived* —
the ancestor closure of the result elements — and a dimension can end up inside that closure: wire a
parameter to a measured value and the measurement node becomes an ancestor of the result. Dimming the
dimension then would be exactly backwards, so `scaffoldingElements()` excludes annotation outright
(`Element.isAnnotation`). The honest rule, recorded here because it is a decision and not a fallout: **a
dimension is neither result nor scaffolding; it is visible whenever it is not hidden.** It is also never
*consumed* by anything — nothing may depend on an annotation — so excluding it costs the derivation
nothing.

### One idea, three levels
Every level of the model needs an explicit **output set**: which 2D curves are the drawing, which
regions are the sketch (OP-17), which solids get exported to STL (OP-9). This was the concept
missing from the model.

### Implementation status (as built — engine half)
The pure-engine slice ships (`geom/Geom.kt`, `core/Model.kt`, `dsl/Construction.kt`, `RegionTest`):
- **Trim ops** `segmentBetween(curve, from, to)` and `arcBetween(curve, from, to, ccw)`. Both accept
  the *coerced* carrier (line/segment/ray; circle/arc) and **project** their cut points onto it —
  documented rather than silent, because a cut point is normally constructed *from* the curve, so
  exact incidence is unattainable in floating point and projecting is what keeps a trim well-defined
  as parameters move. `ccw` is a stored discrete branch choice, exactly like a `Select` sign (OP-1).
  Neither op needed a new value type: a trimmed line **is** a `Segment`, a trimmed circle **is** an
  `Arc`.
- **`Loop`** (closed + oriented) and **`Region`** (outer + holes) values, built by `loop(vararg)` /
  `region(outer, vararg holes)`. `loop` chains pieces *in the order given* — stable identity (OP-8) —
  but flips each piece as needed, since a piece's stored direction is an accident of how its inputs
  were picked, not a statement about the boundary. Orientation is normalised (outer CCW, holes CW) so
  signed areas add up, which is the form the seam consumes. A chain that stops meeting up makes the
  node invalid with a reason and heals when it closes again (OP-3).
- **`ProfileElement.CircleE`** — a whole circle as one closed piece, so a circular hole is never
  faked as a full-turn arc whose 0-vs-2π sweep is ambiguous. It carries its own `ccw` so a hole can
  be oriented.
- **`loopArea` / `regionArea`** (dimension L², exact for segments and arcs via the ∮(x·dy − y·dx)
  line integral). This also closes "Area measurement" from the tool roadmap.
- Transforms extend over the new values, re-orienting after a mirror so the outer-CCW/holes-CW
  convention survives a negative determinant. The SVG serializer renders loops and regions, so the
  *result* is what an export contains.
- Worked spec example: `RegionTest.flangedPlateRegionArea` builds the OP-17 slice-1 sketch —
  `roundedRect` outer + `boltCircle` holes — and asserts the exact known area
  `w·h − (4−π)r² − n·πr_hole²`, plus an SVG golden.

#### Where a new piece kind has to be handled
`ProfileElement` is a **sealed** hierarchy and Kotlin (1.9) makes a non-exhaustive `when` over one a
compile **error** — as an expression *and* in statement position. So the compiler, not a convention,
lists every site that must handle a new piece kind. Adding `CircleE` produced exactly that list; a
probe adding a fourth kind reproduces it.

That check is worth keeping (it is why nothing silently mishandled circles), which is the argument
against replacing the hierarchy with a `BoundedPiece`-style interface: sealed types make adding a
*variant* compiler-guided and adding an *operation* cheap, and here the operation axis is the one that
grows — `pointAt`/`paramOf`/`length`/`split` all arrive with splines (OP-15). Two rules keep the
guarantee real:

- **Piece dispatch lives in `GeomMath`, and only there** (`startOf`, `endOf`, `reverse`,
  `doubleSignedArea`, `bounds`, `transform`). The one deliberate exception is a renderer emitting
  backend-specific markup, which is not a geometric question. `Transform.kt` and `Svg.kt` therefore
  delegate rather than re-dispatch.
- **No `else` on a sealed dispatch that must stay total.** An `else` silently absorbs new variants,
  which is how `Svg`'s `Value` dispatch would have dropped `Loop`/`Region` from an export without a
  word — an exporter omitting what it was handed is the failure worth preventing, so the non-drawable
  cases are now spelled out. `else` stays where openness *is* the semantics: the editor's
  "no interaction for this kind" (`HitTest`, `Snap`, `SceneRenderer`), coercion predicates answering
  "is this coercible?", and runtime typing of `Ref<*>` varargs, which varargs cannot express statically.

The one place exhaustiveness is structurally unreachable is `DocumentFormat`, which dispatches on a
`String` step kind. Its design contains the blast radius: steps are *tool invocations*, not ops, so a
new tool rides the generic `tool` step instead of adding a kind.

### Implementation status (as built — the UI half)
The result layer is reachable end to end in the browser:
- **`Outline` tool** — click the curves round a boundary in order, then close by clicking the first
  again or pressing Enter. Variable arity is data-driven: `ToolDef.repeating` makes the **last** slot
  repeat, so this needed no new controller special case (unlike the ortho path). Consecutive picks are
  intersected — the branch chosen from where the user clicked and then **stored** (OP-1) — and each
  pick is trimmed between the two joints that fall on it.
- **Two picks are a special case worth naming.** When a boundary has only two pieces, each is the
  other's neighbour on *both* sides, so they must hand over at two *different* meetings; taking the
  nearest one twice collapses both pieces to a point. A chord and its arc take the two intersection
  branches in canonical order; a chord and a spline take the spline's two endpoints.
- **Scaffolding is derived, not flagged** — `Document.scaffoldingElements()` is the ancestor closure of
  the result elements, so "this is construction geometry" means exactly "something in the output
  depends on it". A *Dim construction* view toggle greys it, leaving the drawing legible on its own.
  Geometry no result uses is not scaffolding either — it is simply unused.
- Save/load needed **no per-tool support**: the `tool` step already carries `els=` and `clicks=`, and a
  repeating tool is just more of both.

**Deliberately not done here:** containment is not verified (a hole outside the outer boundary, or
two overlapping holes, are accepted; only holes removing more than the boundary encloses are
rejected) — real containment needs the point-in-region predicate, which this slice does not need.
**Remaining:** the boundary-tracing *Outline* tool and the scaffolding/result display roles — both UI,
and both deliberately deferred so this slice touches none of the files the ortho-path work is in.

## General curves & splines (OP-15 — RESOLVED)

The vocabulary: the family is **splines**. Drawing applications mean **cubic Bézier curves**
(Illustrator/Inkscape/SVG paths — control handles); CAD means **B-splines** (piecewise, local
control) and **NURBS** (rational B-splines with weights — what STEP speaks). Sketchers offer two
flavours: **control-point spline** vs. **spline-through-points** (a *fit*/interpolating spline;
Catmull-Rom is the cheap version). Relatives: **clothoids** (curvature-linear; roads, rails) and
subdivision curves.

**Decision: splines are in, and they fit the paradigm natively rather than by concession** — a
spline is *already a pure function of its control points*. `bspline(P1..Pn, degree, knots) → Curve`
needs no solver. The payoff is that each control point may itself be **constructed** — an
intersection, a tangent point, a point-on-circle, a point offset along a shared `Direction`. That is
the missing bridge from technical construction to smooth "drawing-application" geometry, and no other
CAD paradigm expresses it this directly. (Prior art: Grasshopper — the strongest external support for
the whole thesis.)

### Continuity by construction, not by constraint
"Make this spline tangent to that line" is a constraint in every sketcher. Constructively: place the
first control leg *on* the tangent line — `P0 = endpoint`, `P1 = P0 + t·dir` with `dir` a **shared**
`Direction` reference. G1 then cannot be violated. G2 is equally reachable: the end curvature of a
cubic Bézier is a closed-form function of `P0,P1,P2`, so `P2` is *constructed* on the locus achieving
a target curvature — an ordinary derived point. A showcase example for the paradigm.

### A linear solve is not "a solver"
An interpolating spline through N points needs a tridiagonal solve: closed-form, deterministic,
non-iterative — **not** a breach of the no-solver stance. Likewise curve–curve intersection with
splines goes numeric: *approximate but deterministic*, so purity, undo/reload and SVG goldens all
survive. **Determinism is the load-bearing property, not closed form.**

### The 2D analog of the mesh-is-a-sink rule
Offsetting a spline does not yield a spline, only an approximation; nor does a general fillet between
two splines. So the 2D layer partitions exactly as the 3D layer already does (OP-9):
- **Exact analytic curves** — line / arc / conic / NURBS: measurable, STEP-exportable later.
- **Approximated curves** — offsets, general fillets: render/export-only, never claimed exact.

Same principle covering both layers, enforced by the type system (OP-5). This matters for the
architectural layer: `parallelAtDistance` on a spline centerline is an *approximation*, and the type
must say so.

### Consequences
- A general **`CurveValue`** with `pointAt(t)` / `tangentAt(t)` / `curvatureAt(t)`; `Line`, `Circle`,
  `Arc` become instances of it. This is the largest refactor on the roadmap — do it deliberately.
- **Canonical ordering generalized (OP-1):** for parametric curves, order solutions **by parameter
  along the first operand** — exactly what line–circle already does ("order along the line's own
  direction"). Circle–circle's side-of-line rule remains the special case it is.
- **Handles (OP-13) apply unchanged:** every control point is a handle with coordinate fields, so a
  spline exposes no DOF reachable by mouse but not by number.
- **Build order:** control-point Bézier/B-spline first (pure, trivial, immediately useful) →
  fit-through-points → **NURBS weights last**, since weights mainly buy exact conics and exact
  circles already exist analytically.

### Implementation status (as built — cubic Béziers)
Splines are in, as the completeness proof for the result layer: a `Bezier` piece sits in a boundary
next to segments and arcs on equal terms.
- **`Bezier` value + `ProfileElement.BezierE`**, `bezier(p0, p1, p2, p3)` over four ordinary
  `PointRef`s — so every control point may itself be *constructed*. A `Bezier curve` tool exposes it.
- **Exact piece maths, not sampled.** The area contribution has a closed form for the same line
  integral the other pieces use (derived, then verified against 200 000-step numerical integration):
  `∮(x·dy − y·dx) = [6x₀y₁ + 3x₀y₂ + x₀y₃ − 6x₁y₀ + 3x₁y₂ + 3x₁y₃ − 3x₂y₀ − 3x₂y₁ + 6x₂y₃ − x₃y₀ −
  3x₃y₁ − 6x₃y₂]/10`. So a spline in a boundary costs no accuracy relative to an arc.
- Reverse is the reversed control points; transform is **affine invariance** (map the points, get the
  mapped curve — exact for mirror/rotate/scale, no re-fitting); bounds is the control polygon's box,
  conservative by the convex-hull property. Rendering tessellates at a **fixed** step count, because an
  adaptive one would make goldens depend on curvature.
- **Tangency by construction** — `bezierTangentControl(from, line, handle)` places the first control
  leg *on* the tangent line, so G1 is structurally impossible to violate and survives moving the line
  with no re-solve. This is the OP-15 claim made concrete.
- **A spline is not trimmed into a boundary — it is built onto it.** There is no `bezierBetween`;
  instead a spline contributes its own endpoints as joints, so the constructive move is to attach its
  ends to where they belong (a shared derived point, or drag-to-attach). If it does not actually reach
  its neighbours the loop reports the gap and stays invalid (OP-3), which is more useful than a
  silently mended boundary. Parametric trimming (`subCurve` via de Casteljau) is still open, and is
  what a *fit* spline through points will want.

## Groups, frames & placement (OP-16 — RESOLVED)

Two different needs hide under "group", and separating them dissolves most of the problem:
**selection grouping** (organizational) and the **semantic group** (a real node). Likewise "move"
has two legitimate meanings — a *construction* transform (new derived geometry, what the
`MIRROR`/`ROTATE`/`SCALE`/`TRANSLATE_V` tools build today) and an *edit* transform (relocate what is
already there).

### Moving means moving the frame — not transforming the points
**Decision: a group carries its own coordinate frame; its internal geometry is defined in local
coordinates; moving the group edits the frame.** `place(frame, localConstruction)`, where `frame` is
a **`SourceNode` holding (origin, angle)**. Consequences:

- Moving a group is a **literal edit on one source node** — structurally identical to dragging a free
  point. O(1) instead of O(N), one undo entry, internals untouched and still readable.
- Rejected alternative: applying a transform to every point of the construction (O(N) writes, N
  derived nodes, or a bulk literal rewrite) — correct results, wrong model.
- The frame is a **`Handle`** (OP-13) with x / y / angle fields, so a group is movable by drag *and*
  by typed number for free. A frame-local point's drag inverse-maps the world position into local
  coordinates — again a `Handle`, nothing asserted and solved.

### Grouping an existing construction is a refactoring
Free sources hold absolute literals today, so making them frame-relative is a one-time,
DOF-preserving, invertible rewrite. Once in that form, the three operations are **one code path with
three different invariants**:

| operation | frame | internal locals | invariant |
|---|---|---|---|
| **move** | edited | untouched | locals fixed |
| **relocate origin** (refactoring) | rewritten | rewritten | *world output* fixed |
| **re-parent** into another group | recomposed | untouched | world output fixed |

Relocate-origin is the only one that is O(N) and the only one that touches internal free coordinates.
Framing it as "the world-invariant variant of move" makes clear why it is a refactoring rather than an
edit — and gives it a trivial test: geometry before ≡ geometry after.

### A constructed frame is a mate — without a solver
The frame need not be free. `frameAt(point, direction)`, with the point and direction taken from
*other* geometry, makes the group follow its host. This is the constructive answer to assembly
mating, so **phase-3 assemblies need no solver either** — worth recording now, while it is cheap.

### A group frame and a sketch plane are one concept
`place(frame, local2D)` and `SketchOn(plane, regions)` (OP-17) are the same idea — local construction
plus placement — at two dimensions. Building group frames in 2D therefore **prototypes the 3D seam**.

### Rules and consequences
- **DOF accounting:** a frame *adds* 3 DOF in 2D (6 in 3D) and removes none. The same world position
  becomes reachable two ways (via the frame or via internals) — harmless for purity (still a pure
  function), but the UI must be clear about which handle was grabbed.
- **Boundary attachment makes a group non-rigid, correctly.** If a member point is welded or attached
  to something *outside* the group, its `boundTo` leaves the group, so it is not one of the group's
  free DOF: the frame does not move it and the group deforms. This falls straight out of existing
  `boundTo` semantics (no special-casing) but must be *visible*.
- **Membership = closure or inputs?** Selecting an intersection point raises the question whether its
  ancestor circles join the group or become its inputs — the same question macro-definition-by-example
  asks (OP-6). So **group creation and macro definition are one dialog with a different default**,
  which folds the macro-record UI in rather than duplicating it. Group → macro is the promotion path.
- **Honest failure mode:** a group moves independently only if the free ancestors in its closure are
  not shared with non-members. When they are, report it concretely ("P3, P7 are also used by e12 —
  include them, or this group cannot move independently"). That is a real modelling ambiguity, not a
  bug to paper over.
- **Ortho-path bonus:** retrofitting a path under a frame turns its shared coordinate nodes into
  *local* coordinates, so axis-alignment becomes alignment to the group's own axes. That is precisely
  the rotated **project frame** sketched in the architectural layer — delivered as a side effect.

### Build order
0. **Multi-select — DONE.** A set with a primary element; prerequisite for everything, and
   independently useful (bulk hide / delete).
1. **Flat group — DONE.** A named set. No frame, no closure analysis. Buys select-together, naming,
   tree structure; the container everything else attaches to.
2. **Placed group — DONE.** Frame + the frame-relative retrofit of free sources in the closure.
3. **Relocate-origin, re-parent, constructed frames (mates), group → macro promotion.**

### Implementation status (as built — multi-select & flat groups)

**Selection is a set with a primary element.** `Editor.selection` still names *one* element — the
primary, what the inspector addresses — and the set beside it is what delete, hide and Group act on.
That split is the whole design: a handle field writes one node (OP-13), so with several elements
selected the inspector shows nothing rather than something averaged, while every *bulk* operation has
a well-defined subject. Click replaces the set, Shift+click toggles one member, Escape and a click on
empty space clear it, and every kind is highlighted on canvas (not just points and segments, which is
all a single selection ever needed).

**The empty-space drag became the marquee, so panning became a button.** `pointerDown` gained
`button: PointerButton` and `additive: Boolean`, both defaulted so existing call sites and gestures are
unchanged. Panning now runs on MIDDLE — handled before any tool dispatch, so it works *in every tool*,
which dragging empty space never did — and the browser shell reports Space+drag as MIDDLE, keeping the
key mapping out of the pure controller. The marquee takes what its rectangle *meets*, not what it
contains (`HitTest.within`, a slab clip per kind mirroring `distanceTo`): rubber-banding a room has to
grab the walls crossing the box. One existing test encoded pan-on-empty-drag and was deliberately
rewritten (`wheelZoomsAndMiddleDragPans`).

**Shift is two things and they do not collide.** Shift means axis lock during a drag and toggle on a
click, so the toggle is applied *on release* and only when the gesture did not move: a Shift-drag
reshapes geometry and leaves the selection untouched.

**A group is membership and nothing else.** `Document.groups` is a list of `Group(name, members)` with
no frame, no transform and no node of its own — grouping provably cannot change geometry or handles.
Clicking a member selects the whole group; **clicking it again reaches the member alone** (and again
returns to the group), which is the one mechanism chosen for reaching a member and is stated in the
status line. Alt was rejected for it: Alt already means "place freely / keep flattened corners" mid-
gesture. An element is in **at most one** group, which is not a simplification for its own sake but
what the format enforces — membership lives in a recorded step's argument list, and recorded arguments
are never rewritten.

**Persistence: a `group "name" els=e1,e3` step** (OP-18), replayed through the same `createGroup` the
button calls. Member deletion stays consistent through exactly two rules in one place each: a `group`
step is **exempt** from the "references a dropped element" cascade and goes only when *all* its members
are dropped, and `save` writes only the members the script still declares. Because they are the same
rules, live delete and replay cannot drift — and `save → load → save` byte-equality is the test. A bulk
delete therefore computes **one** step-closure over all roots (`dependentSteps(Set<Step>)`) rather than
a union of per-root closures: two members dropped by two different roots empty a group that neither
root empties alone.

**Deliberate omissions at this step.** Visibility is a *view* state — hiding is neither saved nor an
undo step, because the file is a construction and has no viewing section; a welded alias stays hidden
because it is hidden by construction. A marquee takes elements, not groups (it selects exactly what it
covers). No bulk *move*: that is the frame (step 2), and doing it by rewriting N literals is the model
this design rejects.

### Implementation status (as built — placed groups)

**A frame is one source node holding one value.** `FrameValue(origin, angle)` (angle in rad, the base
unit) with `frameApply(frame, localPoint) → Point` as the single new op. Not three scalar parameters:
moving a group has to be *one* literal write, which is what makes it O(1), one undo entry and
structurally the same operation as dragging a free point. `transformValue` deliberately has **no** rule
for a frame — composing a frame with a construction transform is *re-parenting* (step 3), not a mirror
of what it carries, so it raises instead of doing something plausible-looking.

**Placing is a retrofit on the weld substrate, not new machinery.** For each free point source the group
owns, a fresh local source is created at the frame-inverse of where that point already is, and the
**original node is bound** onto `frameApply(frame, local)` (`SourceNode.boundTo`). Nothing is rewired
(OP-5): every reference to the original — segments, midpoints, intersections, loops — transparently
follows the frame, which is why *derived* geometry needs no rule of its own. The retrofit is therefore
world-invariant (the headline test compares every evaluated position before and after), DOF-preserving
(one local per captured point, plus the frame's three) and invertible: `unplace` writes each point's
current world value back into its own node and drops the frame.

**A framed point is bound but is not a welded alias**, and the two had to be told apart in one place
(`isWelded` now excludes framed points): a framed point stays visible, still drags — through a
`FramedPointHandle` that **inverse-maps the cursor into the frame** and writes the local source — and
still shows *world* x/y in the panel, so placing a group changes no number the user can see. Its drag
also no longer offers the weld magnet: its position is already derived, so a weld would be refused on
release and a halo promising one would be a lie.

**Membership analysis: owned, shared, or outside.** A free point in the members' closure is the group's
own only if the element *displaying* it is a member. That single rule separates the two OP-16 cases
cleanly: a point owned by a non-member is that non-member's DOF, so it is left alone (a member bound to
it simply does not follow the frame — the group deforms, correctly), while a point the group owns that a
non-member also depends on is refused, naming both sides ("Can't place half: e1, e2 are also used by e8
— include them, or this group cannot move independently"). Deformation is *reported at placement time*,
since on canvas it is invisible until the group moves: a member is flagged when its closure contains a
free point source the frame does not drive, or an **ortho vertex coordinate** — the two kinds of source
that pin a position in world coordinates. A curve parameter (a point-on-line distance, a
point-on-circle angle) is deliberately not one of them: it is relative to a curve that follows.

**Gesture: the group/member cycle is a click semantic.** Clicking a member selects the group and shows
the *frame's* x/y/angle in the inspector; dragging it moves the frame; clicking the same member again
reaches it alone, and then dragging edits that member inside the frame. The cycle is therefore applied
**on release, and only when the gesture did not move** — exactly the discipline Shift's toggle already
uses — because deciding the drag's subject *after* re-picking made "click a member, then drag it" move
the member instead of the group.

**Persistence: a `place "name" at=x,y angle=Ndeg` step** (OP-18), with origin and angle restated from
the live frame (literals-as-current-values). The members' own `point` steps keep restating **world**
positions: they replay before the placement, and the retrofit re-derives the locals from world position
and frame — so the file contains no local coordinates and no node names at all, and `save → load → save`
stays byte-equal after a frame drag *and* after a member drag. Delete follows the `group` step's rule
one hop further: a `place` step whose group step went, goes. Unplace drops the step outright (like
ungroup), and ungrouping a placed group unplaces it first.

**Deliberate omissions at this step.** The frame starts at the members' bounding-box centre and stays
there: moving it is *relocate-origin*, the world-invariant refactoring of step 3, not an edit. No
rotation grip on canvas — rotation is the angle **field**; a grip needs picking for something that is
not an element, which buys a gesture and no capability. **Ortho paths and walls are not captured**: their
positions live in shared *scalar* coordinate nodes, and capturing those means re-pointing the very
bindings that hold a leg axis-aligned (and that break/join re-point), plus frame-aware corner handles —
a separate piece of work, and the *ortho-path bonus* above is precisely it. Until then such a member is
reported as not following its frame rather than silently pretending to. Placing is also refused when a
group owns no free point at all: a frame with nothing to carry is three degrees of freedom that move
nothing.

## Going to 3D

- Sketch → feature → sketch loop (sketch on datum plane → extrude/revolve/sweep → derive
  datum from a solid face → sketch again). DAG spans 2D constructions, 3D features, datums.

### The 2D↔3D seam — sketches on frames (OP-17 — RESOLVED)

What 2D hands to 3D, concretely:

```
Loop      = closed, oriented chain of trimmed curve pieces          (2D, OP-14)
Region    = outer Loop + inner Loops (holes), fixed orientation     (2D, OP-14)
Sketch    = SketchOn(plane, [Region])                               <- the seam
Solid     = extrude(sketch, depth, dir, draft) | revolve(sketch, axis, angle)
          | sweep(sketch, path) | loft(s1, s2) | boolean(...)
```

Note that `Loop`/`Region` are exactly what the result layer (OP-14) produces: **the result layer *is*
the 3D interface.** OP-14 is therefore a prerequisite for this seam, not an independent nicety.

**Decision: 2D constructions stay in abstract 2D space; a separate `SketchOn(plane, regions)` node
does the embedding.** 2D geometry is deliberately *not* made intrinsically plane-resident. This keeps
`commonMain`'s 2D engine untouched, keeps 2D a first-class standalone deliverable (OP-2), and — the
real payoff — lets **one 2D construction be embedded on several planes**, which is macro-instance
semantics (OP-6) applied to the seam. A plane is `(origin: Point3, u: Dir3, v: Dir3)`, derived from a
face plus an edge so the frame is *stable* rather than arbitrary. This is the same concept as a group
frame (OP-16), one dimension up.

**The seam is a two-way type conversion but a one-way dataflow.** Upward: `SketchOn`. Downward:
`section(solid, plane) → Region`, `project(edge, plane) → Curve`, `face.plane → Plane` (OP-8
provenance accessors). Both directions are analytic, so the mesh-is-a-sink rule is untouched — but the
acyclicity rule of OP-4 applies verbatim: a 2D construction consuming a projected edge must not be an
ancestor of that solid. No new semantics.

**Holes: inner loop or boolean?** Both are needed. An inner `Loop` of the region handles same-depth
through-features analytically with no boolean at all (cheap, exact). The moment depths differ — a
**counterbore or countersink**, which mechanical work reaches immediately — the feature must become a
separate extrude plus a boolean subtract.

#### First 3D slice: mechanical, not walls
A wall is a **degenerate case of the seam** — one sketch, one extrude, no back-flow, no face-derived
datum — so extruding walls would validate the easy half and leave the risky half untested. The target
is general 3D mechanical engineering, so the first slices are mechanical parts, built on macros that
already exist (`roundedRect`, `boltCircle`, `holePattern` in `dsl/Shapes.kt`):

1. **Flanged plate.** `roundedRect` outer + `boltCircle` holes → `Region(outer, holes)` → extrude →
   STL. Exercises multi-loop regions, orientation conventions, watertightness. Ends with **one
   counterbored hole**, deliberately — that drags the boolean path (and Manifold) in on the first
   slice instead of the fourth.
2. **Turned part.** Revolve a profile about an axis (stepped shaft / pulley). A different feature
   type: profile orientation, axis handling, and the open-vs-closed profile rule (a revolve profile
   may be open where it touches the axis — a genuine OP-3 validity case).
3. **Sketch on a face.** A boss or rib on the plate's top face. The slice that matters: the only one
   exercising OP-8 provenance accessors (`plate.topFace → Plane`), the sketch→feature→sketch loop, and
   acyclicity at the seam. If the seam is wrong, it is wrong here.

Per the testing strategy these double as worked spec examples, extending the existing
`BoltCircleTest` / `RoundedRectTest` / `ProfileTest` pattern to STL assertions (manifold, volume,
bbox). **3D walls are a later application of the same machinery, not the proof of concept.**

### Implementation status (as built — 3D engine core)

The engine half of the seam ships (`geom/Geom3.kt`, three value types in `core/Model.kt`, the
`Tier 5` ops in `dsl/Construction.kt`, `SolidTest`). All three slices are reachable end to end, minus
the counterbore — booleans are the next task. **No UI, no viewport, no document-format support**: 3D
tools arrive with the viewport, deliberately, so this slice touches nothing the 2D editor depends on.

- **Types.** `Vec3`; `Plane3(origin, u, v)` (orthonormal, `normal = u×v`, with `translated`/`flipped`);
  `Sketch3(plane, regions)`; `Feature3.Extrusion` / `Feature3.Revolution` — the *analytic* description;
  `Mesh3(vertices, triangles)` with `Tri(a,b,c)` indices; `Solid3(feature, mesh)`. Values:
  `PlaneValue`, `SketchValue`, `SolidValue`. The mesh rides **inside** the solid value because it is
  derived data, not a second object that could disagree with its parameters — and `SolidValue` stays a
  distinct type from a future mesh-only value, which is the OP-9 partition the type system must carry.
- **Ops.** `plane(origin, u, v)` (+ `planeXY/XZ/YZ`, `planeOffset`, `planeFlipped`),
  `sketchOn(plane, vararg regions)`, `sketchPlane`, `extrude(sketch, depth)`,
  `revolve(sketch, axisOrigin, axisDir, angle)`, `facePlane(solid, TOP|BOTTOM)`, `measureVolume`,
  `measureBBoxMin/Max/Extent(solid, axis)`. The revolve axis is an ordinary `PointRef` + `DirectionRef`,
  so it can be *constructed* (a centreline through two key points) and moves with the profile.
- **Tessellation tolerance: `GeomMath.TESS_TOL_MM = 0.02` mm**, a chord/sagitta bound in **world**
  units — the unit a printed part is wrong by. A documented constant rather than a per-feature knob,
  because the mesh is a sink: nothing downstream measures it, so a knob would only let two solids in
  one document disagree. The renderer's tessellation is a *separate policy* (a fixed 64 steps per full
  turn, so SVG goldens do not depend on the camera) but no longer separate *maths*:
  `GeomMath.sampleArc/sampleCircle/chordSteps/bezierSteps/tessellatePiece` are the single piece-dispatch
  site (OP-14's rule) and `SceneRenderer.tessellate` now delegates to them with its own step count. A
  Bézier's count comes from the closed-form bound `err ≤ max|B''|/(8n²)` — deterministic, not adaptive
  subdivision.
- **Triangulation — hole bridging + ear clipping, both deterministic.** Holes are spliced into the outer
  polygon along a bridge traversed twice (turning outer-plus-holes into one weakly-simple polygon), in
  order of each hole's **rightmost corner** (ties upward, then input order); the bridge partner is the
  **nearest** merged-polygon corner (ties by index) that the bridge can *see* — crossing no boundary
  edge, with its midpoint inside the material. Ear clipping then scans from index 0 every pass, drops
  collinear corners instead of emitting zero-area ears, skips candidate corners *coincident* with the
  ear's own (which is what makes the doubled bridge vertices harmless), and — if a pass finds no clean
  ear — clips the most convex corner anyway, so a near-degenerate sliver costs a little accuracy rather
  than an endless loop. No randomness, no hash-order iteration anywhere; the vertex weld is a
  `WELD_TOL = 1e-7` mm lattice with a fixed 27-cell scan, and indices are handed out in insertion order.
- **Watertightness is structural, not repaired.** Caps and side walls are built from the *same*
  tessellated points, so every wall edge meets exactly one cap edge by construction. A test utility
  `assertManifold(mesh)` states the requirement as one property — every directed edge occurs exactly
  once and its reverse exactly once (closed **and** consistently oriented), no degenerate triangle,
  positive signed volume — and runs on every solid in every test.
- **Provenance accessors (OP-8): `facePlane(solid, TOP|BOTTOM)` for an extrude.** `TOP` is the sketch
  plane translated by `depth·normal` and keeps the sketch's own `u`/`v`, so a sketch placed on it uses
  the same coordinates as the sketch below; `BOTTOM` is the sketch plane flipped. Stability is by
  construction: `which` is a stored discrete choice (like a `Select` sign, OP-1) and the plane is
  recomputed from the feature's parameters, so it stays *the top face* across every edit and nothing is
  ever re-identified from mesh topology. This is what makes slice 3 work — the boss follows the plate
  when the plate's depth changes.
- **Measurements (OP-4): `measureVolume` (dimension L³, divergence theorem over the mesh) and
  `measureBBoxMin/Max/Extent` per `Axis3`.** These are the 3D→2D scalar seam and are unobstructed: a
  test drives a 2D circle's radius from a solid's thickness. Volume is *exact for the mesh* (hence
  deterministic) and approximate for the curved solid, by the tessellation tolerance.
- **Volume assertion tolerances, stated honestly.** Mesh volume equals *tessellated-polygon area ×
  depth* to `1e-6` mm³ — a prism's volume is its cap area times its depth whatever the triangulation, so
  this is an exactness check on the kernel. Against the *exact* area the flanged plate is within `1e-3`
  relative (measured ~1e-4), and **the sign is not determined**: an inscribed corner arc removes a
  sagitta of material while an inscribed hole fails to remove one, so a plate with holes comes out
  marginally *heavy*. A purely convex revolution has no such compensation and is strictly light (the
  stepped shaft: 2.6e-3 under `πr²h`, asserted `< 5e-3` and asserted to be *under*). A diameter across a
  revolve's axis is short by up to twice the chord tolerance.
- **Cuts (whole capabilities, refused rather than approximated):**
  - **Booleans** — the counterbore of slice 1, and therefore slice 1's last step. The next task.
  - **`facePlane` on a revolve** is refused with a reason: a partial revolve's end caps *are* planes, but
    they are rotated frames and naming them `TOP`/`BOTTOM` would invent a convention nothing needs yet.
  - **Negative or zero extrude depth** is refused (a direction flip is a real feature, but it inverts
    the cap-orientation rule and belongs with a `dir`/`draft` argument, not smuggled in as a sign).
  - **No 3D transform**: mirroring/rotating a plane, sketch or solid by a 2D affine is refused (node
    invalid) rather than guessed — a 2D reflection line does not determine a 3D one. It arrives with
    assemblies.
  - **No SVG output for 3D values**: a solid's 2D image needs a *chosen projection*, which is a view
    decision the serializer must not invent. It arrives with the viewport.
  - Deliberately **not** cut, because the general algorithm covered them: a profile *touching* the axis
    (the collapsed quads are simply dropped), a partial revolve's two flat end caps, and a revolved
    region *with a hole* — the toroidal cavity is closed and its volume checks out against `2π²Rr²`.

### 3D representation & CNC (OP-9, OP-8, OP-11 — RESOLVED)

**Decision:** an **analytic construction layer is the source of truth**; the mesh is an
**output/preview/export artifact** (a terminal *sink*), produced by **Manifold** as the robust
boolean-composition + guaranteed-watertight-mesh engine.

- **CNC scope (OP-11):** precision CNC + STEP is a **target, but not day-one → posture 1.**
  Ship printing-first on the mesh engine, but **preserve exact analytic provenance** so a
  B-rep/STEP export can be added later (e.g. OCCT as an *export-only* backend). Exact surfaces
  cannot be reconstructed from a mesh after the fact — they must come from the analytic layer.
- **Engine (OP-9):** Manifold — guaranteed-manifold mesh booleans; JVM **and** Rust bindings
  (fits OP-10; depending on a C++ lib via bindings ≠ writing C++); used by OpenSCAD/Blender/
  Godot. Fillets/chamfers are **explicit constructions** (sweep a rounded profile along a
  provenance-known edge, then boolean), not a magic kernel op. Optional implicit/SDF sub-layer
  later for organic smooth-min blends.
- **Sub-entity identity (OP-8):** **provenance-based**, not discovered topology — so the
  topological-naming problem largely dissolves. Faces/edges/datums are constructed derived
  accessors with stable IDs (e.g. `box.topFace`, `box.sideFace(edge_k)`), the same
  compound-value+accessor principle as `PointSet`/macros. Through booleans, mesh face identity
  rides on **Manifold's ID/property propagation**; exact surfaces come from the analytic
  operands; datums stay analytic and never touch the mesh.

#### The mesh-is-a-sink rule
Follow-up constructions rely on the **analytic layer**, not the mesh — because most
"mesh-looking" needs are actually analytic:
- **Boolean results are analytic-preserving:** exact face surfaces = operand surfaces (known);
  only trimmed boundaries are emergent (analytically computable). Manifold is a helper, not a
  dependency. (This is what enables later STEP reconstruction.)
- **Intersection edges/curves, datums, dimensions** — all analytic.

A follow-up genuinely relies on **mesh output** only here:
1. **Mesh-only geometry ops** — `offset`/`shell`/`hull`/`minkowski`/mesh-smoothing of a
   composite: no clean analytic form → produce a `Mesh` value that stays mesh-only
   (print/render), **not STEP-exportable**.
2. **Imported meshes** — mesh-*as-source* (scan/download); referenceable, non-analytic,
   non-STEP.
3. **Global mass properties** — volume/CoM/bbox/thin-wall: easiest from mesh. These are
   **scalars**, so they may drive a **new, independent (non-ancestor) analytic construction**
   forward (ordinary dataflow); only feeding one **back into the mesh's own ancestors** is a
   **cycle (forbidden, OP-4)**.

**Geometry vs scalars across the mesh boundary.** The "no mesh→analytic lift" rule is about
**geometry** only. A **scalar measured from a mesh** is just a number and *may* drive a new,
independent analytic construction (forward, acyclic). Such a value is **approximate** (inherits
mesh resolution), which is acceptable exactly where it arises — clearance/play (e.g. a bore
sized for a rotating axle *wants* tolerance). UI should hint that mesh-derived values are
approximate, so a precision-critical dimension isn't unknowingly driven from one.

**Operations partition** into *analytic-preserving* (primitives, extrude/revolve/sweep,
booleans — exact + provenance, measurable, STEP-exportable later) and *mesh-only*
(offset/shell/hull/minkowski/smoothing, imported meshes — `Mesh` type, print/render-only,
never lifts back to analytic *as geometry*). The **strong type system (OP-5)** enforces the
boundary: `Solid`/`Face`/`Edge` (analytic) are distinct from `Mesh`; no mesh→analytic
*geometry* lift. (Scalars measured from a mesh may still feed forward — see below.)

### Representation families considered (background)
Three broad families (see OP-9 decision above):
  - **B-rep (OpenCASCADE / OCCT):** precise, real fillets/chamfers, native STEP; C++ with
    bindings; heavy; brings the topological-naming problem. Best fit for mechanical precision.
  - **CSG (à la OpenSCAD):** union/difference/intersection of primitives — literally
    "constructive," maps beautifully onto our DAG; simple & robust; but fillets and
    face-referencing are hard/awkward.
  - **Implicit / F-rep / SDF:** compose signed-distance fields; trivial booleans, easy
    blends/fillets, great for organic+mechanical mixes and 3D printing (mesh via marching
    cubes/dual contouring); but precise dimensions, exact edges and face identity are harder.
  - Note: for **3D printing** the final artifact is a mesh anyway, which softens the case for
    exact B-rep — but mechanical features (holes, precise fillets, datum faces) still favor it.
- **Topological naming problem**: re-identifying "this face/edge" after regeneration when the
  kernel renumbers. 2D is fine (identity = node); the pain is at the solid-kernel boundary.
  Note CSG/implicit representations sidestep some of this (no persistent B-rep topology to
  re-identify) but make "sketch on this face / fillet this edge" harder to express.

## Open points (to discuss one by one)

- [x] **OP-1 Branch/continuity policy** — RESOLVED: deterministic, orientation-based branch
      selector; model stays a pure function of parameters. Continuity tracking rejected as
      core (optional drag-only heuristic later).
- [x] **OP-2 Scope & phasing** — RESOLVED: 3D is the goal, 2D is a first-class sub-goal and
      the phase-1 implementation; design covers 3D up front. Primary delivery = 3D printing.
- [x] **OP-3 Undefined-state propagation** — RESOLVED: every node has a validity state.
      A node is *invalid* when its construction has no result (empty intersection, degenerate
      input). Invalidity **propagates transitively** to all dependents. Invalid objects are
      **hidden and flagged invalid**; their definitions are retained, so the model **heals
      automatically** when inputs return to a valid range.
- [x] **OP-4 Measurements** — RESOLVED: first-class derived Scalar/Angle nodes in v1;
      forward-only (driven), never cyclic; driving XOR driven. freeze-to-constant (a) in v1;
      re-parameterize-a-free-source (b) deferred on-demand; general inversion (c) out of scope.
- [x] **OP-5 Node graph data model** — RESOLVED: one uniform, strongly-typed dataflow DAG;
      unified numbers+geometry; exactly one output per node; intersections emit an ordered
      `PointSet` value consumed by a separate `Select(set, sign)` node (computed once, shared);
      topological eval with dirty-marking.
- [x] **OP-6 Macros / custom constructions** — RESOLVED: by-example definition;
      reference/composite instances with edit-propagation; instance = namespace of derived
      path-IDs `M/nk` (no output declaration; transparent groups); virtual addressing; pure
      functions of arguments (no per-instance overrides); specialization via partial
      application; adjustable-vs-constant parameter presentation flag; no recursion.
- [x] **OP-7 Expression language & units** — RESOLVED: named values + v1 expression language
      (arithmetic, common functions, `pi`, references; no conditionals); unit-aware scalars
      with dimensional analysis (Length/Angle/Dimensionless + derived Area/Volume), base units
      internal, display unit as presentation.
- [x] **OP-8 Topological naming** — RESOLVED: provenance-based sub-entity identity (constructed
      accessors with stable IDs, e.g. `box.topFace`); through booleans via Manifold's
      ID/property propagation; datums stay analytic. Topological-naming problem largely dissolves.
- [x] **OP-9 3D representation / kernel** — RESOLVED: analytic construction layer as source of
      truth + **Manifold** (guaranteed-manifold mesh booleans, JVM/Rust bindings) as the
      boolean/mesh/output engine; mesh is a terminal sink (see mesh-is-a-sink rule); fillets as
      explicit constructions; optional implicit sub-layer later. Ops partition analytic-
      preserving vs mesh-only, enforced by the type system.
- [x] **OP-11 CNC / STEP (B-rep) interop scope** — RESOLVED: precision CNC + STEP is a target
      but **not day-one → posture 1**. Ship printing-first on Manifold; **preserve exact
      analytic provenance** so a B-rep/STEP export (e.g. OCCT export-only backend) can be added
      later. Exact surfaces can't be reconstructed from a mesh after the fact.
- [x] **OP-10 Implementation platform** — RESOLVED: **JVM + Kotlin (Kotlin Multiplatform)**,
      shared engine on JVM (server/desktop) and browser (Kotlin/JS, Kotlin/Wasm). Ruled out
      C/C++, TS/JS, and Rust (error-model ergonomics). Kotlin over Java for the browser story.
- [x] **OP-12 Deployment / UI architecture** — RESOLVED (principles): layered — pure Kotlin
      engine (no shell deps) → client-side hand-rendered canvas (engine compiled to browser) →
      document/serialization format → shell deferred (start fat browser client / desktop
      JavaFX harness, file persistence). Client stack = Kotlin (TL-as-shell a non-requirement,
      so GWT/J2CL not indicated; Flutter would sacrifice the shared engine). TL module and
      server-side 3D compute remain valid non-driving later options.
- [x] **OP-20 Where things meet** — RESOLVED: a meeting point is a `Junction` that **owns** the shared
      freedom, with everything meeting there bound to it. Fixes the order-dependent *attribution* of
      DOF that made two runs at one junction behave differently; drags and typed values both reach the
      shared freedom through the junction, in closed form. See *Junctions own the freedom*.
- [x] **OP-19 Break / join legs** — RESOLVED and built: threshold-triggered topology edits by
      gesture (join on jog collapse, committed on release; break as a tool inserting a zero-length
      perpendicular). Required ortho coordinates to move from *shared* nodes to *bound* ones — a
      binding can be re-pointed in place, which is what makes a jog expressible. See *Break and
      join legs*.
- [x] **OP-18 Document format** — RESOLVED: a **construction script** — the sequence of steps that
      built the drawing, replayed on load. Stores no node kinds, nothing synthetic (handles, styles,
      path/wall structure) and no separate values section: a step's literals are written as the
      current value of what it introduced. One generic `tool` step covers every tool. See *Document
      format* under the editor architecture.
- [x] **OP-13 Dragging vs. numeric entry** — RESOLVED: the **same operation**. One `Handle` per
      grabbable DOF, with a continuous binding (drag) and a discrete one (typed fields); a field
      is a re-parameterization of a single free node, never a new node and never a consumed DOF.
      A quantity spanning two vertices (a leg length) therefore has one field *per end* — the
      field belongs to the handle that moves, which is what makes "which end moves?" unambiguous
      without an anchor picker. See *Handles* under the editor architecture.
- [x] **OP-14 Result layer (construction vs. output)** — RESOLVED: the result is **constructed, not
      flagged**, because it is not a subset of the construction elements (an outline needs *trimmed*
      pieces). Adds trim ops (`segmentBetween`/`arcBetween`/`subCurve`) and `Loop`/`Region` values;
      scaffolding reads as scaffolding because nothing consumes it. Boundary-tracing *Outline* tool.
      Interior-seed region detection **rejected** (discovered identity re-imports OP-8 into 2D).
      Result-vs-scaffolding (semantic) / hidden (presentation) / layer (organizational) kept distinct.
      **Implemented end to end**: trim ops, `Loop`/`Region`, `CircleE`, exact areas, transforms and SVG
      over the new values, the boundary-tracing *Outline* tool (variable arity via `ToolDef.repeating`),
      graph-derived scaffolding with a dim toggle, and save/load for free. Remaining: traced regions
      *with holes*, and the wall rework (OP-21).
- [x] **OP-15 General curves & splines** — RESOLVED: splines are in and fit natively — a spline is a
      pure function of its control points, and those control points may themselves be *constructed*
      (the bridge from technical construction to smooth geometry). Continuity by construction (G1 via a
      shared `Direction`, G2 via the closed-form end-curvature locus), never by constraint. A linear
      (tridiagonal) solve is **not** a solver; numeric curve intersection is approximate but
      deterministic — determinism is load-bearing, not closed form. Adds a general `CurveValue`
      (largest refactor) and a **2D analog of the mesh-is-a-sink rule**: exact analytic curves vs.
      approximated curves (spline offsets, general fillets). Order: Bézier/B-spline → fit-through-points
      → NURBS weights last. **Cubic Béziers implemented** — exact closed-form area (verified against
      numerical integration), affine invariance, tangency by construction, and a spline sitting in a
      traced boundary beside segments and arcs. Splines are *built onto* their neighbours rather than
      trimmed to them; parametric trimming and fit-through-points remain.
- [x] **OP-16 Groups, frames & placement** — RESOLVED: a group carries its **own coordinate frame**;
      internals are local; **moving a group edits the frame** (one literal write, O(1)) rather than
      transforming every point. The frame is a `Handle` (OP-13), so groups move by drag and by number.
      Grouping an existing construction is a DOF-preserving retrofit to frame-relative form; **move /
      relocate-origin / re-parent** are one code path with three invariants (only relocate-origin is
      O(N) and touches internal free coordinates). A **constructed** frame is a *mate* — so phase-3
      assemblies need no solver. Group frame ≡ sketch plane (OP-17) one dimension down. Build order:
      multi-select → flat group → placed group → relocate/re-parent/mates/macro promotion. **Steps 0–2
      built**: a selection set with a primary element, a marquee (panning moved to the middle button),
      bulk hide/delete, flat named groups recorded as a `group` step, and the **frame** — one
      `FrameValue` source node plus a `frameApply` op, with placement binding the group's own free
      points onto it through the existing weld substrate (world-invariant, DOF-preserving, invertible);
      see that section's implementation status. Remaining: relocate-origin / re-parent / constructed
      frames (mates), macro promotion, and ortho paths under a frame.
- [x] **OP-21 A wall is an output feature** — RESOLVED: a wall belongs to the **result layer**
      (OP-14), and the same description must feed the seam (OP-17), because a floor plan is a route
      into 3D. The first wall implementation needed rework for two independent reasons: it was
      **regenerated** (`ownedIds` + `elements.removeAll`, so orphaned nodes accumulated and nothing
      could depend on the wall as a value), and it was **not a pure function of its parameters**
      (openings were sorted by `evalMm` at graph-construction time, so dragging one opening past
      another left stale structure). General rule extracted: value-dependent work belongs inside a
      node's `compute`, never in the builder. **Built** as the generic `ThickPath` + `PathInterval`
      (the model says nothing about walls — session-3 directive (a)); see that section's
      implementation status. Deeper correction: **the plan gap is a drawing convention, not a cut** —
      in plan a wall is unbroken at a window (wall below the sill, wall above the head), so one
      description projects to **two outputs**: the plan drawing (gaps, jambs, swing arcs as
      convention) and the solid (`extrude(footprint)` minus a sill→head box per opening). A wall
      therefore emits `thickFootprint(...) → Region`; a closed carrier yields
      `Region(outer, [inner])`, so a wall ring *is* OP-14's hole machinery. Junctions are trimmed
      **by construction** at the neighbour's face line rather than by adding 2D region booleans.
      Walls lead in 2D (a strong forcing function for the result layer) and follow in 3D (the
      mechanical triad still exercises more of the seam).
- [x] **OP-17 The 2D↔3D seam** — RESOLVED: `Sketch = SketchOn(plane, [Region])`; the OP-14 result layer
      *is* the 3D interface. 2D stays abstract 2D and the plane embeds it (so one construction is
      reusable on several planes — OP-6 semantics at the seam); a plane is `(origin, u, v)` derived from
      face + edge for stability. Two-way type conversion, **one-way dataflow** (OP-4 acyclicity).
      Holes as inner loops for same-depth through-features, boolean once depths differ. First 3D slice
      is **mechanical, not walls** (a wall is a degenerate seam): flanged plate with a counterbore →
      turned part (revolve) → sketch-on-face boss (the actual risk).
      **Engine core built** (`geom/Geom3.kt`, `SolidTest`): planes, `sketchOn`, extrude, revolve, an
      indexed watertight mesh sink, `facePlane` provenance accessors and volume/bbox measurements — all
      three slices run, with deterministic hole-bridged ear clipping and a documented 0.02 mm world
      tessellation tolerance. See *Implementation status (as built — 3D engine core)*. Cut from this
      slice: booleans (the counterbore), 3D transforms, any UI/viewport/document-format support.

## Prior art to keep in mind

- Dynamic geometry: GeoGebra, Cinderella, Cabri
- Parametric dataflow: Grasshopper (Rhino), OpenSCAD
- Feature-based solid CAD: SolidWorks, FreeCAD (and its topological-naming struggles)
- Kernel: OpenCASCADE (OCCT)

## Discussion log

- **Turn 1** — Established the core idea: construction-based parametric model (DAG of pure
  constructions), no constraint solver. Identified intersections (branch selection /
  continuity) as the central hard problem. Sketched primitive algebra and the 2D→3D path.
  Captured open points OP-1..OP-8.
- **Turn 2** — Resolved OP-2 (scope & phasing): 3D is the goal (primary delivery = 3D
  printing), 2D is a first-class sub-goal and phase-1 implementation; design covers 3D up
  front. Added deliverables (STL/3MF for 3D; SVG/DXF/PDF for 2D) and a 3-phase plan. Split
  out OP-9 (3D representation/kernel: B-rep vs CSG vs implicit) and OP-10 (platform).
- **Turn 3** — Resolved OP-1: deterministic, orientation-based branch selector (side-of-line
  for circle–circle, along-line order for line–circle); model stays a pure function of
  parameters; continuity tracking rejected as core (optional drag-only heuristic later).
  Resolved OP-3: per-node validity, transitive invalid propagation, hide+flag, auto-heal.
  Recorded OP-10 as deferred with platform constraints (avoid C/JS, web acceptable).
- **Turn 4** — Resolved OP-5 (node graph data model): one uniform strongly-typed dataflow
  DAG, unified numbers+geometry, exactly one output per node. Key refinement (user's idea):
  intersections emit an ordered `PointSet` value consumed by a separate `Select(set, sign)`
  node — computed once, shared, uniform. Relocated OP-1's branch selector onto `Select`.
  Confirmed strong typing. OP-4/6/7 shown attaching cleanly to the model.
- **Turn 5** — Resolved OP-4 (measurements): first-class derived Scalar/Angle nodes in v1,
  forward-only (driven), never cyclic (driving XOR driven). Clarified freeze/convert:
  (a) freeze-to-constant in v1; (b) re-parameterize a free source = a DOF-preserving
  coordinate change (no solver), deferred on-demand; (c) general inversion out of scope.
- **Turn 6** — Resolved OP-7 (expressions & units): named values + v1 expression language
  (arithmetic, common functions, `pi`, references; no conditionals in v1); unit-aware scalars
  with dimensional analysis (Length/Angle/Dimensionless + derived Area/Volume), base units
  internal, display unit as presentation.
- **Turn 7** — Resolved OP-6 (macros): by-example definition; reference/composite instances
  with edit-propagation. Key model (user's idea): an instance is a namespace whose internal
  nodes are addressable via derived path-IDs `M/nk` — no output declaration, transparent
  groups; unifies with `PointSet`+`Select` as "compound value + accessor"; implemented via
  virtual addressing. Instances are pure functions of arguments (no per-instance overrides);
  fixed variants via **specialization/partial application** (e.g. `standardRect` from
  `roundedRect`); added adjustable-vs-constant parameter presentation flag; no recursion.
- **Turn 8** — Explored the 3D representation (OP-9), incl. B-rep/OCCT vs CSG vs implicit, and
  evaluated **Manifold** (verified via web: guaranteed-manifold mesh booleans; C++ core with
  JVM/Rust/WASM/Python bindings; ID/property propagation; used by OpenSCAD/Blender/Godot).
  Resolved OP-11 (CNC/STEP a target but not day-one → posture 1: printing-first, preserve
  analytic provenance for later B-rep/STEP export). Resolved OP-9 (analytic layer = source of
  truth + Manifold as boolean/mesh/output engine; fillets as explicit constructions) and OP-8
  (provenance-based identity via Manifold IDs + analytic datums). Established the
  **mesh-is-a-sink rule** and the analytic-preserving vs mesh-only operation partition,
  enforced by the type system (user's framing: mesh is output/rendering only, save for
  mesh-only ops, imported meshes, and terminal mass-property read-outs).
- **Turn 9** — Refined the mesh boundary (user's correction): "no mesh→analytic lift" applies
  to **geometry** only. A **scalar measured from a mesh** may drive a **new, independent
  (non-ancestor) analytic construction** forward (acyclic); only feeding back into the mesh's
  ancestors is a cycle (OP-4). Such values are **approximate** (mesh resolution) — acceptable
  where clearance/play is wanted (e.g. bore for a rotating axle); UI should flag them.
- **Turn 10** — Resolved OP-10 (platform): **JVM + Kotlin (Kotlin Multiplatform)** — ruled out
  C/C++, TS/JS, Rust (error-model ergonomics); Kotlin over Java for the shared browser engine.
  Resolved OP-12 (deployment/UI architecture): layered — pure Kotlin engine (no shell deps),
  client-side hand-rendered canvas (engine compiled to browser), document format, deferred
  shell (start fat browser client / JavaFX harness). Client stack = Kotlin; **TL-as-shell is
  an explicit non-requirement** (so GWT/J2CL not indicated; Flutter would sacrifice the shared
  engine; app is canvas-centric not widget-centric). TL module and server-side 3D compute kept
  as non-driving later options.
- **Turn 11** — Noted a testing dividend of the architecture: the pure headless engine + SVG
  output + deterministic model (no solver) enable **construct-in-code → render SVG → assert**
  tests. Recorded the testing strategy: value-level assertions + SVG golden/snapshot; requires
  a programmatic construction API and a canonical deterministic SVG serializer; test cases
  double as worked spec examples and extend to STL/3MF in phase-2.
- **Turn 12** — Implemented **Phase 1** (see PHASE1_PLAN.md): Kotlin/JVM 2D construction engine
  (units+dimensions, typed DAG + memoized eval with invalid propagation, geometry +
  ordered-solution-set intersections + Select, macros with derived path-ids + specialization,
  canonical SVG serializer). All four DoD examples pass at value + SVG-golden level; 16 tests
  green via Gradle 8.7 wrapper. Deferred within phase-1: OP-7 string parser, incremental
  dirty-recompute, interactive UI, DXF/PDF export.
- **Turn 13** — Completed the primitive/operation algebra (Tier 1–3): relational ops
  (perp/parallel-through, perp-bisector, angle-bisector, project-to-line, point-on-line/circle,
  circle-through-point), general affine transforms on any geometry (mirror/rotate/scale/
  translate), scalar functions (mul/div/sqrt/trig/atan2/abs/min/max/pow/mod), measurements
  (angle/length/radius/x/y), fillet arc, tangents (from-point, common), ray/circumcircle/
  arc-through-3/direction, and a Profile type (bridge to 3D). Added showcase tests with
  known-answer invariants (Euler line, Thales, tangent lengths, fillet tangency, obround,
  hexagon, golden-ratio pentagon). 33 tests green.
- **Turn 14** — Built the interactive canvas as a Kotlin Multiplatform app (deferring the OP-7
  string parser). Chose browser (Kotlin/JS) over JavaFX (DOM UI far cheaper; real target).
  Elastic layering: pure engine/document/scene/camera/interaction core in commonMain behind a
  DrawTarget interface; thin jsMain shell (HTML5 canvas + DOM). Phases A–D: KMP restructure,
  pure interaction core (headless gesture tests + SVG scene snapshot), browser shell, and
  Playwright E2E driving system Chrome with screenshots. 40 jvm tests (1 E2E, opt-in).
- **Turn 15** — Editor tool set filled out to the full 2D algebra (data-driven `ToolDef` registry,
  categorized palette, scalar tools, existing-only slots, unique scalar names); parameter wiring;
  measurement-as-input; carrier-line coercion; arc hit-testing.
- **Turn 16 — welding & drag-to-attach.** Joining two free points is **point-level wiring**, not a
  solver constraint: `SourceNode` gained `boundTo` (mirroring `ParameterNode`), so welding an alias
  onto a master mutates the alias in place and every reference follows (−2 DOF). Exposed as a Join
  tool + drag-to-weld magnet (halo). Generalized to **drag-to-attach**: dropping a free point on a
  line/circle makes it a point-on-curve.
- **Turn 17 — architectural layer** (see below): ortho path, walls (offset + mitre), openings
  (parametric gaps + jambs) — all macros over the core, no solver.
- **Turn 18 — editing model rebuilt.** The ortho path moved from a relative leg-chain to the
  **shared-coordinate model** (see *Implementation status*): local vertex dragging (only the vertex
  + its two neighbours move), symmetric loop closing (the pre-closing vertex keeps 2 DOF), and open
  endpoints that weld/attach to points/lines while the neighbour keeps its DOF. This **supersedes**
  the direction/frame/length-parameter sketch in *Axis-aligned / perpendicular lines* below.
- **End of session 1.** Pushed to `github.com/haumacher/constructit` (README added). 72 jvm tests
  green; every feature verified live in-browser via Playwright. The 2D engine + interactive editor
  + an architectural drawing layer are working; 3D remains designed-but-unbuilt.
- **Session 2, design turn — result layer, curves, groups & the 2D↔3D seam.** Resolved four coupled
  open points raised by the user: most construction elements are technical and should not be part of
  the result (OP-14), constructions should be able to drive smooth "drawing-application" geometry
  (OP-15), there is no grouping mechanism and transforms only touch one element (OP-16), and the 2D
  output should drive a follow-up 3D construction (OP-17). Three of the four turned out to be the same
  missing concept — an explicit **output set / compound value** — and OP-14 is a hard prerequisite for
  OP-17 because `Loop`/`Region` *are* the 3D interface. Two user corrections shaped the outcome:
  (a) extruding **walls** is the wrong first 3D slice — architecture is a niche, the target is general
  mechanical engineering, and a wall is besides a *degenerate* seam (no back-flow, no face datum), so
  the first slice became the flanged-plate → turned-part → sketch-on-face triad; (b) **moving a group
  should move its coordinate frame**, not apply a transform to every point — which made the frame a
  first-class source node (an O(1) literal write, a `Handle` per OP-13), and made "relocate a group's
  origin" the *world-invariant* variant of the same operation, the one case that legitimately rewrites
  internal free coordinates. Two spin-offs worth recording: a **constructed** frame is an assembly
  *mate*, so phase-3 assemblies need no solver either; and the group frame is the same concept as the
  sketch plane, so 2D frames prototype the 3D seam. Agreed build order: multi-select → flat groups →
  frames/placement → result layer + trim → 2D↔3D seam → splines.
- **Session 2 — OP-14 engine half built.** Started with the result layer rather than multi-select,
  inverting the agreed order for one reason: multi-select is `Editor.kt` surgery, which is exactly
  where the in-progress ortho-path work lives, whereas trim + `Loop`/`Region` is additive in `geom/`,
  `core/` and `dsl/` and touches none of it. Three things made it cheaper than expected: a trimmed
  line already *is* a `Segment` and a trimmed circle *is* an `Arc`, so no new curve type was needed;
  `Dimension.AREA` already existed, so the roadmap's area measurement fell out; and the general
  `CurveValue` refactor (OP-15) turned out **not** to be a prerequisite after all — it is only needed
  once splines arrive. One design decision worth noting: `loop` chains pieces in the order given but
  *flips* them as needed, because order carries the boundary's identity while a piece's stored
  direction is an accident of how its inputs were picked. 107 jvm tests green.
- **Session 2 — piece dispatch, then OP-21.** Consolidated `ProfileElement` dispatch into `GeomMath`
  and removed `Svg`'s `else` on the sealed `Value` dispatch, after establishing (by probe, not
  assumption) that Kotlin 1.9 makes a non-exhaustive `when` over a sealed type an error in *statement*
  position too — so the compiler already lists every site a new piece kind must be handled, which is
  the argument for keeping the sealed hierarchy rather than a `BoundedPiece` interface. Then resolved
  **OP-21** on the user's prompting: a **wall is an output feature**, not an architectural side-show,
  so it belongs to the result layer and the same description must reach the seam. Two defects named in
  the as-built wall (regeneration with orphaned nodes; openings sorted by value at build time, so the
  wall is not a pure function of its parameters) and one deeper correction: **the plan gap is a drawing
  convention, not a cut** — in plan a wall is unbroken at a window, so one description projects to a
  plan drawing *and* a solid. A closed wall centerline turns out to yield `Region(outer, [inner])`,
  making walls a genuinely good forcing function for OP-14 rather than the niche they were positioned
  as — while the mechanical triad still leads in 3D, since a wall extrude exercises less of the seam.

- **Session 2 — the output layer, end to end, with splines.** Built the *Outline* tool over the OP-14
  engine, plus cubic Béziers (OP-15) to prove the boundary machinery generalises beyond segments and
  arcs. Five things worth recording. (1) Variable arity stayed data-driven: `ToolDef.repeating` repeats
  the last slot, so a tool that takes "as many curves as you click" needed no controller special case —
  unlike the ortho path, which predates the idea. (2) The Bézier area needed a **derivation**, not a
  recollection; it is checked against 200 000-step numerical integration, and the check earned its keep
  when two *test* expectations turned out wrong while the implementation was right. (3) A **two-piece
  boundary** is a genuine special case: each piece is the other's neighbour on both sides, so they must
  meet at two different places — taking the nearest meeting twice collapses both pieces to a point.
  This surfaced as three failing tests with one root cause. (4) Splines are **built onto** their
  neighbours, not trimmed to them, which is the paradigm's own answer and avoids needing de Casteljau
  yet. (5) Scaffolding is the **ancestor closure of the results**, so it needs no flag and cannot drift;
  geometry no result uses is simply unused rather than scaffolding. Verified in a real browser
  (screenshots under `tmp/`): trace an arch over a construction line, then dim the construction and only
  the drawing remains. 151 jvm tests green.
- **Session 3 kickoff — scope directives (user).** Four persona showcases were proposed (parametric
  gear → STL; floor plan → 3D house; reverse-engineered spare part; papercraft net for a plotter)
  and accepted **as examples only**: every feature must be **generic**, never trimmed to a use
  case. Concrete corrections recorded: (a) the "wall" implementation is too specific, *even its
  name* — it is a convenience for **"thick" paths**; the UI may say "wall", the model must not.
  (b) Multi-storey + roof is a showcase of a **multi-step 2D→3D→2D→3D dependency chain** and must
  need no special code. (c) **No unfolding algorithm** for papercraft: the user constructs the net
  *manually* from the 3D model — measurements taken from 3D drive a 2D construction (the OP-9
  scalar-forward rule doing real work), output goes to a plotter. (d) **No format compliance yet**:
  concentrate on modeling features, UI/UX, 2D *and 3D* visualization, and workflow; import/export
  may exist informally. (e) Gear: a **sampled involute** (deterministic approximation at tolerance)
  in a macro — OP-15 proceeds independently, not as a gate. (f) Ordering of the editor baseline is
  delegated, with one rule: **deliver nothing half-done**.
- **Session 3 — the wall becomes a thick path (OP-21).** Reworked the wall end to end into a generic
  output feature and, in doing so, closed both defects OP-21 named. Five things worth recording.
  (1) The fix for *"not a pure function of its parameters"* is a **placement** rule, not an algorithm:
  the opening sort moved from the builder into the computation, and the property came back for free.
  The count of intervals is structural, their order is not — only the latter may move inside.
  (2) Making the footprint **one node** rather than a bundle of face segments is what removed
  regeneration: with one `Region` value there is nothing to delete and rebuild, so an interval edit
  cannot orphan anything. `Construction.nodesCreated` exists only to let a test assert that.
  (3) The **plan gap is a drawing convention** — moving it to `SceneRenderer` is what let the footprint
  stay unbroken, and it is why the same description will feed the solid.
  (4) Recording the interval as a *description* (`opening e4 leg=0 pos=25mm width="w" …`) rather than as
  the click that placed it paid three ways at once: a typed position now survives a reload (it silently
  did not), delete's dependency walk catches intervals through the ordinary explicit-reference rule, and
  the wall/opening special case in `dependentSteps` disappeared. A step that names what it belongs to
  needs no bespoke cascade.
  (5) The **name mattered**: calling it `ThickPath`/`PathInterval` (with *Wall*/*Opening* surviving as
  tool labels) is what made justification, sill and head fall out as ordinary properties instead of
  wall-specific extras. 178 headless tests pass, plus two new plan goldens; verified in a real browser
  (screenshots under `tmp/`).

## Domain layer: architectural drawing (draft — no new solver)

> **As-built note (Turn 18):** axis-alignment is realized by the **shared-coordinate** model
> (each vertex `pointXY(x,y)` sharing one coordinate node per neighbour), *not* by the
> direction/frame/length-parameter approach sketched immediately below — that sketch is kept as
> design rationale. See *Implementation status (as built)* for what actually ships.

Architectural drawing is a **macro/vocabulary layer over the existing geometric algebra** — it
needs no constraint solver and no new evaluation semantics. Three existing pieces are the enablers:
**shared direction references** (axis-alignment by construction), **offset + line-line intersection**
(mitered wall corners), and **point-on-line** (the sliding 1-DOF param, used for opening placement).

### Axis-aligned / perpendicular lines
Do not store a "horizontal" flag and solve it — construct so it *cannot* be otherwise.
- **Persistent alignment via directions.** A segment as `start + direction·length`, where
  `direction` is a *shared reference* to a project axis (the `Direction` primitive already exists).
  Editing = changing a **length parameter**, not free-dragging — dimension-driven, pure DAG.
- **Project frame.** Two named axes (X/Y), optionally **rotated**, so a building sited at an angle
  still draws "orthogonally" in its own frame; rotating the frame rotates everything that
  references it.
- **Ortho input aid** (fast entry): while drawing, snap the direction to the frame axes / 90° and
  **store the axis reference** (not baked coordinates) so alignment persists.
- Motivates a **wall-path / turtle tool**: "from here go 4m east, 3m north…", each leg
  `prev + axisDir·lengthParam` → axis-alignment + parametric dimension chains for free.

### Wall pairs (thickness)
A **Wall = centerline path + thickness parameter + justification** (center/left/right), a macro
emitting two offset faces. The corner problem is solved by existing machinery:
- Each leg's face = `parallelAtDistance(centerlineLeg, ±w/2, side)`.
- Each **corner point = `intersectLL`(adjacent face lines)** — that *is* the miter joint.
- End caps = perpendiculars at the path ends.
- Compound value with accessors (OP-6): `wall.face(side)`, `wall.corner(i)` — reusable for
  dimensioning / opening placement / wall-to-wall snapping.
- Special case: collinear legs → offset lines parallel (intersection at infinity) → fall back to
  the plain offset point.

### Openings (windows / doors — gaps in a wall)
An opening = **position** (distance along the centerline) + **width**, plus **sill/head heights**
carried for later 3D even though invisible in 2D.
- Position *is* a point-on-line on the centerline (reuses the sliding constraint); width is a param.
- Render the plan gap with door-swing arcs / window mullions as symbols.
- 3D (later): wall extrudes to a slab; each opening becomes a subtracted box (sill→head) — analytic
  params drive the boolean, matching the "mesh is a sink" plan.

> **Superseded in part by OP-21** (below): the sketch above has the Wall macro produce *segmented
> faces*, cutting the plan geometry at each opening. That conflates the plan drawing with the solid,
> and it is one of the two reasons the as-built wall needs rework. See *A wall is an output feature*.

## A wall is an output feature (OP-21 — RESOLVED)

A wall is the first thing in this model that is **not** construction geometry: nobody wants a wall's
offset lines and mitre intersections in the finished drawing, they want the wall. So a wall belongs to
the result layer (OP-14) — and because a floor plan is a legitimate route into 3D, the same
description must also feed the seam (OP-17). That reframing is what the as-built implementation gets
wrong, in two independent ways.

### Why the first wall implementation needed rework
*(Both defects are fixed — see* Implementation status *below. The diagnosis is kept because the rule it
yielded applies to every feature, not just this one.)*

**1. It was regenerated, not computed.** `Document.regenerateWall` deleted the elements it previously
owned (`ownedIds`) and rebuilt them. Two consequences: the *nodes* behind the removed elements stayed
in the `Construction`, so every opening edit grew the graph monotonically; and the wall was not a
value anything could depend on, only a bundle of loose `SEGMENT` elements that got replaced.

**2. It was not a pure function of its parameters.** The build sorted each leg's openings with
`sortedBy { evalMm(it.position) }` — at *graph-construction* time. Structure therefore depended on the
values held when the wall was built, so dragging one opening past another left the faces split in
the stale order until something regenerated. This is precisely the property the whole model exists to
guarantee (OP-5), broken by doing value-dependent work in the builder.

**The fix for (2) is the general rule:** anything value-dependent belongs *inside* a node's `compute`,
never in the code that assembles the graph. Sorting openings inside `compute` is pure and re-sorts
itself on every pass. Note the count of openings is structural (known when building) while their
*order* is not — only the latter must move inside.

### The plan gap is a drawing convention, not a cut

The deeper error is treating an opening as a gap in the wall's plan geometry. In plan, a window does
not interrupt the wall at all — below the sill there is wall, above the head there is wall. Even a
door leaves a lintel. **The footprint is unbroken** unless an opening is genuinely full-height, which
is a pass-through, not an opening.

So one parametric description projects to **two different outputs**:

| output | what it is | openings appear as |
|---|---|---|
| **plan drawing** | the printed drawing | a *convention*: faces drawn broken, jamb/reveal lines, swing arcs, mullions |
| **solid** | what extrudes (OP-17) | boolean subtraction boxes, sill→head |

Two outputs, one description — the same "output set at every level" point OP-14 ends on. Conflating
them is what made openings cut the footprint.

### What a wall emits

- **`thickFootprint(carrier, thickness, justification) → Region`** — the offset faces, mitred
  corners (`intersectLL` of adjacent face lines, as already designed) and end caps, assembled as a
  closed oriented `Loop`. An **open** carrier gives a single loop; a **closed** one gives
  `Region(outer, [inner])` — a wall ring is exactly OP-14's hole machinery, which is why walls are a
  good forcing function for the result layer rather than a niche. The name is deliberately not
  "wall": this is the generic **thick path**, and a wall is one use of it (session-3 directive (a)).
- **Interval features** — the UI's openings — stay `(position, width, sill, head)` parameters and do
  **not** touch the footprint.
- **3D** is then `extrude(footprint, height)` minus one box per opening — analytic parameters driving
  the boolean, as OP-9 already prescribes.
- Accessors per OP-6 (`wall.face(side)`, `wall.corner(i)`) stay, for dimensioning and wall-to-wall
  snapping.

### Junctions: trim by construction, don't reach for 2D booleans
Where two walls meet (T or L), the *union* of two footprint regions is the honest description — but
2D region booleans do not exist here and adding them would be a large, solver-adjacent detour. They
are not needed: a junction is already expressible **by construction**, trimming each wall's face at
the neighbour's face line, which is the same `intersectLL` that makes a mitre. Prefer that. (This is
also consistent with OP-20 owning the freedom where things meet.)

### Ordering — and an honest correction
Reworking the wall onto the result layer is worth doing **before** the hand-tracing *Outline* tool:
the wall needs rework regardless, it produces regions programmatically (so it exercises OP-14 with no
new UI), and it is the driver for the plan→3D story.

This partly revisits OP-17's "first 3D slice is mechanical, not walls". Both hold, on different axes:
for the **2D output layer** a wall is a strong forcing function (multi-loop regions, rings, holes);
for the **first 3D slice** the mechanical triad still exercises strictly more of the seam (sketch on a
face, provenance accessors, the sketch→feature→sketch loop) than a wall extrude does. So walls lead
in 2D and follow in 3D.

### Implementation status (as built — the thick path)

The rework ships as one generic feature. **`ThickPath`** (`editor/Document.kt`) is a carrier polyline +
`thickness` + `Justification` + a list of **`PathInterval`**s (`position`, `width`, `sill`, `head`); the
UI still says *Wall* and *Opening* and nothing in the model does.

- **One node, one element.** `Construction.thickFootprint(vertices, thickness, closed, justification)`
  emits a single `RegionValue`, displayed by one `AREA` element. Editing the carrier, the thickness or
  any interval **recomputes** it: no element is replaced and no node is orphaned. `Construction`
  counts the ids it has handed out (`nodesCreated`) purely so a test can assert that — it is the one
  observable that separates computing a feature from regenerating it.
- **The geometry lives in `compute`.** `GeomMath.thickFaces` / `thickRegion` are functions of *values*:
  leg directions, the mitres, and (for a ring) which offset side is the outer boundary — decided by
  comparing enclosed areas, so it survives a carrier being reshaped or reversed. Only the *count* of
  carrier vertices is structural, and that is what the node's input list carries. A collinear pair of
  legs, a zero-length leg or a zero thickness make the node invalid **with a reason**, and it heals
  (OP-3).
- **Interval order is read per pass.** `Document.planOf` sorts each leg's intervals by their *current*
  position while producing the plan, so moving one past another needs no rebuild at all — the headline
  regression test asserts the plan re-sorts while the node count, the element list and the footprint
  value are all untouched.
- **The plan gap is a drawing convention.** The footprint region stays whole; `SceneRenderer` draws a
  thick path's footprint as broken faces + jamb (reveal) lines + end caps, derived at render time from
  the footprint and the intervals. Two goldens cover it (open run, closed ring with a door).
- **Justification** center/left/right is implemented, recorded in the file, and selectable in the
  browser panel ("Wall side"). It is defined by the carrier's own traversal direction (left = +90°), so
  it needs no inside/outside — which an open carrier does not have.
- **Every DOF stayed reachable (OP-13).** An interval's position, width, sill and head are named
  parameters, hence typed fields; the width is *shared* with whatever the tool was given, so two equal
  openings are equal **by construction**. The carrier is a plain ortho path, dragged and typed exactly
  as before. Sill/head are new: they were designed-in but previously unreachable.
- **The file records the description** (OP-18): `wall "t" center` and
  `opening e4 leg=0 pos=25mm width="w" sill=0mm head=2100mm`. Because the interval step *names the
  footprint it belongs to* and restates the values it introduced, three things follow — a typed
  position now survives a reload (it silently did not before), delete's dependency walk catches
  intervals through the ordinary explicit rule, and `Document.dependentSteps` lost its wall/opening
  special case entirely. Deleting one opening no longer drops the later ones.

**Deliberately not done here.** Wall-to-wall **junction trimming** (T/L merges) stays future work: two
thick paths meeting still overlap, which is visible and known. Per OP-6 **accessors** on the footprint
(`face(side)`, `corner(i)`) are not built — nothing consumes them yet, and they are what dimensioning
and wall-to-wall snapping will need. One capability was traded away with the loose face segments: a
wall face is no longer a `SEGMENT` element, so it cannot be snapped or attached to. That is the correct
direction (OP-21's whole point is that offset lines are not the drawing), and the replacement is those
accessors, not the old bundle. **3D** (`extrude(footprint, height)` minus one box per interval, sill→head)
is untouched — the description it needs is now in place.

### Build order (MVP-first)
1. Directions + project frame + ortho input (fast axis-aligned drawing).
2. Wall-path tool (turtle/relative legs with length params) — the dimension-chain backbone.
3. Wall thickness macro (offset + miter).
4. Openings (position + width, reusing point-on-line; carry sill/head).
Then 3D walls = extrude + boolean.

### Implementation status (as built)
- **Slice 1 — ortho path** (`Tools.ORTHO_PATH`): rectilinear polyline as a **bound-coordinate
  model** — each vertex is `pointXY(x, y)` and *owns* both nodes; a leg is axis-aligned because one
  endpoint's coordinate is `boundTo` the other's (a horizontal leg binds `y`, a vertical one binds
  `x`). Consequences, all solver-free:
  - **Local editing** — dragging a vertex writes the *master* of each coordinate's binding chain
    (`writableMaster`), so the vertex and exactly the neighbours resolving to the same node move; no
    downstream cascade, and edges stay axis-aligned by construction (`OrthoCornerHandle`).
  - **Closing** is just one more binding: the last vertex's own coordinate is bound to the start's, and
    because a drag writes the master, the vertex before the closing edge keeps 2 DOF like every other
    corner — no special drag-redirect handle. Closing is triggered by clicking the start.
  - Binding rather than sharing one node is what keeps the topology **editable**: a binding can be
    re-pointed in place, which is what break and join need (OP-19). Sharing could not express a jog at
    all — see that section.
  - **Immovable is explained, not silent** — connecting a path end binds a coordinate node, and
    because that node is *shared* with the neighbour, the adjacent leg's single DOF goes with it. The
    leg is then immovable by construction, which is correct but invisible, so a dead drag reads as a
    bug. `Handle.dragNodes` states what a drag writes (deliberately not defaulted, and *not* the union
    of the fields' nodes — a leg's length fields write the nodes along it, which its perpendicular drag
    never touches). An element whose drag is inert is still selectable, so its values stay readable,
    and the status bar names which value is driven.
  - **Axis-locked dragging** — with `Editor.axisLock` (Shift in the browser shell) a drag keeps only
    the component its gesture is dominated by, measured from where the drag began, so one coordinate
    of a corner can be changed without disturbing the other. Filtering the position before dispatch
    means it works for every handle kind; a leg, already single-axis, is exempt.
  - **Leg dragging** — a whole leg is a handle too (`OrthoEdgeHandle`). Its endpoints share the
    coordinate perpendicular to it, so the leg has exactly one DOF of its own: dragging it writes
    that single node, moving both ends together and stretching the two neighbouring legs. This is
    the direct way to change one coordinate without disturbing the other.
  - **Numeric editing** — per OP-13 every one of those drags is also a typed field. A vertex offers
    `x`, `y` and the length of the leg that created it; a leg offers its perpendicular position plus
    its length *from either end* (`length (move end)` / `length (move start)`), because a length
    spans two vertices and each end is a separate write. A field over a driven node (welded,
    attached, loop-closed) reports itself unwritable. Clicking in SELECT mode sets `Editor.selection`
    (a corner, or a leg — corners win) and the shell renders its fields as an inspector, so the
    numeric form of every drag is reachable without any per-tool UI code.
  - The path is retained as an `OrthoPath` (vertices in draw order + a segment element per leg), so
    legs are addressable — which is what lets a leg's length find the neighbour supplying its other
    end. A `ThickPath` (OP-21) keeps a reference to the carrier path it was built over.
  - **Direct distance entry** — while a leg is previewed the mouse supplies its *direction* and the
    keyboard its *length*: type digits, Enter places the leg at exactly that length (Backspace edits,
    Esc cancels the entry before it finishes the path). The preview shows the typed length, and the
    placed leg is indistinguishable from a clicked one — same construction, so the length is
    afterwards editable by dragging, per OP-13. `Editor.key` keeps this in the pure controller.
  - **Extending** — clicking a *dangling* end continues that path, from either end (appending or
    prepending, with the same straight-on coalescing at both). Starting a separate path welded there
    instead left a phantom corner, because two paths cannot coalesce a straight-on step. An end that is
    already *connected* is a terminus rather than a loose thread, so clicking that one starts a branch —
    which is the only way to make a T-junction, and the other thing a click on an endpoint can mean.
  - **A grab holds its offset.** Picking has a tolerance, so writing the cursor's position outright made
    geometry jump to it on the first move; the drag applies the offset from where the grab landed
    instead. A repeat click on the growing end is ignored for the same family of reasons — it is the
    second half of a double-click, and used to leave a hairline segment behind before the path finished.
  - **The closing edge is previewed as it will be.** Closing *moves* a corner — binding its own
    coordinate to the start's is what makes the closing leg axis-aligned — so hovering the start shows
    the leg into that corner *already aligned* plus the closing leg, rather than a band reaching for the
    start that promises a different shape. The drawing no longer appears to jump on close.
  - Rubber-band preview; Esc / double-click / click-start to finish.
- **Slice 2 — thick paths** (`Tools.WALL`, `Document.buildThickPath`): carrier + thickness +
  justification → **one** `Region` node (offset faces, `intersectLL` mitre corners, end caps), retained
  as a `ThickPath` and displayed as a single `AREA` element. Nothing is regenerated; the wall corners
  are the same draggable ortho vertices, so walls edit like paths. See *A wall is an output feature*
  (OP-21) for the model and why the earlier face-bundle version was replaced.
- **Slice 3 — interval features** (`Tools.OPENING`, `Document.addInterval`): click a wall to place a
  door/window; position (distance-from-leg-start), width, sill and head are editable parameters.
  Position is anchored at the start edge; width extends the end. The footprint stays **whole** — the
  plan gap (broken faces + jamb reveal lines) is drawn from the footprint and the intervals at render
  time, because in plan an opening does not interrupt the material.
- **Endpoint connections**: an **open** path's end vertices (`OrthoCornerHandle.isEndpoint`)
  take part in the weld/attach magnet — drag an end onto a point to **weld** it, or onto a
  line/circle to **attach** it. Attaching to a line binds exactly one coordinate: **the one the line
  determines**. A line crossing every horizontal fixes x once y is known, so x is derived from the
  (still free) y, and y is the remaining DOF that slides along the line; a horizontal line is the
  mirror image.
  - Keying that on the **line's** orientation, not on the vertex's own/shared split, is what makes the
    two ends of a path attach *symmetrically*. A path's start attaches before it has any leg, so its
    own coordinate is not yet defined; deciding from the leg therefore had to pin *both* of the
    start's coordinates, which silently robbed its first leg of the perpendicular freedom that the
    same connection at the other end left intact — two legs that are symmetric to the eye behaved
    differently. The line's orientation is always defined, so one rule covers both ends.
  - **Reaching upstream.** A leg whose own coordinate is *driven* — welded to a junction that is itself
    attached to non-axis-aligned geometry — still drags: the gesture moves the free ortho coordinate
    upstream of it, solving for the value that puts the leg under the cursor (`freeInputs` finds the
    candidates, `influences` rejects the ones that cannot actually move it, `driveTo` inverts). Dragging
    such a leg slides the junction along the line it is attached to, which pushes the *other* run — the
    mirror image of what dragging that other run already did, and the reason the two felt asymmetric
    when only one of them wrote its own coordinate.
    - Not a solver: nothing is asserted or stored and the model stays a pure function of its parameters.
      It is what every handle already does — an on-curve point projects the cursor onto its curve, a
      length field inverts its own arithmetic — except that the relationship is read off the graph by
      probing rather than known in closed form. Every relationship the editor builds this way is affine,
      so one secant step is exact; a failed solve leaves the value untouched.
    - Restricted to **ortho coordinates**, so reaching upstream can reshape ortho paths but never move
      the reference line or point a junction was attached to.
    - Typing reaches exactly as far as dragging (OP-13): the same field is writable, and setting it
      drives the same upstream DOF.
  - What remains genuinely immovable is geometry, not accident: if the leg at the attached end runs
    *parallel* to the line, the bound coordinate is the one shared with the neighbour, so the
    neighbour lands on the line too and the leg has no perpendicular freedom — an axis-aligned leg
    starting on a parallel line has to be collinear with it.
- **Next (ortho editing):** a shared snap resolver for point placement (see the tool roadmap — today a path
  click uses the raw cursor, so a wall cannot be started exactly on an existing corner), and the
  robustness gaps: closing moves the last vertex without previewing the closing edge, and there is no
  insert-vertex-on-leg / delete-vertex yet. Two are closed: a step continuing along the previous leg's
  axis now *extends* that leg instead of creating a collinear pair with an undefined miter, and a
  fully driven vertex is no longer grabbable (dragging it was inert while it stole the grab from the
  geometry that drives it).
- **Next (architectural):** wall-to-wall junction cleanup (T/L merges — two thick paths meeting still
  overlap), footprint accessors for dimensioning and wall-to-wall snapping (OP-6), and 3D walls
  (extrude + boolean-subtract each interval, sill→head; the parameters are already carried). Note the
  ordering decision in OP-17:
  3D walls are a *later application* of the seam, not its proof of concept — the first 3D slices are
  mechanical parts, because a wall exercises only the degenerate half of the seam.
