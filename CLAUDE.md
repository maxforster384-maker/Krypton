# CLAUDE.md — Krypton Client

Vollständige technische Dokumentation des Projekts. Diese Datei ist die primäre
Kontext-Referenz für Claude Code und für jeden Entwickler, der am Projekt arbeitet.

---

## 1. Projekt-Überblick

**Krypton** ist ein Minecraft-Utility-/Cheat-Client, gebaut als **Fabric Mod** für
**Minecraft 1.21.11**. Der Client bietet ein eigenes ClickGUI, Render-Module (ESP,
Tracers, Fullbright), eine Freecam, mehrere Automatisierungs-Bots (Auto Spawner,
Bones Farmer, Discord-gesteuertes Spawner-Script), Player-Logging/Radar,
Auto-Reconnect und einen Bedrock-Hohlraum-Finder ("Basefinding").

| Eigenschaft | Wert |
|---|---|
| Mod-ID | `krypton` |
| Maven Group | `com.krypton.cheatclient` |
| Mod-Version | `1.0.0` |
| Minecraft | `1.21.11` |
| Yarn Mappings | `1.21.11+build.4` |
| Fabric Loader | `0.19.2` |
| Fabric API | `0.141.3+1.21.11` |
| Loom | `1.14-SNAPSHOT` |
| Java | `21` (Release-Target), CI baut mit JDK 25 |
| Entrypoint | `com.krypton.cheatclient.Krypton` (`main`) |
| Lizenz (deklariert) | CC0-1.0 (aus Fabric-Template übernommen) |
| Git Remote | `https://github.com/maxforster384-maker/Krypton.git` |

**Sprache im Code:** Kommentare, Chat-Strings und GUI-Texte sind größtenteils
**Deutsch**. Neue Kommentare bitte ebenfalls auf Deutsch halten, um konsistent zu
bleiben.

---

## 2. Verzeichnisstruktur

```
Krypton-Client/
├── build.gradle                  Loom-Build, Java 21, sourcesJar, maven-publish
├── gradle.properties             Alle Versionen (MC, Yarn, Loader, API, Loom)
├── settings.gradle               rootProject.name = 'krypton'
├── gradlew / gradlew.bat         Gradle Wrapper
├── .github/workflows/build.yml   CI: baut bei push + PR, lädt build/libs hoch
├── .gitignore                    ignoriert run/, build/, .gradle/, .idea/, out/
├── .gitattributes
├── LICENSE                       CC0
├── README.md                     Fabric-Template-Default (noch nicht angepasst)
├── CLAUDE.md                     ← diese Datei
├── .claude/
│   ├── launch.json               Preview-Server-Configs (projektfremd, Altlast)
│   └── settings.local.json       Lokale Permission-Allowlist
├── run/                          Minecraft-Dev-Runtime (NICHT in Git)
│   ├── krypton_*.txt             Persistierte Client-Settings (siehe §7)
│   └── logs/, crash-reports/, saves/, screenshots/
└── src/main/
    ├── java/com/krypton/cheatclient/
    │   ├── Krypton.java          ~2965 Zeilen — die gesamte Client-Logik
    │   └── mixin/
    │       ├── CameraMixin.java
    │       ├── EntityMixin.java
    │       ├── ExampleMixin.java
    │       ├── GameRendererMixin.java
    │       ├── KeyboardInputMixin.java
    │       ├── MinecraftClientMixin.java
    │       ├── PerspectiveMixin.java
    │       ├── SimpleOptionMixin.java
    │       └── WorldRendererMixin.java
    └── resources/
        ├── fabric.mod.json       Mod-Metadaten + Entrypoint + Mixin-Referenz
        ├── krypton.mixins.json   Mixin-Registrierung (alle client-seitig)
        └── assets/krypton/icon.png
```

### Architektur-Hinweis
Das Projekt ist bewusst **monolithisch**: praktisch die gesamte Logik liegt in
`Krypton.java` als `static`-Felder und `static`-Methoden. Es gibt **keine**
Module-Registry, **kein** Config-Framework und **keine** Abstraktionsschicht.
Alle GUI-Screens sind `public static class` innerhalb von `Krypton`. Die Mixins
lesen und schreiben direkt die `public static` Flags von `Krypton`.

---

## 3. Bootstrap: `Krypton.onInitialize()`

Reihenfolge beim Client-Start:

1. **Alle Settings laden** — `loadWhitelist()`, `loadKeybind()`, `loadFullbright()`,
   `loadReconnect()`, `loadGuiKey()`, `loadFreecamSettings()`, `loadLogs()`,
   `loadCheatStates()`, `loadBonesFarmerKey()`, `loadDropBase()`, `loadDiscordConfig()`.
2. **Keybinds registrieren** über `KeyBindingHelper` (Kategorie `MISC`):
   - `key.krypton.gui` — Default `RIGHT_SHIFT`, überschrieben von `lastSavedGuiKey`
   - `key.krypton.freecam` — Default aus `freecamKey` (Fallback `V`)
   - `key.krypton.bonesfarmer` — Default aus `bonesFarmerHotkey` (Fallback `UNKNOWN`)
3. **`ClientLifecycleEvents.CLIENT_STOPPING`** → `saveCheatStates()`
4. **Chat-Listener** (`ClientReceiveMessageEvents.CHAT` + `.GAME`) für die
   Bones-Farmer-Delivery-Erkennung
5. **Interaktions-Callbacks** (`AttackBlock`, `AttackEntity`, `UseBlock`,
   `UseEntity`, `UseItem`) → in Freecam alles `FAIL`, außer `isManualInteraction`
6. **`ClientTickEvents.END_CLIENT_TICK`** → der große Haupt-Tick (siehe §4)
7. **`HudRenderCallback`** → ArrayList-HUD unten links (siehe §6.1)
8. **`WorldRenderEvents.END_MAIN`** → alle Welt-Renderings (siehe §6.2)

---

## 4. Haupt-Tick (`END_CLIENT_TICK`) — Ablauf in exakter Reihenfolge

1. **GUI-Key Auto-Backup** — liest per Reflection das nicht-finale `InputUtil.Key`
   Feld aus `openGuiKey`; bei Änderung → `saveGuiKey()`.
2. **Keybind-Sync** — `getBoundKeyCode()` für Freecam- und BonesFarmer-Binding;
   bei Abweichung → Feld aktualisieren + speichern. So werden Änderungen aus den
   Vanilla-Controls übernommen.
