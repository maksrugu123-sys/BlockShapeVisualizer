package net.blockshape.client.mixin;

import net.blockshape.client.BlockShapeClient;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Notifies BlockShape when a client-side block changes so cached nearby
 * block meshes and neighbor-dependent faces can be rebuilt promptly.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelBlockUpdateMixin {
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("RETURN"), require = 1)
    private void blockshape$invalidateChangedBlock(
            BlockPos pos,
            BlockState newState,
            int updateFlags,
            int updateLimit,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (Boolean.TRUE.equals(cir.getReturnValue())) {
            BlockShapeClient.invalidateBlockAndNeighbors(pos);
        }
    }
}
