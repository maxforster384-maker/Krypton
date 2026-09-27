package com.krypton.afkdiagnose;

import java.time.Duration;
import java.util.Locale;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.world.ClientWorld;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Eigenstaendige Diagnose-Mod. Kein Mixin, keine Paket-Erzeugung und keine Krypton-Aenderung. */
public final class AfkDiagnoseClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("AFK-Diagnose");
    static final String BUILD = "afk-diagnose-2026-09-27";
    private DiagnosticLog log;
    private ObservationClock clock;
    private KryptonObserver krypton;
    private KeyBinding logKey;
    private ClientWorld previousWorld;
    private boolean previousConnected;
    private boolean initialized, deferred, failed;
    private int worldSequence;
    private String previousGuard = "";
    private long lastGuardEvent;

    @Override
    public void onInitializeClient() {
        log = new DiagnosticLog(FabricLoader.getInstance().getGameDir().resolve("krypton_afk_diagnose.txt"),
                problem -> LOGGER.warn("AFK-Protokoll konnte nicht gespeichert werden; Diagnose bleibt nur im RAM.", problem));
        clock = new ObservationClock(System.nanoTime());
        krypton = new KryptonObserver();
        logKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.afk_diagnose.log",
                InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F8, KeyBinding.Category.MISC));
        log.record("MOD_START", BUILD + "; Messintervall=10 echte Minuten; nur lokale Beobachtung");
        LOGGER.info("{} geladen; Protokoll: {}", BUILD, log.path());
        ClientTickEvents.END_CLIENT_TICK.register(this::tickSafely);
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (failed) return;
            try {
                String text = message.getString();
                String event = ServerMessageClassifier.classify(text);
                if (event != null) log.record(event, text);
            } catch (Exception | LinkageError problem) { disable(problem); }
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            log.record("MOD_STOP", "Minecraft wird beendet; letzte ausstehende Eintraege werden bestmoeglich gespeichert");
            log.close();
        });
    }

    private void tickSafely(MinecraftClient client) {
        if (failed) return;
        try { tick(client); } catch (Exception | LinkageError problem) { disable(problem); }
    }

    private void tick(MinecraftClient client) {
        long now = System.nanoTime();
        long gap = clock.tick(now);
        if (gap > 0) log.record("MESSLUECKE", "Kein Diagnose-Tick fuer " + Duration.ofNanos(gap).toSeconds()
                + " Sekunden; Ursache unbekannt (z.B. Stillstand, Suspend oder Haenger)");
        boolean connected = client.getNetworkHandler() != null
                && client.getNetworkHandler().getConnection().isOpen();
        if (!initialized || connected != previousConnected) {
            log.record("VERBINDUNG", (connected ? "Verbindung offen" : "keine offene Spiel-Verbindung") + "; " + location(client));
            previousConnected = connected;
        }
        if (!initialized || client.world != previousWorld) {
            previousWorld = client.world;
            worldSequence++;
            log.record("WELTWECHSEL", "WeltNr=" + worldSequence + "; " + location(client)
                    + "; neue Welt beweist keine Rueckkehr zur Farm");
            clock.requestAfterWorldChange(now);
        }
        initialized = true;

        KryptonObserver.State state = krypton.read();
        if (!state.description().equals(previousGuard)
                && (previousGuard.isEmpty() || now - lastGuardEvent >= 1_000_000_000L)) {
            log.record("SCHUTZSTATUS", state.description());
            previousGuard = state.description();
            lastGuardEvent = now;
        }
        boolean busy = state.busy() || client.interactionManager != null && client.interactionManager.isBreakingBlock();
        // Nur unser selbst geoeffnetes Fenster schliessen, niemals fremde Screens.
        if (busy && client.currentScreen instanceof AfkLogScreen) client.setScreen(null);
        while (logKey.wasPressed()) {
            if (client.currentScreen instanceof AfkLogScreen) client.setScreen(null);
            else if (!busy && client.currentScreen == null) client.setScreen(new AfkLogScreen(log));
        }

        if (!clock.due(now)) return;
        if (busy) {
            if (!deferred) log.record("MESSUNG_VERSCHOBEN", "Abbau/Schutz-Einsatz aktiv oder Status unbekannt; KEIN Spawner-Scan; " + state.description());
            deferred = true;
            return;
        }
        String detail = "WeltNr=" + worldSequence + "; " + location(client) + "; FensterFokus=" + client.isWindowFocused()
                + "; " + state.description() + "; "
                + (client.world != null && client.player != null && connected
                    ? SpawnerSnapshot.read(client) : "Spawner=NICHT PRUEFBAR (keine verbundene Spielwelt)");
        log.record(deferred ? "MESSUNG_NACHGEHOLT" : "MESSUNG", detail);
        deferred = false;
        clock.completed(now);
    }

    private static String location(MinecraftClient client) {
        if (client.world == null || client.player == null) return "Spielwelt/Spieler fehlt";
        return "Dimension=" + client.world.getRegistryKey().getValue() + "; KoerperXYZ="
                + String.format(Locale.ROOT, "%.2f,%.2f,%.2f", client.player.getX(), client.player.getY(), client.player.getZ());
    }

    private void disable(Throwable problem) {
        if (failed) return;
        failed = true;
        log.record("DIAGNOSE_DEAKTIVIERT", "Eigener Fehler; Minecraft und Krypton werden nicht angehalten: " + problem.getClass().getSimpleName());
        LOGGER.warn("AFK-Diagnose wegen eines eigenen Fehlers deaktiviert.", problem);
    }
}
