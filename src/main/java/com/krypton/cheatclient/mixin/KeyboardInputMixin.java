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
			// Alle Bewegungseingaben nullen – der Spielerkörper bleibt stehen,
			// während die Kamera fliegt.
			// AUSNAHME Sneak: läuft der Dauer-Sneak des Spawner-Schutzes, bleibt
			// der Körper auch in der Freecam geduckt. Sneak allein bewegt den
			// Spieler nicht, die Freecam bleibt also eingefroren – der Schutz
			// geht aber nicht verloren, nur weil man kurz in die Freecam geht.
			// Komponenten-Reihenfolge von PlayerInput:
			// forward, backward, left, right, jump, sneak, sprint
			boolean sneak = Krypton.shouldForceSneak();
			input.playerInput = new net.minecraft.util.PlayerInput(false, false, false, false, false, sneak, false);
		}
	}
}
