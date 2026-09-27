package com.krypton.afkdiagnose;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reine Darstellung: keine Welt-, Datei- oder Krypton-Zugriffe. Auch ohne Spiel testbar. */
final class LogPresentation {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM. HH:mm:ss");
    static final int CARD_GAP = 6;

    record Layout(int left, int width, int top, int bottom, int cardHeight, int perPage,
                  int buttonY, int buttonWidth) { }
    record Entry(String heading, int color, List<String> summary, String fullText) { }

    private LogPresentation() { }

    static Layout layout(int width, int height) {
        int panel = Math.max(0, Math.min(720, width - 24));
        int top = 66, bottom = Math.max(top, height - 70);
        int available = bottom - top;
        int card = available < 32 ? 0 : Math.min(72, available);
        int perPage = card == 0 ? 1 : Math.max(1, (available + CARD_GAP) / (card + CARD_GAP));
        return new Layout((width - panel) / 2, panel, top, bottom, card, perPage,
                Math.max(0, height - 32), Math.max(1, (panel - 16) / 3));
    }

    static int pages(int count, int perPage) { return Math.max(1, (count + perPage - 1) / perPage); }
    static int clampPage(int page, int count, int perPage) {
        return Math.clamp(page, 0, pages(count, perPage) - 1);
    }

    static Entry present(String raw) {
        String[] parts = raw.split(" \\| ", 3);
        if (parts.length != 3) return new Entry("Älterer Protokolleintrag", 0xAABBCD, List.of(raw), raw);
        String timestamp;
        try { timestamp = DATE.format(OffsetDateTime.parse(parts[0])); }
        catch (DateTimeParseException ignored) { timestamp = parts[0]; }
        String event = parts[1], detail = parts[2];
        String label = switch (event) {
            case "MESSUNG" -> "Spawner-Prüfung";
            case "MESSUNG_NACHGEHOLT" -> "Prüfung nachgeholt";
            case "MESSUNG_VERSCHOBEN" -> "Prüfung verschoben";
            case "WELTWECHSEL" -> "Weltenwechsel";
            case "VERBINDUNG" -> "Verbindungsstatus";
            case "SCHUTZSTATUS" -> "Krypton-Schutzstatus";
            case "LIMBO_MELDUNG" -> "Limbo-Meldung";
            case "SERVER_UPDATE_MELDUNG" -> "Server-Wartung";
            case "SHARD_BELOHNUNG" -> "Shard erhalten";
            case "MESSLUECKE" -> "Längere Messlücke";
            case "MOD_START" -> "Diagnose gestartet";
            case "MOD_STOP" -> "Diagnose beendet";
            case "DIAGNOSE_DEAKTIVIERT" -> "Diagnose ausgefallen";
            default -> event;
        };
        int color = switch (event) {
            case "MESSLUECKE", "DIAGNOSE_DEAKTIVIERT" -> 0xFF8B8B;
            case "LIMBO_MELDUNG", "SERVER_UPDATE_MELDUNG", "MESSUNG_VERSCHOBEN" -> 0xF3C36B;
            case "SHARD_BELOHNUNG" -> 0x85D6A3;
            default -> 0x78C7E8;
        };
        if (detail.contains("TEILMESSUNG") || detail.contains("NICHT PRUEFBAR")) color = 0xF3C36B;
        Map<String, String> fields = fields(detail);
        List<String> lines = new ArrayList<>();
        boolean measurement = event.equals("MESSUNG") || event.equals("MESSUNG_NACHGEHOLT");
        if (measurement && fields.containsKey("Spawner-BLOCKPOSITIONEN")) {
            lines.add("Spawner: " + fields.get("Spawner-BLOCKPOSITIONEN") + " Positionen • Nächster: "
                    + fields.getOrDefault("Naechster", "unbekannt"));
            lines.add("Chunks: " + fields.getOrDefault("Chunks", "?") + " • "
                    + fields.getOrDefault("Pruefung", "nicht prüfbar") + " • Fensterfokus: "
                    + yesNo(fields.get("FensterFokus")));
            lines.add("XYZ: " + fields.getOrDefault("KoerperXYZ", "unbekannt") + " • Welt: "
                    + fields.getOrDefault("Dimension", "unbekannt").replace("minecraft:", ""));
            lines.add(guardLine(fields));
        } else if (measurement && fields.containsKey("Spawner")) {
            // Offline ist NICHT dasselbe wie eine erfolgreiche Messung mit null Treffern.
            lines.add("Spawner: " + fields.get("Spawner"));
            lines.add("Standort: " + fields.getOrDefault("KoerperXYZ", "keine Spielwelt/Spieler"));
            lines.add(guardLine(fields));
        } else if (event.equals("SCHUTZSTATUS") && fields.containsKey("Guard")) {
            lines.add(guardLine(fields));
            lines.add("Discord-Script: " + yesNo(fields.get("Script")));
        } else {
            // Alte/unbekannte Eintraege bleiben lesbar; Rohdaten werden nie veraendert.
            lines.addAll(List.of(detail.split("; ")));
        }
        return new Entry(timestamp + "  •  " + label, color, List.copyOf(lines), raw);
    }

    private static Map<String, String> fields(String detail) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String part : detail.split("; ")) {
            int equal = part.indexOf('=');
            if (equal > 0) fields.put(part.substring(0, equal), part.substring(equal + 1));
        }
        return fields;
    }

    private static String guardLine(Map<String, String> fields) {
        if (fields.containsKey("Krypton")) return "Krypton: " + fields.get("Krypton");
        if (fields.containsKey("Krypton-Status")) return "Krypton-Status: " + fields.get("Krypton-Status");
        return "Guard: " + fields.getOrDefault("Guard", "unbekannt") + " • Freecam: "
                + yesNo(fields.get("Freecam")) + " • Rejoin-Sperre: " + yesNo(fields.get("RejoinLock"));
    }

    private static String yesNo(String value) {
        if ("true".equals(value)) return "ja";
        if ("false".equals(value)) return "nein";
        return "unbekannt";
    }
}
