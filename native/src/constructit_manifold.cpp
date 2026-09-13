// ConstructIt's JNI shim over Manifold (OP-9; OP-31's queue item (5q)).
//
// **Why this file exists.** The general boolean (OP-9) runs on Manifold on both platforms. The browser
// has the engine as npm `manifold-3d` 3.5.1 and can speak `Mesh64`; the JVM had only the clojars
// JavaCPP binding `org.clojars.cartesiantheatrics:manifold3d` 2.0.3, whose *native* is already a
// Manifold 3 but whose *Java surface* is 2.x-shaped — no `MeshGL64`, so every vertex crosses the seam
// as float32, and no way to ask for a serial, reproducible evaluation. Session 86 measured both costs:
// two disjoint fillets on one partial revolve disagree by 1.69e-4 mm^3 between the two gesture routes
// (float32 re-snapping a curved tessellation), and byte-identical operands come back as 2334 vertices
// on one call and 2336 on the next (the bundled native links TBB). This shim is the answer to both:
// a Manifold 3 built from source, **serial** (`MANIFOLD_PAR=OFF`), talking `MeshGL64` in and out.
//
// **The surface is exactly what `MeshBool` needs and nothing more**: one boolean, and a version string.
// No cross-section, no mesh IO, no transforms, no smoothing — those either live in ConstructIt's own
// pure-Kotlin geometry or are not wanted at all. Keeping the surface this small is what lets the whole
// binding be one C++ file and one Kotlin object.
//
// **Determinism is a build fact, not a flag.** Manifold 2.x carried `ExecutionParams::deterministic`
// ("will disable some parallel optimizations"); Manifold 3 **removed it** — there is no `deterministic`
// field on `ExecutionParams` in 3.5.1, and no other switch stands in for it. What replaces it is the
// parallel backend being a *build* choice: `MANIFOLD_PAR=OFF` compiles `manifold::parallel` down to the
// serial `std::` algorithms, so there is no thread-count-dependent reduction or race left to make an
// answer depend on anything but its operands. Manifold's own release flags already carry
// `-ffp-contract=off -fexcess-precision=standard`, which pins the floating point the same way. So the
// shim does not set a flag it would only be pretending to set; it is deterministic because it is
// serial, and `BooleanEngineTest` is what proves that rather than this comment.
//
// **Ownership comes from the engine, never from a guess** (OP-8; OP-31 item 4). A `Manifold` built from
// a mesh is an *original* and carries an `OriginalID`; the result's `runOriginalID` says which original
// each run of triangles belongs to and `runIndex` says where each run starts. Matching the two against
// the two operands' ids is the whole derivation, and a triangle whose run matches neither leaves -1 —
// *"the engine did not say"* — which the Kotlin side then resolves against both operands' carriers.

#include <jni.h>

#include <cstdint>
#include <exception>
#include <string>
#include <vector>

#include <manifold/common.h>
#include <manifold/manifold.h>

using manifold::Manifold;
using manifold::MeshGL64;
using manifold::OpType;

namespace {

// The stages a boolean can fail at. These are the shim's own numbering, chosen to line up one-for-one
// with the refusals `MeshBool` already had words for, so no new sentence had to be invented (OP-29).
constexpr jint kOk = 0;
constexpr jint kFirstNotSolid = 1;
constexpr jint kSecondNotSolid = 2;
constexpr jint kResultFailed = 3;
constexpr jint kResultEmpty = 4;
constexpr jint kThrew = 5;

/** The operand arrays as a `MeshGL64`: three doubles per vertex, triangles as an index run. */
MeshGL64 ToMesh(JNIEnv* env, jdoubleArray verts, jintArray tris) {
  MeshGL64 mesh;
  mesh.numProp = 3;
  // `tolerance` is left at 0, which asks Manifold for its own baseline from the bounding box. Tried and
  // rejected in session 86: pinning it to ConstructIt's 1e-7 mm welding lattice recovers exactly one of
  // the suite's marginal contacts (a 5.7e-13 mm^2 sliver) and 1e-6 mm loses ten more, so the number is
  // not a knob with an answer on it — the marginal contacts that float32 used to nudge apart want a
  // remedy of ConstructIt's own, not a tolerance guessed here (OP-31's (5q), and the note in DESIGN.md).
  const jsize nv = env->GetArrayLength(verts);
  const jsize nt = env->GetArrayLength(tris);
  mesh.vertProperties.resize(static_cast<size_t>(nv));
  if (nv > 0) env->GetDoubleArrayRegion(verts, 0, nv, mesh.vertProperties.data());
  std::vector<jint> idx(static_cast<size_t>(nt));
  if (nt > 0) env->GetIntArrayRegion(tris, 0, nt, idx.data());
  mesh.triVerts.reserve(idx.size());
  for (jint i : idx) mesh.triVerts.push_back(static_cast<uint64_t>(i));
  return mesh;
}

/** One result slot: the four arrays `boolOp` hands back, in the order the Kotlin side reads them. */
jobjectArray Result(JNIEnv* env, const std::vector<double>& verts,
                    const std::vector<jint>& tris, const std::vector<jint>& owners,
                    jint stage, jint code) {
  jclass object = env->FindClass("java/lang/Object");
  jobjectArray out = env->NewObjectArray(4, object, nullptr);

  jdoubleArray jv = env->NewDoubleArray(static_cast<jsize>(verts.size()));
  if (!verts.empty())
    env->SetDoubleArrayRegion(jv, 0, static_cast<jsize>(verts.size()), verts.data());
  env->SetObjectArrayElement(out, 0, jv);

  jintArray jt = env->NewIntArray(static_cast<jsize>(tris.size()));
  if (!tris.empty()) env->SetIntArrayRegion(jt, 0, static_cast<jsize>(tris.size()), tris.data());
  env->SetObjectArrayElement(out, 1, jt);

  jintArray jo = env->NewIntArray(static_cast<jsize>(owners.size()));
  if (!owners.empty())
    env->SetIntArrayRegion(jo, 0, static_cast<jsize>(owners.size()), owners.data());
  env->SetObjectArrayElement(out, 2, jo);

  const jint status[2] = {stage, code};
  jintArray js = env->NewIntArray(2);
  env->SetIntArrayRegion(js, 0, 2, status);
  env->SetObjectArrayElement(out, 3, js);
  return out;
}

jobjectArray Failure(JNIEnv* env, jint stage, jint code) {
  const std::vector<double> nv;
  const std::vector<jint> ni;
  return Result(env, nv, ni, ni, stage, code);
}

}  // namespace

