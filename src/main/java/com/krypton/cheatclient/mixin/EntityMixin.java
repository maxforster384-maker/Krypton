package com.krypton.cheatclient.mixin;

import com.krypton.cheatclient.Krypton;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public class EntityMixin {

	@Inject(method = "changeLookDirection", at = @At("HEAD"), cancellable = true)
	public void onChangeLookDirection(CallbackInfo ci) {
		if (Krypton.isFreecamActive && (Object) this == MinecraftClient.getInstance().player) {
			ci.cancel();
		}
	}
}