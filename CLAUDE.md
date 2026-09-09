# CLAUDE.md — Krypton Client

Vollständige technische Dokumentation des Projekts. Diese Datei ist die primäre
Kontext-Referenz für Claude Code und für jeden Entwickler, der am Projekt arbeitet.

---

## 1. Projekt-Überblick

**Krypton** ist ein Minecraft-Utility-/Cheat-Client, gebaut als **Fabric Mod** für
**Minecraft 1.21.11**. Der Client bietet ein eigenes ClickGUI, Render-Module (ESP,
Tracers, Fullbright), eine Freecam, mehrere Automatisierungs-Bots (Auto Spawner,
Discord-gesteuertes Spawner-Script), Player-Logging/Radar,
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
    │   ├── Krypton.java          ~3850 Zeilen — die gesamte Client-Logik
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
   `loadCheatStates()`, `loadDiscordConfig()`,
   `loadSafetyLogout()`, `loadStaffDetect()`, `loadDisconnectLog()`.
2. **Keybinds registrieren** über `KeyBindingHelper` (Kategorie `MISC`):
   - `key.krypton.gui` — Default `RIGHT_SHIFT`, überschrieben von `lastSavedGuiKey`
   - `key.krypton.freecam` — Default aus `freecamKey` (Fallback `V`)
3. **`ClientLifecycleEvents.CLIENT_STOPPING`** → `saveCheatStates()`
3b. **`ClientTickEvents.START_CLIENT_TICK`** → `applyForceSneak()` (Auto-Sneak des
   Spawner-Schutzes, siehe §5.4.2). Bewusst **START** und nicht END: der Handler
   hängt am Kopf von `MinecraftClient.tick()` und läuft damit vor
   `world.tickEntities()` → `KeyboardInput.tick()`. Würde man den Sneak erst in
   `END_CLIENT_TICK` setzen, gäbe es beim Loslassen der echten Sneak-Taste jedes
   Mal einen Tick ohne Sneak und damit ein `STOP_SNEAKING`/`START_SNEAKING`-Paketpaar.
5. **Interaktions-Callbacks** (`AttackBlock`, `AttackEntity`, `UseBlock`,
   `UseEntity`, `UseItem`) → in Freecam alles `FAIL`, außer `isManualInteraction`
6. **`ClientTickEvents.END_CLIENT_TICK`** → der große Haupt-Tick (siehe §4)
7. **`HudRenderCallback`** → ArrayList-HUD unten links (siehe §6.1)
8. **`WorldRenderEvents.END_MAIN`** → alle Welt-Renderings (siehe §6.2)

---

## 4. Haupt-Tick (`END_CLIENT_TICK`) — Ablauf in exakter Reihenfolge

1. **GUI-Key Auto-Backup** — liest per Reflection das nicht-finale `InputUtil.Key`
   Feld aus `openGuiKey`; bei Änderung → `saveGuiKey()`.
2. **Keybind-Sync** — `getBoundKeyCode()` für das Freecam-Binding;
   bei Abweichung → Feld aktualisieren + speichern. So werden Änderungen aus den
   Vanilla-Controls übernommen.
3. **Server-Tracking** — `lastServer = client.getCurrentServerEntry()`.
   In der Welt: `ticksConnected++`; nach 60 Ticks werden `wasSafetyLogout`
   (via `setSafetyLogout(false)`, löscht auch die Datei) und `sessionFixAttempts`
   zurückgesetzt. Die 60 Ticks verhindern ein Reset während des kurzen
   Disconnect-Übergangs; da bei gesetztem Flag **jeder** automatische Rejoin
   gesperrt ist, kann eine Verbindung, die 60 Ticks hält, nur manuell zustande
   gekommen sein.
4. **Auto-Reconnect / Session-Fix** — siehe §5.7 und §5.11.
5. **Welt == null** → Reset von `isFreecamActive`, `hasMinedSpawner`,
   `autoSpawnerState`, `actionDelayTimer`, `sessionSeenPlayers`,
   `spawnerScriptActive/State/CurrentTarget`, `guardEngaged`, `sneakSuppressTicks`,
   `guardAimFailTicks`, `guardSneakWaitTicks`; dann `return`.
5b. **Guard-Watchdog** — `ensureGuardReady(client)`, danach `guardExitFreecamOnEnemy(client)` und `updateGuardReadiness(client)`. Läuft vor allem anderen
   In-Welt-Code und stellt sicher, dass der Spawner-Schutz jederzeit abbaufähig
   ist (siehe §5.4.1).
6. **Discord-Poll** — alle 100 Ticks (5 s) `pollDiscordAsync()`; Trigger-Flag
   vom Background-Thread wird übernommen (`spawnerScriptState = 1`).
7. **Keybind-Handling** — GUI öffnen, Freecam togglen.
8. **Freecam-Tick** — siehe §5.1.
9. **Radar-Logging** — siehe §5.8.
10. **Spawner-Scan** — alle 20 Ticks, wenn Spawner-ESP / AutoSpawner /
    SpawnerScript aktiv ist. Iteriert über alle Chunks im
    Render-Distance-Quadrat und liest `chunk.getBlockEntities()` (O(n) über
    BlockEntities, **nicht** über alle Blöcke) und filtert
    `MobSpawnerBlockEntity` → `foundSpawners`.
11. **Auto-Spawner-Logik** — siehe §5.4.
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
- **Velocity:** wird **nicht** angefasst. Früher wurde X/Z jeden Tick auf 0 gesetzt —
  ein Anti-Cheat-Vektor: kein Knockback, kein Treiben im Wasser, Bewegungs-
  Vorhersage (Grim) schlägt fehl. Der Körper steht trotzdem, weil der
  `KeyboardInputMixin` alle Eingaben nullt; Restmomentum läuft wie in Vanilla aus.
- **Body-Freeze:** `setYaw/setPitch/setHeadYaw/setBodyYaw` **und** `lastYaw/lastPitch/lastHeadYaw/lastBodyYaw` (`freezePlayerRotation()`) auf
  `displayYaw/displayPitch` — kein sichtbares Zittern für andere Spieler.
- **prev\*-Snapshot** für Frame-Interpolation.
- **Bewegung:** WASD relativ zu `freecamYaw`, Space = hoch, Sneak = runter.
  Geschwindigkeit `2.0f` mit Sprint-Taste, sonst `0.8f`. Vektor wird normalisiert.
- **Linksklick (Abbau):** Raycast **in der eingefrorenen Blickrichtung**
  (`savedPitch/savedYaw`) — die Abbaurichtung dreht sich also nie mit der Kamera mit.
  `updateBlockBreakingProgress()` + `swingHand()`, umschlossen von
  `isManualInteraction = true/false`, damit die Interaktions-Callbacks nicht blocken.
  Ohne Linksklick → `cancelBlockBreaking()`.
- **Rechtsklick (Interaktion):** Raycast **in der eingefrorenen Blickrichtung** wie der Abbau
  (`savedPitch/savedYaw`), `interactBlock()`, Cooldown 4 Ticks. Nie in Kamerarichtung — sonst interagiert der Spieler aus Serversicht mit einem Block hinter seinem Rücken.

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

### 5.4 Auto Spawner / "Guard" / Spawner-Schutz (`isAutoSpawnerActive`)

Notfall-Automatik: Sobald ein **fremder Spieler** in Reichweite kommt, werden
alle Spawner in der Nähe abgebaut und der Client loggt sich aus.

**Bedingungen:** aktiv, Welt und Spieler vorhanden, **nicht** in Freecam.

**Einschalten über das ClickGUI (Modul 1) schaltet automatisch mit ein:** Auto Reconnect,
Session Fix und Infinite — und speichert sie. Guard an bedeutet AFK-Betrieb; ohne
Reconnect stünde der Client nach dem ersten Kick im Menü, bis jemand hinschaut.

**Bereitschaftsprüfung (`updateGuardReadiness()`):** alle 40 Ticks, solange der Guard
scharf, aber nicht im Einsatz ist — erreichbarer Spawner (derselbe Raycast wie beim
Abbau) und Spitzhacke im Inventar. Ergebnis im HUD: `Guard: §aBEREIT`, `§aBEREIT §e(ohne
Silk Touch)` oder `§cNICHT BEREIT – <Grund>`. Man sieht also **vorher**, ob der Notfall-Abbau
funktionieren würde, statt es erst zu merken, wenn der Gegner da ist.

**Notfall vor Freecam (`guardExitFreecamOnEnemy()`):** In der Freecam kann der Guard nicht
arbeiten (Körper eingefroren). Bisher war er dort schlicht aus. Jetzt: Fremder in 40 Blöcken
→ Freecam wird beendet, der Guard-Block läuft im selben Tick.

**Spitzhacke aus dem Inventar (State 1):** Liegt keine Spitzhacke in der Hotbar, holt der
Guard sie per `SlotActionType.SWAP` (Vanilla-Zifferntasten-Paket) aus dem Hauptinventar in den
aktuellen Slot — Silk Touch bevorzugt, ein Versuch pro Einsatz (`guardSwapTried`).

