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

- **Construction DAG** — strongly-typed nodes (points, lines, rays, segments, circles, arcs, **ellipses and
  elliptic arcs**, Béziers, **function curves**, directions, profiles, scalars) with memoized evaluation and
  transitive invalid-propagation.
- **Deterministic intersections** — an intersection is an ordered solution set + a `Select` node, so branch
  choice is stable across recompute, undo and reload (no continuity tracking). Two conics cross in **up to
  four** points; the set is ordered by parametric angle on the first operand and the branch is stored as an
  index, so it never re-decides itself, and a branch that stops existing goes invalid *with a reason* and
  heals rather than silently becoming another point.
- **Unit-aware scalars** — dimensional analysis over Length / Angle / Dimensionless (+ Area /
  Volume); base units mm and rad.
- **Rich 2D tool set** — points, midpoints (or any ratio along a span), intersections, projections, perpendiculars, parallels,
  bisectors, tangents (from a point / common), fillets and chamfers (between lines, circles and arcs alike —
  a chamfer's setback is measured *along* each leg, so on a round one it is an arc distance), circles
  (centre+point / centre+radius / 3-point / **3 tangents** — the incircle and the three
  excircles of three lines), arcs, **ellipses and elliptic arcs**, rectangles (rounded or not) and regular
  polygons, transforms (mirror / rotate / scale / translate), linear and circular **arrays**, and
  measurements. Any tool that wants a line takes a segment or ray, and any tool that wants a circle takes
  an **arc** — the carrier is what the construction is about, and an elliptic arc likewise carries its whole
  ellipse.
- **Conics are first-class, and exact where it counts.** An ellipse is centre + axis end + a second
  semi-axis, every one of them a node: click an existing point and it is shared, bind the axis end to a
  line's direction and the ellipse turns with that line. A point riding an ellipse lives at its **parametric
  angle**, so its position, tangent and normal are exact trigonometry — nothing forces arc length to be the
  parameter. Areas are exact too (a whole ellipse encloses π·a·b to the last bit). What is honestly
  *approximate* is only what is genuinely metric, and it says so: a conic's measured **length** is computed
  to a stated tolerance (an elliptic integral has no closed form), and a wall thickened over an elliptic
  carrier is flagged, because an ellipse's offset is not an ellipse.
- **A curve a formula defines — and no new curve type per curve family.** Write `x(t)` and `y(t)` over a
  stated domain — whose two ends are numbers *or* formulas themselves, so a gear flank's length follows a
  teeth count — in the same expression language a parameter's formula is written in, reading any named
  scalar in the drawing: an **involute**, a cycloid, a spiral, a catenary and a lemniscate are one element
  with five different texts. The two texts *are* the record — stored verbatim, re-stamped when a scalar is
  renamed, parsed back on load — and the scalars they read are ordinary inputs, so editing one moves the
  curve by plain recompute. Position along it is **exact**: a rider lives at the parameter, and the point,
  the tangent and the normal there come from the expression and its **symbolic derivative**, closed-form and
  never a numerical difference; a function whose derivative this vocabulary cannot state still draws and
  still carries a rider, and refuses only the constructions that need a tangent — by name. It mirrors,
  rotates and patterns **exactly** (an affine map composes with the function), traces into a loop beside
  segments and arcs, and extrudes to a watertight solid. What stays honestly approximate is what is
  genuinely metric: its measured length, its offsets, and its intersections — numeric, but deterministic and
  ordered by parameter along the curve.
- **A swept section whose size is a formula over the run.** The same expression language, over the same
  parameter `t` — 0 at the start of a run, 1 at its end: a tube's **radius** may be `5mm * (1 - t/2)` (a
  tapered handle) or `2mm + 8mm*t*t` (a horn), and a picked area takes a **scale** about the point it rides
  on, so `1 + t/2` is half again as large at the end as at the start. It reads any named value in the drawing,
  re-stamps when one is renamed, and stores verbatim; every one of the sweep's own refusals — the bend it must
  fit round, the run it must not come back into, the corner it must not mitre away — is read at the size the
  law states **at that station**, so a run that is thin where it bends and thick where it is straight builds
  where a section of one size could not.
- **Live previews — what the click will make, before you make it.** Every drawing, transform, rounding and
  dimension tool paints its result under the cursor as you move: the growing circle, the circumcircle through
  your two picks and the pointer, the rectangle's outline, ghost copies of what a mirror or an array would
  produce, the ring a pattern would stamp, a dimension riding the cursor — with the typed radius, the default
  and the current count already in the picture. Where a tool has to *choose* something from where you click —
  which quadrant a fillet rounds, which of four circles is tangent to your three lines — the choice becomes
  visible before it is committed, because the preview runs the very scoring the click will run. Previewing is
  pure computation: it never adds anything to the model.
