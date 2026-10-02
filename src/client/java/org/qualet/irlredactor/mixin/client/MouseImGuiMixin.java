package org.qualet.irlredactor.mixin.client;

import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.qualet.irlredactor.imgui.ImGuiInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Swallows mouse button / scroll events before Minecraft (and any open screen,
 * e.g. Replay Mod's timeline) handles them, whenever the ImGui editor wants the
 * mouse. ImGui has already seen the event via its own chained GLFW callback, so the
 * panel still works; this just stops the click from also scrubbing the timeline.
 *
 * <p>26.2 routes raw GLFW input through {@code onButton(long,
 * MouseButtonInfo, int)} and {@code onScroll(long, double, double)}.</p>
 */
@Mixin(MouseHandler.class)
public class MouseImGuiMixin
{
    @Inject(method = "onButton(JLnet/minecraft/client/input/MouseButtonInfo;I)V", at = @At("HEAD"), cancellable = true)
    private void irl$captureMouseButton(long window, MouseButtonInfo input, int action, CallbackInfo ci)
    {
        if (ImGuiInput.wantsMouse())
        {
            ci.cancel();
        }
    }

    @Inject(method = "onScroll(JDD)V", at = @At("HEAD"), cancellable = true)
    private void irl$captureMouseScroll(long window, double horizontal, double vertical, CallbackInfo ci)
    {
        if (ImGuiInput.wantsMouse())
        {
            ci.cancel();
        }
    }
}
