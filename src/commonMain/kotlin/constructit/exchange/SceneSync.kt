package constructit.exchange

import constructit.editor.Appearance
import constructit.geom.Mesh3

/**
 * **The retained consumer's diff over the [ExportScene] seam** — which bodies to build, which to leave
 * alone, which to restyle, which to take down.
 *
 * A one-shot consumer (a file writer) reads the scene and is done; a *retained* one (the three.js preview)
 * holds a live object per body and must be brought in step on every document change. The three cases per
 * body, and the middle one is the point of the whole arrangement (OP-5): the mesh object is **the same** as
 * last time, so nothing upstream of the body moved and its buffers are left alone — at most its material is
 * written; the mesh is **new**, so the old handle *leaves the scene* and a new one is built; the body is
 * **gone**, so its handle leaves too. Rebuild and removal both go through the one [Backend.remove], because
 * the two halves of "gone" — out of the scene graph *and* resources released — must never come apart: a
 * handle that is disposed but still attached is a ghost the renderer happily re-uploads, which is exactly
 * the stale-body pileup this class exists to make testable (the parameter-edit regression in
 * `PreviewSyncTest`).
 *
 * Lives in `commonMain` although its only production caller is the browser preview: the decision of *what*
 * changes is platform-free and worth a headless test; only the *how* (three.js objects) is the backend's.
 */
class SceneSync<H : Any>(
    private val backend: Backend<H>,
) {
    /** What a retained consumer does — attach, detach, restyle. [remove] must undo everything [add] did. */
    interface Backend<H : Any> {
        /** Build [node]'s live object and attach it to the scene. */
        fun add(node: ExportNode): H

        /** Detach [handle] from the scene **and** release what it holds — both, always together. */
        fun remove(handle: H)

        /** Restyle [handle] to [material]; its geometry is untouched. */
        fun material(
            handle: H,
            material: Appearance,
        )
    }

    private class Entry<H>(
        val mesh: Mesh3,
        var material: Appearance,
        val handle: H,
    )

    private val shown = HashMap<String, Entry<H>>()

    /** How many handles the last [update] had to (re)build. Zero is the answer an orbit should give. */
    var lastUploads: Int = 0
        private set

    fun update(exported: ExportScene) {
        var uploads = 0
        val live = HashSet<String>()
        for (node in exported.nodes) {
            live.add(node.name)
            val had = shown[node.name]
            if (had != null && had.mesh === node.mesh) {
                // nothing upstream of this body moved (OP-5): keep its buffers, and write the material only
                // if *that* changed — a colour picked in the panel must not cost a geometry upload
                if (had.material != node.material) {
                    backend.material(had.handle, node.material)
                    had.material = node.material
                }
                continue
            }
            had?.let { backend.remove(it.handle) }
            shown[node.name] = Entry(node.mesh, node.material, backend.add(node))
            uploads++
        }
        for (name in shown.keys.toList()) {
            if (name in live) continue
            shown.remove(name)?.let { backend.remove(it.handle) }
        }
        lastUploads = uploads
    }
}
