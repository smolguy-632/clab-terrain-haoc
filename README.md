# CLab Terrain HAOC

Hardware-accelerated occlusion culling (HAOC) for **Minecraft 1.20.1 + Sodium 0.5.x**
(branch `1.20.1/stable-0.5`, tested against Sodium 0.5.13).

Inspired by CLab's `HardwareOcclusionCuller` (entity/background proxy culling) and the
architecture used by BFRC-Revived: a standalone mixin mod that hooks into Sodium's render
pipeline instead of patching its source.

## How it works

Each chunk section (16^3 blocks) is represented by its axis-aligned bounding box. Every frame,
a bounded set of sections which the CPU visibility graph considers potentially visible are
re-tested with a **GPU occlusion query** (`GL_SAMPLES_PASSED`):

1. The probe pass renders each candidate section's bounding box as a depth-tested cube with
   color writes disabled, one query per section.
2. Sections under test are excluded from the terrain draw batches of the same frame, so a
   section's own geometry can never satisfy its own query (self-occlusion).
3. The probe pass runs between the solid and cutout terrain passes, so only fully-opaque
   terrain can hide a probe. Leaves, glass, and other cutout scenery never hide terrain.
4. A section whose probe returns zero depth samples is invisible from the camera and is
   filtered out of the render lists until a later probe proves it visible again.

Safety behavior:

- Sections within a configurable "safe radius" (default 56 blocks) of the camera are never
  culled, keeping the strongest occluders on screen and preventing holes near the camera.
- Re-test frequency scales with distance, and hidden sections recover quickly (their terrain
  re-draws within a few frames if the world changes in front of them).
- Query results expire after a set number of frames; stale results fall back to "visible".
- Enclosed and interior sections (underground, regardless of loaded chunks) benefit most.

## Configuration

`config/clab_haoc.json` (reloads automatically when the file changes):

| Field               | Default | Description                                      |
|---------------------|---------|--------------------------------------------------|
| `enabled`           | `true`  | Toggle the culler at runtime.                    |
| `safeRadius`        | `56.0`  | Blocks around the camera never culled.           |
| `maxQueriesPerFrame`| `320`   | New occlusion queries submitted per frame.       |
| `showDebugLine`     | `true`  | Adds a line to the Sodium F3 debug overlay.      |

## Building

The build runs via GitHub Actions (see `.github/workflows/build.yml`): push the repo and the
compiled jar will be uploaded as a workflow artifact.

Requirements enforced by the workflow: JDK 17, Gradle 8.12.1 (wrapper), fabric-loom 1.9.2,
Sodium 0.5.13 fetched from the Modrinth maven.

## Dependencies

- Minecraft ~1.20.1, Fabric Loader >= 0.14.21
- Sodium 0.5.x (`1.20.1/stable-0.5`) must be installed
