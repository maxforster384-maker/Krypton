package com.krypton.cheatclient.mixin;

import net.minecraft.client.network.ClientPlayerInteractionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Zugriff auf {@code ClientPlayerInteractionManager.blockBreakingCooldown}.
 *
 * <p>Vanilla setzt das Feld nach JEDEM zerbrochenen Block auf 5. Solange es
 * laeuft, tut {@code updateBlockBreakingProgress()} gar nichts – es zaehlt nur
 * herunter und kehrt sofort zurueck:
 *
 * <pre>
 * if (this.blockBreakingCooldown &gt; 0) { --this.blockBreakingCooldown; return true; }
 * </pre>
 *
 * <p>In dieser Zeit geht also kein {@code START_DESTROY_BLOCK} raus. Fuer den
 * Spawner-Schutz ist das schaedlich: nach der Pause zwischen zwei gestackten
 * Spawnern verstreichen noch einmal 5 Ticks, in denen der Client nach aussen
 * gar nichts tut, obwohl die Taste schon wieder gedrueckt ist.
 *
 * <p>Ein echter Mausklick umgeht den Cooldown komplett, weil
 * {@code MinecraftClient.doAttack()} beim Druecken {@code attackBlock()}
 * DIREKT aufruft. Genau das macht der Guard jetzt auch – und setzt zusaetzlich
 * beim Reset zwischen zwei Bloecken den Zaehler auf 0, damit der Zustand des
 * Interaktions-Managers wirklich sauber ist.
 *
 * <p>{@code @Accessor} uebernimmt das Remapping des Feldnamens; deshalb hier
 * kein Reflection-Gefrickel mit Intermediary-Namen.
 */
@Mixin(ClientPlayerInteractionManager.class)
public interface InteractionManagerAccessor {
	@Accessor("blockBreakingCooldown")
	void setBlockBreakingCooldown(int value);
}
