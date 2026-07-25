package constructit.editor

import constructit.core.CircleValue
import constructit.core.Evaluator
import constructit.core.LineValue
import constructit.core.ScalarValue
import constructit.core.SourceNode
import constructit.dsl.CircleRef
import constructit.dsl.LineRef
import constructit.dsl.valueOf
import constructit.geom.Vec2
import constructit.units.Quantity

/**
 * A 1-DOF constraint for a point that lives on a curve. Dragging such a point updates its
 * hidden internal parameter (distance along a line / angle on a circle) by projecting the drag
 * position onto the curve — the point stays on the curve, and the model stays pure (the
 * parameter is an ordinary free source node that the drag simply writes).
 */
interface PointConstraint {
    fun update(
        world: Vec2,
        ev: Evaluator,
    )
}

/** Point on a line: internal parameter is signed distance along the line's direction. */
class OnLineConstraint(private val line: LineRef, private val t: SourceNode) : PointConstraint {
    override fun update(
        world: Vec2,
        ev: Evaluator,
    ) {
        val l = (ev.valueOf(line) as? LineValue)?.line ?: return
        t.value = ScalarValue(Quantity.mm((world - l.origin).dot(l.dir)))
    }
}

/**
 * A corner of an ortho path/wall. The vertex is `pointXY(xNode, yNode)`; each coordinate node is
 * *shared* with one neighbour (a horizontal edge shares y, a vertical edge shares x), so writing the
 * dragged cursor into both nodes moves this vertex and exactly its two neighbours while keeping every
 * edge axis-aligned — no downstream cascade, no solver. A coordinate welded to the start (loop
 * closure) is bound and simply ignores the write.
 */
class OrthoCornerConstraint(val xNode: SourceNode, val yNode: SourceNode) : PointConstraint {
    /** True while this vertex terminates its path (degree 1) — the case that may weld/attach. */
    var isEndpoint: Boolean = true

    /** Which coordinate is this vertex's *own* (not shared with a neighbour): 0 = x, 1 = y. The one
     *  to bind when attaching to a line, so the shared coordinate stays free for the neighbour. */
    var ownCoord: Int = 0

    override fun update(
        world: Vec2,
        ev: Evaluator,
    ) {
        xNode.value = ScalarValue(Quantity.mm(world.x))
        yNode.value = ScalarValue(Quantity.mm(world.y))
    }
}

/** Point on a circle: internal parameter is the angle around the centre. */
class OnCircleConstraint(val circle: CircleRef, private val angle: SourceNode) : PointConstraint {
    override fun update(
        world: Vec2,
        ev: Evaluator,
    ) {
        val c = (ev.valueOf(circle) as? CircleValue)?.circle ?: return
        angle.value = ScalarValue(Quantity.rad((world - c.center).angle()))
    }
}
