# AFK Diagnose — separate Fabric-Mod

Minecraft **1.21.11**, Fabric Loader **0.19.2+**, Fabric API, Java **21+**.
Die Mod ist ein eigenstaendiges Gradle-Projekt in diesem Repository. Kryptons
Quellen, Einstellungen, Build und bestehende JAR werden nicht veraendert.
Krypton ist **optional**, keine harte Abhaengigkeit.

## Installation und Bedienung

- `afk-diagnose-1.0.0.jar` zusaetzlich zur bisherigen Krypton-JAR in den Mods-Ordner legen.
- Lokaler NoRisk-Zielordner:
  `C:\Users\maxfo\AppData\Roaming\norisk\NoRiskClientV3\data\profiles\Fabric 1.21.11\custom_mods`.
- Minecraft vollstaendig neu starten. Der separate AFK-PC muss die Diagnose-JAR ebenfalls bekommen.
- Die Messung startet automatisch; kein Modul muss eingeschaltet werden.
- **F8** oeffnet das eigene **AFK LOG**-Fenster. Die Taste ist unter Minecraft →
  Steuerung → Tastenbelegung → MISC aenderbar. Kein neuer Knopf im Krypton-Menue.
- Aeltere/Neuere blaettert, Neueste springt zur aktuellen Seite. Der Bildschirm pausiert
  das Spiel nicht. Bei laufendem Abbau/Guard-Einsatz wird er nicht geoeffnet bzw.
  ein bereits geoeffnetes Diagnose-Fenster geschlossen. Fremde Screens bleiben unangetastet.

## Was wird gemessen?

Alle **zehn echten Minuten**, zusaetzlich kurz nach einem Weltwechsel:

- Vollstaendiges Datum, Uhrzeit und Zeitzonen-Offset.
- Verbindung offen/geschlossen, aktuelle Client-Welt und Dimension.
- **Spielerkoerper-Koordinaten**, nicht Freecam-Position; Fensterfokus.
- Spawner-**Blockpositionen** innerhalb eines Radius von **16 Bloecken** um die
  Spielerfuesse und Abstand/Koordinaten des naechsten Treffers. Dieser Radius ist
  nur der Diagnose-Bereich, **keine Behauptung ueber DonutSMPs Aktivierungsradius**.
- Geladene/gepruefte Client-Chunks; fehlende Chunks oder ein abgebrochener Scan werden
  ausdruecklich als **TEILMESSUNG** ausgewiesen. Null Treffer bei fehlenden Daten
  beweisen NICHT, dass alle Spawner verschwunden sind.
- Optionaler Krypton-Status: Guard, Einsatz, Freecam, Rejoin-Sperre und Discord-Script.

Zusaetzlich werden Welt-/Verbindungswechsel, Diagnose-Tick-Luecken ab 30 Sekunden
und bereits empfangene DonutSMP-Systemmeldungen fuer **Limbo, Server-Updates und
Shard-Belohnungen** protokolliert. Es werden keine Nachrichten oder Befehle gesendet.
Ein Weltwechsel beweist nicht, dass der Server den Spieler zur Farm zurueckgebracht
hat. Shards beweisen keine Spawner-Produktion. Tick-Luecken beweisen keinen bestimmten
Windows-/Netzwerkfehler.

**NICHT messbar:** Loot-Zuwachs, Anzahl der Spawner innerhalb eines Plugin-Stacks,
serverseitige Chunk-Aktivitaet oder die interne Produktionsberechtigung des Plugins.
Der Client sieht nur bereits vom Server uebermittelte Welt-Daten. Es gibt keine
Anti-Kick-, Reconnect-, Teleport- oder Reparaturfunktion.

## Schutz der bestehenden Mod

- Keine Mixins, keine Paket-Erzeugung, keine Aenderung von Eingaben, Rotation,
  Inventar, Sneak, Abbau, Krypton-Flags oder Krypton-Dateien.
- Optional werden ausschliesslich fuenf **public boolean**-Flags aus Krypton gelesen;
  keine Krypton-Methode wird aufgerufen und kein Feld geschrieben. Bei unbekannten
  Flags wird aus Vorsicht nicht gescannt.
- Kein Scan waehrend Guard-Einsatz, Discord-Script oder erkennbarem manuellem Abbau;
  eine faellige Messung wird verschoben und danach nachgeholt. Keine neue GUI wird
  automatisch geoeffnet.
- Maximal neun schon geladene Chunks, maximal 4096 BlockEntity-Eintraege und ein
  weiches Zeitbudget von **2 ms** pro Scan. Ein einzelner Java-Aufruf/JIT/GC kann das
  Budget ueberschreiten; es ist keine Echtzeit- oder Null-Latenz-Garantie.
- Welt-Daten werden nur im Client-Thread gelesen; Dateizugriff laeuft auf einem
  eigenen niedrig priorisierten Daemon-Thread. Warteschlange und RAM-Historie sind
  begrenzt. Eine volle Warteschlange verwirft Eintraege, statt den Abbau zu blockieren.
- Eigene Laufzeitfehler deaktivieren nur die Diagnose. Schreibfehler werden sichtbar
  gemeldet; die Historie bleibt dann nur im RAM.
- Rein lokale Beobachtung erzeugt keine neuen Gameplay-Pakete. Das ist keine
  pauschale Anti-Cheat-, Regelkonformitaets- oder Kompatibilitaetsgarantie fuer andere Mods.

## Protokoll-Datei

`krypton_afk_diagnose.txt` liegt im **Spielverzeichnis** (bei NoRisk neben
`krypton_cheats.txt` und `logs/`). Neue Eintraege werden angehaengt, beim Neustart
werden die letzten Eintraege fuer das GUI wieder eingelesen.

Bei ungefaehr 4 MiB wird nach `krypton_afk_diagnose.txt.1` rotiert. Die bisherige
`.1` wird dabei ersetzt. Das GUI zeigt bis zu 500 Eintraege aus der aktuellen Datei;
lange Eintraege sind in der Vorschau gekuerzt, die Datei enthaelt die volle Messzeile.
Koordinaten sind vertraulich: Dateien nur an gewuenschte Empfaenger weitergeben.
Ein abrupter Prozess-/Stromabbruch kann die letzten noch ausstehenden Eintraege verlieren.

## Build und Tests

Vom Krypton-Repository aus, mit JDK 25 und Java-21-Release-Target:

```powershell
.\gradlew.bat -p afk-diagnose build
```

```bash
./gradlew -p afk-diagnose build
```

Artefakt: `afk-diagnose/build/libs/afk-diagnose-1.0.0.jar`, **nicht** `-sources.jar`.
Der bestehende Wrapper wird wiederverwendet; kein zweites Git-Repository und kein
Umbau von Kryptons Root-Build. Eigener GitHub-Actions-Workflow baut diese Mod separat.

`build` fuehrt `diagnoseTest` aus: Zeitplanung, Nachholen nach langem Abbau,
Weltwechsel, Tick-Luecken, nanoTime-Ueberlauf, Systemmeldungen, asynchrones Speichern,
Neustart-Historie, Rotation, Schreibfehler, begrenzte Warteschlange und
Read-only-Quellcode-Vertrag. Die Tests starten Minecraft nicht.

Ein echter Fabric-/NoRisk-Lauf und ein Nacht-Test sind separat zu bestaetigen;
erfolgreicher Build und Tests ersetzen keinen Ingame-Kompatibilitaetstest.
