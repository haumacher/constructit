# ConstructIt

A parametric **2D/3D CAD tool for mechanical engineering and architectural drawing** — where
geometry is built as a *construction*, not solved by a constraint solver.

You draw by constructing: each object is a pure function of its inputs, and inputs are either free
parameters or earlier objects. The whole drawing is a directed acyclic graph (DAG). Editing means
changing a parameter or dragging a free object and recomputing everything downstream — like a
spreadsheet for geometry. This is closer to **dynamic geometry** (GeoGebra, Cinderella) generalized
to mechanical CAD than to constraint-based sketchers.

> **Why no solver?** Because sharing a node *is* equality. "Same radius" isn't a constraint to
> solve — it's a single parameter node feeding two circles. Many things usually expressed as
> constraints become shared inputs or tangent-construction primitives here, which makes the model
> deterministic, fast, and free of convergence failures or under/over-constrained states.

The ultimate target is 3D geometry for 3D printing; 2D technical and architectural drawings are a
first-class goal and the current implementation focus.

## Highlights

- **Construction DAG** — strongly-typed nodes (points, lines, rays, segments, circles, arcs,
  directions, profiles, scalars) with memoized evaluation and transitive invalid-propagation.
- **Deterministic intersections** — an intersection is an ordered solution set + a `Select(sign)`
  node, so branch choice is stable across recompute, undo and reload (no continuity tracking).
- **Unit-aware scalars** — dimensional analysis over Length / Angle / Dimensionless (+ Area /
  Volume); base units mm and rad.
- **Rich 2D tool set** — points, midpoints (or any ratio along a span), intersections, projections, perpendiculars, parallels,
  bisectors, tangents (from a point / common), fillets (between lines, circles and arcs alike) and
  chamfers, circles (centre+point / centre+radius / 3-point), arcs, rectangles (rounded or not) and regular
  polygons, transforms (mirror / rotate / scale / translate), linear and circular **arrays**, and
  measurements. Any tool that wants a line takes a segment or ray, and any tool that wants a circle takes
  an **arc** — the carrier is what the construction is about.
- **Hide/show is part of the drawing** — hiding scaffolding is a recorded step, so it survives save/load
  and undoes like anything else (a welded alias stays hidden by construction, and is not recorded).
- **The file is the construction** — a `.cit` drawing is the readable sequence of steps that built it, and
  loading replays them (which is also the undo substrate). The drawing's **name** is a field in the topbar,
  not something stored in the file: where the browser offers it, the first Save asks for a file and every
  later Save writes back to that same one, with *Save as…* for a new one and a plain download everywhere else.
- **Shapes by construction** — a rectangle is two clicks that draw a closed rectilinear path, so its
  sides stay axis-parallel however you drag them *and* each side's length is a number you can type; a
  polygon's vertices are rotations of one, so it cannot stop being regular; an array copy is a transform
  node over the original, so the copies follow it live.
- **A shape library that is just constructions** — rounded rectangle, bolt circle, hole pattern and a
  **parametric spur gear** (module, tooth count, pressure angle, bore) live in the same DSL a user's own
  macro does. The gear's tooth flank is a *sampled* involute — deterministic by fixed sampling, and within
  a quarter of the tessellation tolerance of the exact curve — with the standard tip, root and pitch
  proportions, so a pair of them meshes at centre distance m·z.
- **Constrained, draggable points** — point-on-line / point-on-circle (1-DOF), and *drag-to-weld*:
  drag a free point onto another to join them, or onto a curve to attach it as a sliding point. A point
  riding a curve keeps its place while the curve is stretched, moved, *or turned* — a gesture that reshapes
  a host re-solves what rides it to where it stood, so nothing you did not touch runs away.
