package org.qualet.irlredactor.mixin.client;

import net.minecraft.client.player.ClientInput;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Write access to {@code ClientInput.movementVector} (a protected field on the base
 * {@code ClientInput} class) so the free camera can zero the player's analog movement.
 *
 * <p>A subclass {@code @Shadow} of the inherited field does NOT attach when the
 * mixin targets {@code KeyboardInput} (the field lives on the superclass
 * {@code ClientInput}) — that fails at mixin-apply with "field was not located in the
 * target class". An {@code @Accessor} on the DECLARING class ({@code ClientInput}) is
 * the robust way to reach a protected/inherited field.</p>
 */
@Mixin(ClientInput.class)
public interface InputAccessor
{
    @Accessor("moveVector")
    void setMovementVector(Vec2 value);
}
