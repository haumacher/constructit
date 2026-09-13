#!/usr/bin/env bash
# Build ConstructIt's Manifold shim (OP-9; OP-31's (5q)).
#
#   native/build.sh            -> native/build/libconstructit_manifold.so
#
# Two steps, kept apart on purpose (see CMakeLists.txt): a plain upstream Manifold build at a pinned
# tag, then our one-file JNI shim linked statically against it. Everything lands under native/build/,
# which is gitignored; nothing this writes is committed.
#
# Needs: git, cmake >= 3.20, a C++17 compiler, and a JDK (for jni.h). No network beyond the one clone,
# and no Manifold dependency is fetched: the configure below turns off cross-section (clipper2) and the
# parallel backend (TBB), which is also what makes the engine deterministic.
#
# Then point the JVM at it:  ./gradlew jvmTest -Dconstructit.manifold.native=<repo>/native/build
set -euo pipefail

# The pinned tag. 3.5.1 is not the newest 3.x (3.5.3 is) — it is the version the **browser** already
# runs as npm `manifold-3d`, and one engine on both platforms is worth more than three patch releases.
MANIFOLD_TAG="${MANIFOLD_TAG:-v3.5.1}"
MANIFOLD_COMMIT_EXPECTED="cc8a7f66d7d5a560da94346258c5b546af27811e"

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
out="$here/build"
src="$out/manifold-src"
mkdir -p "$out"

if [ -z "${JAVA_HOME:-}" ]; then
  JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
  export JAVA_HOME
fi
echo "JAVA_HOME=$JAVA_HOME"

if [ ! -d "$src" ]; then
  git clone --depth 1 --branch "$MANIFOLD_TAG" --recurse-submodules --shallow-submodules \
    https://github.com/elalish/manifold.git "$src"
fi
commit="$(git -C "$src" rev-parse HEAD)"
echo "manifold $MANIFOLD_TAG @ $commit"
if [ "$MANIFOLD_TAG" = "v3.5.1" ] && [ "$commit" != "$MANIFOLD_COMMIT_EXPECTED" ]; then
  echo "!! v3.5.1 is not $MANIFOLD_COMMIT_EXPECTED — the tag moved; check before trusting this build" >&2
fi

# MANIFOLD_PAR=OFF is the determinism (Manifold 3 dropped ExecutionParams::deterministic; serial is
# what replaces it). CROSS_SECTION/CBIND off drops clipper2; TEST/PYBIND off drop gtest and nanobind;
# DOWNLOADS=OFF makes it an error rather than a surprise if any of that were still wanted.
cmake -S "$src" -B "$out/manifold-cmake" \
  -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_SHARED_LIBS=OFF \
  -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
  -DMANIFOLD_PAR=OFF \
  -DMANIFOLD_CROSS_SECTION=OFF \
  -DMANIFOLD_CBIND=OFF \
  -DMANIFOLD_PYBIND=OFF \
  -DMANIFOLD_TEST=OFF \
  -DMANIFOLD_DEBUG=OFF \
  -DMANIFOLD_DOWNLOADS=OFF \
  -DCMAKE_INSTALL_PREFIX="$out/manifold-install"
cmake --build "$out/manifold-cmake" -j"$(nproc 2>/dev/null || echo 4)"
cmake --install "$out/manifold-cmake"

version="manifold ${MANIFOLD_TAG#v} (${commit:0:10}, serial, MeshGL64)"
cmake -S "$here" -B "$out/shim-cmake" \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_PREFIX_PATH="$out/manifold-install" \
  -DCONSTRUCTIT_MANIFOLD_VERSION="$version"
cmake --build "$out/shim-cmake" -j"$(nproc 2>/dev/null || echo 4)"
cp "$out/shim-cmake/libconstructit_manifold.so" "$out/libconstructit_manifold.so"

echo
echo "built $out/libconstructit_manifold.so  ($(du -h "$out/libconstructit_manifold.so" | cut -f1))"
echo "  version(): $version"
echo "  use it:    ./gradlew jvmTest -Dconstructit.manifold.native=$out"