3. **Server-Tracking** — `lastServer = client.getCurrentServerEntry()`.
   In der Welt: `ticksConnected++`; nach 60 Ticks wird `wasSafetyLogout` gelöscht
   (verhindert Reset während des kurzen Disconnect-Übergangs).
4. **Auto-Reconnect** — siehe §5.7.
5. **Welt == null** → Reset von `isFreecamActive`, `hasMinedSpawner`,
   `autoSpawnerState`, `actionDelayTimer`, `sessionSeenPlayers`,
   `spawnerScriptActive/State/CurrentTarget`; dann `return`.
6. **Discord-Poll** — alle 100 Ticks (5 s) `pollDiscordAsync()`; Trigger-Flag
   vom Background-Thread wird übernommen (`spawnerScriptState = 1`).
7. **Keybind-Handling** — GUI öffnen, Freecam togglen, Bones Farmer togglen.
8. **Freecam-Tick** — siehe §5.1.
9. **Radar-Logging** — siehe §5.8.
10. **Spawner-Scan** — alle 20 Ticks, wenn Spawner-ESP / AutoSpawner /
    BonesFarmer / SpawnerScript aktiv ist. Iteriert über alle Chunks im
    Render-Distance-Quadrat und liest `chunk.getBlockEntities()` (O(n) über
    BlockEntities, **nicht** über alle Blöcke) und filtert
    `MobSpawnerBlockEntity` → `foundSpawners`.
11. **Auto-Spawner-Logik** — siehe §5.4.
12. **`tickBonesFarmer(client)`** — siehe §5.5.
13. **`tickSpawnerScript(client)`** — siehe §5.6.
14. **Bedrock-Finder-Scan** — siehe §5.3.

---

## 5. Module — vollständige Funktionsbeschreibung

### 5.1 Freecam (`isFreecamActive`)

Frei bewegliche Kamera; der Spielerkörper bleibt exakt eingefroren stehen.

**Toggle:** Keybind (`freecamKey`, Default `V`) oder GUI-Modul 0.
`toggleFreecam()` setzt beim Aktivieren `freecam{X,Y,Z}` auf die Spielerposition
(`getEyeY()`), `freecamYaw/Pitch` auf die Spieler-Rotation, sichert diese in
`savedYaw/savedPitch` und `displayYaw/displayPitch`, initialisiert die
`prev*`-Felder für die Interpolation und schaltet `client.chunkCullingEnabled = false`.
Beim Deaktivieren → `chunkCullingEnabled = true`.

**Pro Tick:**
- **Damage-Abbruch:** Wenn `disableFreecamOnDamage && player.hurtTime > 0`
  → Freecam sofort aus.
- **Velocity:** horizontale Velocity wird auf 0 gesetzt (kein Sliding),
  Y bleibt erhalten (Schwerkraft / vertikaler Knockback bleiben "echt").
- **Body-Freeze:** `setYaw/setPitch/setHeadYaw/setBodyYaw` auf
  `displayYaw/displayPitch` — kein sichtbares Zittern für andere Spieler.
- **prev\*-Snapshot** für Frame-Interpolation.
- **Bewegung:** WASD relativ zu `freecamYaw`, Space = hoch, Sneak = runter.
  Geschwindigkeit `2.0f` mit Sprint-Taste, sonst `0.8f`. Vektor wird normalisiert.
- **Linksklick (Abbau):** Raycast **in der eingefrorenen Blickrichtung**
  (`savedPitch/savedYaw`) — die Abbaurichtung dreht sich also nie mit der Kamera mit.
  `updateBlockBreakingProgress()` + `swingHand()`, umschlossen von
  `isManualInteraction = true/false`, damit die Interaktions-Callbacks nicht blocken.
  Ohne Linksklick → `cancelBlockBreaking()`.
- **Rechtsklick (Interaktion):** Raycast **in Freecam-Blickrichtung**
  (`freecamPitch/freecamYaw`), `interactBlock()`, Cooldown 4 Ticks.

**Beteiligte Mixins:** `CameraMixin`, `EntityMixin`, `GameRendererMixin`,
`KeyboardInputMixin`, `MinecraftClientMixin`, `PerspectiveMixin`,
`WorldRendererMixin` — siehe §8.

**Persistenz:** `krypton_keybind.txt` (Taste), `krypton_freecam_settings.txt`
(`disableFreecamOnDamage`). `isFreecamActive` selbst wird **nicht** persistiert.

---

### 5.2 Render-Module

| Modul | Flag | Farbe | Beschreibung |
|---|---|---|---|
| Player ESP | `isPlayerEspActive` | Blau `0xFF0080FF` / Whitelist Grün `0xFF00FF80` | Skelett-Boxen um alle Spieler |
| Tracers | `isTracersActive` | dito | Linien von der Kamera zu jedem Spieler |
| Spawner ESP | `isSpawnerEspActive` | Pink `0xFFFF66CC` | 40 gestapelte Outlines = "dicker" Rahmen |
| Bedrock Finder | `isBedrockFinderActive` | Dunkelrot `0xFF990000` | Outline pro Hohlraum-Block |
| Fullbright | `isFullbrightActive` | — | Gamma-Override auf 100.0 via Mixin |

**Player ESP im Detail** (`WorldRenderEvents.END_MAIN`):
- Arbeitet auf einem `ArrayList`-Snapshot von `world.getPlayers()` →
  verhindert `ConcurrentModificationException` bei Join/Leave.
- Der **eigene Spieler** bekommt ebenfalls eine Box (nützlich, um sich in der
  Freecam wiederzufinden), aber nur wenn der Kamera-Abstand² ≥ 1.0 ist —
  sonst würde die Box in 1st-Person den ganzen Bildschirm füllen.
- Skelett-Aufbau, alle Maße relativ zur Bounding-Box-Höhe `h`:
  - Kopf: 0.5³ Würfel bei `torsoTop + 0.25`, rotiert mit `headYaw - bodyYaw`
  - Torso: `0.5 × (torsoTop-legTop) × 0.25`
  - Arme: Pivot an der Schulter (`±0.375, torsoTop`), Schwing-Animation
    aus `p.limbAnimator` — `cos(animationProgress * 0.6662) * min(speed, 1.0)`,
    linker und rechter Arm gegenphasig
  - Beine: zwei Boxen bei `x = ∓0.125`