**Spielererkennung** (Distanz² < 1600, also 40 Blöcke):
- Whitelist-Spieler werden übersprungen.
- **Staff erkannt** (`getPlayerRank()` liefert nicht-leer) → Guard schaltet sich
  **selbst ab**, Attack los, `cancelBlockBreaking()`, alle States zurück.
  Es wird nichts abgebaut und sich nicht ausgeloggt ("still halten").
- **Normaler Spieler** → `enemyFound = true`, Logout-Log wird geschrieben
  (`lastLogoutLog` mit Uhrzeit, Name, Distanz, XYZ) und gespeichert.

**`guardEngaged`** (`enemyFound || hasMinedSpawner`) markiert den Notfall-Modus.
Solange er läuft, hat der Abbau absoluten Vorrang:
vom Server geöffnete GUIs werden geschlossen.

**Spawner-Suche:** `findReachableSpawner()` — 9×9×9 Würfel um den Spieler,
`Blocks.SPAWNER`. Pro Kandidat **derselbe Check wie beim Abbau**: Raycast vom
Auge Richtung Blockmitte, Länge = `getBlockInteractionRange()`, der **erste**
getroffene Block muss der Spawner sein. Davon der nächstgelegene. Ziele aus
`guardFailedTargets` (3 s nicht getroffen) werden übersprungen, damit der
Guard garantiert alle Spawner durchgeht und danach beim Safety-Logout landet.
Gibt es keinen erreichbaren Spawner, zeigt das HUD
`Guard: §cKEIN SPAWNER IN REICHWEITE` (§6.1) — der Spieler muss dann **innerhalb**
**von 4,5 Blöcken mit freier Sicht** stehen, sonst kann der Guard nichts tun.

**State-Machine `autoSpawnerState`:**

| State | Aktion | Nächster Delay |
|---|---|---|
| 0 | Initialisierung | 3–6 Ticks |
| 1 | Beste Pickaxe in der Hotbar wählen (Silk Touch bevorzugt, sonst beliebige Pickaxe als Backup) | 2–4 |
| 2 | Auf den Spawner drehen (siehe Rotation unten) | — |
| 3 | Warten bis der Sneak serverseitig anliegt (`player.isSneaking()`), Notausstieg nach 10 Ticks | 1–2 |
| 4 | `attackKey` drücken, `isMining = true`, `guardAimFailTicks = 0` | — |
| 5 | Brownian-Drift + **eigener Abbau** (siehe §5.4.1). Nach jedem zerbrochenen Block **8–13 Ticks Pause mit mechanisch losgelassener Taste** (siehe unten) | — |

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

---

#### 5.4.1 Abbau-Garantie — warum der Guard nicht mehr über Vanilla abbaut

**Das Problem.** `MinecraftClient.tick()` ruft `handleBlockBreaking(boolean)` mit
einem Flag auf, in das u. a. `currentScreen == null` und
`mouse.isCursorLocked()` einfließen. Ist das Flag `false`, läuft intern
`cancelBlockBreaking()` und der Abbaufortschritt fällt auf 0 zurück.
Zusätzlich ruft `MinecraftClient.setScreen()` beim Öffnen eines Screens
`KeyBinding.unpressAll()` auf — der vom Guard gedrückte Attack-Key geht damit
wieder los.

Daraus folgen genau die Bugs "er baut plötzlich nicht mehr ab" / "es backt rum":

| Auslöser | Vanilla-Folge |
|---|---|
| ESC gedrückt | `GameMenuScreen` offen → Abbau tot |
| Fensterfokus weg (Alt-Tab, Remote-Desktop-Trennung) bei aktivem *Pause on Lost Focus* | `openGameMenu(true)` → Abbau tot |
| Chat / Inventar / Optionen offen | Abbau tot |
| Server öffnet eine GUI | Abbau tot |
| Nach dem Schließen eines Screens ist der Cursor nicht wieder gegriffen | `isCursorLocked() == false` → Abbau tot |

**Die Lösung — drei Ebenen, weil jede einzelne Lücken lässt:**

1. **`openGameMenu(boolean)` wird geblockt** (`MinecraftClientMixin`).
   Das ist der einzige Vanilla-Einstieg ins Pausenmenü und deckt sowohl ESC als
   auch den Fokusverlust ab.
2. **`setScreen(Screen)` filtert** über `Krypton.isScreenBlocked()`
   (`MinecraftClientMixin`) — siehe §5.4.3.
3. **Der Guard baut selbst ab.** State 5 macht einen echten Raycast in der
   aktuellen Blickrichtung (`guardRaycastTarget()`) und ruft bei einem Treffer
   auf dem Ziel-Spawner `interactionManager.updateBlockBreakingProgress()` +
   `swingHand()` — genau wie die Freecam. Damit hängt der Abbau an **keinem**
   Vanilla-Gate mehr (Screen, Cursor-Lock, `attackCooldown`, Fensterfokus).
   Vanillas `handleBlockBreaking` wird währenddessen gecancelt
   (`Krypton.guardIsMining()`), sonst brechen sich beide Pfade gegenseitig ab —
   derselbe Desync, der schon bei der Freecam auftrat.

**Anti-Cheat-Sicht:** Am Paketbild ändert sich nichts. Es gehen dieselben
`PlayerAction`- und Swing-Pakete raus wie beim manuellen Abbau, und nur auf
Blöcke, die ein Raycast in echter Blickrichtung innerhalb von
`getBlockInteractionRange()` auch trifft. Das ist **strenger** als vorher: früher
Zielwahl — vorher konnte der Guard einen Spawner aus der Würfelecke (bis 6,9
Blöcke) wählen, den der Abbau-Raycast nie traf: anvisieren → Fehlschlag →
dasselbe Ziel → Endlosschleife ohne einen einzigen Schlag.

**Taste zwischen den Blöcken loslassen — sonst bricht der Guard nach dem
ersten Spawner ab.** Bei **gestackten** Spawnern (DonutSMP) rückt sofort das
nächste Exemplar an dieselbe Position nach: der Blockzustand bleibt `SPAWNER`.
Die Bruch-Erkennung über "Block ist kein Spawner mehr" löst deshalb **nie** aus,
die Abbau-Taste bleibt gedrückt, und der Server sieht einen einzigen, nie
endenden Klick — der Abbau des nächsten Exemplars wird nicht mehr registriert.

Erkannt wird der Bruch stattdessen an `interactionManager.isBreakingBlock()`:
Vanilla setzt `breakingBlock = false` (plus 5 Ticks `blockBreakingCooldown`)
genau in dem Tick, in dem `currentBreakingProgress` 1.0 erreicht — unabhängig
davon, was danach an der Position steht. Fällt das Flag, wird
`guardReleaseTicks = 8 + rand(6)` gesetzt: 8–13 Ticks ohne gedrückte Taste und
ohne Abbau-Paket. Dazu kommen Vanillas 5 Ticks `blockBreakingCooldown`, die nach
einem Bruch ohnehin laufen — effektiv **650–900 ms**. Kürzer reicht auf einem
vollen Server nicht: das nächste Exemplar des Stacks kommt erst mit der Antwort
des Servers an, bis dahin steht an der Position clientseitig Luft. Danach startet
der nächste Abbau mit einem frischen `START_DESTROY_BLOCK`, also einem sauber
getrennten Klick.

Losgelassen wird **mechanisch**, nicht nur als Flag: `setAttackPressed()` setzt
neben dem Instanzfeld auch `KeyBinding.setKeyPressed(Key, boolean)` — genau den
statischen Pfad, den Vanilla in `Mouse.onMouseButton` / `Keyboard.onKey` aufruft.
Zusätzlich läuft `cancelBlockBreaking()` wie in Vanillas
`handleBlockBreaking(false)`. Damit ist der Tastenzustand an jeder Stelle
konsistent, die ihn abfragt.

> **Nur ZWISCHEN Blöcken loslassen.** `cancelBlockBreaking()` setzt
> `currentBreakingProgress` auf 0 zurück. Eine Pause *mitten* im Abbau hätte zur
> Folge, dass der Spawner nie fertig wird. Deshalb hängt die Pause strikt am
> erkannten Blockbruch und nicht an einem festen Intervall.

**Safety-Logout-Karenz (`guardSinceBreakTicks`).** Direkt nach einem Bruch ist
die Position für ein paar Ticks leer, bis der Server das nächste Exemplar des
Stacks schickt. `findReachableSpawner()` findet in dieser Lücke nichts — ohne
Gegenmaßnahme würde der Guard "keine Spawner mehr" schließen und **mitten im**
**Stack ausloggen**, obwohl noch Dutzende dastehen. Deshalb startet der
Safety-Logout-Timer erst, wenn der letzte Blockbruch **mehr als 60 Ticks (3 s)**
her ist.


**Kein Hängenbleiben:** Trifft der Raycast das Ziel nicht (verdeckt / zu weit weg),
zählt `guardAimFailTicks`. Nach 5 Ticks geht es zurück in State 2 (neu
anvisieren), nach 60 Ticks wird das Ziel freigegeben (`autoSpawnerState = 0`),
damit stattdessen der Safety-Logout greifen kann.

