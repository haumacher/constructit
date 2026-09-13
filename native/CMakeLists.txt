# ConstructIt's JNI shim over Manifold (OP-9; OP-31's (5q)) — see src/constructit_manifold.cpp for why.
#
# This project builds **only the shim**. Manifold itself is built first, from a pinned source tag, by
# `build.sh`, which then points this at the install tree. Keeping the two steps apart is deliberate:
# the Manifold build is the slow, dependency-bearing half, and it must stay a plain upstream build with
# no patch of ours in it, so what we ship is provably the upstream engine.
cmake_minimum_required(VERSION 3.20)
project(constructit_manifold CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()

# The engine, as built by build.sh: a *static* archive, so the one .so we hand the JVM carries the whole
# engine and has no dependency beyond libstdc++/libm. That is what makes distribution one file.
find_package(manifold REQUIRED)

# The JVM's headers. JAVA_HOME is set by build.sh from `readlink -f $(which javac)` when it is not
# already in the environment.
find_package(JNI REQUIRED)

set(CONSTRUCTIT_MANIFOLD_VERSION
    "manifold ?.?.? (serial, MeshGL64)"
    CACHE STRING "The identity string version() hands back")

add_library(constructit_manifold SHARED src/constructit_manifold.cpp)
target_include_directories(constructit_manifold PRIVATE ${JNI_INCLUDE_DIRS})
target_link_libraries(constructit_manifold PRIVATE manifold::manifold)
target_compile_definitions(
  constructit_manifold
  PRIVATE CONSTRUCTIT_MANIFOLD_VERSION="${CONSTRUCTIT_MANIFOLD_VERSION}")
# the same floating-point pinning Manifold's own release flags carry, so the shim's arithmetic and the
# engine's agree (no FMA contraction, no x87 excess precision)
target_compile_options(constructit_manifold PRIVATE -O2 -ffp-contract=off -fexcess-precision=standard)
# ...and nothing but the two JNI entry points leaves the library — see src/exports.map for why that
# matters when the old binding's own libmanifold is loaded in the same JVM.
target_link_options(
  constructit_manifold PRIVATE
  "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/src/exports.map" "-Wl,--exclude-libs,ALL")
set_target_properties(
  constructit_manifold PROPERTIES CXX_VISIBILITY_PRESET hidden VISIBILITY_INLINES_HIDDEN ON)
set_target_properties(constructit_manifold PROPERTIES PREFIX "lib")
