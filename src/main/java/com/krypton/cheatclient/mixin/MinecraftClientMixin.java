package com.krypton.cheatclient.mixin;

import com.krypton.cheatclient.Krypton;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public class MinecraftClientMixin {

	/**
	 * Blockt Vanilla's Block-Abbau-Logik komplett wenn FreeCam aktiv ist.
	 *
	 * URSACHE DES BUGS:
	 * Vanilla ruft handleBlockBreaking() jeden Tick auf und nutzt dabei
	 * crosshairTarget – also den Block, auf den die FREECAM-Kamera schaut.
	 * Unser Custom-Code bricht Blöcke aber in savedYaw/savedPitch-Richtung.
	 *
	 * Wenn FreeCam woanders hinschaut (nach "umgucken"):
	 *   1. Vanilla: updateBlockBreakingProgress(FreeCam-Block)
	 *      → Block ≠ unser Block → cancelBlockBreaking() wird intern aufgerufen
	 *      → attackBlock(FreeCam-Block) → geblockt via AttackBlockCallback
	 *   2. Unser Code: updateBlockBreakingProgress(savedYaw-Block)
	 *      → wurde gerade gecancelt → startet neu von 0% → nie fertig
	 *   → Server-Desync → Blöcke buggen zurück
	 *
	 * FIX: Vanilla-Mining komplett unterdrücken wenn FreeCam läuft.
	 * Nur unser Custom-Mining in savedYaw/savedPitch-Richtung darf laufen.
	 */
	@Inject(method = "handleBlockBreaking", at = @At("HEAD"), cancellable = true)
	private void onHandleBlockBreaking(boolean mining, CallbackInfo ci) {
		if (Krypton.isFreecamActive) {
			ci.cancel();
		}
	}
}
