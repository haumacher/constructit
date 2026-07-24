# ConstructIt — Phase 1 Implementation Plan (2D construction engine)

Phase 1 implements the 2D construction engine per DESIGN.md, with the classic constructions
as the **definition of done**. No 3D, no kernel, no UI beyond headless SVG rendering.

Language/build: **Kotlin (JVM)**, Gradle wrapper, JUnit5. Engine is a **pure library**
(no UI/shell deps) so tests build models in code and assert (value-level + SVG golden).

## Milestones

- **M1 — Skeleton.** Gradle wrapper (modern), Kotlin JVM plugin, JUnit5, package layout.
- **M2 — Units & dimensions.** `Dimension` (Length/Angle exponents), `Quantity` (base units
  mm, rad), dimensional arithmetic (`+`/`-` require equal dim; `*`/`/` combine), constructors
  (`mm`, `cm`, `deg`, `rad`, dimensionless), display formatting.
- **M3 — Core graph & evaluation.** `Node` (stable id, inputs), typed `Value`s, `EvalResult`
  (Valid/Invalid) with **transitive invalid propagation** (OP-3), `Evaluator` with per-pass
  memoization; parametric recompute by mutating a source literal and re-evaluating.
- **M4 — Geometry types + math.** `Vec2`, `Point`, `Line`, `Segment`, `Circle`, `Arc`,
  `PointSet` (ordered solution set); intersection & construction math.
- **M5 — Construction ops (DSL).** `Construction` builder returning typed refs:
  `freePoint`, `pointXY`, `parameter`, scalar `expr` ops, `midpoint`, `lineThrough`,
  `segment`, `circleCR`, `polarPoint`, `translate`, `rotate`, `intersectCC/LL/LC`, `select`
  (OP-1 orientation ordering), `measureDistance`.
- **M6 — Macros.** Definition (subgraph builder + designated inputs), instance with **derived
  path-IDs** `M/nk` (OP-6), **specialization** (partial application) — e.g. `standardRect`
  from `roundedRect`.
- **M7 — Canonical SVG renderer.** Deterministic output (fixed precision, stable order/ids),
  auto viewBox; renders points/segments/lines/circles/arcs.
- **M8 — Example test cases (DoD).** Value-level asserts + SVG goldens for all four examples,
  plus a parametric-recompute test and an invalid-propagation test.

## Definition of done (the four examples)

1. **Perpendicular bisector** — two free points + shared radius `R`; two equal circles;
   `IntersectCC` → `Select(±1)` → `lineThrough`. Assert: result line passes through the
   midpoint and is perpendicular to `P1P2`; the two intersection points are equidistant from
   `P1`/`P2`. Assert **invalid propagation** when `R < |P1P2|/2` (empty intersection).
2. **Rounded rectangle macro** — `roundedRect(center, width, height, radius)` → 4 segments +
   4 arcs. Assert bounding box = `width × height`, arc radii = `radius`, tangency. Then
   **`standardRect(w,h)`** via specialization (radius fixed 2mm) — assert it equals
   `roundedRect(w,h,2mm)`.
3. **Bolt circle** — `boltCircle(center, pitchDiameter, count, startAngle)` → `count` points
   (and hole circles) equally spaced. Assert radius and angles (e.g. 6 holes at 0..300°).
4. **Hole pattern** — rectangular grid of points (`rows × cols`, `dx`, `dy`). Assert positions.

All four produce committed **SVG goldens**; the whole suite passes via `./gradlew test`.

## Explicitly deferred within Phase 1 (not required by DoD)

- String **expression parser** (OP-7 language) — the graph-level `expr` nodes and units are in;
  the text parser can follow. Builder expresses formulas in Kotlin meanwhile.
- Incremental **dirty-marking** recompute — correctness via per-pass memoization now; the
  incremental optimization later.
- Interactive canvas/UI, drag, 2D export beyond SVG (DXF/PDF).
- Macro edit-propagation UI (mechanics demonstrated via re-instantiation in tests).
