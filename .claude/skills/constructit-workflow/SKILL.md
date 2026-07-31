---
name: constructit-workflow
description: The proven working method for developing ConstructIt — orchestrating sub-agents, adversarial probe reviews, the verification gate, deploy loop, GitHub-issue triage, and the design/format doctrines. Load at the start of any session doing feature or bug work on this repository.
---

# The ConstructIt working method

This skill captures the meta-knowledge of the sessions that built this project (23 resolved OPs,
950+ tests, ~30 agent deliveries). Follow it and a session produces the same quality. The two
documents it complements, never duplicates: `CLAUDE.md` (conventions) and `DESIGN.md` (the design
record — **read the relevant OP sections before touching anything**).

## 1. The operating loop

Work is a pipeline, not a pile:

1. **Intake**: user reports (chat or GitHub issues) and the queue in DESIGN.md's "Open work queue"
   section. Bugs outrank queued features. Triage every new GitHub issue with a diagnosis comment
   *before* fixing.
2. **Reproduce before dispatching.** A user report gets a headless reproduction first — load their
   pasted `.cit` verbatim, drive gestures, print state. Half the reported "bugs" are your own
   probe-gesture errors (see §4 traps) and a quarter are already-fixed or design-correct behavior;
   know which before an agent burns tokens. If the main tree is agent-owned, reproduce in a git
   worktree at HEAD (`git worktree add <scratch>/deploy <commit>`), never in the live tree.
3. **Dispatch** one sub-agent per coherent package (§2). Sequential by default; parallel only with
   disjoint file ownership stated in both briefs (§3).
4. **Review with a novel probe** (§4). This is non-negotiable and has caught a real defect in
   roughly every third delivery.
5. **Gate** (§5), **commit** (§6), **redeploy** (§7), close the issue with the fixing commit +
   a summary comment naming the regression test.
6. **Before ever finishing: `gh issue list -R haumacher/constructit`.** Stop only when issues and
   queue are both empty. Keep the queue mirrored in DESIGN.md ("Open work queue") so a crashed
   session loses nothing.

Track packages with TaskCreate/TaskUpdate; keep statuses honest (in_progress on dispatch,
completed only after the commit).

## 2. Agent briefs — what makes them work

Use Opus for feature/bug packages (per the user's standing directive: Opus or cheaper; Sonnet only
for genuinely mechanical chores). Run in background; you'll be notified. A brief that produces a
good delivery contains, in order:

- **Grounding**: "Read CLAUDE.md, then DESIGN.md: <the exact OP sections that govern this work>.
  Study <the specific files and the specific tests that are the behavioral contract>."
- **The task as design intent**, not implementation orders — but with the load-bearing decisions
  made: what is structural vs value, what is recorded vs derived, what refuses vs heals. Quote the
  user's own words where they designed the feature (they often did, and their design is usually
  better than your draft — patterns-as-orbits and stated-anchors both came from them).
- **User fixtures embedded verbatim** as required regression tests.
- **Explicit acceptance tests**, including exact numbers where computable (volumes, positions,
  byte-equality). "save → load → save byte-equal" belongs in almost every brief.
- **The standing rules block**: ALL existing tests green; `--rerun-tasks` on stale
  NoClassDefFoundError; ktlintFormat; jsBrowserDistribution builds; `-De2e=1` green; do NOT
  commit; commonMain platform-free; comments cite OP-n; **nothing half-done — cut whole items
  only, refuse in-app with a reason, and report every cut**; update DESIGN.md (as-built note,
  queue retirement, discussion-log entry).
- **Ask for a report**: mechanism chosen and why, alternatives rejected, cuts, test count
  before/after. The report is your review input; a vague report predicts a vague delivery.

**Reworks go back to the same agent** via SendMessage with the failing probe path and the demand:
*fix it generally, not to the probe* (user directive). If several rework rounds fail, question the
architecture, not the tests.

**Stalled agents are real.** If a background agent's transcript goes quiet: arm a Monitor on the
output file's mtime (10-minute quiet threshold, until-loop, not tail -f). On silence: nudge-resume
via SendMessage ("Resume exactly where you left off: <last visible step>"). If the transcript
stays frozen after a resume, the agent is dead — take the remainder over yourself; its committed
work is usually further along than the last message suggests (check `git status` and run its
tests before redoing anything).

## 3. Concurrency — the hard-won rules

