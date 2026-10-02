package org.qualet.irlredactor.mixin.client;

import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.qualet.irlredactor.imgui.ImGuiInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Swallows key / char events when an ImGui text field is active (e.g. editing a
 * light's name), so typing doesn't also trigger Minecraft / Replay Mod keybinds.
 * Gated on WantCaptureKeyboard, which — with keyboard nav disabled (see
 * {@code ImGuiRuntime}) — is true only while a text input is focused.
 *
 * <p>26.2 routes the event records through {@code keyPress} and
 * {@code charTyped}; these injections run before Minecraft consumes them.</p>
 */
@Mixin(KeyboardHandler.class)
public class KeyboardImGuiMixin
{
    @Inject(method = "keyPress(JILnet/minecraft/client/input/KeyEvent;)V", at = @At("HEAD"), cancellable = true)
    private void irl$captureKey(long window, int key, KeyEvent input, CallbackInfo ci)
    {
        if (ImGuiInput.wantsKeyboard())
        {
            ci.cancel();
        }
    }

    @Inject(method = "charTyped(JLnet/minecraft/client/input/CharacterEvent;)V", at = @At("HEAD"), cancellable = true)
    private void irl$captureChar(long window, CharacterEvent input, CallbackInfo ci)
    {
        if (ImGuiInput.wantsKeyboard())
        {
            ci.cancel();
        }
    }
}