- Der gesamte Stack ist mit `-bodyYaw` um die Y-Achse rotiert.
- NaN/Infinity-Guards auf `h`, `bodyYaw` und `headYaw`.

**Tracers im Detail:**
- Startpunkt ist **1 Block vor der Kamera** in Blickrichtung, nicht `(0,0,0)` —
  sonst würde die Near-Clip-Plane die Linie abschneiden.
- `drawTracerLine()` schreibt pro Vertex `position → color → normal → lineWidth(2.0f)`.
  **`lineWidth` ist in 1.21.11 zwingend**, sonst crasht das Spiel mit
  `Missing elements in vertex: LineWidth`.

**Render-Setup:** Alle Welt-Renderings laufen in `WorldRenderEvents.END_MAIN`,
holen `getEntityVertexConsumers()`, rufen zuerst `imm.draw()` (Flush), dann
`GL11.glDisable(GL_DEPTH_TEST)` (Through-Walls), rendern, und `glEnable` am Ende.
Jedes Modul flusht mit einem eigenen `imm.draw()`.

---

### 5.3 Bedrock Finder / Basefinding (`isBedrockFinderActive`)

Sucht im Bedrock-Layer nach zusammenhängenden **Deepslate**-Clustern, die
vollständig von Bedrock umschlossen sind — ein klassischer Indikator für
künstlich erzeugte Hohlräume/Basen.

- **Radius:** `RADIUS = 32` Blöcke um `scanAnchor` (Spielerposition bei Scan-Start)
- **Y-Bereich:** `-64` bis `-50`
- **Inkrementell:** max. **1000 Spalten pro Tick** (`q++ < 1000`), Zustand in
  `scanX`/`scanZ`; nur Spalten in geladenen Chunks werden geprüft.
- **`analyzeHole()`** — BFS/Flood-Fill über `Direction.values()`:
  - Cluster > 10 Blöcke → verworfen (`return`)
  - Jeder Nachbar außerhalb des Clusters muss **Bedrock** sein, sonst verworfen
  - Cluster ≥ `minHoleSize` → in `currentScanBatch`
- Nach vollem Durchlauf (`scanX > RADIUS`) → `stableHoles` wird atomar ersetzt,
  `isScanning = false`. Der nächste Tick startet einen neuen Scan mit neuem Anchor.
- Beim Deaktivieren → `stableHoles.clear()`, `visitedPositions.clear()`.
- `minHoleSize` (Default 2, Bereich 1–100) einstellbar über GUI-Modul 12
  (Inline-Eingabe) oder `HoleSizeScreen`. **Nicht persistiert.**
- HUD zeigt `Finder: §4<Anzahl>`.

---

### 5.4 Auto Spawner / "Guard" (`isAutoSpawnerActive`)

Notfall-Automatik: Sobald ein **fremder Spieler** in Reichweite kommt, werden
alle Spawner in der Nähe abgebaut und der Client loggt sich aus.

**Bedingungen:** aktiv, Welt und Spieler vorhanden, **nicht** in Freecam.

**Spielererkennung** (Distanz² < 1600, also 40 Blöcke):
- Whitelist-Spieler werden übersprungen.
- **Staff erkannt** (`getPlayerRank()` liefert nicht-leer) → Guard schaltet sich
  **selbst ab**, Attack/Sneak los, `cancelBlockBreaking()`, alle States zurück.
  Es wird nichts abgebaut und sich nicht ausgeloggt ("still halten").
- **Normaler Spieler** → `enemyFound = true`, Logout-Log wird geschrieben
  (`lastLogoutLog` mit Uhrzeit, Name, Distanz, XYZ) und gespeichert.

**Spawner-Suche:** 9×9×9 Würfel um den Spieler, `Blocks.SPAWNER`, zusätzlich
Line-of-Sight-Check per Raycast auf die Blockmitte.

**State-Machine `autoSpawnerState`:**

| State | Aktion | Nächster Delay |
|---|---|---|
| 0 | Initialisierung | 3–6 Ticks |
| 1 | Beste Pickaxe in der Hotbar wählen (Silk Touch bevorzugt, sonst beliebige Pickaxe als Backup) | 2–4 |
| 2 | Auf den Spawner drehen (siehe Rotation unten) | — |
| 3 | `sneakKey` drücken | 1–2 |
| 4 | `attackKey` drücken, `isMining = true` | — |
| 5 | Attack halten + Brownian-Drift | — |

**Rotation (State 2):** Zielwinkel aus `atan2`. Es wird ein
**GCD-Snapping** angewendet, das die Vanilla-Mausbewegung nachbildet:
```
f   = sensitivity * 0.6 + 0.2
gcd = f * f * f * 8.0 * 0.15
step -= step % gcd
```
Schrittgröße `diff * 0.3`, geklemmt auf ±20°. Fertig bei `|yawDiff| < 2 && |pitchDiff| < 2`.

**Anti-Pattern-Maßnahmen:**
- Zufälliger Zielpunkt im Spawner (`targetOffset{X,Y,Z}` = 0.3 + rand·0.4)
- Zufällige Delays zwischen allen States
- **Brownian-Motion-Drift** während des Abbaus (State 5): Random Walk mit
  Mean-Reversion (`*= 0.85`), geklemmt auf ±0.12° Yaw / ±0.08° Pitch —
  bewusst **kein** sinusförmiges Muster
- Alle Rotationen laufen durch das GCD-Snapping

**Safety-Logout:** Wenn `hasMinedSpawner == true` und kein Spawner mehr gefunden
wird, startet `safetyLogoutTimer` (8–24 Ticks). Bei 0: alle Keys los, Guard aus,
`wasSafetyLogout = true` (unterdrückt den Auto-Reconnect!), dann
`networkHandler.getConnection().disconnect(...)` mit der Nachricht
`§aAlle Spawner im Umkreis gesichert! §4Notfall-Logout.`

**Persistenz:** `isAutoSpawnerActive` in `krypton_cheats.txt` (Zeile 3).

---

### 5.5 Bones Farmer (`isBonesFarmerActive`)

Server-spezifischer Farm-Bot (zugeschnitten auf einen Server mit gestackten
Spawnern, GUI-Loot und einem `/order`-Delivery-System).

