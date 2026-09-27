# Arbeitsregeln — AFK Diagnose

Dieses Unterprojekt ist eine separate, ausschliesslich beobachtende Client-Mod
fuer Fabric/Minecraft 1.21.11. Die technische Referenz ist README.md in diesem Ordner.

- Kryptons bestehende Quellen, Root-Build, Runtime-Dateien und installierte JAR
  bleiben unveraendert. Nur Diagnose-Dateien und der separate CI-Workflow gehoeren
  zu diesem Unterprojekt. Kein eingebettetes Git-Repository anlegen.
- Keine Mixins, Gameplay-Pakete, Klicks, Bewegungen, Befehle, Teleports, Reconnects
  oder Schreibzugriffe auf Krypton. Nur public-Flags lesen, niemals Methoden aufrufen.
- Client-Welt nur im Client-Thread lesen, niemals Chunks nachladen. Scans waehrend
  Abbau/Guard/Script verschieben; fehlende Daten immer als nicht pruefbar/teilweise
  kennzeichnen. Spawner-Anwesenheit niemals als Produktionsnachweis ausgeben.
- Dateizugriffe ausschliesslich ueber den begrenzten asynchronen Diagnose-Writer.
  Bei Fehlern darf nur die Diagnose ausfallen, nicht Krypton/Minecraft.
- Kommentare und GUI auf Deutsch. README sowie diese Datei und CLAUDE.md bei
  relevanten Diagnose-Aenderungen synchron halten.
- GUI: farbige Ereigniskarten, lesbare Zeit/Messwerte, Details per Maus; nur
  Neuere/Aeltere plus vollstaendiges Zurueck. Navigation an Fenster anpassen und
  Layout/Seitengrenzen ohne Minecraft testen. Keine Messlogik fuer Design aendern.
- Textfarben immer als ARGB mit Alpha FF, auch dynamische Ereignisfarben.
  DrawContext verwirft in 1.21.11 Text mit Alpha 0. Deckkraft am Zeichenpfad
  absichern und testen; die vier Messzeilen auch in kleinen Fenstern zeigen.
- Mit JDK 25 vom Repository aus `.\gradlew.bat -p afk-diagnose build` ausfuehren.
  Tests muessen erfolgreich sein. Keine Installation/Commits/Pushes bei Buildfehlern.
- Nur `afk-diagnose/build/libs/afk-diagnose-1.0.0.jar` nach
  `C:\Users\maxfo\AppData\Roaming\norisk\NoRiskClientV3\data\profiles\Fabric 1.21.11\custom_mods`
  installieren. Alle anderen JARs belassen, Quelle/Ziel per SHA-256 pruefen.
- Nur aufgabenbezogene Quellen/Dokumentation/CI stagen; Diagnose-Logs und Runtime
  niemals committen. Nach erfolgreicher Bearbeitung deutsch committen, auf den
  bestehenden Upstream pushen und verifizieren (ausser der Nutzer untersagt es).
- Kein 100-Prozent-Sicherheitsversprechen; Build, Tests und echten Ingame-Test
  klar auseinanderhalten. Insbesondere kein Anti-Cheat- oder Loot-Nachweis behaupten.
