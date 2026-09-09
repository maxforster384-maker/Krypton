package com.krypton.cheatclient.mixin;

import net.minecraft.client.input.Input;
import net.minecraft.util.math.Vec2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Schreibzugriff auf {@code Input.movementVector}.
 *
 * <p>Seit 1.21.2 fuellt {@code KeyboardInput.tick()} zwei Felder: das Record
 * {@code playerInput} (public, direkt beschreibbar) und {@code movementVector}
 * ({@code Vec2f}) – und DARAUS entsteht die tatsaechliche Bewegung. In der
 * Freecam muessen beide genullt werden, sonst laeuft der Koerper weiter gegen
 * die Wand.
 *
 * <p>{@code movementVector} ist aber {@code protected} und in der OBERKLASSE
 * {@code Input} deklariert, nicht in {@code KeyboardInput}. Ein
 * {@code @Shadow} im {@code KeyboardInputMixin} liess sich zwar uebersetzen,
 * scheiterte zur Laufzeit aber beim Anwenden des Mixins:
 *
 * <pre>
 * MixinApplyError: Mixin [krypton.mixins.json:KeyboardInputMixin] FAILED during APPLY
 * RuntimeException: Mixin transformation of net.minecraft.class_743 failed
 * </pre>
 *
 * <p>Der Grund: das Refmap-Remapping sucht das Feld in der Zielklasse
 * {@code KeyboardInput} (class_743), deklariert ist es aber in
 * {@code Input}. Deshalb hier ein eigener Accessor direkt auf {@code Input} –
 * damit stimmen Zielklasse und Deklaration ueberein.
 */
@Mixin(Input.class)
public interface InputAccessor {
	@Accessor("movementVector")
	void setMovementVector(Vec2f value);
}
