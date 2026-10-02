package org.qualet.irlredactor.mixin.client;

import com.mojang.blaze3d.opengl.GlSurface;
import org.qualet.irlredactor.editor.LightEditorScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the ImGui light editor on top of the finished frame.
 *
 * <p>26.2 renders GUI commands into the main target and presents through a
 * {@code GpuSurface}. On the OpenGL backend we hook {@code GlSurface#present}
 * immediately before GLFW swaps buffers. The complete Minecraft frame is already
 * on the default framebuffer, so raw-GL ImGui lands on top and is presented with
 * it. The call is a cheap no-op unless a {@link LightEditorScreen} is open.</p>
 */
@Mixin(GlSurface.class)
public class MinecraftClientImGuiMixin
{
    @Inject(method = "present()V", at = @At("HEAD"))
    private void irl$renderImGuiOverlay(CallbackInfo ci)
    {
        LightEditorScreen.renderActiveOverlay();
    }
}
