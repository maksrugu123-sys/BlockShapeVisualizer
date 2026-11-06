package net.blockshape.client.mixin;

import net.blockshape.client.BlockShapeClient;
import net.minecraft.client.renderer.blockentity.AbstractEndPortalRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Replaces the animated End Portal shader with the vanilla static End sky texture.
 * The vanilla portal geometry, face visibility and placement are preserved.
 */
@Mixin(AbstractEndPortalRenderer.class)
public abstract class AbstractEndPortalRendererMixin {
    @ModifyVariable(
            method = "submitCube(Ljava/util/Collection;Lnet/minecraft/client/renderer/rendertype/RenderType;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0,
            require = 0
    )
    private static RenderType blockshape$useStaticEndTexture(RenderType original) {
        if (!BlockShapeClient.isPortalCategoryEnabled()) return original;
        return RenderTypes.entityTranslucent(AbstractEndPortalRenderer.END_SKY_LOCATION);
    }
}
