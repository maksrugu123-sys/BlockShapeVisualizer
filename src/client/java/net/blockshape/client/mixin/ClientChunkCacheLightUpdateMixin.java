package net.blockshape.client.mixin;

import net.blockshape.client.BlockShapeClient;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Coalesces client lighting notifications into a local BlockShape cache refresh. */
@Mixin(ClientChunkCache.class)
public abstract class ClientChunkCacheLightUpdateMixin {
    @Inject(
            method = "onLightUpdate(Lnet/minecraft/world/level/LightLayer;Lnet/minecraft/core/SectionPos;)V",
            at = @At("TAIL"),
            require = 1
    )
    private void blockshape$invalidateLightSection(
            LightLayer layer,
            SectionPos sectionPos,
            CallbackInfo ci
    ) {
        BlockShapeClient.invalidateLightSection(sectionPos);
    }
}
