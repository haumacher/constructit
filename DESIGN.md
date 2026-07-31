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
> **As-built note — the binding substrate has three node kinds, not two.** A `SourceNode` (free point)
> and a `ParameterNode` (named scalar) can be *bound* in place onto another node, which is how welding
> and parameter wiring remove a DOF without rewiring any consumer. Placing an ortho path under a group
> frame (OP-16) needed the same move over a **derived** value — a vertex is `pointXY(x, y)`, and a turned
> frame mixes x into y, so `world = f(frame, lx, ly)` cannot be expressed by any per-axis binding. Hence
> `IndirectNode`: a re-pointable view of another node, published where a vertex's point is consumed. It
> adds no evaluation semantics (bound → the master's value, else the target's) and keeps the rule that
> matters: *a capture binds in place; nothing that already referenced the node is touched.*

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

> **As-built note — the dirty-marking, and what it turned out to be.** For a long time this bullet was the
> one promise the implementation did not keep: the memo was *per pass*, a fresh `Evaluator` per repaint, per
> hit-test, per handle read, each recomputing the whole cone of whatever it touched. Invisible on 2D
> geometry; on a revolve it re-tessellated the mesh on every mouse move, which is what made even the *2D*
> view lag (the 2D pass evaluates a solid element too, if only to find out that it is one).
>
> **The scheme: each node remembers its last result together with the argument values it computed that
> result from, and reuses it when the arguments are the same objects (`===`).** That is all of it. There is
> no clock, no version stamp, no reverse-dependency index, and nothing that has to be maintained in step
> with anything else.
>
> - **The mark propagates by itself.** A mutation invalidates exactly the node it wrote (`Node.invalidate`
>   from the setters of `SourceNode.value` / `.boundTo`, `ParameterNode.literal` / `.boundTo`,
>   `IndirectNode.boundTo` — the complete list of mutation points). That node recomputes and hands its
>   consumers a **new value object**, whose identity misses their memo, and theirs in turn: the dirty mark
>   travels down exactly the affected cone and stops. Nothing walks the dependents, so nothing needs to know
>   who they are.
> - **Why not the two obvious alternatives.** A *global version stamp* bumped on every mutation is correct
>   and useless: it flushes the whole document on every drag frame, so the revolve recomputes although its
>   cone was never touched. *Forward invalidation* over a reverse-dependency index is the textbook answer
>   and is the one this graph must not have: **`boundTo` re-pointing changes the shape of the DAG** — a weld,
>   an attach, a capture under a group frame, a parameter wiring — so the index would have to be updated at
>   exactly the four places where getting it wrong yields *wrong geometry* rather than a slow repaint. A
>   per-node `lastMutated` with a cached transitive max is the honest third option, and it needs a document
>   clock, two more fields per node and the same pass-time traversal — more moving parts to buy nothing.
>   Keying on the inputs themselves is self-validating: the freshness test **re-reads the very edges the
>   result depends on**, including edges that have just been re-pointed.
> - **Which is why re-pointing needs no special case at all.** `SourceNode.inputs` *is* `boundTo`, so
>   welding a point changes its arguments from none to one and unwelding changes them back; an
>   `IndirectNode` captured onto a frame reads its arguments through the new master from that moment on. The
>   setters are still there, and they earn their keep for the one case where the arguments do *not* change:
>   a literal write on a source that is nobody's consumer of anything.
> - **Identity, not equality.** O(1) per input however large the value is (a revolve's mesh compares as one
>   pointer), and conservative in the safe direction: an input that recomputes to an equal-but-new value
>   costs a recompute, never a stale answer. This also means every value type must stay immutable, which
>   they are (data classes over read-only lists).
> - **Invalidity is never memoized (OP-3).** A node can be invalid for a reason its arguments do not carry —
>   the general boolean engine (OP-9) still loading in the browser is the standing case — and OP-3 promises
>   it heals the moment that changes. Retrying every invalid node on every pass is what keeps that promise.
> - **One conservative opt-out, named: a macro `InstanceNode` over a definition *source*.** The wrapper runs
>   somebody else's `compute`, and a `SourceNode`/`ParameterNode` reads its own literal and its own
>   bound-or-free state, neither of which is in the instance's arguments — so a captured default retyped in
>   the definition would leave a memo here that nothing invalidates. Such an instance therefore never
>   memoizes. It costs nothing measurable: that delegation is a pass-through, and it still hands its
>   consumers the same value object, which is what *their* memos are keyed on.
> - **The bound: one entry per node, and it lives on the node.** No table anywhere, so nothing to evict and
>   nothing to grow: the memo is a field, it is document-scoped because a node belongs to one document, it
>   dies with the node when a delete or a journal rewrite drops it, and there is no shared mutable static
>   for parallel tests or a second document to trip over.
> - **`Evaluator` stays the API.** Its per-pass map keyed by node id is unchanged (it collapses a pass's own
>   repeats, which is what makes a diamond graph a linear walk); the persistent memo sits underneath it. The
>   several hundred `Evaluator()` call sites did not move, and a pass over untouched geometry is now a
>   pointer compare per edge and no arithmetic.
>
> Measured on a four-revolve turned part (38 nodes, 1916 triangles): the evaluation half of a render pass
> went from **~2.5–3.2 ms to under 0.01 ms** (300–450× run to run), i.e. a repaint that changes nothing
> upstream does no geometric work at all. `Node.computeCount` is the instrument the tests state that in.

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

### Showcases (worked spec examples — as built)

The four persona showcases agreed at the *Session 3 kickoff* are **tests**, one class each, with the spec
narrative in the class KDoc — they are documentation that cannot rot. The rule they were accepted under is
the one they are written to: **every feature must be generic**, so a showcase may add no code of its own.
What each proves, and the one macro plus two units/validity ops they needed, are recorded here.

| Showcase | Test class | What it is the acceptance test for |
|---|---|---|
| Mechanical — parametric spur gear → solid | `GearTest` | a **sampled curve** as a first-class curve (OP-15); a structural count vs. continuous parameters; a macro (OP-6) reaching a real part |
| Architect — the multi-step chain + gable roof | `HouseChainTest` | `Geom3.extrude` being **plane-general**: a roof is a triangle on a *vertical* plane; one drag driving three solids through measurements |
| Maker — reverse-engineered spare part | `BracketTest` | every dimension a **named parameter**; face-derived datums (OP-8) surviving a re-measurement; five booleans composing (OP-22) |
| Papercraft — a net constructed *from* the model | `PapercraftNetTest` | the **downward scalar seam** (OP-4 forward / OP-9): a 2D drawing every length of which is measured off a mesh |

- **The gear** (`dsl/Shapes.kt: spurGear`) is the only new *geometry* code, and it is a macro over the
  existing algebra: standard proportions (`rb = rp·cos α`, tip `m(z/2+1)`, root `m(z/2−1.25)`), one flank
  sampled into **12 chords at fixed values of the pressure angle at the point**, the second flank its mirror,
  a tip arc, a root land, and a radial line below the base circle where a generating cutter would leave a
  trochoid. The sample count is a **constant, not a function of the module**: a count decides how many nodes
  exist, so deriving it from a value would rebuild the graph on every edit (OP-21) — the same rule as an
  array's count. It is stated to be within ~0.005 mm of the exact involute for a m2 z20 gear (a quarter of
  `TESS_TOL_MM`) and *asserted* to be, from both sides: every sample point lies on the exact involute to
  1e-9, and the chords are measured against it.
- **The roof was the real question, and the answer was "nothing".** A gable roof is a triangular region
  sketched on `planeOffset(planeYZ(), x)` and extruded along that plane's normal, i.e. horizontally. It
  worked unchanged, which is what the seam being *a separate embedding node* (OP-17) was for. The scene
  keeps it as its own solid: a horizontal prism and a vertical one have **no common axis**, so OP-22's
  boolean refuses with the reason that names Manifold — asserted, rather than worked around.
- **Two generic gaps were filled, both tiny, both recorded as generic** (`dsl/Construction.kt`):
  - `radians(x)` / `radianMeasure(a)` — the **dimension system's one missing conversion** (OP-7). `sin`,
    `cos`, `tan` and `atan2` already cross from angle to number; nothing crossed back, so any closed-form
    angular formula *mixing* the two was unstateable. The involute function `inv β = tan β − β` is the
    canonical example, and it is a units op, not a gear feature.
  - `requirePositive(value, reason)` — **a stated precondition as a node** (OP-3). Threaded through the
    chain that needs it, it makes dependent geometry disappear *with an explanation* and heal, instead of
    coming out folded through itself. The gear states its own domain with it in **two** places: a tooth wider
    at its foot than half the pitch (reachable only at exotic pressure angles, never by a standard gear), and
    a **bore that leaves no material** between itself and the root circle. The second was found by a review
    probe after the fact and is the more instructive: the region *type* cannot catch a bore just **outside**
    the root circle, because that shape removes less area than the boundary encloses while detaching every
    tooth — only the construction that made it knows. See the correction under OP-14's *Deliberately not done
    here*, which also moved the degeneracy check into `region(...)` itself.
- **Gaps found and left open, deliberately:** the `DrawTarget`/SVG seam has **no line-style/dash**
  attribute, so the papercraft net distinguishes fold lines from cut lines by colour (dashes are a change to
  the whole seam, not to a showcase); the tool surface offers a solid's **extent** but not a bounding-box
  **bound**, and no way to *name* a plane, which is why the roof is DSL-built (the missing piece is
  datum-plane UI, not geometry); and there is still no mesh export, so "print it" ends at the mesh.

## Canvas / editor architecture (implemented)

Elastic layering — everything except pixel-drawing and native events is pure Kotlin
(`commonMain`, portable to any target); only the last two layers are platform-specific/thin:

```
Platform shell (thin)  — jsMain: DOM toolbar/tree, native event plumbing, repaint, WebGL calls
DrawTarget (interface) — screen-space draw ops; impls: SvgDrawTarget (tests), BrowserCanvas (jsMain)
InteractionController  — Editor (2D) / Viewport3 (3D): tool + gesture state; abstract pointer events
Camera / Camera3       — world<->screen (2D pan/zoom about cursor) / orbit camera + matrices (3D)
SceneRenderer          — world->screen projection, arc tessellation, line/ray clipping, grid
Scene3 + Painter3      — solids/grid as a value; painter's-algorithm projector onto DrawTarget
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

### The 3D viewport (as built)

OP-12's "Canvas2D for 2D → WebGL for 3D" made concrete. The rule that keeps this honest: **there is
exactly one projection pipeline**, and it lives in `commonMain`.

```
Camera3    — orbit camera: target, distance, yaw, pitch (+ fovY); near/far follow the distance
Mat4       — column-major 4x4; perspective + lookAt; toFloatArray() feeds uniformMatrix4fv untransposed
Scene3     — a value: every visible SOLID element's mesh + a stable colour, plus grid and axes as lines
Painter3   — painter's-algorithm projector onto DrawTarget: back-face cull, depth-sort, shade, polygon
Viewport3  — the pure gesture controller (Editor's pointer API, one dimension up)
WebGlRenderer3 (jsMain) — one program: position+normal+colour, uniform MVP, headlight; ~200 lines
```

- **The camera is four numbers, not a matrix.** An orbit camera is the same discipline the model
  follows (OP-5): every gesture writes one of `target/distance/yaw/pitch`, so a view is reproducible,
  a drag is assertable (`Viewport3Test`), and no accumulated transform can drift. Pitch is clamped
  short of straight down, where the up vector would collapse.
- **The painter's projector exists to make the projection testable.** It renders a `Scene3` through the
  ordinary `DrawTarget` seam using the *same* `Camera3` matrices the GPU is handed, so an SVG golden of
  a 3D scene (`Painter3Test`, `scene3-box-and-turned-part.svg`) is evidence about what the browser
  draws. It is deliberately not the fast path: **no depth buffer** — triangles and grid segments are
  sorted back-to-front by centroid depth, which is exact for a convex solid and can mis-order
  interleaving triangles of a concave one. (This is also why the grid is emitted one cell at a time: a
  full-length line has one depth for its whole length and would paint over a part standing on it.) The
  GPU path has a real depth test, so the artifact is confined to goldens.
- **One new `DrawTarget` primitive: `polygon`.** A shaded triangle is an *area*, so it cannot be
  expressed with the stroke primitives 2D drawing needed. Both back ends implement it.
- **Flat shading by duplicated vertices** in the WebGL path: each triangle carries its own face normal.
  No derivative extension, no vertex-normal averaging that would round off a machined edge — a solid's
  facets are what it *is* (OP-9: the mesh is the sink, shown as it will be printed). Both renderers use
  the same shading law (headlight diffuse + a 0.35 ambient floor).
- **Feature edges — a contour is topology, not lighting** (GitHub issue #3). One headlight gives two
  coplanar faces the same normal and therefore the same colour, so the floor of a 5 mm pocket shaded
  *exactly* like the surface it was cut into and the pocket's contour disappeared from every view that
  showed its floor. No lighting model repairs that — the two faces really do face the same way — because
  the missing information is topological. So each solid's mesh is walked for **creases**: undirected edges
  whose two triangles' normals diverge by more than `Scene3.CREASE_ANGLE_RAD`, drawn as lines in a darker
  shade of the solid's own colour by *both* back ends out of the one extraction in `Scene3`. Read off the
  mesh per repaint like the rest of the scene, so there is no cache that can disagree with the model.
  - **The threshold is set by what must *not* be drawn.** A tessellated cylinder, bore or fillet is a fan
    of facets whose neighbours differ by the chord step `2·acos(1 − tol/r)` at `TESS_TOL_MM` = 0.02 mm, so
    a threshold under that step draws the tessellation and turns every curved wall into a barrel of lines.
    Inverted, a threshold `t` is quiet for every radius above `tol / (1 − cos(t/2))`: **30°** clears
    everything above ≈0.59 mm (a 1 mm fillet steps 22.5°, a 5 mm one 10.0°, a 10 mm bore 7.2°), where 20°
    would already speckle a 1 mm fillet — an everyday feature size. What 30° gives up is a crease shallower
    than a 150° dihedral, and *there the two faces already shade differently*, which is the case shading
    handles by itself. Threshold and shading between them cover exactly the range each is good at, and
    `CreaseEdgeTest` pins both directions with the margin stated numerically.
  - **A tangency seam is not a crease.** Where a fillet runs into the flat it is tangent to, the normals
    differ by half a tessellation step — below any usable threshold — so nothing is drawn there. That is not
    a lucky consequence but the same rule as the barrel, and it is what makes a filleted part read as round
    instead of as a stack of strips.
  - **Depth bias, once per back end, stated.** An edge lies exactly *on* the two faces that make it. The
    painter's projector has no depth buffer, so an edge is sorted at the nearest of its own midpoint and its
    two faces' centroids (plus a 0.1 % nudge toward the eye) — near enough to beat the face it lies on,
    local enough that it cannot jump in front of an unrelated one. The GPU has a real depth test, and GL ES
    2.0 offers `POLYGON_OFFSET_FILL` but no `POLYGON_OFFSET_LINE`, so the *faces* are offset one depth-slope
    unit away from the eye while the lines are drawn plain. Biasing in the shader instead would have been a
    second projection pipeline, which is the one thing this view does not have (OP-12). One consequence
    visible in the golden and *only* there: a crease can be nibbled into dashes where an unrelated large facet
    (a triangulation fan across a cap) has a nearer centroid than the edge — the same centroid-sort
    imprecision this projector already documents for concave solids, not a property of the edges. The GPU
    draws them solid, which is what a depth buffer is for.
  - **Cut, deliberately: the silhouette.** Where a *curved* surface turns away from the eye there is no
    crease to find — a cylinder's outline against the background is still only a shading gradient, and a
    sphere would have no feature edges at all. A silhouette edge is view-dependent (recomputed per orbit,
    not per document change), which is a different kind of thing from a crease and needs its own place in
    the pipeline. The reported defect is about faces that shade identically and creases answer it; the
    silhouette is recorded here as the next honest increment.
- **The repaint "version counter" is `Editor.onChange`.** GPU buffers are rebuilt exactly when the
  editor reports a document change; an orbit never goes through the editor, so it only re-issues the
  draw call with a new matrix. No dirty flag on the document was needed.
- **Cut, deliberately: no picking in the 3D view.** A click there selects nothing, and the status line
  says the drawing tools apply to the 2D view. Picking in 3D needs a ray/mesh intersection *and* an
  answer to "what does selecting a face mean for a construction" — that answer is the sketch-on-face
  task, and guessing it now would put a second, weaker selection model beside the 2D one. Sketch-on-face
  has since shipped and answered it **without** 3D picking: a face is named by a *provenance choice*
  (`facePlane(solid, TOP)`) reached by picking the solid in plan, not by clicking a facet — so the cut
  stands, and what remains for 3D picking is convenience rather than expressiveness.
- Consequence handled rather than ignored: a solid's 2D **footprint hint** sits exactly on the area it
  was extruded from, so a canvas click can only reach the topmost of the two. The **element tree**
  therefore selects by name (`Editor.selectElement`). Biasing the pick would merely make the other one
  unreachable.

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
- **Typing reaches a tool's *inputs* too, not only an existing element's fields.** Digits typed while a
  tool is armed become the scalar it wants, as an ordinary parameter — one mechanism for every scalar slot,
  and the same principle one step earlier in time (see *Usability — click budgets*).
- Deliberately *not* called a constraint. A handle only writes free source nodes; what stays
  invariant (a leg's axis, a point's curve) is invariant because of the nodes it does *not* write,
  which are shared with the geometry that must follow.

### Editor tool roadmap

**Implemented** (data-driven `ToolDef` registry, categorized palette; a tool declares an ordered list of
dimensioned scalar inputs, which are **typed into the flow or picked in the panel**; a handful of tools
declare a single-key shortcut; existing-only slots never create stray points; scalar names are
auto-uniquified so wiring is unambiguous — see *Usability — click budgets*):
- Points: Point, Midpoint, Intersect, Project-to-line, Point-on-circle & Point-on-line
  (1-DOF draggable), Point-at-distance (0-DOF, side by click), Point (x, y) (no clicks — two scalar
  inputs), Key points (sub-entity extract), Join points (weld two points into one — see *Welding* below)
- Curves: Line, Segment, Ray, Circle (c,pt), Circle (c,r), Circle (3pt), Arc (3pt),
  Arc (centre,ends), Concentric circle, Rectangle, Rounded rectangle (the `roundedRect` macro as a
  tool), Regular polygon (with an optional corner radius, which builds OP-23's pattern composition),
  Break curve (one tool over an ortho leg, a segment, an arc and a Bézier —
  see *Break and join legs*, OP-19)
- Construct: Perp/Parallel-through, Perp-bisector, Angle-bisector, Parallel-at-distance,
  Tangent-from-point, Tangent-at-point (1 click), Fillet, Chamfer, Outer/Inner common tangents
- Transform: Mirror, Rotate, Scale, Translate-by-vector, Linear array, Circular array, and **Circular /
  Linear pattern** — a *rule* later gestures ride rather than a copy of geometry (OP-23)
- Measure: Distance, Angle (3pt), Angle (2 lines), Length, Radius, X/Y coordinate, and of a solid
  (OP-4 forward): Volume, Extent X/Y/Z (three tools, because the axis is a stored discrete choice and a
  tool id is where a `tool` step stores one)
- Solids (the seam, OP-17): Extrude (an area + a depth parameter), Revolve (an area + an in-plane axis
  line + an angle parameter) — see *Implementation status (as built — the seam's tools and the viewport)*
- Solids, downward (OP-17): Extrude on face (a solid + an area + a depth — the sketch→feature→sketch loop
  as one gesture, through `facePlane`), Section (a solid + a height → an ordinary 2D area, exact for
  prisms) — see *Implementation status (as built — the seam downward)*
- Parameter **wiring** (reduce DOF; equality by shared reference), measurement-as-scalar-input.
- Any `LINE` slot also accepts a segment/ray (carrier line), and any `CIRCLE` slot an **arc** (carrier
  circle) — see *Arcs are circle operands* below. `Fillet` takes either carrier in both slots.
- An `AREA` slot accepts either result-layer element that bounds an area: a traced `Outline` (one loop,
  coerced with `region(...)`) or a thick path's footprint (already a region) — or a **section** of a solid,
  which is why the downward seam needed no new slot kind.

#### Arcs are circle operands, and a fillet's legs may be round (as built)

Reported as *"intersect between arc and circle not working"*. It was **one filter**: every `CIRCLE` slot
demanded `ElementKind.CIRCLE`, so an arc was refused by Intersect, Concentric, Tangent-from-point, the common
tangents, Point-on-circle and Radius — although every op behind them is about the *carrier* circle, which an
arc has. The fix is the coercion that already existed one kind up: `Document.carrierCircle` is the exact twin
of `carrierLine` (`circleOfArc` for an arc, the ref itself for a circle), and the slot filter became
`Element.isCentric`, the twin of `isLinear`. One pattern, two kinds, no per-tool case.

**The honest consequence is stated where it is picked**: a point derived through a carrier may land **off the
drawn piece** — an arc–circle intersection can fall outside the arc's sweep, exactly as a segment's carrier
line meets things beyond its ends. That is what a carrier *is*, so the tool help says it and a test asserts
it rather than papering over it (`ArcCarrierTest`). The one route deliberately **not** generalized is
*drag-to-attach* onto an arc: that is a gesture whose magnet promises the point lands where the halo is, and
riding a carrier off the visible arc would break that promise. It stays on the roadmap as before.

**Fillet, generalized to any carrier leg.** Same idea, one level up: a fillet is the circle of radius *r*
tangent to both legs, and only *where its centre is* differs per leg kind —

| legs | centre |
|---|---|
| line–line | the corner-side bisector construction (`filletBetweenLines`, unchanged) |
| line–circle | `intersect(parallelAtDistance(line, r, side), concentric(circle, r, ±))` |
| circle–circle | `intersect(concentric(c₁, r, ±), concentric(c₂, r, ±))` |

So the generalization adds **no geometry**: the centre is composed of ops that already existed, each tangency
is a projection (straight leg) or the one new accessor `radialPoint` (round leg — a scaled radial, the circle
analogue of `projectToLine`), and `filletArc` closes it by taking the minor arc between them. Which variant a
fillet *is* — side of the line, R+r or R−r, which of the two intersections — is a **stored discrete choice**
(OP-1), decided once from the two clicks by scoring every variant's tangencies against where the legs were
clicked, and never re-derived. The clicks say where the rounding should touch; that is all the information in
them, and it is exactly enough. Unsolvable radii are ordinary invalidity with a reason that names the missing
tangency, and heal (OP-3) — the *closest-to-solvable* variant is the one stored, so a fillet heals into the
one the user was reaching for rather than into a sibling.

Two deliberate limits. The arc is emitted **quietly** (one element, no visible tangent points), matching the
line–line fillet — its tangencies are registered as *joints* instead (below), which is what the boundary
tracer needs and the drawing does not. And **chamfer stays line-only**: a bevel across a round leg has two
honest readings, a chord and an arc of the same length, and until that convention is stated a tool that
silently picked one would be guessing. Recorded here rather than half-built.

#### Circle from three tangents — and the Apollonius family (as built)

*Circle (3 tangents)* is the **LLL** case of Apollonius' problem: three line picks (the ordinary carrier
coercion — a line, a segment, a ray or a wall leg), then a click near the circle wanted. It is the first
member of that family to get a tool, and it was chosen first because it needs **no new geometry at all**:

```
bisector(l1, l2, s) = angleBisector( pointAlongLine(l1, corner, 1, +1), corner, pointAlongLine(l2, corner, 1, s) )
                      where corner = Select(intersectLL(l1, l2), +1)
centre              = Select( intersectLL( bisector(l1, l2, s12), bisector(l1, l3, s13) ), +1 )
circle              = circleCP( centre, projectToLine(centre, l1) )
```

**Tangency is by construction, and that is the whole argument for building it this way.** A point on a
bisector of two lines is equidistant from both, so a centre on one bisector per pair is equidistant from all
three; and the radius is not a computed number but *the distance to `l1`*, because the circle is built through
the foot of the perpendicular there. Nothing is asserted, nothing is solved, and dragging any of the three
lines keeps all three tangencies exactly — asserted as `|dist(centre, lᵢ) − r| < 1e-9` on all three, before
and after a drag, with `nodesCreated` flat across it (recompute, not regenerate).

**Four solutions, one stored choice** (OP-1). Three lines that make a triangle admit four tangent circles —
the incircle and the three excircles — and they are exactly the four combinations of the two bisector
branches. So the discrete freedom is `signs=s12;s13`, two integers, scored **once** from the final click (the
candidate whose *circumference* is nearest it, since that is what the user is pointing at) and then restated
by the step on every save. Replay takes them verbatim. This is the fillet's lesson applied before it could
become the fillet's bug: the regression test moves a leg until re-scoring the stored click would prefer a
*different* candidate, reloads, and requires the chosen circle to come back. Two parallel legs (or three
concurrent lines) admit nothing: the tool refuses out loud and records no step, and every `Select` in the
chain carries its own reason, so a circle that becomes impossible mid-edit hides and heals (OP-3).

No joints are registered for the three touch points, deliberately — unlike a fillet's tangencies. A fillet
*replaces* a corner, so the boundary tracer has to know where it hands over; an inscribed circle replaces
nothing, and touching a line is not a handover between two pieces of one boundary.

**The rest of the family, and what each one still needs.** The pattern generalizes, and the honest note is
that the *bookkeeping* is what differs, not the difficulty:

- **LLC** (two lines, a circle) — the centre is on a bisector of the two lines and on a parabola-like locus
  from the circle; constructible as `intersect(bisector, concentric(C, ±r))` only once *r* is known, so the
  composition needs one more step (the tangent-length relation) and has up to **four** solutions with an
  inside/outside sign per circle leg.
- **LCC / CCC** — the classical constructions go through an inversion or a radical axis; both are expressible
  with the ops that exist plus one, and CCC has up to **eight** solutions, i.e. three sign bits.

Each therefore wants its own `signs=` layout and its own scoring click, which is precisely why they are
separate tools rather than one "tangent circle" tool with a guessing pick: the number of discrete choices is
part of the *shape* of the construction, and OP-1 requires each of them to be stored, not tracked.

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

**Several scalar inputs.** `ToolDef.scalars` is an ordered list of **named** inputs (`listOf(len("x"), len("y"))`)
where there used to be a single `scalar = true` flag consuming the active parameter. (Each entry later gained
its **dimension** as well — see *Usability — click budgets* below: that is what lets a typed number become a
parameter for any slot.) Collecting them
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

**A scalar slot may carry a DEFAULT (as built).** `ScalarSlot(name, dim, default)`: a tool whose scalar slots
*all* have defaults (`ToolDef.scalarsOptional`) never **waits** for one — it completes on its last click and
`build` receives no scalar ref at all unless the user typed or picked one. That is what lets a tool gain an
optional input without gaining a gesture, a step or a node: *Midpoint* is still two clicks, its `tool` step is
still `tool midpoint pts=e1,e2 clicks=…` with no `scalar=` in it, and its result is still a plain derived point
(asserted byte-for-byte). Three decisions inside that:

- **The default is what `build` does with nothing**, and the declaration states it so the status line can name
  it ("Using factor = 0.5 (default) — type a number for another"). It is deliberately *not* materialized as a
  node when unused: a hidden constant per unused slot would change the graph, the file and the undo history of
  every tool that grew a default.
- **A defaulted slot does not adopt a stray pick.** The panel memory is consumed by *dimension* here: a length
  picked for the previous tool is not silently read as a dimensionless ratio (which would only make the new
  node invalid, OP-7). A required slot keeps taking the last pick as before — that is what the user armed the
  tool for; a defaulted one is not waiting for anything and must not hijack.
- **Typing still wins over everything**, through the existing typed-scalar flow: the digits become an ordinary
  parameter (panel row, `param` step, wireable), and the tool's own checkpoint seals it — so "type `.3`, click,
  click" is one undo step exactly as "type 7, click" already was.

**Ratio points — a midpoint with its 0.5 relaxed (as built).** The demand was "the point a third of the way
along", and it is the first user of the mechanism above. One new op, `pointAtRatio(A, B, t)` with `t`
**dimensionless**; *Midpoint* and *Perp. bisector* each gained one defaulted `factor` slot (0.5).

- With no factor both tools are exactly what they were (`cx.midpoint`, `cx.perpBisector`). With one, the point
  is `pointAtRatio` and the bisector becomes the perpendicular *through that point* — composed from
  `lineThrough` + `perpendicularThrough`, no new op for the second tool at all.
- **`t` is dimensionless on purpose**: a share of a span, not a length. So one `t` node feeding several pairs is
  **equal proportions by construction** (OP-5 — sharing a node *is* equality): dragging one ratio point moves
  every point that shares the factor, and stretching one span leaves the others' proportions alone. A length
  could not express that, and a constraint would have been the alternative.
- **1 DOF, reachable both ways** (OP-13): `RatioPointHandle` projects the cursor onto the span and writes `t`,
  and the same node is a `factor` field in the inspector and a row in the panel. `t` outside `[0, 1]` is
  allowed and **said** ("factor 1.5 is outside 0…1, so the point sits beyond the second point") rather than
  clamped — extrapolating along a span is a construction, not an error.
- A ratio point is **rigid under a group's frame with nothing to capture** (OP-16), and unlike a polar bearing
  it is rigid under *rotation* too, precisely because the factor says nothing about the world's axes.

**A structural count.** A polygon's side count and an array's instance count decide **how many nodes
exist**, exactly as an ortho path's vertex count does. It is therefore a property of the *gesture*, not a
parameter: `Editor.count` is a tool option like the wall justification (there is no slot to click it
into), it is recorded in the tool step as `count=n`, and replay re-runs the tool with it verbatim — the
loader's element-count check then vouches for it. Changing a count later means using the tool again.

> **Superseded for one construction (OP-23).** A **pattern** stores the rule of every gesture that rides it,
> which makes "changing a count later" a journal rewrite (re-stamp) rather than a re-draw — without making the
> count live and without a compound value. The paragraph below still holds verbatim for an array's and a
> polygon's count, which store no such rule. See *Patterns as orbits*.

That is the honest answer here, and the alternative is worth naming: a **live** count — a parameter that
adds and removes copies as you edit it — cannot be one node per copy, because the number of nodes would
depend on a value. It needs a *compound* value (one node yielding a list of geometry) plus accessors into
it, i.e. a new value kind and an addressing scheme for "the k-th instance" — OP-8's territory, and a
possible OP of its own later. It is deliberately not smuggled in here: a half-live count would make the
document stop being a pure function of its parameters at exactly one point, which is the property
recompute, undo and reload all rest on.

**Shapes by construction.** The new tools add no ops and no geometry code — they compose what was there,
which is the test of whether the algebra is closed:

- *Rectangle* clicked two diagonal corners and derived the other two as `pointXY(x(a), y(c))` and
  `pointXY(x(c), y(a))` — rectangular **by construction**, since the shear was not expressible. **Superseded
  (GitHub #4):** the same two clicks now draw a **closed ortho path**, whose legs are axis-aligned by the same
  trick one dimension down and which brings every path affordance with it (leg drags, typed side lengths,
  break/join, walls). The old build survives for replay only — see the ortho path's slice-1 note. `Tools.legacy`.
- *Rounded rectangle* is the existing `roundedRect` macro (OP-6) with its centre = the clicks' midpoint
  and width/height = their coordinate spans, so the two clicks keep driving it; the radius is an ordinary
  parameter, so editing it re-rounds live with no node replaced.
- *Regular polygon* is the general `rotate` applied *count-1* times to the clicked vertex.
- *Chamfer* shares the fillet's clicked-quadrant sign resolution (a stored discrete choice, OP-1) and is
  otherwise `intersectLL` + `Select` for the corner and `pointAlongLine` for each bevel end.
- *Arrays* are a **fan, not a chain**: copy *k* is `k·v` (or `k·360°/n`) from the original, so no copy
  depends on a sibling, every copy recomputes straight from the original, and the copies are the
  original's dependents — deleting it takes them (OP-18).

#### Live tool previews — one declaration, no controller cases (as built)

Until now exactly one gesture said what it was about to build: the ortho path's rubber band. Every other tool
was a sequence of clicks whose result appeared only after the last one — and for the tools that resolve a
**discrete choice** from where you clicked (a fillet's variant, a chamfer's quadrant, a dimension's sector)
that meant the choice was invisible until it had already been stored. So the band was generalized rather than
copied: `ToolDef` gained one optional member.

```kotlin
val preview: ((PreviewContext) -> List<PreviewShape>)? = null
```

`PreviewContext` carries the picks so far, the **values in effect** (picked, typed, or the slot's declared
default), the structural count, the cursor, the document and the pick tolerance; `PreviewShape` is a small
sealed set of plain geometry — dot, segment, line, ray, circle, arc, Bézier, polyline, and a dimension's
`DimensionGraphic`. `SceneRenderer` draws them in the band's own style, through the same clipping,
tessellation and dimension-skeleton code the real values go through. The editor's whole share is one call in
`pointerMove`; there is no case per tool anywhere.

**The rule, restated as a property rather than a discipline: a preview never touches the graph.** The snap
resolver already worked this way ("hover-time intersections are computed with `GeomMath`, so previewing never
touches the graph"), and here it is made structural — `PreviewContext` holds no `Construction`, and
`PreviewShape` holds no `Node`, `Ref` or `Element`, so a preview function *cannot* record anything even by
mistake. What can be asserted generically then is: hover a grid of positions with every previewing tool
armed, and `nodesCreated`, the element list, the journal and the saved script are all unchanged
(`PreviewTest.noHoverTouchesTheGraph` — one test, and the next preview written is covered by it without it
being touched).

**Honesty is the other rule, and it is what decides the edge cases.** A preview draws what the click will
build, not an approximation of it:

- a *line* previews as an infinite line and a *ray* as a half line, because that is the element the click
  makes — a band between two points would promise a segment;
- the values in effect are **in the picture**: a rounded rectangle previews with the radius that is in effect,
  a polygon with the current count *and* its corner arcs, an array with all its copies. Typing a value or
  changing the count redraws it where the cursor last was;
- where the tool would build nothing, nothing is drawn — a missing required scalar (Rotate with no angle), a
  degenerate pick (a rectangle whose corners share a coordinate), a cursor over nothing (Mirror needs an axis
  under it);
- the cursor a preview is given is the **snapped** one wherever a snap is in effect, since that is where the
  click will land.

**Previews run from the first pick onward.** That is a deliberate choice with a cost, and the cost is named:
*Circle (centre, radius)* completes on its first click and so is never previewed, and the rectangle's first
corner is unpreviewed too. The alternative — previewing with nothing picked — puts geometry under the cursor
the moment a tool is armed, which reads as a click already made; and a tool armed by accident would then paint
over the drawing. From the first pick the user has committed to something, and the preview is about the *next*
click rather than about the tool's existence.

**The scored choices are the point of the feature.** For the fillet and the chamfer the preview runs the *same*
scoring the build runs — `FilletMath.variantFor` / `legSigns`, extracted out of `Document` into `geom` for
exactly this reason, so there is one implementation and not a second formula that could drift. Moving the
cursor from one side of the second leg to the other flips the previewed arc, and the click then builds
precisely that arc (asserted by comparing the previewed arc with the built one). Note *where* that preview
runs: after the **first** leg, with the second one resolved from what the cursor is over — because the second
click both names the leg and scores the side, so there is no hover moment with two legs already in (the tool
completes on that click, unless it is still waiting for its radius, and then there is no arc to draw either).
The same holds for the three-tangent circle's four candidates and for a dimension's sector: `LinearDimension.graphicOf` and its two
siblings are the annotation's own graphic on values, so a previewed dimension and a placed one are drawn by
one piece of code.

**The first wave**, twenty-four tools: Segment, Line, Ray (the band in each one's own form); Circle
(centre, point); Circle (3 points) and Arc (3 points) — the live circumcircle/arc through the picks and the
cursor; Arc (centre, ends); Rectangle and Rounded rectangle; Regular polygon; Mirror, Rotate, Scale,
Translate and both arrays — **ghost copies** of the picked geometry under the transform the picks imply;
Circular and Linear pattern — the **ring** (or row) of members under the cursor; Fillet and Chamfer; the three
dimensions, riding the cursor for their placement click; and Circle (3 tangents). Nothing was cut. The tools
without one are the ones that would have nothing to say: a pick that creates a point where you click, a
boolean between two solids, a measurement, and the path tools, which have had their own band from the start.

#### The selection says what it is built from (as built, on a user report)

*"Which point is this circle's midpoint?"* — asked of a drawing with forty elements in it, and there was no
way to answer it. The construction knows, exactly and cheaply (it is a DAG, OP-5), but nothing published the
answer: the inspector showed the selection's *values* and the canvas showed the selection alone. Reading the
journal is not an answer either, because the journal says what was *done* and the question is about what a
piece *is*.

So a selection now publishes two more relations. On canvas its **inputs** are restated in one colour and its
**dependents** in another (the same emphasis vocabulary as a selection and a tool's picks — the piece drawn
again on top of itself), and the inspector gains a **built from** row and a **used by** row listing them by
name, each name a chip that highlights its element while hovered and selects it when clicked.

**The depth rule, stated honestly, because neither obvious answer works.** *Direct inputs* is unusable: most
element nodes consume op nodes nothing displays — a fillet's arc is built over derived tangency and centre
nodes, a wall's region over a whole offset network — so a literally-direct rule reports **nothing** for
precisely the elements a user asks about. *The whole cone* is unusable in the other direction: it is the
entire drawing upstream, and highlighting everything says nothing. The rule is therefore

> walk up from the selection's node and **stop at the first node an element displays**.

A node nothing displays is *transparent* and the walk carries on through it; a node some element displays is a
**barrier**, and that element is an input. So a circle reports its centre and its radius point, a fillet its
two legs *and not the legs' own points* (those are one barrier further up, reachable by selecting a leg and
asking again), an extrusion of a rectangle reports all four legs — which is the honest answer, since the area
is coerced from them (`Document.regionOf`) and the solid genuinely consumes every one.

Two consequences fall out of the rule rather than being arranged. **The two arrows agree**: `dependentsOf` is
the *exact inverse* of the ancestor relation, not a second walk with a rule of its own, so following "used by"
and then "built from" always comes back. And **welding needs no special case**: binding is an in-place
re-point (OP-5), so a welded alias' node simply *has* an input, and the walk finds its master through the same
line of code as everything else.

**The words come from the step, the membership from the graph.** A `tool` step records its picks, and a
`ToolDef` now declares what its slots *mean* (`slotNames`), so the row reads `centre e4, radius point e5`
rather than listing two anonymous points. The two sources are kept strictly apart: the graph decides *who* is
an input (the step cannot know — a rectangle's extrusion is built from four legs and only one was clicked),
and the step supplies the *word* for the picks it does name. An input the step has no word for is listed
without one, which is the common case for derived geometry and reads perfectly well.

Scalars are deliberately absent from both rows: a parameter is not an element, it has its own panel row, and
the wiring dropdown there already says what it drives.

#### An icon palette, and what a glyph is for (as built)

The palette was sixty full-width text rows, which is a list to *read* rather than a board to *aim at*.
`ToolDef` gained one more optional member — `icon`, inline SVG markup for a 24 × 24 box — and the palette
renders a wrapping grid of 34 px buttons per category, with the words moved into the tooltip (label, help,
shortcut). Every button keeps `id="tool-<id>"` and `data-tool`, so no flow, no keyboard route and no E2E
selector changed.

Three properties, each structural rather than stylistic. The markup is **part of the build** (`Icons`), so
there is no icon font, no sprite sheet and nothing to fetch — the shell still works from a `file:` URL. Every
glyph strokes `currentColor` at a width the wrapping `<svg>` sets, so an active button's white-on-blue state
costs the icon nothing. And a glyph draws **the operation, not the result**: at 24 pixels a fillet is not an
arc, it is *two legs and the corner the arc replaced*, so the fillet and chamfer glyphs keep that corner as a
ghost — the same reason *Mirror* has a dashed axis with a shape either side and *Translate* a ghost copy.

**Coverage is partial on purpose, and here is the line.** **60 of the 77** palette tools have a glyph, plus
*Select*, which is not a `ToolDef` and so carries its own; the other **17** keep their text rows in the same
category, below the grid. A tool gets one when its operation has a picture — every curve, every construction,
every transform, the result layer, the solids and booleans, and the three dimensions. It does not when a
picture would be a guess the user has to decode: *Point at distance*, *Make relative* / *Make absolute*,
*Angle bisector*, the ten scalar **measurements** (Angle, Angle 2 lines, Length, Radius, X, Y, Volume, Extent
X/Y/Z — a number has no shape, and drawing the thing being measured would repeat the dimension tools'
glyphs), *Sketch on face*, *Sketch plane* and *Cut openings*, and every user-defined macro (OP-6), whose name
is the user's and whose picture there is no way to know. **A glyph nobody can read is worse than the label it
replaced**, which is the whole reason the field is nullable and the palette renders both kinds.

One defect this uncovered, recorded because it was invisible in every unit test and killed the whole palette
in the browser: the click delegation cast its target to `HTMLElement`, and an **`SVGElement` is not an
`HTMLElement`** — so every click that landed on a glyph was dropped. Fixed in both directions (the cast is to
`Element`, and the glyph is `pointer-events: none`), and it is the reason the browser E2E is not optional for
a change like this one.

#### The inspector is a region of its own (as built)

What the inspector shows depends entirely on what is selected: nothing (one line of hint), a jamb (four
parameter rows), a placed group's frame (three), a corner (two), a dimension (two plus its measured value) —
and now a name row and two dependency rows on top. Every one of those is a different **height**, and the
panel is one scrolling column, so every click moved the parameter list, the measurements and the element tree
up or down under the cursor. Selecting something and then wanting to click the element below it in the tree
meant re-finding the tree first.

The inspector is therefore a fixed-height box that scrolls internally. Nothing below it can move, whatever is
selected, and the assertion is exactly that: the browser E2E reads `#tree`'s viewport position with nothing
selected, with an element selected, and after deselecting, and requires all three to be the *same number*.

#### A corner scale bar (as built)

The 2D view had no statement of its own scale. The grid implies one, but reading it means counting cells and
knowing what a cell is worth; a drawing print has a scale bar for the same reason a map does.

The bar is a `SceneRenderer` overlay — bottom-left, a line with two end ticks and its length written over it —
which is what makes it appear in the SVG goldens rather than being a shell decoration. Its length is a **round
number of millimetres** spanning at most 100 px, and the rounding is not a new one: `niceLength` is the grid's
own 1/2/5 × 10^k rule, now shared by the 2D grid (40 px per cell), the 3D ground (`Scene3.gridStepFor`, sized
by the model) and the bar (100 px), so a bar and the grid it sits on can never round differently. At 2 px/mm
it says `50 mm`, at 4 px/mm `20 mm` (25 would fit; 20 is the round one), and it follows the wheel live.

It is **off by default in the `Editor` and switched on by the shell**, exactly as `showGrid` is — so a
headless render stays a render of the geometry alone and the existing goldens are untouched, while the one new
golden (`editor_scale_bar`) records the overlay itself. The label is in millimetres always: mm is the model's
base unit, and a bar that switched to metres would be the display-unit question (OP-7) answered in one corner
of one view.

#### Usability — click budgets (as built)

The session-3 charge was to *test the application and its usability, and reach the results in a reasonable
number of clicks*. So usability was made **measurable**: `ClickBudgetTest` scripts four whole workflows
through the ordinary gesture surface and **counts user actions**, asserting a ceiling per workflow. A click
is 1, a drag is 1, a keyboard entry (digits + Enter, a shortcut, Esc) is 1, a tool switch is 1, picking a
parameter row is 1 — and **creating a parameter in the panel is 3**, because it is three interactions (name
field, value field, Add). That last weight is the only judgement call in the table, and it is the honest
one: weighting it 1 would have hidden the very friction the work removed.

| workflow | before | after | what the workflow is |
|---|---|---|---|
| W1 mechanical | 43 †  | **23** | rounded-rect plate → area → extrude; bolt circle by circular array; counterbore subtracted |
| W2 architect | 27 | **18** | closed 4-corner wall ring, door + window, extrude, cut openings → storey solid |
| W3 macro | 25 | **23** | record a 5-element construction as a tool, stamp it 3 times |
| W4 drawing | 35 † | **24** | rounded-rect bracket outline, bore, brace + linear/radial/angular dimensions |
| total | 130 | **88** | |

† **W1 and W4 did not actually complete before.** Measuring them is what found two defects, both of which
the "before" numbers therefore *understate*: a rounded rectangle could not be traced at all, and a circle
could not become an area at all. Both are fixed below; the *before* column counts the actions the same
scripts spend, so the two columns are comparable.

**Four mechanisms were added, each generic, none of them shaped to a workflow.**

1. **A typed number is a scalar input — for every scalar slot** (OP-13 generalized). Direct distance entry
   already existed for a path's leg length; it now covers *any* tool's scalar. Digits typed with a tool
   armed accumulate in the same buffer, and Enter turns them into an **ordinary parameter**, named after the
   slot and uniquified exactly as a panel one is (`depth`, `depth2`). Nothing marks it as typed: it appears
   in the panel, rides the `param` step and is wireable. Its **undo unit is the operation it was typed
   for**: the parameter is sealed by the tool's own checkpoint, so "7, Enter, click" undoes as one step —
   and a typed value whose tool is cancelled is *retracted* (`Document.retractParameter`, refused if
   anything already references it), so an orphan can never leak into a later, unrelated undo step. A
   panel parameter, created deliberately, keeps a step of its own. What made this
   possible in *one* place is that `ToolDef.scalars` became a list of `ScalarSlot(name, dim)` — a bare name
   cannot be turned into a quantity without guessing, while a dimension makes the same digits mean mm for a
   depth, degrees for an angle and a bare number for a factor.
   - Two consequences worth recording. **The picks are no longer thrown away**: a tool whose slots are all
     clicked but whose scalar is missing now *waits*, and finishes when the number arrives (typed, or picked
     in the panel) — so the geometry no longer pays for a value's absence, and the two orders (value first,
     clicks first) cost the same. And typing is offered **even when the panel memory would already satisfy
     the tool**: a tool consumes the last picks in order, so without that a value could never be overridden
     once anything had been picked. The status line now also *names* the values a tool will consume
     ("Using depth = h1 — type a number for another"), because silently reusing the last-picked parameter
     was convenient and invisible, and the invisible half is what makes a feature come out the wrong size.
2. **Single-key tool shortcuts**, as a `ToolDef` field (`shortcut`) — data, like everything else about a
   tool. `S P L C R O W D E X M` (select, point, segment, circle-by-radius, rectangle, outline, wall,
   door/opening, extrude, subtract, linear dimension); the palette renders the key on each button and in its
   tooltip, and `Editor.key` routes it. Deliberately **not** one letter per tool — a full cipher is not
   discoverable — and deliberately not given to macros, whose names are the user's. This changes no *count*
   (a palette click and a keystroke are both one action) and that is stated plainly: what it removes is the
   mouse round trip to a 40-button palette, not an action.
3. **Pieces that already meet hand over there, instead of being re-intersected** (`Document.jointBetween`).
   Every side of a rounded rectangle meets its corner arc *tangentially*, and a tangent line and circle have
   no intersection to find — so the Outline tool refused to trace the commonest outline in mechanical CAD,
   and refused **silently** (no joint, no loop, nothing built, no message). The joint is now the shared
   endpoint, recognized by position within `JOIN_TOL` and constructed as an **accessor node**
   (`arcStart`/`segmentEnd`/…), so the traced boundary is still a pure function of the parameters — asserted
   by re-typing the radius and re-measuring the area. Fillets and chamfers benefit for the same reason.
4. **A curve that already bounds an area can be picked where an area is wanted** (`regionOf` /
   `boundaryPiecesOf`). Two cases, one rule: a **closed curve** is a boundary by itself (a circle — which
   before this could not become an area *at all*, so a plain cylindrical hole was unreachable through the
   tools), and a **closed chain one step built** is a boundary in the order that step created it (a
   rectangle, a rounded rectangle, a polygon). The order is the *construction's*: OP-14 rejects seed-point
   region finding because a discovered loop's identity is unstable, and here it is read off the step that
   built the pieces — so the same step always yields the same loop, and what is checked at pick time is only
   whether that chain currently closes (on values, with no throwaway node). The `roundedRect` macro now
   states its `boundary` order, which is the only reason nothing has to guess how its eight pieces join.

**Friction found and deliberately *not* removed** (measured, then judged):

- **Tracing a boundary was one click per piece** (9 of W4's 31 actions). The obvious extension — let the
  *Outline* tool take a whole closed chain from one pick — was rejected: the Outline tool's job is to say
  *which curves in which order*, and a whole-shape pick would make it impossible to start a mixed boundary
  on a shape's side (a plate with one corner cut away). The area coercion above is safe precisely because an
  `AREA` slot has no other reading of a curve pick. Where the whole shape *is* meant, the coercion already
  avoids the trace entirely.
  - *Removed later — and the rejection above is why it took the shape it did.* The **boundary-follow** (see
    *The tracer reports, and follows* under OP-14) appends only what is **not a choice**, so beginning a mixed
    boundary on a shape's side is untouched. W4's trace is 2 actions instead of 9 (31 → 24).
- **Repeat-last-tool was evaluated and not built.** It buys nothing here: a tool stays armed after it
  builds, so repeating it is already just its own clicks, and re-arming costs one keystroke since (2).
- **Regions with holes are still not traceable** (only single loops are), so a hole in a plate is a second
  solid subtracted rather than a hole in one sketch. That is a real gap, and it is the next one worth
  taking: it wants a way to say "these loops are holes of that one", i.e. a grouping gesture over outlines.
- **A shape tool does not emit a result outline**, and should not: OP-14's rule is that the output set is
  explicit, so a rectangle that might be scaffolding must not silently become a drawing.
- **Only lengths, angles and plain numbers can be typed** — no expressions, no unit suffix (`2.5cm`), no
  negative sign. The expression language is OP-7's own item; the parse point is now in one place
  (`commitTypedScalar`) for when it arrives.

Verified in real Chrome under `-De2e=1` (`BrowserE2ETest.architectFlowByKeyboardInBrowser`): W2 driven
entirely by tool keys and typed values, with the plan and the cut storey screenshotted to `build/e2e/`, and
with the one thing only a browser can answer — the panel's own inputs still receive their characters,
letters included, because the shell's keydown seam skips fields and modifiers.

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
   *Tool inputs* above). A whole **group** fills the geometry slot as one operand (OP-16), so one step arrays
   every member — from the canvas or from the groups panel. Remaining: snap modes — key points of *derived* geometry (endpoint/midpoint/
   quadrant, which need the derived point materialized, not just its coordinate) and arcs (no carrier
   circle yet); drag-to-attach onto **arcs** and onto **derived points** (the two cases the weld
   magnet doesn't yet cover — see *Welding*).
4. **Selection & grouping (OP-16).** **Multi-select, flat named groups and placed groups are done** — a
   selection set with a primary element, a marquee (panning moved to middle/Space+drag), bulk
   hide/delete, `group` steps that survive save/load and stay consistent when a member is deleted, and
   a **frame**: placing a group retrofits the free points it owns to frame-relative form, after which
   moving the group is one literal write on the frame (drag, or typed x/y/angle) and its derived
   geometry follows for free. **Group → macro promotion is done** with item 6: both are the same dialog
   over the same closure analysis, so the promotion path is "open it in the other mode". An **ortho path is
   captured whole** by a frame (the rotated project frame — walls follow it), and a group is a tool **operand**:
   with the group selected as a whole, a geometry-slot click on any member — or a click on its panel row —
   feeds the group, so an array copies every member in one step. Remaining: **relocate-origin / re-parent /
   constructed frames (mates)**, grouping the copies an array of a group makes (they land ungrouped, on
   purpose — see the as-built note), and letting Mirror/Rotate/Scale/Translate take a group the same way (their
   builds index `Picks.elements` positionally).
5. **Result layer (OP-14) — done end to end.** Trim ops, `Loop`/`Region`, areas, the *Outline* tool,
   derived scaffolding + a dim toggle, and cubic Béziers (OP-15) taking part in a boundary. Remaining
   here: **rework the wall as an output feature** (OP-21), regions with holes from traced outlines
   (only single loops are traceable so far), and parametric spline trimming.
6. **User-defined macros — done.** Record a sub-construction, tick which of its free sources are the
   inputs, get a palette tool; clicking its slots stamps an **instance** that is a path-addressed *view*
   over the definition, so editing the original updates every instance live. The dialog is shared with
   group creation (OP-16), the tool is part of the file (a `macrodef` step), and an instance is an
   ordinary `tool` step. See *Implementation status (as built — user-defined macros, the UI half)* under
   OP-6. Remaining there: **specialization** (partial application) has no UI, a definition's *structure*
   (not its values) does not propagate to existing instances, and a dimension or a placed group cannot be
   part of a definition yet — each refused with a message rather than half-supported.
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

**Two rules the format keeps, both of which a *turned* placed group put under real pressure (as built):**

- **One canonical number format, and it is never scientific.** Every coordinate and quantity goes through one
  writer (`DocumentFormat.trim`), whose basis is `Double.toString` because that is the shortest form which
  reloads to the *same double* — but for a tiny or huge magnitude it prints `-6.123233995736766E-16`, and a
  canonical serialization does not emit exponents (the SVG goldens have followed that rule from the start). A
  group turned 90° produces exactly such a coordinate, being zero up to the rounding of `sin 90°`. The exponent
  is therefore expanded by moving the decimal point **in the digits themselves** — a string transformation, so
  the reloaded value is bit-identical. Fixed-precision rounding was rejected for the opposite reason: it would
  be perfectly byte-stable *and* would silently move the drawing on every reload.
- **State is restated as a value, never as a rewritten click.** A slider's position used to be written by
  replacing its tool step's last click with the rider's current position, and that is wrong on both counts a
  click has: replay re-projects the position onto the geometry as it stands **before** the placement that turns
  the group (so a turned figure came back with its rider elsewhere, and the round trip broke), while the click
  itself encodes a *choice* — which curve, which side — that replay must repeat unchanged. The `pointoncurve`
  step has restated its own parameter since session 9; the point-on-line and point-on-circle **tools** now do
  the same through `dofs=`, so there is one rule and no special case. Old files keep loading: with no `dofs=`
  the click places the rider exactly as it always did.

```
constructit 2
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

#### Versioning & migration — a stored literal's meaning is frozen (as built, on a data-loss report)

The header is `constructit <version>`; this build writes **2** and reads **1 and 2**. A file that claims a
higher version is refused as *written by a newer version*, which is a fact the user can act on, where the old
whole-line comparison could only say "unsupported format".

**The doctrine, in one rule: a stored literal's semantics are frozen the moment a build that might have
written it could have shipped.** Changing what a stored number *means* is therefore not an edit to the reader —
it is a **version bump plus a migration**, and the migration's job is to reproduce the geometry *the old
writer meant*. Three corollaries, each of which this format had already brushed past:

- **Replay is not a free pass.** OP-20 concluded that the anchoring rework "needed **nothing** from the file
  format", and that was true *only* because at that moment a rider's position was still rebuilt from its
  recorded click. Six commits later the `pointoncurve` step began restating the rider's own parameter — and
  the same number now had two possible meanings (a distance from the carrier line's `origin`, or from the
  point of that line nearest the world origin, which differ by the anchor's own offset). The order of those
  two changes is the only reason no file was ever written under the old reading. That is luck, not design.
- **A scored choice must be persisted at creation, not re-scored on replay.** OP-1 says a branch is a stored
  discrete choice; a sign stored only in the session's `Select` nodes is stored *only until the next save*.
  A fillet's variant was scored from its two clicks — and re-scored on every load, against whatever the
  geometry had become since, so a reload could hand back a different one of the eight variants than the user
  chose. Fillet, chamfer and `intersectnear` now write their resolved signs into the step (`signs=1;-1;1`),
  and replay takes them verbatim: **scoring happens exactly once, when the user clicks.** `count=` and an arc
  break's `ccw` were already verbatim; the same test applies to anything added later — *would a later edit
  change what this argument re-derives?*
- **Where a v1 value is genuinely ambiguous, say so.** The migration prefers the reading that reproduces the
  step's **recorded position**, because a click is creation-time truth (`Document.migratedRiderDof`). When the
  position cannot arbitrate — the rider was moved since, or an edit upstream has since turned its host, so the
  position no longer lies on the carrier at all — today's reading is kept (it is what the last writer meant)
  and the load **names the element** through `Document.loadNotes`. No silent guessing: a drawing that comes
  back differently must say which element it was unsure about.

The **semantic-change catalogue** as it stands, because the next bump needs to extend it rather than rediscover
it:

| stored literal | v1 → v2 | migration |
|---|---|---|
| `pointoncurve dofs=` / `tool pointon* dofs=` along a **carrier** | the anchor a distance is measured from changed with OP-20 (line `origin` → the line's nearest-world-origin point) | arbitrated by the recorded position; kept + reported when it cannot arbitrate |
| the same, on an **axis-aligned** host or a **circle** | unchanged — a world coordinate and an angle about a centre were the *point* of OP-20 | none |
| `tool fillet` / `tool chamfer` | re-scored from `clicks=` on every load → `signs=` restated | scored once during the v1 load, against the migrated geometry, then stamped |
| `intersectnear` | re-scored from its position on every load → `signs=` restated | as above |
| `tool pointon*` **clicks** | a pre-`dofs=` writer rewrote the last click to the rider's position | none needed: with no `dofs=` the click places the rider, which is exactly what that writer meant |
| `relative dofs=` | the list is positional *per form* (distance+angle for a polar offset, one signed distance along a carrier), and which form applies is decided by the construction, not by the file | none |
| `wall` justification, `breakarc` ccw, `opening pos/sill/head`, `place at/angle`, `count=`, `sketchspace piece=` | unchanged | none (absent justification still defaults to centred) |
| `sketchspace line= / angle= / part=` — the **datum** variant (OP-17, GitHub #6) | new arguments in this build; the face variant (`el=`/`piece=`) is untouched and told apart by `line=` | none, and **no version bump**: an argument that never existed cannot have meant something else, so no stored literal changes meaning. `part=` is a choice recorded at creation (never re-derived on load); the angle is *state* and rides the `param` step of the parameter this one names |

Known residual, recorded rather than papered over: the **Outline** tracer still resolves its handovers from
that tool's own clicks on every replay (`jointBetween`), and a determined ortho meeting still picks its
circle branch by nearness. Both are re-derived from geometry the same replay rebuilds, so they are stable
under reload — but they are the same *shape* of risk, and they are the next things to move into `signs=`.

#### The name the file gives an element is the only name (as built, on a user report)

There were **two** numbering schemes for one thing. The file names elements script-locally (`e1`, `e2`, … per
the journal, from 1, gapless — the whole point being that the format does not depend on runtime id generation),
while the panel, the status line, the inspector header and every dialog showed `Element.id`, the *runtime* id
from a counter that also numbers parameters, measurements, riders' parameters, frames and local coordinates. So
the numbers drifted apart the moment a drawing had a parameter in it: a file that said `e17` had the user
looking at `e21`. Reported as a defect, and rightly — a name whose purpose is to be *said* ("what is wrong with
e17?") is worthless when the two sides of the conversation number differently.

**The script-local name is now the only user-visible name**, and the document is its one authority
(`Document.nameOf`): it derives the map from the journal exactly as the writer does, and the writer *asks it*
rather than deriving it a second time, so the two cannot drift. Everything that shows a name goes through it —
panel rows, selection labels, pick-cycle hints, status lines, every refusal note, the create dialog's candidate
rows, the space dropdown, the load's own findings. The runtime id stays exactly what it was and is now purely
internal: the DOM rows still key on it, the per-element maps still key on it, and nothing shows it.

- **Cached, and recomputed when the journal changes.** An append names only new elements, so existing names are
  stable; a delete replays the whole script into a fresh document anyway. That covers every case the user can
  observe, which is why no id-preservation machinery was needed.
- **An element created by the operation *now recording* can already be named**, because its name is determined:
  the journal grows at the end, and the step about to be appended declares what it created in creation order. Without
  that, exactly the notes that report what a gesture just built — "e7 split into e8 and e9" — would have fallen
  back to internal ids.
- **A retired element keeps its number.** A vertex a join coalesced is still created by the replay (the later
  `orthojoin` must have something to collapse), so it still occupies a name in the file. That is the one place
  where a name is not the name of anything visible, and the file wins: the point of the name is to be the same
  on both sides.
- **The migration findings finally reach the user.** `Document.loadNotes` — the "which element could I not
  decide about" of the *Versioning & migration* rule above — was published into the document's one-shot note,
  which only a tool run reads, and then `Editor.replaceDocument` cleared the status line as its last act. The
  one thing a migration owes the user was written and thrown away. Opening a file now says it, once, naming the
  element the way the file does.

#### Visibility is recorded after all — a reversal (as built, on a user report)

This decision is **reversed**. What it said, and why, is kept verbatim because the reasoning was sound and the
conclusion was still wrong:

> Visibility is a **view** state: the saved file is a construction (OP-18) and has no viewing section, so
> hiding is deliberately neither persisted nor an undo step.

The user's counter-argument wins: the file is a construction the user has *arranged*, and reopening a drawing
with every hidden helper line back on top of the result is **data loss from the user's chair**. Purity was
never the point of the format — replayability was, and a hide replays perfectly. It is also, precisely, a
*decision*, which is the same reason a `Select` sign is stored (OP-1): a decision the file drops is a decision
the user has to make again.

So `hide els=e1,e5` / `show els=…` are journal steps, batched one per gesture, and they behave like the other
steps that *name* elements without being built from them (`group`, `place`): membership is state, so save
writes the members the script still declares, a delete leaves the rest, and a step with none left is dropped
(`Document.dependentSteps`). Load applies them in order, so a later `show` simply wins. Undo/redo came free —
the undo substrate *is* the saved script.

**One rule, everywhere: per-element steps.** A group's hide/show records the same step over the group's
members rather than a flag on the group. A group flag would be a second thing a file can say about
visibility, and the two would need reconciling on every membership change; as members, the state also stays
with the elements when the group is dissolved.

**What stays unrecorded is what is not a decision:** a welded alias hides *by construction* (it would draw a
second point on its master), so nothing records it and `setElementsVisible` refuses to show one. That is the
line the reversal draws — the file records what the user chose, not what the construction implies.

`macrodef "widget" els=e1,e2,e3 pts=e1,e2 scalar="r"` is the third step of that kind (OP-6): it declares a
**user-defined tool** over elements earlier steps built — which of them are the definition, which of their
free points are its click slots (the first is the anchor) and which panel scalars it consumes. It creates
nothing, so the custom tool is part of the *file* rather than of the session, and an **instance of it is an
ordinary `tool macro:widget …` step**: the only thing the format needed was for the tool registry to be the
document's (`Tools.all` plus its macros) instead of a static list.

`place "kitchen" at=30,50 angle=15deg` is the same kind of step one level up (OP-16 step 2): it names a
group and restates its frame, and replay re-runs the same retrofit. The members' `point` steps keep
restating **world** positions — they replay before the placement, which then derives the local
coordinates from world position and frame — so a placed group adds no local coordinates and no node
names to the file, and a `place` step whose group step is gone goes with it.

#### The drawing's **name** is not in the file (decision)

A drawing now has a name — an editable field in the topbar, defaulting to `drawing`, and Save writes
`<name>.cit`. **The name is shell state and is deliberately not a step, not a header field and not part of the
format.** The reasoning is the format's own: the file *is* the construction, a name constructs nothing, and the
filesystem already holds it — better, because it stays true under the operations a file undergoes. Rename the
file and a name stored inside it is a lie; copy the file and the copy claims to be the original; two drawings
saved from the same session would disagree with their own contents. Loading therefore takes the name **from the
picked file** (its base name, extension stripped), which is the same fact read from the place that owns it.

This is not the visibility reversal in reverse. Hiding is a *decision the user made about the drawing*, so
dropping it is data loss; a file's name is a *fact about the file*, and storing it duplicates something the
environment already answers. The test that keeps the two apart is the round trip: `save → load → save` must be
byte-equal, and it is unaffected by what the drawing is called.

What is shared code is the arithmetic — `DocumentName` in `commonMain`, unit-tested: the default, what a typed
name normalizes to (trimmed, path part dropped, a typed `.cit` not doubled, file-illegal characters replaced,
bounded in length), and how a picked file's name is read back. The *platform* half can only live in `jsMain`.

**Save is a real Save where the browser has one.** With the File System Access API (Chrome's
`showSaveFilePicker`) the first Save asks once and every later Save writes back to that **same handle**, so
repeated saving is one file rather than a pile of numbered downloads; *Save as…* is the separate affordance that
asks for a new one, and Open through `showOpenFilePicker` hands back a handle too, which is what makes the next
Save a real one. The handle is kept only while it still *is* the drawing's name: edit the name field and Save
asks again, because the field says what the drawing is called and silently overwriting the old file would make
it a lie in the other direction. Feature-detected, no library, and **every** failure path ends in the download that always
worked: no API, a refused prompt, a revoked permission, a vanished file. One case deliberately does *not* fall
back — a **cancelled** picker, because downloading behind the user's back is the wrong answer to "not that
name". The API is also switched off over `file:`, where a page has no origin to remember a handle for and Chrome
refuses the picker anyway; that keeps the browser E2E on the fallback path, where it asserts the download's
name rather than hanging on a native dialog.

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
- **A meeting point may be *determined* rather than owned.** How much freedom the arriving corner still has
  decides which kind of meeting point this is. Two free coordinates make a junction owning one DOF along the
  curve; **one** free coordinate cannot — a second junction would own a slide the corner's other coordinate
  already fixes, one DOF too many — so the meeting point is *derived*: where the axis line through the
  coordinate the corner no longer owns crosses the curve (`bindCornerToDeterminedMeeting`, composed from
  `pointXY` + `lineThrough` + `intersect*` + `Select`, with the circle's branch a stored discrete choice per
  OP-1). The count stays right (1 free → 0) and no solver appears. This is the **second end of a T-web's
  middle run** — the case a user reported as *"the ending did not snap and did not finish the path"*: its x
  belonged to the junction at its first end, so the attach was refused, and because the refusal was silent
  the run neither joined nor finished. The axis line is built from the given **coordinate**, never from the
  corner's point, which depends on both coordinates and would put the binding inside its own input cone.
  Welding such a corner onto a *point* is still refused — a weld pins both coordinates, and one of them is
  no longer the corner's to give — but it now says so, and says that reaching the leg through that point
  works instead.
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

**Where a thing sits along its host is an absolute quantity, never a share of the host (as built).** A
junction held its position as a distance along the host's *carrier line*, measured from that line's
`origin` — which for a segment's carrier is one of the segment's own endpoints. So the stored position was
relative to the host's extent, and **editing the host dragged everything attached to it**: on the reported
drawing (a closed rectangle plus a T-branch from the top wall to the right wall) dragging the *bottom* wall
down by 20 took the branch's horizontal leg from y=17.25 to y=-2.75, because the right wall's carrier line
takes its origin from the corner the bottom wall owns. The user named the rule better than the code did:
*it should be transparent to which corner a segment-attached point is anchored.*

One helper (`Document.riderOn`) now decides the parameter for **every** route that puts a position on a
curve, so the routes cannot disagree:

| route | was | is |
|---|---|---|
| `bindCornerToJunction` — a run's end on a wall (the report) | distance from the line's origin | the **world coordinate the host leaves free** (`OnAxisHandle`) on a host axis-aligned *by construction*; distance along the line otherwise |
| `bindCornerToDeterminedMeeting` — a second end with one coordinate left | already absolute: it stores *no* parameter, being the crossing of the axis line through the given coordinate with the carrier | unchanged, now pinned by a test |
| `attachToCurve` — drag a free point onto a curve | distance from the line's origin | the same rule as a junction |
| `pointOnLine` — the point-on-line tool's slider | distance from the line's origin | the same rule |
| `pointOnCircle`, a junction on a circle | angle about the centre | unchanged — a circle has no ends to stretch, so an angle is already absolute |
| a thick path's **openings** (OP-21) | distance from the leg's start | unchanged, and deliberately: an opening is measured from a wall's corner because that is what a plan drawing dimensions, and a join re-measures it (OP-19). It also pays: a distance along a leg is what a rigid placement preserves, so dragging an opening's jamb needs no frame case — see *openings are grabbable at their jambs* |
| `pointAlongLine` — the point-at-a-distance tool | distance from a point the user picked | unchanged: the anchor is *stated*, which is the opposite of hidden |

Two decisions inside that:

- **An axis-aligned host gets a world coordinate, but only when the construction keeps it axis-aligned** —
  an ortho path's leg, and not while its path is placed in a group (a placed path's legs are axis-aligned in
  the *group's* space, OP-16). A segment a user happened to draw horizontally is aligned by coincidence, and
  the next drag turns it; a fixed axis line crossing it would then race off toward infinity. The parameter is
  the coordinate the host does not determine, and the meeting point is where the axis line at that
  coordinate crosses the carrier — the same primitive stack as a determined meeting. It is direction-blind
  (inverting a wall by dragging its far corner past the near one cannot mirror what rides it) and exact (an
  axis crossing an axis-aligned line needs no division), which is why the drawings above are byte-stable
  through `save → load → save`.
- **A diagonal host has no world coordinate to offer, so it keeps a distance along the line — but anchored
  to the line, not to the host.** The anchor is the point of the line *nearest the world origin*, so the
  parameter is `world · dir`: a property of the carrier alone. Dragging a slanted wall's endpoint *along its
  own line* — an edit that changes nothing one can see — no longer slides what rides it. The limit is that
  **turning** the host still moves the rider, and no parameter along a curve can avoid that — *but a
  **gesture** compensates, which supersedes the limit where it was felt* (see below); a second, smaller one is
  that reversing the line's direction mirrors the rider about that anchor, and projection turns out to cure
  that too. Both were recorded rather than papered over, and both are unreachable on an axis-aligned host,
  which is where walls live.

The file format needed **nothing**: a step restates the *position* its creation produced and the attach
steps re-derive their parameter on replay, so old files come back with absolute anchoring simply by being
loaded (OP-18's "replay reconstructs" earning its keep again).

#### A corner may meet **two** junctions, one per coordinate (as built, on GitHub #4)

Reported on a T-web — a closed rectangle, one branch turning once between the left wall and the bottom wall,
one straight run between the top wall and the bottom wall: the corner where the turning branch bends *"cannot
move freely (only one direction) and snaps back to its original location"*, and so did other corners.

**The junction model was right; the handle could only hold one of them.** That branch attaches at both ends,
and the two ends land on walls of *different* orientation, so after both attaches:

- the junction on the (vertical) left wall owns the branch's **y** — and, through the binding that keeps the
  horizontal leg horizontal, the y of both of that leg's ends;
- the second end arrives with **two** free coordinates (its own y, and the x it shares with the bend), so it is
  an ordinary junction too, and the one on the (horizontal) bottom wall owns the branch's **x**;
- the bend between the two legs is therefore driven by **two junctions, one per coordinate** — and
  `OrthoCornerHandle` held a single `junction`, `junctionOf(x) ?: junctionOf(y)`. With both coordinates driven
  the drag took the *whole cursor* branch and handed it to whichever junction the x lookup found first, so the
  y half of the gesture was dropped: the corner tracked the cursor's x, ignored its y, and returned to where it
  started as soon as the x did. Two degrees of freedom, one of them reachable.

Per-axis delegation is the fix, and it is the same sentence OP-20 already made about *which* coordinate is
delegated, now applied to *whose* junction: `jx.place(0, …)` and `jy.place(1, …)`, each to the junction that
owns that axis. The whole-cursor branch survives for the one case it was written for — **one** junction owning
both coordinates, a weld onto its point — where a projection is genuinely better than a pair of per-axis
places. That the count was never wrong is worth stating twice: this was attribution again, and the editor
exposes attribution.

Three things the diagnosis is worth recording for:

- **What it was not.** Rider compensation was not involved and could not have been —
  `Document.riderAnchors()` is *empty* on this drawing, since every host is an axis-aligned leg and such riders
  are never registered (see the note above). Nor was it the pick pile or selection priming: the status line
  named the right corner throughout. "Snaps back" reads like a gesture writing a node and something re-solving
  it, and it was not that; it was half a gesture never being written at all.
- **The panel is what gave it away.** *Typing* the bend's y worked while dragging it did nothing — because a
  coordinate field asks `junctionOf` **per coordinate** and the drag did not. Whenever typing and dragging
  disagree, one of them has the answer (OP-13 is not only a promise to the user; it is a diagnostic).
- **And typing was over-promising in the other direction.** `orthoCoordField` offered *any* driven coordinate
  as writable, so a junction riding a horizontal wall advertised a `y` whose write did nothing at all. A driven
  coordinate is not read-only **as far as the junction can reach, and no further**: a junction on a host that is
  axis-aligned by construction owns exactly one world coordinate, and the host determines the other outright.
  `Junction.placeable(axis)` is that question — structural, asked before a value exists — so the field greys out
  exactly where the drag along that axis moves nothing. A value merely *out of reach* (an x beyond a circle's
  diameter) is still a `place` refusal, which is a different thing and stays one.

One more defect fell out of the same assumption, on the same drawing: **welding onto such a corner** bound both
of the arriving corner's coordinates to *one* of the two junctions' points — a point that is not where the
corner clicked is. `bindCornerToMeeting` now takes the driving point and a junction **per axis**: one junction
owning both keeps the flat one-hop reach through its own point, and where the target's coordinates come from
two different places (two junctions, or a junction and a *determined* meeting) the meeting point is the
target's own vertex and each coordinate follows whatever drives it there. Before this, welding a run onto the
straight run's lower end — whose x is a junction's and whose y is a determined meeting — landed it at the *top*
wall.

Pinned by `OrthoWebFreedomTest`: the reported file verbatim, then every corner and every leg dragged on each
axis separately from a freshly loaded fixture, asserting the axes that move and the axes that must not, plus
the panel's writable coordinates agreeing with them element by element. Reverting the delegation fails exactly
the two tests about the bend and leaves the rest green, which is what makes it a regression test rather than a
snapshot.

### A gesture compensates the riders of the host it turns (as built)

The recorded limit came back as a report: on the drawing above's diagonal cousin — a segment with two riders
on it joined by a third segment — dragging the host's right endpoint 90° down made the inner segment slide
dramatically along the turning host, "even beyond its extent". Both riders kept their distance *along* the
carrier while the carrier's direction changed under them, so the pair travelled rigidly along a line that was
sweeping.

**The limit is a property of the parameter, so the answer is the edit, not another parameter.** While a
gesture edits a host, every rider whose parameter is carrier-anchored is **re-solved on every move** so it
sits at the projection of the world position it had **at grab time** onto the host's current geometry. These
are ordinary literal writes inside the gesture — OP-13's discipline (*a drag writes free source nodes*)
extended from the thing grabbed to what depends on it. Nothing is asserted, no solver appears, the model
stays a pure function of its parameters, and the file restates the compensated values like any others.

Five decisions make it one rule rather than a family of patches.

- **The reference is the grab, never the previous move.** That single choice buys all three properties at
  once: a stretch or a translation that leaves the host's direction alone writes *nothing at all* (projection
  is the identity there — the parameter already holds `world · dir`), a rotation moves each rider
  continuously and by the least the host allows, and dragging back to where the gesture started restores
  every rider **bit for bit**, because an unturned host restores the grab-time literal itself instead of
  recomputing it. An incremental reference would have drifted on all three.
- **Which riders: only the carrier-anchored ones, and they are known structurally.** A rider on a host that
  is axis-aligned by construction stores a world coordinate, and one on a circle stores an angle about the
  centre; both are anchored to something no edit to the host can move, so compensation there would be a
  write with nothing to correct. Such riders are therefore **never registered**, which is the cheap check —
  `Document.riderAnchors()` on a wall drawing is empty, and the absolute semantics above are reached by
  exactly the code they always were.
- **One seam, not one per handle.** The registry is filled in `riderOn`, the single helper that already
  decides every rider's parameter, and the compensation runs once in `Editor.pointerMove` after the handle's
  drag. So endpoint drags, leg drags and the junction delegation of OP-20's own per-axis rule are all
  covered without knowing about each other — and the **discrete twin** (a typed field, a panel parameter that
  drives a host) is the same call around the write, because typing and dragging are one operation.
- **A rider the gesture itself writes is never compensated**: its own drag wins. It is recognised by its
  parameter no longer holding what the compensation last left there, which needs no knowledge of *which*
  handle is being driven — the delegated junction of a corner drag identifies itself the same way an
  explicitly grabbed slider does.
- **Chains are compensated outer to inner.** A rider may *carry* another rider's host (a segment drawn
  through it), and projecting the inner one onto geometry still about to move would be wrong; the pass is
  ordered by the ordinary `dependsOn` walk, and the evaluator is renewed after each write so the next rider
  sees the corrected geometry.

Undo needed nothing: the whole drag is one checkpoint as before, and a checkpoint is the saved script, so the
compensated literals ride the same snapshot. That did expose one real gap in the file, fixed here: the
`pointoncurve` step restated *nothing*, so a rider's position — dragged, typed, or compensated — was rebuilt
from the click that first placed it. It now carries the rider's own parameter as `dofs=`, the same seam a
dimension's placement uses; the click stays verbatim, because what the click encodes is a **choice** (which
curve, which side of a circle) and replay must repeat it, while where the rider sits is state.

What is deliberately *not* compensated is a **placed group's frame drag** (OP-16): that gesture moves the host
and everything on it as one rigid body, which is exactly the intent, and re-projecting the riders against a
grab-time reference would fight the gesture rather than help it. The rule is about a host being **reshaped**,
not about the whole drawing being carried somewhere else.

**And an EXPLICIT anchor supersedes the compensation entirely (as built).** Compensation is the answer for a
position the user never stated — a parameter anchored to the world, whose *meaning* turns with the carrier. Once
the user says what a rider is measured **from** (*Make relative* on a shared carrier, see OP-4 case (b) above),
its motion under an edit of the host is **fully stated**: it keeps its distance from its base by construction,
so turning the host moves base and dependent coherently and there is nothing left to correct. Such a rider is
therefore **not registered for compensation at all** — the same structural test the note above already uses for
riders that are absolutely anchored, now with a third reason to be off the list. Asserted rather than assumed:
after the re-anchoring `Document.riderAnchors()` is empty on that drawing, so a 90° turn of the host performs
**zero** compensation writes and the geometry is still exactly where the stated distances put it.

That is the general rule the two halves make: **compensate only what the model does not say.** A gesture-time
correction is a patch over an unstated quantity, and the moment the quantity is stated the patch must switch
itself off, or the two would fight. It is also why *Make relative* on a rider is worth having beyond its own
convenience: it converts a compensated position into a constructed one, and the group placement below uses
exactly that conversion to make a figure rigid.

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

#### Break on a plain segment, an arc and a Bézier (as built — the user's design)

One tool, dispatched by **what the click landed on**. An ortho leg keeps the jog logic above verbatim (its
break is a topology edit on a path); any other segment, arc or cubic Bézier is split as an ordinary
**construction**, because off a path there is no topology to edit — only geometry to build. The gesture and
the promise are the same in all four cases: the split lands *exactly* where the curve is, so the drawing does
not change shape at the moment of the break, and the joint is a real freedom from that instant on.

| clicked | the split | the halves | the freedom |
|---|---|---|---|
| ortho leg | two vertices, a zero-length perpendicular leg | three legs | the jog opens by dragging either half |
| segment | a **free point** at the projection of the click | two segments over the same endpoints | the point bends the joint |
| arc | a **rider** at the click's angle on the carrier circle | two `arcBetween` on that carrier | the rider slides, re-splitting live |
| Bézier | de Casteljau over one shared `t` | two cubics over the constructed controls | `t` slides the split along the curve |

**The consumer rule, and why it is the whole design (OP-5).** Nothing is ever rewired, so the original
curve's node keeps whatever meaning it had. What differs is whether the original is still *needed*:

- **Nothing reads it** → the break **replaces the step that drew it**: that step is dropped and the
  remaining script replayed (the same journal-rewrite-and-replay a delete uses, OP-18), so the file reads as
  the construction of the two halves and there is no third curve in the drawing. This is only expressible
  because the halves are built from the curve's **own defining points** — the picks of the step that drew it —
  rather than from the curve; that is the one structural choice the feature turns on.
- **Something reads it** — a fillet leg, a rider, an outline piece, a dimension, a measurement, or a later
  step that merely *names* it (a group's membership) → the original **stays**, hidden by a recorded `hide`
  step, and the status says which element is why: *"e3 stays (hidden): e7 is built on it"*. Retiring it would
  silently change what that consumer means, which is the one thing OP-5 forbids; hiding costs nothing, since
  the halves cover it exactly.

Three consequences worth stating, because each is a *property* rather than a case:

- **An arc is always its own consumer.** Its halves are trims *of it* (OP-14: a trimmed circle is an arc), so
  the shared carrier they need **is** the original — there is nothing else an arc offers. It therefore always
  takes the hidden route, and the status says so in those words rather than pretending a choice was made. The
  alternative (rebuilding the carrier from the picks of whichever tool drew the arc) would put per-tool
  knowledge into the break, which is exactly what the data-driven registry exists to avoid.
- **A curve the drawing *derives* still breaks**, over its **key points**: a mirrored
  segment or a spline a macro built has no picks of its own, so the break materializes `keypoints` on it (for
  which `extractPoints` gained the Bézier case — all four controls, through the new `bezierControl(i)`
  accessor) and keeps the original as their source, hidden. So no kind is half-supported; what varies is only
  whether the original can go.
- **Two refusals, both about a promise the break could not keep.** A curve a user-defined tool is built from
  is refused as the ortho break refuses a leg (OP-6: replacing it would leave the definition describing
  geometry that is gone), and a member of a **placed** group is refused because the free point a break
  introduces is a world point the frame would not carry (OP-16 — membership lives in the recorded `group`
  step, and a step's arguments are never rewritten, so the new point cannot simply join).

**de Casteljau as a construction, not as a computation.** The five intermediate points and the split point are
`pointAtRatio` nodes over the four controls, all sharing **one** `t` parameter — the subdivision triangle,
built rather than evaluated. That buys three things at once, and they are the reason not to compute the halves
numerically and store the result: the halves *are* the subdivision formula, so they are exact; they stay exact
under any drag of the controls, because the formula is re-evaluated rather than re-fitted; and `t` is an
ordinary live parameter, so **the split slides** — type it, or drag any of the six ratio points, and the curve
re-splits where you put it. One `t` shared by six ratio points is also the OP-5 statement in miniature: it is
one degree of freedom because it is one node, not because anything says so.

**What the file records.** The segment and Bézier breaks record no step kinds of their own: a half of a segment
*is* a segment, a half of a spline *is* a spline, and a de Casteljau point *is* the ratio point Midpoint makes,
so the break emits ordinary `point` / `param` / `tool` steps and replays through the same `ToolDef.build` a
click would have run. Only the arc needed one, `breakarc <el> <angle> ccw|cw`, because everything it makes
hangs off the arc it names: the **angle is state** (the rider slides, so it is restated on save) and the sweep
is a **stored discrete choice** (OP-1), taken from the carrier and never re-derived from the click. One
checkpoint per break, however many steps it emitted — the same rule a path's start/vertex/close steps follow.

## Patterns as orbits (OP-23 — RESOLVED)

**The user's design, adopted whole.** A pattern is not a copy of geometry. It is a **rule** — a reference
member, what that member is repeated about, and a count — plus the list of gestures that ride it. The rule:

> **Any subsequent operation whose inputs touch pattern members is replicated by index shift.** Draw
> `segment(ref0, ref1)` and the editor also records `segment(refj, refj+1)` for every *j*. Fillet
> `(edge0, edge1)` and every corner rounds. And the **outputs of a replicated operation become members at
> their own index**, so the orbit *grows*: geometry built on replicated geometry replicates too.

A circular pattern is a centre click, a reference click and the count field; creating it replicates the
reference point *n*−1 times round the centre (the ring — polygon vertices, but as a live object). The linear
analog is a base point, a step vector's end, and *n*.

### Why this is not the array tool one level up

The two look alike and are opposites, which is the whole reason both exist (`ARRAY_*` is untouched by this OP):

| | array (OP-6's macros, generalized) | pattern (this OP) |
|---|---|---|
| what is repeated | **geometry** — copy *k* is a transform node over the original | a **gesture** — copy *j* is the same tool applied to shifted members |
| what a copy is built on | a transform of the original's points | the **shared member points themselves** |
| adjacent copies | meet at two coincident-but-distinct points | meet at **one node** |
| adding a feature later | array it again, and hope the two arrays line up | draw it once; it fans out |
| changing the count | use the tool again (structural, OP-18) | **re-stamp**: the rule is re-run at the new count |

The third row is the load-bearing one. Because a copy is built *on* the members rather than transformed off
copy 0, **there is no seam**: side *j* and side *j*+1 reference the very same `SourceNode`, so they coincide
because sharing a node *is* equality (OP-5) and not because a tolerance says so. The visible dividend is that
the Outline tracer crosses every copy boundary and every fillet joint **with zero new machinery** — a rounded
hexagon traces in two clicks, exactly as the hand-drawn filleted triangle of OP-14 does, and extrudes
watertight. That is asserted rather than asserted-of (`PatternTest.adjacentSidesShareTheRingsPointNodes`,
`theRoundedPolygonTracesInTwoClicksAndExtrudesWatertight`).

### The invariance rule — what a replicated gesture may touch besides members

A gesture replicates when one pick is a member **and every other pick is either a member of the same pattern
or invariant under the pattern's transform.** Otherwise it does not replicate, applies once, and the status
line names the input that stopped it (*"not replicated: e9 is outside the pattern"*). Three consequences, each
falling out of the geometry rather than being legislated:

- **Scalars need no test at all.** Every copy is handed the *same* parameter node, so one radius drives all
  six roundings by reference (OP-5). Retyping it re-rounds the lot with nothing regenerated.
- **A rotation has exactly one fixed point: its centre.** So a spoke from a member to the centre is a
  legitimate replicated gesture, and six spokes come out of one click pair.
- **A translation has no fixed point.** So a *linear* pattern's gestures may touch members and shared scalars
  and nothing else. Stated, not worked around.

**Alt suppresses replication**, which is the existing Alt semantics ("leave the model as I put it") said one
level up: this feature is a one-off — a keyway, a single flat, one chamfer. Note the two halves agree rather
than fight: on a slot that *places* a point Alt already declines the snap, so a one-off there is a one-off by
construction, and on a slot that picks existing geometry (a fillet leg) Alt is what makes the single feature
possible at all.

Two exclusions are **declared** in the tool table (`ToolDef.replicates`, and it defaults to **true** —
the rule of this OP is that *any* gesture fans, so a table where each tool had to opt in would be a rule with
exceptions instead): a tool that owns a degree of freedom of its own whose value is absolute (the point-on-line
and point-on-circle riders, the dimensions, the re-parameterizations) and a **measurement**, which is a
*reading* rather than geometry — six copies of one number is clutter where one is the answer. Two more are
structural and enforced in `Document.replicationOf` rather than declared: a `repeating` tool already collects
the whole ring in one gesture (that is the Outline tool), and a tool with no slots cannot touch a member.

### Chain or fan — the per-tool orbit rule (as built, on a review probe)

A gesture over *points and curves* is a **fan**: the copies are independent, built on shared members, and
nothing sequences them. A gesture over a *solid* is not, and the difference is the whole of the mechanical
payoff case. A probe found the gap: a ring of four circles on a plate's face plus one **Cut** cut **one**
pocket, because a face-part tool's operand is the part the editor resolved for it (OP-17) — one body, one
subtraction, no fan at all.

**The rule, and it is derivable from the tool's own declaration rather than being a new flag:**

| tool | its solid operand | orbit rule |
|---|---|---|
| **Cut** (`facePartOperand`) | the part of the face space, resolved by the editor — *not* a pick | **chain**: copy *k*'s base is the tip **after** copy *k*-1, i.e. OP-17's sequential-feature rule applied once per index. One body with *n* pockets. |
| **Extrude on face** (a `SOLID` slot) | a picked base solid | **fan**: every copy raises its boss off the *same* base, giving *n* independent solids. Honest, and what "a boss per member" means. |
| Union / Subtract / Intersect solids / Section / Cut openings | two `SOLID` slots, or one | never triggers in practice — a solid becomes a pattern member only if an orbit built it, and then it is an ordinary member fan like any other. |
| everything else (points, curves, fillets, …) | none | **fan** over members. |

Two things fall out of this. A **solid is the one non-member, non-invariant input a replicated gesture may
touch**, because it is the body a feature is applied *to* rather than a geometric input that has to travel with
the copy — every other outside input is still refused by name. And **subtractive means chain, additive means
fan**, which is not a special case but the same statement OP-17 already makes about a single feature: a cut
consumes the part, a boss consumes a face.

**What the file records for a chain: the rule, not the *k* names.** A single `tool cut` step names its resolved
base, and deliberately so (replay must not re-resolve it and fork). An orbit cannot: copy *k*'s base is a
different body for every *k* **and for every count**, so a baked chain of names could not exist at another
count — the re-stamp would have nothing to hand copy 5 of an eight-fold ring. So the step says `part=tip`, and
each copy resolves the tip in index order exactly as the editor's own click did. That is a *positional*
reference, of the same kind the ortho steps have always used for "the current path", and it is deterministic
for the same reason: the script prefix before it is the same on every replay. The chain is one `orbit` step, so
one undo removes every pocket, and a count change re-runs it (four pockets → six, asserted by volume, since
volume is what tells a chain from a fan: four *forks* would leave a tip missing one pocket).

### The bookkeeping encoding — one step per gesture, and it is the rule

Replication is **edit-time bookkeeping**, in the exact sense of OP-18's outline-follow precedent: *the machine
saves the clicks, the file stores what the clicks stated.* Two step kinds, both pure descriptions:

```
point 0,0 -> e1
point 100,0 -> e2
pattern "P1" circular ref=e2 centre=e1 count=6 -> e3,e4,e5,e6,e7
orbit "P1" segment pts=e2@0,e2@1 cells=100,0;100,0.0000000000000071054273576 -> e8,e9,e10,e11,e12,e13
param "fillet" = 20mm
orbit "P1" fillet els=e8@0,e8@1 cells=65,60.62177826;85.00000000000001,25.98076211 scalar="fillet" signs=-1;1 -> e14,…,e19
tool outline els=e8,e14,e9,e15,e10,e16,e11,e17,e12,e18,e13,e19 clicks=… -> e20,…,e32
tool extrude els=e32 scalar="depth" -> e33
```

That is the whole acceptance model — a rounded hexagon, traced and extruded — in nine lines. The decisions
inside it:

- **One `orbit` step per gesture, not *n* ordinary steps.** The alternative (record the *n* copies as *n*
  ordinary `tool` steps) replays just as exactly, and was rejected for one reason: the file would then not know
  that the *n* steps were one gesture, so the count change would have nothing to re-run. A compact encoding
  where the step **is** the rule makes the journal bookkeeping the file's own content rather than session
  state. One step is also one checkpoint, hence **one undo removes the whole orbit** — no extra rule needed.
- **A member pick is written `e2@1`: the member of `e2`'s orbit at index 1.** `e2` is always that orbit's
  member 0 — the reference point for the ring, copy 0's output for a derived orbit — and member 0 is the one
  member *every* count has, so the reference survives a re-stamp in either direction. It is an ordinary
  element reference, which is what makes an orbit's picks travel through the delete cascade and the name map
  with no new case (`Arg.Member`).
- **Indices are stored relative to the gesture's lowest member index.** The recorded rule therefore says
  nothing about *which* copy the user happened to click: the base copy is the gesture shifted down to offset 0,
  and copy *j* uses `offset + j` (mod *n* for a ring). One consequence worth having: the same clicks produce
  the same file whether the user started at member 0 or member 4.
- **`cells=` are the gesture's clicks, carried back to the cell of member 0.** Geometry is *never*
  transformed here — a click is, because a click states a choice ("this quadrant", "the outside") and the
  corresponding choice in another cell is that same click carried round by the pattern's own transform. Storing
  it cell-locally is what makes a re-stamp come out right: at a new count each copy's click is re-derived from
  the pattern's *current* shape, so it lands where the new member is.
- **`signs=` are scored exactly once, by the first copy, and handed to every other copy verbatim** (OP-1,
  and OP-18's *a scored choice must be persisted at creation*). Every copy of a congruent configuration scores
  the same variant anyway; taking the first copy's answer removes both the redundant work and the floating-point
  risk of scoring a rotated click against rotated geometry.
- **The loader's count check still vouches for everything.** An `orbit` step declares *n*×*k* names and must
  create exactly that many; a `pattern` step declares *n*−1. `save → load → save` byte-equality is asserted at
  every stage of the acceptance flow, the count changes included.

### Count change — a journal rewrite, and the one thing mod-*n* cannot absorb

Changing the count is the delete machinery's move with one literal changed instead of a step removed: save the
script, replay it with the pattern's `count=` rewritten, adopt the result (`DocumentFormat.restamp`). Nothing
is copied and nothing is patched — each `orbit` step *is* its gesture's rule, so re-running the script at the
new count **is** the whole update. Three rules make that safe:

- **Names map positionally.** A member a bigger count adds is simply unnamed (nothing referred to it); a
  member a smaller count removes leaves its name unmapped, and the steps that named it are **dropped and
  named** in the status line. That is precisely the promised behaviour for an Alt-suppressed one-off: it stays
  single through any count change *if its anchor index survives*, and if it does not, the drawing says which
  feature it lost rather than silently moving it somewhere else.
- **A step a re-stamp did not resize is still held to the strict count check.** The relaxation is scoped to
  the pattern being re-stamped, its own gestures, and a re-followed boundary — everywhere else a count mismatch
  remains the load error it always was (OP-18). A load is strict; a re-stamp is an *edit*, and an edit may lose
  something as long as it says so.
- **A gesture spanning more neighbours than the new count has members is refused, by name, before anything
  happens.** This is the honest answer to *"can that even happen with mod-n?"* — **yes.** "Member 0 to member
  4" is a pair at six; at three it is not a pair at all, and folding it to (0, 1) would quietly make a
  different drawing. So `Document.restampRefusal` reports *"can't re-stamp pattern P1 at 3: its segment spans 4
  members of a 3-member orbit — use the tool again instead"*, and the drawing is untouched.

**A row does not wrap, and that is not a special case.** For a linear pattern a gesture spanning *m*+1
neighbouring members makes *n*−*m* copies — four rungs between five holes — because wrapping round would jump
back across the whole row. One expression covers both kinds (`copiesFor`): a ring's copy count is its member
count, a row's is `min(orbit size − offset)`.

**The traced outline re-follows itself, rather than invalidating.** This was the one open choice, and the
better of the two options turned out to be available. The Outline tool's boundary-follow is edit-time
bookkeeping (OP-14: the file keeps the whole ordered boundary, and a *load* discovers nothing); a re-stamp is
an edit, so it may follow. When a re-stamp replays an `outline` step whose pieces are pattern members, the
tracer's own two sources of truth — the joint registry and coincident endpoints (`Document.followedLoop`) — are
re-run from that step's first two picks, and the rewritten step carries the full new list. So a hexagon
re-stamped to eight sides comes back as a closed sixteen-piece boundary and a watertight solid, and the file it
saves is as explicit as the one it replaced. If the follow does **not** close (a fork, a gap), the step is
dropped with the reason *"the pattern's boundary no longer closes by itself — trace it again (two clicks)"* —
the honest fallback, and it heals in two clicks. OP-14's refusal of *discovering* the loop is untouched: a
reload still follows nothing.

**Names line up from the right end.** A pattern's and an orbit's creations are copy-major, so a surviving name
keeps meaning the same copy when counted from the start; a traced outline creates its joints first and the
**loop last**, so its names line up from the *end* — which is what keeps the loop's name naming the loop, and
so keeps the extrude built on it alive.

### The everyday shortcut: a polygon with a corner radius

*Regular polygon* gained one **defaulted** length slot, `corner radius` (0 = don't). With no radius it is
exactly the tool it always was, recorded as the same `tool polygon` step (asserted byte-for-byte, and an unused
defaulted slot still costs the step nothing — OP-13's rule). With a radius it builds **this OP's composition**:
a circular pattern of the clicked vertex, one replicated side, one replicated fillet — so the shortcut and the
general mechanism are the *same construction*, the shortcut is fully re-stampable, and the file says which of
the two the user asked for. What it is not is a new shape kind.

One honest wart, recorded rather than papered over: this is the first **defaulted `LENGTH`** slot, and the
existing rule for a defaulted slot only declines a pick of the *wrong dimension* — so a length parameter left
active by a previous tool will be adopted as a corner radius. The result is visible and undoable, and the
alternative (a defaulted slot that refuses every pick) would break the ratio-point behaviour that rule exists
for.

### Click cost

The acceptance model — a rounded hexagon, outlined and extruded — costs **16 actions** by the general
mechanism (pattern 3, one side 3, one fillet 4, outline 3, extrude 3, counting a tool switch, a click and a
typed number as 1 each, per the click-budget rules), and **10** through the polygon shortcut. Drawing the same
figure without patterns is 6 vertices + 6 sides + 6 fillets ≈ 40. The count change afterwards is **2** (type
the count, press Re-stamp) where it used to be "draw it again".

### What is deliberately not here

- **A pattern of a pattern.** The pattern tools do not replicate (what they build *is* a pattern), so patterns
  do not nest. Nothing in the model forbids it; nothing has asked for it.
- **A pattern as a tool operand, or a whole group as a pattern's reference** (OP-16's `groupOperand`): the
  reference is one point. A group *ring* would be the natural next demand, and it is the arrays' territory
  today.
- **Angular spacing other than the full turn** (a 90° sector of six). The count means *evenly round*, exactly
  as the circular array's does; a partial pattern needs an angle input and a decision about whether the ring
  closes, which is a design question and not an omission.

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

### Implementation status (as built — user-defined macros, the UI half)

**An instance is a view over the definition, not a copy of it** — OP-6's *virtual addressing* taken
literally. One new node kind (`InstanceNode`, ~10 lines) holds no computation of its own: it delegates to
a *definition* node's `compute` and only substitutes the inputs, and its id is the derived path-id
`M/nk`. Instantiating therefore maps each definition node exactly once:

| definition node | in the instance |
|---|---|
| a designated **input** | the *argument* node itself — nothing is bound, nothing is rewired |
| an internal **free point** | the definition's position **offset by (this anchor − the definition's anchor)** |
| an internal **ortho vertex coordinate** | the same, per axis (a run holds its position as shared scalars, OP-19/20) |
| any other **free source** (a parameter, a slider's own DOF, a constant) | the definition's own node — a captured default *shared* by every instance |
| anything **derived** | an `InstanceNode` over the same computation, inputs mapped |

Consequences worth stating, because they are what the design promised: **edit-propagation is automatic
and has nothing to synchronize** — dragging an internal point of the original moves every instance on the
next evaluation pass, retyping a captured parameter re-radiuses all of them, and nesting composes
(`M2/M1/n3`) because an instance's nodes are ordinary nodes. **Purity is structural rather than enforced:**
an instance's elements carry no `Handle` and a point of one is `DERIVED_POINT`, so there is no node of
theirs to write; its whole freedom is its arguments. And because every internal node is addressable and
displayable, macros stay **transparent groups** — an outside construction can be built on an instance's
geometry, and a definition can contain an instance.

**Note what this deliberately does *not* use.** The engine's pre-existing `Macro<A, R>` (the Kotlin
build-lambda behind `roundedRect` / `boltCircle`) is a *code-level* macro: it re-runs its body per
instantiation, so it duplicates nodes and gives no edit-propagation. It stays what it is — the way library
shapes are written in Kotlin — while user-defined macros are the OP-6 mechanism, which is the one that had
been sketched and not built. Adopting it for tools would have meant copy semantics; the whole point of the
OP is that it does not have to.

**The anchor rule (one sentence, and the reason instances land under the cursor):** *the first point input
is the anchor, and every internal free position is captured relative to it.* So the captured constants are
a captured **relative layout**, not frozen coordinates: clicking elsewhere translates the whole instance,
and moving the definition's own points still re-propagates. Rotation is not part of it — an instance is
placed, not oriented (a frame would be, and that is OP-16 step 3's territory).

**One dialog, two defaults — OP-16's claim, cashed in.** `CreateDialog` (commonMain, unit-tested) is what
both *Group…* and *Make tool…* open. Its rows are the free sources the selection's closure reaches, and
what ticking one means is the only difference: a **member** for a group, an **input port** for a tool.
Defaults: nothing ticked for a group (a plain named set, exactly what the button always did), and for a
tool the free points the selection **owns** — its ancestors are offered but left captured, which is what
keeps a definition containing an instance from sprouting the inner definition's points as extra slots. When
the selection owns no free point at all (only derived geometry was picked), its ancestors are ticked
instead, since otherwise there would be no input to place by. Parameter rows appear for tools only: a group
takes elements. Scalar inputs need no new machinery — they are the ordered scalar slots the tool-completion
work already added.

**A macro is a `ToolDef`, so the palette needed no new kind of button** — only a registry that is no longer
static: `Document.toolDef` serves `Tools.all` plus the open document's macros, and the four call sites in
the controller (help, click collection, replay) ask the document instead of `Tools`. That is the whole of
"custom tools" in the UI layer; the palette rebuilds its *custom* category from the document, so opening a
file brings its tools with it.

**Persistence: `macrodef "widget" els=e1,e2,e3,e4,e5 pts=e1,e2 scalar="r"`** (OP-18) — a step that creates
nothing, like `group`: a *designation* over what earlier steps built, naming the definition's elements, the
point inputs in slot order (first = anchor) and the scalar inputs in consumption order. An instance is an
**ordinary `tool macro:widget pts=e6,e7 clicks=… -> e8,e9,e10` step**, so the instance format is the one
every tool already has, and definitions precede instances by construction, which is exactly what replay
needs. `save → load → save` byte-equality with a definition and several instances is the test.

**Delete: refused, naming the instances.** Deleting anything that would take a definition away while
instances live on is refused ("it defines tool widget, used by 3 instance elements (e8, e9, e10) — delete
the instances first"). The two alternatives were both worse: *cascading* would silently delete work the
user never selected (an instance's step names the **tool**, not the definition's elements, so it is not a
dependent in the ordinary sense), and *orphaning* is not OP-3 invalidity here — an instance's element count
is structural (OP-18), so an orphan is a file that no longer loads. Selecting the instances **together
with** the definition is allowed and is one operation, since then nothing is lost silently. Retiring a tool
from the panel follows the same rule, and drops its step outright (as `ungroup` does). Belt-and-braces: the
step-closure also drops instance steps of a dropped `macrodef`, so no route can leave an unreplayable
script. The same rule covers the two edits that *retire* elements without deleting anything — an ortho
**break** (which replaces a leg with three) and the **join** that flattens a jog: both are refused on a
definition's elements. The break says why; the join simply leaves the jog, which is what a drag with Alt
does anyway.

**Undo:** declaring a tool is one checkpoint and each instantiation is another — both fall out of the
existing seams (`Editor.checkpoint` after the dialog confirms; the tool build for an instance).

**Deliberate omissions at this step.** **Specialization / partial application has no UI** — the engine can
express it (`standardRect` = `roundedRect` with the radius fixed) but deriving a macro from a macro in the
dialog was cut whole rather than half-built; the same effect is available by making a tool from a
construction that captures the value instead of exposing it. A definition's **structure** does not
propagate: adding an element to the original does not add it to existing instances (their element list is
fixed at instantiation, which is also what the loader's count check vouches for) — values and DAG edits do,
structural edits mean instantiating again. A **dimension** cannot be part of a definition (it is an
annotation with its own DOF, OP-14's third column) and neither can a **placed group's** members (their
positions live in a frame an instance would have to carry a copy of) — both refused with a message. A macro
cannot be renamed and its slots cannot be reordered after the fact: both are recorded in the step's
argument list, and recorded arguments are never rewritten. An **ortho run** can be part of a definition, but
only with a free point in the selection to act as anchor — its own vertices are derived, so there is
nothing else to place it by.

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

### Named values in the panel (as built)

A parameter's **name is editable in place** — the panel's name cell is a text field that commits on
Enter or blur — and it uniquifies exactly as creating one does: a clash gets a suffix (`width` →
`width2`), a blank field keeps the old name, and the field then shows the name it actually **took**
rather than what was typed. Names are also normalised to one quote-free word (`wall width` →
`wall-width`), because a step's arguments are split on spaces and a name is written quoted, so either
would otherwise come back as a different name — or not at all.

**The save format needed nothing.** Every mention of a scalar in the script is written as its *current*
name (the `param` step's own label, `scalar=`, `wire`, an opening's `width=`) and load resolves by that
name, so one rename restates the whole file consistently and `save → load → save` stays byte-equal with
everything the parameter drove still wired to it.

That is also the rule for **what may be renamed: exactly what the file names.** A scalar without a
`param` step of its own carries no name into the script, so a rename would either be silently lost on
reload or — worse — write a reference nothing declares. Two kinds fall out of that and are refused with
the reason instead of renamed in memory: a **measurement** (OP-4; its name comes from the step that
measures it) and a parameter another step created as part of itself (an opening's `pos`/`sill`/`head`,
OP-21). Their panel rows show the name as text, so nothing offers an edit that would be refused. One
consequence worth stating: an opening's values are now restated **positionally** against the step's keys
rather than by matching those parameters' names, so the file cannot quietly stop tracking a renamed one.

One cosmetic residue, recorded rather than papered over: a **custom tool's scalar-slot label** (OP-6)
snapshots the entry's name when the tool is first built, so a renamed input port keeps its old name in
that tool's prompt until the file is reloaded. Nothing about the wiring is stale — the port is the entry
itself — only the sentence.

**Values are native number fields.** A value cell is `type=number`, so the browser's own up/down arrows
and the arrow keys nudge it (1 mm / 1°, 0.1 for a dimensionless factor — uniform, not per parameter).
The granularity is stated rather than implied: **every tick writes the model live** (the drawing has to
follow the nudge, which is the whole point of a spinner) and **only a committed change is an undo step**
— `Editor.setParameter(e, v, commit)` is that seam, so the shell routes `input` → live write and
`change` → checkpoint. Intermediate tick values were never operations, exactly as the intermediate
positions of a drag are not (a drag checkpoints on release too). The shell adds one rule to make it
work: while a parameter row has the keyboard its DOM is left alone, since replacing it under a live
spinner destroys the focus and with it the next tick.

**A pick can be switched off, and it had to be** (a user report, and OP-13's symmetry: the panel is as much
an input as the canvas, so it needs the same *never mind*). Clicking the **active** row again clears the
active parameter and forgets it as a pick; Escape with no tool gesture pending does the same, and both say
`no parameter active — tools use their defaults`. What made this a defect rather than a nicety is the
interaction with a **defaulted** scalar slot: a defaulted slot adopts any pick of *its own dimension*
(OP-7 keeps a length out of a ratio, but not a ratio out of a ratio), so the dimensionless factor left over
from building one ratio point shadowed every dimensionless default for the rest of the session — *Midpoint*
kept rebuilding that ratio point instead of the midpoint it was asked for, with no way to say otherwise. The
decision lives in `Editor.clickScalar`, so the shell only routes the row's click, and focusing a value *field*
still picks rather than toggles: that click is on the way to typing. Dropping a pick is not deleting a value —
the parameter stays in the panel and in the file.

### Names for elements and groups — the same rule, two more places (as built)

OP-7 says *nodes get optional user-facing names*, and until now only one kind had them: a scalar parameter.
Two more places wanted the same thing, and both are the **same rule** — *what may be renamed is exactly what
the file names* — applied where it had not been yet.

**An element can carry a name.** `name e7 "bore-axis"` is a step of its own, and the panel then reads
`bore-axis (e7)`. Three decisions in that one line:

- **A step, not a field on the element.** A name is a *decision about the drawing*, which is precisely the
  argument the visibility reversal settled (OP-18): the file records what the user chose. Undo, redo,
  save/load and replay then come free, because the undo substrate *is* the saved script.
- **The script name is never dropped from the display.** `bore-axis (e7)` and not `bore-axis`, because `e7`
  is the drawing's one identity — what the file says, what a refusal quotes, what two people say to each
  other about a drawing. A panel that showed only the label would recreate exactly the two-numbering-schemes
  defect *The name the file gives an element* was written to end.
- **The name is state, so it is restated.** A rename updates the one step's label at save time rather than
  appending a second `name` step — the parameter-rename pattern verbatim. Clearing the field drops the step
  (there is nothing left for it to say), and deleting the element drops it too, through the ordinary
  reference rule in `dependentSteps` and with no special case anywhere: the step *names* the element as an
  argument, which is all that rule needs.

Refused for exactly one kind, for the stated reason: an element **no step created** carries no name into the
script, so a name given to it could only be dropped on reload — or, worse, write a reference nothing declares.

**A group's name is editable, and that exposed a latent defect.** A group is named in **two** steps — the
`group` step that declares it and the `place` step that gives it a frame (OP-16 step 2) — and both now
restate the *current* name, which is how a renamed parameter restates its `param` step and every `scalar=`
reference to it. What had to change beyond that is that the writer no longer looks a placement's group up **by
name**: it asks which group *this very step* placed. That lookup was already wrong and nobody had noticed,
because nothing could change a group's name; with a rename it becomes visible immediately, and in the worst
way — the frame would silently stop being restated and a placed group's position would be lost on the next
save. `GroupRenameTest.aPlacedGroupKeepsBothItsNameAndItsFrame` is the guard.

**Patterns were checked and are not involved.** A pattern's name (`pattern "P1"`, and every `orbit "P1"`
gesture riding it, OP-23) lives in its own namespace, and no pattern or orbit step ever names a group — so a
group rename cannot reach one, and the labels in those steps can stay frozen in the args. That is consistent
rather than lucky: patterns are **not renameable**, so their names are never state, and the moment one became
renameable it would need exactly the treatment the group's just got.

One shell consequence, recorded because it is a real trade: making the group's name an input took away the
row's widest click target, and that target *does* something — it selects the group and feeds a whole group
into an armed geometry slot (OP-16). So focusing the name field **also picks the group**, which is the
parameter panel's own rule (focusing a value field makes that parameter active) rather than a new one.

## Measurements & value feedback (OP-4 — RESOLVED)

- Measurements are **first-class derived nodes** with `Scalar`/`Angle` outputs
  (`Measure.Distance`, `Measure.Angle`, `Measure.Length`, …), **in v1**.
- **Forward-only (driven).** A measured value can feed downstream inputs, but the flow stays
  one-directional. Cycles are impossible by construction (a measurement can only be consumed
  by nodes created after it). A quantity is therefore **driving XOR driven**, never both —
  wanting both is a constraint, which is exactly what we exclude.
- **A solid is measurable too** (OP-17's cheapest downward path): *Volume* and *Extent X/Y/Z* are
  `ToolDef`s over a `SOLID` pick, landing read-only panel scalars that drive *new* 2D construction — the
  papercraft net's dimensions come from the part. Wiring one back into an ancestor of the same solid is
  refused by the ordinary cycle check, which is where driving-XOR-driven is enforced.

### freeze / convert
- **(a) Freeze to constant — IN v1.** Replace a measurement's consumers with an editable
  `Parameter` initialized to the current value (a pure detach). Always possible.
- **(b) Re-parameterize a free source — DELIVERED (the demand arrived; see *Relative points* below).**
  Not graph inversion but a coordinate change: replace a *free* source node (e.g. a free
  point, 2 DOF cartesian) with an equivalent construction that exposes the measured quantity
  as a driving parameter, capturing residual DOF from current geometry
  (e.g. `P2 = P1 + PolarVector(d, θ)`). Preserves DOF count → **no solver**. Refused when the
  quantity's endpoints are fully determined (no free DOF to absorb the input).
- **(c) General inversion** (solve for a determined quantity) — out of scope (needs a solver).

### Relative points — OP-4 case (b), as built

The demand arrived as a drawing: a circle built centre-and-point, whose **centre rides a segment** and whose
rim passes through a **free point**. Dragging the segment moved the centre and *changed the radius*, because
the rim point stayed where it was. The user's intent was that the rim point belongs to the centre — and
stating that intent is a **re-parameterization, not a constraint**: `P = polarPoint(anchor, d, θ)`, with `d`
and `θ` read off the geometry the point already has.

- **Two DOF before, two after, and nothing moves at the moment of the change.** That is the whole test of
  case (b): the point is not pinned, it is *described differently*. `d` and `θ` are ordinary free scalar
  sources, so the point is still dragged (the drag inverts the offset from the cursor) and still typed —
  now as a **distance and an angle**, which is how a two-point circle finally gains "type the radius"
  (OP-13, at no cost: a handle's fields are its drag).
- **Polar rather than a vector offset**, because the two numbers a user wants for a rim point are its
  radius and its bearing; a `dx/dy` pair would have made the radius unreachable again.
- **The substrate is the bind-in-place one welding uses** (`SourceNode.boundTo`, OP-5): the free point's
  node is bound onto the `polarPoint` node, so *everything already referring to that point follows* — the
  circle here — with no input list rewired and no element replaced. This is the third feature that
  binding-rather-than-sharing has paid for (weld, ortho legs, this).
- **Invertible, and that is what makes it a conversion.** *Make absolute* unbinds and writes the point's
  current world position back into its literal — the point does not move, and the drawing behaves as it
  did before. One affordance covers every way a point can have lost its coordinates (relative, welded,
  attached), because they are all the same rewiring seen from the user's side.
- **Two tools, not one that guesses** (`ToolCategory.POINTS`): *Make relative* takes two points, *Make
  absolute* takes one. A tool's slots are its promise about what it takes, so a single tool that meant
  different things depending on what the first click hit would have needed controller code to say so.
- **Cycles are refused by the predicate every other connection uses** (`joinWouldCycle`): anchoring a point
  to something that already follows it would put it inside its own input cone, which is OP-4's acyclicity
  applied to a re-parameterization. Refused *with a reason*, and a refusal records no step.
- **Persistence needed no new mechanism.** The offset is state (dragged, typed), so it rides the same
  **`dofs=`** seam a dimension's placement uses: `tool makerel els=e5,e4 clicks=… dofs=25.3mm;-118deg`,
  restated from the live nodes on every save and taken verbatim on replay — hence exact, byte-stable
  round trips and no drift. (`Document.makeRelative` called directly records the same thing as a
  `relative` step, for the same reason `weld` has one.)
- **One general seam fell out of it**, worth naming because the next such tool needs nothing: a tool that
  only *rewires* changes nothing the canvas can show, so a silent success is indistinguishable from a
  silent refusal. The document now carries a one-shot **`note`** which the controller reads after any tool
  completes — no case per tool. The same absence had a second symptom, fixed here: a tool whose build
  created no element had its journal step dropped as "empty", so the **Join points tool's weld was lost on
  save** although the identical weld by drag was kept. An in-place rewiring now counts as an effect.

### Relative on a shared carrier — the same conversion for a rider (as built)

The polar form above is the right answer for a **free** point: two degrees of freedom before, two after. A
**rider** has one, and it belongs to its carrier — so when *both* picks of *Make relative* are positions on one
carrier (the point rides a line-like host; the base is another rider on that host or a point the host is built
from), the offset the user means is a signed distance **along the carrier**. The tool therefore *specializes*
rather than guessing: same slots, same clicks, the more specific reading when the picks allow it, and the
status line says which form was chosen. One DOF before, one after; nothing moves at the moment of the change.

- **The binding sits in the rider's own parameter, not in its point** (OP-5 bind-in-place, one level down):
  `t.boundTo = add(lineParam(carrier, base), d)`, i.e. the position along the carrier is now *base's position
  along it plus `d`*. Since `along(carrier, t) = proj(base) + dir·d`, that **is** the
  `pointAlongLine(carrier, base, d)` form — expressed so that the rider's point, its element, its handle
  target and everything referring to it stay exactly as they were, and so that the creating step keeps
  restating **one** node whose value still means "where along the carrier" (`Document.riderParam`). A
  point-level rebinding would have orphaned the parameter the `pointoncurve` step restates.
- **Two forms, both covered.** A rider on a diagonal host stores `world · dir`, one on a host axis-aligned by
  construction stores a world coordinate (OP-20); the offset is added to whichever of the two, through
  `lineParam` or `measureX/Y` of the base. The handle (`CarrierOffsetHandle`) writes `d = wanted − base`, which
  is exact, and its one field is a **distance**.
- **Chains are ordinary** — a rider measured from an end of its carrier, the next measured from that rider — and
  a dimension chain is what they read as. The reverse is refused by the acyclicity predicate every connection
  uses (OP-4): a base measured from its own dependent would put the rider inside its own input cone. A rider
  that already has a base is not silently re-anchored either — *Make absolute* first, exactly as the polar form
  insists.
- **Invertible, and that is again what makes it a conversion.** *Make absolute* on such a rider unbinds the
  parameter and writes its current effective value back, so the rider keeps its place and is world-anchored
  again (for a rider the *tool* created that is the only freedom it has, there being no literal of its own to
  hand back; a free point that was attached and then re-anchored is freed outright as before).
- **Persistence needed nothing new**: the distance is state, so it rides the same `dofs=` seam, and the
  creating step restates the absolute position along the carrier — so `save → load → save` is byte-equal and
  the reloaded drawing behaves identically under the same edit. One correction fell out of it: a step's `dofs=`
  is now written for the re-parameterizations **that step performed**, not for every relative point it
  *references*, or a circle through a relative point would carry that point's distance and angle as its own.

### Freeing a rider — the same conversion, and the view that makes it possible (as built, on a user report)

*Make absolute* worked on a point that had been **dragged** onto a curve and **refused** the identical point
made by the snap or by the point-on-line / point-on-circle tools. The two are indistinguishable on the canvas —
one degree of freedom along a host, the same handle, the same panel row — and the difference was entirely in how
the element was *published*: an attached point still published its own `SourceNode` (bound onto the on-curve
node), so unbinding handed its coordinates back, while a tool-created rider's element published the **derived**
on-curve node itself. There was no literal to hand back, and no way to give it one: OP-5 forbids rewriting a
consumer's input list, so re-pointing the point at a free source would have meant rebuilding everything built on
it. The refusal even said something true ("derived by the construction") about a point the user had just placed
by clicking, which is why it read as a bug rather than as a rule.

**The substrate already existed, one dimension up.** OP-16's `IndirectNode` — the re-pointable view an ortho
vertex is published through, so a placement can insert `frameApply` in front of it without rewiring a consumer —
is exactly this problem: *a derived value that must be able to become another derived value in place*. Every
rider is now published through one (`Document.addRider`, the single seam every rider-creation route already went
through), and freeing it is a **re-point**: the view stops naming the on-curve node and names a fresh
`SourceNode` holding the position the point has at that instant.

- **Nothing moves at the moment of the change**, and everything built on the rider keeps working and follows the
  *point* from then on — a perpendicular through it, a fillet on that, an arrayed copy of the lot. That is the
  property the reported wheel drawing is the regression for (`RiderDetachTest`): its `e18` rides a perpendicular
  bisector and carries a six-fold array of a whole figure, and freeing it moves not one of the drawing's points.
- **Old files gain it on load**, with no migration and no format change: replay runs the same creation code, so a
  rider saved by any earlier build comes back publishing a view.
- **One progression, three steps**, all through the one affordance: a rider *measured from a base of its carrier*
  → *riding the world* → *free of the curve*. Each step is invertible, each is a re-parameterization that moves
  nothing (OP-4 case b), and the second one is what the earlier slice built.
- **The freed point is a free point in every sense**, not merely one that draws in the right place: it welds,
  attaches, is made relative, is captured by a group's frame and reports its coordinates as its handle fields.
  That uniformity is one question asked in one place — *does this element own its coordinates?* — over both
  shapes a point's node can have (`Document.literalNode`), and it replaced a dozen casts to `SourceNode`.
- **The freed position is state, so the step restates it** (OP-18): `absolute e4 dofs=70mm;55mm`. It has to be,
  because the point can be dragged anywhere afterwards, and the rider's own parameter no longer describes where
  it is. The step is the ordinary `dofs=` seam every other re-parameterization uses, and it goes through
  `restatedPosition`, so a later placement capture does not make the step describe post-capture geometry.
- **What still refuses, and why:** an ortho path's **corner**. Its coordinates are *shared* with its neighbours
  (that sharing is what keeps a leg axis-aligned, OP-19) and a meeting point's freedom belongs to its junction
  (OP-20) — so a corner freed of its path would be a path that is not rectilinear, which is not a point's
  decision to make. The refusal there names the construction, which is now the truth.

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
| dimmed / hidden | presentation | per-element flag — **recorded** as a `hide`/`show` step (see the reversal under OP-18) |
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

#### The tracer reports, and follows (as built — user reports)

Two complaints about the same tool: *nothing says where I am while picking*, and *I have to click every
piece of a corner I already constructed*.

**Feedback.** Picked pieces are drawn in a colour of their own (`Styles.PICKED`, through the one *emphasis*
path the selection also uses — a pick is not a selection, so it must not read as one), a click that hits
nothing says *"that click hit no curve — N picked so far"* instead of leaving the old count standing, and the
count is on the status line after every pick. The silent miss was the worst of the three possible answers: the
drawing does not change either way, so nothing at all distinguished "that curve is in" from "that click
landed in space".

**Boundary-follow.** Two picks fix a direction; from there the tool appends every piece whose continuation is
**unique**, and closes the outline when it comes back to the first piece. The user's own showcase — a triangle
with two chamfered corners and one filleted corner, six boundary pieces — closes in **two clicks**, and the
rounded rectangle in W4's budget went from nine actions to two.

The load-bearing part is what it reads and what it records:

- **A joint registry, not a search.** A fillet or chamfer *states* where it hands over: it registers its two
  tangencies (or bevel ends) as `Joint(a, b, at)`, where `at` is a **node** — `projectToLine` / `radialPoint`
  / `arcStart` — so the joint keeps following the parameters. This generalizes `sharedEndBetween` from "do
  these two touch?" to "**which pieces continue here?**" (`Document.continuationsFrom`), over the same two
  sources of truth: registered joints first, coincident endpoints second. An *intersection* is deliberately
  not one of them — two curves crossing is not a statement that a boundary turns there.
- **A cut corner is recorded as cut.** A chamfered triangle vertex is the case that forces this: the two legs
  still meet there, but the boundary goes round the bevel instead, so the rounding registers a `Supersession`
  of that corner. Position by position, not pair by pair — two curves can meet twice (a chord and its arc)
  and a rounding replaces only the corner it sits in, which needs no tolerance to identify: it is the meeting
  nearest the rounding.
- **Nothing is discovered on replay.** The followed pieces are appended to the *pick list*, so the recorded
  step carries the full ordered boundary and replay re-runs the same `ToolDef.build` over the same list. OP-14's
  rejection of *"the loop's identity would be discovered rather than constructed"* is untouched, because the
  follow lives strictly in the **gesture**: it saves clicks, and it is the clicks that are stored. (A followed
  piece therefore needs a click position too — a point on it between its two joints, so an arc's branch is
  read off it exactly as a clicked one is.)
- **Stop conditions, every one reported**: a dead end, a fork (two or more continuations — a genuine choice,
  so the user makes it), a piece already in the chain, a whole circle (which of its two arcs is meant is a
  choice, OP-1), and a pair with no constructed joint at all. The status line narrates what was followed and
  why it stopped.
- **The cut, stated:** auto-close means a boundary that *is* forced can no longer be abandoned half-traced in
  favour of a different closing piece — the escape is Escape, or one undo. Worth it, because a forced
  continuation is by definition not a decision the user was making.

**Deliberately not done here:** containment is not verified (a hole outside the outer boundary, or
two overlapping holes, are accepted; only holes removing more than the boundary encloses are
rejected) — real containment needs the point-in-region predicate, which this slice does not need.

*Corrected later, on a probe (session 3).* That rejection originally lived **only in `regionArea`**, so a
`Region` value that encloses nothing was perfectly valid until somebody asked for its area — and a caller
that never does (an extrude) met it as a triangulation failure instead, which reports a symptom rather than
the fault. The check now also sits in `region(...)` itself, where the claim above is made, and heals (OP-3);
`regionArea` keeps its own copy because regions also arrive from `thickFootprint`, `intervalFootprint` and
`sectionAt` without passing through `region(...)`. The **limit is unchanged and is now asserted** rather than
merely stated (`RegionTest.aHoleReachingOutsideTheBoundaryIsAcceptedBecauseContainmentIsNotVerified`): this is
a *degeneracy* check, not a *containment* check, so a hole poking out through the boundary while remaining
smaller than it is still accepted, with an area that is arithmetically right and geometrically meaningless.
Consequence worth stating plainly, because it is the general lesson: a construction able to produce such a
shape must **state its own domain** — the type below it cannot.
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
  (**Built** — see *Implementation status (as built — ortho paths and walls under a frame)*.)

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

**As built — the drag subject is the selection the press found (a user report).** Pressing a member of a
group *nobody had selected* and dragging moved the whole frame, which is unexpected because **grouping is
invisible until something of it is selected**: the drawing gives no cue that the thing under the cursor
belongs to anything. So the rule is now split across the two ends of the gesture, and each end decides
exactly what only it can know:

- **press time decides the drag's subject**, from the selection the press *finds*: the frame only when
  the group is already selected **as a whole** (`selectedGroup === group`), otherwise the member's own
  handle. Decided there and never re-decided, because a release cannot undo what the drag already moved.
- **release time decides the selection**: a click runs the cycle above; a drag leaves *the element it
  moved* selected — the least surprising answer, and the same one dragging ungrouped geometry gives.

So the three cases read as one sentence: a click selects the group, a drag on an unselected group's member
edits that member as if it were ungrouped (magnet and axis lock included, unchanged), and a drag *after*
that click moves the frame. Flat groups are untouched: with no frame there is nothing to disambiguate, so
the press selects the group as it always did and the drag is element-wise as it always was.

**The frame marker follows visibility, not only selection.** Hiding a placed group left its origin marker
drawn — geometry-looking scaffolding for a group that has nothing on screen. A frame is drawn only while
some member is **selected and visible**, which covers both routes into it (the group panel's toggle and
Hide on the selection) with one condition and no new state.

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
not an element, which buys a gesture and no capability. Placing is refused when a group owns no freedom
at all: a frame with nothing to carry is three degrees of freedom that move nothing.

> **Superseded (ortho paths).** This note used to record a cut — "ortho paths and walls are not
> captured", because their positions live in shared *scalar* coordinate nodes. **That cut is closed**;
> the next section is the work it pointed at, and with it the *ortho-path bonus* above is delivered.

**A group carries the freedom it is built on — of every kind (as built).** The reported drawing was a segment,
a **rider** on it carrying a circle's centre, and the circle's rim point made **relative** to that centre. It
could be grouped and never placed, and two independent halves of the same blind spot were why: the closure
analysis and the capture both understood *plain two-coordinate free points only*. So the dialog could not offer
the rider or the offset, they stayed outside the group, and the free points the group *did* want to capture were
then "also used by" them — the conflict refusal, arriving as a wall with no way through it.

`Document.freedoms(members)` now answers the closure question for **every** kind of degree of freedom the engine
has, and each kind has one honest answer about placement:

| freedom | how a placement carries it | why |
|---|---|---|
| **free point** (2 DOF) | captured: bound onto `frameApply(frame, local)` | as before — the coordinates become the group's own |
| **ortho path** (shared coordinate nodes) | captured whole, coordinates translated | as before (the *ortho-path bonus*) — a path is one unit of freedom |
| **rider on a member carrier** (1 DOF along a curve) | **re-anchored** to a point of that carrier (OP-4 case b) | its parameter is anchored to the **world** (a coordinate, or `world·dir`), so a frame that moves the carrier would strand or slide it; a stated distance from the carrier's own end is rigid under translation *and* rotation |
| **relative point** (polar offset, anchor inside the group) | nothing — already rigid (see the angle limit below) | the offset is measured from member geometry, so it follows whatever the anchor does |
| **ratio point** (a share of a span) | nothing — already rigid | dimensionless: it says nothing about the world, so it survives rotation too |
| **rider on a member circle** (angle about the centre) | nothing — already rigid (same limit) | centre-relative; a circle has no ends to stretch |
| a rider whose **carrier is not a member**, an offset whose **anchor is outside** | not carried, **named** | it follows something the frame does not move: the boundary-attachment rule, reported |

Four things worth recording about that.

- **The re-anchoring is a conversion, not a special case**: placement calls the very same operation *Make
  relative* performs on a rider (`anchorRiderTo`), so it is DOF-preserving, world-invariant at the moment of
  capture, invertible by unplacing (the rider gets its absolute parameter back where it stands), and it makes
  the rider drop out of the compensation registry exactly as a user-stated anchor does. **This is the
  stated-anchor principle closing the circle: explicit relative anchoring is what makes a group rigid.**
  - **Its offset is read before *any* binding**, which is the rule the free-point capture already stated ("every
    world position is read before the retrofit proceeds") and which a rider needs even more: its parameter is a
    *world* quantity, so an offset derived after the points are bound would be derived against **turned**
    geometry. Since only a replay places at a nonzero angle, that would make the gesture and the replay of it
    capture two different figures — a broken round trip on exactly the drawing that motivated the feature.
- **The base is *stated*, and that trades one property for another, deliberately.** Inside a placed group the
  rider is measured from an end of its carrier, so dragging that end along the line now carries the rider —
  which is precisely what OP-20's absolute anchoring avoided for a *world* rider. Both are right in their
  place: absolute while the host merely carries the thing, relative once the group is the thing being moved
  (the same distinction OP-17's face-relative bore records from the other side).
- **The default tick flipped for groups** (OP-16's "one dialog, two defaults" — the defaults are now the same,
  read two ways). A group used to start with *nothing* ticked, which made the everyday case fail late: the
  freedom the figure is built on stayed outside, and Place refused it much later. The closure is ticked by
  default, so a naive group is **movable**; unticking is still there for the point that genuinely belongs
  elsewhere, and each row is labelled by kind ("e4 — slides on e3", "e5 — relative to e4"). Tool mode is
  unchanged.
- **The honest failure moved to the gesture that causes it.** What a group cannot carry is invisible on canvas,
  so `Document.placementWarnings` is asked at **creation** time too and reports, in the words Place would use,
  which members are held by something outside the group and what holds them. Placement itself is unchanged
  where it was right: a conflict is still refused with the ambiguity named, a partly-driven group is still
  placed *with* the deformation reported, and the "owns no freedom at all" refusal now counts every kind.

**Two limits, named rather than found later**, both about **angles stated in the world's axes** — and both
asserted by tests rather than assumed:

- **A polar offset's bearing does not turn with the frame** (nor does an on-circle angle). The re-anchored
  rider *does*, because a distance along a carrier turns with the carrier; but `polarPoint(anchor, d, θ)` has θ
  in world axes, so under a turned frame the rim point keeps its direction from the centre while everything else
  turns. What that costs is one **marker** point, not the figure: the circle through it is unchanged, since its
  radius is that same `d`. The cheap fix is the identical bind-in-place trick one level down — bind θ onto
  `frameAngle(frame) + θ_local` — and it is parked rather than half-built, because it needs a frame-angle
  accessor and an answer to what the *number* in the panel then means (a bearing in the group, or in the world).
- **A rider on a host axis-aligned by construction** keeps a world **axis** in its parameter, so re-anchoring
  makes it rigid under translation but a turned frame still slides it along its leg (an axis line does not
  turn). Walls live there and a turned group of walls turns through the *path* capture instead, so nothing
  reachable today hits it; the honest fix is to impose the along-line form at capture.

### Implementation status (as built — ortho paths and walls under a frame)

The cut above was the one thing a frame could not carry, and it was the thing architectural drawing most
needed to move: a run of walls. Closing it delivers the **rotated project frame** the architectural layer
sketched — a building sited at an angle, drawn orthogonally in its own frame.

**Where the frame goes in, and why it cannot go anywhere else.** A vertex's freedom is two scalar
coordinate nodes, and a leg is axis-aligned because one endpoint's coordinate is `boundTo` the other's
(OP-19). A turned frame mixes x into y, so `world = f(frame, lx, ly)` is *not* expressible as a per-axis
binding on a scalar — there is no scalar for a rotated world x to bind to. What every consumer reads is
the **vertex's point**, so that is where the capture belongs: each vertex is now `pointXY(x, y)` published
through an `IndirectNode` (see the note under OP-5), and capturing binds that node onto
`frameApply(frame, pointXY)`. Legs, the wall footprint riding them, openings, sections, solids — all of it
follows without one input list being rewired (OP-5), exactly as a captured free point's consumers do.

**The coordinates are not converted — they are re-read as local.** The binding structure is untouched:
who follows whom still holds, and it now relates *local* coordinates. That is the whole feature —
axis-alignment becomes alignment to the **frame's** axes, so turning the frame 30° leaves every leg
straight and perpendicular *in the group* and tilts the drawing 30° in the world. The only write is one
translation per **master** coordinate (once per master, not per vertex: the vertices of a straight run
resolve to the same node, and writing it once is what keeps them straight).

**One capture rule for both kinds: a capture changes the origin, never the orientation.** It has to for a
path — reading its coordinates in a turned frame *turns* it, by construction. Free points were being
captured through the full frame inverse, which would have left a mixed group half-turned by a placement at
an angle; they now follow the same origin-only rule, so a group is rigid whatever it contains. Since the
gesture always places at angle 0, the retrofit is exactly world-invariant where the user can see it, and
rotation is a later edit on the frame. (The uniform rule also fixed a latent save defect: a rotated free
point's world position did not survive `save → load → save` bit-for-bit, because the round trip went
through two rotations.)

**Unplacing gives back exactly what placing took.** For an unturned frame that is still world-invariant.
For a *turned* one it cannot be — only a frame can hold an axis-aligned leg at an angle — so the group
comes back unturned rather than being torn into the parts that could stay turned and the parts that could
not, and the status line says so.

**Interaction (OP-13) is the same discipline, in local space.** A corner or leg drag inverse-maps the
cursor through the frame and then writes the very same masters as before, so grab offsets and axis lock
(both world-space, both rigid) carry over unchanged, and hit-testing is untouched because it runs on
evaluated geometry — a leg is picked where it is *drawn*, rotation included. Typed fields keep showing
**world** x/y for a corner and write through the inverse; under a turned frame a world x depends on both
local coordinates, so that write lands on both masters — precisely the pair the drag writes. A *leg's*
offset has no world counterpart at all (a leg of a turned group is neither horizontal nor vertical), so it
is shown as `y in group` rather than quietly meaning something else.

**Break and join keep working (OP-19), in local space.** A break maps its click into the frame and
publishes the two vertices it creates through the frame as well; a join re-points bindings and needs
nothing new — except that the perpendicular value it lands on is read from the *node* rather than off the
drawn segment, which is right in both spaces and was a world/local mismatch waiting to happen.

**Junctions (OP-20) are the boundary, and the boundary is honest.** A junction's position is a *world*
position, so a captured vertex may not be driven by one: the coordinate would be read as local. Two
consequences, both stated in the app. A path whose freedom leaves it at a junction — an end welded or
attached to anything — is **not captured** (a path is one unit of freedom: its coordinate nodes are shared
along each run, so half a capture would bend it where nothing moved), and its members are reported as not
following the frame, which is OP-16's boundary-attachment rule one granularity up. And an end of an
already-placed path **refuses** to weld or attach outward: the magnet does not offer it, so no halo
promises a join the release would refuse. The other direction is fine and is how a run reaches a placed
wall — something outside joins *onto* a placed corner, reading its world position.

**Cuts, named.** *Drawing onto a placed path* is refused: a new leg would be snapped to the world axes
while the path holds local ones. Clicking a placed end therefore starts a new run **joined** to it (what
clicking an already-connected end has always done), and says so. And the vertices a break creates inside
a placed group are not added to the group's member list — a `group` step's argument list is never
rewritten (OP-16 step 1), so the file could not name them; they are captured and drag correctly, but
clicking one reaches the element rather than the group.

**Persistence (OP-18): one convention, applied per step position.** A captured source's own step replays
*before* the `place` step that captures it, so it restates the position it had **before** the capture —
its local value plus the frame's origin, which the capture then subtracts off again. For a path that is
the only restatement that can work at all: the world positions of a turned path are not axis-aligned, and
the drawing steps snap every leg to an axis, so they could not rebuild it. A step recorded *after* the
placement (a break inside a placed group) runs on already-captured geometry and maps its own positions
into the frame, so there the world position is what is written. The file still holds no local coordinates
and no node names — only positions its own steps can be replayed from, and `save → load → save` is
byte-equal after placing, a frame drag, a corner drag, a rotation and a break.

### Implementation status (as built — a whole group as a tool *operand*)

A user report closed the last gap between "a group is a thing" and "a group is a thing you can *build with*":
*"I cannot create a circular array from a group as input."* Grouping bought select-together, naming, bulk
visibility and a frame — but every tool still took one element per slot, so the answer to "array this room"
was to array eight walls eight times.

**The rule is the one the drag subject already established, applied to a pick.** *A group acts as a whole
only when selected as a whole.* So a click into a `GEOMETRY` slot means the **group** when `selectedGroup`
names the group the click landed in, and means that element alone otherwise — a member deliberately reached
alone, or a group nobody has selected. That is not a second convention: it is the same sentence the press-time
drag rule says, and it holds for the same reason — grouping is invisible until something of it is selected, so
a click must never copy more than the user can see. Both readings stay reachable with no modifier and no mode.

**Opting in is a `ToolDef` property, because accepting a group is a promise about `build`.** `groupOperand =
true` on the two array tools, and `linearArray`/`circularArray` take a `List<Element>` where they took one —
*one element is the list of one*, exactly as the scalar-input list generalized a single active parameter. The
flag is not inferred from the slot kind for a concrete reason: a tool with a **second** element slot (Mirror's
axis) indexes `Picks.elements` positionally, and a multi-element geometry slot would silently break it. So the
table says which tools fan, and the array tools' step vector nodes are created **once** and shared by every
member's copies — sharing a node is equality (OP-5), so one drag of the vector re-spaces the whole array.

**The panel is the second route into the same slot** (OP-13: the panel is as much an input as the canvas). A
click on a group's row feeds a waiting geometry slot and otherwise selects, as it always did — one entry point
(`Editor.clickGroup`), so the shell only routes and the decision (which tool is armed, how many slots are
filled) stays where it can be tested. Naming *is* the pick there, so it needs no prior selection; and the
selection is deliberately left alone, because a pick is not a selection — the members get the *picked* mark
every other half-finished operation gets. The slot has no click of its own, so the group's **bounding-box
centre** is recorded for it: the click list is positional (a fillet reads `clicks[0]`/`clicks[1]`), so a hole
in it would be a trap, and the group's centre is the one position that is not a fabrication.

**Persistence: the file names the members, and never the group.** `tool arraycircular pts=e3 els=e1,e2
count=3` — the geometry slot's picks, which are now several. A `group=` argument was considered and rejected:
the group is a fact about the **gesture**, not about the construction, and three things fall out of leaving it
out. Ungrouping afterwards cannot orphan an array. The delete cascade needs no new rule — the step references
the members, so deleting one takes the array with it, and the group is left consistent by the rule it already
had. And nothing has to guarantee that a `group` step precedes the arrays that used it. `save → load → save`
is byte-equal, which is the whole test.

**The count means what it always meant**, and that is worth stating because a group makes it ambiguous-looking:
*N instances including the original*, so N = 3 over a two-member group is **4** new copies. The one thing a
group changes is the bound: `members × (count-1)` copies, capped by the same `MAX_COUNT` that protects a single
element from a mistyped count, and **refused with the numbers** rather than clamped — a different number of
copies is a different construction (OP-18), so quietly building fewer would be answering a question nobody
asked.

**Placed groups: it just works, and the probe says why.** A copy is a transform node over the member's
*published* point, which a placement has already bound onto `frameApply(frame, local)` — so the copies are
downstream of the frame and a frame drag moves them with everything else. Nothing in the array knows a frame
exists (OP-5 again). The **other order is refused, by the rule that was already there**: array first and the
copies are non-members depending on a free point the group owns, which is exactly `analysePlacement`'s
"owned, shared, or outside" conflict — so placing says which elements are in the way. The honest sequence is
*place, then array*, and both directions are pinned in `GroupArrayTest`.

**Cuts, named.** The copies land **ungrouped** — a deliberate first cut. Grouping each copy is the obvious next
step and it is not free: a group is recorded membership, so *k* copies would mean *k* new `group` steps whose
names have to be generated, deleted and undone as a unit, and a copy's group would want to be a *linked* copy
of the original's rather than an independent one (edit the original room, and does the fifth copy's membership
follow?). Recorded as follow-up rather than guessed at. The status line says the copies are not grouped, so
nothing has to be discovered by clicking one. And only the two array tools opt in: Mirror, Rotate, Scale and
Translate could fan the same way once their positional `elements` indexing is addressed, which is a change to
those builds, not to this mechanism.

### Implementation status (as built — one pick cycle, and a group that is framed by default)

Three user items that turned out to be one **selection state machine**, plus the defect the machine exposed.

**A group is framed by default, and *flat* is a purpose rather than a fallback.** The create dialog's
`movable (with frame)` tick is on, so confirming runs `createGroup` **and** `placeGroup` under **one**
checkpoint: giving a part its frame is not a second thing the user did, and one undo removes both. The reason
the tick is not simply implied is the other reading, which a *user* found: a **flat group is the natural array
original**, because the copies an array makes of it are transform nodes over the members' published points and
therefore derive frame-free — array a *placed* group and the copies are downstream of the frame instead. So the
dialog words both as intents ("a movable part…" / "a named set: no frame, e.g. an array original…"), never as
success and shortfall. The creation-time placement warnings are unchanged, and a placement that is **refused**
outright (a group owning no freedom at all, or a shared point) leaves the group standing, flat, with the reason
Place would have given — arriving now at the gesture that caused it rather than at a button much later.

**One pick cycle replaces two hand-built two-element cycles.** The group/member reach (OP-16) and jamb-vs-leg
(OP-21) had grown separately, each remembering its own state, and each reaching exactly two things. A SELECT
click now collects **every** candidate within the pick tolerance, ranks them, and selects the first; clicking
the same spot again steps to the next, wrapping (`segment e12 (3 of 5 here — click again for the next)`).
Five things are worth recording about it.

- **The ranking is today's precedence, written down once.** Points first — the user's rationale, and it is why
  precedence beats distance here: *a point cannot dodge, a curve can be clicked elsewhere.* Then draggable
  curves, then a draggable annotation (last, so a dimension over the geometry it names never steals its grab),
  then the **jamb**, which still competes with the curves *by distance* (along the wall the leg is nearer,
  across it the jamb is), then everything else selectable, nearest first. The jamb rule stays the **ranking**;
  what cycling adds is that the loser is reachable. A grouped hit contributes **two consecutive entries** —
  the whole group, then that member alone — so the old reach is now two entries of the general list and its
  order is unchanged.
- **The first-click invariant is the acceptance bar**, and it is structural rather than tested-for: a press
  with no cycle standing selects `candidates.first()`, which *is* what the press has always selected. Every
  existing selection and gesture test passes unedited, including the ones that click the same member three
  times — because the group entry repeats before each member, `click, click, click` still reads *group, member,
  group*. A click far from the previous one, or one whose selection no longer stands, is a first click again.
- **The repeat threshold is `REPEAT_CLICK_PX`, defined as `CLICK_SLOP_PX`** — deliberately the same notion,
  one named constant: within it the pointer has not travelled, so "click again" means what it says and
  click-vs-drag can never drift apart from repeat-vs-new.
- **Applied on release, and only when the gesture did not move** — the discipline the group cycle already
  used, and for the reason it was invented: deciding a drag's subject after re-picking made "click a member,
  then drag it" move the member instead of the group. A drag keeps the press-time subject rules and leaves no
  cycle behind. `Tab` is the keyboard twin (OP-13: nothing may be reachable one way and not the other) and is
  consumed only while a cycle is live, so it keeps its usual meaning otherwise.
- **Selection primes the drag**, which is what makes cycling worth having: a press that continues the cycle
  drags what the cycle selected, overriding the ranking — step to the curve under a point, then drag the
  *curve*. A **placed group selected as a whole** is this same rule and predates it (its frame branch is left
  where it is rather than duplicated), and when the primed selection cannot move the press says why and moves
  **nothing** — predictability over convenience; clicking elsewhere or Esc gives the ranking back. Only the
  *primary* primes: a multi-selection has no single drag subject, and a bulk drag is the frame's job.
  Consequently **selection rank and drag rank are two different rankings**, on purpose.

**A parameter pick can be switched off** (see *Named values in the panel*), which the cycle's own "never mind"
made conspicuous by contrast: the canvas had one and the panel did not.

**And the machine exposed a picking hole: a ray could not be clicked at all.** `HitTest.distanceToValue` had no
`RayValue` case, so every ray fell into the unpickable `else` — it drew, and a marquee took it (the rectangle
test *did* have the kind), but no click could select it, cycle to it, or feed it to a slot, so *Perpendicular*
refused a ray outright. One case in the one distance rule (a segment's clamp on the origin side only) brings
all of that back at once, which is the payoff of picking having a single seam. The audit behind it: the kinds
`SceneRenderer` draws and the kinds `distanceTo` measures must agree, and the one drawn kind deliberately left
out is a `PointSetValue` — an ordered solution set is scaffolding for the `Select` beside it (OP-1), and it is
that selected point, an element of its own, a click is meant to reach.

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
`Tier 5` ops in `dsl/Construction.kt`, `SolidTest`). All three slices are reachable end to end; the
counterbore that completes slice 1 followed with the booleans (OP-22, *Exact prismatic booleans*) and
`PrismBooleanTest` builds it. At the time this was written there was no UI; the tools and the viewport
followed in the next slice (below), so nothing here had to be revisited.

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
  - **Booleans** — since resolved and built for the same-axis prismatic case, which is what the
    counterbore needed: see **OP-22**. General booleans still wait for Manifold (OP-9), and a
    non-prismatic operand is refused with a reason that says so.
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

### Implementation status (as built — the seam's tools and the viewport)

The seam became **reachable**: two tools cross it, and a second viewport shows what came out. The
viewport's own architecture is recorded under *The 3D viewport (as built)* in the editor section; this
note is about the seam.

- **Two `ToolDef`s, no controller code.** `Extrude` = an `AREA` slot + a `depth` scalar; `Revolve` =
  an `AREA` slot + a `LINE` slot (the axis) + an `angle` scalar. Adding them needed one new `SlotKind`
  (`AREA`), one new `ToolCategory` (`SOLIDS`), one new `ElementKind` (`SOLID`) and two `Document`
  methods — the data-driven tool table did the rest, and the generic `tool` step (OP-18) gave
  save/load/undo/delete for free (`SolidToolTest` asserts save→load→save byte-equality with both
  features present).
- **The `AREA` slot takes either kind of area.** A thick path's footprint already *is* a `Region`; a
  traced `Outline` is a single `Loop`, coerced by the ordinary `region(...)` op. That coercion creates a
  node but **no element**, so the tool step still accounts for exactly one creation and the loader's
  element-count check still vouches for the replay.
- **The revolve axis is a picked line, kept parametric.** `lineOrigin(line)` + `lineDirection(line)`
  are new provenance accessors (OP-8), so the axis is derived nodes rather than captured numbers: drag
  the centreline and the turned part follows. A profile crossing its axis stays invalid *with a reason*
  and heals (OP-3) — `Geom3.revolve`'s rule, unchanged, now visible in the UI.
- **The sketch plane is the world XY plane, and only that** (the decision to record). A 2D drawing *is*
  the plan, so that is where its regions live; asking the user to choose a plane before there is any way
  to *make* one would be datum management with no datums. Choosing a plane arrives with sketch-on-face,
  which is exactly what `facePlane` already exists for. Consequence: the 2D footprint hint and the 2D
  pick geometry read the sketch's own coordinates directly, which is exact only while the plane is XY —
  when a plane can be chosen, both become a projection through it.
- **The feature's DOF is a panel parameter, not a 3D drag handle** (OP-13 satisfied through the
  parameter). With no picking in the 3D view there is nothing to grab there, and a 2D handle for a depth
  along the view normal would be a fiction. Editing the parameter recomputes the one solid node; a depth
  of zero refuses the feature and heals when it is raised again.
- **A solid draws a light footprint hint in the 2D canvas** — the boundary of the sketch it came from,
  not a projection of its mesh (a shaded or hidden-line view is a *chosen* projection, which is the 3D
  view's job). The hint is what makes the solid pickable, hence selectable and deletable, in the one
  view that has picking.
- **Deletion runs the normal cone.** Deleting the area drops the solid with it (the tool step names the
  area as an argument); deleting the solid leaves the drawing — the dependency runs one way. Undo
  restores the whole cone, because undo is the saved script (OP-18).
- **Cuts in this slice:** no 3D picking (above); no plane choice (above); no STL/3MF export yet (the
  mesh is ready for it — it is a file-format task, not a geometry one); the 2D toolset is inert while
  the 3D view is shown, and says so.

### Implementation status (as built — the seam downward: sections, sketch-on-face, 3D measurements)

The seam's **other direction** ships, which is the half OP-17 called the risky one: `section(solid, …) →
Region` and the face accessor reached by clicking. The 2D→3D→2D→3D loop is now a gesture sequence with no
code that knows about it — `SeamDownToolTest.aStoreyBuiltFromTheStoreyBelowIsOneLiveChain` is the
acceptance test: a drawn wall ring becomes storey 1, storey 1's *section* becomes 2D geometry again, and
that section extruded on storey 1's own **top face** becomes storey 2.

- **`sectionAt(solid, height) → Region` (`Geom3.sectionAt`, one op node).** For a prism this is **exact and
  not an approximation of anything**: a prism *is* a stack of areas over z-intervals (OP-22), so the
  section at a height **is** the slab there, corner for corner. A plain `Extrusion` is answered from its
  own **analytic sketch** rather than from its prismatic reading, so a cut through a bored plate keeps its
  exact circles — the tessellation a boolean forces is not needed here. The areas come back mapped through
  the sketch plane's own in-plane frame, i.e. in **world plan coordinates**, which is the identity for the
  XY plane and every plane derived from it by translation, and a genuine reflection for a flipped one.
- **The boundary rule, stated in the world: a cut landing on a slab interface shows the material
  *above* it.** Consequently a solid's **bottom** face is a section and its **top** face is not — a face is
  not a cross-section, and refusing is more useful than an empty area. Because the rule is stated in the
  world and not in the prism's own axis direction, it holds for a solid grown *downwards* from a flipped
  face plane too (whose local intervals ascend into −z), which is why the implementation has the two cases.
- **Refused with a reason, and healing (OP-3):** a **revolve** (an analytic revolve section is a real
  problem, and is cut from this slice); a prism whose **axis is not vertical** (a horizontal cut through it
  is not one of its slabs at all); a height **outside** the material; and a cut that falls into **several
  disjoint areas** — the last is *the type* refusing rather than the geometry failing, since a `Region` is
  one outer boundary with holes, so "the wall at floor level, which the door splits in two" has no
  single-region answer. The piece count is a **value**, so the node exists either way and heals when the
  geometry reconnects; nothing about it is structural.
- **Two new `ToolDef`s, no controller code.** *Extrude on face* = a `SOLID` slot + an `AREA` slot + a depth
  (`extrude(sketchOn(facePlane(base, TOP), region), depth)`); *Section* = a `SOLID` slot + a height, and it
  emits an ordinary `AREA` element, so the result draws in plan, is pickable, can be dimensioned and can be
  **extruded again** by the plain *Extrude* tool. Both ride the generic `tool` step, so save/load/undo/
  delete came free and the round trip is byte-equal.
- **The plan is drawn once.** *Extrude on face* needs no plane-choosing UI: the 2D canvas **is** the plan,
  so an upper storey or a boss is drawn in the same 2D space and the tool only says which solid it is
  stacked on. `facePlane` works through booleans (a `Prism`'s top is derived from its own slabs' extent), so
  a storey stacks on a **cut** wall and follows the wall's height parameter through the boolean — asserted.
  Only `TOP` is offered; the bottom face is a second tool, not built (nothing needs it yet).
- **A face-based extrusion's footprint hint is at its true plan outline**, so it is pickable where it is
  drawn — because `facePlane(TOP)` keeps the sketch plane's own `u`/`v` and moves only the origin along z,
  the sketch coordinates still *are* plan coordinates. That is the caveat recorded in the slice above,
  discharged for every plane the tools can produce. Still open (and now precise): a **flipped or rotated**
  sketch plane — reachable only from the DSL today — would need the hint and the 2D pick to apply the same
  in-plane map `sectionAt` applies, and a *vertical* sketch plane's honest plan projection is a line, which
  would make such a solid unpickable in plan. So the projection is not attempted rather than half-attempted.
- **3D measurements as tools (OP-4, forward): *Volume* and *Extent (X/Y/Z)*.** Each is a `SOLID` pick that
  creates no geometry and lands a read-only scalar in the panel, usable as any other tool's scalar input or
  as a wiring target — the papercraft flow, asserted: two measured extents become the two coordinates of a
  point, so a 2D net rectangle *is* the part's size and re-typing the part's parameters moves the drawing.
  **The axis is a stored discrete choice and the tool id is where it is stored** — three tools, exactly as
  there are three boolean tools for `BoolOp` and two for inner/outer tangents. The angular-dimension
  precedent (resolve the choice from the placing click) cannot apply: the choice includes **Z**, which no
  click in a plan view can name, and a tool step already records its id verbatim, so three ids need no new
  argument in the format for a choice that must replay identically (OP-18).
- **Acyclicity, checked rather than assumed.** Extruding a solid's *own section* onto its *own face* makes
  the new solid depend on the base **twice** — and that is not a cycle: acyclicity is about ancestry, and
  the base is an ancestor of both paths. No tool can create a cycle at all, because a tool only ever builds
  *new* op nodes whose inputs are fixed at construction; the only mutating connections are `boundTo`
  (welding, parameter wiring), and both already refuse a circular one. The honest refusal at this seam is
  therefore a **wiring** refusal, and it is asserted: a solid's measured extent cannot drive the depth
  parameter (or the wall thickness) the solid is built from — driving XOR driven, OP-4.
- **Cuts in this slice:** an analytic **section of a revolve** (refused with a reason); a **multi-piece**
  section (above); sectioning along a **plane other than horizontal** (a general `section(solid, plane)`
  wants a plane-valued slot, which wants datum-plane UI); the **bottom** face as a stacking target; and
  `project(edge, plane)` — the seam's third downward accessor, which nothing has needed yet.

### Implementation status (as built — sketch spaces, and a sketch on any planar face)

The seam's last missing half: **a 2D drawing can now live somewhere other than the plan.** A document has
named **sketch spaces**, one of them active; a space derived from a solid's *side* face makes vertical
planes nameable from the toolbar, and with them the whole class of features mechanical work reaches
immediately — the user's back-side drill, end to end, by clicking. `FaceSketchTest` is the record;
`FaceSketchTest.aDrillOnASideFaceLandsWhereTheFaceCoordinatesPutIt` is the acceptance test.

- **A space is organisation and view state, and the engine is untouched.** OP-17's decision — 2D stays
  abstract and a `SketchOn(plane, regions)` node does the embedding — is exactly what makes this cheap:
  a space contributes *one thing* to a construction, the plane its features sketch on, which is the
  argument `sketchOn` has always taken. `SketchSpace(name, plane, anchor, piece)` therefore adds no value
  type, no op and no evaluation rule; it is OP-14's third column (the organizational one) plus a camera.
  Every element records the space it was drawn in (`Element.space`, stamped by the one `Document.add`),
  the default being the **plan** (world XY) — so nothing about an existing drawing, or an existing file,
  changed. **One canvas shows one space**: `SceneRenderer` skips the others and `HitTest` will not
  address them, because the coordinates do not even mean the same thing in two spaces.
- **The face pick is one click, on a footprint edge** — the pick a plan-view editor can actually make. A
  vertical side face *projects to exactly one footprint edge*, so that edge names the face and the solid
  it belongs to at once, and clicking it twice (once for the solid, once for the edge) would be asking the
  user to repeat themselves. The identity is the **boundary-piece index** (OP-8): regions in order, outer
  loop then holes, pieces in loop order — a constructed accessor, `sideFacePlane(solid, piece)`, with
  `piece` a stored discrete choice exactly like a `Select` sign (OP-1). Nothing is re-identified from mesh
  topology, and the plane is recomputed from the feature's own parameters.
  - **Arming the tool returns to the plan view, and that is provable rather than convenient.** A footprint
    edge is drawn in the space its solid's plan is drawn in — and only a solid extruded **vertically** has a
    planar side face at all (`Geom3.sideFace` refuses every other axis), while a vertical axis means the
    sketch plane is horizontal, i.e. the plan. So the plan is the *only* space where this pick can ever
    succeed, and refusing the click where the user happens to be standing would refuse a pick that could not
    have worked there. It also closes a trap a review probe fell into: in a face view the tool found nothing,
    said so quietly, and the drawing that followed went into the *old* space — two bores on one face, looking
    like a second face that had not been created.
  - The honest caveat: a `Loop` is normalised counter-clockwise (OP-14), so a ring the user turns *inside
    out* — dragging a rectangle's corner past its opposite — comes back reversed and renames its own
    edges. That is the same order-of-traversal limit OP-20 records for a reversed host line, and it is
    unreachable for a footprint that keeps its handedness.
- **The frame, and the flip convention, stated once.** `Geom3.sideFace(feature, piece)` returns the face's
  plane with its normal pointing **out of the material** — the convention `facePlane` already uses for
  `TOP`/`BOTTOM` — with `u` along the picked edge and `v` = world **+Z**. The **sketch** plane is that
  plane *flipped*, so its normal points **into** the material, which is the direction a *Cut* sweeps (a plain
  *Extrude* builds a boss the other way — see *Two corrections from the same face*, below). The flip is `Plane3.flipped`, which mirrors `v` (a right-handed frame cannot flip
  its normal and keep its 2D coordinates — there is no third option), and that fixes the rest of the
  convention: the frame is anchored at the edge's start corner **at the face's top edge**, because only a
  top anchor leaves the face itself at `v ∈ 0..height` once `v` is mirrored. So in the space the user
  draws in, **`u` runs along the edge from its start and `v` runs down from the top face**, and the tool's
  help, the status line and the space's own note all say so.
  - Two consequences worth naming rather than discovering: the face view is a mirror of standing in front
    of the part (the canvas viewer sits on the +normal side, which is *inside* the material — unavoidable
    once the normal points in), and thickening the plate moves a hole dimensioned from its top face. The
    alternative frame (`u` reversed, `v` up) trades the first wart for the second one's mirror image; the
    axes were chosen the way OP-17 states them and the consequence is written down.
  - Refused with a reason and healing (OP-3): a **curved** boundary piece, whose swept face is a cylinder
    (a rounded rectangle's corner, a circle's whole side); a solid with **no prism form** (a revolve, a
    general boolean — its faces are emergent, OP-9); a solid whose axis is **not vertical**, where "v =
    world +Z" is not a direction in the face at all; and a solid of no height.
- **Face-RELATIVE positioning is the honest intent here, and that is the opposite of OP-20's rule.** The
  frame is derived, so the sketch's literal coordinates are *relative to the part*: stretch the plate and
  the bore stays 25 mm from the edge's start and 8 mm below the top face — it rides the face. OP-20
  concluded the reverse for a rider on a wall ("where a thing sits along its host is an absolute quantity,
  never a share of the host") and both are right, because the *intent* differs: a plan rider is placed
  where it is in the world and must not move when a wall on the other side of the room is dragged, whereas
  **a hole is dimensioned from the part's own edge** — that is what the drawing says and what the machinist
  does. The distinction is recorded here because the two rules look contradictory and are not: absolute
  when the host merely *carries* the thing, relative when the host is what the thing is *measured from*.
- **What the face view renders.** Its own space's elements in their own (u, v), plus one piece of reference
  context: the **rectangle the face covers** (width = the edge's length, height = the solid's z-extent),
  drawn at the grid's weight, so the user can see where the face *is* before drawing on it. That rectangle
  is also where a pick of the base solid lands — the one deliberate cross-space affordance, and the reason
  it exists is *Subtract*: the plate has no plan in these coordinates, but it does have this face, and
  "the solid this face belongs to" is exactly what the user means by clicking it. One rule, so what is
  visible is pickable: `Document.faceOutline` serves the renderer, the distance test and the marquee.
  A solid's footprint hint is drawn **in the space its sketch was drawn in**, which discharges the caveat
  the earlier slice recorded: a drill sketched on a face shows its circle in the face view, where it
  belongs, instead of being projected into a plan it has no honest projection into.
- **What the elements panel lists: the active space, plus the solids** (as built, on GitHub #2). Reported as
  *"the elements list contains the union of all elements of all sketches — the defining sketch is shown, but
  this will get messy fast. I think it is sufficient to only show elements that are defined on the current
  sketch: the 2D elements, and the 3D-defining outlines and resulting extrusions."* The rule, one line and its
  reason: **an element belongs to one sketch space, except a solid, which belongs to none** —
  `Document.listedIn` / `listedElements`.
  - The **2D** half needs no case analysis: an outline, an area, a construction line and a dimension all live
    in the space they were drawn in, so "the outlines that define a feature" arrive with everything else drawn
    there. One canvas shows one space, so what the panel lists is what the drawing on screen is made of.
  - A **solid** has no position in any space's coordinates and is shown in the 3D viewport, the same view
    whichever sketch space is active — so it is listed everywhere. Filtering solids by the space they happened
    to be extruded in would hide the part exactly where the next feature is being drawn, and a boolean's
    operand on a face has to be reachable there (`facePartTip`). That also collapses the `· space` suffix a
    row used to carry from "most rows, in every view" to "only a solid, and it means: look in 3D".
  - Deliberately *not* `addressableIn`, which is picking's question and admits only the part **tip**: what is
    listed is not the same as what a click can hit, and the panel exists precisely to reach an element a click
    cannot. Nor is the filter a lock — `Editor.selectElement` still selects anything, and now asks
    `listedIn` rather than "same space" before telling the user to switch spaces, since a solid needs no space
    to be seen in.
  - The query is the **document's**, and the browser shell renders whatever it returns: which elements a space
    owns is a fact about the model, not about the DOM. `ElementListSpaceTest` pins the partition — the lists of
    all spaces cover the document, each 2D element appearing in exactly one and each solid in every one — plus
    selection per space.
- **Features from a face space: one rule.** The *Extrude* and *Revolve* tools sketch on
  `Document.activePlane()` — the active space's plane, which for the plan is the world XY plane exactly as
  before. So no tool grew an argument, and "sketch on a face" is a *space* decision rather than a per-tool
  one. (One qualification, added later: *which way* a feature builds is the operation's, so a face-space
  *Extrude* offsets its start plane to build outward — the space's frame is untouched. See *Two corrections
  from the same face*.) *Extrude on face* stays as it was for the top-face stacking case (a storey, a boss): it names a
  solid instead of switching spaces, because that plan is drawn in the plan.
  - The drill's subtraction is **cross-axis**, so it takes the general engine (OP-9's `MeshBool`) — which
    has worked since session 5 but was unreachable from the toolbar, because there was no way to *name* a
    vertical plane. That gap, named in OP-9's own "what remains", is closed.
  - **`Cut`** is the same thing in one gesture: extrude on the face's plane, subtract from the part the
    face belongs to. Kept because the *space already says which part is being cut*, so asking for the pick
    again asks the user to repeat what choosing the face said; it is two existing document methods and
    creates two solids (the tool and the part), so nothing about the format, the delete cone or the 3D view
    needed a case for it.
- **The sequential-feature rule: a feature CHAINS onto the part, it does not fork it** (found by a review
  probe, and general). A second cut — on a second face, or on the same one — must subtract from **the part**,
  not from the plate the part started as. Anchoring the boolean to the space's original base forked the
  model: two coincident one-hole solids, each claiming to be the part, with the final volume short by
  exactly the first bore, and two shells z-fighting in the 3D view because their only *shared* material was
  the plate. The rule, in three parts:
  - **The operand is the current tip, resolved at tool time.** `Document.facePartTip()` is the most recent
    visible solid made *of* the space's base — material, not ancestry, so the same solid-valued-input rule
    the 3D view uses (`Document.isMaterial`) answers both questions — or the base itself while nothing has
    consumed it yet. It has one obvious answer at the moment the user asks for it, which is exactly why it is
    asked *then*.
  - **The step records that solid by name** (`tool cut els=e13,e15 …`), so replay is exact and nothing is
    ever re-resolved on load: the choice is structural, in OP-18's sense, like a `Select` sign or an array's
    count. A tool declares that it takes the part as `ToolDef.facePartOperand`, and the editor feeds it in as
    `elements[0]` — the same shape as `groupOperand`, a promise about how `build` indexes its picks.
  - **The plane keeps a different answer, deliberately: it stays anchored to the original base.** The face's
    *geometry* is that solid's face (and stays valid: a bore does not move the plate's edge), so only the
    boolean's operand advances. Two answers to two different questions, and conflating them is what the
    defect was.
  - The same rule reaches the **manual** path, because the face rectangle now stands for the part at its tip:
    a `Subtract` pick in the face view takes the cut, not the plate. That matters more than it looks — a
    cross-axis result is mesh-only and has *no footprint*, so the rectangle is the only place it can be
    picked at all.
- **A defect the face space made glaring, and it was never about faces.** `Scene3` hid any solid another
  *visible* solid was built from (OP-14's scaffolding rule, one level up) — and a face plane makes the base
  an **ancestor** of the drill without making it its **material**, so the plate vanished from the 3D view
  the moment anything was sketched on it (and a wall vanished under the storey stacked on it, which had
  been true all along). Material now means a **solid-valued** input, which is precisely what a boolean
  takes: a frame accessor (`facePlane`, `sideFacePlane`) or a `section` passes through a plane or a region,
  so the base stays an output in its own right until a boolean actually consumes it. Asserted from both
  sides — plate *and* drill drawn before the cut, only the part after it.
- **Persistence (OP-18): a step for the space, and the *ordering* for everything else.** `sketchspace
  "face1" el=e9 piece=0` declares a space — the solid and the boundary-piece index its plane is derived
  from, never the plane itself, so a part edited since comes back with its faces where they now are. Which
  space a *step* was built in is carried by ordering, with a `space "name"` switch step, exactly the
  ortho path's "current path" precedent. Two decisions inside that:
  - **The switch is written lazily**, just before a step that needs it (`Document.noteSpace`), because
    switching views is *view state*: flipping back and forth records nothing, is not an undo step, and only
    a step that is actually **built** somewhere says where. A replay puts the switches back verbatim
    (`switchSpace(record = true)`), so `save → load → save` is byte-equal even for a trailing switch that
    a delete can leave behind — asserted, with geometry in two spaces.
  - **Undo keeps the view.** A replayed script ends in whatever space its last step was built in, which
    after an undo is not where the user is looking, so the *view* wins where its space still exists and the
    replayed answer stands where it does not (undo the space itself and the plan comes back). Loading a
    file leaves you in the space the script ends in — the same argument as OP-18's visibility reversal: the
    file records what the user arranged.
  - **The delete cascade is honest.** The `sketchspace` step names its solid as an argument, so the
    ordinary explicit rule drops it when that solid goes; from there **everything drawn in that space goes
    with it**, because the space *is* what those coordinates mean. Unlike a group's membership that is not
    a matter of degree, and it is one more forward pass in `Document.dependentSteps` (the loader's ordering
    rule mirrored, so a delete and a replay agree on what belongs where). Undo restores the whole cone.
- **The shell.** A `<select>` in the topbar next to 2D/3D: the view indicator *and* the way back to the
  plan, listing every space with the face's solid beside its name. The Editor keeps **one camera per
  space** (so returning to the plan returns to the plan's own zoom and pan) and frames a face the first
  time it is shown, which is what makes switching over land on the material instead of beside it. The
  browser E2E drills for real: over **http** — where the WASM engine can load at all — a rectangle, an
  extrude, *Sketch on face*, a circle in the face view, *Cut*, and the bore in the 3D view. That is also
  the first browser coverage of a **cross-axis boolean**, which was previously unreachable by clicking.
- **Cuts in this slice, deliberately:**
  - **No ghosting of the other spaces.** A plan dimmed behind a face view is a projection decision (which
    of the other spaces, projected how?) and the reference outline already answers the question the user
    actually has, which is *where on the part am I drawing*.
  - **A face is one whole side.** A solid whose face is split by several z-slabs (a wall with an opening
    cut through it) offers its full extent as one rectangle rather than one space per slab; the sketch is
    still exact, but the outline can show material that is not there. Splitting it wants a per-slab face
    identity, which is a second index in the provenance name and is not built.
  - **No keyboard shortcut for switching spaces** (single letters are tool keys, and a cipher for spaces
    would be worse than the topbar control), no space **renaming** (a name is what the file refers to and
    nothing needs it), and no space **on a top or bottom face** — `facePlane` already names those and
    *Extrude on face* already uses them; a space on one is the same construction and no more.
  - A **mesh-only** solid (the general boolean's result) still has no footprint, so the drilled part is not
    pickable in 2D at all — OP-9's own open point, unchanged, and the reason the drill flow ends in the 3D
    view. Picking it wants 3D picking, which is still cut.

#### Two corrections from the same face (as built, on user reports)

**(1) One stamping seam, and it has to be the only one.** Reported: an ortho path drawn in a face view put its
*legs* in the face space and its corner *points* in the plan. `Element.space` is stamped by `Document.add`, "the
one place an element is born" — except one route built its `Element` by hand, and that route makes every
*constrained point*: a path corner, a rider, a ratio point, an arc break's split point. In a face view those
points are then invisible and unpickable (one canvas shows one space, deliberately), while the plan shows a
scatter of points whose coordinates mean nothing there and which drag the face's geometry about when clicked.
The fix is the seam itself — the route now goes through `add` — and the audit that follows it is the point: one
regression per creation route, drawn on a face (`SpaceStampingTest`), plus the reported file itself. One route
was corrected in the other direction while auditing: a **copy** (an array, a mirror, a rotate) now keeps its
original's space, as it already keeps its kind, so transforming the part a face is a face *of* — the one
cross-space pick there is — stays in the plan where its footprint is drawn.

**(2) Which way a feature builds belongs to the operation, not to the space.** Reported (GitHub #1) as a glitch:
a profile drawn on a face and extruded produced no visible solid, only two surfaces flickering against each
other. The solid was there, the right size and in the right place — *buried inside the part*, sharing its base
plane with the face. A face space's plane is the face's plane **flipped** (OP-17's frame convention, above), and
a plain *Extrude* inherited that direction: every boss was a wart inside the material, and only *Cut* was
reachable. The rule now stated: **Cut goes in, Extrude goes out**, and a face-space *Extrude* is a boss.

- **The space keeps its frame, and that is not a free choice.** A right-handed plane's normal cannot be reversed
  without mirroring its `v` (`Plane3.flipped`, and there is no third option — the frame note above says so), so
  turning the space's plane round would move every face-space drawing ever saved. The *extrude* therefore starts
  its sweep `depth` **behind** the face — `sketchOn(planeOffset(facePlane, −depth))`, still swept the space's own
  way — so the material lands between the face and `depth` outside it and not one drawn coordinate changes
  meaning. No kernel change either: the sweep stays positive, which is what the cap-winding rule is tied to.
- **No version bump, and the reasoning matters** (OP-18's doctrine is about stored *literals*): the depth still
  means what it meant, and the direction was never in a file at all. What changes for an existing drawing is
  that a buried wart comes back where it was drawn, which is the repair. The one behaviour that goes with it:
  *Extrude* + *Subtract* by hand no longer drills a face — an outward boss subtracted removes nothing — and the
  operation for that is *Cut*, which is what it was built for. Said in the tool help, the space's own note and
  the status line.
- **Stated limit: a partial *Revolve* in a face space still sweeps inward.** An extrude can be turned round by
  moving its start plane, which changes no coordinate; a sweep cannot — reversing it means a negative angle, and
  the kernel ties its cap winding to a positive sweep (the same rule that refuses a negative extrude depth). The
  honest fix is a `dir` argument on the feature, which is not built. A full turn is unaffected.

#### Datum planes — any line, any angle (as built, on GitHub #6)

The general form of a sketch space, and the request that named it: *"in general it should be possible to define
an arbitrary 2D sketching plane … a new plane can be either parallel to the base plane (already possible with
Section) or intersect the base plane in a line under some angle. Sketch-on-face is the special case where the
line is a boundary segment and the angle is 90°. Any line in the base sketch can be used, and any angle."*

That reading is exactly right, and building it that way is what makes it cheap: a space still contributes *one
thing* to a construction — the plane its features sketch on — so this adds **one op** (`datumPlane`), one
`ToolDef`, one variant of the `sketchspace` step, and no value type, no evaluation rule and no second concept.
*Sketch plane (line + angle)* takes a `LINE` pick (a line, a segment, a ray or an ortho/wall leg — the ordinary
carrier coercion, so a wall's centreline carries a plane) plus a **defaulted** angle slot, so the gesture is one
click and typing a number first is what tilts it. `DatumPlaneTest` is the record.

- **The frame: the base space's frame rotated about the line, right-hand rule.** With `n` the base plane's
  normal and `w = n × u` (its in-plane perpendicular, so `u × w = n`): `u` is the line's direction embedded in
  the base plane, `v = w·cos θ + n·sin θ`, hence `normal = u × v = n·cos θ − w·sin θ`. Three properties fall out
  and each is asserted: at **0°** the datum *is* the space it came from (same point set, same normal),
  re-anchored on the line; at **90°** it stands upright with `v` along the base normal; and the hinge lies in
  **both** planes at `v = 0`, which is what "intersect the base plane in a line" means — so an angle edit
  rotates the plane about the line the user picked and about nothing else.
- **The origin is absolute, and that is the opposite of a face's rule.** It is the **carrier's**
  nearest-origin point (OP-20's anchoring rule, one dimension up), not the picked segment's start: stretching
  the host must not slide the datum's coordinates along it, and only the carrier's foot has that property —
  dragging a segment's start 15 mm along its own carrier moves nothing on the plane, asserted. A face frame is
  deliberately the other way (anchored at the face's *top* edge, so a bore stays 8 mm below the top face when
  the plate is thickened) because a hole is dimensioned from the part's own edge. Same distinction OP-20 draws
  between a host that merely *carries* a thing and a host that is what the thing is *measured from* — and here
  the two answers sit in one file, one line apart, which is why both are written down.
- **Which way a feature builds: Extrude follows +normal, Cut follows −normal.** On a face the rule is stated
  against the *material* (Cut goes in, Extrude goes out — *Two corrections from the same face*), because a face
  plane has a material side by construction. **A datum has none**, so the rule is stated against the datum's
  own normal instead, and the normal's sign is fixed by the right-hand rule about the line: **the sign of the
  angle flips both, deliberately**, and it is said in the tool's help, the space's note and the status line.
  Mechanically the two are one implementation: the operation that goes the *other* way starts its sweep `depth`
  behind the plane and sweeps positively, so the kernel's positive-depth cap rule is untouched and not one drawn
  coordinate changes meaning (the same move GitHub #1's boss needed).
- **Sketch-on-face is the special case, asserted as an equivalence.** A **90°** datum on a footprint edge has
  the same `u`, the same `v` and the same normal as `sideFacePlane` derives for that edge, and the face plane's
  origin lies in the datum's plane — the two frames differ by an offset **along `v` only**, the part's own
  height, which is precisely the anchoring difference above. A **−90°** datum is the plane the *Sketch on face*
  tool actually opens (that space's plane is the face's flipped, normal into the material), so "8 mm below the
  top face" is `v = 8` there and `v = 8 − 20` here and nothing else differs. Both directions asserted.
- **The part a datum cuts, and why it is a recorded choice.** *Cut* means "subtract from the part this plane
  belongs to", and a face space *names* its solid while a datum names a line. So the part is resolved from the
  hinge — the newest visible solid the line is part of the construction of (**ancestry**, not material, which is
  the opposite of `facePartTip`'s test and for a stated reason) — resolved **once, at creation**, when the
  datum's own plane node does not exist yet, so the tool solid a *Cut* is about to build cannot be mistaken for
  the part being cut. It is then **recorded in the step** (`part=e5`) and never re-derived on load: OP-18's rule
  that a scored choice is persisted at creation. From there the ordinary sequential-feature rule takes over —
  `facePartTip` chains onto whatever this resolved to, so a second cut chains instead of forking. A datum whose
  hinge belongs to no solid is a **free-standing** sketch plane: *Extrude* works, and *Cut* declines with a
  reason that names *Extrude*.
- **The tilted cut is cross-axis, so it is OP-9's engine, and the numbers are exact.** A datum hinged on the
  plate's front bottom edge at 45° is the plane `z = y`; *Cut* sweeps −normal, so with a sketch rectangle and a
  depth that both overhang the part the removed set is precisely `plate ∩ {z ≤ y}` = 64000 mm³ and what is left
  is the triangular prism `½·20·20·80` = **16000 mm³**, watertight, `Feature3.MeshBoolean` — a 45° miter by
  clicking. That is also the first exactly-predictable assertion on the general boolean path.
- **The angle is a live parameter, which is the whole point of it being a node.** Retyping it tilts the plane
  and every feature built on it follows by recompute — `nodesCreated` flat (OP-21's rule) — and a rigid prism
  stays rigid while its bounding box turns. A datum's angle is an ordinary panel parameter, so it can be wired,
  shared or measured like any other; and a **negative** one is typed there, since the canvas's numeric entry
  takes digits only.
- **Spaces compose, and one ordering rule had to be made explicit.** A datum can hinge on a line drawn in
  another datum space — asserted as a two-level chain, where the second plane's `v` at 90° *is* the first
  plane's normal — because the base is `Document.activePlane()`, an ordinary node. That is the first step that
  is rotated out of *the space it was built in*, so the lazy `space` switch (OP-18) has to be written **before**
  it: by the time the `sketchspace` step is appended the new space is already active, so `createDatumSpace` asks
  for the switch itself. Round-tripped byte-equal with a switch back and forth in between.
- **What the datum view renders: the hinge, in the plane's own (u, v)** — the datum's `u` axis, over the extent
  the picked element reaches, at the grid's weight, and it is where a pick of the part lands (one rule: what is
  visible is pickable, `Document.spaceOutline` serving the renderer, the distance test, the marquee and the
  first camera). An **unbounded** hinge (an infinite line, a ray) has no extent and none is invented for it.
- **The division of labor with Section, stated so neither grows the other's job.** *Section* is the **parallel**
  answer (`sectionAt`, an offset along the base normal, cutting a solid into 2D geometry); a **datum** is the
  **angled** one (a hinge line plus an angle, giving a plane to draw *on*). A general `section(solid, plane)` —
  the cut OP-17's downward slice recorded as "wanting datum-plane UI" — is now unblocked but still not built: it
  needs a plane-*valued* tool slot, which is a slot kind, not a plane.
- **Cuts in this slice, deliberately:**
  - **No projected reference context.** The base space's silhouette behind the datum is not drawn; the hinge
    answers the question the user actually has (*where on the drawing am I standing*), and a silhouette is a
    projection decision — which of the other spaces, projected how — of exactly the kind the face view already
    declines to make.
  - **A boolean between a datum-space solid and a plan solid cannot be picked in one gesture.** Each solid draws
    its footprint hint in the space its sketch was drawn in (which is what makes it honest), and one canvas
    shows one space, so a two-operand *Subtract* across two spaces has no gesture. *Cut* covers the case that
    matters by naming the part for the user, which is why it exists; the general case wants 3D picking (OP-9's
    own open point, unchanged).
  - **No datum from a 3D edge**, only from a line in a sketch — the issue's own scope. A solid's edges are not
    addressable objects yet (that is Manifold face/edge provenance, OP-9), and the one 3D-derived plane a click
    can reach is already `sideFacePlane`'s.
  - **No parallel-offset datum** (a plane at a distance from the active one, with no hinge): it would be a
    second tool for `planeOffset`, and nothing has asked for it — *Extrude on face* and *Section* cover the
    stacking cases the plan actually reaches.

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

### Implementation status (as built — the general boolean path, OP-9)

**Manifold is in, behind one declaration.** `expect object MeshBool { available; status; boolean(kind, a, b) }`
in `geom/MeshBool.kt` is the whole seam OP-9 promised as *"a deployment toggle, not a rewrite"*: the JVM actual
is the `manifold3d` JavaCPP binding (`org.clojars.cartesiantheatrics:manifold3d:2.0.3`, one jar per platform
with the C++ library inside), the JS actual is the `manifold-3d` WASM package, and nothing above the seam
knows which is running.

- **The exact path is untouched and still comes first.** `dsl` asks `Geom3.sameAxis(a, b)` — a cheap axis-only
  predicate — and same-axis prisms go to OP-22's slab algebra exactly as before, *including its refusals*: an
  empty result or an inconsistent arrangement is reported as itself and is **not** retried on the mesh engine.
  A general boolean that quietly answered where the exact one declined would make the exact path impossible to
  trust, since nothing downstream could tell which had run. `MeshBooleanTest.aSameAxisBooleanStillTakesThe`
  `ExactPath` asserts the split through the *value*: exact ⇒ `Feature3.Prism` with the bore as a hole in a
  slab and a volume equal to cap-area × height to 1e-6 mm³.
- **What comes out of the general path is mesh-only, by type.** `Feature3.MeshBoolean(kind)` carries the
  operation and nothing else — no plane to sketch on, no slab to cut, no plan to draw — so OP-9's
  mesh-is-a-sink rule is enforced rather than remembered: `facePlane` and `sectionAt` refuse it with a reason,
  and its `footprint` is empty. It is still a legal operand of the next boolean (which then also takes the
  general path), still measurable, still renderable, still printable.
- **Determinism is manufactured, not assumed.** Manifold guarantees a *manifold* result, not a vertex
  numbering; it sorts internally, may run under TBB, and emits duplicated vertices where property runs meet.
  So every result passes through `MeshCanon.canonical`: weld vertices with identical coordinates (`-0.0`
  normalised to `0.0` first, since the two are equal as numbers but not as keys), sort the survivors
  lexicographically and renumber, rotate each triangle onto its smallest index — a *rotation*, so the winding
  survives — drop the degenerate ones, sort the triangles. The canonical form is a function of the geometry
  alone: asserted directly, by relabelling and duplicating a mesh's vertices and getting the identical value
  back. Because vertices come out shared, `assertManifold` applies **unchanged** — the same
  every-edge-used-once-each-way check as the exact path, no adapted weaker check.
- **The precision cost, stated.** This Manifold generation carries `MeshGL` positions as **float32** (the
  double-precision `MeshGL64` is newer; moving to it is a one-line change in each actual). A general boolean
  is therefore accurate to ~1e-5 mm on drawing-sized coordinates: five orders coarser than the exact path's
  1e-7 mm welding lattice, two orders *finer* than the 0.02 mm chord tolerance the tessellated operands
  already carry. One more reason the exact path stays exact.
- **One deployment wrinkle, recorded because it is invisible otherwise.** `libmanifold.so` in that jar is
  built with Manifold's optional **assimp**-based mesh IO — a path this engine never calls — and links
  against `libassimp.so.5` *without bundling it*, so `dlopen` fails outright on a machine with no system
  assimp (which is what "the engine is unavailable" looked like at first). Demanding a system package for
  unused code would be a poor trade, so the build declares LWJGL's native bundle (which publishes exactly
  that library, with the right soname, for every platform) and `MeshBool` loads a copy by absolute path
  before Manifold's own — the dynamic linker then resolves the dependency against the already-loaded
  object. Best effort: a missing resource is skipped and the smoke test reports the real reason.
- **The browser: async instantiation against a synchronous evaluator — solved with OP-3, not with async.**
  WASM comes up after the first paint; `Evaluator` is and stays a synchronous pure function of the graph. So
  the module is loaded once at startup, `available` is false until it is up, and until then a cross-axis
  boolean is an *ordinary invalid node with a reason that says the engine is still starting*. When it
  arrives, one repaint re-evaluates the graph and the solids appear — the auto-heal that OP-3 already
  specified, with no loading flag threaded through the engine and no async colour spreading into the DAG.
  `Main.kt` contributes exactly that: `MeshBool.initialize { console.log(...); repaint() }`.
- **The WASM ships with the app.** The npm entry point is emscripten glue — an ES module with a top-level
  `await` that finds its `.wasm` through `import.meta.url` — so it is *not* re-bundled: a Gradle `Copy` task
  puts `manifold.js` and `manifold.wasm` from the resolved npm package into the js resources, and they land
  next to `index.html`. The app loads them from its **own origin** with the browser's native ESM loader
  (`locateFile` pointing at the `.wasm`), so nothing is fetched from a CDN and the published glue runs
  untransformed. Verified in Chrome over http: `[MeshBool] ready — Manifold 3.5.1 (WASM, float32 meshes)`.
  Opened over `file:` the browser refuses ES modules altogether, and that is the honest demonstration of the
  unavailable path — the E2E asserts the engine reports itself either way and the shell carries on.
- **The seam checks its own output, and one degenerate class is refused.** Manifold's guarantee is about
  *its* representation, not about a `Mesh3`, and the gap is real: a **tangent** contact — a bore whose wall
  exactly touches a face — is a solid that touches itself along a line, which Manifold represents with
  coincident-but-topologically-distinct vertices and canonicalisation necessarily welds into one, leaving a
  directed edge used twice. So `MeshCanon.finish` runs the same every-edge-once check the tests do and
  **refuses** rather than emit a shell with that in it (OP-3: invalid with a reason, healing as soon as the
  radius or the thickness moves off the tangency — asserted). Positional identity is deliberate and matches
  the rest of the engine: `Geom3`'s own mesh builder welds too, so "one vertex per position" is what a
  `Mesh3` *means* here, and a zero-thickness contact has no representation in it either way.
- **Where it is checked:** `MeshBooleanTest` — a 80×50×20 plate minus a **horizontal** ⌀12 bore (watertight;
  86 vertices, 172 triangles; volume 74369.56 mm³ against the analytic 74345.13 mm³, i.e. +3.3e-4 relative
  and *heavy*, the direction an inscribed bore must err in; byte-identical mesh across two independent
  evaluations, ~6 ms each), a hexagonal prism with a side pocket on a flat that is coplanar with nothing,
  cross-axis union and intersection (the overlap box, to its bounds), the tangency refusal and its healing,
  the exact-path guard, and canonicalisation itself.
  Two former refusals became hand-offs and are asserted from both sides (engine present / absent):
  `PrismBooleanTest`'s revolve operand and cross-axis pair, and — the one that reads like a feature —
  `HouseChainTest`, where **the roof now fuses onto the walls**.
- **What remains.** ~~The editor tooling: there is no way to *name a vertical plane* in the UI yet, so a
  cross-axis boolean is reachable from the DSL but not from the toolbar~~ (the same gap the house's roof has
  had all along — a datum-plane UI, not an engine limit). **Closed** by sketch spaces: a space on a solid's
  side face names a vertical plane by clicking one footprint edge, so the drill is a gesture — see
  *Implementation status (as built — sketch spaces, and a sketch on any planar face)* under OP-17. A mesh-only solid has no footprint, so it cannot be
  picked in plan or marquee-selected; picking it wants 3D picking, which is cut. Manifold's face-ID/property
  propagation (OP-8's route to naming a *boolean's* faces) is not read yet. Windows and arm64 have no
  published jar, where `available` is false and the path refuses with that as the reason. And the mesh export
  (STL/3MF) that OP-9 names as the sink's whole point is still not written.

### Exact prismatic booleans (OP-22 — RESOLVED)

**Decision: booleans between solids extruded along the *same axis* are computed here and now, and
**exactly**; every other boolean is refused with a reason and waits for Manifold (OP-9).**

> **As-built note:** the waiting is over — Manifold is wired in behind the `MeshBool` seam (see
> *Implementation status (as built — the general boolean path, OP-9)* above), so "every other boolean" is now
> *handed to the general engine* rather than refused. **Nothing else in this section changed**: the same-axis
> path below is still taken first, still exact, and still reports its own failures as its own — a general
> boolean never answers where the exact algebra declined. What follows describes the path that stayed.

The reasoning is the mirror image of OP-9's. General mesh CSG — BSP splitting, then a stitch — *cannot
honestly keep the watertightness guarantee* in floating point: coplanar faces have to be classified by an
epsilon, and every split leaves T-junction candidates behind, so a "manifold" result is a hope with a
repair pass behind it. That difficulty is exactly why Manifold exists and why OP-9 already names it as
the production engine. But what the showcases need *now* is a narrow and very common case:

| need | operands |
|---|---|
| **counterbore / pocket / bore** (OP-17 slice 1) | a cylinder into a plate |
| **wall openings** (OP-21's 3D half) | a box through a wall |
| **storey and boss stacking** | prisms at different heights |

All three are booleans between prisms **along one axis**. For those there is nothing to approximate: the
answer decomposes into a stack of 2D problems, and the 2D problems are polygon booleans.

#### The prismatic solid — the form that is closed under the operation
An extrusion generalises to a **prism**: `Prism(plane, slabs)` with `Slab(regions, z0, z1)`, the height
ranges disjoint and ascending along the plane's normal. A plain extrude is the one-slab case. The point of
the generalisation is **closure**: the union, intersection or difference of two same-axis prisms is another
prism, so a boolean's result is an operand of the next one and a counterbored, pocketed, lidded part is
just a chain of ordinary nodes.

A plain `extrude` nevertheless keeps its **analytic** `Extrusion` form (its arcs are exact circles); the
prismatic reading is derived only when a boolean asks for it. That is where the tessellation happens, and
it is deliberate — see *Where the exactness ends*.

#### The slab algebra
1. Take every slab boundary of **either** operand as a z-breakpoint (welded at `Z_EPS`).
2. On each resulting z-interval, each operand contributes exactly one slab or none — because the
   breakpoints are the *combined* ones, a slab either spans the whole interval or misses it. Apply the 2D
   kernel to those two areas. This is the whole boolean.
3. Adjacent output slabs whose areas come out **identical** merge back. Two storeys with one footprint are
   therefore a single shaft with no floor slab inside it, not two boxes touching.
4. Mesh: side walls per slab from its region boundaries; horizontal caps at every level, where the
   up-facing faces are `areaBelow − areaAbove` and the down-facing ones `areaAbove − areaBelow`. **The
   counterbore's annular shoulder is not a case in this code** — it is what that subtraction comes to.
   Where the two areas agree the difference is empty and there is no face at all.

"Same axis" means **parallel normals**, either direction; the frames are otherwise free, because the map
from one in-plane frame to the other is then a rigid motion of the 2D coordinates and preserves the
polygons exactly (an anti-parallel normal swaps the heights and mirrors the rings). So a pocket sketched
on a *flipped* face plane is an ordinary operand.

#### The 2D kernel — an arrangement, then a winding classification
`geom/RegionBool.kt`. Not Greiner-Hormann (it fails on shared edges) and not a Martinez-Rueda sweep
(whose status flags are precisely the part that is hard to get right in the degenerate cases): instead the
brute-force form of the same idea, in four deterministic passes.

1. **Weld** every input vertex into one table on an `EPS` lattice, so "the same point" is one integer.
2. **Arrange**: split every edge wherever another crosses or touches it, collinear overlaps included, into
   *fragments* whose interiors meet nothing. Each **undirected** fragment is kept once however many
   operands contributed it — which is what makes a shared edge behave instead of leaving a zero-width slit.
3. **Classify** each fragment by asking which side is material, per operand, at a probe point *provably
   inside a face of the arrangement*: offset from the fragment's midpoint by less than half the distance to
   the nearest non-collinear edge. Inside-ness is the **nonzero winding rule**, which is exactly OP-14's
   convention (outer counter-clockwise, holes clockwise). A fragment survives iff the operation's result
   differs across it, oriented material-on-the-left. No probe is ever taken *on* a boundary.
4. **Chain**: at a vertex, the next fragment is the first met rotating **clockwise** from the reverse of the
   arrival direction. That separates a pinch point into two loops rather than one figure-eight — which
   matters downstream, since a self-touching loop is what makes a triangulated cap leak. Loops then nest
   into `Region(outer, holes)` by containment, tested at an *edge midpoint* (two loops of a valid area may
   share isolated points but never a stretch of edge, so no tolerance is needed).

#### T-junctions: the one subtlety, and a defect it uncovered
A horizontal boundary may **cross** a vertical one — a boss overhanging the plate it sits on — which puts a
cap corner in the middle of a wall edge. That is a T-junction: a hole in the shell that no triangle count
reveals. So every polygon is made to **conform** to one global corner set: wall edges are split at it, and
cap triangles are subdivided at it *after* triangulation (a point on a triangle edge splits that triangle
through the opposite corner; both halves keep the winding and the new interior edge is shared by exactly
those two).

Doing this uncovered a real defect in the existing triangulator: `earClip` accepted an ear whose **diagonal
passed through another corner**, which both leaves a T-junction and makes the remaining polygon touch
itself, after which the clipper produces overlapping garbage. It had gone unnoticed because no test region
had a corner on a diagonal — a plus-shaped union has one immediately. `extrude` had the same bug; the fix
(count the ear triangle's *boundary* as containing a corner) is in the triangulator, not in the booleans.

#### Epsilons, stated
- **`RegionBool.EPS = 1e-7 mm`** — welding, collinearity, "does this point lie on that edge". Five orders
  of magnitude below the 0.02 mm tessellation tolerance, so it can never merge two genuinely distinct
  tessellation points, and far above the ~1e-13 mm noise of a line-line intersection on drawing-sized
  coordinates, so a crossing computed twice from two different edges lands on one vertex.
- **`RegionBool.PARALLEL_SIN = 1e-9`** — below this sine of the angle between two edges they are read as
  parallel. The positional error of an intersection grows like `noise / sin`, so at 1e-9 a crossing is
  still located to about one `EPS`. Two boundaries crossing at a *shallower* angle are not split; that is
  the one honest hole in the arrangement, and it surfaces as a refusal, never as a leak.
- **`Geom3.Z_EPS = 1e-7 mm`** — heights closer than this are one level of the stack.
- Predicates are written in **millimetres** (distance to a segment, distance along an edge), never as a
  threshold on a raw cross product, so no epsilon silently changes meaning with the size of the drawing.
  The classification probe offset is *derived from the local geometry* rather than being a constant.
- Determinism: insertion-ordered vertex ids, explicit sorts, a canonical output form (each ring rotated to
  its lexicographically smallest corner, rings then sorted). Nothing iterates a hash map for anything that
  reaches the result — which is also what makes the slab merge a structural comparison rather than a shape
  match.

#### Degenerate classes — covered, and refused
**Covered** (each with its own test): shared/collinear edges (side-by-side squares unite with no slit);
touching corners (two loops, not a figure-eight); a hole created by subtraction; a cut reaching the
boundary, which is a notch and not a hole; a cut right through, giving two disjoint regions; an operand
that already has a hole; an area minus itself (empty); coplanar interfaces (a boss on a plate's top face);
a horizontal boundary crossing a vertical one; and a counterbore whose deeper radius swallows the bore's.

**Refused with a reason** (OP-3: the node is invalid, hidden, and heals) — never approximated:
- an operand that is **not prismatic** (a revolve today, an imported mesh later), and two prisms with **no
  common axis**: these two were the refusals that named Manifold, and they are now **hand-offs** to it — the
  refusal survives only where the engine is unavailable (an unsupported platform, a WASM module still
  loading), and then the reason says exactly that;
- a result that is **empty** — subtracting everything leaves no solid, and saying so is more useful than a
  solid with no material in it;
- an arrangement that comes out **inconsistent** (an unbalanced vertex, a chain that will not close, a hole
  belonging to no boundary, two boundaries closer together than `EPS`, a cap that cannot be split to meet
  its neighbours). These are the failure modes of the epsilons above, and each one refuses rather than
  emitting a shell with a crack in it.

#### Where the exactness ends
Curved pieces are tessellated **before** the kernel runs, so a boolean's boundary is an *approximated*
curve from then on — the 2D analog of the mesh-is-a-sink rule, exactly as OP-15 records for spline offsets.
An exact analytic circle stays exact until it meets a boolean. What "exact" means for the boolean itself is
therefore precise: the result is the exact boolean of the *tessellated* operands, and a prism's volume is
its cap area times its height to the last bit. Tests assert both — against the polygons the mesh is made
of (1e-6 mm³) and against the analytic part (within what the 0.02 mm chord tolerance explains, and in the
direction it must err: an inscribed bore removes slightly too little, so a bored part comes out heavy).

### Implementation status (as built — booleans, and the openings they cut)

- **The kernel**: `geom/RegionBool.kt` (`BoolOp`, `combine`, `regionsOf`, `windingAt`, `canonical`) with
  `RegionBoolTest` naming one degenerate class per test. **The solid algebra**: `Slab`, `Feature3.Prism`,
  `Geom3.prismatic/boolean/prismMesh` plus the conforming passes, with `PrismBooleanTest`.
- **Three ops, one node each**: `union`, `subtract`, `intersect` in `dsl/Construction.kt` Tier 5. All of the
  value-dependent work is inside `compute` (OP-21's rule), so a parameter edit recomputes and creates
  nothing — asserted, as everywhere else, through `Construction.nodesCreated`.
- **`Feature3.footprint`** replaced the three places that reached into `feature.sketch.regions`: a prism has
  no single sketch, but every feature can say what its plan shows, which is what the 2D pick, the marquee
  and the footprint hint actually want. A boolean's hint is the outline of *every* slab, so the cut is
  visible in plan without inventing a projection.
- **Four `ToolDef`s, no controller code**: *Union*, *Subtract*, *Intersect solids* (two `SOLID` picks each —
  one new `SlotKind`, which is only a filter since solids were already pickable by their footprint hint) and
  *Cut openings*. They ride the generic `tool` step, so save/load/undo/delete came free and
  `BooleanToolTest` asserts the byte-equal round trip. `facePlane` works through a boolean, so
  sketch-on-face composes with it (the counterbore's plane is *the plate's top face, lowered*).
- **Cut openings** (OP-21's 3D half): click a solid extruded from a thick path's footprint and every
  interval on that path becomes a subtracted box — position and width along the leg, the wall's **full
  thickness** across it (so the box's side faces are *coplanar* with the wall's, the degenerate case the
  kernel is built for), sill to head in z — chained into **one** new solid. Every box is wired to the
  interval's own parameters, so dragging or typing a position, width, sill or head moves the cut, and the
  wall's carrier does too. Since the openings became grabbable at their jambs (OP-21's note), that is now a
  *canvas* gesture: `OpeningHandleTest` drags a jamb and asserts the cut follows — a slide moves the reveals
  and cannot change the volume, widening removes exactly `Δwidth × thickness × height` more, and the mesh
  stays manifold throughout.
  - The **number of openings is structural** (the array rule): it decides how many nodes exist, so an
    opening added afterwards does not retro-cut and the tool is simply run again. Deleting one *does* take
    its box with it, because a delete drops the step and replays the surviving script (OP-18) — the chain
    is rebuilt with one box fewer, with no special case anywhere.
- **Cuts in this slice**: no non-axis booleans (above); no fillets/chamfers in 3D (OP-9 has them as explicit
  constructions — sweep a profile along a provenance-known edge, then boolean, which needs sweeps first); no
  mesh export yet; the boolean's own faces are not nameable (`facePlane` gives a prism its top and bottom,
  but there is no accessor for "the shoulder"), and the tessellation of a boolean's operand is not shared
  between two booleans over the same solid — each recomputes it.
- **Known cost**: the 2D arrangement is **O(E²)** in boundary edges (a plain double loop), which is
  immeasurable at the sizes this engine produces — a bored plate is a few hundred edges — but would matter
  for a thousand-edge operand such as a tessellated involute gear cut into a plate. A sweep-line version
  fits behind the same signature; the brute-force one was chosen because the degenerate-case honesty above
  is the part that is hard, and it is much easier to get right without a sweep's status flags.

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
      re-parameterize-a-free-source (b) **delivered** on demand as *relative points*
      (`P = polarPoint(anchor, d, θ)`, DOF-preserving and invertible — see *Relative points*), and
      **generalized** to a rider: when both picks lie on one carrier the offset is a signed distance *along*
      it (`t = base + d`, one DOF before and after — see *Relative on a shared carrier*), which is also what a
      group's placement uses to make a figure rigid (OP-16);
      general inversion (c) out of scope.
- [x] **OP-5 Node graph data model** — RESOLVED: one uniform, strongly-typed dataflow DAG;
      unified numbers+geometry; exactly one output per node; intersections emit an ordered
      `PointSet` value consumed by a separate `Select(set, sign)` node (computed once, shared);
      topological eval with dirty-marking — **now as built**: a persistent per-node memo keyed on the
      *identity* of the argument values, self-invalidating at the mutation points, so a repaint that
      changes nothing upstream recomputes nothing (see the as-built note under *Evaluation*).
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
      **Manifold is now actually wired in**, behind one `expect object MeshBool` — the JVM binding and the
      WASM package, one declaration, so the engine is a deployment toggle. Any boolean the exact same-axis
      algebra (OP-22) cannot answer goes there and comes back as a mesh-only solid
      (`Feature3.MeshBoolean`, no named faces, no cross-section — the type boundary doing its job); results
      are canonicalised so a mesh stays a pure function of its parameters; in the browser the WASM's async
      arrival is carried entirely by OP-3 invalidity + one repaint. See *Implementation status (as built —
      the general boolean path)*. Still cut: mesh export (STL/3MF), face-ID propagation through booleans,
      and the editor tooling for cross-axis operands (a datum-plane UI).
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
      **Both halves of "hand-rendered canvas" are now built**: Canvas2D for the drawing and one WebGL
      program for the solids, with the whole 3D pipeline except the GL calls in `commonMain` — so the
      projection has SVG goldens and the orbit gestures have headless tests. See *The 3D viewport*.
- [x] **OP-20 Where things meet** — RESOLVED: a meeting point is a `Junction` that **owns** the shared
      freedom, with everything meeting there bound to it. Fixes the order-dependent *attribution* of
      DOF that made two runs at one junction behave differently; drags and typed values both reach the
      shared freedom through the junction, in closed form. Where a thing sits along its host is an
      absolute quantity, never a share of the host; and where the host has no absolute to offer, a
      **gesture compensates** its riders to the projection of their grab-time positions — but only while the
      position is unstated: an **explicit** anchor (OP-4 case b) supersedes the compensation and switches it
      off, which is the general rule *compensate only what the model does not say*. See *Junctions
      own the freedom* and *A gesture compensates the riders of the host it turns*.
- [x] **OP-19 Break / join legs** — RESOLVED and built: threshold-triggered topology edits by
      gesture (join on jog collapse, committed on release; break as a tool inserting a zero-length
      perpendicular). Required ortho coordinates to move from *shared* nodes to *bound* ones — a
      binding can be re-pointed in place, which is what makes a jog expressible. **Break now covers
      plain curves too** — a segment, an arc and a cubic Bézier, split as constructions, with one
      consumer rule deciding whether the original's step is replaced or the original kept hidden. See
      *Break and join legs*.
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
      see that section's implementation status. **Ortho paths and the walls riding them are captured
      too** (the *ortho-path bonus*): the same binding one level up — a vertex is published through a
      re-pointable `IndirectNode`, its coordinate nodes become the group's local ones, and so
      axis-alignment becomes alignment to the frame's axes, i.e. the rotated **project frame**.
      **A group now carries freedom of every kind**, not only free points and paths: a rider on a member
      carrier is *re-anchored* to a point of that carrier (the OP-4 case (b) conversion, so a stated anchor is
      what makes a figure rigid), while a polar offset, a ratio point and an on-circle angle are already
      relative to member geometry and need nothing. The create dialog offers all of them, labelled by kind,
      and **ticks them by default**, so a naive group is movable; what unticking costs is reported at creation
      time. See *A group carries the freedom it is built on*.
      Remaining: relocate-origin / re-parent / constructed frames (mates), macro promotion, and drawing
      *onto* a placed path (refused today, with the reason stated).
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
      tessellation tolerance. See *Implementation status (as built — 3D engine core)*.
      **Reachable from the UI**: `Extrude` and `Revolve` as ordinary `ToolDef`s over a new `AREA` slot
      (an outline or a footprint), riding the generic `tool` step, plus an orbiting shaded 3D view
      (`Camera3`/`Scene3`/`Painter3`/`Viewport3` in `commonMain`, one WebGL program in `jsMain`). See
      *Implementation status (as built — the seam's tools and the viewport)* and *The 3D viewport*.
      **The downward direction is built too**: `sectionAt(solid, height)` (exact for a prism, analytic for
      a plain extrude) plus *Extrude on face* and *Section* as `ToolDef`s, and *Volume*/*Extent X/Y/Z* as
      3D measurements feeding forward — so the 2D→3D→2D→3D loop is a gesture sequence. See
      *Implementation status (as built — the seam downward)*.
      Cut so far: 3D transforms, 3D picking, mesh export (STL/3MF), a *general* `section(solid, plane)`
      (only horizontal cuts, which need no plane-valued slot), an analytic section of a revolve, and
      `project(edge, plane)`. Sketching on a *chosen* plane is no longer cut for the case that matters —
      a solid's top face — though a flipped or rotated sketch plane still has no plan projection for its
      footprint hint. Booleans — the counterbore — are no longer cut: see OP-22.
- [x] **OP-22 Booleans between solids** — RESOLVED: booleans between prisms **along one axis** are
      computed **exactly**, now; every other boolean goes to Manifold (OP-9), which is wired in — and the
      exact path is still tried first, still keeps its own refusals, and is asserted through the result's
      own type so it cannot silently degrade. General mesh CSG cannot honestly keep the watertightness guarantee in floating point
      (coplanar faces classified by epsilon, T-junctions after every split), which is why Manifold
      exists — but the counterbore (OP-17 slice 1), the wall opening (OP-21) and storey/boss stacking
      are all same-axis, and for those the answer decomposes into z-breakpoints × **2D region
      booleans** with nothing left to approximate. An extrusion generalises to a `Prism(plane, slabs)`,
      the form that is **closed** under the operation, so results compose; horizontal caps are the
      region difference between the slabs above and below (the counterbore's shoulder is not a case in
      the code, it is what that subtraction *is*). The 2D kernel is an **arrangement + nonzero-winding
      classification + clockwise chaining**, deterministic, with epsilons stated in millimetres; every
      degenerate class it cannot resolve is **refused**, never leaked. Curves are tessellated first, so
      a boolean's boundary is an *approximated* curve — OP-15's rule, one dimension down. See *Exact
      prismatic booleans*.

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
- **Session 3 — exact prismatic booleans (OP-22).** Resolved and built the boolean layer by *narrowing
  the problem until it stopped being approximate*: general mesh CSG cannot keep the watertightness
  guarantee honestly (which is why OP-9 names Manifold), but every boolean the showcases need — a
  counterbore, a wall opening, a storey stack — is between prisms on one axis, and that case is exact.
  Six things worth recording. (1) The right move was choosing the **representation**, not the algorithm:
  once an extrusion generalises to a stack of slabs, prisms are *closed* under the operation and the
  boolean is z-breakpoints × 2D region booleans. Nothing in the code special-cases a counterbore; its
  annular shoulder is simply what "the area below minus the area above" comes to at that level. (2) The
  2D kernel wanted **brute force over cleverness**: an arrangement plus a winding test at a probe point
  provably inside a face classifies every degenerate configuration uniformly — shared edges, touching
  corners, a hole reaching the boundary — where a sweep's status flags are exactly the part that is hard
  to get right. (3) The one genuine subtlety is **T-junctions**, and it is not in the booleans: a
  horizontal boundary crossing a vertical one puts a cap corner in the middle of a wall edge. Making
  every polygon conform to one global corner set fixed it — and uncovered a **latent defect in the
  existing ear clipper** (it accepted an ear whose diagonal passed through another corner, which
  `extrude` had suffered from too, unnoticed because no test region had a corner on a diagonal).
  (4) Epsilons are stated in **millimetres** and reasoned about, not tuned: 1e-7 mm for "the same point"
  (five orders below the tessellation tolerance, four above intersection noise), 1e-9 for the sine below
  which two edges read as parallel — and every configuration those thresholds cannot resolve **refuses**
  with a reason instead of emitting a shell with a crack in it. (5) *Cut openings* made OP-21's 3D half
  fall out with no new concepts: one box per interval, wired to the interval's live parameters, with the
  opening **count** structural (the array rule) — and because a delete replays the surviving script,
  deleting an opening rebuilds the chain with one box fewer, needing no cascade of its own.
  (6) One design note that reads backwards but is right: the 2D kernel now exists, and wall-to-wall
  **junctions still should not use it** — a boolean is the honest answer for a solid, while a unioned
  footprint is an opaque area whose corners the user can no longer grab (OP-20). 362 headless tests
  green (35 new), and the browser E2E now cuts an opening in a real Chrome.
- **Session 3 — the four showcases, as worked spec examples.** Built all four persona showcases (see
  *Showcases* above) as test classes with the spec in their KDoc, under the rule they were accepted under:
  a showcase may add no code of its own, only generic mechanism. Five things worth recording.
  (1) The score: **one macro** (`spurGear`) and **two ops of ten lines each** were all the new code, and
  neither op is about gears — `radians` closes the dimension system's one missing conversion (OP-7: angle→
  number existed, number→angle did not, so `tan β − β` could not be written at all) and `requirePositive`
  makes a **precondition a node** (OP-3), which is how a macro states its own domain without a comment.
  (2) The **gable roof needed nothing**, which was the point of building it: a triangle on a vertical plane,
  extruded along that plane's normal. Had the seam been quietly XY-only this is where it would have shown,
  and the reason it is not is that `SketchOn` embeds rather than 2D being plane-resident (OP-17).
  (3) The **sample count is structural** — the gear's flank is 12 chords at fixed parameter values, and the
  count may not be derived from the module however tempting: a value-derived count changes how many nodes
  exist, which is the regeneration OP-21 forbids. Adaptive tessellation *inside* one `compute` is free; a
  count that shapes the graph is not. Same rule as an array's count, third instance of it.
  (4) The papercraft net is the first thing to make the **downward scalar seam carry real weight**: nine
  panels whose every length is a `measureBBoxExtent`/`Max` of a mesh, asserted panel-by-panel against the
  *area of the actual mesh faces* — and retyping the wall height resizes the net with no node created. A
  net drawn by hand from measurements turned out to need no unfolding algorithm at all, exactly as the
  kickoff directive said.
  (5) A **refusal is a feature, twice over**: the roof cannot be unioned with the walls (no common axis,
  OP-22) and a section through both openings cannot be one area (a `Region` has one outer boundary) — both
  are asserted *as* refusals with reasons rather than designed around. Two goldens were inspected as
  pictures, which is what a golden is for: the gear has to look like a gear and the net like a house.
  437 headless tests green (25 new), three new goldens.
- **Session 3 — a probe on the gear's domain, and an honest correction to OP-14.** A review probe found that
  a 30 mm bore in a gear with a 17.5 mm root circle evaluated as a perfectly valid region. Three things came
  out of it. (1) The macro was **stating half its domain**: the pressure-angle guard was there, the bore was
  not, and the fix is the same `requirePositive` mechanism — expressing the bore as "the root radius less the
  web that must remain" is what puts the guard in the chain the geometry reads. (2) The claim that OP-14
  rejects holes removing more than the boundary encloses was **true only of `regionArea`**; nothing on the
  probe's path asked for an area, so nothing checked. Moved into `region(...)` as well, where the claim is
  made. (3) The genuinely interesting part: **that check could never have caught this class of fault anyway.**
  A bore at 18 mm — outside the root circle, inside the tip circle — removes *less* area than the toothed
  boundary encloses, passes the check, and yields 208 mm² of geometrically meaningless area for a ring of
  twenty detached teeth. Degeneracy is checkable by area; containment is not. So the lesson is not "add a
  stronger check to the type" but the one the paradigm already implies: **a construction that can produce a
  shape its type cannot describe must state its own domain**, and that is now asserted from both sides
  (the accepted non-contained hole included, so the limit cannot be mistaken for more than it is).
  441 headless tests green (4 new, two of them the reviewer's probe).
- **Session 3 — usability, measured as click budgets.** Turned "reach the result in a reasonable number of
  clicks" into a number: four whole workflows scripted as gestures and *counted*, with a ceiling asserted per
  workflow (`ClickBudgetTest`; see *Usability — click budgets* for the table and the mechanisms). Five things
  worth recording. (1) **Measuring found defects, not just costs.** Two of the four workflows did not
  complete at all: a rounded rectangle could not be traced (every joint is a tangency, and a tangent line and
  circle have no intersection — so the Outline tool silently built nothing) and a circle could not become an
  area (the tracer needs two pieces), which made a plain cylindrical hole unreachable through the tools. A
  budget is a good bug detector precisely because it walks the whole path instead of one op.
  (2) The fix for both was the same shape of answer: **stop re-deriving what the construction already
  knows** — pieces that already meet hand over at their shared endpoint (as an accessor node, so the trace
  stays parametric), and a curve that already bounds an area is accepted where an area is wanted, with the
  boundary order read off the step that built the pieces rather than detected from the picture (OP-14's rule
  survives intact).
  (3) **The generic form of "type it instead" was a *dimension* on the slot.** Direct distance entry existed
  for a leg's length; making it work for every scalar input needed nothing about tools and one thing about
  the declaration — `ScalarSlot(name, dim)` — after which digits typed with any tool armed become an
  ordinary parameter with no per-tool half anywhere.
  (4) **Two honesty notes.** Keyboard shortcuts changed no budget at all (a palette click and a keystroke are
  both one action) and are recorded as removing *mouse travel*, not actions; and a panel parameter creation is
  weighted 3 in the table, because it is three interactions and weighting it 1 would have hidden the friction
  being removed.
  (5) **What was refused:** letting the Outline tool swallow a whole closed chain from one pick (it would take
  away the ability to begin a mixed boundary on a shape's side), repeat-last-tool (a tool stays armed, so it
  buys nothing), and a shape tool auto-emitting a result outline (OP-14 wants the output set explicit).
  457 tests green (16 new), and the architect workflow now runs by keyboard in real Chrome.
- **End of session 3.** Thirteen work packages, each reviewed against a probe test its implementer never
  saw — a discipline that caught four real defects the suites had missed: node-id aliasing under repeated
  name hints, an ear-clip diagonal passing through a corner, a gear accepting a bore its area check could
  not see, and a typed parameter stranding itself outside its tool's undo step. What ships: the editing
  baseline (undo/redo, step-unit delete, multi-select, groups, frames), the generic thick path (OP-21),
  dimensions, arrays and shapes-by-construction, the whole 2D↔3D seam both ways (OP-17) with exact
  prismatic booleans (OP-22) and a WebGL view, user-recorded macro tools with live propagation (OP-6),
  the four persona showcases as worked specs, and click budgets asserted as tests (130 → 95 actions over
  the four flagship workflows). 459 jvm tests green; every feature also verified in real Chrome.
- **Session 4 — ortho paths under a frame: the cut in OP-16 step 2 is closed.** A user report ("I can
  group an ortho-path, but I cannot assign a frame to that group — *it owns no free point*") was exactly
  the omission OP-16's as-built note recorded, and closing it delivers the *ortho-path bonus*: the rotated
  **project frame**. Six things worth recording.
  (1) **The insertion point was the question, not the algorithm.** An ortho vertex's freedom is two scalar
  coordinate nodes, and a rotated frame mixes x into y — so `world = f(frame, lx, ly)` cannot be a per-axis
  binding, and the capture has to sit where the *point* is consumed. Hence one new node kind, `IndirectNode`
  (OP-5's binding substrate generalized from a literal to a derived value): a re-pointable view that a
  vertex is published through, bound in place onto `frameApply(frame, pointXY)`. Everything downstream —
  legs, wall footprint, openings, section, cut solid — followed with no rule of its own, which is the
  clearest evidence yet for OP-5's no-rewiring stance.
  (2) **Nothing was converted; the coordinates were re-read.** The binding structure that holds a leg
  axis-aligned is untouched and now relates *local* coordinates, so axis-alignment becomes alignment to the
  frame's axes. Turning the frame 30° leaves every leg straight and perpendicular in the group. The feature
  *is* the side effect.
  (3) **One rule for both capture kinds, forced by the paths:** a capture changes the origin, never the
  orientation (an axis-aligned path in a turned frame *is* turned). Free points had been captured through
  the full inverse; making them follow the same rule keeps a mixed group rigid — and incidentally fixed a
  latent defect the new test found, a rotated free point losing a bit through `save → load → save`.
  Unplacing therefore gives back exactly what placing took, and a turned group comes back unturned rather
  than torn in two.
  (4) **The junction is the honest boundary (OP-20).** A junction is a *world* position, so a captured
  vertex may not be driven by one. A path whose freedom leaves it at a junction is not captured at all —
  a path is one unit of freedom, its coordinates being shared along each run — and is reported as not
  following its frame; and a placed path's end refuses to weld outward, magnet included. The reverse
  direction needed nothing and is how a new run reaches a placed wall.
  (5) **Two conventions in one file, decided by step position.** A captured source's own step replays
  before the placement, so it restates its *pre-capture* position; a break recorded after the placement
  restates world. For a turned path only the former can work — the drawing steps snap every leg to an axis,
  so they cannot rebuild a turned run from world coordinates. The file still contains no local coordinates
  and no node names.
  (6) **One cut, stated in the app:** drawing *onto* a placed path is refused (a new leg would snap to the
  world axes), so clicking its end starts a new run joined to it — what clicking a connected end always
  did. 491 headless tests green (23 new; `PlacedPathTest`), and the browser E2E now groups the whole storey
  and turns it by typing an angle.
- **Session 4 — the end of a run, made unmissable (and the silence behind it).** A user drawing a T-web
  reported *"the ending did not snap and did not finish the path"*. Four things worth recording.
  (1) **The complaint was literally true, and it was not about feedback.** The middle run's second end could
  not attach at all: its x already belonged to the junction at its *first* end, so a second junction was one
  DOF too many and `attachOrthoEndpointToCurve` returned false — silently. The fix is a **determined meeting
  point** (OP-20): with one coordinate left to give, the meeting place is the crossing of the axis line
  through the given coordinate with the curve, so the corner ends with none. Composed from existing
  primitives, so it replays from the same `attachortho` step and undoes like anything else — and the saved
  file of the reported drawing now *reloads* as what was drawn, where before load quietly dropped the
  connection too.
  (2) **The wrong message was a true statement about a wrong graph.** Clicking that end afterwards said
  *"extending this path"* — the dangling-end wording — and the handler was right, because (1) had left the
  end dangling. Diagnosing the state instead of rewording the message is what turned one confusing sentence
  into one real defect; with the attach made, the same click starts a branch, as the design always said.
  (3) **A design rule reaffirmed: no route may refuse in silence.** OP-20 already required a refused
  connection to explain itself, but only the drag magnet did — the path click, the very gesture that *looks*
  like the end of a run, said nothing. One predicate (`Document.connectRefusal`) now serves magnet, release
  and drawing click, so the three cannot drift apart, and a refused end-click keeps drawing with the reason
  on the status line.
  (4) **The cue had to be generic and had to outlive the status line.** A finished run marks its terminus on
  the canvas — nested diamonds, one mark for weld, attach and close alike, derived from the finished path
  rather than from the gesture — because what must be noticed is that *drawing stopped*, not which
  construction stopped it. It is cleared by the next press or keystroke and deliberately not by a hover: a
  mouse always moves right after a click, and the status line is already handed to the snap label by that
  same move, which is exactly why the durable half of the signal has to be on the canvas. The rubber band
  needed no change and turned out to be the honest second half — a band means "still drawing", and there is
  none after a terminal click. 500 headless tests green (7 new; `OrthoRunEndTest`, with an SVG golden for the
  mark).
- **Session 4 — four user reports about *reaching* things: a group's members, and a parameter's name.** All
  four were about the editor getting in the way of something already modelled, and each had one honest rule
  behind it rather than a special case.
  (1) **Which subject a gesture on a group member addresses is now decided at the press, from the selection
  the press found** (OP-16's as-built note). Dragging a member of a group *nobody had selected* moved the
  whole frame, which cannot be right when grouping is invisible until it is selected: nothing on the canvas
  says the thing under the cursor belongs to anything. Press time picks the subject (frame only when the
  group is selected as a whole, otherwise the member's own handle) and release time picks the selection (a
  click cycles group→member→group, a drag leaves the element it moved selected — what dragging ungrouped
  geometry does). That is one coherent scheme with the deferred Shift-toggle, and it is why the decision is
  *not* re-made on release: a release cannot undo what the drag already moved. Flat groups needed no change,
  and the magnet and axis lock work on the member drag because it *is* the ordinary element drag.
  (2) **A frame marker is drawn only while a member is selected *and visible*.** Hiding a placed group left
  its origin marker behind, which reads as unpickable geometry. One condition, both hide routes, no new
  state.
  (3) **A parameter's name is editable in place, and the format needed nothing** (OP-7). The script names
  every scalar by its *current* name and resolves by name on load, so rename + save + load + save is
  byte-equal with everything still wired. The interesting part was the *scope*: renameable is exactly
  "named in the file". A measurement (OP-4) and an opening's own `pos`/`sill`/`head` (OP-21) carry no name
  into the script — replay would regenerate the old one, and a reference under the new one would not load —
  so they are refused with the reason instead of renamed in memory only. Two latent format bugs fell out on
  the way: names are now normalised to one quote-free word (a space split the step, a quote came back
  changed), and an opening's values are restated **positionally** instead of by matching its parameters'
  names, so nothing can quietly stop tracking a renamed one.
  (4) **Value fields are native number inputs, with the undo granularity stated.** Geometry follows every
  spinner tick (a live, uncommitted write) and only a committed change is an undo step — `commit` is an
  argument of one `Editor.setParameter`, so the shell's `input`/`change` split carries no policy of its own.
  The shell's one new rule is that a parameter row holding the keyboard is not re-rendered; replacing it
  under a live spinner destroys the focus and with it the next tick. 517 headless tests green (14 new:
  drag-subject and marker visibility in `PlacedGroupTest`/`GroupTest`, rename + granularity in the new
  `ParameterPanelTest`), plus the browser E2E extended for the two real-DOM rules (a name commits on Enter
  without its letters arming tools; ArrowUp in a value field reaches the canvas and undoes as one step).
- **Session 4 — "why did resizing a wall move a leg on the other side of the room?"** Reported on a T-web:
  dragging the rectangle's bottom wall down by 20 took the branch's horizontal leg down with it, from
  y=17.25 to y=-2.75. Four things worth recording.
  (1) **The position was stored relative to the host, and the host's anchor was invisible.** A junction held
  a distance along the host's carrier line, whose `origin` a segment inherits from one of its endpoints — and
  that endpoint belongs to the *neighbouring* wall. So every T attached to a wall slid when the wall was
  resized, and *which* corner it slid with was an accident of how the wall had been drawn. The user's own
  formulation is the rule: it must be transparent which corner a segment-attached point is anchored to.
  (2) **The fix is a parameter chosen from what the host determines, not from how it was built.** A host that
  is axis-aligned *by construction* fixes one of the rider's coordinates and leaves the other free, so the
  free one — a plain world coordinate — is the parameter, and the meeting point is where the axis line at that
  coordinate crosses the carrier. That is the same primitive stack a *determined* meeting already used, so
  the two ends of a T-web's middle run are now anchored the same way, and neither stores anything a wall can
  move. The degree of freedom is untouched: the T still slides along both walls, by drag and by typed number
  (OP-13). Its panel row even improved, from "along line" to "y".
  (3) **Fixing the class, not the gesture, meant auditing every route that parametrizes a position on a
  curve** — junction, determined meeting, drag-to-attach of a free point, the point-on-line slider,
  point-on-circle, a thick path's openings, the point-at-a-distance tool — and answering each on its own
  terms. Three were relative and became absolute through one shared helper (`Document.riderOn`); the circle's
  angle already was; the openings stay leg-relative *deliberately* (a plan dimensions an opening from a
  corner); the determined meeting turned out already absolute and got a test so it stays that way. On the
  old code, six of the seven new tests fail — which is the measure of how much of this was one defect.
  (4) **The diagonal host was the honest part.** A slanted line has no world coordinate to offer, so it keeps
  a distance *along* the line — but re-anchored to the point of the line nearest the world origin, a property
  of the carrier alone, so stretching a slanted wall from either end no longer slides what rides it. What
  remains is that *turning* the host moves the rider, which no parameter along a curve can avoid, and that
  reversing the line's direction mirrors it; both are recorded under OP-20 rather than hidden. The file format
  needed nothing at all — a step restates the position it produced, and replay re-derives the parameter, so
  old files acquire absolute anchoring merely by being loaded. 527 headless tests green (7 new;
  `JunctionAnchorTest`, which replaces the print-only repro the report came in as).
- **Session 5 — "a plate minus a *horizontal* cylinder": Manifold, wired in.** The engine OP-9 named on turn 2
  and OP-22 deferred to has been a promise in this document for a long time; the ask was to make it a shippable
  seam rather than a spike, with the acceptance case being the one thing the exact algebra cannot express — a
  vertical prism drilled sideways. Six things worth recording.
  (1) **The seam is one `expect object`, and the dispatch is a *predicate*, not a fallback on failure.** The
  first design was "try exact, and if it returns a reason, try Manifold". That is wrong, and finding out why
  was the useful part: the exact path's refusals include *real* ones — the result is empty, the arrangement is
  inconsistent — and retrying those on a mesh engine would answer a question the exact algebra had declined,
  silently, with no way for anything downstream to tell which engine had run. So `Geom3.sameAxis` decides up
  front, cheaply (axes only, no tessellation), and each engine owns its own failures. The guard against
  degradation is then not a comment but a type: an exact result is a `Prism`, a general one a `MeshBoolean`.
  (2) **Determinism had to be manufactured.** Manifold guarantees a manifold mesh, not a vertex numbering —
  it sorts internally, can run under TBB, and duplicates vertices at property-run seams. A model that is a
  pure function of its parameters (OP-4) cannot accept that, so every result is canonicalised: weld identical
  positions, sort lexicographically, rotate each triangle onto its smallest index (a rotation, so the winding
  survives), sort the triangles. The reward is bigger than determinism — because vertices come back *shared*,
  the existing `assertManifold` applies unchanged, so the general path is held to exactly the same
  every-edge-once-each-way standard as the exact one, with no weakened check anywhere.
  (3) **The first honest bug the new path produced was a *tangency*, and it became a refusal.** The
  acceptance case was written with a ⌀12 bore through a 12 mm plate, which makes the bore tangent to both
  faces — a solid touching itself along two lines. Manifold accepted it and reported `NoError`, because its
  own representation can hold coincident-but-distinct vertices; the canonical form welds by position (as the
  whole engine does) and so turned each contact line into an edge used twice. The fix was not to loosen the
  check but to *make* it: the seam now verifies its own output and refuses a result that is not a closed
  shell, with the tangency named in the reason, healing as soon as the radius moves. The general path is
  therefore held to exactly the standard OP-22 set for the exact one — refuse rather than leak.
  (4) **The browser's async WASM needed no async anything.** Instantiation finishes after the first paint,
  while `Evaluator` is synchronous and must stay so. The state for "this cannot be built right now" already
  existed: OP-3. So `available` is false until the module is up, a cross-axis boolean is an ordinary invalid
  node whose reason says the engine is starting, and one repaint on ready is the entire auto-heal. Three lines
  in `Main.kt`, nothing in the engine.
  (5) **Not bundling the WASM was the right call.** The npm entry point is emscripten glue — an ES module
  with a top-level `await` that locates its `.wasm` through `import.meta.url` — and re-bundling that through
  Kotlin/JS's webpack yields a mangled loader rather than an error. Copying the two published files into the
  distribution and loading them from the app's own origin with the browser's native loader is less machinery,
  works offline, and runs exactly what upstream shipped. `npm()` still pins the version.
  (6) **Two refusals became features, and one of them reads like the point of the whole session.** The
  house's roof — a prism swept along X, over walls swept along Z — had been a *separate solid* on the record
  since it was built, with the refusal asserted in a test. It now fuses, watertight, at the sum of the two
  volumes. What is still missing is not engine but UI: nothing in the toolbar can name a vertical plane, so
  the cross-axis case remains a DSL construction, and a mesh-only solid has no plan footprint to pick in 2D.
  532 headless tests green (6 new in `MeshBooleanTest`), plus the browser E2E extended with the engine's own
  availability line.
- **Session 6 — five user reports, and one recorded decision reversed.** All five were about the editor
  standing between the user and something already modelled. Six things worth recording.
  (1) **A tool that collects picks has to say what it collected.** The Outline tool drew nothing while
  picking and answered a miss with silence — and since a pick changes no geometry, silence made "that curve
  is in" and "that click landed in space" identical on screen. Picks are now drawn in their own colour
  through the *same* emphasis path as the selection (a pick is not a selection, so it gets a different colour
  and the same vocabulary), a miss says so with the running count, and the count is on the status line after
  every pick.
  (2) **The click-per-piece trace fell to "two clicks", by recording what the construction already knew.**
  A fillet or chamfer now *registers* its tangencies as joints — nodes, so they follow the parameters — and
  registers the corner it replaced as superseded, position by position (two curves can meet twice; a rounding
  replaces only the corner it sits in, identified as the meeting nearest it, with no tolerance). The tracer's
  `sharedEndBetween` generalized from "do these two touch?" to "which pieces continue here?", and the tool
  follows every continuation that is **unique**, stopping at forks, dead ends, whole circles and re-visits —
  each with a reason on the status line. The user's own triangle (two chamfers, one fillet, six pieces)
  closes in two clicks; W4's budget went 31 → 24 and the total 95 → 88.
  (3) **The rule that kept OP-14 intact is that following is a *gesture*, not a load-time search.** The
  followed pieces are appended to the pick list, so the recorded step lists the whole ordered boundary and
  replay re-runs the same build over the same list — the loop is still constructed, never discovered. The
  price is that a followed piece needs a click position too (a point on it between its two joints, so an
  arc's branch is read off it exactly as a clicked one is), and the honest cut is that a *forced* boundary
  now closes itself, which Escape or one undo reverses.
  (4) **A recorded decision was reversed: visibility is persisted.** OP-18 said hiding is a view state
  because the file is a construction. The user's counter — losing hide state is data loss from where they
  sit — wins, and the old text is kept verbatim above the reversal. `hide`/`show` are journal steps batched
  per gesture, following the `group` rules for members that go away; undo/redo came free because the undo
  substrate *is* the saved script. One rule everywhere (per-element steps, group toggles included), and one
  exclusion that draws the line: a welded alias hides *by construction*, so nothing records it.
  (5) **Two "not working" reports were the same missing coercion, one kind apart.** *Intersect between arc
  and circle* failed because every `CIRCLE` slot demanded a circle, though every op behind it is about the
  carrier — so `carrierCircle`/`isCentric` were built as the exact twins of `carrierLine`/`isLinear`, and six
  tools accepted arcs with no per-tool case. The same move generalized *Fillet* to round legs: line–circle
  and circle–circle centres are compositions of `parallelAtDistance`, `concentricCircle` and the existing
  intersections, so the only new geometry in the whole feature is `radialPoint` (a circle's `projectToLine`)
  and `filletArc`. Which of the eight variants a fillet is gets decided from the two clicks and **stored**
  (OP-1); an unreachable radius is invalid with a reason and heals into the variant closest to solvable.
  (6) **Two limits stated instead of guessed.** Chamfer stays line-only (a bevel across a round leg means
  either a chord or an arc of the same length, and the convention is not stated yet), and drag-to-attach
  still refuses arcs (its magnet promises the point lands where the halo is, which riding a carrier off the
  visible arc would break). 561 headless tests green (24 new: `OutlineFollowTest`, `VisibilityTest`,
  `FilletCurvesTest`, `ArcCarrierTest`), and the browser E2E extended with the group-visibility toggle —
  pixels gone, one undo, panel state following.

- **Session 7 — two user reports: a group you can *build with*, and a drawing that has a name.**
  (1) **"I cannot create a circular array from a group as input."** The fix was not a group-array tool but the
  missing half of a rule the previous session had already written down: *a group acts as a whole only when
  selected as a whole.* That sentence decided the drag subject; applied to a **pick**, it decides that a
  geometry-slot click on a member feeds the *group* while the group is what is selected, and that element alone
  otherwise — the old behaviour, pinned by a test, because grouping is invisible until something of it is
  selected. Accepting a group is a promise about `build`, so it is a `ToolDef` property (`groupOperand`) rather
  than something inferred from the slot kind: a tool with a second element slot (Mirror's axis) indexes
  `Picks.elements` positionally, and a multi-element geometry slot would have broken it silently. The arrays
  now take a `List<Element>` where they took one, and *one element is the list of one* — the same
  generalization the ordered scalar list made.
  (2) **The panel is the second route into the same slot**, since it is as much an input as the canvas (OP-13):
  a group's row feeds a waiting geometry slot and otherwise selects, through one commonMain entry point, so the
  shell only routes. Naming is the pick there, so it needs no selection — and the slot's missing click position
  is filled by the group's own bounding-box centre, because the click list is positional and a hole in it would
  be a trap for the next tool that reads `clicks[1]`.
  (3) **What the file records is the members, never the group** (`els=e1,e2`). A `group=` argument was
  considered and rejected: the group is a fact about the *gesture*, and leaving it out means ungrouping cannot
  orphan an array, the delete cascade needs no new rule, and no step ordering has to be guaranteed. The count
  keeps meaning *instances including the original* (N = 3 over two members is 4 copies), and the one thing a
  group changes — `members × (count-1)` — is bounded by the same `MAX_COUNT` and **refused with the numbers**
  instead of clamped, since a different number of copies is a different construction.
  (4) **Arraying a *placed* group needed no code at all, and the probe says why**: a copy is a transform node
  over the member's published point, which a placement has already bound onto the frame, so the copies are
  downstream of it (OP-5). The reverse order is refused by the conflict rule that was already there, naming the
  copies in the way. The named cut is that copies land **ungrouped** — grouping per copy wants generated names,
  a delete/undo unit, and an answer to whether a copy's membership *follows* the original's, so it is recorded
  as follow-up rather than guessed at, and the status line says the copies are not grouped.
  (5) **A drawing has a name, and the name is not in the file** (recorded under OP-18). The file is the
  construction; a name constructs nothing and the filesystem already holds it — and holds it *truthfully* under
  rename and copy, which a stored name would not. So loading takes the name from the picked file, and
  `save → load → save` byte-equality is untouched. Deliberately *not* the visibility reversal in reverse:
  hiding is a decision about the drawing, a file's name is a fact about the file.
  (6) **Save became a real Save** where the browser has the File System Access API: ask once, then overwrite the
  same handle, with *Save as…* as the separate affordance and Open handing back a handle too. Every failure path
  falls back to the download — no API, refused prompt, revoked permission — except a *cancelled* picker, which
  must not download behind the user's back. The API is off over `file:`, which is both honest (no origin to
  remember a handle for) and what keeps the E2E on the fallback path, asserting the download's name instead of
  hanging on a native dialog. 583 headless tests green (19 new: `GroupArrayTest`, `DocumentNameTest`), and the
  browser E2E extended with the name field, a fallback Save and a group array fed from the panel.
- **Session 8 — "drill a hole in the back side": sketch spaces, and a sketch on any planar face.** The user
  asked for the one mechanical gesture the editor could not make. The engine could: OP-9's general boolean
  has cut a cross-axis bore since session 5, and OP-8's provenance identity always said *"side faces by
  boundary-piece index"*. What was missing was a way to **name a vertical plane by clicking**, and the
  answer turned out to be organizational rather than geometric — which is OP-17's decision paying off a
  third time. A document now has named **2D sketch spaces**, one active; a space contributes exactly one
  thing to a construction, the plane its features sketch on, so `SketchSpace` adds no value type, no op and
  no evaluation rule. It is OP-14's third column plus a camera. Six things are worth recording.
  (1) **The pick is one click on a footprint edge**, because a vertical side face projects to exactly that
  edge — so the edge names the face *and* its solid, and asking for both separately would ask the user to
  repeat themselves. Identity is the boundary-piece index (OP-8), a stored discrete choice like a `Select`
  sign, never re-identified from mesh topology. The caveat is written down: a ring turned inside out
  renames its edges, the same order-of-traversal limit OP-20 records for a reversed host line.
  (2) **The flip convention, forced rather than chosen.** The face's plane has its normal out of the
  material (as `facePlane` does); the *sketch* plane is that plane flipped, so a positive extrude depth
  drills **inward**. A right-handed frame cannot reverse its normal and keep its 2D coordinates, so the
  flip mirrors `v` — which then *forces* the anchor to the face's **top** edge, since only there does the
  mirrored `v` leave the face at `v ≥ 0`. Hence: `u` along the edge from its start, `v` down from the top
  face. The two consequences (the face view is seen from inside the material; thickening the plate moves a
  hole measured from its top) are recorded rather than discovered — the alternative frame trades one for
  the other and neither is free.
  (3) **Face-RELATIVE, which is the opposite of OP-20's rule, and both are right.** The frame is derived,
  so the bore rides the face: stretch the plate and the hole stays 25 mm from the edge and 8 mm below the
  top. OP-20 concluded the reverse for a rider on a wall, and the distinction is the *intent*: absolute
  when the host merely carries the thing, relative when the host is what the thing is **measured from** —
  a hole is dimensioned from the part's own edge, which is what the drawing says and what the machinist
  does.
  (4) **One deliberate cross-space affordance, and it is what makes `Subtract` reachable.** Each canvas
  draws and picks one space; the exception is that the solid a face belongs to is addressable *as that
  face* — the rectangle drawn as reference context is also its pick target, so the base can be clicked for
  the boolean. `Cut` (extrude + subtract in one step) was kept as well, since the space already names the
  solid being cut into. Ghosting the other spaces is cut: the reference outline answers the question the
  user actually has.
  (5) **A latent defect the feature made glaring.** `Scene3` hid any solid another visible solid was built
  from — and a face plane makes the base an *ancestor* of the drill without making it its *material*, so
  the plate vanished from the 3D view as soon as anything was sketched on it (a wall had been vanishing
  under its storey all along). Material now means a **solid-valued input**, which is exactly a boolean's
  operands; a frame accessor or a section passes through a plane or a region and consumes nothing.
  Regression-tested from both sides.
  (6) **Persistence needed one step kind and one ordering rule** (OP-18): `sketchspace "face1" el=e9
  piece=0` declares the space as a *description* (never the plane), and which space a step was built in
  rides the ordering, with a `space "name"` switch step — the ortho path's "current path" precedent
  exactly. The switch is written **lazily**, just before a step that needs it, because switching views is
  view state: it records nothing, is no undo step, and an undo keeps the view it can. Deleting the face's
  solid drops the space and everything drawn in it, because the space *is* what those coordinates mean.
  A **review probe caught the one semantic defect**, and it was the sequential-feature rule: with two faces
  and a cut on each, the second cut subtracted from the *original plate* instead of from the first cut's
  result — the model **forked** into two coincident one-hole solids (volume short by exactly the first bore)
  instead of chaining. The fix is recorded above as a rule rather than a patch: a feature's operand is **the
  current tip** of the part's boolean chain, resolved at tool time and *recorded in the step by name* so
  replay never re-resolves it, while the *plane* keeps its own answer and stays anchored to the original base
  (the face's geometry is that solid's). The probe also walked into a trap worth closing: it picked the second
  edge while still in the first face's view, where the tool found nothing and said so too quietly, so the
  drawing that followed went into the old space. Arming *Sketch on face* now returns to the plan — provably
  the only space where that pick can succeed, since only a vertically extruded solid has a planar side face.
  604 headless tests green (19 new: `FaceSketchTest`, the review probe, and a second browser E2E), and the
  E2E now serves the distribution over **http** from the JDK's own server so the WASM engine can load — the
  first browser coverage of a cross-axis boolean, screenshotted with the bore in it.

- **Session 9 — two reports, one package: a gesture that compensates, and relative points.** Both were the
  same shape of complaint — *"editing this moved something I did not touch"* / *"editing this changed something
  that should have followed"* — and both were answered without a constraint appearing anywhere.
  (1) **The recorded limit came back as a bug, so it stopped being acceptable.** OP-20 stores a rider on a
  diagonal host as a distance along the carrier and noted that turning the host still moves the rider. Turning
  one by 90° slid a whole inner segment along it. The fix belongs to the **edit**, not to the parameter: while
  a gesture reshapes a host, every carrier-anchored rider is re-solved each move to the projection of the
  world position it had **at grab time**. Grab-time (not incremental) is the load-bearing word — it makes an
  unturned host write *nothing*, a rotation continuous, and a drag back to the start exact to the bit. It also
  cures the second, smaller limit for free, since projection is direction-blind.
  (2) **One seam, found by asking where host geometry changes rather than which handles change it.** The
  registry is filled by `riderOn` — already the single decider of every rider's parameter — and the
  compensation runs once after the handle's drag in `pointerMove`, so endpoint drags, leg drags and OP-20's own
  junction delegation are covered without any of them knowing. The typed twin is the same call around a field
  or parameter write (OP-13). Riders that are *already* absolutely anchored are never registered, so the
  wall drawings of `JunctionAnchorTest` are provably untouched; chains are ordered by `dependsOn`.
  (3) **Relative points closed OP-4 case (b) on demand**, on a drawing where dragging a segment moved a
  circle's centre and changed its radius. `polarPoint(anchor, d, θ)` bound in place over the free point —
  2 DOF before, 2 after, nothing moves at the moment of the change, and *every existing reference follows*
  because binding is the OP-5 substrate welding already uses. Polar rather than dx/dy because the number the
  user wanted was the **radius**, and a handle's fields are its drag, so "type the radius" arrived at no cost.
  Invertible by *Make absolute*, which also became the missing UI for un-welding and detaching.
  (4) **Two gaps the work exposed, both fixed rather than noted.** A tool whose build creates no element had
  its journal step dropped as "empty" — so the *Join points* tool's weld was silently lost on save, while the
  same weld by drag was kept; an in-place rewiring now counts as an effect. And `pointoncurve` restated
  nothing, so a rider's position (dragged, typed, or compensated) was rebuilt from the click that first placed
  it; it now carries its parameter as `dofs=`, the seam a dimension's placement already uses — which is also
  what lets the compensated values ride the undo snapshot, since a checkpoint *is* the saved script.
  (5) **A general channel instead of a per-tool case**: a tool that only rewires changes nothing the canvas can
  show, so the document now carries a one-shot `note` the controller reads after any tool completes. 623
  headless tests green (19 new: `RiderCompensationTest`, `RelativePointTest`).

- **Session 10 — an opening becomes grabbable at its jambs (OP-21 × OP-13).** The user's own design, and the
  point of it is that this was **not** a new feature: an interval's `pos` and `width` were reachable only by
  number, which OP-13 calls a bug in the model. Five things worth recording.
  (1) **A drawing can carry a handle.** The reveal lines `planOf` derives per pass are now the pick: the same
  derivation returns them tagged with the interval and the edge (`Document.jambsOf`), `HitTest` measures to
  them with its one distance rule, and the winner *resolves* into a `JambHandle` over parameters that already
  existed. This is the ortho-leg addressing pattern taken one step further — a leg at least has an element,
  a jamb has none — and the payoff is that nothing needs keeping in sync: the plan re-sorting itself, a
  carrier drag or a reload all yield fresh jambs from fresh values, and the graph is untouched
  (`nodesCreated` and the element list are asserted unchanged across a drag).
  (2) **Two jambs is the whole answer to "which end moves?"** The leading one writes `pos` (and the width,
  being start-relative, survives untouched); the trailing one writes `width`. No gesture called *drag the
  width* exists, exactly as none called *drag the length* does — the field belongs to the handle that moves.
  (3) **Leg-relative positions earned their recorded exception.** Because an interval's DOF is a distance
  *along a leg*, and a rigid placement preserves distances, a jamb of a wall inside a placed, **turned**
  group drags in world space through the very same projection — no inverse frame map, no case of its own.
  The same property is why stretching the carrier leaves the opening where the plan dimensions it.
  (4) **The clamps had to live below both routes, or the two would stop being one operation.** `pos` is held
  in `[0, legLength − width]`, `width` in `(0, legLength − pos]` with a 1 mm floor — a width of zero is where
  the jambs meet and beyond it they cross, which is a broken drawing rather than a smaller opening, so it is
  refused. Putting the clamp in `Document.setInterval…` means a typed number is bounded identically to the
  drag and reports the same reason, and clamping (rather than ignoring the write) keeps the gesture
  reversible. The picking rule needed the same honesty: a jamb crosses its own carrier leg, so precedence
  would make one of the two unreachable and **distance** decides instead — along the wall the leg, across it
  the jamb. A jamb also outranks a placed group's frame, the one exception to OP-16's whole-group rule, and
  the gesture names what it took the drag from.
  (5) **One generalization fell out and one gap got named.** `HandleField`/`Handle.dragNodes` now speak of
  `Node` rather than `SourceNode`, because a **named parameter** (OP-7) is as much a grabbable DOF as an
  anonymous coordinate — an opening's position is a panel row *and* what its jamb drag writes. And Delete
  still cannot reach an opening (an interval owns no element, and the selection→step route runs through
  elements); the journal half has worked since the thick-path rework, so it is a missing route, and pressing
  Delete on a selected opening now says so rather than doing nothing. 643 headless tests green (19 new:
  `OpeningHandleTest`), plus one more drag in the browser E2E.

- **Session 11 — a share of a span, a stated anchor, and a group that carries what it is built on.** Three
  requests that turned out to be one shape of problem: a quantity the model did not *say*. (1) **Ratio
  points**, from "the point a third of the way along". The mechanism came first and is what makes the feature
  free: a `ScalarSlot` may carry a **default**, and a tool whose scalar slots all have one never waits for a
  value — so *Midpoint* is still two clicks with an unchanged step and an unchanged derived point, and a typed
  `.3` turns the same gesture into `pointAtRatio(A, B, t)`. `t` is **dimensionless** on purpose: one `t` node
  over several pairs is equal proportions *by construction* (OP-5), which a length could not express and a
  constraint would have faked. Outside `[0, 1]` it extrapolates and says so. *Perp. bisector* gained the same
  slot and composes its answer from ops that already existed. (2) **Make relative on a shared carrier** — the
  user's own design. When both picks are positions on one carrier the offset is a signed distance *along* it,
  bound **into the rider's own parameter** (`t = lineParam(carrier, base) + d`), so the rider's point, element
  and consumers are untouched and the creating step keeps restating one meaningful node. One DOF before, one
  after; chains are dimension chains; the reverse is refused as the cycle it is. That closed a principle
  rather than a case: **compensate only what the model does not say** — a rider whose anchor is stated is no
  longer registered for OP-20's gesture compensation at all, and a 90° turn of its host now performs *zero*
  compensation writes. (3) **The grouping bug**, which was two bugs. The reported "ticked points do not become
  members" did **not** reproduce (the step lists them, now pinned by a test); the real wall was that the
  closure analysis and the placement capture understood plain free points only, so the figure's actual freedom
  — a rider and a polar offset — could neither be offered nor carried, and the points the group *did* want
  became a conflict. `Document.freedoms` now answers for every kind, and placement carries each its own way:
  the rider is **re-anchored to its carrier** (the same conversion (2) built — a stated anchor is what makes a
  group rigid), while offsets, ratio points and on-circle angles are already relative to member geometry. The
  group default flipped to *ticked* so a naive group is movable, and what unticking costs is said at creation
  time instead of at a Place click much later. The **duplicate `attach` steps** in the file were the last
  thread: the drag magnet offered an attach to the very circle the point defines, the release refused it (a
  relative point has no coordinates left to bind) and the step was recorded anyway. The magnet now asks the
  same question the release does, and a refused rewiring records nothing — so old files **heal** on load, which
  is what the fixture's two junk steps do. Two limits named rather than found later, both about angles stated
  in world axes (a polar bearing and an on-circle angle do not turn with a frame; the fix is the same
  bind-in-place trick over a frame-angle accessor). **A review probe then turned the frame**, and found the one
  place where "as built" was still a claim rather than a property: a placed figure rotated 90° did not survive
  `save → load → save`. Two causes, both fixed as rules rather than as cases. The rider's position was restated
  by **rewriting its tool step's last click**, which replays against the *pre-placement* (unturned) geometry —
  so it now rides `dofs=` exactly as the `pointoncurve` step's parameter has since session 9, and the click
  stays the choice it encodes. And the placement derived a rider's offset **after** binding the points, i.e.
  against turned geometry, so a replay at a nonzero angle captured a different figure than the gesture did; it
  is now read before any binding, the rule the free-point capture already followed. The probe also produced a
  coordinate of `-6.12E-16` in the script — zero up to the rounding of `sin 90°` — which exposed that the file's
  number writer fell back to raw `Double.toString`: it now expands the exponent by moving the decimal point in
  the digits, so the format is canonical *and* still bit-exact. (The probe's second finding, "a ratio-point
  selection refuses to group", was not a defect: grouping, placing and the round trip all succeed — the probe's
  first click after placing reaches the *member alone* through the ordinary click cycle, so the frame's angle
  field was simply not what it was addressing.) 683 headless tests green (38 new: `RatioPointTest`,
  `CarrierRelativeTest`, `GroupClosureTest`, the format's canonical-number rule and the review probes), plus a
  typed factor in the browser E2E.

- **Session 12 — Break, off the ortho path: one tool over a segment, an arc and a Bézier.** The user's own
  design, and it turned on a question the ortho break never had to ask: *what happens to the curve you
  clicked?* On a path a break is a topology edit — vertices in, legs out, nothing else means anything. Off a
  path there is no topology, only a construction to build, and the curve that was there is either still needed
  or not. So the feature is really **one consumer rule** (OP-5) with three geometries under it. If nothing
  reads the original's node, the break **replaces the step that drew it** — dropped, and the remaining script
  replayed, exactly as a delete leaves a script that still constructs (OP-18) — so the file reads as the two
  halves and no third curve lingers in the drawing. If something does read it (a fillet leg, a rider, an
  outline piece, a dimension, a measurement, a group's membership), the original **stays, hidden by a recorded
  `hide` step**, and the status names what kept it: *"e3 stays (hidden): e7 is built on it"*. Rewiring the
  consumer was never on the table; hiding costs nothing, because the halves cover the original exactly.
  The structural choice the whole thing turns on is **what the halves are built from**: the picks of the step
  that *drew* the curve, not the curve — which is the only reason that step can be dropped at all. Where a
  curve has no picks of its own (a rectangle's side, a mirror, a spline a macro built) the break materializes
  its **key points** instead and keeps the original as their source, hidden; so no kind is half-supported, and
  what varies is only whether the original can go. `extractPoints` gained the Bézier case for that — all four
  controls, through one new accessor `bezierControl(i)`, since a spline's *inner* controls had no name before.
  **An arc is the honest exception, and it is a property rather than a cut.** Its halves are trims *of it*
  (OP-14: a trimmed circle is an arc), so the shared carrier they need **is** the original: an arc offers
  nothing else. It therefore always takes the hidden route, and says so in those words. Rebuilding the carrier
  from the picks of whichever tool drew the arc (`circle3` for a 3-point arc, `circleCP` for a centre-ends one)
  would have made the journal rewrite reachable there too — at the price of putting per-tool knowledge inside
  the break, which is precisely what the data-driven registry exists to prevent. Stated, not hidden.
  **de Casteljau as a construction, not as a computation** is the part worth keeping in mind for OP-15's
  remaining curve algebra. The five intermediate points and the split point are `pointAtRatio` nodes over the
  four controls sharing **one** `t` parameter — the subdivision triangle *built* rather than evaluated. The
  halves are therefore exact because they *are* the formula; they stay exact under any control drag because the
  formula is re-evaluated rather than re-fitted; and `t` is an ordinary live parameter, so **the split slides**
  — type it, or drag any of the six ratio points, and the curve re-splits where you put it. One `t` over six
  ratio points is the OP-5 statement in miniature: one degree of freedom because it is one node.
  Two things the format needed, and only two. The segment and Bézier breaks record **no step kind of their
  own** — a half of a segment is a segment, a half of a spline is a spline, and a de Casteljau point is the
  ratio point Midpoint already makes, so they emit ordinary `point` / `param` / `tool` steps and replay through
  the same `ToolDef.build` a click would have run. The arc needed `breakarc <el> <angle> ccw|cw`, because
  everything it makes hangs off the arc it names: the angle is **state** (the rider slides, so it is restated)
  and the sweep is a **stored discrete choice** (OP-1) taken from the carrier, never re-guessed from the click.
  One checkpoint per break, however many steps it emitted. Two refusals rather than two half-answers: a curve a
  user-defined tool is built from (OP-6, the same refusal the ortho break makes), and a member of a **placed**
  group — the free point a break introduces is a world point the frame would not carry, and membership lives in
  a recorded step whose arguments are never rewritten, so it cannot simply join. 709 headless tests green (26
  new, `BreakCurveTest`), and the browser E2E extended with a plain-segment break — which is where the
  journal rewrite earns its keep, since replaying swaps the document under the running shell.
- **Session 13 — three user items that were one selection state machine.** The asks read as unrelated: *groups
  should be movable without a second step*, *an active parameter should be un-clickable*, and *clicking again
  should reach whatever else is under the cursor*. The third swallowed the first two's shape, and the useful
  part was seeing that the editor already had **two** click cycles — the group/member reach (OP-16) and
  jamb-vs-leg (OP-21) — each with its own remembered state and each reaching exactly two things. Five things
  worth recording.
  (1) **The invariant did the design work.** *A click that is not a repeat must select exactly what the old
  code selected* — stated up front, it fixed the ranking (points, curves, annotation, jamb by distance, then
  everything else), fixed where the machine may run (on release, only when the gesture did not move — the
  discipline the group cycle already used) and made the whole change reviewable against the existing suite:
  every selection, group, placed-group and opening test passes **unedited**, including the ones that click a
  member three times. Making the group entry repeat before each member is what preserves *group, member,
  group* without a rule of its own.
  (2) **The user's rationale is the ranking's justification, and it generalized further than it was given.**
  *A point cannot dodge, a curve can be clicked elsewhere* — so points outrank curves; and once said that way
  it plainly does not depend on how the point was born, which exposed a second defect: a **derived** point (an
  intersection, a midpoint on its own segment) lost the click to the curve under it, because only *draggable*
  points had ever been ranked first. Fixing that is the one sanctioned exception to the invariant, and it
  forced a distinction worth having: **selection rank and drag rank are two rankings**. A click reaches the
  point; the grab still prefers a movable handle.
  (3) **Cycling is only half a feature without priming.** The user's own follow-up: *selection primes the
  drag* — a press that continues the cycle drags what the cycle selected, so "step to the thing you want, then
  drag exactly it" holds. The pleasing part is that the placed group's frame rule (whole group selected → a
  member press moves the frame) **is** that rule, and had been since session 4; it stayed where it was and the
  new case was written as its sibling rather than as a second mechanism. Where the primed selection cannot
  move, the press explains and moves nothing — predictability over convenience, since falling through would
  move something the user did not point at.
  (4) **The defaults question was really "what is a flat group *for*?"** Ticking the frame by default is
  obvious once asked (a group is a part, and parts move); the interesting half is that unticking is not a
  fallback. A user had already found the use: a **flat group is the array original**, because its copies derive
  frame-free, and arraying a *placed* group makes them downstream of the frame instead. So the dialog words
  both readings as intents, and create+place commit as **one** checkpoint — giving a part its frame is not a
  second thing the user did. A refused placement leaves the group flat with the reason shown, which is the
  honest-failure rule arriving at the gesture that caused it.
  (5) **A machine built on one seam inherits that seam's holes.** Collecting candidates through `HitTest`
  turned up a ray that **no click could reach**: `distanceToValue` had no `RayValue` case, so rays drew and
  marqueed but could not be selected, cycled to, or fed to a slot — *Perpendicular* refused one outright. One
  case (a segment's clamp on the origin side only) restored all of it, and the audit it prompted — the kinds
  the renderer draws against the kinds picking measures — leaves exactly one drawn kind out on purpose, the
  transient `PointSetValue`. The un-pickable parameter pick was the same shape of omission one layer up: the
  canvas had a *never mind* and the panel did not, and a defaulted scalar slot silently adopted the stray pick
  forever. 740 headless tests green (28 new: `PickCycleTest`, `FramedGroupDefaultTest`,
  `ParameterDeselectTest`, `RayPickTest`), and the browser E2E extended by the two DOM routes that changed —
  the dialog's frame tick, and a parameter row clicked twice.

- **Session 14 — "the drawing came back wrong": the format gets a version, and a scored choice gets stored.**
  Reported as data loss on a six-spoke wheel, in two symptoms: **the fillets were inverted, producing sharp
  corners**, and **a rider on a construction line had moved along its carrier** between one save and the next.
  The archaeology mattered more than the guess, and it went two ways.
  (1) **The rider was *not* misread — and proving that is what kept the fix honest.** The file's
  `pointoncurve e17 14.118741663069027,42.702264910197286 dofs=52.86964276686915mm` is internally consistent
  under today's semantics, and every committed build since the parameter was first written (four of them,
  checked in worktrees) loads and re-saves it **byte for byte**. What looked like the smoking gun — the
  recorded position lies 19.06 mm off its carrier — turns out to be the format working as designed: put the
  wheel's *other* rider back at its own recorded position (92.833° instead of the 118.462° its `dofs=` now
  carries) and the first rider's recorded position lands on the perpendicular bisector to 1e-14 mm. The
  position was exact when written; an edit upstream later turned the carrier under it. So a "repair" that
  dragged the rider onto its recorded position would have **undone the user's own later edit** — one earlier
  attempt at this did exactly that, and its output is the one genuinely corrupted file of the three. Recorded
  because it is the trap this class of bug sets: *the stale literal is the one the format promises to keep.*
  (2) **The near-miss is the real finding, and it is a rule, not a case.** The anchoring rework (OP-20) changed
  what a distance along a carrier is measured *from*, and six commits later the file began storing exactly that
  number. Nothing was corrupted only because the order happened to be that way round. So the header is now
  versioned (`constructit 2`, reads 1 and 2), and the doctrine is written down: **a stored literal's meaning is
  frozen the moment a build that writes it might have shipped** — changing it costs a version bump and a
  migration, and where a v1 value is ambiguous the migration prefers the reading that reproduces the step's
  **recorded position** and otherwise keeps today's reading and *says which element it could not decide about*
  (`Document.loadNotes`). "Replay reconstructs" is not a free pass; it is a free pass only for what the file
  does not state.
  (3) **The second symptom was a real bug, and OP-1 had already named it.** A fillet's variant — which side of
  each leg, R±r, which intersection branch — was scored from the two clicks *and re-scored on every load*,
  against whatever the geometry had become since. A `Select` sign that lives only in the session's nodes is
  stored only until the next save. Fillet, chamfer and `intersectnear` now write their resolved signs into
  their step (`signs=1;-1;1`) and replay consumes them verbatim; the v1 files score once, on load, and are
  stamped by the save that follows. The regression is the sharp version of the report: a fillet's crossing leg
  is dragged *past* the click that scored it, so the old scoring would flip the rounding to the other side of
  the corner — and the persisted sign holds it where the user put it.
  (4) **What the reported files recover to.** Both fixtures load with their geometry unchanged to the last bit
  (rider parameters 52.86964276686915 mm and 56.38876034496988 mm respectively — each file's own value), all
  four fillets tangent to both legs at r=14, all four variants now stamped, and `save → load → save` byte-stable
  at v2. What is *not* recoverable from the second file is the 3.52 mm the rider had slid: nothing in the
  committed history reproduces that shift from a load, so the value it carries is the only statement of where
  that point was, and the migration keeps it rather than inventing a better one. **14 new tests**
  (`FormatVersionTest`, which carries the reported drawing verbatim), whole suite green.
- **Session 14 — three queued defects and the first issue, all at the `Document` seam.** The asks were
  unrelated on the surface and turned out to share one shape: *something the user reads or reaches is derived in
  two places, and the two disagree.* (1) **A rider could not be freed.** *Make absolute* worked on a point
  dragged onto a curve and refused the identical point placed by clicking, because only the first published its
  own `SourceNode`. OP-16's `IndirectNode` — built so a placement could put a frame in front of an ortho vertex
  without rewiring a consumer — is the same problem one level down, so every rider is now published through one
  and freeing it is a re-point: nothing moves, every consumer follows the point, and old files gain the
  behaviour on load with no format change. The reported wheel is the regression, six-fold array and all. (2)
  **The panel and the file numbered elements differently** — the file names them script-locally while every UI
  surface showed the runtime id, which also counts parameters and hidden nodes, so a file's `e17` was the user's
  `e21`. The script-local name is now the *only* user-visible name and the writer asks the document for it, so
  they cannot drift; the load's own migration findings, which `replaceDocument` used to clear before anyone
  could read them, ride the same authority. (3) **An ortho path drawn on a face left its corners in the plan** —
  one creation route built its `Element` by hand and so missed the sketch-space stamp; fixed at the seam, with a
  regression per creation route and a copy now keeping its original's space. (4) **GitHub #1: an extrude on a
  face built into the material** — the boss was buried inside the part, z-fighting the face it was drawn on.
  Which way a feature builds belongs to the *operation*: *Cut* in, *Extrude* out, realized by starting the
  sweep behind the face so that no drawn coordinate changes meaning (a right-handed frame cannot flip its normal
  without mirroring `v`, and files exist). The reported file is the regression: its wart now stands where it was
  drawn. **31 new tests** (`RiderDetachTest`, `NameAuthorityTest`, `SpaceStampingTest`, `FaceExtrudeOutwardTest`,
  `LoadNoteTest`, plus the boss on a face in `FaceSketchTest`), 785 green, browser E2E green.
- **GitHub #3 — the contour of a pocket, which shading could never have drawn.** The report was about
  lighting ("all surfaces with the same angle seem to be equally light") and the fix is not in the lighting: a
  pocket's floor and the face it was cut into *are* parallel, so any headlight, any ambient term, any number
  of lights gives them the same colour. The contour that vanished is a fact about the mesh's **topology**, not
  about its illumination. **Feature edges** put it back — one extraction (`Scene3.creaseEdges`) that both back
  ends read, drawn in a darker shade of the solid's own colour — and four things are worth recording.
  (1) The whole design is the **threshold**, and it is fixed from the *negative* side: the tessellation of a
  curved surface must stay invisible, which sets a floor of `2·acos(1 − tol/r)`. 30° keeps every radius above
  ≈0.6 mm quiet where 20° would already speckle a 1 mm fillet; what 30° forgoes — creases shallower than a
  150° dihedral — is exactly the range where shading works on its own. Creases and shading are complementary,
  not redundant, and `CreaseEdgeTest` asserts the margin numerically so the constant cannot be lowered by
  accident. (2) The **painter's projector needed the two adjacent centroids**, not a magic epsilon: with no
  depth buffer, "an edge sorts at least as near as the nearer of its own two faces" is a statement that can be
  made exactly, whereas a constant nudge is a guess that fails on a coarse mesh (a box's triangles are 40 mm
  across). The GPU says the same thing in its own vocabulary — `POLYGON_OFFSET_FILL` on the *faces*, because
  GL ES 2.0 has no line offset. (3) The **silhouette is cut, and named**: a curved surface turning away from
  the eye has no crease to find, and a silhouette edge is view-dependent (per orbit, not per document change),
  which is a different kind of thing. (4) The extraction's first version compared every face **with itself**
  (the second triangle of an edge overwrote the first), so a box came out with zero edges instead of twelve —
  found on the first run because the test that says "twelve" was written before the code worked. **7 new
  tests** (`CreaseEdgeTest`: the box, the cylinder's rims without its wall seams, the clean-radius formula, a
  fillet's tangency seams, determinism, the empty mesh, and the issue's own file — whose floor-vs-face shade
  equality is asserted first, so the regression is the *symptom*), plus the regenerated `scene3` golden.
- **GitHub #4 and #2 — "the rectangle is non-editable" and "all elements shown on every sketch", plus the
  regression the first report carried in with it.** Three items, and the shape worth recording is that the
  second of them was already the *answer* to the first.
  (1) **The regression: a corner may meet two junctions, one per coordinate.** The report was *"oh-my-god the
  ortho-path editing also broke again"* with a file attached — a T-web whose middle branch turns once between
  the left wall and the bottom wall. Reproduced headlessly first, by dragging every corner and every leg of the
  fixture on each axis and printing what moved, which turned a vague "snaps back" into a table: eight of nine
  corners behaved, and the ninth — the bend — moved on x and refused y. **The junction model was right; the
  handle could hold only one junction.** That branch's y belongs to the junction on the vertical left wall and
  its x to the junction on the horizontal bottom wall, so the bend is driven by two of them, and
  `OrthoCornerHandle`'s single `junction` (`junctionOf(x) ?: junctionOf(y)`) handed the *whole cursor* to
  whichever the x lookup found first. Half the gesture was never written, which is what "moves only in one
  direction and snaps back" looks like from outside. Three things about the diagnosis: rider compensation was
  **not** involved and provably could not be (`riderAnchors()` is empty on this drawing — every host is
  axis-aligned, so nothing is registered), nor were the pick pile or selection priming; **the panel is what
  gave it away**, because typing the same y worked and a coordinate field asks `junctionOf` per coordinate;
  and typing was over-promising in the *other* direction, offering a `y` on a junction riding a horizontal wall
  whose write did nothing at all — so `Junction.placeable(axis)` now makes typing refuse exactly as far as
  dragging does, which cost two existing tests their assertion and gained them a truer one. A fourth defect
  fell out of the same assumption: welding onto such a corner bound both coordinates to *one* of the two
  junctions' points, landing the arriving run somewhere the user never clicked.
  (2) **The rectangle: the user proposed the fix, and it was to stop having a rectangle.** *"Maybe a better
  approach is to produce the same result as the ortho-path tool would create but more easily by just setting two
  points."* Two clicks now emit `orthostart`, three `orthovertex` and `orthoclose`, so what comes out is not a
  rectangle *kind* — and every editing affordance the report asked for (drag a side, type the width and height)
  and several it did not (break a leg, attach a run, thicken to walls with openings) arrive without a line of
  code each. The load-bearing decision is **why it records steps rather than a `tool` step**: a path's degrees
  of freedom are its corner positions, which the ortho steps restate on every save, whereas a `tool` step
  restates only the clicks — so a `tool rect` rectangle would have lost every later drag on reload. The format
  needed **nothing**. The old build stays reachable for replay alone (`Tools.legacy`, still the id `rect`),
  because a stored step means what it meant when it was written; that is OP-18's frozen-literal doctrine
  applied to a *step kind* for the first time.
  (3) **The elements panel filters by space, and the rule is a partition.** *"Only show elements that are
  defined on the current sketch — the 2D elements, and the 3D-defining outlines and resulting extrusions."*
  Stated as a rule rather than a list of kinds: **an element belongs to one sketch space, except a solid, which
  belongs to none.** The 2D half needs no case analysis (an outline lives where it was drawn), and the solid
  half is not an exception but the same statement — a solid has no coordinates in any space and is shown in the
  3D viewport, so it is listed in every space. Asserted as a partition: the lists of all spaces cover the
  document, each 2D element appearing in exactly one and each solid in every one. Deliberately *not*
  `addressableIn`: what is listed is not what a click can hit, since the panel exists to reach what a click
  cannot.
  Two smaller findings paid for themselves. **`Document.linkPathEnd`** is now the one helper every joining
  route goes through (the path click, the drag magnet's release, and the rectangle's two corners) — and the
  rectangle's corners must be linked *while each is still the run's loose end*, which is both the constraint
  `orthoEndpoint` states and the order a hand-drawn path naturally has. **A closed ortho path bounds an area**,
  which the rectangle needed and every closed path now gets: the loop's identity is still read off the
  construction (a path *is* a retained ordered chain that knows it is closed), so OP-14's refusal of seed-point
  region finding is untouched. **35 new tests** (`OrthoWebFreedomTest`, `RectanglePathTest`,
  `ElementListSpaceTest`, plus two on the rectangle in `ToolCompletionsTest`), 820 green, browser E2E green.
  Reverting the per-axis delegation fails exactly the two tests about the bend and leaves the rest green, which
  is what makes the fixture a regression test rather than a snapshot.
- **Patterns as orbits (OP-23) — the user's own design, and the four things building it settled.** The demand
  arrived as a finished design: a pattern is a centre, a reference and a count, and *any later gesture whose
  inputs touch its members is replicated by index shift*. Adopted whole; what the implementation had to decide
  was everything around the rule.
  (1) **The encoding is one step per gesture, and the step is the rule.** Recording the *n* copies as *n*
  ordinary `tool` steps replays identically and was still wrong: the file would not know they were one gesture,
  so a count change would have nothing to re-run and "which steps belong to which gesture" would live only in
  the session. `orbit "P1" segment pts=e2@0,e2@1 cells=…` is the whole of the bookkeeping, in the file, and it
  brings one checkpoint (hence one undo for the whole orbit) with it for free. The member reference is written
  as an **element name plus an index** on purpose — `e2@1` is an ordinary reference as far as the delete
  cascade and the name map are concerned, so neither needed a new case, while the index says the *rule* rather
  than one of its copies. Indices are stored relative to the gesture's lowest, so the file does not record
  which copy the user's hand happened to be over.
  (2) **Clicks are the only thing transformed, and they are stored cell-locally.** Geometry must not be
  transformed — that is the point of the whole OP — but a click *states a choice*, and the corresponding choice
  in another cell is that click carried round. Storing each click carried back to member 0's cell is what makes
  a re-stamp correct rather than approximately correct: at the new count every copy's click is re-derived from
  the pattern's current shape. The scored `signs=` then need not be re-derived at all — the first copy scores
  once and the rest take its answer verbatim, which is OP-1's rule and also removes the floating-point risk of
  re-scoring a rotated click against rotated geometry.
  (3) **Mod-*n* is not a universal solvent, and the refusal was the interesting part.** The design asked
  whether a gesture could fail to re-apply at a new count "with mod-n". It can: a gesture from member 0 to
  member 4 is a *pair* at six and not a pair at three, and folding it to (0, 1) would silently draw something
  else. So the re-stamp refuses by name before touching the drawing. The other loss is honest rather than
  refused: a smaller count genuinely removes members, so a step that named one is dropped **and reported** —
  which is exactly the promised behaviour for an Alt-suppressed one-off ("stays single if its anchor index
  survives"). What made all of it implementable was that name mapping is positional, plus one wrinkle worth
  recording: a traced outline creates its joints first and the **loop last**, so its surviving names line up
  from the *end* while a pattern's and an orbit's line up from the start.
  (4) **The outline re-traces; it does not invalidate.** The offered fallback was to invalidate a boundary
  whose ring changed and let the user re-trace. It was not needed: the tracer's follow is *edit-time*
  bookkeeping (OP-14), and a re-stamp is an edit, so re-running the same follow from the step's first two picks
  is entirely in keeping with "recorded, not discovered" — a reload still discovers nothing. A hexagon
  re-stamped to eight sides comes back a closed sixteen-piece loop and a watertight solid. The honest fallback
  survives for the case where the follow does not close, with the reason and a two-click cure.
  The dividend the design predicted showed up exactly as predicted, and is the assertion worth keeping: because
  copies are built **on** the shared members rather than transformed off copy 0, adjacent sides reference one
  node, and the Outline tracer crossed every copy boundary and every fillet joint with **no new machinery at
  all** — the two-click trace of OP-14's hand-drawn triangle, unchanged.
  (5) **A review probe found the one gap, and it was the mechanical payoff case: subtractive means chain.** A
  ring of four circles on a plate's face plus one *Cut* cut **one** pocket — a face-part tool's operand is the
  part the editor resolved for it (OP-17), not a pick, so the fan had nothing to shift and the gesture applied
  once. The fix is not a new concept but the *existing* tip rule applied per index: copy *k* subtracts from what
  copy *k*-1 left, and one gesture is a bolt circle of pockets in one body. Two decisions worth keeping. A
  **solid is the one outside input a replicated gesture may touch**, because it is the body a feature is applied
  *to* rather than a geometric input that must travel with the copy — and whether the same body means a *chain*
  (Cut) or a *fan* of independent bosses (Extrude on face) is read off the tool's own declaration rather than a
  new flag, which is the per-tool table now in this OP. And the step records `part=tip` rather than *k* names:
  a single Cut names its base precisely so replay cannot re-resolve and fork, but an orbit's base is a different
  body at every count, so the only form that survives a re-stamp is the rule — a positional reference of exactly
  the kind the ortho steps have always used for "the current path", deterministic for the same reason. Volume is
  the assertion that tells a chain from a fan (four forks leave a tip missing one pocket), and it holds at four
  and again at six after a re-stamp. Two smaller things paid for
  themselves: `ToolDef.replicates` defaults to **true** (a rule with per-tool opt-in would be a rule with
  exceptions; the handful of `false`s are tools that own an absolute DOF, and measurements, which are readings
  rather than geometry), and `recording` gained an `argsAfter` hook because a gesture's `signs=` are only known
  once its first copy has been built. **18 new tests** (`PatternTest` — the acceptance flow end to end, the
  shared-node assertion, the non-invariant refusal, Alt, undo of a whole orbit, 6→8→5 with and without a
  one-off, the chained bolt circle of pockets, the linear row, and both polygon paths), plus the two review
  probes (`PatternProbeTest`), 843 green, browser E2E extended with a fifth flow
  (`patternOrbitsInBrowser`: the ring, the one gesture that becomes six sides, and the count field re-stamping
  in place).
- **Session 16 — "any line, any angle": the sketch plane stops being a special case (GitHub #6).** The user's own
  generalization, and the reason it cost so little is that it was stated as one: *"sketch-on-face is the special
  case where the line is a boundary segment and the angle is 90°."* Because OP-17 decided at the outset that 2D stays
  abstract and a space contributes only the plane its features sketch on, the whole feature is **one op**
  (`datumPlane`), one `ToolDef`, one variant of an existing step — no value type, no evaluation rule, no second
  concept, and the equivalence the user asserted is now a test rather than a claim: a 90° datum on a footprint edge
  has the same `u`, `v` and normal as `sideFacePlane`, differing from the face frame by an offset along `v` only.
  Five decisions were genuinely open, and each one had a right answer that the existing doctrine already implied.
  (1) **The frame is the base frame rotated about the line, right-hand rule.** Stated that way rather than as
  three formulas, it pays immediately: 0° *is* the space it came from, 90° stands upright, and the hinge lies in
  both planes at `v = 0` — so an angle edit turns the plane about the line the user picked and nothing else. It
  also fixes the sign question below without a second convention.
  (2) **The origin is the carrier's nearest-origin point — absolute — and a face's is not.** OP-20's anchoring
  rule, one dimension up: an origin at the picked segment's "defining start" would slide the plane's coordinates
  along it whenever the host is stretched, and the carrier's foot cannot. That is the *opposite* of the face
  frame's deliberate anchoring at the face's top edge, and the two now sit one method apart in the same file —
  which is exactly why OP-20's distinction (a host that *carries* a thing versus a host a thing is *measured
  from*) is worth having written down. The discriminating drag is asserted.
  (3) **Extrude follows +normal, Cut follows −normal, and the angle's sign flips both.** A face plane has a
  material side, so GitHub #1's rule could be stated against the material ("Cut goes in, Extrude goes out"). A
  datum has no material side, and inventing one — "the side the base solid is on" — would have been a guess in
  exactly the shape this design refuses. So the rule is stated against the datum's own normal, with the sign of
  the angle as the visible control, and mechanically the two are the same implementation: whichever operation goes
  the other way starts its sweep `depth` behind the plane, so the kernel's positive-depth rule is untouched.
  (4) **What a datum *Cut* subtracts from is a choice, and it is recorded at creation.** A face space names its
  solid; a datum names a line. The honest bridge is the construction — the newest visible solid the hinge is part
  of (ancestry, not material, which is the *opposite* of `facePartTip`'s test) — resolved once, at a moment when
  the datum's own plane does not exist yet, so a cut tool cannot be mistaken for the part it cuts, and then
  written into the step as `part=`. That is OP-18's "a scored choice is persisted at creation, never re-scored on
  replay" reaching a third kind of choice. A hinge that belongs to no solid gives a free-standing plane where
  *Extrude* works and *Cut* declines naming *Extrude* — the refusal-with-a-reason pattern, again.
  (5) **Spaces compose, and that exposed a real ordering bug before it could ship.** A datum is rotated out of the
  space it was *built in*, so unlike every earlier step the lazy `space` switch has to be written **before** it —
  and by the time the step is appended the new space is already active, so `createDatumSpace` asks for the switch
  itself. Round-tripped byte-equal with a switch back and forth in between, and asserted as a two-level chain (a
  datum on a line drawn in another datum, where the second plane's `v` at 90° *is* the first plane's normal).
  Two dividends worth recording. The **panel rule of GitHub #2 needed no case at all** — it is stated over spaces,
  not over faces, so a datum's list is right by construction — and the same is true of the delete cascade, the
  stamping seam and the space dropdown (whose label became a document query, since which spaces exist and what
  they are *of* is a fact about the model). And the tilted cut gave the **general boolean path its first exactly
  predictable assertion**: a datum on the plate's front bottom edge at 45° is the plane `z = y`, so a *Cut* that
  overhangs the part removes `plate ∩ {z ≤ y}` and leaves the triangular prism `½·20·20·80` = 16000 mm³ — a 45°
  miter, by clicking, watertight. **19 new tests** (`DatumPlaneTest`: the frame, the sign, 0°, the anchoring drag,
  both halves of the sketch-on-face equivalence, the 45° extrude, the live angle with `nodesCreated` flat, the
  tilted cut, the free-standing refusal, the two-level chain, two round trips, the delete cascade, the hinge as
  reference context, the unbounded hinge, the panel rule, the space list, and a wall leg as a hinge), 862 green,
  browser E2E extended with a further flow (`datumSketchPlaneInBrowser`: a 45° plane on a footprint edge and a boss
  on it). One thing is deliberately still cut and it is named in the queue: two solids drawn in two different
  spaces cannot both be picked by one boolean gesture, because one canvas shows one space.

- **Session 17 — "show me what the click will make", and the first tangent-circle tool.** Two items off the
  queue, and they turned out to be the same item twice: both are about a **discrete choice that used to be
  invisible until it was already stored**. Five things worth recording.
  (1) **The generalization was already sitting there, half-built.** The ortho path had a rubber band and nothing
  else did — so the work was not "add previews" but "notice that the band is a `ToolDef` member". One optional
  lambda, one call in `pointerMove`, one loop in the renderer, and twenty-four tools gained a preview with no
  controller code between them. The test of that claim is the negative one: there is no `when (toolId)` anywhere
  in the mechanism, and the LLL tool written *after* it got its preview by naming a function in the table.
  (2) **The never-touches-the-graph rule was made structural instead of trusted.** The snap resolver already had
  the rule in prose; a mechanism that runs on every mouse move needed it enforced. `PreviewContext` deliberately
  carries **no `Construction`**, and `PreviewShape` carries no `Node`, `Ref` or `Element` — so the honest
  version of "a preview must not record anything" is *a preview cannot record anything*. That is what let the
  invariant be asserted **once, generically**, over every tool that declares a preview (`nodesCreated`, the
  element list, the journal and the saved script all flat across a grid of hovers), instead of once per tool
  where the twenty-fifth would have been forgotten.
  (3) **Honesty forced two refactorings, and both were improvements on their own.** The fillet's variant scoring
  lived inside `Document` over throwaway nodes; the preview needed the *same* scoring, and a second copy of it
  would have been a formula that could drift from the one the click uses. It moved to `geom/FilletMath.kt` as
  values, and `Document` now calls what the preview calls — so "the arc you see is the arc you get" is a fact
  about the code and not a promise, and the test asserts it by comparing the previewed arc with the built one.
  A dimension's graphic needed the same treatment for the same reason (`LinearDimension.graphicOf` and its two
  siblings), since an annotation is nodes and a preview may own none.
  (4) **"From the first pick onward" is the interesting decision, and it is a cost accepted rather than a limit
  found.** Previewing with *nothing* picked is tempting — a circle of the typed radius under the cursor is
  useful — but it puts geometry under the cursor the moment a tool is armed, which reads as a click already
  made, and a tool armed by accident then paints over the drawing. So the rule is the pick, and what it costs is
  named in place: *Circle (centre, radius)* completes on its first click and is therefore never previewed.
  (5) **The tangent-circle tool is the fillet's lesson applied before it could become the fillet's bug.** Three
  lines admit four circles — incircle plus three excircles — and they are exactly the four combinations of two
  bisector branches, so the whole construction is `angleBisector` ∘ `intersectLL` ∘ `projectToLine` ∘
  `circleCP`, with **no new geometry** and tangency by composition rather than by assertion (asserted as
  `|dist(centre, lᵢ) − r| < 1e-9` on all three, before and after dragging a leg, with `nodesCreated` flat).
  The regression written *first* is the one that bit the fillet: move a leg until re-scoring the stored click
  would prefer another candidate, reload, and require the chosen circle back. **27 new tests**
  (`PreviewTest` 19, `CircleTangentsTest` 8), 892 green, and the browser E2E extended by the one thing only a
  real canvas can show — that the growing circle is painted while hovering and gone after the click, counted in
  pixels.
- **Session 18 — "a wall is a thickness applied to an arbitrary path", and the T/L junction finally falls
  out.** Queue line 1, and the user's own one-sentence spec turned out to contain the answer to a problem
  OP-21 had deferred twice. Six things worth recording.
  (1) **The junction problem dissolved rather than being solved.** OP-21 had it as "merge two wall
  footprints", tried to avoid a boolean by trimming each face at the neighbour's face line, and recorded that
  preference twice. The user's framing — *a path is a fully connected graph of points and curves; this also
  nicely defines the joining of walls* — reframes two walls that meet as **one carrier with a shared vertex**,
  and a shared vertex has no merge problem at all. What resolves it is the **cyclic order** of the incident
  tangents: the boundary walk visits a *k*-way vertex *k* times, once per angularly adjacent pair, and each
  visit is an ordinary two-wall corner. The old note's stated reason ("trim at *the* neighbour's face line")
  is what gave it away as wrong — at *k*≥3 there is no "the neighbour", and the note had quietly assumed the
  case would stay a two-body problem. Recorded as a correction under the note itself, not as a retraction:
  the *preference* it expressed (construction, not a boolean, so a corner stays addressable — OP-20) is what
  the cyclic-order rule delivers, and delivers better.
  (2) **One rule, no case per *k*.** Dangling end, ordinary corner and branch are the same line of code:
  `next(h) = the clockwise neighbour of rev(h)`. At *k*=1 the neighbour is `rev(h)` itself, which is exactly
  the end cap; at *k*=2 it is the mitre. A **figure-8** was on the candidate-cut list and came out honest (one
  outer boundary, two holes) without a line written for it — which is the usual sign that a rule is the right
  one rather than a clever one.
  (3) **The model was unified, and the geometry deliberately was not.** One retained `ThickNetwork` with a
  two-case carrier, so the plan convention, the jambs, the interval clamps, *Cut openings*, the inspector and
  the file's `opening` step stayed written once. But the **ortho case still computes its footprint with the
  same `thickFaces`/`thickRegion` it always did**. A generalized tracer that merely *happened* to agree on
  rectilinear input would be a claim with nothing to check it; reusing the code makes "every stored `wall`
  step replays identically" a guarantee, and the goldens prove it.
  (4) **The file needed nothing.** A per-curve wall side is a *discrete choice scored at creation* — which is
  what `signs=` has carried for a fillet's variant and an intersection's branch since session 14 (OP-1/OP-18).
  `tool thicken els=… signs=1;2` restates one integer per curve and replay takes them verbatim. Reaching for
  a new argument would have been reaching for a new mechanism to say something the format already says.
  (5) **The kernel is a route, not a preference — and the route is detected by signs.** Pairwise construction
  cannot express the union when two walls overlap past their adjacent pair; two exact tests catch it (a
  trimmed run with negative extent, and a ring whose **total turning** is not one full circle), and only then
  does `RegionBool` run, as a self-union of the traced rings. The turning test is the one that earned its
  place: the first implementation shipped only the extent test, and a spur folded straight back along its own
  wall slipped through it with every run forward and a doubled ring — reported by the test that asserted the
  area, which came back 2800 instead of 2000. Taking the kernel route **demotes the footprint to OP-15's
  approximated class**, and that is carried in the type (`ThickBody.approximated`) rather than in prose.
  (6) **The key points had to be *extracted*, not owned.** "A wall should show and use its key points" reads
  as an accessor per corner created with the wall — and that is defect 1 of the original wall implementation
  in a new costume, because the corner *count* is a function of the carrier's values. The honest exposure is
  the one already in the toolbox: the *Key points* tool takes a footprint, the count is structural per
  extraction (as it is for a Bézier's controls), and a corner that is gone says so instead of pointing
  somewhere else. Same bargain as every other structural count in the file, and it is a real point
  afterwards — snappable, dimensionable, weldable — which is the whole of what "use its key points" asked
  for.
  (7) **A review probe found two defects, and both were on the wall's side of the seam** — see *Two defects
  a review probe found* under the extension. The second is the one with a lesson beyond this feature: OP-21
  had recorded the cutter sharing the wall's faces as an unqualified virtue ("the degenerate case the kernel
  is built to handle honestly"), and it *is* — for a straight wall, where the shared face is one exact line.
  On a curved wall the two faces are two independent tessellations of one arc, which is **near**-coincident,
  and near-coincident is the worst input any kernel can get. The cutter now overhangs where the face is not
  exact. Diagnosing it took dumping the 2D subtraction and counting rings — twelve where there should be
  two — which is the argument for the kernel being a *value-level function* one can call from a scratch test
  rather than a stage buried in the mesher.
  **20 new tests** (`ThickNetworkTest`) plus the review probe's two, 916 green, ktlint clean, the browser
  E2E unchanged and still passing — the last of which is the load-bearing number, because the whole ortho half of the feature was
  refactored underneath it and none of it was supposed to move.
- **Session 19 — the OP-5 dirty-marking, kept: a persistent memo keyed on argument identity.** The last
  queue line, and the oldest debt in the file: *"topological eval with dirty-marking … outputs are cached"*
  has been in OP-5 since turn 4, while the implementation memoized *per pass* and threw the results away
  between repaints. The user's report is the shape of that debt — a revolve re-tessellating on every mouse
  move, and the *2D* view lagging with it, because the 2D pass evaluates a solid element too. Five things
  worth recording.
  (1) **The design brief named two schemes and the right one was a third.** A global version stamp is
  correct and useless (it flushes the document on every drag frame, so an untouched revolve recomputes
  anyway); forward invalidation over a reverse-dependency index is the textbook answer and is precisely
  what this graph must not have, because `boundTo` re-pointing **changes the shape of the DAG** and the
  index would need updating at the four places — weld, attach, capture under a frame, parameter wiring —
  where being wrong means *wrong geometry*, not a slow repaint. What shipped keys the memo on **the
  identity of the argument values themselves**, so the freshness test re-reads the very edges the result
  depends on. There is nothing derived to keep in step, which is why the dangerous case needed no code:
  `SourceNode.inputs` *is* `boundTo`, so a weld changes the arguments from none to one and the memo misses
  on its own.
  (2) **The dirty mark needs no dependents.** A mutation invalidates the one node it wrote; that node
  recomputes and hands out a **new value object**, which misses its consumers' memos, and theirs — the mark
  walks the affected cone by itself and stops. The five setters (`SourceNode.value`/`.boundTo`,
  `ParameterNode.literal`/`.boundTo`, `IndirectNode.boundTo`) are the complete list of mutation points, and
  they are needed only for the case where the *arguments* do not change: a literal write on a free source.
  (3) **Invalidity is deliberately not memoized, and that is OP-3 rather than an oversight.** A node can be
  invalid for a reason its arguments do not carry — the Manifold WASM still arriving is the standing case —
  and the promise is that it heals on the next pass. Retrying every invalid node is what keeps it; the
  browser E2E's drill-through-a-face test is the one that would have caught the alternative.
  (4) **One conservative opt-out, and it is named where it lives**: a macro `InstanceNode` over a
  definition *source* runs somebody else's `compute`, which reads a literal this instance's arguments do not
  carry, so it never memoizes. The regression is that retyping a captured default still reaches every
  instance.
  (5) **The regressions were written for the re-pointings first**, before a line of the memo: weld/unweld,
  make-relative/make-absolute, attach, freeing a rider (the `IndirectNode` re-point), placing and unplacing
  a group and a wall path, wiring and unwiring a parameter — each *through a warm cache*, which is the only
  state in which the bug could exist. **14 new tests** (`IncrementalRecomputeTest`), **930 green**, ktlint
  clean, the JS bundle built and the browser E2E still passing. The numbers the acceptance asked for: 100
  repaints of an untouched turned part → zero recomputes; a 20-step drag of an unrelated point → the
  revolve's counter unmoved; a sweep edit → exactly one; and the evaluation half of a render pass on a
  four-revolve part (38 nodes, 1916 triangles) **~2.5–3.2 ms → under 0.01 ms**, 300–450× run to run.

- **Session 20 — the five-part UI-polish item: the shell finally says what the graph already knew.** Not one
  of them is a construction question, which is exactly why they had queued up: each is a small tax the
  application charges every session, and none is urgent on any given day. Six things worth recording.
  (1) **"Which point is this circle's midpoint?" is a question about the DAG, and the DAG could always
  answer it** — nothing published the answer. What took thought was not the walk but the *depth*: direct
  inputs reports nothing for a fillet (its arc consumes derived nodes nothing displays) and the whole cone
  reports the drawing. The rule that works is *stop at the first node an element displays*, and it is worth
  stating as a rule because it is what makes the answer **composable**: a fillet reports its two legs, and
  asking a leg reports its points, so the user walks the construction one honest hop at a time instead of
  being handed a cone. Two things then fell out rather than being built — the reverse arrow is the *inverse*
  of the same relation, so "used by" and "built from" can never disagree; and welding needs no case at all,
  because binding is an in-place re-point and the alias' node simply has an input (OP-5 paying off in a place
  it was not designed for).
  (2) **The graph decides membership, the step decides the words.** `ToolDef.slotNames` makes the row read
  `centre e4, radius point e5`, but the *set* is never the step's: an extrusion of a rectangle is built from
  four legs and exactly one of them was clicked. Keeping those two sources apart is what stops the feature
  from becoming a prettier journal.
  (3) **The icon palette's real risk is not drawing badly, it is drawing ambiguously**, so the rule is that a
  glyph shows the *operation* — a fillet is two legs and the ghost of the corner the arc replaced — and a
  tool whose operation has no picture keeps its text row. 60 of 77, and the 17 are named in the note. The
  bug it produced is the one to remember: an `SVGElement` is **not** an `HTMLElement`, so the palette's click
  delegation dropped every click that landed on a glyph — the entire palette, dead, and every unit test
  green. The browser E2E caught it in one run.
  (4) **A fixed-height inspector is a one-line CSS change and a real defect fixed.** The panel is one column
  and the inspector's height depends on the selection, so every click moved the element tree under the
  cursor. The assertion is the honest one and only a browser can make it: `#tree`'s viewport position is the
  same number with nothing selected, with something selected, and after deselecting.
  (5) **Renaming groups and elements needed no new mechanism — and it found an old bug.** Both follow OP-7's
  rule (*what may be renamed is exactly what the file names*) and the parameter's restate-at-save pattern. But
  a group is named in two steps, and the writer resolved the second, `place`, **by name**; with the name
  changeable, that would have silently stopped restating the frame and lost a placed group's position on the
  next save. It is now resolved by step identity. Patterns were checked and are genuinely unaffected: their
  names are in their own namespace and no pattern step names a group — consistent rather than lucky, since
  patterns are not renameable and so their names are not state.
  (6) **The scale bar cost almost nothing because the rounding rule already existed twice.** `niceLength` is
  now shared by the 2D grid, the 3D ground and the bar, which is what guarantees a bar and the grid under it
  round the same way; and the bar is a renderer overlay switched on by the shell exactly as the grid is, so
  the existing goldens are untouched and one new golden records the overlay itself.
  **20 new tests** (`DependencyViewTest`, `ElementNameTest`, `GroupRenameTest`, `ScaleBarTest`, and a sixth
  browser flow, `panelPolishInBrowser`), **932 → 952 green**, ktlint clean, the JS bundle built and all six
  browser E2E flows passing.

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
  - **As built, and not as a second concept:** a project frame *is* a placed group (OP-16). Grouping a
    run of walls and placing it re-reads the path's coordinate nodes as the group's local ones, so the
    frame's angle field turns the building while every leg stays axis-aligned in the group. There are no
    named axes and no axis references — the frame carries the geometry, which is one mechanism instead of
    two. See OP-16's *ortho paths and walls under a frame*.
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

> **Correction (OP-22):** a 2D region boolean kernel now *does* exist — the 3D booleans needed one, and
> it turned out to be the small part of that work rather than a solver-adjacent detour. The preference
> above still stands for a *junction*, and for the same reason as before: trimming by construction keeps
> the corner's freedom addressable (OP-20) and the drawing editable, whereas a unioned footprint is one
> opaque area whose corners nothing can grab. A boolean is the honest answer for a **solid**, not for a
> plan the user still has to edit.

> **Second correction, and the one that mattered (the generalized-wall extension, below).** The note
> above is *right about the preference and wrong about the reason it gave for it*. "Trim each wall's face
> at the neighbour's face line" only ever describes **two** walls; at a vertex where three or four meet
> there is no "the neighbour", and the note quietly assumed the T/L case would stay a two-body problem.
> It does not. What replaces it is not a boolean and not a pairwise trim either: it is the **cyclic order**
> of the curves incident at the shared vertex, which turns *k* walls meeting into *k* independent
> two-wall corners — see *Branch vertices resolve by cyclic order* below. That construction is exact for
> lines and arcs, keeps every corner a constructed point (so OP-20's freedom stays addressable and the
> drawing stays editable, which is what the preference was protecting), and needs the kernel for nothing.
> The kernel is kept as a **fallback for the one case the pairwise construction cannot express** — sides
> chosen so that material genuinely overlaps beyond the adjacent pair — and taking it is a *demotion to
> OP-15's approximated class*, which is stated where it happens. So: pairwise by construction where it
> can, the kernel where it must, and the file records which route a given footprint took only in the
> sense that the same parameters always take the same one.

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

**Deliberately not done here** *(both closed by the generalized-wall extension below — kept because the
shape of the deferral is what the extension answered)*. Wall-to-wall **junction trimming** (T/L merges)
stays future work: two thick paths meeting still overlap, which is visible and known. Per OP-6 **accessors**
on the footprint (`face(side)`, `corner(i)`) are not built — nothing consumes them yet, and they are what
dimensioning and wall-to-wall snapping will need. One capability was traded away with the loose face segments: a
wall face is no longer a `SEGMENT` element, so it cannot be snapped or attached to. That is the correct
direction (OP-21's whole point is that offset lines are not the drawing), and the replacement is those
accessors, not the old bundle. **3D is now built** (OP-22): the *Cut openings* tool turns each interval
into a subtracted box, sill→head, over the wall's full thickness, wired to the interval's own parameters —
`extrude(footprint, height)` minus one box per interval, exactly as designed here.

### Implementation status (as built — openings are grabbable at their jambs)

The interval's two degrees of freedom were typed-only; now they are **dragged where they are drawn**, which
is OP-13 enforced rather than extended (a DOF reachable only by number is a bug in the model, and `pos` /
`width` were exactly that). Nothing new is stored: no element, no node, no step kind.

- **Two jambs, two handles, and that is what answers "which end moves?"** The *leading* jamb writes `pos`
  and slides the whole opening — the width is measured **from** the position, so it is preserved for free.
  The *trailing* jamb writes `width = cursor − pos`, so the leading edge stays. There is deliberately no
  gesture called *drag the width*: as with a leg's length (OP-13), the quantity spans two edges and the
  field belongs to the handle that moves. Both are 1-DOF along the leg, the cursor projected onto the
  carrier leg's direction.
- **A drawing carries a handle.** The jambs `planOf` derives each pass are now *pickable*:
  `Document.jambsOf` returns the same reveal lines the plan emits, tagged with the interval and the edge
  they belong to, and `HitTest.nearestJamb` measures to them with the one distance rule everything else
  uses. A hit resolves into a `JambHandle` over the interval's existing parameters — the ortho-leg pattern
  one level further, since a leg at least *has* an element and a jamb has none. Consequently there is
  nothing to keep in sync: re-sorting the drawing, moving the carrier or reloading the file all produce
  fresh jambs from fresh values.
- **The pick competes by distance rather than by precedence.** A jamb crosses its own carrier leg, so any
  fixed ranking would make one of the two unreachable near the crossing. Vertices still win outright
  (most specific), and then the curve/annotation searches are capped at the jamb's distance: *along* the
  wall the leg is nearer, *across* it the jamb is — which is how the drawing reads. A jamb also outranks a
  placed group's frame (the one exception to OP-16's "a selected group moves as a whole"), because
  otherwise the same wall would behave differently depending on how it had been reached; the gesture says
  out loud which of the two it took.
- **Selection with no element.** `Editor.selectedJamb` is a selection that owns no `Element` — the
  inspector shows the interval's `position`, `width`, `sill` and `head` (one stable order for both jambs;
  which node the *drag* writes is `dragNodes`' business, not the panel's), and the canvas emphasizes the
  opening's own drawing: two jamb lines plus the gap span on either face. Deliberately *not* accompanied
  by selecting the wall, or Delete would remove a whole wall after a click that pointed at one opening.
- **Clamps, said out loud.** `pos` is held in `[0, legLength − width]` and `width` in
  `(0, legLength − pos]`, with a floor of 1 mm: a width of zero is where the two jambs *meet*, and beyond
  it they cross — which is not a narrower opening but a broken drawing, so it is refused. Clamping rather
  than ignoring keeps the gesture reversible (dragging back out grows the opening again from where it
  stopped). The clamps live in `Document.setIntervalPosition` / `setIntervalWidth` precisely so a typed
  number is bounded by the same rule as the drag and reports the same reason — otherwise "drag and type are
  one operation" would hold only until a value went out of range.
- **Positions stay LEG-RELATIVE, and this is where that pays.** The recorded exception to absolute
  anchoring (see the anchoring table) is what makes a jamb drag need no case of its own: a distance along a
  leg is exactly what a rigid placement preserves, so an opening in a **placed, turned** group drags in
  world space through the very same projection, and stretching the carrier leaves the opening where the
  plan says it is. A jamb drag is an ordinary literal write inside a gesture, so the release checkpoints it
  as one undo step and the `opening` step restates `pos` (and `width`'s own `param` step its value), giving
  save→load→save byte-equality after a drag with no format change at all.
- **3D follows live.** The *Cut openings* boxes are wired to the same parameters, so dragging a jamb
  recomputes the cut: sliding moves the reveals and cannot change the volume, widening removes exactly
  `Δwidth × thickness × height` more, and the mesh stays manifold throughout (asserted).

**Deliberately not done here.** **Delete cannot reach an opening**: an interval owns no element and the
selection-to-step route runs through elements. The journal half already works (an `opening` step is
independently droppable, and the cut chain rebuilds with one box fewer), so this is a missing route, not a
missing capability — and pressing Delete on a selected opening now *says* that instead of doing nothing.
Also not done: a hover cue on a jamb before pressing (picking is only consulted on press), and any
snapping of an opening to the carrier's corners or to another opening.

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
    - **"Extending" was a true message about a wrong graph.** Clicking the connected end of a T-web's
      middle run reported *extending this path* — the message for a dangling end. The click handler was
      right: that end really was still dangling, because the attach that should have connected it had been
      refused, and refused in *silence* (OP-20's determined meeting point). Fixing the attach fixed the
      transition; the message was never the bug, and a genuinely dangling end still extends.
  - **A run that ends on something says so, and shows it.** Reaching other geometry finishes the run (see
    *Snapping while placing*), which used to be announced by one quiet status line — so a click meant as an
    intermediate corner stopped the drawing unnoticed, and the *next* click, which then started a fresh
    path, read as nothing having happened. The terminus is now **marked on the canvas** (nested diamonds in
    their own colour — `SceneRenderer`'s `terminal`) and the wording ends *"the run is finished; click a
    point or leg to start the next one"*. One mark for **every** way a run can end on something — welded,
    attached, closed — because what has to be noticed is that drawing stopped, not which construction
    stopped it; `Editor.markTerminal` therefore reads it off the finished path rather than off the gesture
    that finished it. It lasts until the user's next *action* (a press or a key, deliberately **not** a
    hover: the mouse always moves right after a click, and a mark a stray move erases is not a mark), which
    is also what makes it the durable half of the signal — the status line is transient by design, a hover
    handing it to the snap label. The rubber band is the other half, and needed no change: a band means
    "still drawing", and a terminal click leaves none.
  - **No route refuses in silence.** A connection the editor will not make explains itself on *every* route
    that can bind (OP-20's rule, extended to the drawing click): `Document.connectRefusal` owns the reason
    so the drag magnet, a release and a path click say the same thing. The drawing route was the one that
    still refused silently, and it is the worst place for it — the click looks exactly like the end of a
    run, so nothing joining and nothing finishing reads as the tool being broken rather than as the model
    saying no. Such a click now keeps the run growing (band and all) and says why the join failed.
  - **A grab holds its offset.** Picking has a tolerance, so writing the cursor's position outright made
    geometry jump to it on the first move; the drag applies the offset from where the grab landed
    instead. A repeat click on the growing end is ignored for the same family of reasons — it is the
    second half of a double-click, and used to leave a hairline segment behind before the path finished.
  - **The closing edge is previewed as it will be.** Closing *moves* a corner — binding its own
    coordinate to the start's is what makes the closing leg axis-aligned — so hovering the start shows
    the leg into that corner *already aligned* plus the closing leg, rather than a band reaching for the
    start that promises a different shape. The drawing no longer appears to jump on close.
  - Rubber-band preview; Esc / double-click / click-start to finish.
  - **The rectangle tool draws one** (as built, on GitHub #4). Reported as *"the rectangle produced by the
    rectangle tool is almost non-editable — when dragging its free points, they move only along one axis"*,
    with the user's own proposal attached: *"produce the same result as the ortho-path tool would create but
    more easily by just setting two points. This would also allow setting the width and height precisely."*
    Two diagonal clicks now emit `orthostart`, three `orthovertex` and `orthoclose` — literally the steps the
    ortho tool records — so the result **is not a rectangle kind**. Everything the path machinery offers
    arrives with it and none of it was written twice: every corner drags on both axes (OP-20), every *side*
    drags across itself, either side's length is a numeric field of its leg (OP-13), a leg breaks and joins
    (OP-19), a run attaches to it, and it thickens into walls with jamb-ready openings (OP-21). The old build
    was rectangular by construction too — but its two clicked corners were free *points* and the other two
    derived one coordinate from each, so what a drag of a corner could reach was one axis at a time, which is
    exactly what the report said.
    - **Replay is the reason it records steps rather than a `tool` step.** A path's degrees of freedom are its
      corner positions, and the `orthostart`/`orthovertex` steps *restate* them on every save; a `tool` step
      restates only the clicks that started it, so every later drag of a corner or a side would be lost on
      reload. `ToolDef.recordsSteps` says a build emits its own steps and is therefore not wrapped
      ([Editor.maybeCompleteTool]); one checkpoint still covers the whole gesture, as it does for a break that
      emits several steps. The **file format needed nothing** — no new kind, and no way to tell the two
      gestures apart.
    - **The old build stays reachable, for replay only.** A stored step means what it meant when it was
      written (OP-18), and every existing file carries `tool rect` — whose element count the loader checks. So
      `Tools.RECTANGLE_V1` keeps the id `rect` and `Document.rectangle`, resolved by `Tools.byId` through a
      `Tools.legacy` list that is deliberately *not* part of `Tools.all`: nothing in the palette can arm it and
      no new file can name it. The live tool took a new id (`rectpath`), which is the one place the change is
      visible outside the drawing — a tool's button id is its tool id, so the browser E2E's selector moved.
    - **Two clicks that land on geometry join it**, exactly as an ortho-path click does — and each link is made
      *while its corner is still the run's loose end*, which is the one thing a connection asks for. That is how
      a rectangle can still be driven by a measured point (the papercraft-net flow in `SolidMeasureToolTest`).
      `Document.linkPathEnd` is now the single helper every joining route goes through — the path click, the
      drag magnet's release, and these two corners — and `Picks.landings` carries what each click landed on,
      resolved at click time and recorded by nothing, because a connection is recorded by its own step.
    - **A closed ortho path bounds an area** (`Document.boundaryPiecesOf`), so the rectangle still extrudes with
      one pick. The same rule as "a closed chain one step built", read off a different record: a path *is* a
      retained ordered chain and it *knows* it is closed, so the loop's identity still comes from the
      construction and nothing is discovered (OP-14 still rejects seed-point region finding). It generalises
      for free — any closed ortho path now extrudes without first tracing an outline over it.
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
- **Next (architectural):** ~~wall-to-wall junction cleanup (T/L merges — two thick paths meeting still
  overlap) and footprint accessors for dimensioning and wall-to-wall snapping (OP-6)~~ — **both done**, and
  not as "merging two walls": see *Walls over arbitrary curve networks* above. Walls that meet are one
  carrier with a shared vertex, resolved by cyclic order; the accessors are the *Key points* tool taking a
  footprint. What is still true is the narrower statement: two **separate** thick networks that cross still
  overlap. **3D walls are
  done** — extrude plus a boolean-subtracted box per interval, sill→head (OP-22, the *Cut openings*
  tool). Note the ordering decision in OP-17: 3D walls were a *later application* of the seam, not its
  proof of concept — the first 3D slices are mechanical parts, because a wall exercises only the
  degenerate half of the seam.

### Walls over arbitrary curve networks (the OP-21 extension — RESOLVED)

The user's sentence is the whole specification, and it is a better one than the queue line it replaced:

> *A wall is a thickness applied to an arbitrary path, where a path is a fully connected graph of points
> and point-connecting curves (segments, arcs, béziers). This also nicely defines the joining of walls.
> Each curve should allow to define the wall side (left/right/center). And a wall should show and use its
> key points.*

Four claims, and the third and fourth are the ones that pay. "*This also nicely defines the joining of
walls*" is the T/L junction cleanup OP-21 deferred twice: two walls that meet are not two footprints to
be merged, they are **one carrier with a shared vertex**, and a shared vertex has no merge problem at
all. And "*a wall should show and use its key points*" is the OP-6 accessor cut in the first slice.

#### One model, two constructor cases

`ThickPath` is renamed **`ThickNetwork`** and gains a `carrier`, which is a two-case sealed type:

| case | what it is | footprint node |
|---|---|---|
| `ThickCarrier.Ortho` | the rectilinear polyline the *Wall* tool draws — vertices, closed flag, **one** justification for the whole run | `Construction.thickFootprint` (unchanged) |
| `ThickCarrier.Network` | a connected subgraph of ordinary curve elements, **each with its own side** | `Construction.thickNetworkFootprint` (new) |

**Unified now, and deliberately not by delegation.** Keeping two retained types would have doubled
every consumer that does not care which carrier it has — the plan convention, the jambs, the interval
clamps, `Cut openings`, the inspector, the pick cycle, the file's `opening` step — and each of those is a
place a second type could drift. So there is one retained class, one `Document.thickNetworks` list, one
`Jamb`, one `JambHandle`. What is *not* unified is the geometry underneath, and that is the point of the
two cases: an ortho carrier keeps computing its footprint with the same `thickFaces`/`thickRegion` it
always did, so **every stored `wall` step replays to the identical region, byte-identical goldens
included**. A generalized tracer that merely *happened* to agree on rectilinear input would be a claim
this codebase has no way to check; reusing the old code is a guarantee.

The seam between the two is one value type, `ThickBody` — the legs, the joins, and the region — which
both cases produce and everything above consumes:

- **`ThickLeg`** is one carrier curve resolved to values: the oriented piece, its **arc length**, its two
  signed face offsets, and the two *trimmed* offset runs (corner to corner). `pointAt`/`dirAt`/`facePoint`
  are arithmetic on it, so a position along a leg means the same thing whether the leg is a straight
  ortho run, a concentric arc or a sampled Bézier.
- **joins** are the boundary pieces belonging to no leg: an open end's cap, and the straight *step* where
  two offsets cannot mitre. The ortho case's two end caps are joins, which is why `planOf` needed no
  case of its own.

`planOf` therefore now returns `List<ProfileElement>` rather than `List<Segment>` — a curved wall's plan
is drawn with arcs, not with a barrel of chords.

#### Offsets per curve kind — and where the honesty line is

| carrier kind | offset | class (OP-15) |
|---|---|---|
| segment | the parallel segment at the signed offset | **exact** |
| arc | the **concentric** arc, radius `r ∓ o` by the sweep direction | **exact** |
| cubic Bézier | sample at the ordinary tessellation parameters, displace each sample along the curve's **exact** normal there, keep the result as a polyline | **approximated** |

A Bézier's offset is not a Bézier and never was (OP-15 records exactly this for spline offsets), so a
Bézier carrier produces an offset **polyline** and the footprint containing it is in the approximated class
from that point on. What is worth being precise about is *where* the approximation lives, because it is not
where "offset by tessellation" suggests: the samples are displaced along the curve's own derivative, so the
drawn offset is **exact at every sample parameter** and approximate only in the chords between them —
the identical bargain every tessellated curve in this engine already makes, and the thing the test asserts
(a sample's exact offset is a drawn corner, to 1e-9). The same holds for a *position along* such a leg: the
arc-length→parameter map is the sampled part, and the point and normal it then hands back are the curve's
own, so a jamb on a Bézier wall sits exactly half a thickness off the curve.

This is not hidden behind a tolerance argument: `ThickBody.approximated` says so, and the *Thicken* tool's
status line says so at the moment a Bézier is picked. An arc wall stays exact — its footprint holds real
`ArcE` pieces, extrudes to a real swept surface, and its area is the analytic one.

An arc thicker than twice its own radius has no inner offset at all (the concentric radius goes
negative). That is invalid **with a reason** and it heals (OP-3), like every other degenerate carrier.

#### Side per curve

`Justification` is unchanged — left is the +90° side of the curve's **own** direction — and it is now
stored **per carrier curve** instead of per path. The tool option (the browser panel's *Wall side*)
applies to the **next pick**, so a run is drawn by setting the side and clicking, changing it and
clicking again.

**It needed no file format change**, which is the part worth recording. A per-curve side is a *discrete
choice scored at creation*, which is precisely what `signs=` already carries for a fillet's variant and
an intersection's branch (OP-1/OP-18): the `tool thicken els=… signs=0;1;2` step restates one integer
per picked curve and replay takes them verbatim. Reaching for a new argument would have been reaching
for a new mechanism to say a thing the file already knows how to say.

#### The boundary is a walk of the fat graph

Everything below happens **inside the footprint node's `compute`** (OP-21's rule): which endpoints
coincide, and therefore what the graph even *is*, depends on where the carrier currently is. Only the
*count* of carrier curves and their sides are structural. Dragging two carrier ends apart makes the
footprint invalid with a reason rather than silently wrong.

1. **Weld** the curve endpoints into vertices on a `JOIN_TOL` lattice, and check the graph is
   **connected** — a disconnected pick is refused, by name, before a node is built and again inside
   `compute` if an edit pulls it apart.
2. Each carrier curve becomes two **directed half-edges**. A half-edge's *left wall* is one offset curve:
   the forward half-edge's left wall is the `+` offset, the reverse half-edge's is the `−` one negated.
3. At each vertex sort the outgoing half-edges by the **angle of their outgoing tangent**.
4. The walk: travel a half-edge's left wall; on arrival at vertex *v* along `h`, the next half-edge is
   the neighbour of `rev(h)` **clockwise** in that cyclic order. This keeps material on the left, and it
   is the whole junction rule.
5. Between the arriving wall and the departing wall, the corner is the **intersection of their carriers**
   nearest *v* — `intersectLL` for line/line (the existing mitre), `intersectLC` for line/arc and
   `intersectCC` for arc/arc (the fillet work's machinery, reused), and the infinite line of the terminal
   offset segment for a Bézier polyline. Where the two carriers do not meet at all, the two wall ends are
   joined by a straight **step** — which is the same construction as an end cap, and is what a dangling
   end (*k*=1, where `rev(h)` is its own neighbour) produces.
6. The walk decomposes into rings. It keeps material on its **right** (a half-edge's left wall is the
   boundary the material lies *under*), so every ring comes back with the opposite handedness to OP-14's
   convention and they are all flipped together — which is what leaves the outer/hole split a question of
   sign. One positive ring is the outer boundary; the negative ones are its holes.

Note what step 5 does **not** do: it never refuses a corner. The old ortho code declares "corner *n* has
collinear legs, so no mitre" and goes invalid; here two offsets that do not meet are simply joined by a
straight step, because on a general carrier that configuration is a real plan — it is what a wall changing
side part-way through a straight run looks like, and refusing it would refuse a drawing rather than a
degeneracy. (The ortho case keeps its refusal, because there the same configuration *is* one.)

##### Branch vertices resolve by cyclic order

Step 4 is the answer to the question OP-21 kept deferring, and it does not special-case *k*. At a vertex
with *k* incident curves the walk visits it *k* times, once per angularly adjacent pair, and each visit
resolves one ordinary two-wall corner. **A T-junction of three walls is three mitres, not a union**, and
the result is one region with no interior edge and no sliver, because no interior edge was ever
constructed to be removed. A cross of four is four. A ring is the *k*=2 case everywhere, which is why the
closed carrier still comes out as `Region(outer, [inner])`. A **figure-8** — two rings sharing one vertex,
*k*=4 there — comes out as one outer ring with two holes, honestly, and is not refused: it was on the
candidate-cut list and did not need to be cut.

##### When the kernel is used instead, and what that costs

Pairwise construction cannot express a union in exactly one situation: two of the walls overlap past what
the corner between their adjacent pair resolved. Two **signs** detect it — signs, not tolerances, which is
what makes the choice of route a deterministic function of the parameters:

- a trimmed offset run with **negative extent** (a corner landing behind where the run started — a leg
  shorter than the mitre its neighbours demand), and
- a traced ring whose **total turning** is not one full circle. A simple closed curve turns through exactly
  ±2π; a ring that has run over itself does not. This is one pass over the pieces (each arc's own sweep
  plus the exterior angle at each corner), needs no tessellation and no pairwise intersection test, and it
  is what catches the case the extent test misses — a spur folded straight back along the wall it came
  from, whose runs are all forward and whose ring is nevertheless doubled.

Either sign takes the second route:

> tessellate the traced rings, `RegionBool.combine(rings, rings, UNION)` — the nonzero-winding interior
> of the tangle, which is what the union *is* — and nest the result.

Also routed there: a trace that comes back with **more than one positive ring**, which a connected fat
graph cannot honestly produce and which therefore means the same thing. If the kernel then yields more
than one region the footprint is genuinely several disjoint areas and it is **refused by name** ("thicken
them separately") rather than silently returning one of them.

The cost is stated where it is paid: the kernel is polygonal (OP-22), so **a footprint that took this
route is approximated even if every carrier was a line or an arc**. `ThickBody.approximated` carries it.
This is the reconsideration OP-21's junction note asked for, resolved by *route*, not by preference:
construction where construction can, the kernel where it cannot, and a name on the difference.

#### Key points — extracted, not accessorized

The user asked for a wall to *show and use* its key points. The tempting reading — one accessor element
per footprint corner, created with the wall — is the thing OP-21 exists to forbid: the corner **count** is
a function of the carrier's *values* (a mitre that degenerates, a step that appears, a kernel route that
retessellates), so a set of elements sized by it would have to be regenerated on every edit, which is
defect 1 of the original wall implementation wearing a different hat.

So the exposure is the one the model already has a shape for: **the existing *Key points* tool now
accepts a footprint** — its slot widened from `CURVE` to `EXTRACTABLE`, "anything whose defining points can
be materialized" — and extracts the corners of the region *as it stands*, each as a
`Construction.regionCorner(footprint, i)` accessor — an OP-6 provenance node, a real point element,
therefore pickable, snappable, dimensionable and welded-to like any other point. The count is
**structural per extraction**, exactly as it is for key points on a fillet or a Bézier's controls today:
extract, and you get the corners there are now; change the carrier so there are fewer, and the surplus
accessors go invalid **with a reason** (OP-3) instead of silently pointing somewhere else. That is the
same bargain OP-18 strikes for every structural count in the file, and it is the honest one — the
alternative is a live count, which is queue item 2's territory and not this one's.

Jambs already demonstrate the other half (a *drawing* that is pickable without being an element), and
they are unchanged: an opening's two reveal lines are still derived per pass and still resolve into a
`JambHandle`.

#### The tool

*Thicken* is the repeating-slot tool *Outline* already is, with two declarations added to the table rather
than any controller code: `minPicks = 1` (a single curve is a perfectly good wall, where a single curve is
not a boundary) and `sidePerPick`, which is what collects the wall side in effect at each click. Two things
were separated out at the same time, because *Outline* had been the only repeating tool and its behaviour
had been the definition of one:

- **`followsBoundary`**, now declared rather than assumed. Auto-appending the pieces that merely continue
  is right for tracing a boundary and wrong for a wall: which curves a wall runs over is a *choice*, and a
  follow would build a wall the user did not draw.
- **`minPicks`**, likewise, instead of the hard-coded "needs at least two curves".

It draws a **live preview** (`Previews::thicken`) like the other twenty-four: the actual footprint of the
curves picked so far plus the one under the cursor, computed on values, drawn as itself. A pick that would
disconnect the network simply previews nothing — and the click then says why.

#### Openings on any curve kind

An interval's `position` is a distance **along its leg's own arc length**, which is what it always was —
the leg was just always straight. Nothing in `PathInterval`, the clamps, the `opening` step or the jamb
handle changed:

- `positionAlongLeg` projects the cursor onto the leg and returns arc length (the angle about the centre
  times the radius for an arc; the cumulative polyline length for a Bézier),
- a jamb is `Segment(facePoint(d, 0), facePoint(d, 1))` — for an arc leg that is the **radial** line, which
  is what a plan draws,
- dragging a jamb on an arc wall therefore slides the opening **along the arc**, with no case of its own.

Leg-relative positioning is a *recorded* exception to absolute anchoring (see the anchoring table under
OP-20), and this is the second time it pays: the same reason a rigid placement leaves an opening alone is
the reason a curved leg needs no new parameterization.

#### 3D needs nothing

A footprint is a region, and a region extrudes (OP-17). An arc-walled ring extrudes watertight because
the caps and the side walls come from the *same* tessellation (OP-9's structural watertightness), and
*Cut openings* works on a curved leg because the interval's plan shape is derived from the same
`ThickLeg` — for an arc leg, two concentric arcs closed by two radial segments.

#### Two defects a review probe found, and where they were

Both were on the **wall's side**, not the kernel's — worth recording because the second one is a general
rule about booleans that this codebase had only ever met in its benign form. The probe: a
segment–arc–segment wall (t=8), a door on the *arc* leg, extruded and cut. It came back with a **cracked
shell** (`edge 111->112 used 2 times`) through the *exact* prismatic path, which must never emit one.

**1. The reversed-run test over-triggered on arcs, and quietly demoted exact walls to the kernel.** The
tangle test asked whether a trimmed arc run covered *more* of the arc than the arc has. But a mitre
legitimately pushes a corner **past** the carrier's own end — a straight run's does too, and there only
*reversal* is tested. On a 180° arc meeting a straight leg at a right angle the mitre extended the run to
197°, which read as an overlap; the whole footprint took the kernel route, and came back tessellated
(`approximated`, and reported with the *Bézier* wording, which was not even true of it). The fix measures
the run's own direction instead, placing each corner at the representative of its angle nearest where it
nominally belongs — exact for any mitre short of half a turn, and immune to the wrap that made 197° look
like an overlap. The wall's faces are exact arcs again.

**2. A cutter may share a face with the wall only when that face is exact.** This is the general rule, and
it is the one OP-21 had stated as an unqualified virtue: *"the two faces are the wall's own faces, so the
subtraction's side walls are coplanar with the wall's — the degenerate case the 2D kernel is built to
handle honestly."* True on a straight leg, where the two faces are literally the same line and the kernel's
shared-edge rule resolves them exactly. **False on a curved one**, where the wall's face and the cutter's
face are two *independent tessellations of one arc*: not coincident but **near**-coincident, crossing each
other once per chord. The kernel did exactly what it should — it returned a crescent for every crossing —
and ten slivers of 2×10⁻⁶ to 2×10⁻² mm² became ten sub-slabs, one of which was too thin to triangulate
into a shell. Diagnosed by dumping the 2D subtraction: twelve rings where there should be two.

The fix is not to special-case the box and not to loosen the kernel: on a curved leg the cutter stops
pretending and **overhangs** (`ThickLeg.cutterOffsets`), its long faces clear of the material on both
sides by ten tessellation tolerances, so the only faces that cut are the two jamb faces — and those are
genuinely transverse. The removed volume is unchanged, because the overhang is outside the wall (asserted).
The 2D subtraction now returns exactly the two rings it should, and the straight-leg case is untouched:
its coplanarity is exact, so it keeps it.

> **The rule, stated so it is not re-learned:** near-coincident faces are the worst input a boolean kernel
> can get — strictly worse than either coincident or clearly separated. Exact coincidence is a *choice a
> construction can make* only when the shared geometry is exactly representable. Where it is not, share
> nothing and overhang. This is the same exact/approximated line OP-15 draws, arriving one level up.

#### What this deliberately does not do

- **Wall side is per curve, not per curve *per network*.** A curve used in two thickenings takes its side
  from each pick separately, which is right, but it also means the same physical curve can carry two
  walls whose material overlaps — and that overlap is *not* resolved between two networks. Two thick
  networks that cross still overlap, exactly as two thick paths did. The junction cleanup this delivers is
  for walls of **one** carrier, which is the honest scope of "a wall is a thickness applied to a path".
- **No mitre limit.** A very sharp branch produces a very long spike, exactly as the ortho case always
  has. Stated so it is not mistaken for a bug; capping it is a convention decision (like the
  chamfer-on-arc one) and belongs with the others.
- **A whole circle cannot be a carrier**: it has no endpoints, so it joins nothing and its "network" is a
  ring with no vertices. Break it into arcs first — refused by name.
- **The carrier curves are not consumed.** A thickened segment stays a visible segment, as the ortho
  carrier's legs always did. Hiding it is a view decision, and the view has no such state (OP-18).

## Open work queue (crash-safe snapshot; ordered)

Kept here so no in-flight plan lives only in a session. Per-feature deliberate cuts stay recorded in
their own as-built notes; this is the *ordered queue* as of session 13. Session 11's three items (ratio
points / relative parameterizations on a shared carrier / the grouping closure) arrived as **demands** rather
than off this queue and are delivered, so nothing was retired for them; what they left behind is parked below.

**Retired in session 12: break on plain curves.** *Break* covered only ortho legs; it now covers a plain
segment, an arc and a cubic Bézier as well, with one consumer rule over all of them — see *Break on a plain
segment, an arc and a Bézier* under OP-19. It left nothing parked: the two limits it names (a curve a
user-defined tool is built from, a member of a placed group) are refusals by design, not deferrals.

**Retired in session 13: the two hand-built click cycles, and "a group you have to remember to place".** Both
were *parked as behaviour*, not as queue lines: the group/member reach and jamb-vs-leg are now one ranked pick
cycle with a first-click invariant, and a group is framed by default under one checkpoint — see *one pick
cycle, and a group that is framed by default* under OP-16. Session 13's three items arrived as **demands**, so
nothing else was retired for them, and they left nothing parked: the keyboard twin (`Tab`) was delivered rather
than cut, and the *unpickable ray* they uncovered is fixed rather than deferred. What the cycle does **not**
do, stated so it is not looked for: a bulk drag of a multi-selection (that is the frame's job, OP-16) and any
hover cue before a press (picking is still consulted only on press — the cut OP-21's jamb note already records).

**Retired in session 14: the two naming schemes, the rider that could not be freed, and the face-space stamp.**
Three queued defects, all of them at the `Document` seam, plus the first GitHub issue. (a) *A rider created by
the snap or by a point-on-curve tool refused to be made absolute* — it is now published through OP-16's
re-pointable view, so freeing it is a re-point and old files gain it on load (see *Freeing a rider* under OP-4).
(b) *The panel and the file numbered elements differently* — the file's script-local name is now the only
user-visible name, with one authority the writer itself asks (see *The name the file gives an element* under
OP-18); the migration findings that used to be discarded on load ride the same fix. (c) *An ortho path drawn on
a face left its corners in the plan* — one stamping seam, plus an audit of every creation route (see *Two
corrections from the same face* under OP-17). And (d) GitHub #1, *an extrude on a face builds into the
material*: the inward direction belongs to *Cut*, and a face-space *Extrude* is a boss. They left one thing
parked, stated where it belongs: a partial **revolve** in a face space still sweeps inward, which needs a
direction argument on the feature.

**Retired in session 15: "changing a count means using the tool again", for patterns.** Not a queue line but a
*stated limitation* — the structural-count note (see *Tool inputs*) said a live count "cannot be one node per
copy, because the number of nodes would depend on a value", and offered a compound value plus an addressing
scheme (OP-8's territory) as the only alternative. OP-23 found the third answer the note did not consider:
keep the count structural and **re-stamp the journal**. The document stays a pure function of its parameters
(nothing is live; a count change is an *edit*, one undo step, exactly like a delete), and what makes it
tractable is that a pattern stores the *rule* of every gesture riding it rather than the geometry those
gestures made. The limitation still stands verbatim for an **array's** count and a **polygon's** side count,
which store no rule — that is precisely the difference the new OP is about. It leaves one thing parked, stated
where it belongs: a **pattern of a pattern**, and a whole **group** as a pattern's reference member (OP-23's
closing note).

**Retired in session 16: "a rotated sketch plane is reachable only from the DSL", and the plane-choosing UI two
other cuts were waiting on.** Not a queue line but a *stated limit* in three places, and GitHub #6 arrived as the
demand that closed it — see *Datum planes — any line, any angle* under OP-17. (a) OP-17's seam-downward slice
recorded that "a **flipped or rotated** sketch plane — reachable only from the DSL today — would need the hint and
the 2D pick to apply the same in-plane map `sectionAt` applies": rotated planes are now reachable by clicking, and
the caveat is retired rather than repaired, because the *space* rule already answers it — a solid's footprint hint
is drawn in the space its sketch was drawn in, at that sketch's own coordinates, so nothing is ever projected into
a plan it has no honest projection into. (b) The same slice's cut "sectioning along a **plane other than
horizontal** … wants datum-plane UI" is **unblocked but not built**: the UI exists, and what a general
`section(solid, plane)` still needs is a plane-*valued* tool slot. (c) OP-9's "cross-axis booleans are unreachable
from the toolbar" was closed for *faces* in session 8 and is now closed for arbitrary angles, with the first
exactly-predictable assertion on that path (a 45° miter, 16000 mm³). It leaves one thing parked, stated where it
belongs: **a two-operand boolean whose operands live in two different spaces has no gesture**, because one canvas
shows one space; *Cut* covers the case that matters by naming the part, and the general case wants 3D picking.

**Retired in session 17: "only the path tools say what they are about to build", and the first Apollonius
case.** Two queue lines, and the first of them also retires half of a cut recorded in session 13. (a) *Live
tool previews* — `ToolDef.preview` is now one declared member per tool, drawn by `SceneRenderer` in the ortho
band's own style, with **twenty-four** tools covered and none cut; see *Live tool previews* under the editor
roadmap. Session 13's stated cut — "any hover cue before a press (picking is still consulted only on press)" —
is retired for *cues*: a preview that must know what the cursor is **over** (a fillet's second leg, Mirror's
axis) asks `HitTest` on hover. What stands verbatim is the half that cut was really protecting: **the
selection** is still decided on press and on release only, and no hover changes it. It leaves nothing parked;
the two tools it deliberately skips (Circle (centre, radius), and the rectangle's first corner) are named where
the from-the-first-pick rule is stated. (b) *Circle from three tangents* — the **LLL** case, four solutions
resolved by the final click and stored as two bisector branches (OP-1), tangency by construction. It leaves
one thing parked, stated where it belongs: the rest of the Apollonius family (**LLC**, **LCC**, **CCC**), each
of which is composable from the ops that exist plus at most one, and each of which needs its own `signs=`
layout because the number of discrete choices is part of the shape of the construction.

**Retired in session 18: generalized walls, and with them the T/L junction cleanup OP-21 deferred twice.**
Queue line 1, delivered whole — see *Walls over arbitrary curve networks* under OP-21. A wall is now a
thickness over a connected graph of curves with a side per curve; a branch vertex resolves by **cyclic
order** into one ordinary corner per angularly adjacent pair, which is what makes a T-junction one region
with no sliver and no boolean. Three things it retires beyond the queue line itself: the long-standing
*"wall-to-wall junction cleanup (T/L merges — two thick paths meeting still overlap)"* under **Next
(architectural)**; the first-slice cut *"per OP-6 accessors on the footprint are not built"*, now the *Key
points* tool taking a footprint; and, as a **correction rather than a deferral**, OP-21's *"trim by
construction, don't reach for 2D booleans"* note, whose stated reason ("trim each wall's face at the
neighbour's face line") only ever described two walls — see the second correction under that note. It leaves
three things parked, each stated where it belongs: **two thick networks that cross still overlap** (the
junction cleanup is for walls of *one* carrier), there is **no mitre limit** on a sharp branch, and a
footprint that takes the kernel route is **approximated** even when every carrier was a line or an arc.

**Retired in session 19: incremental recompute — the OP-5 dirty-marking the implementation had owed since
day one.** Queue line 1, the last one standing, delivered whole — see the as-built note under *Evaluation*
in OP-5. The queue line proposed "a cross-pass value cache keyed by source-node versions"; what shipped
needs no versions and no keys: **a node reuses its result when its arguments are the same objects**, which
makes the freshness test re-read the very edges the result depends on and so survives the four re-pointings
(weld, attach, capture, wire) that a version index would have had to be told about. Its acceptance is met
exactly as stated — 100 repaints of an untouched drawing recompute nothing, a drag outside the revolve's
cone leaves its counter alone, an edit inside it costs one recompute — and the evaluation half of a render
pass on a four-revolve part drops from ~2.5–3.2 ms to under 0.01 ms. It leaves **two things parked**, both named
where they belong: an `InstanceNode` over a definition *source* does not memoize (stated in the note, and a
pass-through anyway), and **invalidity is never memoized**, deliberately, because OP-3's healing promise
depends on retrying — so a node that is expensive *and* invalid pays its cost every pass. The optional
follow-on the queue line mentioned, **a low-poly view mode, is not needed and not built**: with the mesh
cached, a drag that does not touch the solid does no tessellation at all, and one that does needs the real
mesh anyway.

**Retired in session 20: the UI-polish item, all five of it.** Not a construction question at any point —
five things the shell owed a person actually using it, and the reason they were one queue line is that each
one costs something every session. (a) *Dependency visibility* — a selection now shows what it is **built
from** and what is **used by** it, on canvas and by name, on the honest depth rule (nearest element-bearing
ancestors); see the note under the editor roadmap. (b) *An icon palette*, 60 of 77 tools, with the other 17
named where the line is drawn. (c) *A stable inspector*, fixed height and its own scroll, so nothing below it
moves when the selection changes. (d) *Renaming*, for groups and for elements, both on OP-7's existing rule
and one of them exposing a latent save defect in `place`. (e) *A corner scale bar*, on the grid's own
rounding, shared now by all three consumers of that rule. It leaves **two things parked**, each stated where
it belongs: the dependency rows list **elements only** — a parameter that drives the selection is not shown
there, because a scalar is not an element and its own panel row already says what it drives, but "which
parameter moves this?" is the same question one type up and will want the same answer; and the scale bar
speaks **millimetres always**, which is the display-unit half of OP-7 (a bar that switched to metres would be
answering that question in one corner of one view).

**Queued in session 21 (user-directed): the loft — the multi-section solid, general from the first
slice.** The one solid class the toolset cannot produce is the one whose cross-section *changes* along
the sweep: every solid today is a prism (extrude), a surface of revolution (revolve), or a boolean of
those, and the simplest counterexample is a pyramid. (There is a construction trick — a square pyramid
is the intersection of two perpendicular triangular prisms — but cross-space boolean gestures are parked,
and the trick expresses a workaround, not intent.) The generic operation is the **loft**: an ordered list
of section profiles on datum planes (which exist since session 16), a **point allowed as a terminal
section** (pyramid = base polygon → apex; cone = circle → apex; frustum = polygon → smaller polygon),
**guide curves** shaping the run between sections, and the section-to-section **vertex correspondence /
seam as the one discrete choice**, scored at creation and riding `signs=` (OP-1/OP-18), never re-scored
on replay. The apex is a *constructed point*, so the solid stays a pure function of its sections and its
apex like everything else in the DAG — drag the apex and the pyramid follows (GeoGebra's
`Pyramid(polygon, apex)` is the construction-flavored precedent; feature CAD's Loft/Multi-Section
Solid/Blend is the mechanism precedent). Honesty classes carry over unchanged: polygon→polygon and
polygon→point lofts have exactly planar facets (exact class, watertight by construction, assertManifold
holds); curved sections and guide-curve runs are ruled/tessellated and flagged **approximated**, exactly
the bargain Bézier offsets already make (OP-15). Scope is the user's directive, quoted because it is the
standing everything-generic rule applied to a new solid: *"Include also \[guide curves / more than two
sections\] — we are building a general tool that cannot extend when the first drawing is not possible."*
Pyramids are the example, not the feature.

**Queued in session 21, behind the loft (user-directed): editing in the 3D view, on a working plane.**
The user's design, adopted whole: the 2D/3D split stops being "author here, inspect there" — the 3D view
becomes an *editing* view on one condition, an active **working plane**. Every mouse position in the 3D
viewport casts a ray that intersects that plane, giving exact 2D coordinates in the plane's own space, so
every existing tool gesture translates losslessly and the inverse map draws the sketch back onto the plane
inside the 3D scene; choosing the working plane itself becomes a 3D gesture (click the surface to work
on); a modifier keeps orbit/pan available without leaving the tool. Nothing about the *model* changes — it
is a second projection into the same editor: the `Editor` is already a pure headless controller driven by
pointer gestures in the active space's coordinates, and the working-plane concept already exists
(`activeSpace`, `activePlane()`, face spaces, session 16's datum planes), so the feature is a **projection
seam, not a new controller**. Four pieces: (1) Camera3 unproject-to-ray and ray ∩ `activeSpace.plane` →
plane 2D coords, fed to the same `pointerDown/Move/Up` the test suite drives; (2) the active space's
sketch drawn *in* the 3D view — under perspective, arcs must tessellate in plane space with per-vertex
projection (what the solid painter already does), not in screen space; (3) pick tolerances stay screen
pixels, converted through the *local* px→mm scale at the cursor's plane point (perspective makes it vary);
(4) click-a-face plane selection — ray–triangle picking against the tessellated solids, the face's plane
becoming/activating a face space, with the click recorded as a durable choice, which is exactly the parked
**Manifold face-ID provenance + 3D picking** item (the provenance is the hard part, the ray-cast is easy).
Slices, each whole: (1) edit-in-3D on the current active plane (ray seam, in-plane sketch rendering, all
tools, modifier-gated orbit; the plane chosen the existing way); (2) click-a-face working-plane selection
(3D picking + face provenance as a recorded choice); (3) what that unlocks — the parked cross-space
two-operand boolean gets its gesture, and datum/loft-section placement in 3D.

**Queued in session 21, at the end of the queue (user-directed): the export package — GLB for viewing,
3MF + binary STL for printing.** The session-3 directive is hereby *clarified, not reversed*: format work
was deferred ("no standard compliance is required **yet**"), never banned, and its time is after the
modeling queue above. All three exports are one package because they are the same export: the
already-guaranteed-manifold tessellated mesh (OP-9's watertight-or-refused doctrine did the hard part
years of sessions ago), written three ways, refusing by name for anything that is not a solid.
**GLB** (glTF 2.0, single binary container) is the viewing half — the "JPEG of 3D": indexed triangle
mesh, which is what MeshGL already is nearly verbatim; one node per solid named by the naming authority
(OP-18) so viewers show an honest tree; simple PBR base colors from element styles; glTF is *metres* and
*+Y-up* by spec, so the writer scales mm→m and turns the Z-up world once at the root node — units and
orientation are spec'd, not folk convention, which is why this file kind is safe to write without a
compliance project. **3MF** is the printing half done honestly: units explicit (mm, our canonical base),
indexed mesh, and the spec *requires* manifold consistent orientation — the doctrine as a file format;
core spec only (ZIP + one XML model), no materials/settings extensions. **Binary STL** rides along as the
universal fallback (~50 lines off the same triangles; no units in the format, mm by convention).
Deliberately out, stated so it is not looked for: **STEP export** — the kernel is mesh-based and holds no
exact B-rep for solids, so exact-geometry export would be either dishonest or a compliance project, and
the "STEP into the slicer" trend is unreachable from here by design.

**Appearance, scoped in session 21 — three tiers in, one recorded non-goal.** For viewing, the modeler's
job is to *assign* appearance, never to render it — rendering (lighting, shadows, reflections) is
precisely what the GLB export delegates to real PBR viewers. The tiers, in queue order: **Tier 1, a
material per solid** (base color, roughness, metallic — a handful of numbers, one panel row) rides the
export package above, because five numbers per solid is what makes a GLB render honestly in any viewer.
**Tier 2, textures by projection**, is an *export-time operator*, not a modeling-time subsystem: the model
stores a material reference plus a projection rule (planar/box/cylindrical), and the exporter bakes
per-vertex UVs from mesh positions — no UV tools in the app, the bitmap embedded in the GLB. **Tier 3,
per-face assignment**, waits deliberately for edit-in-3D's slice 2, because naming a face durably is the
same face-ID provenance mechanism that click-a-working-plane needs — one mechanism, two consumers, built
once. And the non-goal, recorded at the user's word before a line of it could be asked for: **Tier 4 — a
material editor, bitmap import UI, UV unwrapping — is out, permanently.** That is DCC territory, off the
paradigm whose value is the construction workflow; the stated alternative is the escape hatch the GLB
export *is* — open it in Blender (or any PBR pipeline) and go as far as taste demands. A refusal with a
reason and an alternative, per the standing doctrine.

Otherwise the numbered queue is empty; what remains is the parked list below, each item recorded at its
source.

Smaller parked items, each already recorded at its source: grouping-per-copy for group arrays and
Mirror/Rotate as group operands (OP-16 note), macro specialization UI (OP-6 note), chamfer-on-arc
convention (fillet note), drag-to-attach onto arcs (welding note), STL/3MF export (OP-9), Manifold
face-ID provenance and 3D picking and the mesh-only footprint (OP-9/OP-17 notes), MeshGL64,
**silhouette edges in the 3D view** (the view-dependent half of the feature-edge work — see the viewport
note's crease bullet, GitHub #3), and — new from
session 11 — **angles under a turned frame**: bind a polar offset's bearing and an on-circle angle onto
`frameAngle(frame) + local` so they turn with a placed group (needs a frame-angle accessor and a decision
about which space the panel's number is in), plus imposing the along-line rider form at capture time so a
rider on an axis-aligned host is rigid under rotation as well (both in the OP-16 note).
