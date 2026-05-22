package com.krypton.cheatclient.mixin;

import com.krypton.cheatclient.Krypton;
import net.minecraft.client.render.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow protected abstract void setPos(double x, double y, double z);
	@Shadow protected abstract void setRotation(float yaw, float pitch);

	@Inject(method = "update", at = @At("TAIL"))
	private void onCameraUpdate(CallbackInfo ci) {
		if (Krypton.isFreecamActive) {
			this.setRotation(Krypton.freecamYaw, Krypton.freecamPitch);
			this.setPos(Krypton.freecamX, Krypton.freecamY, Krypton.freecamZ);
		}
	}
}