**Toggle:** eigener Hotkey (`bonesFarmerHotkey`, Default `UNKNOWN` = ungebunden)
oder GUI-Modul 13. Wird bei Freecam pausiert. Bei **Staff in der Nähe**
(`isStaffNearby()`) schaltet er sich sofort ab und schließt offene GUIs.

**State-Machine `bonesFarmerState`** (alle Delays randomisiert):

| State | Beschreibung |
|---|---|
| 0 | IDLE — wartet bis kein Screen offen ist |
| 1 | Nächsten Spawner aus `foundSpawners` in Distanz² ≤ 25 wählen; `dropLootClicksTarget = bonesFarmerDropBase ± 2` |
| 2 | Auf den Spawner drehen — GCD-Snapping + variable Geschwindigkeit (0.28–0.50) + Micro-Jitter (±1.5·gcd); Recovery: hängende Screens werden geschlossen |
| 3 | Rechtsklick per echtem Raycast; trifft der Raycast nicht den Ziel-Spawner → zurück zu State 2 |
| 4 | Warten bis die Spawner-GUI offen ist und `findDropButton()` in der letzten Slot-Reihe etwas findet; Timeout 40 Ticks |
| 5 | "Drop Loot" klicken. Vorher `hasArrowInSpawnerGui()` — ist ein Arrow im Loot-Bereich, wird abgebrochen. Nach jedem Klick leichter Yaw/Pitch-Jitter |
| 7 | "Next"-Button klicken, dann zurück zu State 5 |
| 10 | Spawner-GUI schließen |
| 20 | `countBonesInInventory()`; bei 0 → `bonesFarmerEmptyCycles++`, nach **2** leeren Zyklen schaltet sich der Bot ab. Sonst `/order bones` senden |
| 21 | Warten auf die GUI mit "deliver" im Titel; Timeout 400 Ticks |
| 22 | Bones einliefern — bis zu **4** Bone-Slots pro Tick per `QUICK_MOVE`, Cursor wird via `snapCursorToSlot()` auf den ersten bewegt. Enthält Chest-Voll-, Drop-, Stall- und Delivery-Timer-Detection (siehe unten) |
| 23 | Delivery-GUI schließen (Server öffnet daraufhin den Confirm-Dialog) |
| 24 | Warten auf Screen mit "confirm"/"bestätig" im Titel; Timeout 100 Ticks |
| 25 | Confirm klicken: 1) Lime/Green Stained Glass Pane, 2) Fallback Text "confirm", 3) sonst Screen schließen |
| 26 | Nach Confirm: neue Order wählen lassen, oder zurück zu 22 / 20 / 27 |
| 27 | ESC #1 |
| 28 | ESC #2, `dropLootClicksDone = 0`, zurück zu State 2 |

**Detection-Mechaniken in State 22:**
- **Chest voll:** alle GUI-Slots belegt → State 23
- **Drop-Detection:** Inventar-Bones nehmen ab, aber der Chest-Füllstand wächst
  nicht → Items fallen auf den Boden (Order voll/abgelaufen) →
  `bonesFarmerPickNewOrder = true`, State 23
- **Stall-Detection:** 10 Ticks ohne Fortschritt → State 23
- **Delivery-Timer:** Der Chat-Listener setzt `bonesFarmerDeliveryDone`, sobald
  eine Nachricht `"deliver"` oder (`"bones"` und `"complet"`) enthält. Kommt
  **100 Ticks (5 s)** lang keine neue Nachricht mehr, gilt die Order als voll →
  neue Order wählen.

**Konfiguration:**
- `bonesFarmerDropBase` (Default 28, 1–99) — GUI-Modul 15, persistiert in
  `krypton_bfdrop.txt`
- `bonesFarmerHotkey` — GUI-Modul 14, persistiert in `krypton_bfkey.txt`

---

### 5.6 Discord-gesteuertes Spawner-Script (`spawnerScriptActive`)

Fernsteuerung über einen Discord-Channel. Der Client pollt den Channel, baut auf
Kommando alle Spawner in der Nähe ab, teleportiert zu einem festen Spieler,
droppt alles und beendet die JVM.

**Trigger:** `pollDiscordAsync()` läuft alle 100 Ticks in einem eigenen Thread,
GET auf `https://discord.com/api/v9/channels/<id>/messages?limit=1`.
Die Antwort wird per String-Indexing (kein JSON-Parser) nach `"id":"` und
`"content":"` durchsucht. Lautet der Inhalt `Spawner <Spielername>` und
entspricht `<Spielername>` dem eigenen Namen, wird `spawnerScriptTrigger = true`
gesetzt und die Message-ID in `krypton_discord_id.txt` persistiert
(verhindert Re-Trigger nach einem Crash).

**State-Machine `spawnerScriptState`:**

| State | Beschreibung |
|---|---|
| 1 | Nächsten Spawner in Distanz² ≤ 25 suchen, Silk-Touch-Pickaxe wählen, Discord-Log senden |
| 2 | Auf den Spawner drehen (GCD-Snapping, Schritt `diff * 0.3`, ±20°) |
| 3 | `attackKey` halten + Brownian-Drift; **Timeout 400 Ticks (20 s)** → aufgeben |
| 4 | Nächsten Spawner in Reichweite suchen → State 2, sonst → State 5 |
| 5 | `/tpa maxzockt6` senden |
| 6 | Auf die TPA-Bestätigungs-GUI warten, grünes Glas klicken; Timeout 200 Ticks |
| 7 | Warten bis `maxzockt6` in Distanz² ≤ 100 (10 Blöcke) ist; Timeout 600 Ticks (30 s) |
| 8 | `dropSpawnersFromInventory()` |
| 9 | Nochmal droppen (Reste) |
| 10 | Chat leeren, `spawnerScriptActive = false`, Discord-Log senden und **danach `Runtime.getRuntime().halt(1)`** |

**`dropSpawnersFromInventory()`:** Iteriert Slots 0–35, prüft ob die Item-ID
`"spawner"` enthält, mappt Inventar-Slot → Screen-Slot (Hotbar `0-8` → `36+i`,
Hauptinventar `9-35` → `i`) und feuert `SlotActionType.THROW` mit Button 1
(ganzer Stack).

**Discord-Logging:** `sendDiscordLog()` (fire-and-forget Thread) und
`sendDiscordLogThenHalt()` (blockiert bis `getResponseCode()`, dann `halt(1)` —
damit das Log garantiert ankommt, bevor die JVM stirbt).

