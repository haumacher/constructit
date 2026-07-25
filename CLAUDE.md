# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

ConstructIt is a parametric 2D/3D CAD tool where geometry is built as a **construction**, not
solved by a constraint solver. The whole drawing is a directed acyclic graph (DAG): each object is
a pure function of its inputs, inputs are either free parameters or earlier objects, and editing =
mutate a source + recompute the downstream cone (a spreadsheet for geometry). This is dynamic
geometry (GeoGebra/Cinderella) generalized to mechanical + architectural CAD. Read `DESIGN.md` for
the full design record (its `OP-n` markers are referenced throughout the code) and `README.md` for
the feature-level overview.

**The no-solver stance is load-bearing.** Sharing a node *is* equality — "same radius" is one
parameter node feeding two circles, not a constraint. Before adding anything, ask whether the intent
can be achieved *by construction* (shared inputs, tangent-construction primitives, ordered solution
sets) rather than by asserting a relation after the fact. Branch choice is a persisted discrete
`Select(sign)` node, never continuity tracking — the stored model must stay a pure function of its
parameters so recompute/undo/reload are deterministic.

## Build & test

```bash
./gradlew jvmTest                                   # headless test suite (fast; the default loop)
./gradlew jvmTest --tests "constructit.FilletTest"  # a single test class
./gradlew jvmTest -De2e=1                            # also run the Playwright browser E2E (else skipped)
./gradlew ktlintCheck                                # lint (ktlint); config in .editorconfig
./gradlew ktlintFormat                               # auto-fix lint violations
./gradlew jsBrowserDevelopmentRun --continuous       # live-reloading dev server
./gradlew jsBrowserDistribution                      # production bundle -> build/dist/js/productionExecutable/
```

Kotlin Multiplatform (JVM + JS/IR browser), Gradle wrapper included, JDK 17+.
The Playwright E2E needs Playwright's browsers installed and only runs under `-De2e=1`.

Lint is ktlint (official style) via the `org.jlleitschuh.gradle.ktlint` plugin. `.editorconfig`
relaxes two rules to match this codebase's deliberate style: `max_line_length = off` (the
data-driven `ToolDef` table in `Tools.kt` is intentionally one wide line per tool) and
`property-naming` disabled (geometric single-capital names like `X1`/`C1`). Keep new code
ktlint-clean; run `ktlintFormat` before committing. Note: inline comments inside an argument list
(trailing `// ...` after an argument) trip the `discouraged-comment-location` rule, which cannot be
disabled without crashing the engine — put such comments on their own line above the argument.

## Architecture

Layered so the UI shell is a late, reversible choice. The engine is pure Kotlin shared between JVM
(tests) and browser (`src/jsMain`) — **keep `commonMain` free of platform APIs.**

| Layer | Location |
|-------|----------|
| Core DAG engine | `core/Model.kt` — `Node`, `SourceNode`, `ParameterNode`, `OpNode`, `Evaluator` |
| Geometry + units | `geom/` (`Vec2`, `Line`, `Circle`, `Arc`, `Profile`, affine), `units/Units.kt` (dimensional analysis) |
| Construction DSL | `dsl/Construction.kt` (typed builder), `dsl/Shapes.kt` (macros) |
| Editor core | `editor/` — `Document`, `Editor`, `Tools`, `HitTest`, `Camera`, `SceneRenderer` |
| Browser shell | `src/jsMain` — `Main.kt` (DOM chrome), `BrowserCanvasDrawTarget.kt` |
| Tests | `src/jvmTest` — gesture tests, SVG goldens, opt-in Playwright E2E |

### Core engine (`core/Model.kt`)
Every node produces **exactly one** typed `Value` (`ScalarValue`, `PointValue`, `LineValue`, …).
`Evaluator` does per-pass memoized eval; invalidity propagates transitively (`EvalResult.Invalid`)
so a bad intersection just hides its dependents. Two source-node kinds carry all degrees of freedom:
`SourceNode` (free/draggable point) and `ParameterNode` (named scalar). Both have a `boundTo` field
— when set, the node tracks another node's value instead of its literal. **This is how welding and
parameter-wiring remove a DOF: binding mutates the node in place, so every existing reference
transparently follows the new master and no immutable input list is rewired.**

### Typed DSL (`dsl/`)
`Ref<V : Value>` is a compile-time-typed handle over the untyped graph (`PointRef`, `LineRef`, …).
`Construction` is the builder that generates stable ids and supports macro scopes (`M/nk` path ids).
Intersections return an ordered `PointSet` + a separate `Select` node picking the branch by sign.

### Editor (`editor/`)
- `Editor` is a **pure, headless interaction controller** — no platform APIs, driven by simulated
  pointer gestures, which is what the jvmTest suite exercises. Don't reach for the DOM here.
- `Tools` is **data-driven**: a `ToolDef` declares `slots` (a `SlotKind` sequence to pick by
  clicking) + optional scalar + a `build` lambda. Adding a tool = add a `ToolDef`, not new
  controller code. `Editor` runs any tool as a generic slot-collector.
- `Document` holds the retained `Element`s (a displayable graph output with kind + style + optional
  `Handle`) and the architectural editing state (ortho paths, walls, openings).
- Rendering goes through the one `DrawTarget` seam: `SceneRenderer` projects world→screen and
  tessellates arcs, so backends (`SvgDrawTarget` for golden tests, `BrowserCanvasDrawTarget`) stay
  trivial. All `DrawTarget` coordinates are **screen pixels**.

### Units
Quantities are stored in canonical base units — **mm for length, rad for angle**. `Dimension`
tracks length/angle exponents; `+`/`-` require equal dimension, `*`/`/` combine them, and a
`DimensionError` is caught into node invalidity.

## Conventions

- Code comments cite design decisions as `OP-n` (e.g. `OP-5`, `OP-1`); these index into `DESIGN.md`.
- **SVG golden tests** (`Golden.check` in `TestSupport.kt`): a missing golden is written and passes
  on first run — inspect and commit it; later runs assert byte-equality. Delete the file to
  regenerate.
- **Scratch / verification artifacts** (screenshots, scratch scripts, throwaway output) go under
  `tmp/` — its contents are gitignored (the folder is kept via `tmp/.gitkeep`). Don't drop such
  files at the repo root; write them to `tmp/`.
- Match `assertClose` tolerances to base units (mm/rad) when writing geometry assertions.
