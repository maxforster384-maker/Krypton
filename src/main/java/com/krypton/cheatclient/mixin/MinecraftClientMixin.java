package com.krypton.cheatclient.mixin;

import com.krypton.cheatclient.Krypton;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public class MinecraftClientMixin {

	/**
	 * Blockt Vanilla's Block-Abbau-Logik.
	 *
	 * FALL 1 – FREECAM (Ursache des alten Bugs):
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
	 * FALL 2 – SPAWNER-SCHUTZ (Guard):
	 * Der Guard baut seit der Härtung ebenfalls selbst ab (eigener Raycast in
	 * Krypton.guardRaycastTarget), damit ein offener Screen, ein nicht
	 * gegriffener Mauszeiger oder ein fehlender Fensterfokus den Notfall-Abbau
	 * nicht mehr abwürgen können. Auch hier darf Vanilla nicht parallel laufen,
	 * sonst entsteht exakt derselbe Desync wie in Fall 1.
	 *
	 * FIX: Vanilla-Mining unterdrücken, solange einer der beiden eigenen Pfade
	 * aktiv ist. Der eigene Pfad ruft updateBlockBreakingProgress() direkt auf
	 * und hängt damit an keinem der Vanilla-Gates (Screen, Cursor-Lock,
	 * attackCooldown, Fensterfokus).
	 */
	@Inject(method = "handleBlockBreaking", at = @At("HEAD"), cancellable = true)
	private void onHandleBlockBreaking(boolean mining, CallbackInfo ci) {
		if (Krypton.isFreecamActive) {
			ci.cancel();
			return;
		}
		if (Krypton.guardIsMining()) {
			ci.cancel();
		}
	}

	/**
	 * Sperrt das Pausenmenü, solange der Spawner-Schutz scharf ist.
	 *
	 * openGameMenu(boolean) ist der EINZIGE Vanilla-Einstieg ins GameMenuScreen –
	 * sowohl über ESC als auch über "Pause on Lost Focus", wenn das Fenster den
	 * Fokus verliert (z.B. wenn die Remote-Desktop-Sitzung getrennt wird).
	 * Genau dieser zweite Fall ist die häufigste Ursache dafür, dass der Guard
	 * im entscheidenden Moment nicht mehr abbaut: ein offener Screen setzt in
	 * MinecraftClient.tick() das Abbau-Flag auf false.
	 *
	 * Das ist rein clientseitig – der Server bekommt davon nichts mit.
	 */
	@Inject(method = "openGameMenu", at = @At("HEAD"), cancellable = true)
	private void onOpenGameMenu(boolean pauseOnly, CallbackInfo ci) {
		if (Krypton.guardLockActive()) {
			ci.cancel();
		}
	}

	/**
	 * Zweite Verteidigungslinie: filtert Screens, die den Abbau blockieren
	 * würden (Chat, Inventar, Optionen, Statistiken …), unabhängig davon, über
	 * welchen Codepfad sie geöffnet werden.
	 *
	 * Die Entscheidung liegt bewusst in Krypton.isScreenBlocked() – dort ist es
	 * eine BLOCKLIST: Disconnect-, Tod-, Lade- und Server-GUIs kommen weiter
	 * durch, ebenso alle Krypton-eigenen Screens (sonst gäbe es keinen Weg
	 * mehr, den Guard wieder auszuschalten).
	 */
	@Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
	private void onSetScreen(Screen screen, CallbackInfo ci) {
		if (Krypton.isScreenBlocked(screen)) {
			ci.cancel();
		}
	}
}
