package net.blockshape.client.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

/**
 * Keeps selected vanilla effect textures on their first atlas frame.
 * The fluid and portal geometry remains vanilla; only frame animation is disabled.
 */
@Mixin(SpriteContents.class)
public abstract class SpriteContentsMixin {
    private static final Set<String> BLOCKSHAPE$STATIC_TEXTURES = Set.of(
            "minecraft:block/water_still",
            "minecraft:block/water_flow",
            "minecraft:block/lava_still",
            "minecraft:block/lava_flow",
            "minecraft:block/nether_portal",
            "minecraft:block/fire_0",
            "minecraft:block/fire_1",
            "minecraft:block/soul_fire_0",
            "minecraft:block/soul_fire_1"
    );

    @Inject(method = "createAnimationState", at = @At("HEAD"), cancellable = true, require = 1)
    private void blockshape$keepFirstTextureFrame(
            GpuBufferSlice uboSlice,
            int spriteUboSize,
            CallbackInfoReturnable<SpriteContents.AnimationState> cir
    ) {
        SpriteContents contents = (SpriteContents) (Object) this;
        if (BLOCKSHAPE$STATIC_TEXTURES.contains(contents.name().toString())) {
            // A null animation state tells the atlas that this sprite is static.
            cir.setReturnValue(null);
        }
    }
}
