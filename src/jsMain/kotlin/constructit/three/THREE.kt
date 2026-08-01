@file:Suppress("unused", "ktlint:standard:class-naming", "ktlint:standard:function-naming")

package constructit.three

/**
 * **The three.js surface this app uses — and nothing more.**
 *
 * Hand-written declarations rather than generated ones, deliberately: three.js is a large library and the
 * preview touches a dozen of its classes, so a generated binding would be tens of thousands of lines of
 * surface nobody reads, most of it able to drift out of date without anything noticing. What is here is what
 * [constructit.editor.Preview3] calls, so an upstream rename breaks the build instead of the page.
 *
 * Declared against the **global** `THREE`, which is what [Preview3.load] puts there after its dynamic
 * `import('three')`: the module is not part of the main bundle (three.js is a ~600 KB dependency and the
 * preview is a panel most sessions never open), so the declarations cannot be `@JsModule`-bound — that would
 * pull it in eagerly. One assignment in the loader is the whole cost of the arrangement.
 */
external object THREE {
    class Vector3(
        x: Double = definedExternally,
        y: Double = definedExternally,
        z: Double = definedExternally,
    ) {
        fun set(
            x: Double,
            y: Double,
            z: Double,
        ): Vector3
    }

    class Color {
        /** Three channels in the renderer's **working** colour space, which is linear-sRGB. */
        fun setRGB(
            r: Double,
            g: Double,
            b: Double,
        ): Color
    }

    open class Object3D {
        var name: String
        val position: Vector3
        val scale: Vector3

        /** Which way is up for this object — what a Z-up world states instead of turning its geometry. */
        val up: Vector3

        fun add(child: Object3D)

        fun remove(child: Object3D)

        fun lookAt(
            x: Double,
            y: Double,
            z: Double,
        )
    }

    class Scene : Object3D {
        var background: dynamic
        var environment: dynamic
    }

    class PerspectiveCamera(
        fov: Double,
        aspect: Double,
        near: Double,
        far: Double,
    ) : Object3D {
        var aspect: Double
        var fov: Double
        var near: Double
        var far: Double

        fun updateProjectionMatrix()
    }

    open class BufferGeometry {
        fun setAttribute(
            name: String,
            attribute: BufferAttribute,
        )

        fun setIndex(attribute: BufferAttribute)

        fun computeBoundingSphere()

        fun dispose()
    }

    class BufferAttribute(
        array: dynamic,
        itemSize: Int,
    )

    class BoxGeometry(
        width: Double,
        height: Double,
        depth: Double,
    ) : BufferGeometry

    open class Material {
        fun dispose()
    }

    class MeshStandardMaterial(parameters: dynamic = definedExternally) : Material {
        val color: Color
        var roughness: Double
        var metalness: Double
    }

    class MeshBasicMaterial(parameters: dynamic = definedExternally) : Material

    class Mesh(
        geometry: BufferGeometry,
        material: Material,
    ) : Object3D {
        val geometry: BufferGeometry
        val material: Material
    }

    class DirectionalLight(
        color: Int,
        intensity: Double,
    ) : Object3D

    class WebGLRenderer(parameters: dynamic = definedExternally) {
        var toneMapping: Int
        var toneMappingExposure: Double
        var outputColorSpace: dynamic

        fun setPixelRatio(value: Double)

        fun setSize(
            width: Int,
            height: Int,
            updateStyle: Boolean = definedExternally,
        )

        fun render(
            scene: Scene,
            camera: PerspectiveCamera,
        )

        fun dispose()
    }

    /** Pre-filters a scene into the mip-mapped radiance map an image-based light needs. */
    class PMREMGenerator(renderer: WebGLRenderer) {
        fun fromScene(
            scene: Scene,
            sigma: Double = definedExternally,
        ): dynamic

        fun dispose()
    }

    /** ACES filmic tone mapping: the curve that stops a bright highlight clipping to a flat white. */
    val ACESFilmicToneMapping: Int

    /** The colour space a canvas is displayed in. */
    val SRGBColorSpace: dynamic

    /** Render the inside of a shell — what makes a box usable as a room. */
    val BackSide: Int
}