**Der Watchdog** `ensureGuardReady()` läuft jeden Tick, solange
`guardLockActive()`:
1. gesperrter Screen offen → `setScreen(null)`
2. `guardEngaged` + Server-GUI offen → `player.closeHandledScreen()`
   (schickt das `CloseHandledScreen`-Paket, also kein Desync)
3. kein Screen, Fenster fokussiert, Cursor nicht gegriffen → `mouse.lockCursor()`

---

#### 5.4.2 Auto-Sneak

Solange der Guard scharf ist, sneakt der Spieler **dauerhaft**
(`shouldForceSneak()`), auch in der Freecam.

**Umsetzung:** `applyForceSneak()` setzt in `START_CLIENT_TICK`
`setSneakPressed(client, true)` — **idempotent**, gesetzt wird nur bei Abweichung.
Grund: bei aktivem *Schleichen umschalten* ist `sneakKey` ein `StickyKeyBinding`,
dessen `setPressed(true)` den Zustand **kippt** und dessen `setPressed(false)`
nichts tut; ein Aufruf pro Tick würde Sneak jeden Tick an/aus schalten
(`START`/`STOP_SNEAKING`-Dauerfeuer). Der Server sieht damit exakt
dasselbe wie bei einem Spieler, der Shift gedrückt hält —
`KeyboardInput.tick()` → `PlayerInput.sneak()` → `ClientPlayerEntity` →
`ClientCommandC2SPacket(START_SNEAKING)`. Kein eigener Paketpfad, keine
Sonderbehandlung.

**Warum START und nicht END:** siehe §3, Punkt 3b — sonst gäbe es beim Loslassen
der echten Sneak-Taste jedes Mal einen Tick ohne Sneak und damit ein
`STOP_SNEAKING`/`START_SNEAKING`-Paketpaar.

**Wiederherstellung:** Geht der Guard aus, setzt `applyForceSneak()` den Key
**genau einmal** auf den *echten* physischen Tastenzustand zurück
(`isBindingPhysicallyDown()` über `InputUtil.isKeyPressed()` bzw.
`glfwGetMouseButton()`). Ohne das bliebe Sneak hängen, wenn der User Shift
gerade gedrückt hält. Manuelles Sneaken und Auto-Sneak können sich deshalb nicht
in die Quere kommen — es gibt nur einen Schreibpfad.

**Sneak-Unterdrückung (`sneakSuppressTicks`) — wichtig:**
Ein sneakender Spieler bekommt **serverseitig keine Block-GUI**:
`ServerPlayerInteractionManager.interactBlock()` prüft
`player.shouldCancelInteraction()` (= `isSneaking()`) und ruft dann statt
`BlockState.onUse()` das Item-`useOnBlock()` auf — mit einem Block in der Hand
wird also sogar **gesetzt** statt geöffnet. Wer den Auto-Sneak einbaut, muss ihn
darum für jeden Rechtsklick auf einen Block kurz abmelden:

- `suppressSneak(ticks)` setzt das Fenster,
- `isSneakReleased(client)` prüft, ob der Sneak serverseitig wirklich weg ist.

Angemeldet ist das an einer Stelle:
- **Freecam-Rechtsklick**: meldet ab, solange die rechte Maustaste hängt.

**Was Sneak in Vanilla wirklich tut** (damit die Erwartung stimmt): Sneaken
verhindert, dass man von der Kante eines Blocks fällt bzw. geschoben wird
(`Entity.adjustMovementForSneaking`), senkt die Hitbox von 1.8 auf 1.5 und
unterdrückt die Block-Interaktion (siehe oben). Es verhindert **keinen**
Knockback und keine Fischerruten-Züge.

---

#### 5.4.3 Menü-Sperre

Solange `guardLockActive()` (Guard an **und** in einer Welt) gilt, blockt
`isScreenBlocked()` folgende Screens:

| Geblockt | Grund |
|---|---|
| `GameMenuScreen` | ESC + *Pause on Lost Focus* — der Hauptauslöser |
| `ChatScreen` (**exakt** `getClass()`) | Chat/Command-Eingabe |
| `InventoryScreen`, `CreativeInventoryScreen` | eigenes Inventar |
| `AdvancementsScreen`, `StatsScreen`, `SocialInteractionsScreen` | — |
| alles in `net.minecraft.client.gui.screen.option.*` | Optionen |

**Nicht geblockt** (bewusst eine Blocklist, keine Allowlist — sonst könnte sich
der Client festfahren):
- `null` (Schließen geht immer)
- alle `com.krypton.*`-Screens → das ClickGUI bleibt der Weg, den Guard
  wieder auszuschalten
- alle übrigen `HandledScreen`s (Server-GUIs)
- `SleepingChatScreen` (deshalb der exakte `getClass()`-Vergleich beim Chat) —
  sonst läge der Spieler ohne UI im Bett fest
- Disconnect-, Tod-, Lade- und Ressourcenpack-Screens

Die Sperre ist rein clientseitig, der Server merkt davon nichts. Im HUD steht
`Guard: ON §8[Menüs gesperrt]`, damit klar ist, warum ESC nichts tut.

---

#### 5.4.4 Safety-Logout

Wenn `hasMinedSpawner == true` und kein Spawner mehr gefunden wird, startet
`safetyLogoutTimer` (8–24 Ticks). Bei 0: Attack los, `cancelBlockBreaking()`,
Guard aus, **`setSafetyLogout(true)`**, dann
`networkHandler.getConnection().disconnect(...)` mit der Nachricht
`§aAlle Spawner im Umkreis gesichert! §4Notfall-Logout.`

`setSafetyLogout(true)` schreibt `krypton_safelogout.txt`. Damit ist jeder
**automatische** Rejoin gesperrt — Auto Reconnect *und* Session-Fix, und zwar
auch über einen Client-Neustart hinweg. Der `KryptonReconnectScreen` zeigt in
dem Fall `§4Notfall-Logout aktiv – Auto-Reconnect gesperrt`; der
Reconnect-Button ist deaktiviert (`active = false`), damit die Sperre nicht mit
einem versehentlichen Klick fällt.

Aufgehoben wird die Sperre nur durch eine **manuelle** Verbindung, die 60 Ticks
stabil hält (§4, Punkt 3).

**Persistenz:** `isAutoSpawnerActive` in `krypton_cheats.txt` (Zeile 3),
`wasSafetyLogout` in `krypton_safelogout.txt`.

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
- Die Original-Disconnect-Begründung kommt aus `DisconnectionInfo.reason()` (Feld
  `info` des `DisconnectedScreen`, per Reflection nach **Typ** gesucht, statische
  Felder übersprungen). **Nicht** das erste `Text`-Feld: das ist in 1.21.11 nur
  eine Button-Beschriftung — genau der Fehler, durch den früher **jede** Trennung
  als `SONSTIGES` galt und Bans nie als `KEIN-REJOIN` erkannt wurden.
- `KryptonReconnectScreen` zeigt einen Countdown-Button ("Reconnect in X…",
  Klick = sofort), eine optionale **Hinweiszeile** (`hint`) und "Cancel".
  Bei gesperrtem Rejoin (`autoBlocked`) ist der Reconnect-Button deaktiviert und
  "Cancel" heißt "Zum Serverbrowser" — und schaltet den Auto-Reconnect dann
  **nicht** ab, weil er nicht die Ursache ist.
- Bei `reconnectTicks == 0`: `attemptIndex++`, dann `ConnectScreen.connect(...)`;
  bei Exception → Multiplayer-Menü.

**Entscheidungsreihenfolge im Disconnect-Handler** (erster Treffer gewinnt):

| # | Bedingung | Verhalten |
|---|---|---|
| 1 | `wasSafetyLogout` | **kein** Reconnect, Button gesperrt, Hinweis "Notfall-Logout aktiv" |
| 2 | `lastServer == null` | nichts (kein Server bekannt) |
| 3 | Session-Kick **und** `isSessionFixActive` | Session-Fix, siehe §5.11 |
| 4 | `isAutoReconnectActive` | normale Delay-Leiter |

Punkt 1 steht bewusst ganz oben: der Notfall-Logout darf durch **keinen**
anderen Pfad ausgehebelt werden.

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
- den Auto Spawner nicht auslösen,
- in ESP und Tracers **grün** (`0xFF00FF80`) statt blau gerendert werden,
- bei der Staff-Erkennung übersprungen werden.

**GUI:** `WhitelistScreen` — Textfeld + "+"-Button zum Hinzufügen, Klick auf
einen Listeneintrag entfernt ihn. Klick-Erkennung läuft über direktes
`GLFW.glfwGetMouseButton()`-Polling mit `wasMouseDown`-Edge-Detection statt über
`mouseClicked()`.

**Persistenz:** `krypton_whitelist.txt`, ein Name pro Zeile.

---

### 5.10 Staff-/Rank-Erkennung (`getPlayerRank`)

Der Zielserver markiert sein Team **nicht mehr per Klartext** ("Admin"), sondern
mit einem **farbigen Stern** im Tab-/Team-Prefix:

| Sternfarbe | Rang | Flag |
|---|---|---|
| grün | Mod / Admin | `staffStarGreen` (Default an) |
| blau / aqua | Helper / Owner | `staffStarBlue` (Default an) |
| lila / magenta | Developer | `staffStarPurple` (Default an) |
| andere (rot, orange, gelb) | generisch "Staff" | `staffStarOther` (Default **aus**) |

