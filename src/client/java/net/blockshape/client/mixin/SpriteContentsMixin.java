package net.blockshape.client.mixin;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import net.blockshape.client.BlockShapeClient;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps selected vanilla effect textures on their first atlas frame.
 * Liquids, portal and fire can be controlled independently in BlockShape.
 */
@Mixin(SpriteContents.class)
public abstract class SpriteContentsMixin {
    @Inject(method = "createAnimationState", at = @At("HEAD"), cancellable = true, require = 1)
    private void blockshape$keepFirstTextureFrame(
            GpuBufferSlice uboSlice,
            int spriteUboSize,
            CallbackInfoReturnable<SpriteContents.AnimationState> cir
    ) {
        SpriteContents contents = (SpriteContents) (Object) this;
        if (BlockShapeClient.shouldKeepSpriteStatic(contents.name().toString())) {
            // A null animation state tells the atlas that this sprite is static.
            cir.setReturnValue(null);
        }
    }
}
