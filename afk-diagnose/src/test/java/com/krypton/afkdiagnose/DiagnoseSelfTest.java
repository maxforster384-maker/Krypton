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
        presentationTests();
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

    private static void presentationTests() {
        for (int width : new int[] {240, 320, 480, 854, 1280}) {
            for (int height : new int[] {180, 240, 360, 480, 720}) {
                LogPresentation.Layout layout = LogPresentation.layout(width, height);
                check(layout.left() >= 0 && layout.left() + layout.width() <= width, "Panel bleibt im Fenster");
                check(layout.buttonWidth() >= 60, "Vollstaendige Navigationsbeschriftung hat Platz");
                check(layout.left() + 3 * layout.buttonWidth() + 16 <= width, "Zurueck-Button nicht abgeschnitten");
                check(layout.buttonY() >= 0 && layout.buttonY() + 20 <= height, "Buttons vertikal im Fenster");
                check(layout.perPage() >= 1 && layout.cardHeight() >= 32, "Mindestens ein lesbarer Eintrag");
                int lastBottom = layout.top() + layout.perPage() * layout.cardHeight()
                        + (layout.perPage() - 1) * LogPresentation.CARD_GAP;
                check(lastBottom <= layout.bottom(), "Karten ueberdecken Navigation nicht");
                check(layout.bottom() + 4 <= layout.buttonY() - 15, "Abstand zur Seitenanzeige");
                check((layout.cardHeight() - 26) / 10 >= 4, "Vier Messzeilen auch im kleinen Fenster sichtbar");
            }
        }
        check(LogPresentation.pages(0, 3) == 1, "Leeres Protokoll hat genau eine Seite");
        check(LogPresentation.pages(3, 3) == 1, "Volle erste Seite erzeugt keine leere zweite");
        check(LogPresentation.pages(4, 3) == 2, "Weiterer Eintrag erzeugt zweite Seite");
        check(LogPresentation.clampPage(-1, 4, 3) == 0, "Neuere endet bei aktuellster Seite");
        check(LogPresentation.clampPage(100, 4, 3) == 1, "Aeltere endet bei letzter Seite");
        check(LogPresentation.clampPage(5, 0, 3) == 0, "Leeren oder kleiner gewordenen Verlauf behandeln");
        String raw = "2026-09-27T09:10:23.123456+02:00 | MESSUNG | WeltNr=2; Dimension=minecraft:overworld; "
                + "KoerperXYZ=1.00,64.00,2.00; FensterFokus=false; Guard=WARTET; Freecam=true; RejoinLock=false; "
                + "Spawner-BLOCKPOSITIONEN=3; Naechster=2.30 Bloecke bei 1,64,3; Chunks=9/9; Pruefung=vollstaendig im Client";
        LogPresentation.Entry entry = LogPresentation.present(raw);
        check(entry.heading().equals("27.09.2026 09:10:23  •  Spawner-Prüfung"), "Vollstaendiges Datum, Uhrzeit und Ereignisname");
        check(entry.summary().size() == 4, "Messwerte in vier strukturierte Zeilen aufgeteilt");
        check(entry.summary().getFirst().contains("Spawner: 3 Positionen"), "Keine erfundene Stackmenge");
        check(entry.summary().getFirst().contains("Abstand: 2.30 Bloecke") && !entry.summary().getFirst().contains(" bei "), "Abstand direkt sichtbar, Zielkoordinaten bleiben in Details");
        check(entry.summary().get(1).contains("Chunks: 9/9") && entry.summary().get(1).contains("Fokus: nein"), "Chunks und Fokus lesbar");
        check(entry.summary().get(2).contains("1.00,64.00,2.00"), "Spielerposition unveraendert");
        check(entry.summary().get(3).contains("Freecam: ja") && entry.summary().get(3).contains("Rejoin-Sperre: nein"), "Schutzflags uebersetzt");
        check(entry.fullText().equals(raw), "Vollstaendige Rohdaten bleiben erhalten");
        check(LogPresentation.present(raw.replace("vollstaendig im Client", "TEILMESSUNG (Chunks fehlen)")).color() == 0xFFF3C36B,
                "Teilpruefung sichtbar als Warnung, nicht als Entwarnung");
        check(LogPresentation.present("2026-09-27T09:10:23+02:00 | MESSLUECKE | Pause").color() == 0xFFFF8B8B, "Messluecke rot");
        check(LogPresentation.present("alt | UNBEKANNT | Text | bleibt erhalten").fullText().endsWith("Text | bleibt erhalten"), "Unbekannte Eintraege mit Trennzeichen bleiben erhalten");
        check(LogPresentation.present("alter unformatierter Eintrag").summary().getFirst().equals("alter unformatierter Eintrag"), "Alte Zeilen weiter sichtbar");
        LogPresentation.Entry offline = LogPresentation.present("2026-09-27T09:10:23+02:00 | MESSUNG | WeltNr=3; "
                + "Spielwelt/Spieler fehlt; Krypton=nicht installiert; Spawner=NICHT PRUEFBAR (keine verbundene Spielwelt)");
        check(offline.summary().getFirst().contains("NICHT PRUEFBAR"), "Offline-Warnung zuerst statt falscher Nullmessung");
        check(offline.color() == 0xFFF3C36B, "Offline-Pruefung bleibt Warnung");
        check(offline.summary().get(2).equals("Krypton: nicht installiert"), "Optional fehlendes Krypton ehrlich darstellen");
        check(LogPresentation.present("2026-09-27T09:10:23+02:00 | SCHUTZSTATUS | Krypton-Status=UNBEKANNT; Messung verschoben")
                .summary().getFirst().contains("UNBEKANNT"), "Unbekannte Bruecke nicht als Guard aus darstellen");
        String screen;
        try { screen = Files.readString(Path.of("src/main/java/com/krypton/afkdiagnose/AfkLogScreen.java")); }
        catch (java.io.IOException problem) { throw new AssertionError(problem); }
        check(screen.contains("Text.literal(\"Zurück\")"), "Vollstaendiger Zurueck-Button");
        check(!screen.contains("Text.literal(\"Neueste\")"), "Kein redundanter dritter Navigationsbutton");
        check(screen.contains("boolean shouldPause() { return false; }"), "Neues Design pausiert Spiel nicht");
        check(LogPresentation.opaque(0xECF4FC) == 0xFFECF4FC, "RGB wird sichtbar statt Alpha null");
        check(LogPresentation.opaque(0) == 0xFF000000, "Auch Schwarz ist nicht versehentlich transparent");
        check(LogPresentation.opaque(0x80CFDAE6) == 0xFFCFDAE6, "Textdarstellung erzwingt volle Deckkraft");
        check(LogPresentation.opaque(0xFFCFDAE6) == 0xFFCFDAE6, "ARGB-Farbe bleibt unveraendert");
        var literals = java.util.regex.Pattern.compile("0x([0-9A-Fa-f]{6,8})\\b").matcher(screen);
        while (literals.find()) {
            long color = Long.parseLong(literals.group(1), 16);
            check((color >>> 24) > 0, "GUI-Farbliteral hat Deckkraft: " + literals.group());
        }
        check(screen.contains("drawTextWithShadow(textRenderer, text, x, y, LogPresentation.opaque(color))"), "Linker Text nutzt Deckkraft-Sicherung");
        check(screen.contains("drawCenteredTextWithShadow(textRenderer, text, width / 2, y, LogPresentation.opaque(color))"), "Zentrierter Text nutzt Deckkraft-Sicherung");
        for (String event : List.of("MESSUNG", "MESSUNG_NACHGEHOLT", "MESSUNG_VERSCHOBEN", "WELTWECHSEL",
                "VERBINDUNG", "SCHUTZSTATUS", "LIMBO_MELDUNG", "SERVER_UPDATE_MELDUNG", "SHARD_BELOHNUNG",
                "MESSLUECKE", "MOD_START", "MOD_STOP", "DIAGNOSE_DEAKTIVIERT", "UNBEKANNT")) {
            check((LogPresentation.present("2026-09-27T09:10:23+02:00 | " + event + " | Test").color() >>> 24) == 255,
                    "Ereignistext hat volle Deckkraft: " + event);
        }
        check((LogPresentation.present("alte Zeile").color() >>> 24) == 255, "Alte Eintraege ebenfalls sichtbar");
        check((new LogPresentation.Entry("Test", 0x78C7E8, List.of(), "Test").color() >>> 24) == 255,
                "Entry sichert auch kuenftige RGB-Farben ab");
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
