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

### Editor tool roadmap

**Implemented** (data-driven `ToolDef` registry, categorized palette; scalar tools use the
active parameter/measurement; existing-only slots never create stray points; scalar names are
auto-uniquified so wiring is unambiguous):
- Points: Point, Midpoint, Intersect, Project-to-line, Point-on-circle & Point-on-line
  (1-DOF draggable), Point-at-distance (0-DOF, side by click), Key points (sub-entity extract),
  Join points (weld two points into one — see *Welding* below)
- Curves: Line, Segment, Ray, Circle (c,pt), Circle (c,r), Circle (3pt), Arc (3pt),
  Arc (centre,ends), Concentric circle
- Construct: Perp/Parallel-through, Perp-bisector, Angle-bisector, Parallel-at-distance,
  Tangent-from-point, Tangent-at-point (1 click), Fillet, Outer/Inner common tangents
- Transform: Mirror, Rotate, Scale, Translate-by-vector
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

*Generalization (planned):* welding a point *onto a curve* = point-on-line/point-on-circle
(2→1 DOF), onto a *derived/intersection point* = alias (2→0 DOF). Folding join +
point-on-line + point-on-circle into a single **drag-to-attach** gesture is the elegant
endpoint; plain point-to-point weld is the first slice.

**Remaining — build order (all planned; ordered, not deferred):**

1. **Tool completions.** Point-from-coordinates (needs two scalar inputs — extend the slot
   model beyond a single active scalar), Chamfer (straight bevel between two legs), Rectangle,
   Regular polygon, Rounded-rectangle (expose the existing macro), Area measurement (needs an
   area op).
2. **Editing & persistence.** Delete (dependency-aware — removing a node invalidates/removes
   dependents), undo/redo (snapshot source values + element list), save/load — the `Document`
   is the file-format seam.
3. **Productivity.** Auto-snapping (ordinary point clicks snap to key points, intersections,
   grid); **drag-to-attach** (extend the weld magnet so dragging a free point onto a curve makes
   it point-on-curve, onto a derived point aliases it — see *Welding*); Arrays — linear (repeat N
   along a vector) and circular (around a centre), the interactive generalization of the
   bolt-circle/hole-pattern macros (needs a count input).
4. **User-defined macros UI.** Record a sub-construction, designate inputs, get a reusable
   tool (OP-6 `Macro` machinery exists in the engine; needs the record/parameterize UI). The
   headline capability of the paradigm.
5. **Dimensions & annotations.** Dimension lines/leaders showing a measured length/angle —
   for the 2D technical/architectural-drawing goal.

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

## Going to 3D

- Sketch → feature → sketch loop (sketch on datum plane → extrude/revolve/sweep → derive
  datum from a solid face → sketch again). DAG spans 2D constructions, 3D features, datums.
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