`getPlayerRank()` prüft zwei Signale, in dieser Reihenfolge:

**1. Stern-Rank.** Quellen werden einzeln geprüft, beim ersten Treffer wird
abgebrochen:
`PlayerListEntry.getDisplayName()` → `player.getDisplayName()` →
`Team.getPrefix()` → `Team.getSuffix()` → `Team.getDisplayName()`.

`collectStars()` läuft mit `Text.visit(StyledVisitor, Style.EMPTY)` über den
Component-Baum und liest die Farbe aus **beiden** Quellen, die auf Servern
vorkommen:
- dem gemergten `Style` (`Style.getColor().getRgb()`), und
- **Legacy-§-Codes im Rohstring** — inklusive `§r` (zurück auf die Style-Farbe),
  der Formatierungscodes `§k§l§m§n§o` (Farbe bleibt stehen) und dem
  BungeeCord-Hex-Format `§x§R§R§G§G§B§B`.

`starColorFamily(rgb)` klassifiziert über den **Farbton (Hue)**, nicht über
feste RGB-Werte — damit funktioniert es auch mit beliebigen Hex-Farben:

| Hue | Familie |
|---|---|
| Sättigung < ~23 % oder max < 40 | unbestimmt (weiß/grau/schwarz → **kein** Staff) |
| 75°–170° | grün |
| 170°–265° | blau/aqua |
| 265°–330° | lila/magenta |
| sonst | andere |

Geprüfte Referenzwerte: `§a`=120°, `§2`=120°, `§9`=240°, `§1`=240°, `§b`=180°,
`§3`=180°, `§d`=300°, `§5`=300°, `§c`=0°, `§6`=40°, `§e`=60°, `§f`/`§7`/`§8`
fallen über die Sättigungsschwelle raus.

**Plausibilitäts-Sicherung (`staffDetectSane`) — wichtig.** Auf Servern wie
DonutSMP hat **jeder** Spieler ein farbiges Deko-Symbol im Tab-Prefix. Würde so
ein Symbol fälschlich als Stern gezählt, wäre plötzlich der halbe Server "Staff"
und der Guard würde sich abschalten — also genau dann nicht schützen, wenn es
darauf ankommt. Deshalb prüft `updateStaffSanity()` alle 40 Ticks: gelten bei
mindestens 5 Spielern **in der Tab-Liste** **mehr als die Hälfte** als Staff, wird die
Stern-Erkennung als kaputt markiert und ignoriert. Es bleibt die
Klartext-Erkennung, und im HUD steht `§cStern-Erkennung unplausibel (n/m) – nur
Text`. Lieber eine sichtbare Warnung als ein stillschweigend abgeschalteter
Schutz.

**Deko-Farben je Familie verwerfen (`staffFamilyDeko`) — die präzisere Stufe.**
Die Notbremse oben schaltet die Stern-Erkennung *komplett* ab. Feiner geht es
pro Farbfamilie: Staff ist auf jedem Server eine kleine **Minderheit** (auf
DonutSMP trägt den echten Stern 1 von 80 Spielern = 1 %), Deko trägt fast jeder
(97 %). `updateStaffSanity()` zählt deshalb alle 40 Ticks über die komplette
Tab-Liste, wie viele Spieler einen Stern **je Farbfamilie** tragen. Liegt eine
Familie über **15 %** (ab **8** Spielern Stichprobe), kann sie kein Rang-Marker
sein und wird ignoriert — nur diese eine Farbe, die echten Rang-Farben bleiben
aktiv. Gezählt wird bewusst **ohne** Deko-Filter (`rawStarFamilies()`), sonst
wäre die Rechnung zirkulär.

Das ist der Schutz davor, dass normale Spieler oder Media als Staff gelten und
der Guard sich abschaltet. Der Staff-Scan zeigt oben, welche Familien verworfen
wurden (`Als Deko verworfen (zu häufig für einen Rang): …`).


**Eigene Glyphen (`krypton_staffglyphs.txt`).** Viele Server benutzen
Resourcepack-Symbole aus der **Private Use Area** (U+E000–U+F8FF), die in keiner
Unicode-Sternliste stehen. Über die Datei lässt sich jeder Codepoint nachtragen
(`U+E001` oder `E001` pro Zeile) — ohne Neubau. `isStarGlyph()` prüft die
eingebaute Liste **und** diese Datei.

**Erkannte Stern-Glyphen:** 32 Unicode-Sterne (`★ ☆ ⚝ ✦ ✧ ✩ ✪ ✫ ✬ ✭ ✮ ✯ ✰ ✱ ✲ ✳
✴ ✵ ✶ ✷ ✸ ✹ ✺ ✻ ✼ ✽ ❂ ❃ ❉ ❊ ❋ ⭐`), im Quelltext als Unicode-Escapes hinterlegt,
damit die Erkennung nicht von der Dateikodierung abhängt.
**ASCII `*` zählt bewusst nicht** — das kommt in Deko-Prefixes viel zu häufig
vor und würde False Positives erzeugen.

**2. Klartext-Fallback** (`getTextRank`, abschaltbar über `staffTextRanks`).
Unverändert die alte Logik: Tab-Name + Entity-Name + Team-Name/-Displayname in
einen String, dann case-insensitiv
`owner` → `sradmin`/`sr.admin` → `admin` → `srmod`/`sr.mod` →
`moderator`/`[mod`/`mod]`/`" mod "` → `srhelper`/`sr.helper` → `helper`.

#### Warum das so gebaut ist

Für den Guard zählt nur **Staff ja/nein** — die Farbe bestimmt ausschließlich
das angezeigte Label. Beide Fehlerrichtungen sind teuer:

- **False Positive** (normaler Spieler wird als Staff gelesen) → Guard schaltet
  sich ab und schützt nicht mehr.
- **False Negative** (Staff wird übersehen) → Guard baut vor Staff ab und loggt
  aus.

Deshalb ist nichts hart verdrahtet: die vier Farbfamilien und der
Klartext-Fallback sind einzeln schaltbar (`krypton_staffdetect.txt`), und der
**Staff-Scan-Screen** (§6.3) zeigt live, was der Server wirklich schickt —
Rohtext mit sichtbar gemachten `§`-Codes, jeden gefundenen Stern mit Codepoint
und Hex-Farbe, die abgeleitete Familie und ob sie als Staff zählt.
**Vor dem Scharfschalten auf einem neuen Server einmal dort gegenprüfen.**

**0. Media-Ausschluss (`NON_STAFF_KEYWORDS`).** Steht `media`, `youtube`, `streamer`,
`creator`, `partner` o. ä. im Tab-/Team-Text und **kein** echtes Staff-Wort, gilt der
Spieler als normaler Spieler — **auch mit farbigem Stern**. Media kann nicht bannen;
vor einem YouTuber soll der Guard die Spawner sichern, nicht still halten. Gilt für
Scan **und** Guard (`getPlayerRank()` → `rankOf()`).

`isStaffNearby()` prüft alle Spieler in Distanz² ≤ 1600 (40 Blöcke), ohne
Whitelist-Einträge.

---

### 5.11 Session Fix (`isSessionFixActive`, `sessionFixMode`)

Fängt kaputte Verbindungsabbrüche ab und verbindet kontrolliert neu.

**Warum das nicht mit einer einzigen Textsuche geht:** "Invalid Session" ist nur
*eine* von vielen Formulierungen. Auf Servern mit vielen Plugins (DonutSMP &
Co.) kommt genauso oft ein roher Java-/Netty-Stacktrace mit langen Zahlen
zurück, z. B.

```
Internal Exception: io.netty.handler.codec.DecoderException:
java.lang.IndexOutOfBoundsException: Index 1146 out of bounds for length 0
```

oder `An internal error occurred in your connection. Error ID: 8347261993844`.
Deshalb wird der Grund **kategorisiert** (`classifyDisconnect`), und wie breit
reagiert wird, steuert `sessionFixMode`.

#### Kategorien (erster Treffer gewinnt, Reihenfolge ist Absicht)

| # | Name | Muster (Auszug) |
|---|---|---|
| **3** | `KEIN-REJOIN` | `banned`, `gebannt`, `tempban`, `kicked by`, `gekickt von`, `outdated client`, `unsupported version`, `no permission` — **bewusst nicht** `whitelist` / `server is full`: beides ist vorübergehend (Wartung, voller Server) und soll endlos weiterprobiert werden → Kategorie 0, normaler Auto-Reconnect |
| **1** | `SESSION` | `invalid session`, `failed to verify username`, `unverified_username`, `authentication servers`, `not authenticated`, `bad login`, `session expired`, `already logged in`, **`restarting your game` / `restart your launcher`** |
| **2** | `TECHNIK` | `internal exception`, `internal error`, `error id`, `io.netty`, `java.lang`, `java.io`, `java.net`, `exception`, `timed out`, `timeout`, `connection reset`, `forcibly closed`, `broken pipe`, `readerindex`, `out of bounds`, `keepalive`, `bad packet`, `decoder`, `nullpointer`, `socket`, `at net.minecraft` |
| **0** | `SONSTIGES` | alles übrige |

