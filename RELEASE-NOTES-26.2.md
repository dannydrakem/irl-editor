# IRL Redactor 1.1.8-shadowfix1 for Minecraft 26.2

Community-maintained Fabric port of IRL-editor to Minecraft 26.2.

## Requirements

- Minecraft 26.2
- Java 25
- Fabric Loader 0.19.5+
- Fabric API 0.161.0+26.2
- Iris 1.11.4+26.2-fabric
- Sodium mc26.2-0.9.2-fabric

## Highlights

- Ports the editor and shared rendering engine to the 26.2 client APIs.
- Adapts render extraction, camera/input/screens, mixins, block and entity
  occluder capture, light SSBOs, shadow atlases, and Iris integration.
- Fixes false self-shadowing in point lights and spotlights by isolating IRL's
  `[-1,+1]` shadow projection from Minecraft's inherited `GL_ZERO_TO_ONE`
  clip-depth mode.
- Includes a real OpenGL regression test for depth values and state restoration.
- Keeps Outline disabled by default; it can be enabled manually in the editor.

## Installation

Place only the release JAR in `mods`. Do not install `irl-core` separately; it
is included in the main JAR. Apply IRLights patches to a legally obtained
supported shaderpack, select the patched pack in Iris, and restart/reload.

The depth fix itself does not require repatching an already-current IRLights
pack. Repatch only when the embedded `.irlights` definitions have changed.

## Validation

Point lights and spotlights were visually confirmed by the user with shadows
enabled. Builds and a Minecraft 26.2 world launch passed on Windows 11 / NVIDIA
RTX 5070 Ti. AMD, Intel, Linux, and macOS still need broader testing.

## Attribution

Original project by [qualet](https://github.com/quaIett), released under MIT.
This is an unofficial community-maintained port.
