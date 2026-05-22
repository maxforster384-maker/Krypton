package com.krypton.cheatclient.mixin;

import com.krypton.cheatclient.Krypton;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class GameRendererMixin {

	// Parameter weggelassen -> Immun gegen 1.21.1 Mapping-Änderungen
	@Inject(method = "renderHand", at = @At("HEAD"), cancellable = true)
	private void onRenderHand(CallbackInfo ci) {
		if (Krypton.isFreecamActive) {
			ci.cancel();
		}
	}
}