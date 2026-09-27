package com.krypton.afkdiagnose;

import java.lang.reflect.Field;
import net.fabricmc.loader.api.FabricLoader;

/** Optionale Bruecke: ausschliesslich public-Flags lesen, niemals schreiben oder Methoden aufrufen. */
final class KryptonObserver {
    record State(boolean busy, String description) { }

    private final boolean installed = FabricLoader.getInstance().isModLoaded("krypton");
    private Field guard, engaged, freecam, lock, script;

    KryptonObserver() {
        if (!installed) return;
        try {
            // Fabric initialisiert die main-Entrypoints vor den client-Entrypoints.
            Class<?> type = Class.forName("com.krypton.cheatclient.Krypton", false,
                    KryptonObserver.class.getClassLoader());
            guard = type.getField("isAutoSpawnerActive");
            engaged = type.getField("guardEngaged");
            freecam = type.getField("isFreecamActive");
            lock = type.getField("wasSafetyLogout");
            script = type.getField("spawnerScriptActive");
        } catch (ReflectiveOperationException | LinkageError ignored) {
            guard = null;
        }
    }

    State read() {
        if (!installed) return new State(false, "Krypton=nicht installiert");
        if (guard == null) return unknown();
        try {
            boolean guardOn = guard.getBoolean(null);
            // guardEngaged umfasst den gesamten Einsatz, auch Dreh-/Loslass-Pausen.
            // Das private isMining wird bewusst NICHT per setAccessible angefasst.
            boolean inAction = engaged.getBoolean(null);
            boolean scriptOn = script.getBoolean(null);
            return new State(inAction || scriptOn,
                    "Guard=" + (guardOn ? inAction ? "EINSATZ" : "WARTET" : "AUS")
                    + "; Freecam=" + freecam.getBoolean(null)
                    + "; RejoinLock=" + lock.getBoolean(null) + "; Script=" + scriptOn);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return unknown();
        }
    }

    private State unknown() {
        // Fail-closed: bei einer unbekannten Krypton-Version keine Welt scannen.
        return new State(true, "Krypton-Status=UNBEKANNT; Spawner-Messung aus Vorsicht verschoben");
    }
}
