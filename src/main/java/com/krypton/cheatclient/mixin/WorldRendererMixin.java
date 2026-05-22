package com.krypton.cheatclient.mixin;

import com.krypton.cheatclient.Krypton;
import net.minecraft.client.render.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WorldRenderer.class)
public class WorldRendererMixin {

	// Verhindert, dass Chunks ausgeblendet werden, wenn man durch Wände fliegt
	@Inject(method = "isRenderingReady", at = @At("HEAD"), cancellable = true)
	private void onIsRenderingReady(CallbackInfoReturnable<Boolean> cir) {
		if (Krypton.isFreecamActive) {
			cir.setReturnValue(true);
		}
	}
}