- **Patterns are orbits, not copies** — a *pattern* is a rule (a centre, a reference point, a count), and
  **any later gesture whose inputs touch its members is repeated round it**: one segment makes every side of
  a hexagon, one fillet rounds every corner, and the outputs join the pattern in turn, so features built on
  replicated geometry replicate too. Because the copies are built on the *shared* member points, adjacent
  copies share a node outright — no seam, so the outline tracer walks the whole ring in two clicks and the
  result extrudes watertight. Alt keeps a feature a deliberate one-off, an input from outside the pattern
  declines to repeat and says why, and the **count is editable afterwards**: re-stamping rebuilds the ring
  and re-runs every gesture at the new count. *Regular polygon* with a corner radius is that whole
  composition in one gesture.
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
- **Expressions** — the same binding, generalized: type a formula into a parameter's row and it is
  *derived* from the other named values — `d/2 + 1mm`, `sin(a)*r`, the whole `java.lang.Math`
  vocabulary plus plain operators, with units in the literals (`10mm`, `15°`) — and a named point's own
  coordinates, `P.x` and `P.y`, read through the same names everything else is named by. One direction only:
  `r = d/2` **defines** `r`, so it is an ordinary set of DAG edges and no solver is involved. The
  text is stored verbatim and re-stamped when something it reads is renamed; dimensions are checked
  (`sin` takes an angle, `sqrt` halves the exponents) and a violation just makes the value invalid
  with a reason, which heals when you fix it. What is derived refuses the drag, and says which
  formula to change.
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
  A wall's carrier need not be rectilinear: *Thicken* takes **any connected network of curves** —
  segments, arcs, Béziers — with the side (left/right/centred) chosen per curve, and where three or
  more walls meet the footprint resolves by the cyclic order of the curves there, so a T-junction is
  one area with no overlap and no sliver. Curves need not meet end to end: a partition whose end lands
  **part-way along** another wall's carrier joins it there, with no seam. And a wall is **extensible** —
  click it with *Thicken*, then click the curves to add: the wall keeps its identity, its thickness and
  its openings, and the solids and dimensions built on it follow, in one undo step. Arc walls offset to real concentric arcs; a Bézier's offset
  is honestly sampled. A footprint's corners are extractable as ordinary snappable points.
  In plan the wall stays whole and the gap is drawn as the convention it is — faces broken, jambs
  shown — while *Cut openings* turns the same description into the 3D cut: one subtracted box per
  opening, sill to head, following the parameters live. Those drawn jambs are **grabbable**: dragging
  the near one slides the whole opening along its wall, the far one sets its width, and both clamp to
  the wall's extent — the same two numbers the panel shows, since dragging and typing are one thing.
