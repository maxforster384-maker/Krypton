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

	// Vanilla-Maus-Look komplett umleiten: In Freecam bewegt die Maus die
	// KAMERA (freecamYaw/Pitch), niemals den Spieler. Der Spielerkopf hat damit
	// keinen Code-Pfad mehr, über den er sich drehen könnte.
	@Inject(method = "changeLookDirection", at = @At("HEAD"), cancellable = true)
	public void onChangeLookDirection(double cursorDeltaX, double cursorDeltaY, CallbackInfo ci) {
		if (Krypton.isFreecamActive && (Object) this == MinecraftClient.getInstance().player) {
			// gleiche Skalierung wie Vanilla (Sensitivität steckt schon im Delta)
			Krypton.freecamYaw   += (float) (cursorDeltaX * 0.15);
			Krypton.freecamPitch += (float) (cursorDeltaY * 0.15);
			if (Krypton.freecamPitch > 90f)  Krypton.freecamPitch = 90f;
			if (Krypton.freecamPitch < -90f) Krypton.freecamPitch = -90f;
			ci.cancel();
		}
	}
}
