package com.krypton.cheatclient.mixin;

import com.krypton.cheatclient.Krypton;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.SimpleOption;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SimpleOption.class)
public class SimpleOptionMixin<T> {

	@Inject(method = "getValue", at = @At("HEAD"), cancellable = true)
	private void onGetValue(CallbackInfoReturnable<T> cir) {
		MinecraftClient client = MinecraftClient.getInstance();

		// Zwingt das Gamma auf 100.0, wenn der Fullbright-Modus aktiv ist
		if (Krypton.isFullbrightActive && client != null && client.options != null) {
			SimpleOption<Double> gammaOption = client.options.getGamma();
			if (gammaOption != null && (Object) this == gammaOption) {
				cir.setReturnValue((T) (Double) 100.0);
			}
		}
	}
}