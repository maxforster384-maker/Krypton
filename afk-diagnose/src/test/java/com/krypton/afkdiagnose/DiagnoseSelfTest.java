package com.krypton.afkdiagnose;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Deterministische Tests ohne Anmeldung, Spielstart, Netzwerk oder Minecraft-Static-Initialisierung. */
public final class DiagnoseSelfTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        clockTests();
        classifierTests();
        logTests();
        readOnlyContract();
        System.out.println("AFK-Diagnose: " + checks + " Pruefungen erfolgreich.");
    }

    private static void clockTests() {
        long start = 1000;
        ObservationClock clock = new ObservationClock(start);
        check(!clock.due(start), "Kein Scan beim Start");
        check(!clock.due(start + ObservationClock.SETTLE - 1), "Chunks bekommen zehn Sekunden Zeit");
        check(clock.due(start + ObservationClock.SETTLE), "Erste Messung faellig");
        long sampled = start + ObservationClock.SETTLE;
        clock.completed(sampled);
        check(!clock.due(sampled + ObservationClock.INTERVAL - 1), "Keine vorzeitige periodische Messung");
        check(clock.due(sampled + ObservationClock.INTERVAL), "Zehn reale Minuten");
        // Beim Guard wird completed() gerade NICHT aufgerufen: faellig bleibt faellig.
        check(clock.due(sampled + ObservationClock.INTERVAL + Duration.ofHours(2).toNanos()), "Langer Abbau verliert Messung nicht");
        long resumed = sampled + ObservationClock.INTERVAL + Duration.ofSeconds(15).toNanos();
        clock.completed(resumed);
        check(!clock.due(resumed + ObservationClock.INTERVAL - 1), "Nachhol-Messung startet Intervall neu");
        long transfer = resumed + Duration.ofMinutes(1).toNanos();
        clock.requestAfterWorldChange(transfer);
        check(!clock.due(transfer + ObservationClock.SETTLE - 1), "Weltwechsel wartet auf Client-Chunks");
        check(clock.due(transfer + ObservationClock.SETTLE), "Weltwechsel-Messung statt Warten bis Minute zehn");
        clock.requestAfterWorldChange(transfer + ObservationClock.SETTLE + 1);
        check(clock.due(transfer + ObservationClock.SETTLE + 1), "Transfer verschluckt ueberfaellige Messung nicht");
        ObservationClock gaps = new ObservationClock(start);
        check(gaps.tick(start + 50_000_000L) == 0, "Normaler Tick ist keine Messluecke");
        check(gaps.tick(start + 80_000_000L) == 0, "30 FPS sind keine Messluecke");
        long afterSleep = start + Duration.ofHours(1).toNanos();
        check(gaps.tick(afterSleep) > 0, "Lange Unterbrechung wird nach Wiederaufnahme erkannt");
        check(gaps.tick(afterSleep + 50_000_000L) == 0, "Messluecke wird nur einmal gemeldet");
        ObservationClock overflow = new ObservationClock(Long.MAX_VALUE - 1_000_000);
        check(overflow.due(Long.MAX_VALUE - 1_000_000 + ObservationClock.SETTLE), "nanoTime-Ueberlauf korrekt");
    }

    private static void classifierTests() {
        check("LIMBO_MELDUNG".equals(ServerMessageClassifier.classify("Connected to proxy limbo. Run /leave to get out.")), "Limbo aus Nacht-Log");
        check("SERVER_UPDATE_MELDUNG".equals(ServerMessageClassifier.classify("WARNING: Servers are updating, do not teleport or you will lose your location")), "Server-Update aus Nacht-Log");
        check("SHARD_BELOHNUNG".equals(ServerMessageClassifier.classify("You earned 1 Shard for playing the server")), "Shard aus Nacht-Log");
        check(ServerMessageClassifier.classify("Please /tpaccept") == null, "Keine Teleport-Automatik");
        check(ServerMessageClassifier.classify("You earned 100 dollars") == null, "Keine erfundene Produktionsmessung");
        check(ServerMessageClassifier.classify("private chat text") == null, "Kein allgemeines Chat-Archiv");
    }

    private static void logTests() throws Exception {
        Path dir = Files.createTempDirectory("afk-diagnose-test-");
        Path logPath = dir.resolve("diagnose.txt");
        AtomicInteger errors = new AtomicInteger();
        try {
            DiagnosticLog log = new DiagnosticLog(logPath, e -> errors.incrementAndGet());
            log.record("TEST", "Zeile eins\nZeile zwei\r\n\tEnde");
            log.close();
            check(log.awaitStopped(3000), "Writer beendet und leert Warteschlange");
            String first = Files.readString(logPath);
            check(first.contains(" | TEST | "), "Ereignistyp gespeichert");
            check(first.matches("(?s)^\\d{4}-\\d{2}-\\d{2}T.*"), "Datum vorhanden, nicht nur Uhrzeit");
            check(first.lines().count() == 1, "Keine Log-Injection durch Zeilenumbrueche");
            check(errors.get() == 0, "Keine Schreibfehler im normalen Fall");
            check(log.history().size() == 1, "GUI-Historie vorhanden");
            try {
                log.history().add("verboten");
                throw new AssertionError("Historie muss unveraenderlich sein");
            } catch (UnsupportedOperationException expected) { checks++; }
            DiagnosticLog reload = new DiagnosticLog(logPath, e -> errors.incrementAndGet());
            reload.close();
            check(reload.awaitStopped(3000), "Neustart-Lesen abgeschlossen");
            check(reload.history().size() == 1 && reload.history().getFirst().contains("TEST"), "Historie ueberlebt Neustart");

            DiagnosticLog rotate = new DiagnosticLog(logPath, e -> errors.incrementAndGet(), 256);
            rotate.record("ROTATION", "x".repeat(300));
            rotate.close();
            check(rotate.awaitStopped(3000), "Rotation abgeschlossen");
            check(Files.isRegularFile(dir.resolve("diagnose.txt.1")), "Alte Diagnose bleibt als .1 erhalten");
            check(Files.readString(logPath).contains("ROTATION"), "Neue Datei nach Rotation");

            // Eine gesperrte/langsame Platte darf niemals den record()-Aufrufer blockieren.
            CountDownLatch inWriter = new CountDownLatch(1);
            CountDownLatch releaseWriter = new CountDownLatch(1);
            DiagnosticLog blocked = new DiagnosticLog(dir.resolve("fehlender-ordner/log.txt"), e -> {
                inWriter.countDown();
                try { releaseWriter.await(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            });
            blocked.record("FEHLER", "kein Ordner");
            check(inWriter.await(3, TimeUnit.SECONDS), "Schreibfehler im Hintergrund erkannt");
            for (int i = 0; i < 600; i++) blocked.record("BURST", Integer.toString(i));
            check(blocked.dropped() > 0, "Volle Warteschlange verwirft statt zu blockieren");
            check(blocked.diskFailed(), "Schreibfehler in GUI sichtbar");
            releaseWriter.countDown();
            blocked.close();
            check(blocked.awaitStopped(3000), "Fehlerhafter Writer haelt Minecraft nicht fest");
            check(blocked.history().size() <= DiagnosticLog.HISTORY_LIMIT, "RAM-Historie begrenzt");
            check(DiagnosticLog.clean("a\nb\rc\td").equals("a b c d"), "Sanitizing");
            check(DiagnosticLog.clean("x".repeat(5000)).length() <= 1203, "Eintraglaenge begrenzt");
        } finally {
            // Nur die drei genau bekannten Test-Dateien und das eigene Temp-Verzeichnis.
            Files.deleteIfExists(logPath);
            Files.deleteIfExists(dir.resolve("diagnose.txt.1"));
            Files.deleteIfExists(dir);
        }
    }

    private static void readOnlyContract() throws Exception {
        List<String> forbidden = List.of("@Mixin", "sendPacket(", "sendChatCommand(", "sendChatMessage(",
                "attackBlock(", "updateBlockBreakingProgress(", "cancelBlockBreaking(", "interactBlock(",
                "clickSlot(", ".disconnect(", "ConnectScreen", "setVelocity(", "setYaw(", "setPitch(",
                "setPosition(", "setPos(", "setSelectedSlot(", "setPressed(", "setKeyPressed(",
                "onKeyPressed(", ".setBoolean(", ".setAccessible(", "Runtime.getRuntime(", "Thread.sleep(");
        Path source = Path.of("src/main/java");
        try (var files = Files.walk(source)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                for (String token : forbidden) check(!content.contains(token), "Keine Spielmutation: " + file.getFileName() + " / " + token);
            }
        }
        String metadata = Files.readString(Path.of("src/main/resources/fabric.mod.json"));
        check(metadata.contains("\"environment\": \"client\""), "Nur clientseitig");
        check(!metadata.contains("\"mixins\""), "Keine Mixin-Registrierung");
        String client = Files.readString(source.resolve("com/krypton/afkdiagnose/AfkDiagnoseClient.java"));
        check(!client.contains("ALLOW_GAME") && !client.contains("MODIFY_GAME"), "Servermeldungen nicht blockieren oder aendern");
        check(client.indexOf("if (busy) {") < client.indexOf("SpawnerSnapshot.read(client)"), "Scan erst nach Abbau-Sperre");
        String scanner = Files.readString(source.resolve("com/krypton/afkdiagnose/SpawnerSnapshot.java"));
        check(scanner.contains("ChunkStatus.FULL, false"), "Kein Nachladen von Chunks");
        check(scanner.contains("TEILMESSUNG (Chunks fehlen)"), "Fehlende Chunks niemals als fehlende Spawner ausgeben");
        // Die optionale Bruecke muss zum unveraenderten Krypton im Repository passen.
        String original = Files.readString(Path.of("../src/main/java/com/krypton/cheatclient/Krypton.java"));
        String observer = Files.readString(source.resolve("com/krypton/afkdiagnose/KryptonObserver.java"));
        for (String field : List.of("isAutoSpawnerActive", "guardEngaged", "isFreecamActive",
                "wasSafetyLogout", "spawnerScriptActive")) {
            check(java.util.regex.Pattern.compile("public\\s+static\\s+(?:volatile\\s+)?boolean\\s+"
                    + field + "\\s*=").matcher(original).find(), "Krypton-Flag oeffentlich lesbar: " + field);
            check(observer.contains("getField(\"" + field + "\")"), "Bruecke liest erwartetes Flag: " + field);
        }
    }

    private static void check(boolean passed, String message) {
        if (!passed) throw new AssertionError(message);
        checks++;
    }
}