**Sonderfall:** Solange das Script läuft, wird der Spieler `maxzockt6` vom
Radar-Logging ausgenommen.

> ⚠️ **Achtung:** `discordToken` und `discordChannelId` sind als String-Literale
> hart im Quellcode und damit in der Git-Historie. Siehe §11.

---

### 5.7 Auto Reconnect (`isAutoReconnectActive`)

- Wird ein `DisconnectedScreen` erkannt (und ist es nicht bereits der eigene
  `KryptonReconnectScreen`), wird geprüft: aktiv? **kein** `wasSafetyLogout`?
  `lastServer != null`?
- **Delay-Auswahl:** `reconnectDelays.get(attemptIndex)`. Ist `attemptIndex`
  über das Ende hinaus und `isInfiniteReconnect` aktiv, wird der letzte Delay
  wiederholt. Default-Liste: `3, 10, 30, 60` Sekunden, max. 6 Einträge in der GUI.
- **Jitter:** `delay * 20 + random(-15..+15)` Ticks, Minimum 20 Ticks.
- Die Original-Disconnect-Begründung wird per Reflection aus dem ersten
  `Text`-Feld des `DisconnectedScreen` gelesen und im eigenen Screen angezeigt.
- `KryptonReconnectScreen` zeigt einen Countdown-Button ("Reconnect in X…",
  Klick = sofort) und "Cancel" (schaltet Auto-Reconnect ab, speichert, geht ins
  Multiplayer-Menü).
- Bei `reconnectTicks == 0`: `attemptIndex++`, dann `ConnectScreen.connect(...)`;
  bei Exception → Multiplayer-Menü.
- **`wasSafetyLogout`** blockt den Reconnect gezielt nach einem Guard-Logout und
  wird erst nach 60 Ticks stabiler Verbindung wieder gelöscht.

**Persistenz:** `krypton_reconnect.txt` — Zeile 1 aktiv, Zeile 2 infinite,
Zeile 3 komma-separierte Delays.

---

### 5.8 Player Radar / Logging

Jeder Tick werden alle Spieler in der Welt geprüft. Ein Spieler wird **einmal pro
Session** geloggt (`sessionSeenPlayers`, ein `HashSet<UUID>`, geleert beim
Verlassen der Welt). Übersprungen werden: der eigene Spieler,
Whitelist-Einträge und `maxzockt6` während das Spawner-Script läuft.

**Log-Format:**
```
§7[HH:mm:ss] §c<Name> §8| §e<Distanz> Blöcke §8| §7X:<x> Y:<y> Z:<z>
```
Neue Einträge werden **vorne** eingefügt, die Liste ist auf **50** Einträge
begrenzt, nach jedem Eintrag wird sofort `saveLogs()` aufgerufen.

`playerHistory` ist eine `CopyOnWriteArrayList<String>` (Thread-Sicherheit
gegenüber Render-/Netzwerk-Threads).

**Logout-Log:** `lastLogoutLog` ist ein einzelner String, der vom Auto Spawner
bei einem Notfall-Logout geschrieben wird.

**Persistenz:** `krypton_logs.txt` — **Zeile 1 = `lastLogoutLog`**, danach eine
Zeile pro Historien-Eintrag.

---

### 5.9 Whitelist

Liste von Spielernamen (immer `toLowerCase()`), die:
- vom Radar-Logging ausgenommen sind,
- den Auto Spawner und den Bones Farmer nicht auslösen,
- in ESP und Tracers **grün** (`0xFF00FF80`) statt blau gerendert werden,
- bei der Staff-Erkennung übersprungen werden.

**GUI:** `WhitelistScreen` — Textfeld + "+"-Button zum Hinzufügen, Klick auf
einen Listeneintrag entfernt ihn. Klick-Erkennung läuft über direktes
`GLFW.glfwGetMouseButton()`-Polling mit `wasMouseDown`-Edge-Detection statt über
`mouseClicked()`.

**Persistenz:** `krypton_whitelist.txt`, ein Name pro Zeile.

---

### 5.10 Staff-/Rank-Erkennung (`getPlayerRank`)

Sammelt drei Quellen in einen String und sucht darin case-insensitiv nach
Rang-Schlüsselwörtern:

1. Tab-Listen-Display-Name (`networkHandler.getPlayerListEntry(uuid).getDisplayName()`)
2. Entity-Display-Name (Name über dem Kopf) — kann bei frisch joinenden Spielern
   `null` sein, daher in `try/catch`
3. Scoreboard-Team-Name **und** Team-Display-Name

**Erkennungsreihenfolge** (erster Treffer gewinnt):
`owner` → `sradmin`/`sr.admin` → `admin` → `srmod`/`sr.mod` →
`moderator`/`[mod`/`mod]`/`" mod "` → `srhelper`/`sr.helper` → `helper`
→ sonst `""` (kein Staff).

`isStaffNearby()` prüft alle Spieler in Distanz² ≤ 1600 (40 Blöcke), ohne
Whitelist-Einträge.

---

## 6. UI

### 6.1 HUD (`HudRenderCallback`)

ArrayList unten links, Skalierung `0.5f`, Zeilenhöhe 11 px, 10 px Abstand zum
unteren Rand. Header `§6Krypton`. Wird komplett ausgeblendet, wenn kein Modul
aktiv ist. Angezeigte Einträge:

```
Finder: §4<n>      Player ESP: §bON   Tracers: §bON
Freecam: §aON      Fullbright: §eON   Guard: §eON
Bones: §aON        Spawner ESP: §dON  Reconnect: §aON
```

### 6.2 ClickGUI (`ClickGuiScreen`)

Öffnet mit `openGuiKey` (Default `RIGHT_SHIFT`), nur wenn kein anderer Screen
offen ist. `shouldPause()` gibt `false` zurück — das Spiel läuft weiter.

**Layout:** 4 Spalten am oberen Bildschirmrand, Spaltenbreite
`min(185, (width - 3) / 4)`, Header 20 px, Zeilenhöhe 16 px, 1 px Spaltenabstand.

**Kategorien und Module:**

