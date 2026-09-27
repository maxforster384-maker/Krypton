package com.krypton.afkdiagnose;

import java.util.Locale;

/** Nur bekannte, bereits empfangene Systemmeldungen; kein allgemeines Chat-Archiv. */
final class ServerMessageClassifier {
    private ServerMessageClassifier() { }

    static String classify(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("connected to proxy limbo")) return "LIMBO_MELDUNG";
        if (lower.contains("servers are updating")) return "SERVER_UPDATE_MELDUNG";
        if (lower.contains("you earned 1 shard for playing the server")) return "SHARD_BELOHNUNG";
        return null;
    }
}
