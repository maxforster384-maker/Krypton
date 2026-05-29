package com.krypton.cheatclient.mixin;

import com.krypton.cheatclient.Krypton;
import net.minecraft.client.option.Perspective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Perspective.class)
public class PerspectiveMixin {

    // Wenn Freecam aktiv ist: immer false zurückgeben.
    // Das verhindert den "skip local player in first-person" Check in WorldRenderer
    // und macht den eigenen Spieler sichtbar ohne die Perspective-Option zu ändern.
    @Inject(method = "isFirstPerson", at = @At("HEAD"), cancellable = true)
    private void onIsFirstPerson(CallbackInfoReturnable<Boolean> cir) {
        if (Krypton.isFreecamActive) {
            cir.setReturnValue(false);
        }
    }
}