| Spalte | Kategorie | Icon | Module (Indizes) |
|---|---|---|---|
| 0 | MISC | graues Plus `0xFF8B8FA8` | 0 Freecam, 10 Freecam Key, 3 Disable On Dmg |
| 1 | BASEFINDING | cyan Diamant `0xFF44BBFF` | 4 Bedrock Finder, 12 Min Hole Size |
| 2 | RENDER | lila Ring `0xFFAA55FF` | 5 Player ESP, 16 Tracers, 6 Spawner ESP, 7 Fullbright |
| 3 | CLIENT | türkiser Stern `0xFF44CCFF` | 1 Auto Spawner, 2 Auto Reconnect, 11 Reconnect Set, 17 Whitelist, 8 Player Logs, 9 Logout Logs, 13 Bones Farm, 14 Bones Key, 15 Bones Drop |

**Modul-Indexliste (`MNAME`):**
```
0  FREECAM          6  SPAWNER ESP     12 MIN HOLE SIZE
1  AUTO SPAWNER     7  FULLBRIGHT      13 BONES FARM
2  AUTO RECONNECT   8  PLAYER LOGS     14 BONES KEY
3  DISABLE ON DMG   9  LOGOUT LOGS     15 BONES DROP
4  BEDROCK FINDER   10 FREECAM KEY     16 TRACERS
5  PLAYER ESP       11 RECONNECT SET   17 WHITELIST
```
Toggle-Module sind `mi < 8 || mi == 13 || mi == 16` — sie bekommen einen
animierten Pill-Toggle (16×8 px, Thumb fährt 8 px). Alle anderen zeigen `>`,
ein `[KEY]`-Label oder einen Inline-Zahlenwert.

**Animationen:** `openAnim` (0→1, `+0.10` pro Frame) steuert Overlay-Alpha und
ein Slide-in von −24 px. `dotAnim[]` interpoliert die Toggle-Position mit
`+= (target - current) * 0.22`.

**Input:** Maus-Klicks werden **nicht** über `mouseClicked()` verarbeitet,
sondern per `GLFW.glfwGetMouseButton()`-Polling im `render()` mit
`wasMouseDown`-Edge-Detection (API-versionsunabhängig). Rechtsklick ruft
`modRightClick()` auf — derzeit ohne Funktion.

**Inline-Eingabemodi** in `keyPressed(KeyInput)`:
- `isRebindingFreecam` / `isRebindingBonesFarmer` — nächster Tastendruck wird
  gebunden (`setKeyBindingBoundKey()` + speichern)
- `isEnteringHoleSize` — max. 3 Ziffern, Enter/ESC übernimmt (ESC schließt zusätzlich die GUI)
- `isEnteringDropCount` — max. 2 Ziffern, `commitDropCount()` validiert 1–99

**Farbpalette:**
```
C_OVERLAY  0xBB000000   C_MOD_OFF  0xFF6E7687
C_COL_BG   0xE8090C12   C_MOD_ON   0xFFCCCEd4
C_HEAD_BG  0xFF0B0F17   C_DOT_OFF  0xFF2E3340
C_HEAD_SEP 0xFF1C2335   C_DOT_ON   0xFF00AAFF
C_CAT_TXT  0xFF6B7585   C_ROW_ACT  0x110055FF
C_DASH     0xFF3A4050   C_ROW_HOV  0x14FFFFFF
```

### 6.3 Sub-Screens

| Klasse | Zweck |
|---|---|
| `KryptonReconnectScreen` | Ersetzt den Disconnect-Screen, Countdown + Sofort-Reconnect + Cancel |
| `ReconnectSettingsScreen` | Infinite-Toggle, bis zu 6 Delay-Textfelder mit X-Button, "+ Add Delay", Done |
| `PlayerLogScreen` | Letzte 20 Sichtungen, dynamisch skaliert damit alles auf den Screen passt; "Historie löschen" / "Zurück" |
| `LogoutLogScreen` | Zeigt `lastLogoutLog`; "Log löschen" / "Zurück" |
| `WhitelistScreen` | Liste mit Hover-Highlight, Klick = entfernen; Textfeld + "+" |
| `HoleSizeScreen` | Eigenes Fenster für `minHoleSize` (1–100), Alternative zur Inline-Eingabe |

---

## 7. Persistenz

Alle Dateien liegen im **Arbeitsverzeichnis** des Spiels (`run/` im Dev,
`.minecraft/` in Produktion). Format: schlichtes Plaintext, kein JSON.
**Alle** Lese-/Schreibmethoden schlucken Exceptions still (`catch (Exception e) {}`).

| Datei | Inhalt | Load / Save |
|---|---|---|
| `krypton_logs.txt` | Z1 = `lastLogoutLog`, danach `playerHistory` | `loadLogs` / `saveLogs` |
| `krypton_whitelist.txt` | ein Name pro Zeile | `loadWhitelist` / `saveWhitelist` |
| `krypton_keybind.txt` | Freecam-Keycode | `loadKeybind` / `saveKeybind` |
| `krypton_guikey.txt` | GUI-Keycode | `loadGuiKey` / `saveGuiKey` |
| `krypton_fullbright.txt` | `true`/`false` | `loadFullbright` / `saveFullbright` |
| `krypton_freecam_settings.txt` | `disableFreecamOnDamage` | `loadFreecamSettings` / `saveFreecamSettings` |
| `krypton_cheats.txt` | 5 Zeilen: BedrockFinder, PlayerESP, AutoSpawner, SpawnerESP, Tracers | `loadCheatStates` / `saveCheatStates` |
| `krypton_reconnect.txt` | Z1 aktiv, Z2 infinite, Z3 Delays (CSV) | `loadReconnect` / `saveReconnect` |
| `krypton_bfkey.txt` | Bones-Farmer-Keycode | `loadBonesFarmerKey` / `saveBonesFarmerKey` |
| `krypton_bfdrop.txt` | `bonesFarmerDropBase` (1–99) | `loadDropBase` / `saveDropBase` |
| `krypton_discord_id.txt` | letzte verarbeitete Discord-Message-ID | `loadDiscordConfig` / `saveLastDiscordMessageId` |

**Nicht persistiert:** `isFreecamActive`, `isBonesFarmerActive`, `minHoleSize`,
`spawnerScriptActive`.
`saveCheatStates()` wird nur bei `CLIENT_STOPPING` aufgerufen, alle anderen
Saves laufen sofort bei der Änderung.

---

## 8. Mixins