Der Zusatz *"(Try restarting your game and the launcher)"* hängt bei Mojang an
**jeder** Session-/Auth-Meldung dran, egal wie der Rest formuliert ist — deshalb
ist er als eigenes Muster drin. Bewusst **nicht** nur `restart`: ein
"Server is restarting" darf nicht als Session-Fehler gelten (per Test abgesichert).

Kategorie 3 wird **zuerst** geprüft: ein Ban-Text, in dem zufällig auch
`exception` steht, darf niemals einen Reconnect auslösen.

#### Modus (`sessionFixMode`, Modul 23 SESSION MODE, Klick schaltet weiter)

| Modus | reagiert auf | Gedacht für |
|---|---|---|
| `STRIKT` (0) | nur Kategorie 1 | konservativ, nur echte Session-Fehler |
| `TECHNIK` (1, **Default**) | Kategorie 1 + 2 | Server, die auch Netty-/Exception-Kicks werfen |
| `ALLES` (2) | Kategorie 0 + 1 + 2 | Server mit völlig unvorhersehbaren Meldungen |

Kategorie 3 ist in **jedem** Modus ausgeschlossen.

#### Ablauf

Reconnect nach **5 s** (plus dem üblichen ±15-Tick-Jitter).
Versuchsgrenze: **3** bei Kategorie 1, **5** bei allen anderen — ein wirklich
toter Access-Token erholt sich nicht, ein Netzwerkfehler schon.
`sessionFixAttempts` wird nach 60 Ticks stabiler Verbindung zurückgesetzt.
Ist die Grenze erreicht, übernimmt der **normale Auto Reconnect** (falls aktiv)
und probiert weiter — mit `Infinite` also endlos. Genau das braucht man beim
AFK-Stehen. Der Hinweis bleibt sichtbar
(`§cSession weiter ungültig – ggf. Client neu starten (Re-Auth).`).
Ist Auto Reconnect aus, wird gestoppt und
`§cSession dauerhaft ungültig – Client neu starten (Re-Auth nötig).` angezeigt.

#### Re-Auth-Fenster (Zusammenspiel mit Re-Auth-Mods)

