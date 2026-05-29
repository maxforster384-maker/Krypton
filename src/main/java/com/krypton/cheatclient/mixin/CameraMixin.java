package com.krypton.cheatclient.mixin;

import com.krypton.cheatclient.Krypton;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
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
			// Kamera-Position zwischen Ticks interpolieren (smooth movement).
			// Ohne Interpolation springt die Position nur 20x/Sek → fühlt sich
			// wie 20 FPS an, obwohl das Spiel mit voller FPS läuft.
			float td = MinecraftClient.getInstance().getRenderTickCounter().getTickProgress(true);
			double x = Krypton.prevFreecamX + (Krypton.freecamX - Krypton.prevFreecamX) * td;
			double y = Krypton.prevFreecamY + (Krypton.freecamY - Krypton.prevFreecamY) * td;
			double z = Krypton.prevFreecamZ + (Krypton.freecamZ - Krypton.prevFreecamZ) * td;

			this.setRotation(Krypton.freecamYaw, Krypton.freecamPitch);
			this.setPos(x, y, z);

			// Spieler-Kopf/Body jeden Frame einfrieren (läuft mit Framerate,
			// überschreibt damit den Vanilla-Mouse-Handler komplett)
			MinecraftClient client = MinecraftClient.getInstance();
			if (client.player != null) {
				client.player.setYaw(Krypton.displayYaw);
				client.player.setPitch(Krypton.displayPitch);
				client.player.setHeadYaw(Krypton.displayYaw);
				client.player.setBodyYaw(Krypton.displayYaw);
			}
		}
	}
}