Alle in `krypton.mixins.json` unter `"client"` registriert,
`compatibilityLevel: JAVA_21`, `defaultRequire: 1`.

| Mixin | Ziel | Injection | Zweck |
|---|---|---|---|
| `CameraMixin` | `Camera` | `update` @TAIL | Setzt in Freecam Kamera-Position und -Rotation. Interpoliert `prev*` → aktuell mit `getTickProgress(true)` (sonst wirkt die Kamera wie 20 FPS). Friert zusätzlich jeden **Frame** Yaw/Pitch/HeadYaw/BodyYaw des Spielers ein und überschreibt damit den Vanilla-Maus-Handler vollständig. |
| `EntityMixin` | `Entity` | `changeLookDirection` @HEAD, cancellable | Leitet in Freecam die Mausbewegung auf `freecamYaw/freecamPitch` um (`delta * 0.15`, Pitch auf ±90° geklemmt) und cancelt den Vanilla-Pfad. Nur für `MinecraftClient.getInstance().player`. |
| `GameRendererMixin` | `GameRenderer` | `renderHand` @HEAD, cancellable | Blendet die Hand/das Item in Freecam aus. Parameter bewusst weggelassen → robust gegen Mapping-Änderungen. |
| `KeyboardInputMixin` | `KeyboardInput` | `tick` @TAIL | Überschreibt in Freecam `input.playerInput` mit einem leeren `PlayerInput` → der Spielerkörper bewegt sich nicht mit. |
| `MinecraftClientMixin` | `MinecraftClient` | `handleBlockBreaking` @HEAD, cancellable | Unterdrückt in Freecam das Vanilla-Mining komplett. **Grund:** Vanilla nutzt `crosshairTarget` (= Freecam-Blickrichtung), unser Code bricht aber in `savedYaw/savedPitch`-Richtung ab. Beide zusammen cancellen sich gegenseitig → Progress startet endlos neu → Server-Desync und zurückbuggende Blöcke. |
| `PerspectiveMixin` | `Perspective` | `isFirstPerson` @HEAD, cancellable | Gibt in Freecam immer `false` zurück. Umgeht den "skip local player in first-person"-Check im `WorldRenderer`, sodass der eigene Spieler sichtbar bleibt — **ohne** die Perspective-Option tatsächlich zu ändern. |
| `SimpleOptionMixin` | `SimpleOption` | `getValue` @HEAD, cancellable | Fullbright: gibt für `options.getGamma()` den Wert `100.0` zurück, wenn `isFullbrightActive`. Identitätsvergleich `(Object) this == gammaOption`. |
| `WorldRendererMixin` | `WorldRenderer` | `isRenderingReady` @HEAD, cancellable | Gibt in Freecam immer `true` zurück → Chunks werden nicht ausgeblendet, wenn man durch Wände fliegt. |
| `ExampleMixin` | `MinecraftServer` | `loadWorld` @HEAD | **Leer** — Rest des Fabric-Templates, kann entfernt werden. |

---

## 9. Wiederkehrende Techniken & Konventionen

### 9.1 GCD-Snapping (Vanilla-Maus-Emulation)
Jede programmatische Rotation wird auf das Vanilla-Mausraster gequantelt:
```java
float f   = sensitivity * 0.6F + 0.2F;
float gcd = f * f * f * 8.0F * 0.15F;
step -= step % gcd;
```
Wird in Auto Spawner (State 2 + 5), Bones Farmer (State 2) und Spawner Script
(State 2 + 3) verwendet.

### 9.2 Reflection
Wird an fünf Stellen eingesetzt, um Mapping-Änderungen zu überleben:
- `getBoundKeyCode()` / `setKeyBindingBoundKey()` — findet das **nicht-finale**
  `InputUtil.Key`-Feld in `KeyBinding` (`defaultKey` ist immer `final`,
  `boundKey` nicht) und ruft danach `KeyBinding.updateKeysByCode()`
- `setGamePerspective()` — greift auf das private `perspective`-Feld in
  `GameOptions` zu und sucht dessen `setValue(Object)`-Methode.
  **Hinweis: aktuell definiert, aber nicht aufgerufen** (der `PerspectiveMixin`
  hat diesen Ansatz ersetzt)
- Auslesen des Disconnect-Grunds aus dem ersten `Text`-Feld von `DisconnectedScreen`
- `snapCursorToSlot()` — liest `x`/`y` (GUI-Offset) aus `HandledScreen`

### 9.3 GUI-Slot-Erkennung
Server-GUIs werden nicht über feste Slot-Indizes, sondern über Inhalt erkannt:
- `itemTextContains()` — durchsucht **Name + CUSTOM_NAME-Component + LORE**,
  entfernt vorher alle `§`-Farbcodes per Regex
- `findDropButton()` — erst Item-Typ (`DISPENSER`/`DROPPER`), dann Text `"drop"`
- `findNextButton()` — dreistufig: Arrow **mit** `next`/`forward`/`right` →
  Arrow **ohne** `back`/`prev`/`left` → beliebiger Text `"next"`
- `findSlotByName()` / `findSlotInRange()` — generisch
- `hasArrowInSpawnerGui()` — prüft nur Slots 0–44; Slots 45–53 enthalten den
  NEXT-Button (selbst ein Arrow) und dürfen nicht mitgezählt werden
- **Slot-Konvention:** `handler.slots.size() - 36` = Anzahl der GUI-Slots
  (die letzten 36 sind immer das Spielerinventar)

### 9.4 Anti-Pattern-Maßnahmen (Übersicht)
- Randomisierte Delays zwischen allen State-Übergängen
- Randomisierte Zielpunkte innerhalb des Ziel-Blocks
- Brownian-Motion-Drift statt periodischer Muster
- Micro-Jitter auf Rotationen beim Bones Farmer
- Reconnect-Delays mit ±15-Tick-Jitter
- `snapCursorToSlot()` bewegt den echten OS-Cursor mit ±3 px Jitter auf den Slot
- Automatisches Abschalten bei erkanntem Staff (Guard **und** Bones Farmer)

### 9.5 Thread-Sicherheit
- `playerHistory` und `stableHoles` sind `CopyOnWriteArrayList`
- `lastDiscordMessageId`, `spawnerScriptTrigger`, `spawnerScriptActive`,
  `bonesFarmerDeliveryDone` sind `volatile` (Discord-Threads / Chat-Callbacks)
