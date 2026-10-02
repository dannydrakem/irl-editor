# Minecraft 26.2 community port

This branch is a community-maintained port of IRL-editor 1.1.8 to Minecraft
26.2. It is based on upstream `quaIett/irl-editor` branch `port/1.21.11`
(`cff57bb`) and keeps the upstream MIT license and attribution.

## Status

- Point lights and spotlights were user-validated with shadows enabled.
- The editor, ImGui UI, auto lights, shaderpack patcher, shadow atlases, and
  optional ReplayMod integration start and render in a real 26.2 client.
- This is a community build, not an official upstream release.

## Runtime matrix

| Component | Version |
|---|---|
| Minecraft | 26.2 |
| Java | 25 |
| Fabric Loader | 0.19.5+ |
| Fabric API | 0.161.0+26.2 |
| Iris | 1.11.4+26.2-fabric |
| Sodium | mc26.2-0.9.2-fabric |

## Important shadow fix

Minecraft 26.2 / Iris may leave OpenGL clip depth in `GL_ZERO_TO_ONE` mode.
IRL's raw shadow renderer uses a forward-Z JOML projection whose receiver and
moment code expects NDC depth converted from `[-1, +1]` with
`depth = ndcZ * 0.5 + 0.5`. Inheriting `ZERO_TO_ONE` stored a different depth
and caused false self-shadowing: nearby surfaces stayed dark while edges were
bright.

The matching `irl-core` branch isolates every shadow pass with
`ShadowDepthConvention`: it captures the actual caller state, bakes in
`GL_NEGATIVE_ONE_TO_ONE`, depth range 0..1, clear depth 1, and restores all
captured state after each spotlight tile or point-light cube face.

The included GPU regression test reproduces the old `0.8` stored depth versus
the expected `0.9`, then validates 168 corrected depth samples and 28 exact
state round trips.

## Shaderpack lifecycle

The Java/OpenGL shadow-depth fix does not require repatching an already-current
IRLights pack. However, replacing the mod JAR never rewrites shaderpacks that
were generated earlier. When `.irlights` patch definitions change, generate a
fresh patched pack from a legally obtained original shaderpack.

Do not redistribute third-party shaderpacks unless their license explicitly
allows it. IterationRP is paid; this repository only contains a patch applied
to a copy owned by the user.

## Build

Build and publish the matching core first:

```powershell
$env:JAVA_HOME = '<path-to-JDK-25>'
cd ../irl-core
.\gradlew.bat clean build publishToMavenLocal

cd ../irl-editor
.\gradlew.bat clean build
```

The editor bundles exactly one `irl-core` nested JAR through Loom `include`.
The build contains staging cleanup because stale incremental include output can
otherwise leave two core versions in the final mod JAR.

## Known follow-up work

- Bump the `irl-core` artifact version before a stable release.
- Make all of `ShadowRenderer.endPass()` exception-safe, not only restoration
  of the clip/depth convention.
- Test AMD and Intel GPUs plus Linux and macOS. The final visual validation was
  performed on NVIDIA / Windows 11.
- Add a supported fallback or a clear error when OpenGL 4.5 / ARB clip control
  is unavailable.
- Profile the large shadow atlas and EVSM VRAM budget on low-end GPUs.
- Review the earlier PCF/MSM and receiver-bias changes independently from the
  confirmed depth-convention fix.

## Attribution

Original project and design: [qualet / quaIett](https://github.com/quaIett).
Minecraft 26.2 port and validation were prepared from the upstream repository
under the MIT license. Third-party projects retain their own licenses.
