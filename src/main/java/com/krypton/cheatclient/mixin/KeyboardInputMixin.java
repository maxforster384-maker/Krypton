package com.krypton.cheatclient.mixin;

import com.krypton.cheatclient.Krypton;
import net.minecraft.client.input.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardInput.class)
public class KeyboardInputMixin {

	@Inject(method = "tick", at = @At("TAIL"))
	private void onTick(CallbackInfo ci) {
		if (Krypton.isFreecamActive) {
			net.minecraft.client.input.Input input = (net.minecraft.client.input.Input) (Object) this;
			input.playerInput = new net.minecraft.util.PlayerInput(false, false, false, false, false, false, false);
		}
	}
}
