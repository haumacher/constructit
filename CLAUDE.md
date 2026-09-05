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
./gradlew translateArb --no-configuration-cache      # NOT part of the build: re-translate l10n/ through DeepL
```

Kotlin Multiplatform (JVM + JS/IR browser), Gradle wrapper included, JDK 17+.
The Playwright E2E needs Playwright's browsers installed and only runs under `-De2e=1`.

One sibling is a **composite build**, checked out next to this repo by convention (see
`settings.gradle.kts`, which carries a `-Pkotlinjt.path=` escape hatch): `../kotlinJT` for the JT
writer/reader. The `translateArb` task comes from the **published** plugin `de.haumacher.auto-translate-arb`
(version in `build.gradle.kts`); `-Pautotranslate.path=<checkout>` swaps in a sibling composite only when
developing that plugin against this repository — never by default, so a bare checkout (CI) configures.

Lint is ktlint (official style) via the `org.jlleitschuh.gradle.ktlint` plugin. `.editorconfig`
relaxes two rules to match this codebase's deliberate style: `max_line_length = off` (the
data-driven `ToolDef` table in `Tools.kt` is intentionally one wide line per tool) and
`property-naming` disabled (geometric single-capital names like `X1`/`C1`). Keep new code
ktlint-clean; run `ktlintFormat` before committing. Note: inline comments inside an argument list
(trailing `// ...` after an argument) trip the `discouraged-comment-location` rule, which cannot be
disabled without crashing the engine — put such comments on their own line above the argument.

### Languages (OP-29)

**Every user-visible sentence** — the chrome's tool titles, help lines, slot names, category headings, panel
labels and buttons, *and* every status note and refusal reason the engine produces — lives in
`l10n/app_en.arb` and its translated siblings, never in Kotlin or in `index.html`. The English ARB is the
source of truth; `:generateMessages` (in `buildSrc/`) compiles every bundle into two objects — one typed
accessor per key in `constructit.l10n.Messages` (the sentence) and one factory per key in
`constructit.l10n.Msgs` (the `Msg` *value*) — and that runs in the ordinary build, so **editing an ARB
recompiles**. The generated sources are build output and are not committed.

**A message is a value, rendered at the edge** (OP-29 slice 2). `EvalResult.Invalid`, `Document.noteMsg`,
`Editor.statusMsg`, every `Pair<T?, Msg?>` refusal, `FaceName.label` and every `.word` enumeration carry a
`Msg`, never a `String`; the shell (or a rendering accessor such as `Document.note` / `Editor.statusHint`)
turns it into words in the active language. `Msg.text(s)` is for text that is *format* and not UI — an
element's name, a number already formatted, an exception's own message (OP-18). A message argument may itself
be a `Msg` and renders in the same locale, which is how a refusal names a face in the reader's language.

- **Adding a string**: add the key, its English text and a real `@key` `description` to `l10n/app_en.arb`
  (the description is what DeepL is given as *context*, so write one that disambiguates), plus typed
  `placeholders` if it has any (`"type": "message"` for an argument that is itself a `Msg`). Call the
  generated accessor — `Messages.<key>()` where a `String` is wanted, `Msgs.<key>()` where the value is.
  Keys are `tool.<id>.title|help|slot.<n>`, `category.<name>`, `slot.<kind>`, `ui.*` for the panel, `msg.*`
  for a chrome note, `refusal.<area>.*` for why the engine cannot build something, `note.<area>.*` and
  `status.<area>.*` for what a gesture did, `name.*` for what a face or an edge is called, and `word.*` /
  `phrase.*` / `list.*` for the pieces a longer sentence takes as arguments.
- **`index.html` states keys, never words**: `data-i18n`, `data-i18n-title`, `data-i18n-placeholder`, which
  `Main.kt`'s `applyStaticText()` fills in and refills when the language changes.
- **Two tests fail the build on an English sentence left in Kotlin**: `ChromeBundleTest` for `Tools.kt`,
  `Main.kt` and `index.html`; `EngineBundleTest` for `geom/`, `dsl/`, `core/`, `Document.kt` and
  `Editor.kt`. A scalar slot's name is exempt by rule: it becomes a *parameter name in the file*, so it is
  format and stays locale-neutral (OP-18); so is a `require`/`throw` that states a programming invariant, and
  each of those is listed by hand with its reason.
- **Never build a sentence by concatenation.** A clause that is sometimes empty is a `{name}` placeholder of
  type `message` filled with `Msg.EMPTY`; a choice of words is an ICU `select` *inside* the pattern, with the
  article in each branch (German declines, English does not); a count is an ICU `plural`, never
  `+ (if (n == 1) "" else "s")` — that idiom renders *"2 Elements"* in German.
- **Formatting is ICU MessageFormat**, done by the reference engines behind one `expect fun formatMessage`
  — ICU4J on the JVM, FormatJS's `intl-messageformat` in the browser. Never hand-roll a formatter, and:
  prefer `{name}` to `#` inside a plural (the translation pipeline mangles `#`); write `''` for an
  apostrophe that touches a brace, because `'{n}'` **quotes the brace** and renders the literal text `{n}`;
  and never leave a `select` branch empty (the translation pipeline refuses it, and it always wants to be a
  plural instead).
- **`translateArb` is not part of the build**: it spends DeepL characters, so it is run by hand when the
  English bundle has changed. It needs `deepl.apiKey` in `~/.gradle/gradle.properties`, and
  `--no-configuration-cache` (the plugin reads `Task.project` at execution time). `l10n/app_de.arb` is
  committed **like a golden**: machine-written, then reviewed by hand — and a hand fix survives later runs,
  because the plugin reuses an existing target entry whose English source is unchanged. `l10n/glossary/`
  pins the terms of art DeepL cannot know.

## Architecture

Layered so the UI shell is a late, reversible choice. The engine is pure Kotlin shared between JVM
(tests) and browser (`src/jsMain`) — **keep `commonMain` free of platform APIs.**

| Layer | Location |
|-------|----------|
| Core DAG engine | `core/Model.kt` — `Node`, `SourceNode`, `ParameterNode`, `OpNode`, `Evaluator` |
| Geometry + units | `geom/` (`Vec2`, `Line`, `Circle`, `Arc`, `Profile`, affine), `units/Units.kt` (dimensional analysis) |
| Construction DSL | `dsl/Construction.kt` (typed builder), `dsl/Shapes.kt` (macros) |
| Editor core | `editor/` — `Document`, `Editor`, `Tools`, `HitTest`, `Camera`, `SceneRenderer` |
| Export / preview | `exchange/` — `ExportScene` (the neutral seam), `Glb.kt`, `ThreeMf.kt`, `Stl.kt` (pure byte producers) |
| Browser shell | `src/jsMain` — `Main.kt` (DOM chrome), `BrowserCanvasDrawTarget.kt`, `Preview3.kt` (three.js) |
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
