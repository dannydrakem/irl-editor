package org.qualet.irlredactor.mixin.client;

import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.qualet.irl.light.FramePipeline;
import org.qualet.irl.light.iris.IrisShadersState;
import org.qualet.irlredactor.client.diag.VlProfiler;
import org.qualet.irlredactor.light.LightDriver;

/**
 * Per-frame entry point for the light + shadow pipeline. Thin delegate to the
 * shared {@link FramePipeline} (main structure). In 26.2 the world entry point is
 * {@code renderLevel(DeltaTracker)}; camera update and render-state extraction have
 * already completed before it is called.
 */
@Mixin(GameRenderer.class)
public class GameRendererLightMixin
{
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void irlite$collectLights(DeltaTracker tickCounter, CallbackInfo ci)
    {
        // Derive the partial tick from the 26.2 DeltaTracker.
        float tickDelta = tickCounter.getGameTimeDeltaPartialTick(true);

        // Dev GPU profiler (editor "perf" section): the shadow bake below runs
        // strictly before the Iris pass sequence, so its GL_TIME_ELAPSED bracket
        // never nests with the per-pass brackets. collect inside frame() issues
        // no GL, so the bracket measures bake GPU work only. The core-side
        // ShadowBakeProbe (installed in IRLRedactorClient) switches this bracket
        // to bake-* siblings at the bakeInner seams; the endPass below closes
        // whichever segment is open. Everything no-ops while collection is off.
        VlProfiler.frameTick();
        VlProfiler.beginPass(VlProfiler.PASS_BAKE);
        // Depth textures are freed on the dormant transition; re-ramp auto-shadow
        // first-bakes when shaders return instead of baking every cube in one frame.
        FramePipeline.frame(tickDelta, IrisShadersState::shadersDisabled, LightDriver::collect,
            LightDriver::resetAutoShadowRamp);
        VlProfiler.endPass();
        // Camera.update and render-state extraction both complete before renderLevel in 26.2.
        FramePipeline.uploadIfPending();
    }
}