extern "C" {

/** The engine's identity, quoted in `MeshBool.status` so a report says which engine made a mesh. */
JNIEXPORT jstring JNICALL Java_constructit_geom_NativeManifold_version(JNIEnv* env, jobject) {
  return env->NewStringUTF(CONSTRUCTIT_MANIFOLD_VERSION);
}

/**
 * The one call. `kind` is `BoolOp`'s ordinal — 0 union, 1 subtract, 2 intersect — and the answer is
 * `[DoubleArray positions, IntArray triangles, IntArray owners, IntArray {stage, code}]`.
 *
 * `code` is Manifold's own `Error` ordinal where it has one, so a refusal can quote the engine rather
 * than paraphrase it. Every exception is caught here: a JNI frame must never let a C++ throw cross it.
 */
JNIEXPORT jobjectArray JNICALL Java_constructit_geom_NativeManifold_boolOp(
    JNIEnv* env, jobject, jint kind, jdoubleArray vertsA, jintArray trisA, jdoubleArray vertsB,
    jintArray trisB) {
  try {
    const Manifold a(ToMesh(env, vertsA, trisA));
    if (a.Status() != Manifold::Error::NoError)
      return Failure(env, kFirstNotSolid, static_cast<jint>(a.Status()));
    const Manifold b(ToMesh(env, vertsB, trisB));
    if (b.Status() != Manifold::Error::NoError)
      return Failure(env, kSecondNotSolid, static_cast<jint>(b.Status()));

    const OpType op = kind == 0 ? OpType::Add : (kind == 1 ? OpType::Intersect : OpType::Subtract);
    const Manifold r = a.Boolean(b, op);
    if (r.Status() != Manifold::Error::NoError)
      return Failure(env, kResultFailed, static_cast<jint>(r.Status()));
    if (r.IsEmpty()) return Failure(env, kResultEmpty, 0);

    const MeshGL64 gl = r.GetMeshGL64();
    const size_t stride = gl.numProp < 3 ? 3 : static_cast<size_t>(gl.numProp);
    const size_t tris = gl.triVerts.size() / 3;

    std::vector<double> verts;
    verts.reserve(gl.vertProperties.size() / stride * 3);
    for (size_t i = 0; i + 2 < gl.vertProperties.size(); i += stride) {
      verts.push_back(gl.vertProperties[i]);
      verts.push_back(gl.vertProperties[i + 1]);
      verts.push_back(gl.vertProperties[i + 2]);
    }

    std::vector<jint> tv;
    tv.reserve(gl.triVerts.size());
    for (uint64_t v : gl.triVerts) tv.push_back(static_cast<jint>(v));

    // the runs, matched against the two operands' original ids — see the ownership note at the top
    std::vector<jint> owners(tris, -1);
    const int idA = a.OriginalID();
    const int idB = b.OriginalID();
    for (size_t run = 0; run < gl.runOriginalID.size() && run < gl.runIndex.size(); ++run) {
      const int id = static_cast<int>(gl.runOriginalID[run]);
      const jint which = id == idA ? 0 : (id == idB ? 1 : -1);
      if (which < 0) continue;
      const size_t from = static_cast<size_t>(gl.runIndex[run]) / 3;
      const size_t to = run + 1 < gl.runIndex.size()
                            ? static_cast<size_t>(gl.runIndex[run + 1]) / 3
                            : tris;
      for (size_t i = from; i < to && i < tris; ++i) owners[i] = which;
    }
    return Result(env, verts, tv, owners, kOk, 0);
  } catch (const std::exception&) {
    return Failure(env, kThrew, 0);
  } catch (...) {
    return Failure(env, kThrew, 0);
  }
}

}  // extern "C"