- **Never let two agents edit the same file.** The one time it happened produced duplicate
  function definitions and a broken commit pushed to master. When parallelizing, write an explicit
  FILE BOUNDARY paragraph into *both* briefs ("do not touch X, Y, Z; if you must, STOP and
  report") and keep Editor.kt/Document.kt ownership singular — everything routes through them.
- **Concurrent gradle runs corrupt incremental compilation.** Symptom: `NoClassDefFoundError` on
  inline-lambda classes in tests that were green. It is never a real failure; `--rerun-tasks` (or
  a clean build) resolves it. Put that in every brief; don't chase it yourself either.
- Don't run gradle in the main tree while an agent is mid-edit; use the side worktree.
- A commit made while an agent has uncommitted work in the tree must be **selective**
  (`git add <files>`), and check `git diff DESIGN.md` attribution first — both of you edit it.

## 4. The probe review (the quality mechanism)

After a delivery reports done and before committing: **write a test the agent never saw**,
composing the new feature with pre-existing features — persistence round-trips, undo, placed
groups/frames, patterns, face spaces, booleans, the naming authority. Good probes ask "is the
mechanism general?" not "does the happy path work?". Keep passing probes as permanent tests
(named `<Feature>ProbeTest`).

**Expect your own probes to be wrong first.** The recurring traps, all of which have burned a
probe at least once — check these before blaming the delivery:

- **Gesture geometry**: grabbing a jamb on the centerline (the leg wins by design — grab between
  the faces); dragging within weld-magnet range (it welds — that's correct); clicking a circle's
  *center* for a GEOMETRY slot (the point wins — click the outline); vertical vs horizontal wall
  coordinates copied between probes.
- **Shared nodes**: clicking an existing point *reuses* it — a probe that clicks a circle's
  center for an array vector has coupled them (correct construction semantics, wrong probe).
- **State machine**: the click-cycle (first click = group, second = member); the active sketch
  space (creating a datum/face switches the view — switch back with `setActiveSpace("plan")`
  before plan-space gestures); dimension tools take a final "where the line sits" click.
- **API drift**: `assertClose(actual, expected, tol, msg)` uses `msg=`; quantities are
  dimension-checked (`.mm` throws on an angle — use `.value`/`.deg`); collections get renamed
  (grep before writing).
- **Undo layering**: an *uncheckpointed* live edit (`doc.setParameter`, a direct node mutation)
  reverts on the FIRST undo — the previous checkpoint is one more press away. Assert the layer
  you actually expect.
- **Stale handles after undo/reload**: undo (and any journal re-stamp) rebuilds the Document —
  every `Element`/`ScalarEntry` handle fetched before it now reads the OLD graph. Re-fetch from
  `ed.doc` after any operation that reloads.
- **Runtime vs script ids** are unified now (the naming authority) — but user reports predating
  it may mix them; decode against a fresh load.
- Playwright: check the canvas bounding box before computing world→screen (a small viewport
  silently puts clicks in the toolbar); `page.mouse.wheel` fires at the current cursor position.

When a probe legitimately fails: send it back (§2). When it exposes something deeper than the
package (an engine sharp edge, a doctrine gap), fix small ones yourself with a regression test;
spawn a package for large ones. Three probe-caught classics to stay paranoid about: silent
refusals (nothing may decline without a status message), forked feature chains (sequential cuts
must target the part's tip), and cracked shells (assertManifold on every solid in every test).

## 5. The verification gate (before every commit)

```bash
./gradlew ktlintFormat                                   # then check its output for errors
./gradlew jvmTest ktlintCheck jsBrowserDistribution -De2e=1
```

All green or no commit. On weird failures: `--rerun-tasks` (stale compilation), then a real look.
The E2E needs Playwright browsers; it has always worked here. Count PASSED lines when in doubt —
"BUILD FAILED" from a racing daemon has lied before; a clean rerun is the arbiter.

## 6. Committing

- House style: a poetic-but-precise one-line title stating the *principle*, then a paragraph of
  the why (never a bullet changelog), ending with the Co-Authored-By line from CLAUDE.md's rules.
  `Closes #N` for issues. Push to origin/master every time (standing directive).
- The agents don't commit; you do, after review. DESIGN.md updates ride the same commit as the
  code they describe.

## 7. The deploy loop

The user tests live against `http://localhost:8123/` served from
`build/dist/js/productionExecutable` (python3 http.server, started once, survives). After each
commit: `./gradlew jsBrowserDistribution` refreshes the served files in place; tell the user the
commit hash and to hard-reload (Ctrl+Shift+R). If the tree is dirty with agent work, build in the
side worktree and copy the bundle over. Never redeploy a broken tree — the served build is the
shared reference for bug reports.

## 8. Design and format doctrines (enforce in every brief and review)

These were each learned from a real defect; treat them as law:

- **DESIGN.md is the contract.** Every feature lands with an as-built note; every reversal quotes
  the old rationale; deliberate cuts are recorded, never silent. The "Open work queue" section is
  the crash-safe pipeline.
- **Recorded, never discovered**: anything the editor derives for the user (outline follow, orbit
  replication, snap links) is written into the journal as explicit steps; replay re-discovers
  nothing.
- **A click is a choice, state restates as a value**: clicks stay verbatim in steps; positions/
  parameters restate current values; **scored choices persist as signs** and are never re-scored
  on replay (re-scoring flips under geometry drift).
- **Format versioning**: a stored literal's meaning is frozen the moment a build that writes it
  might have shipped. Semantic changes require a header version bump + load-time migration that
  prefers recorded positions and *reports* (loadNotes) instead of guessing. Keep fixture files
  written by older builds as permanent load tests — in-build round-trip tests prove nothing
  across builds.
- **Refusals speak**: no route may decline silently; name the element (script name) and the
  alternative.
- **Watertight or refused**: assertManifold on every solid in every test; exact paths never
  degrade silently to mesh paths (dispatch by predicate up front).
- **Explicit anchors beat compensation**: where the user can state a dependency (relative points,
  carrier offsets), build it; gesture compensation is only the fallback for unstated anchors.
- **Structure at build time, values at eval time** (OP-21's rule): counts are structural;
  anything value-dependent lives inside compute.
- **Everything generic**: showcases and issues are exercises of general mechanisms — if a fix is
  shaped like the bug report, it isn't done (user's standing directive).

## 9. Working with this user

They are an expert (mechanical/architectural CAD, software architecture) testing continuously.
Their bug reports come as pasted `.cit` scripts — embed them verbatim as regressions. Their
feature sketches ("do you understand what I mean?") are usually complete designs — restate the
design back crisply, adopt it, credit it in DESIGN.md. When they overrule a recorded decision
(hide-state persistence, framed-by-default), the reversal is documented, not litigated. Answer
their questions with the mechanism, then the plan, then queue position. Redeploy notices always
include the commit hash and what to try.