- Renderer arbeiten auf `ArrayList`-Snapshots von `world.getPlayers()`

---

## 10. Build, Run, CI

```bash
./gradlew build
```
```bash
./gradlew runClient
```

Artefakte landen in `build/libs/`. `withSourcesJar()` ist aktiv, `maven-publish`
ist konfiguriert (Publikations-Repository ist bewusst leer gelassen).

**IntelliJ Run-Configs:** `.idea/runConfigurations/Minecraft_Client.xml` und
`Minecraft_Server.xml`.

**CI** (`.github/workflows/build.yml`) läuft bei jedem `push` und `pull_request`:
`ubuntu-24.04` → Wrapper-Validation → JDK 25 (Microsoft) → `./gradlew build` →
Upload von `build/libs/` als Artefakt `Artifacts`.

> Hinweis: Konfigurations-Cache ist in `gradle.properties` deaktiviert
> (`org.gradle.configuration-cache=false`), weil IntelliJ + Loom damit noch
> Probleme haben.

---

## 11. Bekannte Probleme, Altlasten und Sicherheitshinweise

### Sicherheit
- **Hartkodierter Discord-Token** in `Krypton.java` (`discordToken`) samt
  Channel-ID. Der Token steht im Klartext im Repository **und in der
  Git-Historie**, also auch nach einem einfachen Löschen noch abrufbar.
  Empfehlung: Token in Discord **widerrufen/neu generieren**, danach aus dem
  Code in eine gitignorierte Config-Datei oder Umgebungsvariable auslagern.
  Ein Bereinigen der Historie (`git filter-repo` / BFG) erfordert einen
  Force-Push und ist nur sinnvoll, wenn der Token ohnehin rotiert wird.
- Der harte Spielername `maxzockt6` ist an drei Stellen im Code fest verdrahtet
  (Radar-Ausnahme, `/tpa`-Ziel, Wait-Bedingung).

### Code-Qualität
- **Alle** `try/catch` in den Persistenz-Methoden schlucken Exceptions
  kommentarlos — Fehler beim Laden/Speichern sind unsichtbar.
- `Krypton.java` ist mit ~2965 Zeilen ein Monolith aus `static`-State.
  Eine Aufteilung in Module/Manager wäre der naheliegende nächste Refactor-Schritt.
- Discord-JSON wird per `indexOf`/`substring` geparst — bricht, sobald das
  Antwortformat oder die Feldreihenfolge sich ändert.
- `debugLogSlots()` und `findSlotInRange()` sind definiert, werden aber nirgends
  aufgerufen (`debugLogSlots` war das frühere Bones-Farmer-Debugging).
- `setGamePerspective()` ist toter Code seit dem `PerspectiveMixin`.
- `modRightClick()` ist leer.
- `bonesFarmerLoggedSlots` wird gesetzt, aber nie ausgewertet.
- `ExampleMixin` ist ein leerer Template-Rest.
- `arrowsBeforeSpawner` wird im Bones Farmer als Bone-Zähler zweckentfremdet —
  der Name stammt noch aus einer früheren Version.
- `Runtime.getRuntime().halt(1)` in State 10 des Spawner-Scripts beendet die JVM
  **ohne** Shutdown-Hooks — `saveCheatStates()` läuft dabei nicht mehr.

### Template-Reste
- `README.md` ist noch der unveränderte Fabric-Template-Text.
- `fabric.mod.json` enthält Platzhalter: `description` ("This is an example
  description!"), `authors: ["Me!"]`, `contact.homepage` → fabricmc.net,
  `contact.sources` → `FabricMC/fabric-example-mod`, `license: CC0-1.0`.
- `.claude/launch.json` enthält Preview-Configs aus einem **anderen** Projekt
  (`gastroenterologie-gropiusstadt`, `lusion-clone`) und hat mit Krypton nichts zu tun.
- `depends.minecraft` steht auf `>=1.21.1`, gebaut wird aber gegen `1.21.11` —
  die Range ist weiter, als der Code tatsächlich unterstützt.

### Versions-Fallstricke (1.21.11)
- **Linien-Rendering** braucht pro Vertex zwingend `.lineWidth(...)`, sonst
  `Missing elements in vertex: LineWidth` (siehe `drawTracerLine`).
- `Vertex`-Reihenfolge ist strikt: `vertex → color → normal → lineWidth`.
- `MatrixStack` in `DrawContext` nutzt `pushMatrix()`/`popMatrix()` (2D),
  während der Welt-`MatrixStack` `push()`/`pop()` verwendet.
- `KeyInput`-Record statt `int keyCode, int scanCode, int modifiers` in
  `Screen.keyPressed`.
- `PlayerInventory.setSelectedSlot(int)` statt direktem Feldzugriff.

---

## 12. Arbeitshinweise für Claude

- **Sprache:** Kommentare und Nutzer-Strings auf Deutsch, passend zum Bestand.
- **Stil:** Der bestehende Code nutzt kompakte Zeilen, `switch`-Expressions und
  viele Inline-Kommentare, die das *Warum* erklären — besonders bei
  Version-Workarounds. Diesen Stil beibehalten.
- **State-Machines:** Beim Erweitern der Bots immer daran denken,
  `delay`/`timeout` **und** den Reset-Pfad (Toggle aus, Welt verlassen,
  Staff erkannt) mitzupflegen.
- **Neue Module** brauchen mindestens: ein `static boolean`-Flag, einen Eintrag
  in `MNAME`, einen Index in `MODS`, Cases in `modOn()`/`modToggle()`,
  ggf. `dotAnim`-Handling, einen HUD-Eintrag und — falls persistent — ein
  Load/Save-Paar plus Aufruf in `onInitialize()`.
  `dotAnim` ist auf **17** Einträge dimensioniert; bei Index ≥ 17 muss das
  Array vergrößert werden.
- **Renderer** immer mit NaN/Infinity-Guards und `ArrayList`-Snapshots arbeiten.
- **Mapping-Unsicherheit:** Bei unklaren Yarn-Namen ist `javap` gegen das
  gemergte Minecraft-Jar im Loom-Cache der schnellste Weg (siehe die
  entsprechenden Einträge in `.claude/settings.local.json`).
- **Vor dem Commit:** `./gradlew build` muss durchlaufen; die CI baut mit JDK 25
  gegen Release-Target 21.