- **Export, and a realistic preview** — the finished solids leave the app four ways, all off the *same*
  neutral scene: **GLB** (glTF 2.0) for viewing, one named node and one PBR material per solid, with glTF's
  metres and +Y-up applied once at the root so every vertex in the file is still the model's own millimetre;
  **3MF** for printing, with the unit stated (`unit="millimeter"`) and every mesh re-checked watertight before
  it is written — refused **by name** if it is not; **binary STL** as the universal fallback; and **JT**
  (ISO 14306) for CAD interchange, written by the [kotlinJT](https://github.com/haumacher/kotlinJT) sibling
  library off the same scene — millimetres declared in the file, one named part per solid, coordinates
  unconverted because JT declares no up-axis. Hidden and
  invalid solids are named rather than silently dropped, a boolean's operands are not exported beside the part
  they built, and an empty drawing refuses with a reason. Alongside them, a **realistic preview** on three.js:
  a third, display-only view where the solids are lit, tone-mapped and shown with their materials — reading the
  same scene the GLB writer does, so what you see is what the exported file contains. It re-uploads only the
  bodies that actually changed, and the library is fetched the first time you open the panel, not with the app.
- **A material per solid** — base colour, roughness and metalness, one row in the inspector, recorded in the
  drawing like any other decision and restated on every save. Five numbers is what makes an exported file
  render honestly in any PBR viewer — and the preview and the export read the same five.
- **Browser canvas** — an interactive HTML5-canvas editor; the engine is pure Kotlin shared between
  the JVM and the browser.

## Architecture

Kotlin Multiplatform, layered so the UI/shell is a late, reversible choice:

| Layer | Location | Notes |
|-------|----------|-------|
| Core engine | `src/commonMain` | model, type system, DAG eval, geometry ops, DSL — zero UI deps |
| Editor core | `src/commonMain/.../editor` | document, tools, camera, hit-testing, scene renderer (behind a `DrawTarget` seam) |
| Exchange | `src/commonMain/.../exchange` | the neutral export scene + the GLB / 3MF / STL writers and the JT adapter — pure byte producers, no platform |
| Browser shell | `src/jsMain` | HTML5 canvas `DrawTarget` + DOM chrome (palette, panels), one WebGL program for the 3D view, three.js for the preview |
| Tests | `src/jvmTest` | headless gesture tests, SVG golden snapshots, opt-in Playwright E2E |

The renderer draws through a backend-agnostic `DrawTarget` (SVG for tests, Canvas2D in the browser),
so the interaction core is fully headless-testable by simulating pointer gestures. The 3D view works
the same way: the orbit camera, the scene extraction, a painter's-algorithm projector and the screen↔plane
projection that lets that view *edit* all live in
`commonMain`, so a 3D scene has SVG goldens and both orbit and drawing gestures have headless tests — the browser
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

**A cut does not need the part it removes to be bounded.** Shaping a body — taking an inclined face off a
casting, opening a slot right through a plate, splitting a housing into its two halves — is not naturally
said with a box sized by eye. It is said with **a curve and a side**. *Chain (cutting curve)* draws that
curve: click the points it runs through and press Enter, and the first and last become **rays**, so the chain
runs to infinity at both ends and genuinely divides the drawing into two. Two clicks give an infinite line, a
third bends it into a step, and the points stay live like everything else — drag one and every cut made with
it follows. Then *Cut by chain* takes the solid, the chain and a click on **the side to keep**, and *Split by
chain* keeps both halves as two solids. You need not draw a chain at all where the drawing already holds a
curve that separates the plane: an **infinite line** *is* the two-click chain — including a **mirrored** one,
so a symmetric pair of cuts costs no second line aimed by eye — and any **closed** curve — a circle, a traced
outline, a rectangle, a wall footprint — fills the same slot too, so a circle cuts a through-bore through the
very same tool. (A *ray* does not: it stops, so the plane closes round its end and there are not two sides to
choose between. It says so, and names the line it is one click from being.)

**The cut runs square to the plane the chain is drawn in**, whichever view you are in — so a line drawn in
the plan trims a body sketched on an upright plane vertically, which is usually what you want and is the one
sentence worth knowing. Because a solid is a *body* rather than a drawing, the picks may be made in different
spaces: click the solid, switch the sketch plane, click the chain there. And a solid is clicked wherever the
app draws it — its footprint in the space it was sketched in, its section where a working plane cuts it, or
**the body itself in the 3D view**, where the click goes to whatever is actually under the cursor.

The side you clicked is **remembered**, not re-decided: move the chain across the body afterwards and the
same half survives, because which side to keep belongs to the gesture that said it rather than to the
geometry as it stands. What makes this more reliable than the big box it replaces is that the tool is bounded
to the target's **own** extent plus a margin, worked out afresh every time it is evaluated — so it closes
strictly outside the material, a face of the tool can never land exactly on a face of the part, and a part
that grows is cut through anyway because nothing about the bound was ever stored. Refusals speak and heal
like every other: a chain that misses says the cut leaves the solid untouched (which is what picking the
wrong side looks like), one that would remove everything says so rather than showing nothing, and a chain
that **crosses itself** is refused because a curve that does not separate cleanly has no two sides to choose
between. What comes out is a solid like any other: watertight, a legal operand of the next boolean, drawn as
a footprint in plan, exported to every format, one undo.

The seam runs **both ways**. *Extrude on face* raises an area from a solid's top face — the plan is drawn
in the same 2D space, so an upper storey or a boss needs no datum-plane UI — and *Section* cuts a solid at a
height back into ordinary 2D geometry, which is **exact** for a prism (the section *is* the slab there) and
analytic for a plain extrude (its circles stay circles). A section is an area like any other, so it can be
dimensioned, measured, or extruded again: storey 2 is built from the section of storey 1, and one drag of a
ground-floor wall reshapes both. Volume and per-axis extent land in the panel as read-only values that may
drive *new* construction — forward only, never back into their own solid.

**And on any planar face.** A drawing lives in a **sketch space**: the plan (world XY) by default, or a
space on a solid's *side* face — created by clicking one of its footprint edges, because that edge is what
the face projects to seen from above. The 2D canvas switches to that face and draws in its own coordinates,
and that frame is **intrinsic**: the edge you picked lies on the x axis about its own midpoint, `v` runs up
into the face, and the normal points at you — you are looking *at* the face. Which way a feature builds is the
operation's, not the space's: *Extrude* follows the plane's normal and builds a **boss** standing out of the
material, *Cut* goes the other way and drills **into** it (extrude and subtract in one gesture). Where the
coordinates *start* is yours to move: *Space origin* anchors them on a corner of the part's own section on
that plane — plus an optional (dx, dy) — and since the anchor is a node, the origin follows that corner
through every edit; anchoring a plane that already carries a sketch moves the sketch with the frame, which is
how a whole drawing is shifted on its face. The frame is
derived from the part, not captured from it — stretch the plate and the hole rides the face, still 25 mm
from the edge it is measured from. That is what makes the plainest mechanical feature there is, a hole drilled in an edge, a
matter of four clicks; the cut across the part's own axis is the general engine's, and the file records
which space each step was drawn in. Features **chain**: each cut takes the part as it now stands, so a
second bore on a second face lands in the part with the first bore in it rather than beside it, and the step
records which solid it cut so a reload rebuilds the same chain. The elements panel follows the view: it lists
the active space's 2D geometry plus the solids, which belong to no space and are shown in the 3D view.
A **flat face of a lofted solid** is a face space too — every face of a pyramid — while a ruled or curved one
says so and points at *Sketch plane*.

**...and on any plane at all.** Sketch-on-face turns out to be the special case: *Sketch plane (line + angle)*
takes **any** line in the drawing — a segment, a construction line, a wall's centreline, a footprint edge — and
**any** angle, and gives a sketch space whose plane contains that line and is tilted out of the current one by
that angle. At 90° on a footprint edge it *is* the face plane sketch-on-face derives; at 45° on a plan segment it
is a miter. `u` runs along the line, `v` rises out of the old plane, *Extrude* builds along the new plane's
normal and *Cut* goes the other way — so the **sign** of the angle chooses which. The angle stays a parameter:
retype it and the plane tilts, with every feature built on it following, and a tilted cut through a part is one
gesture (a 45° miter through a plate: exactly the wedge, watertight). Planes compose — a plane on a line drawn on
another plane — and the file records the line and the angle, never the frame, so a part edited since comes back
with its planes where they now are.

**The solid whose cross-section changes: the loft.** A prism and a revolve both sweep *one* profile, so the
simplest shape they cannot make is a pyramid. *Extrude to point* is that gesture — click an area, type a
height, click where the apex belongs, and the apex is a **real point of the drawing**: drag it and the pyramid
leans (same volume, Cavalieri), retype the height and it grows, click an *existing* point and the pyramid and
whatever else uses that point move together. The apex is a **height point** — a base point on the sketch plane
plus a height along its normal, one degree of freedom over the base — which is a point anyone can build with
*Height point* and use anywhere: the height is an ordinary named parameter (rename it, wire it onto another),
and in the **3D view** you can grab the apex itself and drag it up and down, which writes that parameter
(the pointer's ray against the height line says how far). Wire the height to something else and the drag
declines and says what drives it, exactly as a welded point does. A circle instead of a polygon is a cone. *Loft (sections)* is the
general form: click sections in order — outlines, wall footprints, circles, or a point to end the run — and
they may live on **different sketch planes**, so a frustum is the plan's square and a square on a plane
parallel to it (*Sketch plane* takes an offset for exactly that). Three or more sections blend piecewise, and
an **open curve** among the picks is a **guide** the run follows instead of the straight ruling — it has to
pass through corresponding points of the sections it spans, and says by how much it misses when it does not.
Where you click each section starts its boundary **correspondence**, which is the one choice a loft carries:
the preview draws the rails before you commit, the choice is written into the file, and a reload is the solid
you chose rather than the one today's geometry would score. Polygon runs are **exact** — the acceptance
pyramid is 300000 mm³ and the frustum 392000 mm³, to the last digit, with planar facets — while a curved
section or guide is flagged **approximated**, the same bargain a Bézier or an ellipse offset makes. A loft is a solid like
any other: cut it, fuse it, dimension its footprint, chain features onto it.

**A curve that leaves the plane: routing through points in space.** Everything above is flat or a prism of
something flat, and a cable, a tube, a handrail or a ramp is none of those — each is a path through space.
*Curve through points* is that path: click the points it runs through, press Enter, and what you get is a
curve of the drawing like any other — named, styled, hideable, selectable, deletable, one undo for the whole
run. *Smooth curve through points* is the same gesture with the corners rounded off, an interpolating cubic
that passes exactly through every point you clicked and leaves each end along the line to its neighbour;
clicking the **first point again** finishes the run *and* closes the curve. What makes it parametric rather
than drawn is that the points are **shared, not copied**: click a height point (or a pyramid's apex, which is
one) and dragging that point's base in the plan, or retyping its height, moves the curve — and moves
everything else built on that point at the same time. The 3D view draws the curve where it is, behind the
bodies it runs behind; the 2D canvas draws its projection onto the plane you are working on, and a click
reaches it in either view. It is the first half of the sweep — a profile carried along a path — which is what
it is for.

**…and the sweep that rides it: a tube, a conduit, a handrail, a moulding.** *Tube along a curve* is a radius
and one click on the route. *Sweep* takes the route and then any closed area you have drawn — an outline, a
wall footprint, a circle, a rounded rectangle — and carries it along. The section is read in a **moving frame**
that turns with the run and never rolls: it is carried along the path introducing no twist about the tangent,
which is why a run of straight–bend–straight comes out whole where the textbook frame tears it, and why a
section stays the same way up through an S-bend instead of turning over at the inflection. Which way "up" is
at the start comes from the sketch space the route was drawn in, so tilting that plane rolls the sweep; a
**roll** turns the section about the run at its start, and a **twist** turns it by so much from one end to the
other — both ordinary parameters, both zero unless you say otherwise. The area's own origin sits on the path,
so a section drawn off to one side runs off to one side, and a section with a hole in it sweeps a **pipe**.
Corners are **mitred**, ends are capped square to the run, and a closed route needs no caps at all.

**And the section may change size along the run.** Type a formula into *Section law* in the panel — the same
expression language a parameter's formula and a function curve's texts are written in, over a parameter `t`
that runs from 0 at the start of the run to 1 at its end — and the section is scaled by it, station by station.
A tube reads it as the **radius itself**: `5mm * (1 - t/2)` is a tapered handle, `2mm + 8mm*t*t` is a horn. A
picked area reads it as a **scale** about the very point it rides the run on: `1 + t/2` is half again as large
at the end as at the start. The formula reads any named value in the drawing (and a named point's coordinate),
so a taper follows a dimension like everything else; select a swept body and the field shows its own law back
for editing, and clearing the field makes it a section of one size again. What is carried is the section you
drew, larger or smaller — never a re-drawing of it.

What it refuses, it refuses by name and it heals. A section too big to go round a bend would fold through
itself, so it is refused with the place said out loud — *"the tube's radius (12 mm) is larger than the bend at
340 mm along the path (radius 8 mm)"* — and the moment the radius comes back down the solid is there again. A
A size law that goes to nothing part-way along says which station and what value — *"a tube needs a positive
radius — r(t) = 5mm * (1 - 2*t) is -5 mm at t = 0.5 along the run"* — and every one of those refusals is read
at the station it is about, so a run that is thin where it bends and thick where it is straight builds. A
closed route that leaves its plane will not generally bring the frame back to where it started; rather than
hiding that twist in the last piece, the drawing says how far out it is and what twist would close it, and
typing that number closes it. Everything else is what any solid gets: it shows in 3D, draws a footprint you can
click in plan, unions and subtracts with the parts around it, exports to every format, and one undo takes the
whole gesture back.

**Breaking an edge of a solid — the 2D fillet, one dimension up.** *Fillet edge* and *Chamfer edge* take a
radius (or a setback) and one click on a body near the edge you want broken; *Fillet the edges of a face* and
*Chamfer the edges of a face* take one click **on** a face and break its whole boundary in one gesture — the
two flat ends of an outline revolved less than a full turn, the rim of a plate, the eight pieces of a rounded
rectangle's rim, with no crack where the boundary runs on smoothly from one piece to the next. It is the same
construction the 2D *Fillet* tool is, run in the plane square to the edge and carried along it: which is why
"fillet the outline first" was never a different feature, and why an inside corner is **filled in** rather
than cut away by the same arithmetic with the other sign. The size stays an ordinary parameter — retype it and
the body re-rounds, feed one parameter to several blends and they stay equal *by construction* — and it takes
an expression like any other scalar, so a radius of `d/4` follows `d`.

The result is a body with a **face list of its own**: the part's faces are still there, still named, with the
rounding's own band added — so you can sketch on a rounded part's face, drill a *Cut* from it, and click the
edges and corners of a working plane's section of it as construction inputs, none of which a plain mesh result
could offer. Blends chain: round one edge, fuse a pad on, chamfer another, and it is one part throughout. What
it declines, it declines by name and heals: a radius that reaches past one of the two faces says so *and names
the largest that fits*, so the message is a number to type; an edge whose shape changes along it (a turned cap edge over a slanted piece) is named as the
future extension it is. Two limits are stated rather than hidden: a corner where three or more broken edges
meet stays sharp, and a blend applied to a body that was **fused** with another one is a mesh body — it draws,
measures, prints and exports, and its section offers no inputs.

**Hollowing a solid to a wall thickness.** *Shell (open a face)* takes a wall thickness and one click on the
body **on the face you want left open** — a cup, a box, a housing — and *Hollow (closed shell)* leaves the body
closed all round, which is what a sealed vessel or a float wants. The cavity is the body's own profile stepped
inward by the thickness, *exactly*: straight walls stay straight, round ones stay round, arcs offset to arcs, so
nothing is approximated and the wall is that thickness everywhere. In the 3D view the face you open is the one
your pointer is on; on a flat canvas it is the face you are looking at where you clicked, and that choice is
recorded, so a reload never opens a different one. The thickness stays an ordinary parameter — retype it and the
part re-hollows, feed one parameter to two shells and they stay equal *by construction*, bind it to `d/8` and it
follows `d`.

The result is a body with a **face list of its own**, exactly as a rounded one is: the outside is still there,
still named, the face you opened becomes the wall's own **rim** (with the cavity's boundary as a hole in it),
and the inner faces are added — so you can sketch on the outside, sketch on a **pocket floor**, drill a *Cut*
through the wall, and a working plane's section of it shows **both** walls and offers both as inputs. What it
declines, it declines by name and heals: a wall the body cannot host says so *and names the thickest that fits*,
so the message is a number to type. Extruded bodies and bodies revolved the whole way round can be hollowed
today; a partial revolve says why not (its wall would grow thicker with the radius), and a fused part, an
imported mesh, a swept or lofted body and an already-rounded one each name the route that does work — shell the
operands before fusing them, round the part after hollowing it, sweep a hollow section.

**A helix — a spring, a coil, the path a thread runs on.** *Helix (centre, start point, right-hand)* and its
left-hand twin take a rise per turn and — if you want more than one — a number of turns, then two clicks: the
point the axis stands on, and the point the coil **starts** at. Those two clicks state the radius *and* where
the coil begins, so a spring can come off the edge of a drilled hole or the side of a boss and follow it when
that moves — the start point is an ordinary pick, so clicking one that is already in the drawing shares it.
*Helix (centre, radius, right-hand)* and its twin are the spelling that states no starting point: a typed
radius, one click, and the curve starts beside the point along the plane's x direction. Either way the axis is
the sketch plane's own **normal** through the centre, so the coil rises out of the plane you are drawing in
and tilts with it. It is the first curve here that lies in **no** plane, which is exactly what it is for: sweep a tube
along it and you have a spring, watertight, in one further gesture. Everything stays live — drag either point,
retype a height, retype the radius, the pitch or the turn count, and the coil and everything riding it
follow. The turn count may be fractional. Which way it turns is **which tool you used**, so it is what the file
records and it never changes by itself; a negative pitch is refused by name, because a coil that descends
while it turns right *is* the left-hand coil, and the drawing should have one way to say a thing rather than
two. A pitch of nothing is refused too — that is a circle, and a circle is drawn in a space.

**A station — a sketch plane standing across a run, a stated distance along it.** *Station (plane across a
curve)* takes a distance and one click on a curve in space, and what you get is an ordinary sketch plane: the
origin sits on the curve, the normal runs along it, and the in-plane axes are the moving frame's, so what you
draw there stays aligned to itself all the way along a bend instead of slowly rolling. Draw in it, dimension
in it, extrude a fitting off it, cut a mitre or a gland with it, place a group into it — a station is the same
kind of thing a datum plane is, so nothing you already know changes. The distance is measured from the start of
the run and is an ordinary parameter: retype it and the plane slides along the curve with everything drawn on
it, and wiring two stations to one parameter moves both. Because the plane is built *from* the curve, moving a
point the run passes through carries the station and everything on it along. The distance is measured along the
curve itself — exactly on a straight run and on a helix, and by a numeric integral on a spline, good to a few
billionths of a millimetre — so a station at 340 mm is 340 mm of travel and not 340 mm of chords.
A distance past the end of the run does not refuse the gesture: the plane simply has no value, everything drawn
on it hides and says why, and bringing the number back brings all of it back.

**Combine two views — the route drawn twice, in plan and in elevation.** This is how a route was laid out on a
drawing board long before there were kernels, and *Combine two views* is that construction made parametric:
draw the run in one space, switch the sketch plane and draw it in another, click the two, and you get the curve
in space whose shadow in each of them is the curve you drew there. There is nothing new to learn — both picks
are ordinary sketch curves in ordinary spaces — and everything that already makes a drawing live makes the run
live: drag a point of either view, tilt the space one of them is drawn in, or move the line that space hinges
on, and the run follows. Where a straight view meets a straight one the answer is the exact straight run, and a
spline plan against a straight elevation is an exact cubic; where both views curve there is no such thing to be
exact about, so the run is fitted and the error is **said**: a tenth of a micron, two hundred times finer than
the tolerance any solid swept along it is meshed at. What it refuses, it refuses by name and it heals. Two
parallel spaces have no common direction, so there is nothing to combine. A view that **doubles back** along
that direction would let one place in the other view answer to two places on it — break it where it turns and
combine each part. And two views that do not cover the same stretch are not describing one run, with both
ranges said out loud; only the stretch they share becomes a run, and it goes the way the first view you picked
goes.

**Intersection curve — where a working plane meets a body, as a curve you can build on.** Every plane that is
not the plan already draws the section of every solid built before it; *Intersection curve* turns that drawing
into a **curve in space**, so you can sweep a tube along the edge of a cut, stand a station on it, or carry a
cut along it. One click does the whole gesture: click the section of the body you want, and where you click
says which curve you mean — because a plane cuts a body in **several** curves in general (a bent bar is cut in
two places, a tube gives two loops). They come in a stated order — lowest first, in the plane's own
coordinates — and the one you clicked is **remembered**, so moving the plane afterwards never quietly swaps you
onto another curve. If the curve you chose stops existing, the drawing says so in those words and everything
built on it hides until it comes back. The curve rides **both** things it was cut from: retype the plane's
height, tilt the datum, or drag the body's outline, and it follows. Where the cut is straight it is exact to
the last bit — a plate's, a pyramid's; where it is a circle or an ellipse the vocabulary has no name for that
curve in space, so it is fitted and the error is **said**: a tenth of a micron again. And a body with no
analytic pedigree at all — an imported mesh, a stack of slabs from the exact boolean — still gives you the
curve, in the chords its section already draws, and says that is what it is.

**Sphere locus — how the drawing says "40 from that corner and 55 from that one".** In the plan, distance has
always been carried by the circle: two circles cross, you click the crossing you meant, and it stays yours. A
**sphere locus** is that same thing in space — not a ball, but *every point at a stated distance from a point*,
drawn dashed as scaffolding and there to be **intersected**. Type a distance and click a corner, or click two
points and let the drawing state the distance for you. Then three gestures use it: **two loci meet in a circle
in space**, exact and usable like any other curve — sweep it, station it, ride it; **three loci meet at a
point**, which is the sentence at the top of this paragraph said by clicking; and **a locus meets a route**
where that route stands at the distance, which works on a curve in space *and* on a drawing (a footprint's own
outline is read as the run it already is). Nothing about it is solved: the corner is one node feeding the
locus, so dragging the corner moves the answer, retyping the radius moves the answer, and the whole thing
recomputes rather than re-searching. Three loci meet at **two** points, mirror images either side of the plane
through the three centres — a fourth click says which one you mean, and that choice is **remembered**, so
moving the loci afterwards never swaps you onto the other. (In the plan those two can land on the same spot;
orbit into the 3D view and the two are plainly apart.) What comes out is an ordinary point in space: the apex
of a tapered body, a point a curve runs through, the centre of the next locus. Loci that are too far apart,
one inside another, or that merely touch each say so in those words and heal the moment a radius changes.

**Connect — the bend that turns two runs into one route.** Two runs that stop near each other meet at a kink;
*Connect two curves* puts the bend between them, and the bend is not something you draw — it is *derived* from
where the two runs end and which way they are pointing when they get there. Click near the end of one curve in
space, then near the end of another (switch the sketch plane between the clicks if they live on different
planes): the join leaves each run along the way that run was already going, so the whole route reads as one
manufactured piece. **Which end you clicked is which end it joins**, and that choice is remembered, so moving
the curves afterwards never swaps ends on you. Two **tensions** shape it — plain numbers, defaulting to 1,
each a fraction of the gap: raise one and the bend runs on further along that curve's own direction before it
turns, and at 1 with the two ends facing each other the join *is* the straight segment between them. Both stay
parameters, so a whole set of bends can share one. *Connect two curves (curvature)* is the same gesture
matching each run's **curvature** as well as its direction — three cubic pieces instead of one, and still
exact, nothing fitted — which is what makes a tube along the finished route show no break at all. The join
rides both curves: drag a point either of them passes through, retype a helix's pitch, tilt the datum a
combined view was drawn on, and the bend follows and stays smooth. Ends that come to rest in the same place, a
closed run with no end to join, or a tension of nothing all say so and heal the moment the drawing moves.


**Project onto a face — the engraved line, the groove, the route that has to follow the part.** Draw the curve
where you are looking at the body — in the plan, or on any sketch plane — click it, click the body, and the
drawing lands on the body's face: a line across a plate's top at exactly the plate's thickness, a curve down a
pyramid's flank sloping with it. There is no direction to state, because the space you drew in already is one:
the curve is thrown along that space's own normal, which is exactly what its 2D view shows, so what you drew is
what the plan of the result looks like. *Which* face it lands on is decided once — the face you can see from
where you drew, and the one the drawing actually falls on — and then **remembered**, so moving the drawing
afterwards never quietly hops it onto another face. A segment lands as a segment and a Bézier as a Bézier, to
the last bit; a circle lands as the **ellipse it really is** when the face stands at an angle, fitted into the
run at a tenth of a micron because a curve in space has no name for a conic yet. If the drawing hangs over the
edge of the face, it is not clipped and it is not refused: it lands in that face's **plane**, whole, and the
drawing tells you which of the two happened. A face standing edge-on to your drawing says so and comes back
the moment you tilt the plane, and an imported body — which has no named faces at all — says that too, and
points at *Intersection curve*, which does work on one. What comes back is a curve in space like any other:
sweep a tube along it, stand a station on it, or connect it to the next run.

**A working plane's context is the part's section — and the section is an input.** Drawing on a plane that is
not the plan needs to know where the material is, so every such plane draws **the part's section at itself**,
in its own coordinates: a face space draws its face's true boundary (a pyramid's lateral face draws its
triangle), a datum plane draws the curves its cut produces. The load-bearing half is that those curves and
their corners are **construction inputs** — click one while a tool is collecting and it becomes a real element
downstream of the solid *and* the plane, so nothing is asserted and everything is constructed. Select a
pyramid's face, take its three edges as the three tangents of a circle, and you have the face's inscribed
circle to drill at; move the apex and the hole follows, because every step of that chain is a node. A datum
45 mm above a pyramid's base sections it into the exact 50 × 50 square, and retyping the 45 slides the plane,
the square and everything anchored on it. Exactness is stated rather than assumed: a plane through a flat face
is an exact segment, a cylinder cut perpendicular to its axis is that cylinder's own circle (from the profile,
not from the triangles), a rounded plate keeps its corner arcs — while an inclined cut through a curved face is
a true **ellipse**, which the curve vocabulary has no name for, so it draws as chords, says it is approximated,
and **refuses to be an input** rather than letting a construction be tangent to something that is not there.
A part built by the general (mesh) engine has no faces to name: its section draws, and says why it offers
nothing to anchor on.

**The 3D view draws, too.** Arm a tool while the 3D viewport is showing and it stops being somewhere to look
and becomes somewhere to work: every mouse position casts a ray onto the **active working plane**, so the click
lands in that plane's own coordinates and every one of the seventy-odd tools works there unchanged — same
gestures, same snaps, same live previews, same document. The plane's sketch is drawn back **onto the plane** in
the 3D scene, in perspective, so a circle reads as the ellipse it projects to and an arc is sampled from its own
geometry rather than from the screen's. Hold **Ctrl** to orbit without leaving the tool (the wheel zooms,
Space+drag pans); let it go and the next click carries on where you were, through the camera the orbit left
behind. The plane is chosen the way it always was — the spaces list, *Sketch on face*, *Sketch plane* — and the
picking radius stays the same ten pixels wherever the cursor is, because it is converted through the local
scale there; where a plane is so edge-on that a pixel means metres, the app says so instead of guessing. What is
deliberately not here yet: clicking a solid's **face** in the 3D view to choose the plane, and selecting
existing geometry there — both belong to the next slice, which needs a durable name for a face rather than a
ray-cast.

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

## Documentation

- [`DESIGN.md`](DESIGN.md) — the design record: guiding idea, resolved design points, and roadmap.