- **Relative points** — *Make relative* re-parameterizes a free point as a distance and an angle from
  another point, so it follows that anchor (move a circle's centre and its radius holds) while staying
  fully draggable — and its distance is the radius, as a number you can type. *Make absolute* undoes it,
  and un-welds or detaches a point too. A conversion, not a constraint: two degrees of freedom before,
  two after, and nothing moves at the moment you say it. Pick **two points of one curve** and the same tool
  states a distance *along* that curve instead — a dimension from an end, or from another point riding it,
  chainable — which is also what makes a group of such a figure rigid.
- **Ratio points** — *Midpoint* takes an optional **factor**: no number means the midpoint, exactly as
  before, and typing `.3` first puts the point three tenths of the way along (past an end if you ask for
  it). The factor is a plain number, so **one factor shared by several spans keeps them in the same
  proportion** — equality by sharing a node, not by a constraint — and it is draggable along the span as
  well as typeable. *Perp. bisector* takes the same factor.
- **Dimensions** — linear (aligned), radial and angular dimension graphics whose value *is* a
  measurement node: the number and the drawing follow the geometry live, and nothing is asserted. The
  dimension line's own placement is draggable and typeable like any other degree of freedom.
- **Parameter wiring** — reduce degrees of freedom by binding one parameter (or point) to another;
  equality by shared reference, with cycle checks.
- **Your own tools** — select a construction, tick which of its free points and parameters are the
  inputs, and it becomes a button in the palette. Clicking its slots stamps an *instance* that is a
  view over the original rather than a copy of it: **editing the original updates every instance**, an
  instance's only freedom is its inputs, and the tool travels with the file.
- **Groups that carry a frame** — name a selection, then *place* it: the group gets its own coordinate
  frame, and moving or turning it is one edit on that one frame (its x / y / angle are ordinary typed
  fields). Grouping offers **every degree of freedom the selection is built on** — free points, a point
  riding a curve, a point relative to another — ticked by default, so a group you just drew moves as one
  figure; a point riding a member curve is re-stated as a distance from that curve's own end when the group
  is placed, which is what keeps the figure rigid, and unticking anything says at once what it will cost.
  The internals are re-read as local coordinates, so an ortho path in a placed group is
  axis-aligned **in the group** — turn the frame and a building sited at an angle still draws
  orthogonally, walls, openings and the solids cut from them following along. A group is also a tool
  **operand**: with it selected as a whole, clicking any member (or its row in the panel) arrays *every*
  member in one step — and only then, because a group nobody selected still behaves as loose elements.
- **Architectural layer** — a rectilinear **ortho-path** (shared-coordinate model: local vertex
  editing, axis-aligned by construction, closeable loops) and parametric **walls**: a *thick path*,
  i.e. one offset region around the path (mitred corners, end caps, a ring where the path closes),
  with **openings** (doors/windows) as parametric intervals carrying position, width, sill and head.
  In plan the wall stays whole and the gap is drawn as the convention it is — faces broken, jambs
  shown — while *Cut openings* turns the same description into the 3D cut: one subtracted box per
  opening, sill to head, following the parameters live. Those drawn jambs are **grabbable**: dragging
  the near one slides the whole opening along its wall, the far one sets its width, and both clamp to
  the wall's extent — the same two numbers the panel shows, since dragging and typing are one thing.
- **Browser canvas** — an interactive HTML5-canvas editor; the engine is pure Kotlin shared between
  the JVM and the browser.

## Architecture

Kotlin Multiplatform, layered so the UI/shell is a late, reversible choice:

| Layer | Location | Notes |
|-------|----------|-------|
| Core engine | `src/commonMain` | model, type system, DAG eval, geometry ops, DSL — zero UI deps |
| Editor core | `src/commonMain/.../editor` | document, tools, camera, hit-testing, scene renderer (behind a `DrawTarget` seam) |
| Browser shell | `src/jsMain` | HTML5 canvas `DrawTarget` + DOM chrome (palette, panels) + one WebGL program for the 3D view |
| Tests | `src/jvmTest` | headless gesture tests, SVG golden snapshots, opt-in Playwright E2E |

The renderer draws through a backend-agnostic `DrawTarget` (SVG for tests, Canvas2D in the browser),
so the interaction core is fully headless-testable by simulating pointer gestures. The 3D view works
the same way: the orbit camera, the scene extraction and a painter's-algorithm projector all live in
`commonMain`, so a 3D scene has SVG goldens and orbit gestures have headless tests — the browser
contributes only the GL calls, using the very same projection matrices.

Solids are built by construction too: an outline or a wall footprint plus a depth or a sweep angle
gives a watertight, manifold mesh, which is a terminal **sink** — render/print/export only, never
lifted back to analytic geometry, while measurements taken *from* it may drive new construction.

**Booleans** (union / subtract / intersect) are **exact** for solids extruded along the same axis —
counterbores, pockets, wall openings, stacked storeys — because that case decomposes into a stack of
2D region booleans rather than into mesh surgery: no coplanar-face epsilon, no repair pass, and a
result that is itself a legal operand of the next boolean.

Anything else — a plate drilled *sideways*, a revolve operand, a roof fused onto walls — goes to
**Manifold**, the guaranteed-manifold mesh engine, behind one `expect object MeshBool`: its JVM binding on
the desktop, the same library as WASM in the browser. The exact path is always tried first and keeps its own
refusals, and a general result is **mesh-only** by type (no named faces, no cross-section), so which engine
answered is visible in the value rather than a matter of trust. Results are canonicalised — welded, sorted —
so a mesh stays a pure function of its parameters, and the seam verifies its own output: a degenerate
tangency, which has no watertight mesh at all, is **refused with a reason** and heals when the geometry
moves. In the browser the engine's WASM arrives after the first paint; until it does, a general boolean is
simply an invalid node that says so, and one repaint makes it appear.

The seam runs **both ways**. *Extrude on face* raises an area from a solid's top face — the plan is drawn
in the same 2D space, so an upper storey or a boss needs no datum-plane UI — and *Section* cuts a solid at a
height back into ordinary 2D geometry, which is **exact** for a prism (the section *is* the slab there) and
analytic for a plain extrude (its circles stay circles). A section is an area like any other, so it can be
dimensioned, measured, or extruded again: storey 2 is built from the section of storey 1, and one drag of a
ground-floor wall reshapes both. Volume and per-axis extent land in the panel as read-only values that may
drive *new* construction — forward only, never back into their own solid.

**And on any planar face.** A drawing lives in a **sketch space**: the plan (world XY) by default, or a
space on a solid's *side* face — created by clicking one of its footprint edges, because that edge is what
the face projects to seen from above. The 2D canvas switches to that face and draws in its own coordinates:
`u` along the picked edge, `v` down from the top face. Which way a feature builds is the operation's, not the
space's: *Cut* drills **into** the material (extrude and subtract in one gesture), *Extrude* builds a **boss**
standing out of it. The frame is
derived from the part, not captured from it — stretch the plate and the hole rides the face, still 25 mm
from the edge. That is what makes the plainest mechanical feature there is, a hole drilled in an edge, a
matter of four clicks; the cut across the part's own axis is the general engine's, and the file records
which space each step was drawn in. Features **chain**: each cut takes the part as it now stands, so a
second bore on a second face lands in the part with the first bore in it rather than beside it, and the step
records which solid it cut so a reload rebuilds the same chain. The elements panel follows the view: it lists
the active space's 2D geometry plus the solids, which belong to no space and are shown in the 3D view.

Mesh export is next. See [`DESIGN.md`](DESIGN.md) for the full design record and open questions.

## Build & run

Requires a JDK (17+). The Gradle wrapper is included.

```bash
# run the headless test suite
./gradlew jvmTest

# build the browser bundle (output: build/dist/js/productionExecutable/)
./gradlew jsBrowserDistribution

# then serve that directory, e.g.
cd build/dist/js/productionExecutable && python3 -m http.server 8080
# open http://localhost:8080

# or a live-reloading dev server
./gradlew jsBrowserDevelopmentRun --continuous
```

The Playwright browser E2E test is skipped unless opted in with `-De2e=1` (needs Playwright's
browsers installed).

## Status

**Session 3.** The 2D engine, the interactive browser editor, the architectural layer and the 3D seam are
working in both directions, with exact prismatic booleans underneath them. The **four showcases now exist as
worked spec examples** — one test class each, the spec in its KDoc:

- a **parametric spur gear** (module, tooth count, pressure angle, bore) whose tooth flank is a sampled
  involute, extruded into a watertight blank that meshes its own copy at centre distance m·z;
- the **house chain**: a wall ring with a door and a window → the cut ground floor → its section → storey 2
  on storey 1's own top face → a **gable roof** as a triangle sketched on a *vertical* plane and extruded
  along the ridge, its profile driven by measurements of the storeys, so **one drag reshapes all three**;
- a **reverse-engineered bracket** in which every dimension is a named caliper reading, so re-measuring the
  plate is a typed number rather than a redraw;
- **parametric papercraft**: a house net whose nine panels are laid out by hand in 2D but whose every length
  is *measured off the 3D mesh* — change the wall height and the net resizes. No unfolding algorithm.

The showcases needed one macro (the gear) and two ten-line generic ops, which is the result they were
meant to produce.

**Usability is now measured, not asserted.** Four whole workflows are scripted as gestures and *counted*
(`ClickBudgetTest`), each with a ceiling that fails the build if an interaction regresses: the mechanical
plate went from 43 user actions to 23, the architect's storey from 27 to 18, and two of the four workflows
did not previously complete at all. What the measuring produced:

- **type a number for any tool input** — digits typed with a tool armed become an ordinary named parameter
  (editable, wireable, saved), so no scalar-consuming tool needs a trip to the panel first, and a tool that
  is still missing its value now *waits* with your clicks instead of discarding them;
- **single-key tool shortcuts** (`S P L C R O W D E X M`), shown on the palette buttons;
- **boundaries that already meet are not re-intersected**, which is what makes a rounded rectangle (or a
  fillet) traceable at all — its sides meet its corner arcs tangentially;
- **a closed curve, or a closed chain a single step built, can be used wherever an area is wanted** — so a
  circle extrudes into a cylinder and a drawn plate extrudes with one click, no boundary tracing;
- **the Outline tool follows the boundary for you** — two clicks fix the direction, then every piece whose
  continuation is *unique* is added automatically (through the joints a fillet or chamfer registered), and
  the loop closes itself when it comes round. A fork, a dead end or an ambiguous arc stops it and says why;
  picks are highlighted on the canvas and a click that hits nothing says so. The recorded step still lists
  every piece in order, so nothing is re-discovered when the file is reloaded.

561 tests pass headlessly; the browser E2E drives a real Chrome, keyboard included.

Planned next: mesh export (STL/3MF), regions with holes from traced outlines, datum planes in the UI (the
roof is DSL-built for want of a way to *name* a plane), line styles in the render seam, and wall-to-wall
junction cleanup.

## Documentation

- [`DESIGN.md`](DESIGN.md) — the design record: guiding idea, resolved design points, and roadmap.
- [`PHASE1_PLAN.md`](PHASE1_PLAN.md) — the Phase 1 plan and primitive/operation backlog.
