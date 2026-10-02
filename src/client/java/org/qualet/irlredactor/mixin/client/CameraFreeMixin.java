package org.qualet.irlredactor.mixin.client;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import org.qualet.irlredactor.client.FreeCamera;

/**
 * Free-fly camera: overrides the camera position (only) with {@link FreeCamera}'s
 * fly position while it's active. Injected at the TAIL of 26.2's
 * {@code Camera.update(DeltaTracker)} so
 * it runs after vanilla has set the position from the focused entity, and the
 * light SSBO upload (which reads the camera a moment later in {@code renderWorld}
 * — see {@code GameRendererLightMixin}) sees the free-camera eye. Rotation is left
 * vanilla, so the mouse still aims the view as usual.
 */
@Mixin(Camera.class)
public abstract class CameraFreeMixin
{
    @Shadow
    protected abstract void setPosition(double x, double y, double z);

    @Inject(
        method = "update(Lnet/minecraft/client/DeltaTracker;)V",
        at = @At("TAIL"))
    private void irl$freeCamera(DeltaTracker deltaTracker, CallbackInfo ci)
    {
        if (FreeCamera.isActive())
        {
            FreeCamera.advance();
            this.setPosition(FreeCamera.x(), FreeCamera.y(), FreeCamera.z());
        }
    }
}
