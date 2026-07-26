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
- **Rich 2D tool set** — points, midpoints, intersections, projections, perpendiculars, parallels,
  bisectors, tangents (from a point / common), fillets and chamfers, circles (centre+point /
  centre+radius / 3-point), arcs, rectangles (rounded or not) and regular polygons, transforms
  (mirror / rotate / scale / translate), linear and circular **arrays**, and measurements.
- **Shapes by construction** — a rectangle's other two corners *share* the clicked corners'
  coordinates and a polygon's vertices are rotations of one, so they cannot stop being rectangular or
  regular however you drag them; an array copy is a transform node over the original, so the copies
  follow it live.
- **A shape library that is just constructions** — rounded rectangle, bolt circle, hole pattern and a
  **parametric spur gear** (module, tooth count, pressure angle, bore) live in the same DSL a user's own
  macro does. The gear's tooth flank is a *sampled* involute — deterministic by fixed sampling, and within
  a quarter of the tessellation tolerance of the exact curve — with the standard tip, root and pitch
  proportions, so a pair of them meshes at centre distance m·z.
- **Constrained, draggable points** — point-on-line / point-on-circle (1-DOF), and *drag-to-weld*:
  drag a free point onto another to join them, or onto a curve to attach it as a sliding point.
- **Dimensions** — linear (aligned), radial and angular dimension graphics whose value *is* a
  measurement node: the number and the drawing follow the geometry live, and nothing is asserted. The
  dimension line's own placement is draggable and typeable like any other degree of freedom.
- **Parameter wiring** — reduce degrees of freedom by binding one parameter (or point) to another;
  equality by shared reference, with cycle checks.
- **Your own tools** — select a construction, tick which of its free points and parameters are the
  inputs, and it becomes a button in the palette. Clicking its slots stamps an *instance* that is a
  view over the original rather than a copy of it: **editing the original updates every instance**, an
  instance's only freedom is its inputs, and the tool travels with the file.
- **Architectural layer** — a rectilinear **ortho-path** (shared-coordinate model: local vertex
  editing, axis-aligned by construction, closeable loops) and parametric **walls**: a *thick path*,
  i.e. one offset region around the path (mitred corners, end caps, a ring where the path closes),
  with **openings** (doors/windows) as parametric intervals carrying position, width, sill and head.
  In plan the wall stays whole and the gap is drawn as the convention it is — faces broken, jambs
  shown — while *Cut openings* turns the same description into the 3D cut: one subtracted box per
  opening, sill to head, following the parameters live.
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
result that is itself a legal operand of the next boolean. Anything else (a revolve, a future imported
mesh) is **refused with a reason** rather than approximated; general booleans arrive with Manifold.

The seam runs **both ways**. *Extrude on face* raises an area from a solid's top face — the plan is drawn
in the same 2D space, so an upper storey or a boss needs no datum-plane UI — and *Section* cuts a solid at a
height back into ordinary 2D geometry, which is **exact** for a prism (the section *is* the slab there) and
analytic for a plain extrude (its circles stay circles). A section is an area like any other, so it can be
dimensioned, measured, or extruded again: storey 2 is built from the section of storey 1, and one drag of a
ground-floor wall reshapes both. Volume and per-axis extent land in the panel as read-only values that may
drive *new* construction — forward only, never back into their own solid.

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
meant to produce. 441 headless tests pass; the browser E2E drives a real Chrome.

Planned next: mesh export (STL/3MF), datum planes in the UI (the roof is DSL-built for want of a way to
*name* a plane), line styles in the render seam, and wall-to-wall junction cleanup.

## Documentation

- [`DESIGN.md`](DESIGN.md) — the design record: guiding idea, resolved design points, and roadmap.
- [`PHASE1_PLAN.md`](PHASE1_PLAN.md) — the Phase 1 plan and primitive/operation backlog.