Mods wie [Auto Reauth](https://modrinth.com/mod/auto-reauth) erneuern die Session
genau dann, wenn der **Multiplayer-Screen** geöffnet wird. Krypton verbindet nach
einem Kick aber direkt über `ConnectScreen` — der Check würde also nie laufen.
Deshalb wird bei **Kategorie 1 (SESSION)** vor jedem Verbindungsversuch der
Multiplayer-Screen gezeigt, bevor `doReconnect()` läuft
(`pendingSessionReconnect`). Das Fenster wächst mit der Versuchszahl:
`REAUTH_WINDOW_TICKS` (100 Ticks = 5 s) × Versuch, gedeckelt auf
`REAUTH_WINDOW_MAX` (300 Ticks = 15 s) — ein Token-Refresh über das
Microsoft-Login kann je nach Verbindung ein paar Sekunden dauern.
Bei Kategorie 2 (TECHNIK) passiert das **nicht** — dort ist der Token ja in
Ordnung. Ohne installierten Re-Auth-Mod kostet es nur diese Sekunden.

**Alles läuft ohne Klick.** Countdown, Re-Auth-Fenster und Reconnect ticken von
selbst weiter; der Knopf im `KryptonReconnectScreen` ist nur die Abkürzung. Das
ist Absicht — der Client soll aus dem AFK-Betrieb allein zurückkommen.

`doReconnect()` ist der **einzige** Verbindungspfad und steigt bei gesetztem
`wasSafetyLogout` sofort aus — auch das Re-Auth-Fenster kann die Sperre nicht
umgehen.

**Hart gesperrt durch `wasSafetyLogout`** (§5.4.4) — das ist der ganze Grund für
das eigene Modul: ein generischer "Reconnect bei Fehler" würde den Client nach
dem Notfall-Logout wieder auf den Server holen, während der Gegner noch bei den
abgebauten Spawnern steht.

**`disconnectHandled`** sorgt dafür, dass der Handler pro Trennung genau einmal
läuft. Ohne das Flag würde er jeden Tick erneut loggen und hochzählen, solange
der Vanilla-`DisconnectedScreen` offen bleibt.

#### Disconnect-Log

Jede Trennung landet in `disconnectHistory` (max. 20 Einträge, persistiert in
`krypton_disconnects.txt`):

```
§8[14:03:11] §6TECHNIK §8→ §fSession-Fix 1/5 §8| §7Internal Exception: io.netty...
```

Uhrzeit, erkannte Kategorie, tatsächlich ausgeführte Aktion und der Rohtext
(`§` → `&` sichtbar gemacht, auf 110 Zeichen gekürzt). Anzeige über Modul 22
**DISCONNECT LOG**, dort lässt sich der Modus auch direkt umschalten.
Das ist die Grundlage, um auf einem konkreten Server den richtigen Modus zu
wählen — statt zu raten, welche Meldungen überhaupt vorkommen.

#### Was NICHT geht — und warum

Ein **echtes Re-Auth** ist aus einem Fabric-Mod heraus nicht möglich: dafür
bräuchte man den Microsoft-Refresh-Token, und den hat nur der Launcher.
Clients wie NoRisk können das genau deshalb, weil sie *Launcher plus Client*
sind. Mods, die es können, lassen sich den Token vom User geben und speichern
ihn selbst — siehe
[Auth Me](https://modrinth.com/mod/auth-me),
[Auto Reauth](https://modrinth.com/mod/auto-reauth),
[ReAuth](https://www.curseforge.com/minecraft/mc-mods/reauth-fabric).

Krypton macht deshalb bewusst nur den Teil, der ohne Zugangsdaten sauber geht:
den Abbruch erkennen, richtig einordnen und kontrolliert neu verbinden. Das
behebt den häufigen Fall (Aussetzer des Mojang-Session-Servers, "already logged
in", Rate-Limit, Netty-/Paketfehler). Ist der Access-Token wirklich tot, hilft
nur ein Client-Neustart — und genau das sagt die Meldung dann auch.

#### Testen

Modul **SESSION TEST** (ClickGUI, Spalte MISC) trennt die Verbindung mit dem
Grund `Invalid session (Try restarting your game and the launcher)`. Damit
lässt sich der komplette Pfad ohne echten Serverkick prüfen — inklusive der
Notfall-Logout-Sperre, die dabei ganz normal greift. Siehe §13.

---

## 6. UI

### 6.1 HUD (`HudRenderCallback`)

ArrayList unten links, Skalierung `0.5f`, Zeilenhöhe 11 px, 10 px Abstand zum
unteren Rand. Header `§6Krypton`. Wird komplett ausgeblendet, wenn kein Modul
aktiv ist. Angezeigte Einträge:

```
Finder: §4<n>                       Player ESP: §bON      Tracers: §bON
Freecam: §aON                       Fullbright: §eON
Guard: §aBEREIT §8[Menüs gesperrt]   ← §aBEREIT §e(ohne Silk Touch) | §cNICHT BEREIT – <Grund> | §4EINSATZ | §cKEIN SPAWNER IN REICHWEITE
Spawner ESP: §dON     Reconnect: §aON
Session Fix: §aON
§4Rejoin gesperrt (Notfall-Logout)   ← nur wenn wasSafetyLogout gesetzt ist
```

Der Zusatz `[Menüs gesperrt]` steht bewusst dort: sonst wundert man sich, warum
ESC nichts tut (§5.4.3).

### 6.2 ClickGUI (`ClickGuiScreen`)

Öffnet mit `openGuiKey` (Default `RIGHT_SHIFT`), nur wenn kein anderer Screen
offen ist. `shouldPause()` gibt `false` zurück — das Spiel läuft weiter.

**Layout:** 4 Spalten am oberen Bildschirmrand, Spaltenbreite
`min(185, (width - 3) / 4)`, Header 20 px, Zeilenhöhe 16 px, 1 px Spaltenabstand.

**Kategorien und Module:**

| Spalte | Kategorie | Icon | Module (Indizes) |
|---|---|---|---|
| 0 | MISC | graues Plus `0xFF8B8FA8` | 0 Freecam, 10 Freecam Key, 3 Disable On Dmg, 19 Staff Scan, 22 Disconnect Log, 20 Session Test, 21 Rejoin Lock |
| 1 | BASEFINDING | cyan Diamant `0xFF44BBFF` | 4 Bedrock Finder, 12 Min Hole Size |
| 2 | RENDER | lila Ring `0xFFAA55FF` | 5 Player ESP, 16 Tracers, 6 Spawner ESP, 7 Fullbright |
| 3 | CLIENT | türkiser Stern `0xFF44CCFF` | 1 Auto Spawner, 2 Auto Reconnect, 18 Session Fix, 23 Session Mode, 11 Reconnect Set, 17 Whitelist, 8 Player Logs, 9 Logout Logs |

**Modul-Indexliste (`MNAME`, 24 Einträge):**
```
0  FREECAM          6  SPAWNER ESP     12 MIN HOLE SIZE   18 SESSION FIX
1  AUTO SPAWNER     7  FULLBRIGHT      13 (entfernt)      19 STAFF SCAN
2  AUTO RECONNECT   8  PLAYER LOGS     14 (entfernt)      20 SESSION TEST
3  DISABLE ON DMG   9  LOGOUT LOGS     15 (entfernt)      21 REJOIN LOCK
4  BEDROCK FINDER   10 FREECAM KEY     16 TRACERS         22 DISCONNECT LOG
5  PLAYER ESP       11 RECONNECT SET   17 WHITELIST       23 SESSION MODE
```
Toggle-Module sind `mi < 8 || mi == 16 || mi == 18 || mi == 21` —
sie bekommen einen animierten Pill-Toggle (16×8 px, Thumb fährt 8 px). Alle
anderen zeigen `>`, ein `[KEY]`-Label oder einen Inline-Zahlenwert.

**Die neuen Module:**

| # | Name | Typ | Wirkung |
|---|---|---|---|
| 18 | SESSION FIX | Toggle | §5.11, persistiert in `krypton_cheats.txt` Zeile 6 |
| 19 | STAFF SCAN | Screen | Rang-Diagnose, §6.3 |
| 20 | SESSION TEST | Aktion | trennt mit `Invalid session (Try restarting your game and the launcher)` — Testpfad für §5.11 |
| 21 | REJOIN LOCK | Toggle | zeigt/setzt `wasSafetyLogout` (`setSafetyLogout`). Der einzige Weg, die Notfall-Logout-Sperre ohne manuellen Join wieder zu lösen |
| 22 | DISCONNECT LOG | Screen | letzte 20 Trenngründe mit Kategorie und Aktion, §6.3 |
| 23 | SESSION MODE | Cycle | schaltet `sessionFixMode` STRIKT → TECHNIK → ALLES, zeigt `[TECHNIK]` rechts in der Zeile |

**Animationen:** `openAnim` (0→1, `+0.10` pro Frame) steuert Overlay-Alpha und
ein Slide-in von −24 px. `dotAnim[]` (Größe **24**) interpoliert die Toggle-Position mit
`+= (target - current) * 0.22`. Die Indizes 0–7 laufen über eine Schleife,
16/18/21 werden einzeln nachgezogen.

**Input:** Maus-Klicks werden **nicht** über `mouseClicked()` verarbeitet,
sondern per `GLFW.glfwGetMouseButton()`-Polling im `render()` mit
`wasMouseDown`-Edge-Detection (API-versionsunabhängig). Rechtsklick ruft
`modRightClick()` auf — derzeit ohne Funktion.

**Inline-Eingabemodi** in `keyPressed(KeyInput)`:
- `isRebindingFreecam` — nächster Tastendruck wird
  gebunden (`setKeyBindingBoundKey()` + speichern)
- `isEnteringHoleSize` — max. 3 Ziffern, Enter/ESC übernimmt (ESC schließt zusätzlich die GUI)


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
| `DisconnectLogScreen` | Letzte 20 Trenngründe (`disconnectHistory`) mit Uhrzeit, Kategorie und ausgeführter Aktion; skaliert auf Breite **und** Höhe. Buttons: "Modus" (schaltet `sessionFixMode` weiter), "Log löschen", "Zurück". `shouldPause()` = `false`. |
| `StaffScanScreen` | **Rang-Diagnose, zwei Ansichten.** *Glyphen:* zählt jedes Sonderzeichen aus allen Tab-/Team-Prefixes über die **komplette Tab-Liste** (nicht nur geladene Entities), sortiert nach Häufigkeit, mit Codepoint, Hex-Farbe, Familie, Trefferzahl/Prozent und Urteil. Ein Symbol bei ≥50 % ist markiert als `<- DEKO!`, eines bei ≤2 Spielern als `<- verdächtig selten` (plus deren Namen). Das ist der Weg, den echten Rang-Marker zu finden. *Spieler:* Listet jeden Spieler der Tab-Liste (geladene zuerst, dann nach Distanz; nicht geladene mit `nur Tab`): Name, Distanz, Whitelist-Marker, Urteil (`STAFF: <Rang>` / `kein Staff`) und darunter je eine Zeile pro Quelle (Tab, Name, Team-Prefix, Team-Suffix) mit dem Rohtext (`§` → `&` sichtbar gemacht) und jedem gefundenen Stern als `U+XXXX #RRGGBB <Familie> [STAFF]/[egal]`. 15 Zeilen pro Seite, `<`/`>` blättert, "Aktualisieren" scannt neu. Kopfzeile nennt zusätzlich die als Deko verworfenen Farbfamilien mit Trefferzahl. Fünf Schalter (Grün/Blau/Lila/Andere/Text) ändern die Erkennung sofort und speichern nach `krypton_staffdetect.txt`. `shouldPause()` = `false`. |

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
| `krypton_cheats.txt` | 7 Zeilen: BedrockFinder, PlayerESP, AutoSpawner, SpawnerESP, Tracers, **SessionFix**, **SessionFixMode** (0–2) | `loadCheatStates` / `saveCheatStates` |
| `krypton_reconnect.txt` | Z1 aktiv, Z2 infinite, Z3 Delays (CSV) | `loadReconnect` / `saveReconnect` |
| `krypton_discord_id.txt` | letzte verarbeitete Discord-Message-ID | `loadDiscordConfig` / `saveLastDiscordMessageId` |
| `krypton_safelogout.txt` | `wasSafetyLogout` (`true`/`false`) — Notfall-Logout-Sperre | `loadSafetyLogout` / `setSafetyLogout` |
| `krypton_staffglyphs.txt` | zusätzliche Stern-Codepoints, einer pro Zeile (`U+E001`) | `loadStaffGlyphs` / `saveStaffGlyphs` |
| `krypton_staffdetect.txt` | `green=`/`blue=`/`purple=`/`other=`/`text=` (je `true`/`false`) | `loadStaffDetect` / `saveStaffDetect` |
| `krypton_disconnects.txt` | letzte 20 Trenngründe, eine Zeile pro Eintrag | `loadDisconnectLog` / `saveDisconnectLog` |

**Nicht persistiert:** `isFreecamActive`, `minHoleSize`,
`spawnerScriptActive`, `guardEngaged`, `sneakSuppressTicks`.

`saveCheatStates()` wird bei `CLIENT_STOPPING` **und** beim Umschalten von
Session Fix aufgerufen; alle anderen Saves laufen sofort bei der Änderung.
`setSafetyLogout()` ist der **einzige** Schreibpfad für `wasSafetyLogout` —
Wert und Datei können damit nicht auseinanderlaufen.

**Ältere `krypton_cheats.txt` bleiben lesbar:** fehlende Zeilen lassen
`isSessionFixActive` (`false`) und `sessionFixMode` (`1` = TECHNIK) auf ihren
Defaults. `sessionFixMode` wird zusätzlich auf 0–2 validiert.

---

## 8. Mixins

In `krypton.mixins.json` unter `"client"` registriert,
`compatibilityLevel: JAVA_21`, `defaultRequire: 1`.

> **Achtung:** `ExampleMixin` ist **nicht** registriert — die Datei liegt nur
> noch im Quellbaum herum und wird nie geladen. Sie steht unten nur der
> Vollständigkeit halber in der Tabelle.

| Mixin | Ziel | Injection | Zweck |
|---|---|---|---|
| `CameraMixin` | `Camera` | `update` @TAIL | Setzt in Freecam Kamera-Position und -Rotation. Interpoliert `prev*` → aktuell mit `getTickProgress(true)` (sonst wirkt die Kamera wie 20 FPS). Friert zusätzlich jeden **Frame** Yaw/Pitch/HeadYaw/BodyYaw des Spielers ein – **inklusive der `last*`-Interpolationsfelder** (sonst lerpt der Renderer zwischen altem und neuem Wert und der eigene Körper wackelt sichtbar; rein clientseitig, der Server sieht konstante Rotation) und überschreibt damit den Vanilla-Maus-Handler vollständig. |
| `EntityMixin` | `Entity` | `changeLookDirection` @HEAD, cancellable | Leitet in Freecam die Mausbewegung auf `freecamYaw/freecamPitch` um (`delta * 0.15`, Pitch auf ±90° geklemmt) und cancelt den Vanilla-Pfad. Nur für `MinecraftClient.getInstance().player`. |
| `GameRendererMixin` | `GameRenderer` | `renderHand` @HEAD, cancellable | Blendet die Hand/das Item in Freecam aus. Parameter bewusst weggelassen → robust gegen Mapping-Änderungen. |
| `KeyboardInputMixin` | `KeyboardInput` | `tick` @TAIL | Überschreibt in Freecam `input.playerInput` mit einem leeren `PlayerInput` **und** `input.movementVector` mit `(0,0)` (per `@Shadow`, das Feld ist `protected`) — seit 1.21.2 entsteht die Bewegung aus dem Vektor, nicht aus dem Record; ohne das zappelte der Körper mit WASD gegen die Wand → der Spielerkörper bewegt sich nicht mit. **Ausnahme Sneak:** läuft der Dauer-Sneak des Guards (`shouldForceSneak()`), bleibt die Sneak-Komponente `true` — der Körper duckt sich weiter, bewegt sich aber nicht. Komponenten-Reihenfolge von `PlayerInput`: `forward, backward, left, right, jump, sneak, sprint`. |
| `MinecraftClientMixin` | `MinecraftClient` | `handleBlockBreaking` @HEAD, cancellable | Unterdrückt das Vanilla-Mining, wenn **Freecam** *oder* **`Krypton.guardIsMining()`** aktiv ist. **Grund:** Vanilla nutzt `crosshairTarget`, unsere beiden eigenen Abbau-Pfade nutzen einen eigenen Raycast. Beide zusammen cancellen sich gegenseitig → Progress startet endlos neu → Server-Desync und zurückbuggende Blöcke. Siehe §5.4.1. |
| `MinecraftClientMixin` | `MinecraftClient` | `openGameMenu(boolean)` @HEAD, cancellable | Blockt das Pausenmenü bei `Krypton.guardLockActive()`. Das ist der **einzige** Vanilla-Einstieg ins `GameMenuScreen` — deckt ESC *und* "Pause on Lost Focus" beim Fensterwechsel ab. |
| `MinecraftClientMixin` | `MinecraftClient` | `setScreen(Screen)` @HEAD, cancellable | Zweite Verteidigungslinie: cancelt, wenn `Krypton.isScreenBlocked(screen)` — unabhängig davon, über welchen Codepfad der Screen geöffnet wird. Blocklist statt Allowlist, siehe §5.4.3. |
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
Wird in Auto Spawner (State 2 + 5) und Spawner Script
(State 2 + 3) verwendet.

### 9.1b Vanilla-Gates umgehen statt bekämpfen
Wenn Vanilla eine Aktion an einen UI-Zustand koppelt (offener Screen, Cursor-Lock,
Fensterfokus), ist der stabile Weg: **den Vanilla-Pfad canceln und die Aktion
selbst ausführen**, nicht beides parallel laufen lassen. Beide Pfade gleichzeitig
enden sonst in `cancelBlockBreaking()`-Schleifen und Server-Desync.
Angewandt bei Freecam-Abbau und Guard-Abbau (§5.4.1), jeweils zusammen mit einem
eigenen Raycast, damit die verschickten Pakete serverkonform bleiben.

### 9.1c Farberkennung über Hue statt fester RGB-Werte
Server verwenden für dieselbe "Farbe" mal die 16 Vanilla-Codes, mal beliebige
Hex-Werte. `starColorFamily()` rechnet deshalb nach HSV um und ordnet über den
Farbton zu, mit einer Sättigungsschwelle gegen Weiß/Grau. Siehe §5.10.

### 9.2 Reflection
Wird an vier Stellen eingesetzt, um Mapping-Änderungen zu überleben:
- `getBoundKeyCode()` / `setKeyBindingBoundKey()` — findet das **nicht-finale**
  `InputUtil.Key`-Feld in `KeyBinding` (`defaultKey` ist immer `final`,
  `boundKey` nicht) und ruft danach `KeyBinding.updateKeysByCode()`
- `setGamePerspective()` — greift auf das private `perspective`-Feld in
  `GameOptions` zu und sucht dessen `setValue(Object)`-Methode.
  **Hinweis: aktuell definiert, aber nicht aufgerufen** (der `PerspectiveMixin`
  hat diesen Ansatz ersetzt)
- Auslesen des Disconnect-Grunds: Feld vom Typ `DisconnectionInfo` in `DisconnectedScreen` → `reason()`; Fallback erstes **nicht-statisches** `Text`-Feld

### 9.3 GUI-Slot-Erkennung
Mit dem früheren Farm-Bot entfernt. Das Discord-Spawner-Script (State 6) erkennt den
TPA-Bestätigungs-Button noch direkt über den Item-Typ (Lime/Green Stained Glass Pane);
Slot-Konvention weiterhin: `handler.slots.size() - 36` = Anzahl der GUI-Slots.

### 9.4 Anti-Pattern-Maßnahmen (Übersicht)
- Randomisierte Delays zwischen allen State-Übergängen
- Randomisierte Zielpunkte innerhalb des Ziel-Blocks
- Brownian-Motion-Drift statt periodischer Muster
- Reconnect-Delays mit ±15-Tick-Jitter
- Freecam lässt Velocity/Knockback unangetastet und interagiert nur in Körper-Blickrichtung
- Automatisches Abschalten bei erkanntem Staff (Guard)

### 9.4b Sneak und Block-Interaktion schließen sich aus
`ServerPlayerInteractionManager.interactBlock()` prüft
`player.shouldCancelInteraction()` (= `isSneaking()`) und ruft dann statt
`BlockState.onUse()` das `useOnBlock()` des Items auf. Ein sneakender Spieler
bekommt also **keine** Block-GUI — und setzt mit einem Block in der Hand
stattdessen einen Block. Jeder automatisierte Rechtsklick auf einen Block muss
den Dauer-Sneak deshalb vorher abmelden (`suppressSneak()`) und warten, bis
`isSneakReleased()` true ist. Siehe §5.4.2.

### 9.5 Thread-Sicherheit
- `playerHistory` und `stableHoles` sind `CopyOnWriteArrayList`
- `lastDiscordMessageId`, `spawnerScriptTrigger`, `spawnerScriptActive`
  sind `volatile` (Discord-Threads)
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
- `Krypton.java` ist mit ~3850 Zeilen ein Monolith aus `static`-State.
  Eine Aufteilung in Module/Manager wäre der naheliegende nächste Refactor-Schritt.
- Discord-JSON wird per `indexOf`/`substring` geparst — bricht, sobald das
  Antwortformat oder die Feldreihenfolge sich ändert.
- `setGamePerspective()` ist toter Code seit dem `PerspectiveMixin`.
- `modRightClick()` ist leer.
- `ExampleMixin` ist ein leerer Template-Rest und in `krypton.mixins.json`
  gar nicht registriert — die Datei kann ersatzlos weg.
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
  die Range ist weiter, als der Code tatsächlich unterstützt. Dasselbe gilt für
  `depends.fabricloader` (`>=0.18.1` vs. `loader_version=0.19.2`).

### Build-Umgebung
- `gradlew` hatte im Git kein Execute-Bit (`100644`). Die CI setzt es selbst
  (`chmod +x ./gradlew`), lokal schlug `./gradlew` aber mit
  *Permission denied* fehl. Ist jetzt auf `100755` korrigiert.
- Ein Build braucht Netzzugriff auf **`maven.fabricmc.net`** (Loom-Plugin,
  Yarn, Loader, Fabric API). In abgeschotteten Umgebungen ohne diesen Host ist
  kein lokaler Build möglich — dann bleibt nur die GitHub-Actions-CI.
  Zum Gegenprüfen von Mappings ohne Build helfen die Yarn-Mapping-Dateien
  direkt aus dem Repo, z. B.
  `https://raw.githubusercontent.com/FabricMC/yarn/1.21.11/mappings/net/minecraft/client/MinecraftClient.mapping`.

### Versions-Fallstricke (1.21.11)
- **Linien-Rendering** braucht pro Vertex zwingend `.lineWidth(...)`, sonst
  `Missing elements in vertex: LineWidth` (siehe `drawTracerLine`).
- `Vertex`-Reihenfolge ist strikt: `vertex → color → normal → lineWidth`.
- `MatrixStack` in `DrawContext` nutzt `pushMatrix()`/`popMatrix()` (2D),
  während der Welt-`MatrixStack` `push()`/`pop()` verwendet.
- `KeyInput`-Record statt `int keyCode, int scanCode, int modifiers` in
  `Screen.keyPressed`.
- `PlayerInventory.setSelectedSlot(int)` statt direktem Feldzugriff.
- `PlayerInput` ist ein **Record** mit sieben `boolean`-Komponenten in der
  Reihenfolge `forward, backward, left, right, jump, sneak, sprint`. In den
  Yarn-Mappings ist nur `sneak` benannt, die übrigen Komponenten behalten
  Intermediary-Namen — beim Konstruieren also auf die Position achten.
- `Input` hat seit 1.21.2 **zwei** Eingabefelder: `playerInput` (Record, Tastenzustände für
  Sneak/Jump/Sprint-Entscheidungen) und `movementVector` (`Vec2f`, die eigentliche
  Bewegung). Wer Bewegung unterdrücken will, muss **beide** nullen.
- `InputUtil.isKeyPressed(Window, int)` nimmt ein `Window`, **keinen** `long`
  Fenster-Handle.
- `Text.visit(StringVisitable.StyledVisitor<T>, Style)` liefert den **gemergten**
  Style pro Textabschnitt — der richtige Weg, um an Farben zu kommen, statt
  `getString()` zu parsen (dabei gehen Component-Farben verloren).

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
  `dotAnim` ist auf **22** Einträge dimensioniert; bei Index ≥ 22 muss das
  Array vergrößert werden. Toggle-Module brauchen zusätzlich einen Eintrag in
  der `isToggle`-Bedingung **und** eine eigene `dotAnim[i] += …`-Zeile
  (die Schleife deckt nur 0–7 ab).
- **Renderer** immer mit NaN/Infinity-Guards und `ArrayList`-Snapshots arbeiten.
- **Mapping-Unsicherheit:** Bei unklaren Yarn-Namen ist `javap` gegen das
  gemergte Minecraft-Jar im Loom-Cache der schnellste Weg (siehe die
  entsprechenden Einträge in `.claude/settings.local.json`).
- **Vor dem Commit:** `./gradlew build` muss durchlaufen; die CI baut mit JDK 25
  gegen Release-Target 21. Geht das lokal nicht (kein Zugriff auf
  `maven.fabricmc.net`, siehe §11), zumindest `javac` über die Quellen laufen
  lassen — Syntaxfehler fallen dabei auf, auch wenn alle MC-Symbole fehlen —
  und danach den CI-Build auf GitHub abwarten.
- **Guard-Änderungen sind sicherheitskritisch.** Beim Anfassen von §5.4 immer
  mitdenken: Kann noch abgebaut werden, wenn ein Screen offen ist / das Fenster
  keinen Fokus hat? Kann sich der User den Guard noch ausschalten? Bleibt der
  Notfall-Logout-Rejoin gesperrt? Und wird der Auto-Sneak für jeden
  Rechtsklick auf einen Block abgemeldet (§9.4b)?
- **Staff-Erkennung nie "auf Verdacht" ändern.** Erst den Staff-Scan-Screen
  (§6.3) am echten Server aufmachen und sehen, was ankommt — beide
  Fehlerrichtungen sind teuer (§5.10).

---

## 13. Testen der sicherheitskritischen Pfade

Die Guard- und Reconnect-Logik lässt sich nicht sinnvoll durch Zuschauen prüfen.
Diese vier Tests decken die kritischen Pfade ab.

### 13.1 Menü-Sperre und Abbau-Garantie
1. Guard einschalten (ClickGUI → CLIENT → AUTO SPAWNER). HUD muss
   `Guard: ON [Menüs gesperrt]` zeigen.
2. **ESC drücken** → es darf sich nichts öffnen.
3. **T / Chat-Taste, E (Inventar), F3+…** → nichts öffnet sich.
4. **Alt-Tab** (bzw. Remote-Desktop trennen) → beim Zurückkommen darf **kein**
   Pausenmenü offen sein und der Mauszeiger muss wieder gegriffen sein.
5. ClickGUI-Taste (Default `RIGHT_SHIFT`) muss weiterhin funktionieren —
   das ist der Not-Aus.

### 13.2 Auto-Sneak
1. Guard einschalten. Der Spieler muss **sofort und dauerhaft** ducken
   (in F5-Perspektive gut zu sehen).
2. Sneak-Taste drücken **und wieder loslassen** → der Spieler bleibt geduckt,
   ohne dass es einmal kurz aufspringt.
3. Guard ausschalten, **währenddessen Shift gedrückt halten** → der Spieler
   bleibt geduckt (echter Tastenzustand wurde übernommen). Loslassen → steht auf.
4. Guard aus **ohne** Shift → der Spieler steht sofort auf (kein hängender Key).
5. Mit Guard an einen Block rechtsklicken (z. B. in der Freecam) → die GUI muss
   **trotzdem aufgehen** (§9.4b). Geht sie nicht auf, greift die
   Sneak-Unterdrückung nicht.

### 13.3 Session-Fix
1. Modul **SESSION FIX** einschalten (ClickGUI → CLIENT). Modus steht auf
   `TECHNIK` (Modul **SESSION MODE** daneben zeigt `[TECHNIK]`).
2. Auf einem Server einloggen, dann ClickGUI → MISC → **SESSION TEST**.
   Der Client trennt mit `Invalid session (Try restarting your game and the
   launcher)`.
3. Erwartet: eigener Reconnect-Screen mit
   `§eSession-Fix (SESSION) – Versuch 1/3`, Countdown ~5 s, dann automatischer
   Rejoin.
4. Dreimal wiederholen, **ohne** dass die Verbindung 60 Ticks (3 s) hält → beim
   vierten Mal muss `Session dauerhaft ungültig – Client neu starten` stehen
   und **kein** automatischer Reconnect mehr kommen.
5. Gegenprobe ohne Modul: SESSION FIX aus → SESSION TEST → es greift nur der
   normale Auto Reconnect (bzw. gar nichts, wenn der aus ist).

**Echte Server-Kicks prüfen (der wichtigere Teil).** Der Testknopf schickt immer
denselben Text; die echten Meldungen auf einem Plugin-Server sehen anders aus.
Dafür gibt es das **DISCONNECT LOG** (ClickGUI → MISC):
1. Normal spielen. Bei jedem Rausflug wird eine Zeile geschrieben:
   `[Uhrzeit] KATEGORIE → Aktion | Rohtext`.
2. Nach ein paar Tagen dort nachsehen:
   - Steht bei den Bug-Kicks `SESSION` oder `TECHNIK`? → passt, Modus `TECHNIK`
     genügt.
   - Steht dort `SONSTIGES`? → der Server verwendet eine Formulierung, die noch
     in keiner Musterliste steht. Entweder Modus auf `ALLES` stellen, oder den
     Rohtext aus dem Log nehmen und ein passendes Muster in
     `TECHNICAL_KICK_PATTERNS` nachtragen.
   - Steht bei einem echten Ban/Kick `KEIN-REJOIN`? → gut. Steht dort etwas
     anderes, gehört ein Muster in `NEVER_RECONNECT_PATTERNS`, **bevor** man
     Modus `ALLES` benutzt.
3. Der Modus lässt sich direkt im Disconnect-Log-Screen umschalten.

Wer einen eigenen Testserver hat, kann Kick-Texte auch gezielt durchspielen:
`/kick <Name> Invalid session (Try restarting your game and the launcher)`,
`/kick <Name> Internal Exception: io.netty.handler.timeout.ReadTimeoutException`,
`/ban <Name> test` (muss `KEIN-REJOIN` ergeben und **darf nicht** rejoinen).

### 13.4 Notfall-Logout darf NIE rejoinen — der wichtigste Test

**Schnelltest ohne zweiten Account** (prüft nur die Sperre, nicht den Abbau):
1. Auf einem Server einloggen.
2. ClickGUI → MISC → **REJOIN LOCK** einschalten. HUD zeigt
   `§4Rejoin gesperrt (Notfall-Logout)`, `krypton_safelogout.txt` enthält `true`.
3. AUTO RECONNECT **und** SESSION FIX einschalten (beide sollen ja geblockt werden).
4. ClickGUI → MISC → **SESSION TEST**.
5. Erwartet: `§4Notfall-Logout aktiv – Auto-Reconnect gesperrt`,
   Reconnect-Button **ausgegraut**, **kein** automatischer Rejoin — auch nach
   Minuten nicht.
6. Client komplett neu starten → `krypton_safelogout.txt` steht immer noch auf
   `true`, HUD zeigt die Sperre weiter. Ein erneuter Disconnect rejoint nicht.
7. Manuell auf den Server verbinden und **3 Sekunden dort bleiben** → die Sperre
   fällt automatisch, HUD-Zeile verschwindet, Datei steht auf `false`.

**Vollständiger Test mit zweitem Account:**
1. Zweiten Account (nicht in der Whitelist, kein Staff-Stern) besorgen.
2. Guard einschalten, **höchstens 4,5 Blöcke** neben einem eigenen Spawner mit freier Sicht stehen (HUD darf **nicht** `KEIN SPAWNER IN REICHWEITE` zeigen), Spitzhacke mit
   Behutsamkeit in der Hotbar.
3. Mit dem zweiten Account auf **unter 40 Blöcke** herangehen.
4. Erwartet: Guard dreht sich auf den Spawner, baut ihn ab, und trennt danach
   mit `§aAlle Spawner im Umkreis gesichert! §4Notfall-Logout.`
5. **Danach darf nichts mehr passieren** — kein Countdown, kein Rejoin.
   `krypton_safelogout.txt` = `true`, LOGOUT LOGS enthält den Eintrag mit Name,
   Distanz und Koordinaten.

### 13.5 Staff-Erkennung (Stern-Ranks)
1. ClickGUI → MISC → **STAFF SCAN** öffnen, während Staff und normale Spieler
   online sind.
2. Für jeden Spieler prüfen:
   - Zeigt eine der Quellzeilen (Tab / Name / Team-Prefix / Team-Suffix)
     überhaupt einen Stern (`U+XXXX`)?
   - Stimmt die Hex-Farbe mit dem überein, was man im Tab sieht?
   - Ist die abgeleitete Familie richtig (`Mod/Admin`, `Helper/Owner`,
     `Developer`) und mit `[STAFF]` markiert?
3. **Normale Spieler dürfen nirgends `[STAFF]` bekommen.** Passiert das doch
   (z. B. weil Spender auch Sterne haben), die betroffene Farbfamilie unten im
   Screen abschalten.
4. Wird Staff **nicht** erkannt: prüfen, ob die Sternfarbe in eine andere
   Familie fällt — dann `Andere` einschalten — oder ob der Glyph gar nicht in
   `STAR_GLYPHS` steht. Der angezeigte Codepoint `U+XXXX` sagt genau, welcher
   es ist; er lässt sich direkt in die Konstante nachtragen.
5. Die Einstellungen landen sofort in `krypton_staffdetect.txt` und überleben
   den Neustart.
