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

## The hard problem — intersections

`intersect(circle, circle)` yields 0, 1, or 2 points, unstable under parameter change.

1. **Branch selection** — which of the two solutions is "the" one? Needs a stable rule
   (index, nearest-to-reference, sign/orientation). Naive rules flip under rotation.
2. **Continuity vs determinism** — the fundamental fork:
   - *Deterministic labeling* (GeoGebra default): simple, but solutions can swap/snap.
   - *Continuity tracking* (Cinderella; Kortenkamp & Richter-Gebert): tear-free dragging,
     may route through complex coordinates, but path-dependent (no longer a pure DAG).
3. **Vanishing solutions** — propagate a first-class **undefined** state downstream
   (render greyed/dashed, keep definitions so the model heals when params return to valid).

## Going to 3D

- Sketch → feature → sketch loop (sketch on datum plane → extrude/revolve/sweep → derive
  datum from a solid face → sketch again). DAG spans 2D constructions, 3D features, datums.
- Needs a solid-modeling kernel (booleans, extrude, fillet). Likely **OpenCASCADE (OCCT)**
  vs writing our own B-rep kernel (multi-year effort).
- **Topological naming problem**: re-identifying "this face/edge" after regeneration when the
  kernel renumbers. 2D is fine (identity = node); the pain is at the solid-kernel boundary.

## Open points (to discuss one by one)

- [ ] **OP-1 Branch/continuity policy** for intersections
      (deterministic-with-selector vs continuity-tracking).
- [ ] **OP-2 Scope & kernel**: 2D-first then extend, or 3D from the start?
      If 3D: OCCT vs own kernel.
- [ ] **OP-3 Undefined-state propagation** semantics.
- [ ] **OP-4 Measurements feeding back as parameters** (bidirectional dataflow) in v1?
- [ ] **OP-5 Node graph data model** — concrete representation of nodes/edges/params.
- [ ] **OP-6 Macros / custom constructions** — encapsulation & composition mechanics.
- [ ] **OP-7 Expression language** for parameters (units, derived values).
- [ ] **OP-8 Topological naming** identity strategy at the 3D kernel boundary.

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
