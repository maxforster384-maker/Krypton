package com.krypton.cheatclient;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.event.player.*;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.*;
import net.minecraft.world.RaycastContext;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class Krypton implements ModInitializer {

    // --- KEYBINDS ---
    private static KeyBinding openGuiKey;
    private static KeyBinding freecamKeyBinding;
    private static KeyBinding bonesFarmerKeyBinding;
    public static int freecamKey = GLFW.GLFW_KEY_V;
    public static int lastSavedGuiKey = -1;
    private static boolean wasFreecamKeyPressed = false;

    // --- STATUS VARIABLEN ---
    public static boolean isBedrockFinderActive = false;
    public static boolean isPlayerEspActive = false;
    public static boolean isAutoSpawnerActive = false;
    public static boolean isSpawnerEspActive = false;
    public static boolean isTracersActive = false;
    public static boolean isFreecamActive = false;
    public static boolean isFullbrightActive = false;
    public static boolean disableFreecamOnDamage = true;
    public static boolean isManualInteraction = false;

    // --- LOGS & RADAR VARIABLEN ---
    public static final CopyOnWriteArrayList<String> playerHistory = new CopyOnWriteArrayList<>();
    public static String lastLogoutLog = "Bisher kein Logout aufgezeichnet";
    public static final Set<UUID> sessionSeenPlayers = new HashSet<>();

    // --- ANTI-DETECT VARIABLEN (AUTO SPAWNER FIX) ---
    private static int autoSpawnerState = 0;
    private static int actionDelayTimer = 0;

    // --- AUTO RECONNECT VARIABLEN ---
    public static boolean isAutoReconnectActive = false;
    public static boolean isInfiniteReconnect = false;
    public static List<Integer> reconnectDelays = new ArrayList<>(Arrays.asList(3, 10, 30, 60));
    // wasSafetyLogout wird PERSISTIERT (krypton_safelogout.txt). Solange das Flag
    // steht, darf NIEMALS automatisch rejoined werden – weder ueber Auto Reconnect
    // noch ueber den Session-Fix. Sonst koennte der Client nach dem Notfall-Logout
    // direkt wieder auf den Server, waehrend der Gegner noch bei den Spawnern steht.
    public static boolean wasSafetyLogout = false;
    public static int reconnectTicks = -1;
    public static int attemptIndex = 0;
    public static net.minecraft.client.network.ServerInfo lastServer = null;

    // --- SESSION FIX ---
    // Faengt kaputte Verbindungsabbrueche ab und verbindet kontrolliert neu.
    //
    // WICHTIG: "Invalid Session" ist nur EINE von vielen Formulierungen. Auf
    // Servern mit vielen Plugins (DonutSMP & Co.) kommt genauso oft ein roher
    // Java-/Netty-Stacktrace mit langen Zahlen zurueck, z.B.
    //   "Internal Exception: io.netty.handler.codec.DecoderException:
    //    java.lang.IndexOutOfBoundsException: Index 1146 out of bounds for length 0"
    // Deshalb wird der Grund KATEGORISIERT statt gegen eine einzige Liste
    // geprueft, und wie breit reagiert wird, steuert sessionFixMode.
    //
    // Ein echtes Re-Auth ist aus einem Mod heraus nicht moeglich (dafuer
    // braucht es den Microsoft-Refresh-Token des Launchers), deswegen wird
    // nach einer begrenzten Versuchszahl abgebrochen und der User informiert.
    public static boolean isSessionFixActive = false;
    // 0 = STRIKT (nur echte Session-Fehler)
    // 1 = TECHNIK (zusaetzlich Exceptions/Timeouts/Netzwerkfehler)  <- Default
    // 2 = ALLES (jeder Grund ausser den harten Ausschluessen)
    public static int sessionFixMode = 1;
    private static int sessionFixAttempts = 0;
    // Einmal-Flag, damit der Disconnect-Screen-Handler pro Trennung nur einmal
    // laeuft (er wuerde sonst jeden Tick erneut loggen und hochzaehlen).
    private static boolean disconnectHandled = false;
    // Re-Auth-Fenster: Mods wie "Auto Reauth" erneuern die Session, wenn der
    // Multiplayer-Screen geoeffnet wird. Krypton verbindet nach einem Kick aber
    // direkt ueber ConnectScreen – der Check wuerde also nie laufen. Deshalb
    // wird bei Kategorie SESSION vorher kurz der Multiplayer-Screen gezeigt.
    // 5 s beim ersten Versuch, danach laenger – ein Token-Refresh ueber das
    // Microsoft-Login kann je nach Verbindung ein paar Sekunden dauern.
    private static final int REAUTH_WINDOW_TICKS = 100;  // 5 Sekunden
    private static final int REAUTH_WINDOW_MAX   = 300;  // max. 15 Sekunden
    private static int reauthWindowTicks = 0;
    private static boolean pendingSessionReconnect = false;

    // Kategorie 1 – eindeutige Session-/Auth-Probleme.
    private static final String[] SESSION_KICK_PATTERNS = {
        "invalid session", "invalid_session", "ungueltige sitzung", "ungültige sitzung",
        "failed to verify username", "unverified_username", "benutzername konnte nicht",
        "authentication servers", "authentifizierungsserver", "auth servers",
        "not authenticated", "nicht authentifiziert", "bad login",
        "session expired", "sitzung abgelaufen", "session is invalid",
        "already logged in", "bereits eingeloggt", "already online",
        // Der Zusatz "(Try restarting your game and the launcher)" haengt bei
        // Mojang an JEDER Session-/Auth-Meldung dran – egal wie der Rest lautet.
        // Damit werden auch Formulierungen erwischt, die sonst durchrutschen.
        // Bewusst NICHT nur "restart": ein "Server is restarting" darf hier
        // nicht landen.
        "restarting your game", "restart your game", "restarting the game",
        "restart your launcher", "restarting your launcher", "restart the launcher",
        "launcher neu", "spiel neu starten", "starte das spiel neu"
    };
    // Kategorie 2 – technische Abbrueche: Exceptions, Netty, Timeouts, Pakete.
    // Genau hier landen die "Java + lange Zahl"-Kicks.
    private static final String[] TECHNICAL_KICK_PATTERNS = {
        "internal exception", "internal error", "interner fehler", "error id", "fehler-id",
        "io.netty", "java.lang", "java.io", "java.net", "java.util", "exception",
        "timed out", "timeout", "zeitüberschreitung", "zeituberschreitung",
        "connection reset", "connection closed", "connection lost", "forcibly closed",
        "end of stream", "broken pipe", "readerindex", "out of bounds",
        "keepalive", "keep alive", "keep-alive",
        "protocol error", "bad packet", "invalid packet", "packet too", "decoder",
        "nullpointer", "socket", "stacktrace", "at net.minecraft", "at com."
    };
    // Kategorie 3 – hier NIE automatisch neu verbinden. Das sind bewusste
    // Entscheidungen des Servers, kein Bug.
    private static final String[] NEVER_RECONNECT_PATTERNS = {
        "banned", "gebannt", "permanently banned", "temporarily banned",
        "tempban", "permaban", "you are ban",
        "kicked by", "gekickt von", "kicked from the game by",
        "outdated client", "outdated server", "unsupported version", "veraltete version",
        "no permission", "keine berechtigung"
        // BEWUSST NICHT hier: Whitelist und "Server voll".
        // Beides ist voruebergehend. Der Wartungsmodus schaltet die Whitelist
        // nach einer Weile wieder ab, und ein voller Server gibt irgendwann
        // einen Slot frei. Beides faellt damit in Kategorie 0 (SONSTIGES) und
        // wird vom normalen Auto-Reconnect endlos weiterprobiert – genau das,
        // was man beim AFK-Warten auf einen Server will.
    };

    // --- DISCONNECT LOG ---
    // Die letzten 20 Trenngruende im Klartext. Auf Servern mit sehr
    // unterschiedlichen Fehlermeldungen ist das der einzige Weg, die
    // Session-Fix-Einstellung sinnvoll zu waehlen.
    public static final CopyOnWriteArrayList<String> disconnectHistory = new CopyOnWriteArrayList<>();

    // --- BEDROCK FINDER ---
    public static int minHoleSize = 2;
    private static final CopyOnWriteArrayList<List<BlockPos>> stableHoles = new CopyOnWriteArrayList<>();
    private static final List<List<BlockPos>> currentScanBatch = new ArrayList<>();
    private static final Set<BlockPos> visitedPositions = new HashSet<>();
    private static final int RADIUS = 32;
    private static int scanX = -RADIUS, scanZ = -RADIUS;
    private static BlockPos scanAnchor = null;
    private static boolean isScanning = false;

    // --- SPAWNER ESP LISTEN ---
    private static final List<BlockPos> foundSpawners = new ArrayList<>();
    private static int spawnerScanTimer = 0;

    // --- WHITELIST ---
    public static List<String> whitelistedPlayers = new ArrayList<>();

    // --- STAFF-ERKENNUNG (STERN-RANKS) ---
    // Der Server markiert Staff nicht mehr per Klartext ("Admin"), sondern mit
    // einem FARBIGEN STERN im Tab-/Team-Prefix:
    //   grün -> Mod / Admin     blau -> Helper / Owner     lila -> Developer
    // Entscheidend fuer den Guard ist nur "Staff ja/nein" – die Farbe bestimmt
    // lediglich das angezeigte Label. Welche Farbfamilien zaehlen, ist
    // konfigurierbar (krypton_staffdetect.txt + Staff-Scan-Screen), damit man
    // ohne Neubau nachjustieren kann, falls der Server z.B. auch Spendern
    // Sterne gibt.
    public static boolean staffStarGreen  = true;   // Mod / Admin
    public static boolean staffStarBlue   = true;   // Helper / Owner
    public static boolean staffStarPurple = true;   // Developer
    public static boolean staffStarOther  = false;  // andere Sternfarben (Deko/Spender)
    public static boolean staffTextRanks  = true;   // alte Klartext-Erkennung zusaetzlich
    // Stern-Glyphen als Unicode-Escapes, damit die Erkennung nicht von der
    // Quelldatei-Kodierung abhaengt. ASCII '*' ist bewusst NICHT dabei –
    // das kommt in Deko-Prefixes viel zu haeufig vor (False Positives).
    private static final String STAR_GLYPHS =
        "\u2605\u2606\u269D\u2726\u2727\u2729\u272A\u272B\u272C\u272D\u272E\u272F\u2730"
      + "\u2731\u2732\u2733\u2734\u2735\u2736\u2737\u2738\u2739\u273A\u273B\u273C\u273D"
      + "\u2742\u2743\u2749\u274A\u274B\u2B50";
    // Zusaetzliche Glyphen aus krypton_staffglyphs.txt. Noetig, weil viele Server
    // (u.a. DonutSMP) eigene Resourcepack-Symbole aus der Private Use Area
    // (U+E000–U+F8FF) benutzen, die in keiner Unicode-Sternliste stehen.
    public static final Set<Integer> extraStarGlyphs = new HashSet<>();
    // Plausibilitaetsschalter: wird auf false gesetzt, sobald die Stern-Erkennung
    // offensichtlich Unsinn liefert (mehr als die Haelfte aller sichtbaren Spieler
    // waeren "Staff"). Dann zaehlt nur noch die Klartext-Erkennung – der Guard
    // schaltet sich also NICHT wegen eines Deko-Symbols ab.
    public static volatile boolean staffDetectSane = true;
    private static int staffSaneTimer = 0;
    public static int staffSaneTotal = 0, staffSaneHits = 0;

    // --- BONES FARMER ---
    public static boolean isBonesFarmerActive = false;
    public static int bonesFarmerHotkey = GLFW.GLFW_KEY_UNKNOWN;
    public static int bonesFarmerDropBase = 28;
    private static boolean wasBonesFarmerKeyPressed = false;
    private static int bonesFarmerState = 0;
    private static int bonesFarmerDelay = 0;
    private static int bonesFarmerTimeout = 0;
    private static int dropLootClicksTarget = 0;
    private static int dropLootClicksDone = 0;
    private static int bonesFarmerSpawnerIdx = 0;
    private static int arrowsBeforeSpawner = 0;
    private static BlockPos bonesFarmerTargetSpawner = null;
    private static boolean bonesFarmerLoggedSlots = false;
    private static int bonesFarmerEmptyCycles = 0;  // Loot-Zyklen ohne Bones
    private static int bonesFarmerLastChestCount = -1; // Chest-Füllstand vorheriger Tick (Drop-Detection)
    public static volatile boolean bonesFarmerDeliveryDone = false; // Chat-Signal: Order abgeschlossen
    private static boolean bonesFarmerPickNewOrder = false;         // Nach Confirm → User wählt neue Order
    private static int bonesFarmerDeliveryTimer = -1;              // -1 = inaktiv, 0+ = Ticks seit letzter Nachricht

    // --- AUTO SPAWNER ---
    private static boolean isMining = false;
    public static boolean hasMinedSpawner = false;
    private static int safetyLogoutTimer = -1;
    private static BlockPos lastTargetSpawner = null;
    private static double targetOffsetX = 0.5;
    private static double targetOffsetY = 0.5;
    private static double targetOffsetZ = 0.5;

    // --- SPAWNER-SCHUTZ HAERTUNG (Menue-Sperre, Auto-Sneak, Abbau-Garantie) ---
    // guardEngaged = Guard hat einen fremden Spieler erkannt und arbeitet gerade.
    // In dem Zustand hat der Notfall-Abbau absolute Prioritaet: Server-GUIs werden
    // geschlossen, der Bones Farmer pausiert, und das Mining laeuft ueber unseren
    // eigenen Raycast (unabhaengig von Screen, Cursor-Lock und Fensterfokus).
    public static volatile boolean guardEngaged = false;
    // >0 = Auto-Sneak kurz aussetzen. Noetig weil ein sneakender Spieler
    // serverseitig KEINE Block-GUI oeffnen kann (ServerPlayerInteractionManager
    // prueft shouldCancelInteraction()). Der Bones Farmer und die Freecam
    // melden ihre Rechtsklicks hier an.
    public static int sneakSuppressTicks = 0;
    private static boolean forcedSneakLastTick = false;
    // Ticks in denen der Ziel-Spawner nicht per Raycast getroffen wurde
    // (ausser Reichweite / verdeckt). Verhindert ein Haengenbleiben in State 5.
    private static int guardAimFailTicks = 0;
    // Gegner in Reichweite, aber KEIN Spawner, den der Guard von hier aus
    // treffen kann (alle >4,5 Blöcke entfernt oder verdeckt). Der Guard kann
    // dann nichts tun – das muss im HUD sofort auffallen, sonst wiegt man sich
    // in Sicherheit, während der Schutz faktisch nicht greift.
    public static boolean guardNoReachableSpawner = false;
    // Spawner, die 3 s lang nicht getroffen wurden. Werden bei der nächsten
    // Zielwahl übersprungen, sonst wählt findReachableSpawner() sofort wieder
    // dasselbe unerreichbare Ziel und der Guard dreht sich im Kreis.
    private static final Set<BlockPos> guardFailedTargets = new HashSet<>();
    private static int guardSneakWaitTicks = 0;

    // --- DISCORD SPAWNER SCRIPT ---
    private static String discordToken = "MTEwNz0NjQ4MjYxNDEzNjg4NA.GdOveX.igPaPkFKF-umA5pb43o87fqscTv0MiGLOMLky6";
    private static String discordChannelId = "1509562030036619284";
    private static volatile String lastDiscordMessageId = "";
    private static volatile boolean spawnerScriptTrigger = false;
    private static int discordPollTimer = 0;
    public static volatile boolean spawnerScriptActive = false;
    private static int spawnerScriptState = 0;
    private static int spawnerScriptDelay = 0;
    private static int spawnerScriptTimeout = 0;
    private static BlockPos spawnerScriptCurrentTarget = null;
    private static float spawnerScriptDriftYaw = 0f;
    private static float spawnerScriptDriftPitch = 0f;

    // --- FREECAM VARIABLEN ---
    public static double freecamX, freecamY, freecamZ;
    public static double prevFreecamX, prevFreecamY, prevFreecamZ;
    public static float freecamYaw, freecamPitch;
    public static float prevFreecamYaw, prevFreecamPitch;
    public static float savedYaw, savedPitch;
    public static float displayYaw, displayPitch;
    private static float driftYaw = 0f, driftPitch = 0f;
    private static boolean freecamSwitchedPerspective = false;
    private static int ticksConnected = 0;
    private static int rightClickCooldown = 0;
    private static double lastMouseX = 0;
    private static double lastMouseY = 0;
    private static boolean firstMouseTick = true;

    // ==========================================
    // SPEICHER SYSTEM
    // ==========================================

    public static void loadLogs() {
        playerHistory.clear();
        try {
            File file = new File("krypton_logs.txt");
            if (file.exists()) {
                BufferedReader reader = new BufferedReader(new FileReader(file));
                lastLogoutLog = reader.readLine();
                if (lastLogoutLog == null) lastLogoutLog = "Bisher kein Logout aufgezeichnet";
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.trim().isEmpty()) playerHistory.add(line);
                }
                reader.close();
            }
        } catch (Exception e) {}
    }

    public static void saveLogs() {
        try {
            BufferedWriter writer = new BufferedWriter(new FileWriter("krypton_logs.txt"));
            writer.write(lastLogoutLog); writer.newLine();
            for (String s : playerHistory) {
                writer.write(s); writer.newLine();
            }
            writer.close();
        } catch (Exception e) {}
    }

    public static void loadFreecamSettings() {
        try {
            File file = new File("krypton_freecam_settings.txt");
            if (file.exists()) {
                BufferedReader reader = new BufferedReader(new FileReader(file));
                String line = reader.readLine();
                if (line != null && !line.trim().isEmpty()) {
                    disableFreecamOnDamage = Boolean.parseBoolean(line.trim());
                }
                reader.close();
            }
        } catch (Exception e) {}
    }

    public static void saveFreecamSettings() {
        try {
            BufferedWriter writer = new BufferedWriter(new FileWriter("krypton_freecam_settings.txt"));
            writer.write(String.valueOf(disableFreecamOnDamage));
            writer.close();
        } catch (Exception e) {}
    }

    public static void loadWhitelist() {
        whitelistedPlayers.clear();
        try {
            File file = new File("krypton_whitelist.txt");
            if (file.exists()) {
                BufferedReader reader = new BufferedReader(new FileReader(file));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.trim().isEmpty()) whitelistedPlayers.add(line.trim().toLowerCase());
                }
                reader.close();
            }
        } catch (Exception e) {}
    }

    public static void saveWhitelist() {
        try {
            BufferedWriter writer = new BufferedWriter(new FileWriter("krypton_whitelist.txt"));
            for (String name : whitelistedPlayers) {
                writer.write(name);
                writer.newLine();
            }
            writer.close();
        } catch (Exception e) {}
    }

    public static void loadKeybind() {
        try {
            File file = new File("krypton_keybind.txt");
            if (file.exists()) {
                BufferedReader reader = new BufferedReader(new FileReader(file));
                String line = reader.readLine();
                if (line != null && !line.trim().isEmpty()) {
                    freecamKey = Integer.parseInt(line.trim());
                }
                reader.close();
            }
        } catch (Exception e) {}
    }

    public static void saveKeybind() {
        try {
            BufferedWriter writer = new BufferedWriter(new FileWriter("krypton_keybind.txt"));
            writer.write(String.valueOf(freecamKey));
            writer.close();
        } catch (Exception e) {}
    }

    public static void loadBonesFarmerKey() {
        try {
            File file = new File("krypton_bfkey.txt");
            if (file.exists()) {
                BufferedReader reader = new BufferedReader(new FileReader(file));
                String line = reader.readLine();
                if (line != null && !line.trim().isEmpty()) bonesFarmerHotkey = Integer.parseInt(line.trim());
                reader.close();
            }
        } catch (Exception e) {}
    }

    public static void saveBonesFarmerKey() {
        try {
            BufferedWriter writer = new BufferedWriter(new FileWriter("krypton_bfkey.txt"));
            writer.write(String.valueOf(bonesFarmerHotkey));
            writer.close();
        } catch (Exception e) {}
    }

    public static void loadDropBase() {
        try {
            File file = new File("krypton_bfdrop.txt");
            if (file.exists()) {
                BufferedReader reader = new BufferedReader(new FileReader(file));
                String line = reader.readLine();
                if (line != null && !line.trim().isEmpty()) {
                    int v = Integer.parseInt(line.trim());
                    if (v >= 1 && v <= 99) bonesFarmerDropBase = v;
                }
                reader.close();
            }
        } catch (Exception e) {}
    }

    public static void saveDropBase() {
        try {
            BufferedWriter writer = new BufferedWriter(new FileWriter("krypton_bfdrop.txt"));
            writer.write(String.valueOf(bonesFarmerDropBase));
            writer.close();
        } catch (Exception e) {}
    }

    public static void loadGuiKey() {
        try {
            File file = new File("krypton_guikey.txt");
            if (file.exists()) {
                BufferedReader reader = new BufferedReader(new FileReader(file));
                String line = reader.readLine();
                if (line != null && !line.trim().isEmpty()) {
                    lastSavedGuiKey = Integer.parseInt(line.trim());
                }
                reader.close();
            }
        } catch (Exception e) {}
    }

    public static void loadDiscordConfig() {
        // Letzte verarbeitete Message-ID laden (verhindert Re-Trigger nach Crash)
        try {
            File idFile = new File("krypton_discord_id.txt");
            if (idFile.exists()) {
                BufferedReader reader = new BufferedReader(new FileReader(idFile));
                String line = reader.readLine();
                if (line != null && !line.trim().isEmpty()) lastDiscordMessageId = line.trim();
                reader.close();
            }
        } catch (Exception e) {}
    }

    private static void saveLastDiscordMessageId() {
        try {
            BufferedWriter writer = new BufferedWriter(new FileWriter("krypton_discord_id.txt"));
            writer.write(lastDiscordMessageId);
            writer.close();
        } catch (Exception e) {}
    }

    private static void pollDiscordAsync(MinecraftClient client) {
        if (discordToken.isEmpty() || discordChannelId.isEmpty()) return;
        if (client.player == null) return;
        final String myName = client.player.getName().getString();
        new Thread(() -> {
            try {
                URL url = new URL("https://discord.com/api/v9/channels/" + discordChannelId + "/messages?limit=1");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Authorization", discordToken);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                if (conn.getResponseCode() == 200) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    br.close();
                    String resp = sb.toString();
                    int idIdx = resp.indexOf("\"id\":\"");
                    if (idIdx < 0) return;
                    int idStart = idIdx + 6;
                    int idEnd = resp.indexOf("\"", idStart);
                    if (idEnd < 0) return;
                    String msgId = resp.substring(idStart, idEnd);
                    if (msgId.equals(lastDiscordMessageId)) return;
                    int cIdx = resp.indexOf("\"content\":\"");
                    if (cIdx < 0) return;
                    int cStart = cIdx + 11;
                    int cEnd = resp.indexOf("\"", cStart);
                    if (cEnd < 0) return;
                    String content = resp.substring(cStart, cEnd).trim();
                    if ((content.startsWith("Spawner ") || content.startsWith("spawner ")) && content.length() > 8) {
                        String targetName = content.substring(8).trim();
                        if (targetName.equalsIgnoreCase(myName) && !spawnerScriptActive) {
                            lastDiscordMessageId = msgId;
                            saveLastDiscordMessageId();
                            spawnerScriptTrigger = true;
                            sendDiscordLog("✅ Trigger erkannt für: " + myName + " — Script startet");
                        }
                    }
                }
                conn.disconnect();
            } catch (Exception ignored) {}
        }).start();
    }

    public static void saveGuiKey(int key) {
        try {
            BufferedWriter writer = new BufferedWriter(new FileWriter("krypton_guikey.txt"));
            writer.write(String.valueOf(key));
            writer.close();
        } catch (Exception e) {}
    }

    public static void loadFullbright() {
        try {
            File file = new File("krypton_fullbright.txt");
            if (file.exists()) {
                BufferedReader reader = new BufferedReader(new FileReader(file));
                String line = reader.readLine();
                if (line != null && !line.trim().isEmpty()) {
                    isFullbrightActive = Boolean.parseBoolean(line.trim());
                }
                reader.close();
            }
        } catch (Exception e) {}
    }

    public static void saveFullbright() {
        try {
            BufferedWriter writer = new BufferedWriter(new FileWriter("krypton_fullbright.txt"));
            writer.write(String.valueOf(isFullbrightActive));
            writer.close();
        } catch (Exception e) {}
    }

    public static void loadCheatStates() {
        try {
            File file = new File("krypton_cheats.txt");
            if (file.exists()) {
                BufferedReader reader = new BufferedReader(new FileReader(file));
                String line;
                line = reader.readLine(); if (line != null) isBedrockFinderActive = Boolean.parseBoolean(line.trim());
                line = reader.readLine(); if (line != null) isPlayerEspActive     = Boolean.parseBoolean(line.trim());
                line = reader.readLine(); if (line != null) isAutoSpawnerActive   = Boolean.parseBoolean(line.trim());
                line = reader.readLine(); if (line != null) isSpawnerEspActive    = Boolean.parseBoolean(line.trim());
                line = reader.readLine(); if (line != null) isTracersActive       = Boolean.parseBoolean(line.trim());
                line = reader.readLine(); if (line != null) isSessionFixActive    = Boolean.parseBoolean(line.trim());
                line = reader.readLine();
                if (line != null && !line.trim().isEmpty()) {
                    try {
                        int m = Integer.parseInt(line.trim());
                        if (m >= 0 && m <= 2) sessionFixMode = m;
                    } catch (NumberFormatException ignored) {}
                }
                reader.close();
            }
        } catch (Exception e) {}
    }

    public static void saveCheatStates() {
        try {
            BufferedWriter writer = new BufferedWriter(new FileWriter("krypton_cheats.txt"));
            writer.write(String.valueOf(isBedrockFinderActive)); writer.newLine();
            writer.write(String.valueOf(isPlayerEspActive));     writer.newLine();
            writer.write(String.valueOf(isAutoSpawnerActive));   writer.newLine();
            writer.write(String.valueOf(isSpawnerEspActive));    writer.newLine();
            writer.write(String.valueOf(isTracersActive));      writer.newLine();
            writer.write(String.valueOf(isSessionFixActive)); writer.newLine();
            writer.write(String.valueOf(sessionFixMode));
            writer.close();
        } catch (Exception e) {}
    }

    // Notfall-Logout-Sperre persistieren. Ueberlebt damit auch einen Client-Neustart:
    // solange die Datei "true" enthaelt, wird NIE automatisch rejoined.
    public static void loadDisconnectLog() {
        disconnectHistory.clear();
        try {
            File file = new File("krypton_disconnects.txt");
            if (!file.exists()) return;
            BufferedReader reader = new BufferedReader(new FileReader(file));
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) disconnectHistory.add(line);
            }
            reader.close();
        } catch (Exception e) {}
    }

    public static void saveDisconnectLog() {
        try {
            BufferedWriter writer = new BufferedWriter(new FileWriter("krypton_disconnects.txt"));
            for (String s : disconnectHistory) { writer.write(s); writer.newLine(); }
            writer.close();
        } catch (Exception e) {}
    }

    public static void loadSafetyLogout() {
        try {
            File file = new File("krypton_safelogout.txt");
            if (file.exists()) {
                BufferedReader reader = new BufferedReader(new FileReader(file));
                String line = reader.readLine();
                if (line != null && !line.trim().isEmpty()) wasSafetyLogout = Boolean.parseBoolean(line.trim());
                reader.close();
            }
        } catch (Exception e) {}
    }

    // Einziger Schreibpfad fuer wasSafetyLogout – so kann das Flag nirgends
    // versehentlich nur im RAM geaendert werden.
    public static void setSafetyLogout(boolean value) {
        wasSafetyLogout = value;
        try {
            BufferedWriter writer = new BufferedWriter(new FileWriter("krypton_safelogout.txt"));
            writer.write(String.valueOf(value));
            writer.close();
        } catch (Exception e) {}
    }

    public static void loadStaffDetect() {
        try {
            File file = new File("krypton_staffdetect.txt");
            if (!file.exists()) return;
            BufferedReader reader = new BufferedReader(new FileReader(file));
            String line;
            while ((line = reader.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String k = line.substring(0, eq).trim().toLowerCase();
                boolean v = Boolean.parseBoolean(line.substring(eq + 1).trim());
                switch (k) {
                    case "green"  -> staffStarGreen  = v;
                    case "blue"   -> staffStarBlue   = v;
                    case "purple" -> staffStarPurple = v;
                    case "other"  -> staffStarOther  = v;
                    case "text"   -> staffTextRanks  = v;
                    default -> {}
                }
            }
            reader.close();
        } catch (Exception e) {}
    }

    public static void saveStaffDetect() {
        try {
            BufferedWriter writer = new BufferedWriter(new FileWriter("krypton_staffdetect.txt"));
            writer.write("green="  + staffStarGreen);  writer.newLine();
            writer.write("blue="   + staffStarBlue);   writer.newLine();
            writer.write("purple=" + staffStarPurple); writer.newLine();
            writer.write("other="  + staffStarOther);  writer.newLine();
            writer.write("text="   + staffTextRanks);
            writer.close();
        } catch (Exception e) {}
    }

    public static void loadReconnect() {
        try {
            File file = new File("krypton_reconnect.txt");
            if (file.exists()) {
                BufferedReader reader = new BufferedReader(new FileReader(file));

                String line1 = reader.readLine();
                if (line1 != null) isAutoReconnectActive = Boolean.parseBoolean(line1.trim());

                String line2 = reader.readLine();
                if (line2 != null) isInfiniteReconnect = Boolean.parseBoolean(line2.trim());

                String line3 = reader.readLine();
                if (line3 != null && !line3.trim().isEmpty()) {
                    String[] delays = line3.split(",");
                    reconnectDelays.clear();
                    for (String s : delays) {
                        if (!s.trim().isEmpty()) reconnectDelays.add(Integer.parseInt(s.trim()));
                    }
                }
                reader.close();
            } else {
                if (reconnectDelays.isEmpty()) reconnectDelays.addAll(Arrays.asList(3, 10, 30, 60));
            }
        } catch (Exception e) {
            if (reconnectDelays.isEmpty()) reconnectDelays.addAll(Arrays.asList(3, 10, 30, 60));
        }
    }

    public static void saveReconnect() {
        try {
            BufferedWriter writer = new BufferedWriter(new FileWriter("krypton_reconnect.txt"));
            writer.write(String.valueOf(isAutoReconnectActive)); writer.newLine();
            writer.write(String.valueOf(isInfiniteReconnect)); writer.newLine();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < reconnectDelays.size(); i++) {
                sb.append(reconnectDelays.get(i));
                if (i < reconnectDelays.size() - 1) sb.append(",");
            }
            writer.write(sb.toString());
            writer.close();
        } catch (Exception e) {}
    }

    // ==========================================
    // KEYBINDING HELPERS
    // ==========================================

    // Liest das aktuell gebundene InputUtil.Key aus einem KeyBinding
    // (non-final Feld = boundKey). Funktioniert mapping-unabhängig weil
    // defaultKey immer final ist.
    static InputUtil.Key getBoundKey(KeyBinding binding) {
        try {
            for (java.lang.reflect.Field f : KeyBinding.class.getDeclaredFields()) {
                if (f.getType() == InputUtil.Key.class
                        && !java.lang.reflect.Modifier.isFinal(f.getModifiers())) {
                    f.setAccessible(true);
                    return (InputUtil.Key) f.get(binding);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    static int getBoundKeyCode(KeyBinding binding) {
        InputUtil.Key key = getBoundKey(binding);
        return key != null ? key.getCode() : GLFW.GLFW_KEY_UNKNOWN;
    }

    // Setzt den bound Key eines registrierten KeyBinding und aktualisiert die interne Map.
    static void setKeyBindingBoundKey(KeyBinding binding, int keyCode) {
        try {
            for (java.lang.reflect.Field f : KeyBinding.class.getDeclaredFields()) {
                if (f.getType() == InputUtil.Key.class
                        && !java.lang.reflect.Modifier.isFinal(f.getModifiers())) {
                    f.setAccessible(true);
                    f.set(binding, InputUtil.Type.KEYSYM.createFromCode(keyCode));
                    break;
                }
            }
            KeyBinding.updateKeysByCode();
        } catch (Exception ignored) {}
    }

    // Setzt die Minecraft-Perspective via Reflection (umgeht private-Zugriff auf GameOptions.perspective).
    private static void setGamePerspective(MinecraftClient client,
                                            net.minecraft.client.option.Perspective p) {
        try {
            java.lang.reflect.Field pf = client.options.getClass().getDeclaredField("perspective");
            pf.setAccessible(true);
            Object opt = pf.get(client.options);
            for (java.lang.reflect.Method m : opt.getClass().getMethods()) {
                if ("setValue".equals(m.getName()) && m.getParameterCount() == 1) {
                    m.invoke(opt, p); break;
                }
            }
        } catch (Exception ignored) {}
    }

    // Zeichnet einen Tracer als gerade Linie von einem Startpunkt (sx,sy,sz)
    // zum Spieler (ex,ey,ez). Beide Punkte sind kamera-relativ.
    private static void drawTracerLine(MatrixStack st, VertexConsumer buf,
                                        double sx, double sy, double sz,
                                        double ex, double ey, double ez, int color) {
        double dx = ex - sx, dy = ey - sy, dz = ez - sz;
        double dist = Math.sqrt(dx*dx + dy*dy + dz*dz);
        if (!Double.isFinite(dist) || dist < 0.1) return;
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        int a = (color >> 24) & 0xFF;
        float nx = (float)(dx / dist), ny = (float)(dy / dist), nz = (float)(dz / dist);
        var mat   = st.peek().getPositionMatrix();
        var entry = st.peek();
        // WICHTIG: Das Linien-Format in 1.21.11 braucht pro Vertex AUCH lineWidth,
        // sonst -> "Missing elements in vertex: LineWidth" Crash.
        buf.vertex(mat, (float)sx, (float)sy, (float)sz).color(r, g, b, a).normal(entry, nx, ny, nz).lineWidth(2.0f);
        buf.vertex(mat, (float)ex, (float)ey, (float)ez).color(r, g, b, a).normal(entry, nx, ny, nz).lineWidth(2.0f);
    }

    // ==========================================
    // SPAWNER-SCHUTZ (GUARD) – HÄRTUNG
    // ==========================================
    //
    // Hintergrund (Vanilla-Mechanik, 1.21.11):
    // MinecraftClient.tick() ruft handleBlockBreaking(...) mit einem Flag auf,
    // das u.a. "currentScreen == null" und "mouse.isCursorLocked()" enthält.
    // Sobald IRGENDEIN Screen offen ist – Pausenmenü, Chat, Inventar, eine vom
    // Server geöffnete GUI – wird stattdessen cancelBlockBreaking() aufgerufen
    // und der Abbaufortschritt fällt auf 0 zurück. Zusätzlich ruft setScreen()
    // beim Öffnen KeyBinding.unpressAll() auf, wodurch der vom Guard gedrückte
    // Attack-Key wieder losgeht.
    // Genau das ist die Ursache der "baut plötzlich nicht mehr ab"-Bugs:
    //   * ESC (oder Fokusverlust bei aktivem "Pause on Lost Focus") öffnet das
    //     GameMenuScreen → Abbau tot.
    //   * Nach dem Schließen eines Screens ist der Cursor u.U. nicht wieder
    //     gegriffen → Abbau tot.
    //
    // Gegenmaßnahmen (alle drei zusammen, weil jede einzelne Lücken lässt):
    //   1. openGameMenu() wird im MinecraftClientMixin geblockt – das ist der
    //      einzige Vanilla-Einstieg ins Pausenmenü (ESC und Fokusverlust).
    //   2. setScreen() filtert blockierende Screens raus (isScreenBlocked).
    //   3. Der Guard baut NICHT mehr über den Vanilla-Pfad ab, sondern ruft
    //      updateBlockBreakingProgress() selbst auf – wie die Freecam. Damit
    //      ist der Abbau komplett unabhängig von Screen, Cursor-Lock und
    //      Fensterfokus. Vanilla-handleBlockBreaking wird währenddessen
    //      gecancelt, sonst würden sich beide Pfade gegenseitig abbrechen
    //      (derselbe Desync wie bei der Freecam, siehe MinecraftClientMixin).
    //
    // Serverseitig ändert das NICHTS am Paketbild: es werden dieselben
    // PlayerAction- und Swing-Pakete geschickt wie beim manuellen Abbau, und
    // nur auf Blöcke, die ein echter Raycast in Blickrichtung auch trifft.

    /** Guard ist scharf und wir sind in einer Welt. Basis für Menü-Sperre und Auto-Sneak. */
    public static boolean guardLockActive() {
        // Billigster Test zuerst: die Methode wird aus dem setScreen-Mixin
        // gerufen, also auch waehrend des Client-Starts.
        if (!isAutoSpawnerActive) return false;
        MinecraftClient c = MinecraftClient.getInstance();
        return c != null && c.world != null && c.player != null;
    }

    /** Guard führt gerade den Notfall-Abbau aus. */
    public static boolean guardIsMining() {
        return isAutoSpawnerActive && isMining && !isFreecamActive;
    }

    /** Dauer-Sneak, solange der Guard scharf ist und keine Interaktion angemeldet wurde. */
    public static boolean shouldForceSneak() {
        return guardLockActive() && sneakSuppressTicks <= 0;
    }

    /**
     * Meldet eine bevorstehende Rechtsklick-Interaktion an und pausiert den
     * Auto-Sneak. Notwendig, weil der Server bei einem sneakenden Spieler
     * KEINE Block-GUI öffnet (ServerPlayerInteractionManager prüft
     * shouldCancelInteraction() → also isSneaking()). Ohne diese Pause würde
     * der Bones Farmer nie die Spawner-GUI aufbekommen bzw. – mit Block im
     * Slot – sogar einen Block setzen.
     */
    public static void suppressSneak(int ticks) {
        if (ticks > sneakSuppressTicks) sneakSuppressTicks = ticks;
    }

    /** true, sobald der Sneak serverseitig wirklich aus ist (STOP_SNEAKING ist raus). */
    public static boolean isSneakReleased(MinecraftClient client) {
        return client.player == null || !client.player.isSneaking();
    }

    /** Echter, physischer Tastenzustand eines KeyBindings (unabhängig von setPressed). */
    private static boolean isBindingPhysicallyDown(MinecraftClient client, KeyBinding binding) {
        try {
            InputUtil.Key key = getBoundKey(binding);
            if (key == null) return false;
            int code = key.getCode();
            if (code == GLFW.GLFW_KEY_UNKNOWN) return false;
            if (key.getCategory() == InputUtil.Type.MOUSE) {
                return GLFW.glfwGetMouseButton(client.getWindow().getHandle(), code) == GLFW.GLFW_PRESS;
            }
            if (key.getCategory() == InputUtil.Type.KEYSYM) {
                return InputUtil.isKeyPressed(client.getWindow(), code);
            }
        } catch (Exception ignored) {}
        return false;
    }

    /**
     * Dauer-Sneak anwenden. Läuft in START_CLIENT_TICK, also VOR
     * KeyboardInput.tick() im selben Tick. Dadurch entsteht beim Loslassen der
     * echten Sneak-Taste kein einzelner Tick ohne Sneak – und damit auch kein
     * STOP_SNEAKING/START_SNEAKING-Paketpaar, das einem Anti-Cheat auffallen
     * würde. Der Weg über das KeyBinding ist bewusst gewählt: der Server sieht
     * exakt dasselbe wie bei einem Spieler, der Shift gedrückt hält.
     */
    /**
     * Setzt den Sneak-Key IDEMPOTENT auf einen Zielzustand.
     *
     * Warum nicht einfach setPressed(true) jeden Tick: Ist in den Steuerungs-
     * Optionen "Schleichen umschalten" (Toggle Sneak) aktiv, ist sneakKey ein
     * StickyKeyBinding. Dessen setPressed(true) SCHALTET den Zustand UM, statt
     * ihn zu setzen, und setPressed(false) tut gar nichts. Ein setPressed(true)
     * pro Tick kippt Sneak dann jeden Tick an/aus – sichtbar als Zappeln
     * (besonders beim Springen) und als START_SNEAKING/STOP_SNEAKING-Dauerfeuer,
     * das ein Anti-Cheat sofort als unmenschlich einstuft.
     *
     * Ablauf: Stimmt der Zustand schon, passiert nichts. Sonst normal setzen;
     * hat das (Sticky-Fall) keine Wirkung, wird mit setPressed(true) gekippt.
     * Funktioniert damit für normale UND Sticky-Bindings ohne die Option zu
     * kennen.
     */
    private static void setSneakPressed(MinecraftClient client, boolean pressed) {
        KeyBinding k = client.options.sneakKey;
        if (k.isPressed() == pressed) return;
        k.setPressed(pressed);
        if (k.isPressed() != pressed) k.setPressed(true);
    }

    private static void applyForceSneak(MinecraftClient client) {
        if (client == null || client.options == null) return;
        if (shouldForceSneak()) {
            setSneakPressed(client, true);
            forcedSneakLastTick = true;
        } else if (forcedSneakLastTick) {
            // Genau einmal zurücksetzen – und dabei den ECHTEN Tastenzustand
            // wiederherstellen. Sonst hinge Sneak fest, wenn der User Shift
            // gerade gedrückt hält, während der Guard ausgeht.
            setSneakPressed(client, isBindingPhysicallyDown(client, client.options.sneakKey));
            forcedSneakLastTick = false;
        }
        if (sneakSuppressTicks > 0) sneakSuppressTicks--;
    }

    /**
     * Entscheidet, ob ein Screen geöffnet werden darf, solange der Guard scharf ist.
     * Bewusst eine BLOCKLIST und keine Allowlist: unbekannte Screens
     * (Disconnect, Tod, Ladebildschirm, Ressourcenpack-Abfrage) müssen
     * durchkommen, sonst kann sich der Client festfahren.
     */
    public static boolean isScreenBlocked(Screen screen) {
        if (screen == null) return false;
        if (!guardLockActive()) return false;

        String cn = screen.getClass().getName();
        // Eigene Screens bleiben IMMER erreichbar – das ClickGUI ist der
        // einzige Weg, den Guard wieder auszuschalten.
        if (cn.startsWith("com.krypton.")) return false;

        // Eigenes Inventar / Kreativmenü: blocken.
        if (screen instanceof net.minecraft.client.gui.screen.ingame.InventoryScreen) return true;
        if (screen instanceof net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen) return true;
        // Alle anderen HandledScreens kommen vom Server (Spawner-GUI, Order,
        // Bestätigung) – die braucht der Bones Farmer. Im Notfall schließt sie
        // ensureGuardReady() sauber per closeHandledScreen().
        if (screen instanceof HandledScreen<?>) return false;

        // Pausenmenü: hängt an ESC UND an "Pause on Lost Focus" beim Alt-Tab.
        if (screen instanceof net.minecraft.client.gui.screen.GameMenuScreen) return true;
        // Chat exakt per getClass() – so bleibt der SleepingChatScreen erlaubt,
        // sonst läge der Spieler ohne UI im Bett fest.
        if (screen.getClass() == net.minecraft.client.gui.screen.ChatScreen.class) return true;
        if (screen instanceof net.minecraft.client.gui.screen.advancement.AdvancementsScreen) return true;
        if (screen instanceof net.minecraft.client.gui.screen.StatsScreen) return true;
        if (screen instanceof net.minecraft.client.gui.screen.multiplayer.SocialInteractionsScreen) return true;
        if (cn.startsWith("net.minecraft.client.gui.screen.option.")) return true;

        return false;
    }

    /**
     * Watchdog. Läuft jeden Tick, solange der Guard scharf ist, und stellt
     * sicher, dass wirklich jederzeit abgebaut werden kann.
     */
    private static void ensureGuardReady(MinecraftClient client) {
        if (!guardLockActive()) return;

        // 1) Ein gesperrter Screen, der schon offen war, bevor der Guard scharf
        //    gemacht wurde, wird sofort geschlossen.
        if (isScreenBlocked(client.currentScreen)) {
            client.setScreen(null);
        }

        // 2) Im Notfall hat der Abbau absolute Priorität: eine vom Server
        //    geöffnete GUI wird sauber geschlossen (closeHandledScreen schickt
        //    das CloseHandledScreen-Paket, also kein Desync).
        if (guardEngaged && client.player != null
                && client.currentScreen instanceof HandledScreen<?>) {
            client.player.closeHandledScreen();
        }

        // 3) Mauszeiger wieder greifen. Ohne Cursor-Lock lässt Vanilla weder
        //    Umsehen noch Abbau zu. lockCursor() ist ein No-Op ohne
        //    Fensterfokus – der Abbau läuft dann über unseren eigenen Pfad
        //    trotzdem weiter.
        if (client.currentScreen == null && client.mouse != null
                && !client.mouse.isCursorLocked() && client.isWindowFocused()) {
            client.mouse.lockCursor();
        }
    }

    /**
     * Raycast in die AKTUELLE Blickrichtung. Liefert nur einen Treffer, wenn der
     * Strahl wirklich den Ziel-Spawner trifft und dieser in normaler
     * Interaktionsreichweite liegt – exakt so, wie es auch der Server nachrechnet.
     * Damit kann nie ein Abbau-Paket für einen Block rausgehen, den ein echter
     * Spieler gar nicht treffen könnte.
     */
    /**
     * Sucht den nächsten Spawner, den der Guard von der aktuellen Position aus
     * WIRKLICH abbauen kann.
     *
     * Der alte 9×9×9-Scan hatte zwei Löcher, die zusammen den Bug "visiert an,
     * baut aber nie ab" ergeben haben:
     *  1. Er nahm den ERSTEN Treffer in Schleifenreihenfolge (x, y, z je ab -4),
     *     also den Spawner in der Ecke des Würfels – nicht den nächsten. Bei
     *     gestackten Spawnern ist das fast immer der falsche.
     *  2. Die "Sichtprüfung" war keine: ein Raycast bis zur Blockmitte trifft
     *     IMMER irgendeinen Block (spätestens den Spawner selbst) – geprüft
     *     wurde aber nur getType()==BLOCK, nie WELCHER Block. Verdeckte Spawner
     *     und Spawner außerhalb der Interaktionsreichweite (Würfelecke = 6,9
     *     Blöcke, Reichweite = 4,5) galten damit als "sichtbar".
     * Der Abbau-Raycast in State 5 prüft dagegen korrekt: erster Block auf dem
     * Strahl == Ziel, Länge = Reichweite. Der gewählte Spawner war also
     * regelmäßig unerreichbar → guardAimFailTicks → neu anvisieren → derselbe
     * Spawner wird wieder gewählt → Endlosschleife ohne einen einzigen Schlag.
     *
     * Jetzt: exakt dieselbe Prüfung wie beim Abbau (Strahl Richtung Blockmitte,
     * Länge = Reichweite, erster Treffer muss der Spawner sein), davon der
     * nächstgelegene. Ziele aus guardFailedTargets werden übersprungen, damit
     * der Guard garantiert alle Spawner durchgeht und am Ende beim
     * Safety-Logout landet statt in einer Schleife.
     */
    private static BlockPos findReachableSpawner(MinecraftClient client) {
        if (client.world == null || client.player == null) return null;
        Vec3d eye = client.player.getEyePos();
        double reach = client.player.getBlockInteractionRange();
        BlockPos base = client.player.getBlockPos();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int x = -4; x <= 4; x++) {
            for (int y = -4; y <= 4; y++) {
                for (int z = -4; z <= 4; z++) {
                    BlockPos pos = base.add(x, y, z);
                    if (guardFailedTargets.contains(pos)) continue;
                    if (!client.world.getBlockState(pos).isOf(Blocks.SPAWNER)) continue;
                    Vec3d center = Vec3d.ofCenter(pos);
                    double dist = eye.distanceTo(center);
                    if (dist >= bestDist) continue;
                    Vec3d dir = center.subtract(eye);
                    if (dir.lengthSquared() < 1.0E-6) continue;
                    // Nur so lang wie die Reichweite – ein Spawner, den dieser
                    // Strahl nicht als ERSTEN Block trifft, ist auch beim Abbau
                    // nicht treffbar.
                    Vec3d end = eye.add(dir.normalize().multiply(reach));
                    BlockHitResult hit = client.world.raycast(new RaycastContext(eye, end,
                            RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, client.player));
                    if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(pos)) continue;
                    best = pos;
                    bestDist = dist;
                }
            }
        }
        return best;
    }

    private static BlockHitResult guardRaycastTarget(MinecraftClient client, BlockPos target) {
        if (client.world == null || client.player == null || target == null) return null;
        Vec3d start = client.player.getEyePos();
        double reach = client.player.getBlockInteractionRange();
        Vec3d dir = Vec3d.fromPolar(client.player.getPitch(), client.player.getYaw());
        Vec3d end = start.add(dir.multiply(reach));
        BlockHitResult hit = client.world.raycast(new RaycastContext(
                start, end, RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE, client.player));
        if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target)) return hit;
        return null;
    }

    /** Führt den eigentlichen Reconnect auf lastServer aus. */
    private static void doReconnect(MinecraftClient client) {
        reauthWindowTicks = 0;
        // Letzte Reissleine: kein einziger Reconnect-Pfad darf die
        // Notfall-Logout-Sperre umgehen – auch nicht das Re-Auth-Fenster.
        if (wasSafetyLogout) return;
        if (lastServer == null) return;
        attemptIndex++;
        try {
            net.minecraft.client.gui.screen.multiplayer.ConnectScreen.connect(
                    new net.minecraft.client.gui.screen.TitleScreen(),
                    client,
                    net.minecraft.client.network.ServerAddress.parse(lastServer.address),
                    lastServer,
                    false,
                    null
            );
        } catch (Exception e) {
            client.setScreen(new net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen(
                    new net.minecraft.client.gui.screen.TitleScreen()));
        }
    }

    private static boolean matchesAny(String lower, String[] patterns) {
        for (String pattern : patterns) {
            if (lower.contains(pattern)) return true;
        }
        return false;
    }

    /**
     * Kategorisiert einen Disconnect-Grund.
     *   3 = NIE neu verbinden (Ban, Kick durch Staff, Whitelist, falsche Version …)
     *   1 = Session-/Auth-Problem
     *   2 = technischer Abbruch (Exception, Netty, Timeout, kaputtes Paket)
     *   0 = alles andere
     * Reihenfolge ist wichtig: Kategorie 3 gewinnt immer. Ein Ban-Text, der
     * zufaellig auch "exception" enthaelt, darf keinen Reconnect ausloesen.
     */
    static int classifyDisconnect(String reason) {
        if (reason == null) return 0;
        String r = reason.toLowerCase();
        if (matchesAny(r, NEVER_RECONNECT_PATTERNS))  return 3;
        if (matchesAny(r, SESSION_KICK_PATTERNS))     return 1;
        if (matchesAny(r, TECHNICAL_KICK_PATTERNS))   return 2;
        return 0;
    }

    static String disconnectCategoryName(int cat) {
        return switch (cat) {
            case 1 -> "SESSION";
            case 2 -> "TECHNIK";
            case 3 -> "KEIN-REJOIN";
            default -> "SONSTIGES";
        };
    }

    static String sessionFixModeName() {
        return switch (sessionFixMode) {
            case 0 -> "STRIKT";
            case 2 -> "ALLES";
            default -> "TECHNIK";
        };
    }

    /** Greift der Session-Fix fuer diese Kategorie im aktuellen Modus? */
    static boolean sessionFixApplies(int cat) {
        if (!isSessionFixActive) return false;
        return switch (cat) {
            case 1 -> true;                 // echte Session-Fehler immer
            case 2 -> sessionFixMode >= 1;  // technische Abbrueche ab Modus TECHNIK
            case 3 -> false;                // Ban/Kick: niemals
            default -> sessionFixMode >= 2; // unbekannte Gruende nur im Modus ALLES
        };
    }

    /**
     * Wie oft darf hintereinander neu verbunden werden? Bei einem echten
     * Session-Fehler bringt Dauerfeuer nichts (und belastet nur den
     * Mojang-Auth-Server), technische Abbrueche erholen sich dagegen oft.
     */
    static int sessionFixMaxTries(int cat) {
        return cat == 1 ? 3 : 5;
    }

    /** Haengt einen Trenngrund vorne an das Disconnect-Log (max. 20 Eintraege). */
    private static void logDisconnect(String reason, int cat, String action) {
        String time = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
        String clean = reason == null ? "" : reason.replace((char) 167, '&').replace('\n', ' ').trim();
        if (clean.length() > 110) clean = clean.substring(0, 110) + "...";
        String color = switch (cat) {
            case 1 -> "§e";
            case 2 -> "§6";
            case 3 -> "§c";
            default -> "§7";
        };
        disconnectHistory.add(0, "§8[" + time + "] " + color + disconnectCategoryName(cat)
                + " §8→ §f" + action + " §8| §7" + clean);
        while (disconnectHistory.size() > 20) disconnectHistory.remove(disconnectHistory.size() - 1);
        saveDisconnectLog();
    }

    // ==========================================
    // FREECAM LOGIK
    // ==========================================

    public static void toggleFreecam(MinecraftClient client) {
        if (client.world == null || client.player == null) return;

        if (isFreecamActive) {
            freecamX = client.player.getX();
            freecamY = client.player.getEyeY();
            freecamZ = client.player.getZ();
            freecamYaw   = client.player.getYaw();
            freecamPitch = client.player.getPitch();
            savedYaw     = freecamYaw;
            savedPitch   = freecamPitch;
            displayYaw   = savedYaw;
            displayPitch = savedPitch;

            prevFreecamX = freecamX;
            prevFreecamY = freecamY;
            prevFreecamZ = freecamZ;
            prevFreecamYaw = freecamYaw;
            prevFreecamPitch = freecamPitch;

            firstMouseTick = true;
            client.chunkCullingEnabled = false;
        } else {
            client.chunkCullingEnabled = true;
        }
    }

    /**
     * Friert Kopf, Körper UND die Interpolationsfelder auf displayYaw/displayPitch.
     *
     * Die Renderer lerpen jeden Frame zwischen last* und dem aktuellen Wert
     * (Entity.getYaw(tickDelta); LivingEntityRenderer: lastBodyYaw→bodyYaw,
     * lastHeadYaw→headYaw). Werden nur die aktuellen Werte gesetzt, bleibt
     * last* auf dem, was Vanilla im Tick davor daraus gemacht hat – der eigene
     * Körper wackelt dann in der Freecam sichtbar hin und her.
     *
     * Das ist rein CLIENTSEITIG: an den Server gehen nur yaw/pitch aus
     * getYaw()/getPitch(), und die sind seit dem Freecam-Start konstant –
     * Vanilla schickt Rotationspakete nur bei Änderung. Ein Anti-Cheat sieht
     * also einen Spieler, der sich nicht rührt. Das Wackeln war nie am Server,
     * es soll aber weg, damit man dem eigenen Auge trauen kann.
     */
    public static void freezePlayerRotation(PlayerEntity p) {
        if (p == null) return;
        p.setYaw(displayYaw);
        p.setPitch(displayPitch);
        p.setHeadYaw(displayYaw);
        p.setBodyYaw(displayYaw);
        p.lastYaw     = displayYaw;
        p.lastPitch   = displayPitch;
        p.lastHeadYaw = displayYaw;
        p.lastBodyYaw = displayYaw;
    }

    // ==========================================
    // MAIN TICK UND RENDER EVENTS
    // ==========================================

    @Override
    public void onInitialize() {
        loadWhitelist();
        loadKeybind();
        loadFullbright();
        loadReconnect();
        loadGuiKey();
        loadFreecamSettings();
        loadLogs();
        loadCheatStates();
        loadBonesFarmerKey();
        loadDropBase();
        loadDiscordConfig();
        loadSafetyLogout();
        loadStaffDetect();
        loadStaffGlyphs();
        loadDisconnectLog();

        openGuiKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.krypton.gui",
                InputUtil.Type.KEYSYM,
                lastSavedGuiKey != -1 ? lastSavedGuiKey : GLFW.GLFW_KEY_RIGHT_SHIFT,
                KeyBinding.Category.MISC
        ));
        freecamKeyBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.krypton.freecam",
                InputUtil.Type.KEYSYM,
                freecamKey,
                KeyBinding.Category.MISC
        ));
        bonesFarmerKeyBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.krypton.bonesfarmer",
                InputUtil.Type.KEYSYM,
                bonesFarmerHotkey,
                KeyBinding.Category.MISC
        ));

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> saveCheatStates());

        // Auto-Sneak muss VOR der Spieler-Bewegung laufen. START_CLIENT_TICK
        // hängt am Kopf von MinecraftClient.tick(), also vor world.tickEntities()
        // und damit vor KeyboardInput.tick(). Würde man das erst in
        // END_CLIENT_TICK setzen, gäbe es beim Loslassen der echten Sneak-Taste
        // jedes Mal einen Tick ohne Sneak (STOP/START-Paketpaar).
        ClientTickEvents.START_CLIENT_TICK.register(Krypton::applyForceSneak);

        // Chat-Listener: Delivery-Bestätigung erkennen ("delivered" / "bones" + "complete")
        // CHAT-Kanal (Spieler-Nachrichten und Plugin-Broadcasts)
        net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.CHAT.register(
            (message, signedMessage, sender, params, receptionTimestamp) -> {
                if (!isBonesFarmerActive) return;
                String text = message.getString().toLowerCase();
                if (text.contains("deliver") || (text.contains("bones") && text.contains("complet"))) {
                    bonesFarmerDeliveryDone = true;
                }
            });
        // GAME-Kanal (System-/Plugin-Nachrichten ohne Signatur)
        net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.GAME.register(
            (message, overlay) -> {
                if (isBonesFarmerActive && !overlay) {
                    String text = message.getString().toLowerCase();
                    if (text.contains("deliver") || (text.contains("bones") && text.contains("complet"))) {
                        bonesFarmerDeliveryDone = true;
                    }
                }
            });

        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (isFreecamActive && !isManualInteraction) return ActionResult.FAIL;
            return ActionResult.PASS;
        });
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (isFreecamActive && !isManualInteraction) return ActionResult.FAIL;
            return ActionResult.PASS;
        });
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (isFreecamActive && !isManualInteraction) return ActionResult.FAIL;
            return ActionResult.PASS;
        });
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (isFreecamActive && !isManualInteraction) return ActionResult.FAIL;
            return ActionResult.PASS;
        });
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (isFreecamActive && !isManualInteraction) return ActionResult.FAIL;
            return ActionResult.PASS;
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {

            // --- GUI KEY AUTO BACKUP SYSTEM ---
            // Nutzt !isFinal um sicher boundKey (nicht defaultKey) zu treffen
            try {
                for (java.lang.reflect.Field f : KeyBinding.class.getDeclaredFields()) {
                    if (f.getType() == InputUtil.Key.class
                            && !java.lang.reflect.Modifier.isFinal(f.getModifiers())) {
                        f.setAccessible(true);
                        InputUtil.Key key = (InputUtil.Key) f.get(openGuiKey);
                        if (key != null && key.getCode() != -1 && key.getCode() != lastSavedGuiKey) {
                            lastSavedGuiKey = key.getCode();
                            saveGuiKey(lastSavedGuiKey);
                        }
                        break;
                    }
                }
            } catch (Exception e) {}
            // Sync: freecamKey und bonesFarmerHotkey aus den registrierten KeyBindings lesen
            // (falls User in vanilla Controls geändert hat)
            int fcCode = getBoundKeyCode(freecamKeyBinding);
            if (fcCode != GLFW.GLFW_KEY_UNKNOWN && fcCode != freecamKey) {
                freecamKey = fcCode;
                saveKeybind();
            }
            int bfCode = getBoundKeyCode(bonesFarmerKeyBinding);
            if (bfCode != bonesFarmerHotkey) {
                bonesFarmerHotkey = bfCode;
                saveBonesFarmerKey();
            }

            // --- SERVER TRACKING FÜR RECONNECT ---
            if (client.getCurrentServerEntry() != null) {
                lastServer = client.getCurrentServerEntry();
            }
            if (client.world != null) {
                ticksConnected++;
                // wasSafetyLogout erst nach 60 Ticks echter Verbindung clearen –
                // verhindert Reset während des kurzen Disconnect-Übergangs (1-2 Ticks).
                // Weil Auto-Reconnect UND Session-Fix bei gesetztem Flag komplett
                // gesperrt sind, kann diese Verbindung nur eine manuelle sein –
                // genau dann (und nur dann) darf die Sperre wieder fallen.
                if (ticksConnected > 60) {
                    if (wasSafetyLogout) setSafetyLogout(false);
                    sessionFixAttempts = 0;
                    pendingSessionReconnect = false;
                    reauthWindowTicks = 0;
                }
                attemptIndex = 0;
                reconnectTicks = -1;
            } else {
                ticksConnected = 0;
            }

            // --- AUTO RECONNECT / SESSION FIX TICK LOGIK ---
            boolean onDisconnectScreen =
                client.currentScreen instanceof net.minecraft.client.gui.screen.DisconnectedScreen
                && !(client.currentScreen instanceof KryptonReconnectScreen);
            // Sobald wir weder auf dem Disconnect- noch auf unserem eigenen Screen
            // sind, ist die Trennung abgehakt – naechster Disconnect wird wieder
            // ausgewertet.
            if (!onDisconnectScreen && !(client.currentScreen instanceof KryptonReconnectScreen)) {
                disconnectHandled = false;
            }

            if (onDisconnectScreen && !disconnectHandled) {
                disconnectHandled = true;   // pro Trennung genau einmal auswerten

                // Disconnect-Grund auslesen. In 1.21.11 hat DisconnectedScreen KEIN
                // eigenes Text-Feld für den Grund mehr – er steckt im Record
                // DisconnectionInfo (Feld "info", Accessor reason()). Die Text-Felder
                // des Screens sind nur noch Button-Beschriftungen ("Zurück zur
                // Serverliste"). Genau die hat der alte Code erwischt, deshalb stand
                // im Disconnect-Log IMMER "SONSTIGES" – und ein Ban wäre damit nie als
                // KEIN-REJOIN erkannt worden. Statische Felder werden übersprungen.
                Text reasonText = Text.literal("Verbindung vom Server getrennt.");
                try {
                    Text found = null;
                    java.lang.reflect.Field[] fields =
                        net.minecraft.client.gui.screen.DisconnectedScreen.class.getDeclaredFields();
                    // 1) Der eigentliche Grund aus DisconnectionInfo
                    for (java.lang.reflect.Field f : fields) {
                        if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                        if (f.getType() != net.minecraft.network.DisconnectionInfo.class) continue;
                        f.setAccessible(true);
                        Object v = f.get(client.currentScreen);
                        if (v instanceof net.minecraft.network.DisconnectionInfo di && di.reason() != null) {
                            found = di.reason();
                            break;
                        }
                    }
                    // 2) Fallback für ältere Layouts: erstes NICHT-statisches Text-Feld
                    if (found == null) {
                        for (java.lang.reflect.Field f : fields) {
                            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                            if (f.getType() != Text.class) continue;
                            f.setAccessible(true);
                            Object v = f.get(client.currentScreen);
                            if (v != null) { found = (Text) v; break; }
                        }
                    }
                    if (found != null) reasonText = found;
                } catch (Exception e) {}

                String reasonRaw = reasonText.getString();
                int cat = classifyDisconnect(reasonRaw);

                int  delay       = -1;
                Text hint        = null;
                boolean autoBlocked = false;
                String action;
                // Pro Trennung neu entscheiden – ein alter Session-Kick darf das
                // Re-Auth-Fenster nicht auf einen spaeteren Netty-Kick vererben.
                pendingSessionReconnect = false;

                if (wasSafetyLogout) {
                    // HARTE SPERRE. Nach dem Notfall-Logout wird NIE automatisch
                    // neu verbunden – weder über Auto Reconnect noch über den
                    // Session-Fix. Sonst stünde der Client Sekunden später wieder
                    // auf dem Server, während der Gegner noch bei den Spawnern ist.
                    autoBlocked = true;
                    hint = Text.literal("§4Notfall-Logout aktiv §7– Auto-Reconnect gesperrt.");
                    action = "gesperrt (Notfall-Logout)";
                } else if (lastServer == null) {
                    // Kein Server bekannt (z.B. direkt nach einem Client-Neustart).
                    action = "kein Server bekannt";
                } else if (sessionFixApplies(cat) && sessionFixAttempts < sessionFixMaxTries(cat)) {
                    int maxTries = sessionFixMaxTries(cat);
                    sessionFixAttempts++;
                    // Kurzer Delay: sowohl der Session-Aussetzer als auch ein
                    // Netty-/Paketfehler sind beim nächsten Join meist weg.
                    delay = 5;
                    hint  = Text.literal("§eSession-Fix (" + disconnectCategoryName(cat) + ") §7– Versuch "
                                         + sessionFixAttempts + "/" + maxTries);
                    action = "Session-Fix " + sessionFixAttempts + "/" + maxTries;
                    // Nur bei echten Session-Fehlern lohnt das Re-Auth-Fenster;
                    // bei Netty-/Paketfehlern ist der Token ja in Ordnung.
                    pendingSessionReconnect = (cat == 1);
                } else if (isAutoReconnectActive) {
                    // Auch wenn der Session-Fix aufgegeben hat, wird hier weiter
                    // probiert. Genau das braucht man beim AFK-Stehen: der Client
                    // soll von allein zurueckkommen, nicht auf einen Klick warten.
                    if (attemptIndex < reconnectDelays.size()) {
                        delay = reconnectDelays.get(attemptIndex);
                    } else if (isInfiniteReconnect && !reconnectDelays.isEmpty()) {
                        delay = reconnectDelays.get(reconnectDelays.size() - 1);
                    }
                    boolean exhausted = sessionFixApplies(cat);
                    if (exhausted && cat == 1) {
                        // Weiterversuchen ja – aber der Hinweis bleibt: ohne
                        // Re-Auth-Mod hilft am Ende nur ein Client-Neustart.
                        hint = Text.literal("§cSession weiter ungültig §7– ggf. Client neu starten (Re-Auth).");
                    } else if (exhausted) {
                        hint = Text.literal("§c" + sessionFixMaxTries(cat) + " Session-Fix-Versuche erfolglos §7– weiter über Auto-Reconnect.");
                    }
                    // Bei einem echten Session-Fehler auch hier das Re-Auth-Fenster
                    // geben, sonst laeuft der Auto-Reconnect ewig gegen denselben
                    // toten Token.
                    pendingSessionReconnect = (cat == 1);
                    action = delay != -1 ? ("Auto-Reconnect " + delay + "s") : "Reconnect-Liste erschöpft";
                } else if (sessionFixApplies(cat)) {
                    hint = cat == 1
                        ? Text.literal("§cSession dauerhaft ungültig §7– Client neu starten (Re-Auth nötig).")
                        : Text.literal("§c" + sessionFixMaxTries(cat) + " Versuche erfolglos §7– Server oder Verbindung prüfen.");
                    action = "aufgegeben (Auto-Reconnect ist aus)";
                } else {
                    action = "kein Reconnect aktiv";
                }

                logDisconnect(reasonRaw, cat, action);

                if (delay != -1) {
                    reconnectTicks = delay * 20 + (int)(Math.random() * 30 - 15);
                    if (reconnectTicks < 20) reconnectTicks = 20;
                    client.setScreen(new KryptonReconnectScreen(reasonText, hint, false));
                } else if (hint != null) {
                    // Kein automatischer Reconnect, aber der Grund soll sichtbar sein.
                    reconnectTicks = -1;
                    client.setScreen(new KryptonReconnectScreen(reasonText, hint, autoBlocked));
                }
            }

            // Läuft absichtlich unabhängig vom Screen weiter – während des
            // Re-Auth-Fensters ist ja der Multiplayer-Screen offen.
            if (reauthWindowTicks > 0) {
                reauthWindowTicks--;
                if (reauthWindowTicks == 0) doReconnect(client);
            }

            if (client.currentScreen instanceof KryptonReconnectScreen) {
                if (reconnectTicks > 0) {
                    reconnectTicks--;
                } else if (reconnectTicks == 0) {
                    reconnectTicks = -1;
                    if (pendingSessionReconnect) {
                        // Multiplayer-Screen zeigen, damit ein Re-Auth-Mod die Session
                        // erneuern kann – danach wird VON ALLEIN verbunden, ohne Klick.
                        // Fenster waechst mit jedem Fehlversuch (5 s / 10 s / 15 s).
                        pendingSessionReconnect = false;
                        reauthWindowTicks = Math.min(REAUTH_WINDOW_MAX,
                                REAUTH_WINDOW_TICKS * Math.max(1, sessionFixAttempts));
                        client.setScreen(new net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen(
                                new net.minecraft.client.gui.screen.TitleScreen()));
                    } else {
                        doReconnect(client);
                    }
                }
            }

            if (client.world == null) {
                isFreecamActive = false;
                hasMinedSpawner = false;
                autoSpawnerState = 0;
                actionDelayTimer = 0;
                sessionSeenPlayers.clear();
                spawnerScriptActive = false;
                spawnerScriptState = 0;
                spawnerScriptCurrentTarget = null;
                guardEngaged = false;
                sneakSuppressTicks = 0;
                guardAimFailTicks = 0;
                guardSneakWaitTicks = 0;
                guardFailedTargets.clear();
                guardNoReachableSpawner = false;
                return;
            }

            // --- STAFF-ERKENNUNG PLAUSIBILISIEREN ---
            if (++staffSaneTimer >= 40) {
                staffSaneTimer = 0;
                updateStaffSanity(client);
            }

            // --- GUARD WATCHDOG ---
            // Muss VOR allem anderen laufen: sorgt dafuer, dass kein gesperrter
            // Screen offen bleibt und der Mauszeiger gegriffen ist.
            ensureGuardReady(client);

            // --- DISCORD POLL ---
            discordPollTimer++;
            if (discordPollTimer >= 100) { // alle 5 Sekunden
                discordPollTimer = 0;
                pollDiscordAsync(client);
            }
            // Trigger vom Background-Thread übernehmen
            if (spawnerScriptTrigger && !spawnerScriptActive) {
                spawnerScriptTrigger = false;
                spawnerScriptActive = true;
                spawnerScriptState = 1;
                spawnerScriptDelay = 5;
            }

            while (openGuiKey.wasPressed()) {
                if (client.currentScreen == null) client.setScreen(new ClickGuiScreen());
            }

            if (freecamKeyBinding.wasPressed() && client.currentScreen == null) {
                isFreecamActive = !isFreecamActive;
                toggleFreecam(client);
            }

            if (bonesFarmerKeyBinding.wasPressed() && client.currentScreen == null) {
                isBonesFarmerActive = !isBonesFarmerActive;
                bonesFarmerLoggedSlots = false;
                if (!isBonesFarmerActive) { bonesFarmerState = 0; bonesFarmerDelay = 0; bonesFarmerDeliveryTimer = -1; bonesFarmerDeliveryDone = false; }
            }

            if (isFreecamActive && client.player != null) {

                if (disableFreecamOnDamage && client.player.hurtTime > 0) {
                    isFreecamActive = false;
                    toggleFreecam(client);
                    return;
                }

                // Velocity BEWUSST NICHT anfassen. Früher wurde X/Z jeden Tick auf 0
                // gesetzt ("kein Sliding") – genau das ist ein Anti-Cheat-Vektor:
                // der Server simuliert die Physik selbst (Reibung, Wasser, Eis,
                // Knockback) und vergleicht mit der gemeldeten Position. Ein Spieler,
                // der bei einem Treffer keinen Knockback nimmt oder im Wasser nicht
                // treibt, fällt bei Grim & Co. sofort auf (Prediction/Knockback-
                // Check). Der Körper steht trotzdem still, weil KeyboardInputMixin
                // alle Bewegungseingaben nullt – Restmomentum läuft wie in Vanilla aus.

                // Spieler-Kopf/Body EXAKT einfrieren – kein Rauschen mehr.
                // Der Spieler steht perfekt still, kein sichtbares Zittern für
                // andere Spieler oder Anticheat.
                displayYaw   = savedYaw;
                displayPitch = savedPitch;
                // inkl. last*-Felder, sonst lerpt der Renderer und der Körper wackelt
                freezePlayerRotation(client.player);

                prevFreecamX = freecamX;
                prevFreecamY = freecamY;
                prevFreecamZ = freecamZ;
                prevFreecamYaw = freecamYaw;
                prevFreecamPitch = freecamPitch;

                // Maus-Rotation wird über EntityMixin.changeLookDirection in die
                // Freecam umgeleitet – kein eigenes GLFW-Polling mehr nötig.

                if (client.currentScreen == null) {
                    boolean isLeftClicking  = GLFW.glfwGetMouseButton(client.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_LEFT)  == GLFW.GLFW_PRESS;
                    boolean isRightClicking = GLFW.glfwGetMouseButton(client.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;

                    // Abbau: Raycast immer in der eingefrorenen Spieler-Blickrichtung
                    // (savedPitch/savedYaw), NICHT in der Freecam-Blickrichtung.
                    // So dreht sich die Abbau-Richtung nie mit, egal wie die Maus bewegt wird.
                    if (isLeftClicking) {
                        Vec3d start = client.player.getEyePos();
                        Vec3d dir   = Vec3d.fromPolar(savedPitch, savedYaw);
                        double reach = client.player.getBlockInteractionRange();
                        Vec3d end = start.add(dir.multiply(reach));
                        BlockHitResult hit = client.world.raycast(new RaycastContext(start, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, client.player));
                        if (hit.getType() == HitResult.Type.BLOCK && client.interactionManager != null) {
                            isManualInteraction = true;
                            client.interactionManager.updateBlockBreakingProgress(hit.getBlockPos(), hit.getSide());
                            client.player.swingHand(Hand.MAIN_HAND);
                            isManualInteraction = false;
                        }
                    } else {
                        if (client.interactionManager != null) client.interactionManager.cancelBlockBreaking();
                    }

                    // Auch hier gilt: sneakend öffnet der Server keine Block-GUI.
                    // Läuft der Dauer-Sneak des Guards, wird er für die Dauer des
                    // Rechtsklicks abgemeldet. Ist der Guard aus, greift die
                    // Bedingung gar nicht – dann bleibt alles wie vorher.
                    if (isRightClicking) suppressSneak(8);
                    boolean sneakBlocksUse = isAutoSpawnerActive && !isSneakReleased(client);

                    if (isRightClicking && rightClickCooldown <= 0 && !sneakBlocksUse) {
                        Vec3d start = client.player.getEyePos();
                        // Wie beim Abbau: in der EINGEFRORENEN Körper-Blickrichtung,
                        // nicht in Kamerarichtung. Sonst interagiert der Spieler aus
                        // Serversicht mit einem Block, den er gar nicht ansieht –
                        // ein klassischer Interaktions-Richtungs-Check.
                        Vec3d dir   = Vec3d.fromPolar(savedPitch, savedYaw);
                        double reach = client.player.getBlockInteractionRange();
                        Vec3d end = start.add(dir.multiply(reach));
                        BlockHitResult hit = client.world.raycast(new RaycastContext(start, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, client.player));
                        if (hit.getType() == HitResult.Type.BLOCK && client.interactionManager != null) {
                            isManualInteraction = true;
                            client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit);
                            client.player.swingHand(Hand.MAIN_HAND);
                            isManualInteraction = false;
                            rightClickCooldown = 4;
                        }
                    }
                    if (rightClickCooldown > 0) rightClickCooldown--;
                }

                float speed = client.options.sprintKey.isPressed() ? 2.0f : 0.8f;
                Vec3d motion = Vec3d.ZERO;

                Vec3d forward = Vec3d.fromPolar(0, freecamYaw);
                Vec3d right = Vec3d.fromPolar(0, freecamYaw + 90f);

                if (client.options.forwardKey.isPressed()) motion = motion.add(forward);
                if (client.options.backKey.isPressed()) motion = motion.subtract(forward);
                if (client.options.rightKey.isPressed()) motion = motion.add(right);
                if (client.options.leftKey.isPressed()) motion = motion.subtract(right);
                if (client.options.jumpKey.isPressed()) motion = motion.add(0, 1, 0);
                if (client.options.sneakKey.isPressed()) motion = motion.subtract(0, 1, 0);

                if (motion.lengthSquared() > 0) motion = motion.normalize().multiply(speed);

                freecamX += motion.x;
                freecamY += motion.y;
                freecamZ += motion.z;
            }

            // --- RADAR LOGGING ---
            if (client.world != null && client.player != null) {
                for (PlayerEntity p : client.world.getPlayers()) {
                    boolean isMaxzockt6 = p.getName().getString().equalsIgnoreCase("maxzockt6") && spawnerScriptActive;
                    if (p != client.player && !whitelistedPlayers.contains(p.getName().getString().toLowerCase()) && !isMaxzockt6) {
                        if (!sessionSeenPlayers.contains(p.getUuid())) {
                            sessionSeenPlayers.add(p.getUuid());
                            String time = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
                            int dist = (int) Math.round(Math.sqrt(client.player.squaredDistanceTo(p)));
                            String logEntry = "§7[" + time + "] §c" + p.getName().getString() + " §8| §e" + dist + " Blöcke §8| §7X:" + p.getBlockX() + " Y:" + p.getBlockY() + " Z:" + p.getBlockZ();
                            playerHistory.add(0, logEntry);
                            if (playerHistory.size() > 50) playerHistory.remove(playerHistory.size() - 1);
                            saveLogs();
                        }
                    }
                }
            }

            if (isSpawnerEspActive || isAutoSpawnerActive || isBonesFarmerActive || spawnerScriptActive) {
                // Rescan all loaded chunks every 20 ticks (1 second) for performance
                spawnerScanTimer++;
                if (spawnerScanTimer >= 20) {
                    spawnerScanTimer = 0;
                    foundSpawners.clear();
                    if (client.world != null && client.player != null) {
                        int renderDist = client.options.getViewDistance().getValue();
                        int pcx = client.player.getBlockPos().getX() >> 4;
                        int pcz = client.player.getBlockPos().getZ() >> 4;
                        for (int cx = pcx - renderDist; cx <= pcx + renderDist; cx++) {
                            for (int cz = pcz - renderDist; cz <= pcz + renderDist; cz++) {
                                net.minecraft.world.chunk.WorldChunk chunk =
                                    client.world.getChunkManager().getWorldChunk(cx, cz);
                                if (chunk == null) continue;
                                // Use block entity map – O(n) over spawners, not over all blocks
                                for (Map.Entry<BlockPos, net.minecraft.block.entity.BlockEntity> e :
                                        chunk.getBlockEntities().entrySet()) {
                                    if (e.getValue() instanceof
                                            net.minecraft.block.entity.MobSpawnerBlockEntity) {
                                        foundSpawners.add(e.getKey());
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ====================================================
            // AUTO SPAWNER LOGIK
            // ====================================================
            if (isAutoSpawnerActive && client.world != null && client.player != null && !isFreecamActive) {
                boolean enemyFound = false;

                for (PlayerEntity p : client.world.getPlayers()) {
                    if (p == client.player) continue;
                    if (whitelistedPlayers.contains(p.getName().getString().toLowerCase())) continue;
                    if (client.player.squaredDistanceTo(p) >= 1600) continue;

                    String rank = getPlayerRank(client, p);

                    if (!rank.isEmpty()) {
                        // Staff → Guard aus, still halten, nichts abbauen.
                        // sneakKey wird bewusst NICHT angefasst: applyForceSneak()
                        // stellt im nächsten Tick den echten Tastenzustand wieder her.
                        isAutoSpawnerActive = false;
                        client.options.attackKey.setPressed(false);
                        if (client.interactionManager != null) client.interactionManager.cancelBlockBreaking();
                        isMining = false;
                        hasMinedSpawner = false;
                        autoSpawnerState = 0;
                        actionDelayTimer = 0;
                        lastTargetSpawner = null;
                        safetyLogoutTimer = -1;
                        guardEngaged = false;
                        guardAimFailTicks = 0;
                        guardSneakWaitTicks = 0;
                        guardFailedTargets.clear();
                        break;
                    }

                    // Normaler Spieler → abbauen + ausloggen
                    enemyFound = true;
                    String time = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
                    int dist = (int) Math.round(Math.sqrt(client.player.squaredDistanceTo(p)));
                    lastLogoutLog = "§cNotfall-Logout (" + time + "): §e" + p.getName().getString() + " §8| §7" + dist + " Blöcke §8| §7X:" + p.getBlockX() + " Y:" + p.getBlockY() + " Z:" + p.getBlockZ();
                    saveLogs();
                    break;
                }

                // Nur ein Spawner, der von hier aus WIRKLICH abbaubar ist (sichtbar
                // UND in Reichweite) – siehe findReachableSpawner().
                BlockPos spawnerPos = enemyFound ? findReachableSpawner(client) : null;

                // Notfall-Modus: ab hier hat der Abbau Vorrang vor allem anderen
                // (Server-GUIs werden geschlossen, der Bones Farmer pausiert).
                guardEngaged = enemyFound || hasMinedSpawner;
                // Gegner da, aber nichts Abbaubares in Sicht und noch nichts
                // abgebaut → der Guard ist wirkungslos. Sichtbar machen (HUD).
                guardNoReachableSpawner = enemyFound && spawnerPos == null && !hasMinedSpawner;

                if (isMining && lastTargetSpawner != null) {
                    if (!client.world.getBlockState(lastTargetSpawner).isOf(Blocks.SPAWNER)) {
                        client.options.attackKey.setPressed(false);
                        isMining = false;
                        guardAimFailTicks = 0;
                        autoSpawnerState = 0;
                        actionDelayTimer = 4 + (int)(Math.random() * 6);
                        lastTargetSpawner = null;
                        spawnerPos = null;
                    }
                }

                if (enemyFound && spawnerPos != null) {
                    safetyLogoutTimer = -1;
                    hasMinedSpawner = true;

                    if (lastTargetSpawner == null || !lastTargetSpawner.equals(spawnerPos)) {
                        lastTargetSpawner = spawnerPos;
                        targetOffsetX = 0.3 + Math.random() * 0.4;
                        targetOffsetY = 0.3 + Math.random() * 0.4;
                        targetOffsetZ = 0.3 + Math.random() * 0.4;
                        autoSpawnerState = 0;
                        driftYaw = 0f;
                        driftPitch = 0f;
                    }

                    if (actionDelayTimer > 0) {
                        actionDelayTimer--;
                        return;
                    }

                    switch (autoSpawnerState) {
                        case 0:
                            autoSpawnerState = 1;
                            actionDelayTimer = 3 + (int)(Math.random() * 4);
                            break;

                        case 1:
                            int bestSlot = client.player.getInventory().getSelectedSlot();
                            int backupPickaxe = -1;
                            boolean foundSilkTouch = false;

                            for (int i = 0; i < 9; i++) {
                                ItemStack stack = client.player.getInventory().getStack(i);
                                if (stack.isIn(ItemTags.PICKAXES)) {
                                    backupPickaxe = i;
                                    String enchants = stack.getEnchantments().toString().toLowerCase();
                                    if (enchants.contains("silk_touch") || enchants.contains("behutsamkeit")) {
                                        bestSlot = i;
                                        foundSilkTouch = true;
                                        break;
                                    }
                                }
                            }
                            if (!foundSilkTouch && backupPickaxe != -1) {
                                bestSlot = backupPickaxe;
                            }
                            client.player.getInventory().setSelectedSlot(bestSlot);

                            autoSpawnerState = 2;
                            actionDelayTimer = 2 + (int)(Math.random() * 3);
                            break;

                        case 2:
                            double dX = spawnerPos.getX() + targetOffsetX - client.player.getX();
                            double dY = spawnerPos.getY() + targetOffsetY - client.player.getEyeY();
                            double dZ = spawnerPos.getZ() + targetOffsetZ - client.player.getZ();

                            float targetYaw = (float)Math.toDegrees(Math.atan2(dZ, dX)) - 90f;
                            float targetPitch = (float)-Math.toDegrees(Math.atan2(dY, Math.sqrt(dX*dX+dZ*dZ)));

                            float sensRot = client.options.getMouseSensitivity().getValue().floatValue();
                            float fRot = sensRot * 0.6F + 0.2F;
                            float gcdRot = fRot * fRot * fRot * 8.0F * 0.15F;

                            float yawDiff = MathHelper.wrapDegrees(targetYaw - client.player.getYaw());
                            float pitchDiff = MathHelper.wrapDegrees(targetPitch - client.player.getPitch());

                            float speedFactor = 0.3f;
                            float stepYaw = yawDiff * speedFactor;
                            float stepPitch = pitchDiff * speedFactor;

                            stepYaw = MathHelper.clamp(stepYaw, -20f, 20f);
                            stepPitch = MathHelper.clamp(stepPitch, -20f, 20f);

                            stepYaw -= stepYaw % gcdRot;
                            stepPitch -= stepPitch % gcdRot;

                            client.player.setYaw(client.player.getYaw() + stepYaw);
                            client.player.setPitch(client.player.getPitch() + stepPitch);

                            if (Math.abs(yawDiff) < 2f && Math.abs(pitchDiff) < 2f) {
                                autoSpawnerState = 3;
                                actionDelayTimer = 1 + (int)(Math.random() * 3);
                            }
                            break;

                        case 3:
                            // Sneak hält der Dauer-Sneak (applyForceSneak) bereits.
                            // Hier wird nur abgewartet, bis er serverseitig wirklich
                            // anliegt – dann erst startet der Abbau. Nach 10 Ticks
                            // wird trotzdem weitergemacht, damit der Guard im Notfall
                            // niemals hängen bleibt.
                            if (client.player.isSneaking() || ++guardSneakWaitTicks > 10) {
                                guardSneakWaitTicks = 0;
                                autoSpawnerState = 4;
                                actionDelayTimer = 1 + (int)(Math.random() * 2);
                            }
                            break;

                        case 4:
                            client.options.attackKey.setPressed(true);
                            isMining = true;
                            guardAimFailTicks = 0;
                            autoSpawnerState = 5;
                            break;

                        case 5:
                            client.options.attackKey.setPressed(true);

                            // Brownian-Motion-Drift: random walk mit Mean-Reversion
                            // kein sinusoidales Muster mehr das AC erkennen könnte
                            driftYaw   += (float)(Math.random() - 0.5) * 0.05f;
                            driftPitch += (float)(Math.random() - 0.5) * 0.03f;
                            driftYaw   *= 0.85f;
                            driftPitch *= 0.85f;
                            driftYaw   = MathHelper.clamp(driftYaw, -0.12f, 0.12f);
                            driftPitch = MathHelper.clamp(driftPitch, -0.08f, 0.08f);

                            float sensDrift = client.options.getMouseSensitivity().getValue().floatValue();
                            float fDrift = sensDrift * 0.6F + 0.2F;
                            float gcdDrift = fDrift * fDrift * fDrift * 8.0F * 0.15F;

                            float safeYawDrift = driftYaw - (driftYaw % gcdDrift);
                            float safePitchDrift = driftPitch - (driftPitch % gcdDrift);

                            client.player.setYaw(client.player.getYaw() + safeYawDrift);
                            client.player.setPitch(client.player.getPitch() + safePitchDrift);

                            // EIGENER Abbau statt Vanilla-handleBlockBreaking.
                            // Vanilla würde hier an drei Stellen aussteigen: offener
                            // Screen, nicht gegriffener Mauszeiger, attackCooldown.
                            // Genau das sind die "baut plötzlich nicht mehr ab"-Bugs.
                            // Über den eigenen Raycast ist der Abbau davon komplett
                            // unabhängig – und trotzdem serverkonform, weil nur auf
                            // einen Block gefeuert wird, den der Strahl in echter
                            // Blickrichtung und innerhalb der Interaktionsreichweite
                            // trifft. Vanilla wird währenddessen im
                            // MinecraftClientMixin gecancelt, sonst brechen sich
                            // beide Pfade gegenseitig ab (gleicher Desync wie Freecam).
                            BlockHitResult guardHit = guardRaycastTarget(client, lastTargetSpawner);
                            if (guardHit != null && client.interactionManager != null) {
                                guardAimFailTicks = 0;
                                client.interactionManager.updateBlockBreakingProgress(guardHit.getBlockPos(), guardHit.getSide());
                                client.player.swingHand(Hand.MAIN_HAND);
                            } else {
                                // Ziel verdeckt oder außer Reichweite.
                                if (client.interactionManager != null) client.interactionManager.cancelBlockBreaking();
                                guardAimFailTicks++;
                                if (guardAimFailTicks > 60) {
                                    // Nach 3 Sekunden aufgeben: Ziel freigeben, damit
                                    // der Guard weitermacht bzw. der Safety-Logout
                                    // greift, statt für immer in State 5 zu hängen.
                                    guardAimFailTicks = 0;
                                    isMining = false;
                                    client.options.attackKey.setPressed(false);
                                    // Merken – sonst wählt die Suche im nächsten Tick
                                    // exakt dieses Ziel wieder und die Schleife beginnt von vorn.
                                    if (lastTargetSpawner != null) guardFailedTargets.add(lastTargetSpawner);
                                    lastTargetSpawner = null;
                                    autoSpawnerState = 0;
                                    actionDelayTimer = 4 + (int)(Math.random() * 6);
                                } else if (guardAimFailTicks > 5) {
                                    autoSpawnerState = 2; // nochmal sauber anvisieren
                                }
                            }
                            break;
                    }

                } else if (hasMinedSpawner && spawnerPos == null) {
                    if (safetyLogoutTimer == -1) {
                        safetyLogoutTimer = 8 + (int)(Math.random() * 17);
                    }

                    if (safetyLogoutTimer > 0) {
                        safetyLogoutTimer--;
                    } else if (safetyLogoutTimer == 0) {
                        if (isMining) {
                            client.options.attackKey.setPressed(false);
                            isMining = false;
                        }
                        if (client.interactionManager != null) client.interactionManager.cancelBlockBreaking();
                        autoSpawnerState = 0;
                        actionDelayTimer = 0;
                        isAutoSpawnerActive = false;
                        // PERSISTENT setzen: ab jetzt ist jeder automatische Rejoin
                        // gesperrt – auch nach einem Client-Neustart. Erst eine
                        // manuelle Verbindung (60 Ticks stabil) hebt die Sperre auf.
                        setSafetyLogout(true);
                        ticksConnected = 0;
                        safetyLogoutTimer = -1;
                        lastTargetSpawner = null;
                        hasMinedSpawner = false;
                        guardEngaged = false;
                        guardAimFailTicks = 0;
                        guardSneakWaitTicks = 0;
                        guardFailedTargets.clear();

                        if (client.getNetworkHandler() != null) {
                            client.getNetworkHandler().getConnection().disconnect(Text.literal("§aAlle Spawner im Umkreis gesichert! §4Notfall-Logout."));
                        }
                    }
                } else {
                    safetyLogoutTimer = -1;
                    autoSpawnerState = 0;
                    actionDelayTimer = 0;
                    lastTargetSpawner = null;
                    guardAimFailTicks = 0;
                    guardSneakWaitTicks = 0;
                    guardFailedTargets.clear();
                    if (isMining) {
                        client.options.attackKey.setPressed(false);
                        if (client.interactionManager != null) client.interactionManager.cancelBlockBreaking();
                        isMining = false;
                    }
                }
            } else {
                // Guard aus oder Freecam an: kompletter Reset. sneakKey bleibt
                // unberührt – applyForceSneak() gibt die Taste sauber frei.
                safetyLogoutTimer = -1;
                autoSpawnerState = 0;
                actionDelayTimer = 0;
                lastTargetSpawner = null;
                guardEngaged = false;
                guardAimFailTicks = 0;
                guardSneakWaitTicks = 0;
                guardFailedTargets.clear();
                guardNoReachableSpawner = false;
                if (isMining) {
                    client.options.attackKey.setPressed(false);
                    if (client.interactionManager != null) client.interactionManager.cancelBlockBreaking();
                    isMining = false;
                }
            }

            tickBonesFarmer(client);
            tickSpawnerScript(client);

            if (isBedrockFinderActive) {
                if (!isScanning) { scanAnchor = client.player.getBlockPos(); scanX = -RADIUS; scanZ = -RADIUS; visitedPositions.clear(); currentScanBatch.clear(); isScanning = true; }
                int q = 0; while (q++ < 1000 && isScanning) {
                    if (scanX > RADIUS) { stableHoles.clear(); stableHoles.addAll(currentScanBatch); isScanning = false; break; }
                    BlockPos bp = scanAnchor.add(scanX, 0, scanZ);
                    if (client.world.getChunkManager().isChunkLoaded(bp.getX()>>4, bp.getZ()>>4)) {
                        for (int y = -64; y <= -50; y++) {
                            BlockPos p = new BlockPos(bp.getX(), y, bp.getZ());
                            if (!visitedPositions.contains(p) && client.world.getBlockState(p).isOf(Blocks.DEEPSLATE)) analyzeHole(client.world, p);
                        }
                    }
                    if (++scanZ > RADIUS) { scanZ = -RADIUS; scanX++; }
                }
            } else { stableHoles.clear(); isScanning = false; visitedPositions.clear(); }
        });

        HudRenderCallback.EVENT.register((drawContext, tickCounter) -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.textRenderer == null) return;

            List<String> activeCheats = new ArrayList<>();
            if (isBedrockFinderActive) activeCheats.add("Finder: §4" + stableHoles.size());
            if (isPlayerEspActive) activeCheats.add("Player ESP: §bON");
            if (isTracersActive) activeCheats.add("Tracers: §bON");
            if (isFreecamActive) activeCheats.add("Freecam: §aON");
            if (isFullbrightActive) activeCheats.add("Fullbright: §eON");
            // Guard zeigt mit an, dass Menüs gesperrt sind – sonst wundert man
            // sich, warum ESC nichts tut.
            if (isAutoSpawnerActive) {
                String guardState = guardNoReachableSpawner
                        ? "§cKEIN SPAWNER IN REICHWEITE"   // Gegner da, Guard kann nichts tun
                        : (guardEngaged ? "§4EINSATZ" : "§eON");
                activeCheats.add("Guard: " + guardState + " §8[Menüs gesperrt]");
            }
            if (isBonesFarmerActive) activeCheats.add("Bones: §aON");
            if (isSpawnerEspActive) activeCheats.add("Spawner ESP: §dON");
            if (isAutoReconnectActive) activeCheats.add("Reconnect: §aON");
            if (isSessionFixActive) activeCheats.add("Session Fix: §aON");
            if (wasSafetyLogout) activeCheats.add("§4Rejoin gesperrt (Notfall-Logout)");
            // Sichtbare Warnung statt stillem Schutzverlust
            if (!staffDetectSane && isAutoSpawnerActive)
                activeCheats.add("§cStern-Erkennung unplausibel §8(" + staffSaneHits + "/" + staffSaneTotal + ") §7– nur Text");

            if (activeCheats.isEmpty()) return;

            drawContext.getMatrices().pushMatrix();
            float scale = 0.5f;
            drawContext.getMatrices().scale(scale, scale);

            int lineHeight = 11;
            int totalHeight = (activeCheats.size() + 1) * lineHeight;
            int scaledScreenHeight = (int) (mc.getWindow().getScaledHeight() / scale);
            int y = scaledScreenHeight - totalHeight - 10;

            drawContext.drawText(mc.textRenderer, "§6Krypton", 5, y, -1, true);
            for (String cheat : activeCheats) {
                y += lineHeight;
                drawContext.drawText(mc.textRenderer, cheat, 5, y, -1, true);
            }

            drawContext.getMatrices().popMatrix();
        });

        WorldRenderEvents.END_MAIN.register((WorldRenderContext context) -> {
            MinecraftClient mc = MinecraftClient.getInstance(); if (mc.world == null) return;
            MatrixStack st = context.matrices(); Vec3d cam = context.gameRenderer().getCamera().getCameraPos();

            VertexConsumerProvider.Immediate imm = mc.getBufferBuilders().getEntityVertexConsumers();

            imm.draw();
            GL11.glDisable(GL11.GL_DEPTH_TEST);

            if (isBedrockFinderActive) {
                VertexConsumer lines = imm.getBuffer(RenderLayers.lines());
                for (List<BlockPos> cl : stableHoles) {
                    for (BlockPos p : cl) {
                        double x = p.getX() - cam.x, y = p.getY() - cam.y, z = p.getZ() - cam.z;
                        VertexRendering.drawOutline(st, lines, VoxelShapes.fullCube(), x, y, z, 0xFF990000, 1.0f);
                    }
                }
                imm.draw();
            }

            if (isPlayerEspActive) {
                VertexConsumer lines = imm.getBuffer(RenderLayers.lines());
                // Snapshot verhindert ConcurrentModificationException beim Spieler-join/-leave
                List<PlayerEntity> playerSnapshot = new java.util.ArrayList<>(mc.world.getPlayers());
                for (PlayerEntity p : playerSnapshot) {
                    if (p.isRemoved()) continue;
                    // Eigener Spieler bekommt AUCH eine Box (z.B. um sich in der
                    // Freecam von weitem wiederzufinden) – aber nur wenn die Kamera
                    // weit genug weg ist. In 1st-Person würde die Box sonst direkt
                    // an der Kamera kleben und den ganzen Bildschirm ausfüllen.
                    if (p == mc.player) {
                        double dcx = p.getX() - cam.x;
                        double dcy = p.getEyeY() - cam.y;
                        double dcz = p.getZ() - cam.z;
                        if (dcx*dcx + dcy*dcy + dcz*dcz < 1.0) continue;
                    }

                    net.minecraft.util.math.Box bbox = p.getBoundingBox();
                    if (bbox == null) continue;
                    float h = (float)(bbox.maxY - bbox.minY);
                    if (h <= 0f || Float.isNaN(h)) continue;

                    String lowerName = p.getName().getString().toLowerCase();
                    boolean isWhitelisted = whitelistedPlayers.contains(lowerName);
                    int color = isWhitelisted ? 0xFF00FF80 : 0xFF0080FF;

                    double px = p.getX() - cam.x;
                    double py = p.getY() - cam.y;
                    double pz = p.getZ() - cam.z;

                    float legTop  = h * (0.75f / 1.8f);
                    float torsoTop= h * (1.5f  / 1.8f);
                    float torsoCY = (legTop + torsoTop) / 2f;
                    float torsoHH = (torsoTop - legTop) / 2f;
                    float legCY   = legTop / 2f;
                    float legHH   = legTop / 2f;

                    float bodyYaw = p.getBodyYaw();
                    if (Float.isNaN(bodyYaw) || Float.isInfinite(bodyYaw)) bodyYaw = 0f;

                    st.push();
                    st.translate(px, py, pz);
                    st.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-bodyYaw));

                    // Kopf – dreht sich mit headYaw unabhängig vom Body
                    float headYaw = p.getHeadYaw();
                    if (Float.isNaN(headYaw) || Float.isInfinite(headYaw)) headYaw = bodyYaw;
                    st.push();
                    st.translate(0, torsoTop + 0.25, 0);
                    st.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-(headYaw - bodyYaw)));
                    VertexRendering.drawOutline(st, lines,
                        VoxelShapes.cuboid(-0.25, -0.25, -0.25, 0.25, 0.25, 0.25),
                        0, 0, 0, color, 4.5f);
                    st.pop();

                    // Torso
                    VertexRendering.drawOutline(st, lines,
                        VoxelShapes.cuboid(-0.25, -torsoHH, -0.125, 0.25, torsoHH, 0.125),
                        0, torsoCY, 0, color, 4.5f);

                    // Arm-Schwinganimation via LimbAnimator (Schulter als Pivot)
                    float limbPos = p.limbAnimator.getAnimationProgress();
                    float limbSpd = Math.min(p.limbAnimator.getSpeed(), 1.0f);
                    float armSwing = (float)Math.toDegrees(Math.cos(limbPos * 0.6662f) * limbSpd);

                    // Linker Arm – hängt am Schulter-Pivot, schwingt entgegenphasig
                    st.push();
                    st.translate(-0.375, torsoTop, 0);
                    st.multiply(RotationAxis.POSITIVE_X.rotationDegrees(armSwing));
                    VertexRendering.drawOutline(st, lines,
                        VoxelShapes.cuboid(-0.125, -(torsoHH * 2), -0.125, 0.125, 0, 0.125),
                        0, 0, 0, color, 4.5f);
                    st.pop();

                    // Rechter Arm – hängt am Schulter-Pivot
                    st.push();
                    st.translate(0.375, torsoTop, 0);
                    st.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-armSwing));
                    VertexRendering.drawOutline(st, lines,
                        VoxelShapes.cuboid(-0.125, -(torsoHH * 2), -0.125, 0.125, 0, 0.125),
                        0, 0, 0, color, 4.5f);
                    st.pop();

                    // Linkes Bein
                    VertexRendering.drawOutline(st, lines,
                        VoxelShapes.cuboid(-0.125, -legHH, -0.125, 0.125, legHH, 0.125),
                        -0.125, legCY, 0, color, 4.5f);
                    // Rechtes Bein
                    VertexRendering.drawOutline(st, lines,
                        VoxelShapes.cuboid(-0.125, -legHH, -0.125, 0.125, legHH, 0.125),
                        0.125, legCY, 0, color, 4.5f);

                    st.pop();
                }
                imm.draw();
            }

            if (isTracersActive) {
                VertexConsumer tracerBuf = imm.getBuffer(RenderLayers.lines());
                // Startpunkt aller Tracer: 1 Block vor der Kamera in Blickrichtung.
                // NICHT direkt an der Kamera (0,0,0) starten – das würde durch die
                // Near-Clip-Plane abgeschnitten und die Linie wäre unsichtbar.
                var tracerCam = context.gameRenderer().getCamera();
                Vec3d tracerLook = Vec3d.fromPolar(tracerCam.getPitch(), tracerCam.getYaw());
                double sx = tracerLook.x, sy = tracerLook.y, sz = tracerLook.z;
                List<PlayerEntity> tracerSnap = new java.util.ArrayList<>(mc.world.getPlayers());
                for (PlayerEntity p : tracerSnap) {
                    if (p == mc.player || p.isRemoved()) continue;
                    net.minecraft.util.math.Box bbox = p.getBoundingBox();
                    if (bbox == null) continue;
                    float h = (float)(bbox.maxY - bbox.minY);
                    if (h <= 0f || Float.isNaN(h)) continue;
                    double ex = p.getX() - cam.x;
                    double ey = p.getY() - cam.y + h * 0.5;
                    double ez = p.getZ() - cam.z;
                    if (!Double.isFinite(ex) || !Double.isFinite(ey) || !Double.isFinite(ez)) continue;
                    String lowerName = p.getName().getString().toLowerCase();
                    boolean isWhitelisted = whitelistedPlayers.contains(lowerName);
                    int color = isWhitelisted ? 0xFF00FF80 : 0xFF0080FF;
                    drawTracerLine(st, tracerBuf, sx, sy, sz, ex, ey, ez, color);
                }
                imm.draw();
            }

            if (isSpawnerEspActive) {
                VertexConsumer lines = imm.getBuffer(RenderLayers.lines());
                for (BlockPos p : foundSpawners) {
                    double x = p.getX() - cam.x, y = p.getY() - cam.y, z = p.getZ() - cam.z;
                    for (int layer = 0; layer <= 40; layer++) {
                        double e = layer * 0.0006;
                        VertexRendering.drawOutline(st, lines, VoxelShapes.cuboid(-e, -e, -e, 1 + e, 1 + e, 1 + e), x, y, z, 0xFFFF66CC, 1.0f);
                    }
                }
                imm.draw();
            }

            GL11.glEnable(GL11.GL_DEPTH_TEST);
        });

    }

    // ==========================================
    // BONES FARMER
    // ==========================================

    private void tickBonesFarmer(MinecraftClient client) {
        if (!isBonesFarmerActive || client.world == null || client.player == null) return;
        if (isFreecamActive) return;
        // Notfall des Spawner-Schutzes hat Vorrang: der Farmer würde sonst
        // weiter in GUIs klicken, während der Guard abbauen und ausloggen will.
        if (guardEngaged) {
            if (client.currentScreen instanceof HandledScreen<?>) client.player.closeHandledScreen();
            bonesFarmerState = 0; bonesFarmerDelay = 5;
            return;
        }
        if (isStaffNearby(client)) {
            isBonesFarmerActive = false;
            bonesFarmerState = 0; bonesFarmerDelay = 0; bonesFarmerDeliveryTimer = -1; bonesFarmerDeliveryDone = false;
            if (client.currentScreen instanceof HandledScreen<?>) client.player.closeHandledScreen();
            return;
        }
        if (bonesFarmerDelay > 0) { bonesFarmerDelay--; return; }

        switch (bonesFarmerState) {

            // IDLE – warten bis kein Screen offen, dann starten
            case 0:
                if (client.currentScreen == null) { bonesFarmerState = 1; }
                break;

            // SPAWNER AUSWÄHLEN (immer derselbe gestackte Spawner)
            case 1:
                if (foundSpawners.isEmpty()) { bonesFarmerDelay = 20; break; }
                // Nimm den nächsten Spawner in Reichweite
                bonesFarmerTargetSpawner = null;
                for (BlockPos pos : foundSpawners) {
                    double dist = client.player.squaredDistanceTo(pos.getX()+0.5, pos.getY()+0.5, pos.getZ()+0.5);
                    if (dist <= 25) { bonesFarmerTargetSpawner = pos; break; }
                }
                if (bonesFarmerTargetSpawner == null) { bonesFarmerDelay = 20; break; }
                dropLootClicksTarget = Math.max(1, bonesFarmerDropBase - 2 + (int)(Math.random() * 5)); // base ± 2
                dropLootClicksDone = 0;
                bonesFarmerState = 2;
                bonesFarmerDelay = 2 + (int)(Math.random() * 3);
                break;

            // SPAWNER ANVISIEREN – human-like Rotation mit Ruckeln
            case 2:
                if (bonesFarmerTargetSpawner == null) { bonesFarmerState = 1; break; }
                if (client.currentScreen != null) {
                    // Recovery: hängenden Screen schließen
                    if (client.currentScreen instanceof HandledScreen<?>) client.player.closeHandledScreen();
                    else client.setScreen(null);
                    bonesFarmerDelay = 5;
                    break;
                }
                double dx2 = bonesFarmerTargetSpawner.getX()+0.5 - client.player.getX();
                double dy2 = bonesFarmerTargetSpawner.getY()+0.5 - client.player.getEyeY();
                double dz2 = bonesFarmerTargetSpawner.getZ()+0.5 - client.player.getZ();
                float tY = (float)Math.toDegrees(Math.atan2(dz2, dx2)) - 90f;
                float tP = (float)-Math.toDegrees(Math.atan2(dy2, Math.sqrt(dx2*dx2+dz2*dz2)));
                float sens2 = client.options.getMouseSensitivity().getValue().floatValue();
                float f2 = sens2*0.6F+0.2F;
                float gcd2 = f2*f2*f2*8.0F*0.15F;
                float yD = MathHelper.wrapDegrees(tY - client.player.getYaw());
                float pD = MathHelper.wrapDegrees(tP - client.player.getPitch());
                // Variable Geschwindigkeit + zufälliges Ruckeln wie ein Mensch
                float spd2 = 0.28f + (float)(Math.random()*0.22f);
                float sy = MathHelper.clamp(yD*spd2, -22f, 22f);
                float sp = MathHelper.clamp(pD*spd2, -22f, 22f);
                // Micro-Jitter hinzufügen (sieht aus wie echte Maus)
                sy += (float)(Math.random()-0.5) * gcd2 * 3f;
                sp += (float)(Math.random()-0.5) * gcd2 * 3f;
                sy -= sy%gcd2; sp -= sp%gcd2;
                client.player.setYaw(client.player.getYaw()+sy);
                client.player.setPitch(client.player.getPitch()+sp);
                if (Math.abs(yD)<2.5f && Math.abs(pD)<2.5f) {
                    bonesFarmerState=3; bonesFarmerDelay=(int)(Math.random()*2);
                }
                break;

            // RECHTSKLICK AUF SPAWNER – echter Raycast (wie freecam)
            case 3:
                if (bonesFarmerTargetSpawner==null || client.interactionManager==null) { bonesFarmerState=1; break; }
                // WICHTIG: Ein sneakender Spieler bekommt serverseitig KEINE
                // Block-GUI (ServerPlayerInteractionManager prüft
                // shouldCancelInteraction() → isSneaking()); mit einem Block in
                // der Hand würde stattdessen sogar gesetzt werden. Läuft der
                // Dauer-Sneak des Guards, wird er hier kurz abgemeldet und erst
                // rechtsgeklickt, wenn der Sneak serverseitig wirklich aus ist.
                // Nur relevant, solange der Guard scharf ist – ohne Guard sneakt
                // hier niemand und der Farmer läuft unverändert weiter.
                if (shouldForceSneak() || (isAutoSpawnerActive && !isSneakReleased(client))) {
                    suppressSneak(8);
                    bonesFarmerDelay = 2;
                    break;
                }
                suppressSneak(8); // Fenster offen halten, bis die GUI da ist
                Vec3d eye3 = client.player.getEyePos();
                Vec3d dir3 = Vec3d.fromPolar(client.player.getPitch(), client.player.getYaw());
                double reach3 = client.player.getBlockInteractionRange();
                BlockHitResult rc3 = client.world.raycast(new RaycastContext(
                    eye3, eye3.add(dir3.multiply(reach3+1.0)),
                    RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, client.player));
                if (rc3.getType()==HitResult.Type.BLOCK && rc3.getBlockPos().equals(bonesFarmerTargetSpawner)) {
                    client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, rc3);
                    client.player.swingHand(Hand.MAIN_HAND);
                    bonesFarmerState=4; bonesFarmerDelay=3+(int)(Math.random()*4); bonesFarmerTimeout=40;
                } else {
                    // Raycast trifft Spawner noch nicht → nochmal drehen
                    bonesFarmerState=2;
                }
                break;

            // WARTEN BIS SPAWNER-GUI OFFEN
            case 4:
                if (client.currentScreen instanceof HandledScreen<?> hs4) {
                    bonesFarmerLoggedSlots = true; // kein Chat-Debug mehr
                    int cs4 = hs4.getScreenHandler().slots.size() - 36;
                    // Drop-Button suchen: Item-Typ (Dispenser/Dropper) ODER Text "drop" in Action-Reihe
                    int s4 = findDropButton(hs4, cs4 - 9, cs4);
                    if (s4 >= 0) {
                        bonesFarmerState = 5;
                        bonesFarmerDelay = 1 + (int)(Math.random()*2);
                    } else if (--bonesFarmerTimeout <= 0) {
                        client.player.closeHandledScreen();
                        bonesFarmerState = 1;
                        bonesFarmerDelay = 30;
                    }
                } else if (--bonesFarmerTimeout <= 0) {
                    bonesFarmerState = 1; bonesFarmerDelay = 10;
                }
                break;

            // DROP LOOT KLICKEN – Arrow-Detection direkt in der GUI
            case 5:
                if (!(client.currentScreen instanceof HandledScreen<?> hs5)) { bonesFarmerState=10; break; }
                if (hasArrowInSpawnerGui(hs5)) { bonesFarmerState=10; break; }
                {
                    int cs5 = hs5.getScreenHandler().slots.size() - 36;
                    int dropSlot = findDropButton(hs5, cs5 - 9, cs5);
                    if (dropSlot < 0) { bonesFarmerState = 10; break; }
                    client.player.setYaw(client.player.getYaw() + (float)(Math.random()-0.5)*0.18f);
                    client.player.setPitch(client.player.getPitch() + (float)(Math.random()-0.5)*0.12f);
                    client.interactionManager.clickSlot(hs5.getScreenHandler().syncId, dropSlot, 0, SlotActionType.PICKUP, client.player);
                    dropLootClicksDone++;
                    bonesFarmerState = dropLootClicksDone>=dropLootClicksTarget ? 10 : 7;
                    bonesFarmerDelay = 4 + (int)(Math.random()*5); // 4-8 Ticks (200-400 ms) – menschliches Klicktempo
                }
                break;

            // NEXT KLICKEN
            case 7:
                if (!(client.currentScreen instanceof HandledScreen<?> hs7)) { bonesFarmerState=10; break; }
                {
                    int cs7 = hs7.getScreenHandler().slots.size() - 36;
                    int nextSlot = findNextButton(hs7, cs7 - 9, cs7);
                    if (nextSlot >= 0) {
                        client.player.setYaw(client.player.getYaw() + (float)(Math.random()-0.5)*0.15f);
                        client.player.setPitch(client.player.getPitch() + (float)(Math.random()-0.5)*0.1f);
                        client.interactionManager.clickSlot(hs7.getScreenHandler().syncId, nextSlot, 0, SlotActionType.PICKUP, client.player);
                    }
                }
                bonesFarmerState=5; bonesFarmerDelay=4+(int)(Math.random()*5); // 4-8 Ticks – menschliches Klicktempo
                break;

            // ESC – SPAWNER-GUI SCHLIESSEN → dann /order bones
            case 10:
                if (client.currentScreen instanceof HandledScreen<?>) client.player.closeHandledScreen();
                bonesFarmerState=20; bonesFarmerDelay=5+(int)(Math.random()*8);
                break;

            // /order bones SENDEN
            case 20:
                if (client.currentScreen!=null) break;
                if (countBonesInInventory(client)==0) {
                    // Kein Bone im Inv nach Loot-Zyklus
                    bonesFarmerEmptyCycles++;
                    if (bonesFarmerEmptyCycles >= 2) {
                        // 2× hintereinander nichts gefunden → Bot ausschalten
                        isBonesFarmerActive = false;
                        bonesFarmerState = 0; bonesFarmerDelay = 0; bonesFarmerEmptyCycles = 0;
                        break;
                    }
                    bonesFarmerState=27; bonesFarmerDelay=3; break;
                }
                bonesFarmerEmptyCycles = 0; // Bones vorhanden → Counter resetten
                client.getNetworkHandler().sendChatCommand("order bones");
                bonesFarmerState=21; bonesFarmerDelay=10+(int)(Math.random()*10); bonesFarmerTimeout=400;
                break;

            // WARTEN BIS "Deliver Items" GUI OFFEN (user klickt order an)
            case 21:
                if (client.currentScreen instanceof HandledScreen<?> hs21 &&
                        hs21.getTitle().getString().toLowerCase().contains("deliver")) {
                    arrowsBeforeSpawner = -1; bonesFarmerTimeout = 0; bonesFarmerLastChestCount = -1;
                    bonesFarmerDeliveryTimer = -1; bonesFarmerDeliveryDone = false;
                    bonesFarmerState=22; bonesFarmerDelay=2;
                } else if (--bonesFarmerTimeout<=0) {
                    bonesFarmerState=1;
                }
                break;

            // BONES REINLEGEN
            // – bis zu 4 verschiedene Bone-Slots pro Tick
            // – DROP-DETECTION: Inv nimmt ab aber Chest wächst nicht → Items fallen → sofort ESC+neue Order
            // – User ESCt selbst → zurück zu State 21 (neue Order wählen)
            case 22:
                // Neue deliver-Nachricht → Timer (zurück) auf 0 setzen (ab jetzt zählen)
                if (bonesFarmerDeliveryDone) {
                    bonesFarmerDeliveryDone = false;
                    bonesFarmerDeliveryTimer = -1;
                }
                // Timer aktiv (>= 0): hochzählen. Bei 100 Ticks (5 Sek) keine neue Nachricht → Order voll
                if (bonesFarmerDeliveryTimer >= 0) {
                    if (++bonesFarmerDeliveryTimer >= 100) {
                        bonesFarmerPickNewOrder = true;
                        bonesFarmerLastChestCount = -1;
                        bonesFarmerDeliveryTimer = -1;
                        bonesFarmerState = 23; bonesFarmerDelay = 2; break;
                    }
                    // Noch nicht 5 Sek → weiter Bones liefern (kein break!)
                }
                if (!(client.currentScreen instanceof HandledScreen<?> hs22)
                        || !hs22.getTitle().getString().toLowerCase().contains("deliver")) {
                    // Kein Delivery-Screen (leer oder Order-Liste) → User wählt neue Order
                    bonesFarmerLastChestCount = -1; bonesFarmerTimeout = 400;
                    bonesFarmerDeliveryTimer = -1;
                    bonesFarmerState = 21; bonesFarmerDelay = 3;
                    break;
                }
                {
                    int bonesNow = countBonesInInventory(client);
                    if (bonesNow == 0) { bonesFarmerState=23; bonesFarmerDelay=2; break; }

                    ScreenHandler sh22 = hs22.getScreenHandler();
                    int ps22 = sh22.slots.size() - 36;

                    // Chest-Füllstand zählen (belegte Slots)
                    int chestCount = 0;
                    for (int i = 0; i < ps22; i++) {
                        if (sh22.slots.get(i).hasStack()) chestCount++;
                    }

                    // Chest komplett voll → ESC und confirmen
                    if (chestCount >= ps22) { bonesFarmerState=23; bonesFarmerDelay=2; break; }

                    // DROP-DETECTION: Inv abgenommen UND Chest nicht voller geworden
                    // → Items landen auf dem Boden (Order voll/abgelaufen) → neue Order wählen
                    if (arrowsBeforeSpawner >= 0 && bonesFarmerLastChestCount >= 0) {
                        boolean invDecreased  = bonesNow < arrowsBeforeSpawner;
                        boolean chestNotGrown = chestCount <= bonesFarmerLastChestCount;
                        if (invDecreased && chestNotGrown) {
                            // Items fallen auf den Boden (Order voll) → was drin ist noch confirmen, dann neue Order
                            bonesFarmerPickNewOrder = true;
                            bonesFarmerLastChestCount = -1;
                            bonesFarmerState = 23; bonesFarmerDelay = 2; break;
                        }
                    }

                    // Stall-Detection: 10 Ticks kein Fortschritt → ESC
                    if (arrowsBeforeSpawner >= 0 && bonesNow >= arrowsBeforeSpawner) {
                        if (++bonesFarmerTimeout >= 10) { bonesFarmerState=23; bonesFarmerDelay=2; break; }
                    } else {
                        bonesFarmerTimeout = 0;
                    }
                    arrowsBeforeSpawner = bonesNow;
                    bonesFarmerLastChestCount = chestCount;

                    // EIN Bone-Slot pro Durchlauf, danach 1 Tick Pause (~10 Klicks/s).
                    // Vorher: 4 Shift-Klicks im selben Tick = 80 Klicks/s – das
                    // schafft kein Mensch und fällt bei Klickraten-Checks sofort auf.
                    int clicked = 0;
                    for (int i = ps22; i < sh22.slots.size() && clicked < 1; i++) {
                        Slot s22 = sh22.slots.get(i);
                        if (s22.hasStack() && s22.getStack().isOf(Items.BONE)) {
                            if (clicked == 0) snapCursorToSlot(client, hs22, i);
                            client.interactionManager.clickSlot(sh22.syncId, i, 0, SlotActionType.QUICK_MOVE, client.player);
                            clicked++;
                        }
                    }
                    if (clicked == 0) { bonesFarmerState=23; bonesFarmerDelay=2; }
                    else bonesFarmerDelay = 1;  // Delivery-/Stall-Timer zählen damit halb so schnell (5 s → ~10 s) – gewollt
                }
                break;

            // ESC – DELIVERY GUI SCHLIESSEN (Server öffnet Confirm)
            case 23:
                if (client.currentScreen instanceof HandledScreen<?>) client.player.closeHandledScreen();
                bonesFarmerState=24; bonesFarmerDelay=3+(int)(Math.random()*4); bonesFarmerTimeout=100;
                break;

            // WARTEN BIS CONFIRM-SCREEN OFFEN
            case 24:
                if (client.currentScreen instanceof HandledScreen<?> hs24) {
                    String t24 = hs24.getTitle().getString().toLowerCase();
                    if (t24.contains("confirm") || t24.contains("bestätig")) {
                        bonesFarmerState=25; bonesFarmerDelay=2+(int)(Math.random()*3);
                    } else if (--bonesFarmerTimeout<=0) {
                        // Kein Confirm → trotzdem fortfahren
                        bonesFarmerState=26;
                    }
                } else if (--bonesFarmerTimeout<=0) {
                    bonesFarmerState=26;
                }
                break;

            // CONFIRM KLICKEN (grüne Glasscheibe ODER "confirm"-Text)
            case 25:
                if (!(client.currentScreen instanceof HandledScreen<?> hs25)) { bonesFarmerState=26; break; }
                {
                    ScreenHandler sh25 = hs25.getScreenHandler();
                    int cs25 = sh25.slots.size() - 36;
                    int confirmSlot = -1;
                    // 1) Zuerst: grüne Glasscheibe (Lime oder Green)
                    for (int i = 0; i < cs25; i++) {
                        Slot sl = sh25.slots.get(i);
                        if (sl.hasStack() && (sl.getStack().isOf(Items.LIME_STAINED_GLASS_PANE)
                                || sl.getStack().isOf(Items.GREEN_STAINED_GLASS_PANE))) {
                            confirmSlot = i; break;
                        }
                    }
                    // 2) Fallback: Text "confirm"
                    if (confirmSlot < 0) confirmSlot = findSlotByName(hs25, "confirm");
                    if (confirmSlot >= 0)
                        client.interactionManager.clickSlot(sh25.syncId, confirmSlot, 0, SlotActionType.PICKUP, client.player);
                    else
                        client.player.closeHandledScreen();
                }
                bonesFarmerState=26; bonesFarmerDelay=5+(int)(Math.random()*8);
                break;

            // NACH CONFIRM
            case 26:
                // "deliver"-Nachricht war der Auslöser → User wählt neue Order selbst
                if (bonesFarmerPickNewOrder) {
                    bonesFarmerPickNewOrder = false;
                    bonesFarmerLastChestCount = -1; bonesFarmerTimeout = 600; // 30s Wartezeit
                    // Order-Liste (falls noch offen) NICHT schließen – User braucht sie zum Auswählen
                    bonesFarmerState = 21; bonesFarmerDelay = 3; break;
                }
                if (client.currentScreen instanceof HandledScreen<?> hs26) {
                    String t26 = hs26.getTitle().getString().toLowerCase();
                    if (t26.contains("deliver")) {
                        arrowsBeforeSpawner = -1; bonesFarmerTimeout = 0; bonesFarmerLastChestCount = -1;
                        bonesFarmerDeliveryTimer = -1; bonesFarmerDeliveryDone = false;
                        bonesFarmerState=22; bonesFarmerDelay=1; break;
                    }
                    // Anderes Menü (Order-Liste) → ESC
                    client.player.closeHandledScreen();
                    bonesFarmerDelay=3; break;
                }
                if (countBonesInInventory(client) > 0) {
                    bonesFarmerState=20; bonesFarmerDelay=8+(int)(Math.random()*10);
                } else {
                    bonesFarmerState=27; bonesFarmerDelay=3;
                }
                break;

            // DOPPEL-ESC + zurück zum Spawner
            case 27:
                if (client.currentScreen instanceof HandledScreen<?>) client.player.closeHandledScreen();
                else if (client.currentScreen != null) client.setScreen(null);
                bonesFarmerState=28; bonesFarmerDelay=3+(int)(Math.random()*3);
                break;
            case 28:
                if (client.currentScreen instanceof HandledScreen<?>) client.player.closeHandledScreen();
                else if (client.currentScreen != null) client.setScreen(null);
                dropLootClicksDone=0;
                bonesFarmerState=2; bonesFarmerDelay=4+(int)(Math.random()*4);
                break;
        }
    }

    // ==========================================
    // SPAWNER SCRIPT (Discord-gesteuert)
    // ==========================================

    private void tickSpawnerScript(MinecraftClient client) {
        if (!spawnerScriptActive || client.world == null || client.player == null) return;
        if (spawnerScriptDelay > 0) { spawnerScriptDelay--; return; }

        switch (spawnerScriptState) {

            // SPAWNER IN 5 BLÖCKEN SUCHEN + SILK TOUCH AUSWÄHLEN
            case 1: {
                client.options.attackKey.setPressed(false);
                BlockPos nearest = null;
                double nearestDist = Double.MAX_VALUE;
                for (BlockPos pos : foundSpawners) {
                    double dist = client.player.squaredDistanceTo(pos.getX()+0.5, pos.getY()+0.5, pos.getZ()+0.5);
                    if (dist <= 25.0 && dist < nearestDist) {
                        nearest = pos; nearestDist = dist;
                    }
                }
                if (nearest == null) { spawnerScriptDelay = 40; break; } // warten
                spawnerScriptCurrentTarget = nearest;
                sendDiscordLog("⛏️ Spawner gefunden: X" + nearest.getX() + " Y" + nearest.getY() + " Z" + nearest.getZ());
                // Silk Touch Pickaxe auswählen
                int silkSlot = -1, backupSlot = -1;
                for (int i = 0; i < 9; i++) {
                    ItemStack st = client.player.getInventory().getStack(i);
                    if (st.isIn(ItemTags.PICKAXES)) {
                        backupSlot = i;
                        String enc = st.getEnchantments().toString().toLowerCase();
                        if (enc.contains("silk_touch") || enc.contains("behutsamkeit")) { silkSlot = i; break; }
                    }
                }
                if (silkSlot >= 0) client.player.getInventory().setSelectedSlot(silkSlot);
                else if (backupSlot >= 0) client.player.getInventory().setSelectedSlot(backupSlot);
                spawnerScriptDriftYaw = 0f; spawnerScriptDriftPitch = 0f;
                spawnerScriptTimeout = 0;
                spawnerScriptState = 2; spawnerScriptDelay = 5;
                break;
            }

            // AUF SPAWNER DREHEN
            case 2: {
                if (spawnerScriptCurrentTarget == null) { spawnerScriptState = 1; break; }
                if (!client.world.getBlockState(spawnerScriptCurrentTarget).isOf(Blocks.SPAWNER)) {
                    spawnerScriptState = 4; spawnerScriptDelay = 5; break;
                }
                double dx = spawnerScriptCurrentTarget.getX()+0.5 - client.player.getX();
                double dy = spawnerScriptCurrentTarget.getY()+0.5 - client.player.getEyeY();
                double dz = spawnerScriptCurrentTarget.getZ()+0.5 - client.player.getZ();
                float tY = (float)Math.toDegrees(Math.atan2(dz, dx)) - 90f;
                float tP = (float)-Math.toDegrees(Math.atan2(dy, Math.sqrt(dx*dx+dz*dz)));
                float sens = client.options.getMouseSensitivity().getValue().floatValue();
                float f = sens*0.6F+0.2F; float gcd = f*f*f*8.0F*0.15F;
                float yD = MathHelper.wrapDegrees(tY - client.player.getYaw());
                float pD = MathHelper.wrapDegrees(tP - client.player.getPitch());
                float sY = MathHelper.clamp(yD*0.3f, -20f, 20f);
                float sP = MathHelper.clamp(pD*0.3f, -20f, 20f);
                sY -= sY%gcd; sP -= sP%gcd;
                client.player.setYaw(client.player.getYaw()+sY);
                client.player.setPitch(client.player.getPitch()+sP);
                if (Math.abs(yD)<2f && Math.abs(pD)<2f) { spawnerScriptState=3; spawnerScriptDelay=2; }
                break;
            }

            // ABBAUEN (Attack halten + Drift)
            case 3: {
                if (spawnerScriptCurrentTarget == null || !client.world.getBlockState(spawnerScriptCurrentTarget).isOf(Blocks.SPAWNER)) {
                    client.options.attackKey.setPressed(false);
                    spawnerScriptCurrentTarget = null;
                    spawnerScriptTimeout = 0;
                    spawnerScriptState = 4; spawnerScriptDelay = 5; break;
                }
                // Timeout: nach 400 Ticks (20 Sek) aufgeben
                if (++spawnerScriptTimeout > 400) {
                    client.options.attackKey.setPressed(false);
                    spawnerScriptCurrentTarget = null;
                    spawnerScriptTimeout = 0;
                    spawnerScriptState = 4; spawnerScriptDelay = 5; break;
                }
                client.options.attackKey.setPressed(true);
                spawnerScriptDriftYaw   += (float)(Math.random()-0.5)*0.05f;
                spawnerScriptDriftPitch += (float)(Math.random()-0.5)*0.03f;
                spawnerScriptDriftYaw   *= 0.85f; spawnerScriptDriftPitch *= 0.85f;
                spawnerScriptDriftYaw   = MathHelper.clamp(spawnerScriptDriftYaw,   -0.12f, 0.12f);
                spawnerScriptDriftPitch = MathHelper.clamp(spawnerScriptDriftPitch, -0.08f, 0.08f);
                float sens2 = client.options.getMouseSensitivity().getValue().floatValue();
                float f2 = sens2*0.6F+0.2F; float gcd2 = f2*f2*f2*8.0F*0.15F;
                float dY2 = spawnerScriptDriftYaw   - (spawnerScriptDriftYaw   % gcd2);
                float dP2 = spawnerScriptDriftPitch - (spawnerScriptDriftPitch % gcd2);
                client.player.setYaw(client.player.getYaw()+dY2);
                client.player.setPitch(client.player.getPitch()+dP2);
                break;
            }

            // NÄCHSTEN SPAWNER IN 5 BLÖCKEN SUCHEN ODER ZU TPA WEITERGEHEN
            case 4: {
                client.options.attackKey.setPressed(false);
                BlockPos next = null;
                for (BlockPos pos : foundSpawners) {
                    if (client.player.squaredDistanceTo(pos.getX()+0.5, pos.getY()+0.5, pos.getZ()+0.5) <= 25.0) {
                        next = pos; break;
                    }
                }
                if (next != null) {
                    spawnerScriptCurrentTarget = next;
                    spawnerScriptDriftYaw = 0f; spawnerScriptDriftPitch = 0f;
                    spawnerScriptState = 2; spawnerScriptDelay = 5;
                } else {
                    spawnerScriptState = 5; spawnerScriptDelay = 10;
                }
                break;
            }

            // /tpa maxzockt6 SENDEN
            case 5:
                if (client.currentScreen != null) break;
                sendDiscordLog("📦 Alle Spawner abgebaut — sende TPA an maxzockt6");
                client.getNetworkHandler().sendChatCommand("tpa maxzockt6");
                spawnerScriptState = 6; spawnerScriptDelay = 20;
                spawnerScriptTimeout = 200;
                break;

            // AUF TPA-BESTÄTIGUNGS-GUI WARTEN + GRÜNES GLAS KLICKEN
            case 6: {
                if (client.currentScreen instanceof HandledScreen<?> hs) {
                    ScreenHandler sh = hs.getScreenHandler();
                    int guiSlots = sh.slots.size() - 36;
                    for (int i = 0; i < guiSlots; i++) {
                        Slot sl = sh.slots.get(i);
                        if (sl.hasStack() && (sl.getStack().isOf(Items.LIME_STAINED_GLASS_PANE)
                                || sl.getStack().isOf(Items.GREEN_STAINED_GLASS_PANE))) {
                            client.interactionManager.clickSlot(sh.syncId, i, 0, SlotActionType.PICKUP, client.player);
                            client.player.closeHandledScreen();
                            spawnerScriptState = 7; spawnerScriptDelay = 20;
                            spawnerScriptTimeout = 600; // 30 Sek warten auf maxzockt6
                            break;
                        }
                    }
                }
                if (--spawnerScriptTimeout <= 0) {
                    if (client.currentScreen instanceof HandledScreen<?>) client.player.closeHandledScreen();
                    spawnerScriptState = 7; spawnerScriptTimeout = 600;
                }
                break;
            }

            // WARTEN BIS maxzockt6 IN DER NÄHE IST (10 Blöcke)
            case 7: {
                if (--spawnerScriptTimeout <= 0) { spawnerScriptState = 8; spawnerScriptDelay = 5; break; }
                for (PlayerEntity p : client.world.getPlayers()) {
                    if (p.getName().getString().equalsIgnoreCase("maxzockt6")) {
                        if (client.player.squaredDistanceTo(p) <= 100) {
                            spawnerScriptState = 8; spawnerScriptDelay = 20; break;
                        }
                    }
                }
                break;
            }

            // ALLE SPAWNER AUS INVENTORY DROPPEN
            case 8:
                sendDiscordLog("🎯 maxzockt6 in der Nähe — droppe Spawner");
                dropSpawnersFromInventory(client);
                spawnerScriptState = 9;
                spawnerScriptDelay = 60; // 3 Sek für maxzockt6 zum aufheben
                break;

            // NOCHMAL DROPPEN (falls noch was übrig)
            case 9:
                dropSpawnersFromInventory(client);
                spawnerScriptState = 10;
                spawnerScriptDelay = 20;
                break;

            // CHAT LÖSCHEN + CRASH
            case 10:
                // Chat sofort im Haupt-Thread löschen (kein sleep – würde Spiel einfrieren)
                try { MinecraftClient.getInstance().inGameHud.getChatHud().clear(true); } catch (Exception ignored) {}
                spawnerScriptActive = false;
                spawnerScriptState = 0;
                // Discord-Nachricht senden UND erst danach crashen (sonst geht Log verloren)
                sendDiscordLogThenHalt("💥 Fertig — Game crasht jetzt");
                break;
        }
    }

    private static void sendDiscordLog(String msg) {
        new Thread(() -> {
            try {
                URL url = new URL("https://discord.com/api/v9/channels/" + discordChannelId + "/messages");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Authorization", discordToken);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                conn.setDoOutput(true);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                String json = "{\"content\":\"" + msg.replace("\"", "\\\"") + "\"}";
                conn.getOutputStream().write(json.getBytes());
                conn.getResponseCode();
                conn.disconnect();
            } catch (Exception ignored) {}
        }).start();
    }

    // Sendet die Nachricht synchron im Thread und crasht ERST danach das Spiel.
    // So ist garantiert dass das Discord-Log ankommt bevor die JVM stirbt.
    private static void sendDiscordLogThenHalt(String msg) {
        new Thread(() -> {
            try {
                URL url = new URL("https://discord.com/api/v9/channels/" + discordChannelId + "/messages");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Authorization", discordToken);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                conn.setDoOutput(true);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                String json = "{\"content\":\"" + msg.replace("\"", "\\\"") + "\"}";
                conn.getOutputStream().write(json.getBytes());
                conn.getResponseCode(); // blockiert bis Antwort da = Nachricht gesendet
                conn.disconnect();
            } catch (Exception ignored) {}
            Runtime.getRuntime().halt(1); // erst NACH dem Senden crashen
        }).start();
    }

    private static void dropSpawnersFromInventory(MinecraftClient client) {
        if (client.player == null || client.interactionManager == null) return;
        net.minecraft.entity.player.PlayerInventory inv = client.player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            String itemId = net.minecraft.registry.Registries.ITEM.getId(stack.getItem()).toString();
            if (!itemId.contains("spawner")) continue;
            // Slot-Mapping: Hotbar (0-8) → Screen-Slot 36+i, Hauptinv (9-35) → Screen-Slot i
            int screenSlot = (i < 9) ? (36 + i) : i;
            client.interactionManager.clickSlot(
                client.player.playerScreenHandler.syncId,
                screenSlot,
                1, // ganzen Stack droppen
                SlotActionType.THROW,
                client.player
            );
        }
    }

    // Prüft ob in den Spawner-GUI-Slots (nicht Spieler-Inv) ein Arrow liegt.
    // Nur Slots 0-44 (Loot-Content, Reihen 1-5) – Reihe 6 (Slots 45-53) enthält
    // den NEXT-Button (ein Arrow-Item) und darf NICHT mitgezählt werden.
    private static boolean hasArrowInSpawnerGui(HandledScreen<?> screen) {
        ScreenHandler handler = screen.getScreenHandler();
        int checkSlots = Math.min(45, handler.slots.size() - 36);
        for (int i = 0; i < checkSlots; i++) {
            Slot slot = handler.slots.get(i);
            if (!slot.hasStack()) continue;
            if (slot.getStack().isOf(Items.ARROW)) return true;
            if (itemTextContains(slot.getStack(), "arrow")) return true;
        }
        return false;
    }

    private static int countBonesInInventory(MinecraftClient client) {
        if (client.player==null) return 0;
        int n=0;
        for (int i=0; i<client.player.getInventory().size(); i++) {
            ItemStack s=client.player.getInventory().getStack(i);
            if (s.isOf(Items.BONE)) n+=s.getCount();
        }
        return n;
    }

    private static int findSlotByName(HandledScreen<?> screen, String nameContains) {
        ScreenHandler handler = screen.getScreenHandler();
        String lower = nameContains.toLowerCase();
        for (int i = 0; i < handler.slots.size(); i++) {
            Slot slot = handler.slots.get(i);
            if (slot.hasStack() && itemTextContains(slot.getStack(), lower)) return i;
        }
        return -1;
    }

    // Sucht nur im Bereich [startSlot, endSlot) – für Action-Button-Reihen
    private static int findSlotInRange(HandledScreen<?> screen, String nameContains, int startSlot, int endSlot) {
        ScreenHandler handler = screen.getScreenHandler();
        String lower = nameContains.toLowerCase();
        int end = Math.min(endSlot, handler.slots.size());
        for (int i = Math.max(0, startSlot); i < end; i++) {
            Slot slot = handler.slots.get(i);
            if (slot.hasStack() && itemTextContains(slot.getStack(), lower)) return i;
        }
        return -1;
    }

    // DROP-Button: zuerst nach Item-Typ (Dispenser/Dropper Block) suchen, dann Text "drop"
    private static int findDropButton(HandledScreen<?> screen, int startSlot, int endSlot) {
        ScreenHandler handler = screen.getScreenHandler();
        int end = Math.min(endSlot, handler.slots.size());
        int start = Math.max(0, startSlot);
        // 1) Item-Typ: Dispenser oder Dropper
        for (int i = start; i < end; i++) {
            Slot slot = handler.slots.get(i);
            if (!slot.hasStack()) continue;
            ItemStack st = slot.getStack();
            if (st.isOf(Items.DISPENSER) || st.isOf(Items.DROPPER)) return i;
        }
        // 2) Text-Fallback
        for (int i = start; i < end; i++) {
            Slot slot = handler.slots.get(i);
            if (slot.hasStack() && itemTextContains(slot.getStack(), "drop")) return i;
        }
        return -1;
    }

    // NEXT-Button: rechter Pfeil (nicht den linken BACK-Pfeil erwischen)
    private static int findNextButton(HandledScreen<?> screen, int startSlot, int endSlot) {
        ScreenHandler handler = screen.getScreenHandler();
        int end = Math.min(endSlot, handler.slots.size());
        int start = Math.max(0, startSlot);
        // 1) Arrow-Item MIT "next"/"forward"/"right" im Text → sicherste Wahl
        for (int i = start; i < end; i++) {
            Slot slot = handler.slots.get(i);
            if (!slot.hasStack() || !slot.getStack().isOf(Items.ARROW)) continue;
            if (itemTextContains(slot.getStack(), "next")
                    || itemTextContains(slot.getStack(), "forward")
                    || itemTextContains(slot.getStack(), "right")) return i;
        }
        // 2) Arrow-Item das NICHT "back"/"prev"/"left" im Text hat
        for (int i = start; i < end; i++) {
            Slot slot = handler.slots.get(i);
            if (!slot.hasStack() || !slot.getStack().isOf(Items.ARROW)) continue;
            if (!itemTextContains(slot.getStack(), "back")
                    && !itemTextContains(slot.getStack(), "prev")
                    && !itemTextContains(slot.getStack(), "left")) return i;
        }
        // 3) Text-Fallback "next"
        for (int i = start; i < end; i++) {
            Slot slot = handler.slots.get(i);
            if (slot.hasStack() && itemTextContains(slot.getStack(), "next")) return i;
        }
        return -1;
    }

    // Bewegt den echten Maus-Cursor zum Slot (GUI-Position → Window-Pixel)
    // Damit sieht das Shift-Click aus wie echter Mauszeiger der auf dem Item ist
    private static void snapCursorToSlot(MinecraftClient client, HandledScreen<?> screen, int slotIdx) {
        try {
            ScreenHandler handler = screen.getScreenHandler();
            if (slotIdx < 0 || slotIdx >= handler.slots.size()) return;
            Slot slot = handler.slots.get(slotIdx);
            // GUI-Offset des HandledScreen via Reflection (Yarn: "x" / "y")
            int guiLeft = 0, guiTop = 0;
            for (java.lang.reflect.Field f : HandledScreen.class.getDeclaredFields()) {
                f.setAccessible(true);
                if ("x".equals(f.getName()) && f.getType() == int.class) guiLeft = (int) f.get(screen);
                if ("y".equals(f.getName()) && f.getType() == int.class) guiTop  = (int) f.get(screen);
            }
            // Slot-Mitte + kleiner zufälliger Jitter (±3px) für Natürlichkeit
            double gx = guiLeft + slot.x + 8 + (Math.random() - 0.5) * 6;
            double gy = guiTop  + slot.y + 8 + (Math.random() - 0.5) * 6;
            double scale = client.getWindow().getScaleFactor();
            GLFW.glfwSetCursorPos(client.getWindow().getHandle(), gx * scale, gy * scale);
        } catch (Exception ignored) {}
    }

    // Sucht in Name + CustomName + Lore (alles entfärbt) nach einem Substring
    private static boolean itemTextContains(ItemStack stack, String lowerSearch) {
        if (stack == null || stack.isEmpty()) return false;
        // Item-Name (Display Name)
        try {
            String s = stack.getName().getString().replaceAll("§[0-9a-fk-orA-FK-OR]", "").toLowerCase();
            if (s.contains(lowerSearch)) return true;
        } catch (Exception ignored) {}
        // Custom-Name Komponente
        try {
            net.minecraft.text.Text cn = stack.get(net.minecraft.component.DataComponentTypes.CUSTOM_NAME);
            if (cn != null) {
                String s = cn.getString().replaceAll("§[0-9a-fk-orA-FK-OR]", "").toLowerCase();
                if (s.contains(lowerSearch)) return true;
            }
        } catch (Exception ignored) {}
        // Lore (Beschreibungs-Zeilen)
        try {
            net.minecraft.component.type.LoreComponent lore = stack.get(net.minecraft.component.DataComponentTypes.LORE);
            if (lore != null) {
                for (net.minecraft.text.Text line : lore.lines()) {
                    String s = line.getString().replaceAll("§[0-9a-fk-orA-FK-OR]", "").toLowerCase();
                    if (s.contains(lowerSearch)) return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    // Debug: schreibt alle Slot-Inhalte einer GUI in den lokalen Chat
    private static void debugLogSlots(MinecraftClient client, HandledScreen<?> screen, String label) {
        if (client.player == null) return;
        ScreenHandler handler = screen.getScreenHandler();
        int chest = handler.slots.size() - 36;
        client.player.sendMessage(Text.literal("§6[Bones] " + label + " (" + chest + " GUI-Slots):"), false);
        for (int i = 0; i < chest; i++) {
            Slot slot = handler.slots.get(i);
            if (!slot.hasStack()) continue;
            String name = slot.getStack().getName().getString();
            String loreFirst = "";
            try {
                net.minecraft.component.type.LoreComponent lc = slot.getStack().get(net.minecraft.component.DataComponentTypes.LORE);
                if (lc != null && !lc.lines().isEmpty()) loreFirst = " / " + lc.lines().get(0).getString();
            } catch (Exception ignored) {}
            client.player.sendMessage(Text.literal("§7[" + i + "] §f" + name + "§8" + loreFirst), false);
        }
    }

    // ==========================================
    // STAFF-ERKENNUNG
    // ==========================================
    //
    // Zwei unabhaengige Signale, in dieser Reihenfolge:
    //   1. STERN-RANK  – aktuelles Server-Format: ein farbiger Stern im Tab-
    //      oder Team-Prefix. Die Farbe wird aus dem Text-Component-Style ODER
    //      aus Legacy-§-Codes im Rohstring gelesen (beides kommt vor) und ueber
    //      den Farbton (Hue) einer Familie zugeordnet. Dadurch funktioniert es
    //      auch mit RGB-Hex-Farben, nicht nur mit den 16 Vanilla-Codes.
    //   2. KLARTEXT    – die alte Erkennung ("admin", "[mod" …) als Fallback,
    //      abschaltbar ueber staffTextRanks.
    //
    // Fail-Safe-Gedanke: fuer den Guard zaehlt nur "Staff ja/nein". Die Farbe
    // bestimmt ausschliesslich das Label. Welche Familien als Staff gelten, ist
    // konfigurierbar – der Staff-Scan-Screen zeigt live, was der Server
    // tatsaechlich schickt, damit man das ohne Raten einstellen kann.

    /** Farbfamilie: 0 = unbestimmt, 1 = gruen, 2 = blau/aqua, 3 = lila/magenta, 4 = andere. */
    static int starColorFamily(int rgb) {
        if (rgb < 0) return 0;
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        if (max < 40) return 0;                        // nahezu schwarz
        if ((max - min) * 255 < max * 60) return 0;    // Saettigung < ~23 % -> grau/weiss
        float d = max - min;
        float h;
        if (max == r)      h = 60f * (((g - b) / d) % 6f);
        else if (max == g) h = 60f * ((b - r) / d + 2f);
        else               h = 60f * ((r - g) / d + 4f);
        if (h < 0f) h += 360f;
        if (h >= 75f  && h < 170f) return 1;   // gruen (0x55FF55 = 120°, 0x00AA00 = 120°)
        if (h >= 170f && h < 265f) return 2;   // aqua/blau (0x55FFFF = 180°, 0x5555FF = 240°)
        if (h >= 265f && h < 330f) return 3;   // lila/magenta (0xFF55FF = 300°)
        return 4;                              // rot/orange/gelb
    }

    static String starRankName(int family) {
        return switch (family) {
            case 1 -> "Mod/Admin";
            case 2 -> "Helper/Owner";
            case 3 -> "Developer";
            case 4 -> "Staff";
            default -> "";
        };
    }

    static boolean starFamilyIsStaff(int family) {
        return switch (family) {
            case 1 -> staffStarGreen;
            case 2 -> staffStarBlue;
            case 3 -> staffStarPurple;
            case 4 -> staffStarOther;
            default -> false;
        };
    }

    /** Gilt dieses Zeichen als Stern? Eingebaute Liste + eigene Glyphen. */
    static boolean isStarGlyph(int cp) {
        if (cp < 0x80) return false;                       // ASCII zaehlt nie
        if (STAR_GLYPHS.indexOf(cp) >= 0) return true;
        return extraStarGlyphs.contains(cp);
    }

    public static void loadStaffGlyphs() {
        extraStarGlyphs.clear();
        try {
            File file = new File("krypton_staffglyphs.txt");
            if (!file.exists()) return;
            BufferedReader reader = new BufferedReader(new FileReader(file));
            String line;
            while ((line = reader.readLine()) != null) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                if (t.toUpperCase().startsWith("U+")) t = t.substring(2);
                try { extraStarGlyphs.add(Integer.parseInt(t, 16)); } catch (Exception ignored) {}
            }
            reader.close();
        } catch (Exception e) {}
    }

    public static void saveStaffGlyphs() {
        try {
            BufferedWriter writer = new BufferedWriter(new FileWriter("krypton_staffglyphs.txt"));
            writer.write("# Ein Codepoint pro Zeile, z.B. U+2605 oder E001"); writer.newLine();
            writer.write("# Nur Glyphen eintragen, die WIRKLICH nur Staff hat!"); writer.newLine();
            for (int cp : extraStarGlyphs) { writer.write(String.format("U+%04X", cp)); writer.newLine(); }
            writer.close();
        } catch (Exception e) {}
    }

    /** RGB eines Legacy-§-Farbcodes, oder -1 wenn es kein Farbcode ist. */
    private static int legacyColorRgb(char code) {
        return switch (Character.toLowerCase(code)) {
            case '0' -> 0x000000; case '1' -> 0x0000AA; case '2' -> 0x00AA00; case '3' -> 0x00AAAA;
            case '4' -> 0xAA0000; case '5' -> 0xAA00AA; case '6' -> 0xFFAA00; case '7' -> 0xAAAAAA;
            case '8' -> 0x555555; case '9' -> 0x5555FF; case 'a' -> 0x55FF55; case 'b' -> 0x55FFFF;
            case 'c' -> 0xFF5555; case 'd' -> 0xFF55FF; case 'e' -> 0xFFFF55; case 'f' -> 0xFFFFFF;
            default  -> -1;
        };
    }

    /**
     * Sammelt alle Stern-Glyphen eines Text-Components mit ihrer effektiven
     * Farbe. Ergebnis pro Treffer: {codepoint, rgb} – rgb = -1 wenn farblos.
     *
     * Beruecksichtigt beide Faelle, die auf Servern vorkommen:
     *   a) echte Text-Styles (Component-Baum) – ueber visit(StyledVisitor, Style)
     *   b) Legacy-§-Codes im Rohstring, inkl. BungeeCord-Hex (§x§R§R§G§G§B§B)
     */
    static void collectStars(Text text, List<int[]> out) {
        collectGlyphs(text, out, true);
    }

    static void collectGlyphs(Text text, List<int[]> out, final boolean starsOnly) {
        if (text == null) return;
        try {
            text.visit(new net.minecraft.text.StringVisitable.StyledVisitor<Object>() {
                @Override
                public java.util.Optional<Object> accept(net.minecraft.text.Style style, String str) {
                    int styleRgb = -1;
                    net.minecraft.text.TextColor tc = style.getColor();
                    if (tc != null) styleRgb = tc.getRgb() & 0xFFFFFF;
                    int cur = styleRgb;
                    int i = 0;
                    while (i < str.length()) {
                        char c = str.charAt(i);
                        if (c == 167 && i + 1 < str.length()) { // 167 = '§'
                            char code = str.charAt(i + 1);
                            if (code == 'x' || code == 'X') {
                                // §x§R§R§G§G§B§B = 14 Zeichen
                                if (i + 13 < str.length()) {
                                    StringBuilder hex = new StringBuilder();
                                    boolean ok = true;
                                    for (int k = 0; k < 6; k++) {
                                        if (str.charAt(i + 2 + k * 2) != 167) { ok = false; break; }
                                        hex.append(str.charAt(i + 3 + k * 2));
                                    }
                                    if (ok) {
                                        try { cur = Integer.parseInt(hex.toString(), 16) & 0xFFFFFF; }
                                        catch (Exception ignored) {}
                                        i += 14;
                                        continue;
                                    }
                                }
                                i += 2;
                                continue;
                            }
                            if (code == 'r' || code == 'R') { cur = styleRgb; i += 2; continue; }
                            int rgb = legacyColorRgb(code);
                            if (rgb >= 0) cur = rgb;
                            // Formatierungscodes (k/l/m/n/o) lassen die Farbe stehen
                            i += 2;
                            continue;
                        }
                        // starsOnly=false sammelt JEDES Sonderzeichen – so sieht man
                        // im Staff-Scan auch Symbole, die noch in keiner Liste stehen.
                        if (starsOnly ? isStarGlyph(c) : (c >= 0xA1 && c != 167)) {
                            out.add(new int[]{ c, cur });
                        }
                        i++;
                    }
                    return java.util.Optional.empty();
                }
            }, net.minecraft.text.Style.EMPTY);
        } catch (Exception ignored) {}
    }

    /** Rang aus den Sternen eines einzelnen Text-Components, oder "" . */
    private static String scanStarRank(Text t) {
        if (t == null) return "";
        List<int[]> stars = new ArrayList<>();
        collectStars(t, stars);
        for (int[] st : stars) {
            int fam = starColorFamily(st[1]);
            if (starFamilyIsStaff(fam)) return starRankName(fam);
        }
        return "";
    }

    /** Tab-Listen-Display-Name eines Spielers (kann null sein). */
    static Text getTabDisplayName(MinecraftClient client, PlayerEntity p) {
        try {
            if (client.getNetworkHandler() == null) return null;
            net.minecraft.client.network.PlayerListEntry entry =
                client.getNetworkHandler().getPlayerListEntry(p.getUuid());
            return entry != null ? entry.getDisplayName() : null;
        } catch (Exception ignored) { return null; }
    }

    /** Alte Klartext-Erkennung. */
    private static String getTextRank(MinecraftClient client, PlayerEntity p) {
        return textRankOf(sourcesOf(client, p));
    }

    /** Alle Textquellen eines Spielers als EIN kleingeschriebener String (Tab, Name über dem Kopf, Team). */
    static String combinedRankText(RankSources s) {
        StringBuilder combined = new StringBuilder();

        // 1. Tab-Listen Display-Name
        Text tab = s.tab();
        if (tab != null) combined.append(tab.getString()).append(" ");

        // 2. Entity Display-Name (Name über dem Kopf) – null, wenn nur im Tab
        try {
            net.minecraft.text.Text dn = s.entityName();
            if (dn != null) combined.append(dn.getString()).append(" ");
        } catch (Exception ignored) {}

        // 3. Scoreboard Team
        net.minecraft.scoreboard.Team team = s.team();
        if (team != null) {
            combined.append(team.getName()).append(" ");
            try {
                net.minecraft.text.Text tdn = team.getDisplayName();
                if (tdn != null) combined.append(tdn.getString()).append(" ");
            } catch (Exception ignored) {}
        }
        return combined.toString().toLowerCase();
    }

    static String textRankOf(RankSources s) {
        String display = combinedRankText(s);

        if (display.contains("owner"))                                    return "Owner";
        if (display.contains("sradmin") || display.contains("sr.admin")) return "Sr.Admin";
        if (display.contains("admin"))                                    return "Admin";
        if (display.contains("srmod")   || display.contains("sr.mod"))   return "Sr.Mod";
        if (display.contains("moderator") || display.contains("[mod")
            || display.contains("mod]")  || display.contains(" mod "))   return "Mod";
        if (display.contains("srhelper") || display.contains("sr.helper")) return "Sr.Helper";
        if (display.contains("helper"))                                   return "Helper";
        return "";
    }

    /** Nur der Stern-Anteil der Erkennung, ohne Plausibilitaetspruefung. */
    /**
     * Alles, was die Rang-Erkennung über einen Spieler wissen muss – bewusst
     * UNABHÄNGIG davon, ob seine Entity geladen ist.
     *
     * Hintergrund: world.getPlayers() liefert nur Spieler in Renderdistanz.
     * Auf DonutSMP stehen 80 Leute im Tab, aber nur die 2–3 in der Nähe sind
     * als Entity da – der Staff-Scan zeigte deshalb nur einen selbst. Für die
     * Diagnose (welches Symbol ist Deko, welches der echte Rang-Marker?) muss
     * die komplette Tab-Liste her. Der Guard selbst bleibt bei Entities in
     * 40 Blöcken, denn nur die sind eine Gefahr.
     *
     * entity ist null, wenn der Spieler nur im Tab steht.
     */
    private record RankSources(String name, Text tab, Text entityName,
                               net.minecraft.scoreboard.Team team, PlayerEntity entity) {}

    static RankSources sourcesOf(MinecraftClient client, PlayerEntity p) {
        Text en = null;
        try { en = p.getDisplayName(); } catch (Exception ignored) {}
        return new RankSources(p.getName().getString(), getTabDisplayName(client, p), en,
                               p.getScoreboardTeam(), p);
    }

    static RankSources sourcesOf(MinecraftClient client, net.minecraft.client.network.PlayerListEntry e) {
        String name = "?";
        PlayerEntity ent = null;
        try {
            // authlib 7.x (seit 1.21.9): GameProfile ist ein Record → id()/name()
            name = e.getProfile().name();
            if (client.world != null) ent = client.world.getPlayerByUuid(e.getProfile().id());
        } catch (Exception ignored) {}
        Text tab = null;
        try { tab = e.getDisplayName(); } catch (Exception ignored) {}
        Text en = null;
        if (ent != null) { try { en = ent.getDisplayName(); } catch (Exception ignored) {} }
        net.minecraft.scoreboard.Team team = null;
        try { team = e.getScoreboardTeam(); } catch (Exception ignored) {}
        if (team == null && ent != null) team = ent.getScoreboardTeam();
        return new RankSources(name, tab, en, team, ent);
    }

    /**
     * Die komplette Tab-Liste als RankSources. Geladene Spieler zuerst, dann
     * nach Distanz – so steht ein Staff in der Nähe ganz oben im Scan.
     */
    static List<RankSources> tabSources(MinecraftClient client) {
        List<RankSources> out = new ArrayList<>();
        if (client.getNetworkHandler() == null) return out;
        for (net.minecraft.client.network.PlayerListEntry e
                : new ArrayList<>(client.getNetworkHandler().getPlayerList())) {
            out.add(sourcesOf(client, e));
        }
        final PlayerEntity me = client.player;
        out.sort((a, b) -> {
            boolean la = a.entity() != null, lb = b.entity() != null;
            if (la != lb) return la ? -1 : 1;
            if (!la || me == null) return a.name().compareToIgnoreCase(b.name());
            return Double.compare(me.squaredDistanceTo(a.entity()), me.squaredDistanceTo(b.entity()));
        });
        return out;
    }

    static String starRankOf(MinecraftClient client, PlayerEntity p) {
        return starRankOf(sourcesOf(client, p));
    }

    static String starRankOf(RankSources s) {
        String r = scanStarRank(s.tab());
        if (!r.isEmpty()) return r;
        r = scanStarRank(s.entityName());
        if (!r.isEmpty()) return r;
        net.minecraft.scoreboard.Team team = s.team();
        if (team != null) {
            try { r = scanStarRank(team.getPrefix());      if (!r.isEmpty()) return r; } catch (Exception ignored) {}
            try { r = scanStarRank(team.getSuffix());      if (!r.isEmpty()) return r; } catch (Exception ignored) {}
            try { r = scanStarRank(team.getDisplayName()); if (!r.isEmpty()) return r; } catch (Exception ignored) {}
        }
        return "";
    }

    // Ränge, die KEIN Staff sind, obwohl sie oft einen farbigen Marker tragen.
    // Media/YouTuber/Partner können nicht bannen – vor denen soll der Guard
    // die Spawner ganz normal sichern und ausloggen, nicht "still halten".
    private static final String[] NON_STAFF_KEYWORDS = {
        "media", "youtube", "youtuber", "yt", "streamer", "twitch", "tiktok",
        "creator", "content", "partner", "famous", "influencer"
    };

    /**
     * Endgültiges Urteil: Staff ja/nein (+ Label).
     *  0) Media & Co. → NIE Staff, auch nicht mit Stern (siehe NON_STAFF_KEYWORDS),
     *     außer es steht zusätzlich ein echtes Staff-Wort (Admin/Mod/…) dabei.
     *  1) Stern-Rank, sofern die Erkennung plausibel ist.
     *  2) Klartext-Fallback.
     */
    static String rankOf(RankSources s) {
        String text = staffTextRanks ? textRankOf(s) : "";
        if (text.isEmpty() && matchesAny(combinedRankText(s), NON_STAFF_KEYWORDS)) return "";
        if (staffDetectSane) {
            String r = starRankOf(s);
            if (!r.isEmpty()) return r;
        }
        return text;
    }

    /**
     * Plausibilitaetspruefung der Stern-Erkennung.
     *
     * Auf Servern wie DonutSMP hat JEDER Spieler ein farbiges Deko-Symbol im
     * Tab-Prefix. Wuerde so ein Symbol faelschlich als Stern gezaehlt, waere
     * plötzlich der halbe Server "Staff" – und der Guard wuerde sich abschalten,
     * also genau dann NICHT schuetzen, wenn es drauf ankommt.
     *
     * Deshalb: sobald mehr als die Haelfte der sichtbaren Spieler als Staff
     * gelten (bei mindestens 5 Spielern), wird die Stern-Erkennung als kaputt
     * markiert und ignoriert. Es bleibt die Klartext-Erkennung, und im HUD steht
     * eine Warnung. Lieber ein Fehlalarm im HUD als ein stillschweigend
     * abgeschalteter Schutz.
     */
    private static void updateStaffSanity(MinecraftClient client) {
        if (client.world == null || client.player == null) return;
        int total = 0, hits = 0;
        // Ueber die TAB-LISTE, nicht nur geladene Entities: bei 80 Spielern im
        // Tab ist die 50-%-Regel aussagekraeftig, bei 2 Entities in der Naehe nicht.
        String myName = client.player.getName().getString();
        for (RankSources s : tabSources(client)) {
            if (s.entity() == client.player || s.name().equalsIgnoreCase(myName)) continue;
            if (whitelistedPlayers.contains(s.name().toLowerCase())) continue;
            total++;
            if (!starRankOf(s).isEmpty()) hits++;
        }
        staffSaneTotal = total;
        staffSaneHits  = hits;
        // Ab 5 Spielern aussagekraeftig; darunter bleibt die Erkennung an.
        staffDetectSane = !(total >= 5 && hits * 2 > total);
    }

    /** Guard-Pfad: gleiche Logik wie der Scan (rankOf), inkl. Media-Ausschluss. */
    private static String getPlayerRank(MinecraftClient client, PlayerEntity p) {
        return rankOf(sourcesOf(client, p));
    }

    /**
     * Baut die Diagnose-Zeilen fuer den Staff-Scan-Screen. Zeigt fuer jeden
     * sichtbaren Spieler, was der Server tatsaechlich schickt: den Rohtext mit
     * sichtbar gemachten §-Codes, jeden gefundenen Stern mit Codepoint und
     * Farbe, die daraus abgeleitete Familie und ob sie als Staff zaehlt.
     * Damit laesst sich die Erkennung am echten Server verifizieren, statt sie
     * zu raten.
     */
    static List<String> buildStaffScanLines(MinecraftClient client) {
        List<String> lines = new ArrayList<>();
        if (client.world == null || client.player == null) {
            lines.add("§7Keine Welt geladen.");
            return lines;
        }
        lines.add("§8Aktiv: §7grün=" + (staffStarGreen ? "§aan" : "§caus")
                + " §7blau=" + (staffStarBlue ? "§aan" : "§caus")
                + " §7lila=" + (staffStarPurple ? "§aan" : "§caus")
                + " §7andere=" + (staffStarOther ? "§aan" : "§caus")
                + " §7Text=" + (staffTextRanks ? "§aan" : "§caus"));
        lines.add("");
        // Komplette Tab-Liste – nicht nur geladene Entities (sonst sieht man
        // auf einem vollen Server nur sich selbst).
        List<RankSources> snapshot = tabSources(client);
        int loaded = 0;
        for (RankSources s : snapshot) {
            if (s.entity() != null) loaded++;
            String name = s.name();
            boolean wl = whitelistedPlayers.contains(name.toLowerCase());
            String rank = rankOf(s);
            String dist = s.entity() != null
                    ? ((int) Math.round(Math.sqrt(client.player.squaredDistanceTo(s.entity()))) + "m")
                    : "§8nur Tab";
            lines.add("§f" + name + " §8| §7" + dist
                    + (wl ? " §a[Whitelist]" : "")
                    + (s.entity() == client.player ? " §b[ich]" : "")
                    + " §8| " + (rank.isEmpty() ? "§7kein Staff" : "§cSTAFF: " + rank));
            addStaffScanSource(lines, "Tab", s.tab());
            if (s.entityName() != null) addStaffScanSource(lines, "Name", s.entityName());
            net.minecraft.scoreboard.Team team = s.team();
            if (team != null) {
                try { addStaffScanSource(lines, "Team-Prefix", team.getPrefix()); } catch (Exception ignored) {}
                try { addStaffScanSource(lines, "Team-Suffix", team.getSuffix()); } catch (Exception ignored) {}
            }
            lines.add("");
        }
        lines.add(1, "§8" + snapshot.size() + " im Tab, " + loaded + " davon geladen");
        if (snapshot.isEmpty()) lines.add("§7Keine Spieler im Tab.");
        return lines;
    }

    /**
     * Glyph-Uebersicht: sammelt ALLE Sonderzeichen aus den Tab-/Team-Prefixes
     * aller sichtbaren Spieler und zaehlt, wie viele Spieler sie haben.
     *
     * Das ist der entscheidende Diagnose-Schritt: ein Symbol, das fast jeder
     * hat, ist Server-Deko und darf NIEMALS als Staff-Stern gelten. Ein Symbol,
     * das nur ein oder zwei Spieler haben, ist der echte Rang-Marker.
     */
    static List<String> buildGlyphOverview(MinecraftClient client) {
        List<String> lines = new ArrayList<>();
        if (client.world == null || client.player == null) {
            lines.add("§7Keine Welt geladen.");
            return lines;
        }
        // key = codepoint<<24 | (Farbindex), einfacher: Map<String,int[]>
        java.util.LinkedHashMap<String, int[]> counts = new java.util.LinkedHashMap<>();
        java.util.LinkedHashMap<String, java.util.Set<String>> owners = new java.util.LinkedHashMap<>();
        // Komplette Tab-Liste – die Glyph-Statistik lebt von vielen Spielern.
        List<RankSources> snapshot = tabSources(client);
        for (RankSources p : snapshot) {
            java.util.Set<String> seen = new HashSet<>();
            List<int[]> g = new ArrayList<>();
            collectGlyphs(p.tab(), g, false);
            if (p.entityName() != null) collectGlyphs(p.entityName(), g, false);
            net.minecraft.scoreboard.Team team = p.team();
            if (team != null) {
                try { collectGlyphs(team.getPrefix(), g, false); } catch (Exception ignored) {}
                try { collectGlyphs(team.getSuffix(), g, false); } catch (Exception ignored) {}
            }
            for (int[] e : g) {
                String key = String.format("%04X|%06X", e[0], e[1] < 0 ? 0xFFFFFF : e[1]);
                if (!seen.add(key)) continue;   // pro Spieler nur einmal zaehlen
                counts.computeIfAbsent(key, k -> new int[]{ e[0], e[1], 0 })[2]++;
                owners.computeIfAbsent(key, k -> new java.util.LinkedHashSet<>())
                      .add(p.name());
            }
        }
        int players = Math.max(1, snapshot.size());
        lines.add("§eGlyph-Übersicht §8– " + snapshot.size() + " Spieler im Tab");
        lines.add("§8Symbol, das fast jeder hat = Deko. Nur seltene Symbole sind Rang-Marker.");
        lines.add("");
        if (counts.isEmpty()) {
            lines.add("§7Keine Sonderzeichen in den Prefixes gefunden.");
            return lines;
        }
        List<int[]> sorted = new ArrayList<>(counts.values());
        sorted.sort((a, b) -> b[2] - a[2]);
        for (int[] e : sorted) {
            int cp = e[0], rgb = e[1], n = e[2];
            int fam = starColorFamily(rgb);
            int pct = (int) Math.round(n * 100.0 / players);
            String key = String.format("%04X|%06X", cp, rgb < 0 ? 0xFFFFFF : rgb);
            String verdict;
            if (isStarGlyph(cp) && starFamilyIsStaff(fam)) verdict = "§czählt als STAFF";
            else if (isStarGlyph(cp))                      verdict = "§7Stern, Farbe zählt nicht";
            else                                            verdict = "§8kein Stern";
            String hint = pct >= 50 ? " §c<- DEKO!" : (n <= 2 ? " §a<- verdächtig selten" : "");
            lines.add(String.format("§fU+%04X §8| %s §8| %s §8| §f%dx §8(%d%%) §8| %s%s",
                    cp,
                    rgb < 0 ? "#------" : String.format("#%06X", rgb),
                    fam == 0 ? "unbestimmt" : starRankName(fam),
                    n, pct, verdict, hint));
            java.util.Set<String> who = owners.get(key);
            if (who != null && who.size() <= 4) {
                lines.add("   §8" + String.join(", ", who));
            }
        }
        return lines;
    }

    private static void addStaffScanSource(List<String> lines, String label, Text t) {
        if (t == null) return;
        String raw = t.getString();
        if (raw == null) raw = "";
        // §-Codes sichtbar machen (167 = '§'), damit man den Rohtext lesen kann
        String vis = raw.replace((char) 167, '&');
        if (vis.length() > 48) vis = vis.substring(0, 48) + "...";
        List<int[]> stars = new ArrayList<>();
        collectStars(t, stars);
        StringBuilder sb = new StringBuilder("  §8" + label + ": §7" + vis);
        if (stars.isEmpty()) {
            sb.append(" §8(kein Stern)");
        } else {
            for (int[] st : stars) {
                int fam = starColorFamily(st[1]);
                sb.append(String.format(" §8| U+%04X", st[0]));
                sb.append(st[1] < 0 ? " §8#------" : String.format(" §8#%06X", st[1]));
                sb.append(" §8").append(fam == 0 ? "unbestimmt" : starRankName(fam));
                sb.append(starFamilyIsStaff(fam) ? " §a[STAFF]" : " §c[egal]");
            }
        }
        lines.add(sb.toString());
    }

    private static boolean isStaffNearby(MinecraftClient client) {
        if (client.world == null || client.player == null) return false;
        for (PlayerEntity p : client.world.getPlayers()) {
            if (p == client.player) continue;
            if (whitelistedPlayers.contains(p.getName().getString().toLowerCase())) continue;
            if (client.player.squaredDistanceTo(p) > 1600) continue;
            if (!getPlayerRank(client, p).isEmpty()) return true;
        }
        return false;
    }

    private void analyzeHole(net.minecraft.world.World w, BlockPos s) {
        List<BlockPos> cl = new ArrayList<>(); Queue<BlockPos> q = new LinkedList<>(); q.add(s); visitedPositions.add(s);
        while (!q.isEmpty()) {
            BlockPos c = q.poll(); cl.add(c);
            for (Direction d : Direction.values()) {
                BlockPos n = c.offset(d); if (w.getBlockState(n).isOf(Blocks.DEEPSLATE) && !visitedPositions.contains(n)) { visitedPositions.add(n); q.add(n); }
            }
            if (cl.size() > 10) return;
        }
        for (BlockPos c : cl) for (Direction d : Direction.values()) if (!cl.contains(c.offset(d)) && !w.getBlockState(c.offset(d)).isOf(Blocks.BEDROCK)) return;
        if (cl.size() >= minHoleSize) currentScanBatch.add(new ArrayList<>(cl));
    }

    // ==========================================
    // RECONNECT ÜBERLAGERUNGSSCREEN
    // ==========================================
    public static class KryptonReconnectScreen extends Screen {
        private final Text reason;
        private final Text hint;         // Zusatzzeile (Session-Fix / Notfall-Logout), darf null sein
        private final boolean autoBlocked; // true = Reconnect ist gesperrt (Notfall-Logout)
        private ButtonWidget reconnectBtn;

        public KryptonReconnectScreen(Text reason) {
            this(reason, null, false);
        }

        public KryptonReconnectScreen(Text reason, Text hint, boolean autoBlocked) {
            super(Text.literal("Connection Lost"));
            this.reason = reason;
            this.hint = hint;
            this.autoBlocked = autoBlocked;
        }

        private Text reconnectLabel() {
            if (autoBlocked)        return Text.literal("§8Reconnect gesperrt");
            if (reconnectTicks < 0) return Text.literal("Jetzt neu verbinden");
            return Text.literal("Reconnect in " + (reconnectTicks / 20) + "...");
        }

        @Override
        protected void init() {
            int cX = width / 2;
            int cY = height / 2;

            reconnectBtn = addDrawableChild(ButtonWidget.builder(reconnectLabel(), b -> {
                reconnectTicks = 0;
            }).dimensions(cX - 100, cY + 20, 200, 20).build());
            // Nach dem Notfall-Logout ist auch der manuelle Knopf tot – sonst wäre
            // die Sperre mit einem versehentlichen Klick wieder ausgehebelt.
            reconnectBtn.active = !autoBlocked;

            // Bei gesperrtem Rejoin (Notfall-Logout) ist der Auto-Reconnect nicht
            // die Ursache – dann wird er hier auch nicht heimlich abgeschaltet.
            addDrawableChild(ButtonWidget.builder(Text.literal(autoBlocked ? "Zum Serverbrowser" : "Cancel"), b -> {
                if (!autoBlocked) {
                    isAutoReconnectActive = false;
                    saveReconnect();
                }
                client.setScreen(new net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen(new net.minecraft.client.gui.screen.TitleScreen()));
            }).dimensions(cX - 100, cY + 45, 200, 20).build());
        }

        @Override
        public void render(DrawContext c, int mouseX, int mouseY, float delta) {
            super.render(c, mouseX, mouseY, delta);
            c.drawCenteredTextWithShadow(textRenderer, title, width / 2, height / 2 - 50, 0xFFFFFF);
            c.drawCenteredTextWithShadow(textRenderer, reason, width / 2, height / 2 - 30, 0xAAAAAA);
            if (hint != null) {
                c.drawCenteredTextWithShadow(textRenderer, hint, width / 2, height / 2 - 14, 0xFFFFFF);
            }
            if (reconnectBtn != null) {
                reconnectBtn.setMessage(reconnectLabel());
            }
        }
    }

    // ==========================================
    // RECONNECT EINSTELLUNGEN SCREEN
    // ==========================================
    public static class ReconnectSettingsScreen extends Screen {
        private final Screen parent;
        public ReconnectSettingsScreen(Screen parent) {
            super(Text.literal("Reconnect Settings"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            int cX = width / 2;

            addDrawableChild(ButtonWidget.builder(Text.literal("Infinite: " + (isInfiniteReconnect ? "§aON" : "§cAUS")), b -> {
                isInfiniteReconnect = !isInfiniteReconnect;
                saveReconnect();
                b.setMessage(Text.literal("Infinite: " + (isInfiniteReconnect ? "§aON" : "§cAUS")));
            }).dimensions(cX - 100, 20, 200, 20).build());

            for (int i = 0; i < reconnectDelays.size(); i++) {
                int index = i;
                int yPos = 60 + i * 25;
                TextFieldWidget f = new TextFieldWidget(textRenderer, cX - 60, yPos, 100, 20, Text.literal(""));
                f.setText(String.valueOf(reconnectDelays.get(i)));
                f.setChangedListener(s -> {
                    if (s.matches("^[0-9]+$")) {
                        reconnectDelays.set(index, Integer.parseInt(s));
                        saveReconnect();
                    }
                });
                addDrawableChild(f);

                addDrawableChild(ButtonWidget.builder(Text.literal("X"), b -> {
                    reconnectDelays.remove(index);
                    saveReconnect();
                    client.setScreen(new ReconnectSettingsScreen(parent));
                }).dimensions(cX + 45, yPos, 20, 20).build());
            }

            if (reconnectDelays.size() < 6) {
                addDrawableChild(ButtonWidget.builder(Text.literal("+ Add Delay"), b -> {
                    reconnectDelays.add(10);
                    saveReconnect();
                    client.setScreen(new ReconnectSettingsScreen(parent));
                }).dimensions(cX - 60, 60 + reconnectDelays.size() * 25, 125, 20).build());
            }

            addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> {
                client.setScreen(parent);
            }).dimensions(cX - 100, height - 30, 200, 20).build());
        }

        @Override
        public void render(DrawContext c, int mouseX, int mouseY, float delta) {
            super.render(c, mouseX, mouseY, delta);
            c.drawCenteredTextWithShadow(textRenderer, "Delays (in Sekunden)", width / 2, 45, 0xFFFFFF);
        }
    }

    // ==========================================
    // DISCONNECT LOG SCREEN
    // ==========================================
    // Zeigt die letzten 20 Trenngründe im Klartext samt erkannter Kategorie und
    // der daraus abgeleiteten Aktion. Auf Servern, die bei jedem Bug eine andere
    // Fehlermeldung schicken, ist das die Grundlage, um den Session-Fix-Modus
    // sinnvoll zu wählen.
    public static class DisconnectLogScreen extends Screen {
        private final Screen parent;
        public DisconnectLogScreen(Screen parent) {
            super(Text.literal("Disconnect Log"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            int cX = width / 2;
            addDrawableChild(ButtonWidget.builder(
                Text.literal("Modus: §b" + sessionFixModeName()), b -> {
                    sessionFixMode = (sessionFixMode + 1) % 3;
                    saveCheatStates();
                    b.setMessage(Text.literal("Modus: §b" + sessionFixModeName()));
                }).dimensions(cX - 155, height - 30, 100, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("Log löschen"), b -> {
                    disconnectHistory.clear();
                    saveDisconnectLog();
                }).dimensions(cX - 50, height - 30, 100, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("Zurück"), b -> client.setScreen(parent))
                .dimensions(cX + 55, height - 30, 100, 20).build());
        }

        @Override
        public void render(DrawContext c, int mouseX, int mouseY, float delta) {
            super.render(c, mouseX, mouseY, delta);

            List<String> view = new ArrayList<>(disconnectHistory);
            String head = "§eLetzte Trenngründe §8(Session-Fix: "
                        + (isSessionFixActive ? "§aan" : "§caus") + "§8, Modus §b" + sessionFixModeName() + "§8)";

            int longest = textRenderer.getWidth(head);
            for (String v : view) longest = Math.max(longest, textRenderer.getWidth(v));
            float scaleW = Math.min(1f, (width - 16) / (float) Math.max(1, longest));
            int available = height - 40 - 8;
            float scaleH = Math.min(1f, available / (float) (16 + Math.max(1, view.size()) * 10));
            float scale  = Math.min(scaleW, scaleH);

            c.getMatrices().pushMatrix();
            c.getMatrices().scale(scale, scale);
            int x = (int) (8 / scale);
            int y = (int) (10 / scale);
            c.drawText(textRenderer, head, x, y, 0xFFFFAA00, true);
            y += 14;
            if (view.isEmpty()) {
                c.drawText(textRenderer, "§7Noch keine Trennung aufgezeichnet.", x, y, -1, true);
            } else {
                for (String v : view) {
                    c.drawText(textRenderer, v, x, y, -1, true);
                    y += 10;
                }
            }
            c.getMatrices().popMatrix();
        }

        @Override
        public boolean shouldPause() { return false; }
    }

    // ==========================================
    // STAFF SCAN SCREEN (RANG-DIAGNOSE)
    // ==========================================
    // Zeigt fuer jeden sichtbaren Spieler, was der Server wirklich schickt, und
    // wie Krypton das bewertet. Damit laesst sich die Stern-Erkennung am echten
    // Server pruefen, statt sie zu raten – und die Farbfamilien lassen sich hier
    // direkt an-/abschalten.
    public static class StaffScanScreen extends Screen {
        private final Screen parent;
        private List<String> lines = new ArrayList<>();
        private int page = 0;
        private boolean glyphView = false;   // false = Spieler, true = Glyph-Übersicht
        private static final int PER_PAGE = 15;

        public StaffScanScreen(Screen parent) {
            super(Text.literal("Staff Scan"));
            this.parent = parent;
        }

        private void refresh() {
            lines = glyphView ? buildGlyphOverview(client) : buildStaffScanLines(client);
            int maxPage = Math.max(0, (lines.size() - 1) / PER_PAGE);
            if (page > maxPage) page = maxPage;
        }

        private void famBtn(String label, int x, int y, int w,
                            java.util.function.BooleanSupplier get, Runnable toggle) {
            addDrawableChild(ButtonWidget.builder(
                Text.literal(label + ": " + (get.getAsBoolean() ? "§aAN" : "§cAUS")), b -> {
                    toggle.run();
                    saveStaffDetect();
                    b.setMessage(Text.literal(label + ": " + (get.getAsBoolean() ? "§aAN" : "§cAUS")));
                    refresh();
                }).dimensions(x, y, w, 20).build());
        }

        @Override
        protected void init() {
            refresh();
            int cX = width / 2;

            // Farbfamilien-Schalter
            int bw = Math.max(58, Math.min(110, (width - 24) / 5));
            int tot = bw * 5 + 8;
            int bx = cX - tot / 2;
            int by = height - 56;
            famBtn("Grün",   bx,            by, bw, () -> staffStarGreen,  () -> staffStarGreen  = !staffStarGreen);
            famBtn("Blau",   bx + bw + 2,   by, bw, () -> staffStarBlue,   () -> staffStarBlue   = !staffStarBlue);
            famBtn("Lila",   bx + 2*bw + 4, by, bw, () -> staffStarPurple, () -> staffStarPurple = !staffStarPurple);
            famBtn("Andere", bx + 3*bw + 6, by, bw, () -> staffStarOther,  () -> staffStarOther  = !staffStarOther);
            famBtn("Text",   bx + 4*bw + 8, by, bw, () -> staffTextRanks,  () -> staffTextRanks  = !staffTextRanks);

            // Steuerzeile
            addDrawableChild(ButtonWidget.builder(
                Text.literal(glyphView ? "Ansicht: Glyphen" : "Ansicht: Spieler"), b -> {
                    glyphView = !glyphView;
                    page = 0;
                    b.setMessage(Text.literal(glyphView ? "Ansicht: Glyphen" : "Ansicht: Spieler"));
                    refresh();
                }).dimensions(cX - 160, height - 80, 120, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("Aktualisieren"), b -> refresh())
                .dimensions(cX - 160, height - 30, 90, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("<"), b -> { if (page > 0) page--; })
                .dimensions(cX - 66, height - 30, 30, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal(">"), b -> {
                    if ((page + 1) * PER_PAGE < lines.size()) page++;
                }).dimensions(cX - 32, height - 30, 30, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("Zurück"), b -> client.setScreen(parent))
                .dimensions(cX + 6, height - 30, 90, 20).build());
        }

        @Override
        public void render(DrawContext c, int mouseX, int mouseY, float delta) {
            super.render(c, mouseX, mouseY, delta);

            int maxPage = Math.max(0, (lines.size() - 1) / PER_PAGE);
            String title = "§eStaff Scan §8(" + (glyphView ? "Glyphen" : "Spieler")
                         + ") §8– Seite " + (page + 1) + "/" + (maxPage + 1);
            c.drawCenteredTextWithShadow(textRenderer, Text.literal(title), width / 2, 8, 0xFFFFAA00);

            // Auf Breite skalieren, damit auch lange Diagnosezeilen lesbar bleiben
            int longest = 200;
            int from = page * PER_PAGE;
            int to   = Math.min(lines.size(), from + PER_PAGE);
            for (int i = from; i < to; i++) {
                longest = Math.max(longest, textRenderer.getWidth(lines.get(i)));
            }
            float scale = Math.min(1f, (width - 16) / (float) longest);

            c.getMatrices().pushMatrix();
            c.getMatrices().scale(scale, scale);
            int y = (int) (22 / scale);
            int lh = 10;
            for (int i = from; i < to; i++) {
                c.drawText(textRenderer, lines.get(i), (int) (8 / scale), y, -1, true);
                y += lh;
            }
            c.getMatrices().popMatrix();
        }

        @Override
        public boolean shouldPause() { return false; }
    }

    // ==========================================
    // PLAYER LOG SCREEN (LINKS)
    // ==========================================
    public static class PlayerLogScreen extends Screen {
        private final Screen parent;
        public PlayerLogScreen(Screen parent) {
            super(Text.literal("Player Logs"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            int cX = width / 2;
            addDrawableChild(ButtonWidget.builder(Text.literal("Historie löschen"), b -> {
                playerHistory.clear();
                sessionSeenPlayers.clear();
                saveLogs();
            }).dimensions(cX - 105, height - 30, 100, 20).build());

            addDrawableChild(ButtonWidget.builder(Text.literal("Zurück"), b -> {
                client.setScreen(parent);
            }).dimensions(cX + 5, height - 30, 100, 20).build());
        }

        @Override
        public void render(DrawContext c, int mouseX, int mouseY, float delta) {
            super.render(c, mouseX, mouseY, delta);

            // Skalierung berechnen: passt alle 20 Einträge zwischen Titel (y=8) und Buttons (height-34)
            int available = height - 34 - 8; // nutzbare Pixel
            int entries   = Math.min(playerHistory.size(), 20);
            // Jede Zeile braucht 10px im Textraum; Titelzeile + 4px Gap oben
            float scale = Math.min(1f, available / (float)(14 + entries * 10));

            c.getMatrices().pushMatrix();
            c.getMatrices().scale(scale, scale);
            int sw = (int)(width  / scale); // effektive Breite im skalierten Raum
            int sh = (int)(height / scale);

            // Titel
            String title = "§eLetzte 20 Sichtungen:";
            int tx = (sw - textRenderer.getWidth(title)) / 2;
            c.drawText(textRenderer, title, tx, 12, 0xFFFFAA00, true);

            // Einträge – zentriert als Block
            int y = 26;
            for (int i = 0; i < entries; i++) {
                String entry = playerHistory.get(i);
                int ex = (sw - textRenderer.getWidth(entry)) / 2;
                // Horizontaler Clip: wenn immer noch zu breit → leicht links-bündig
                if (ex < 4) ex = 4;
                c.drawText(textRenderer, entry, ex, y, -1, true);
                y += 10;
            }

            c.getMatrices().popMatrix();
        }
    }

    // ==========================================
    // LOGOUT LOG SCREEN (RECHTS)
    // ==========================================
    public static class LogoutLogScreen extends Screen {
        private final Screen parent;
        public LogoutLogScreen(Screen parent) {
            super(Text.literal("Logout Logs"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            int cX = width / 2;
            addDrawableChild(ButtonWidget.builder(Text.literal("Log löschen"), b -> {
                lastLogoutLog = "Bisher kein Logout aufgezeichnet";
                saveLogs();
            }).dimensions(cX - 105, height - 30, 100, 20).build());

            addDrawableChild(ButtonWidget.builder(Text.literal("Zurück"), b -> {
                client.setScreen(parent);
            }).dimensions(cX + 5, height - 30, 100, 20).build());
        }

        @Override
        public void render(DrawContext c, int mouseX, int mouseY, float delta) {
            super.render(c, mouseX, mouseY, delta);
            String title = "§cLetzte Notfall Logouts:";
            c.drawText(textRenderer, title, (width - textRenderer.getWidth(title)) / 2, 20, -1, true);
            if (lastLogoutLog.equals("Bisher kein Logout aufgezeichnet") || lastLogoutLog.isEmpty()) {
                String empty = "§7Keine Logs aufgezeichnet.";
                c.drawText(textRenderer, empty, (width - textRenderer.getWidth(empty)) / 2, 45, -1, true);
            } else {
                String log = lastLogoutLog;
                c.drawText(textRenderer, log, (width - textRenderer.getWidth(log)) / 2, 45, -1, true);
            }
        }
    }

    // ==========================================
    // WHITELIST VERWALTUNG
    // ==========================================
    public static class WhitelistScreen extends Screen {
        private final Screen parent;
        private TextFieldWidget inputField;
        private boolean wasMouseDown = false;

        public WhitelistScreen(Screen parent) {
            super(Text.literal("Whitelist"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            int cx = width / 2;
            inputField = new TextFieldWidget(textRenderer, cx - 75, height - 52, 130, 16, Text.literal(""));
            inputField.setMaxLength(32);
            addDrawableChild(inputField);
            addDrawableChild(ButtonWidget.builder(Text.literal("+"), b -> {
                String name = inputField.getText().trim().toLowerCase();
                if (!name.isEmpty() && !whitelistedPlayers.contains(name)) {
                    whitelistedPlayers.add(name);
                    saveWhitelist();
                }
                inputField.setText("");
            }).dimensions(cx + 60, height - 52, 20, 16).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("Zurück"), b ->
                client.setScreen(parent)).dimensions(cx - 40, height - 30, 80, 16).build());
        }

        @Override
        public void render(DrawContext ctx, int mx, int my, float delta) {
            ctx.fill(0, 0, width, height, 0xCC000000);
            int bx = width/2 - 95, listTop = 26, listBot = height - 62;
            ctx.fill(bx, listTop - 2, bx + 190, listBot, 0xFF0B0F17);
            ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("§eWhitelist §8– §7anklicken entfernt"), width/2, 10, -1);
            ctx.drawText(textRenderer, Text.literal("§7Name hinzufügen:"), bx, height - 65, 0xFF6E7687, false);

            // Klick-Erkennung via GLFW (kein @Override mouseClicked nötig)
            long win = client.getWindow().getHandle();
            boolean down = GLFW.glfwGetMouseButton(win, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;

            int y = listTop;
            for (int i = 0; i < whitelistedPlayers.size() && y + 12 <= listBot; i++) {
                String name = whitelistedPlayers.get(i);
                boolean hover = mx >= bx && mx < bx + 190 && my >= y && my < y + 12;
                if (hover) ctx.fill(bx, y, bx + 190, y + 12, 0x33FF4444);
                if (hover && down && !wasMouseDown) {
                    whitelistedPlayers.remove(i);
                    saveWhitelist();
                    wasMouseDown = true;
                    super.render(ctx, mx, my, delta);
                    return;
                }
                ctx.drawText(textRenderer, Text.literal(name), bx + 4, y + 2,
                    hover ? 0xFFFF6666 : 0xFFCCCEd4, false);
                y += 12;
            }
            if (whitelistedPlayers.isEmpty())
                ctx.drawCenteredTextWithShadow(textRenderer,
                    Text.literal("§7(leer)"), width/2, listTop + 8, -1);

            wasMouseDown = down;
            super.render(ctx, mx, my, delta);
        }

        @Override public boolean shouldPause() { return false; }
    }

    // ==========================================
    // LOCHGRÖSSE EINGABE-FENSTER
    // ==========================================
    public static class HoleSizeScreen extends Screen {
        private final Screen parent;
        private TextFieldWidget inputField;

        public HoleSizeScreen(Screen parent) {
            super(Text.literal("Min. Lochgröße"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            int cx = width / 2, cy = height / 2;
            inputField = new TextFieldWidget(textRenderer, cx - 50, cy - 10, 100, 20, Text.literal(""));
            inputField.setText(String.valueOf(minHoleSize));
            inputField.setMaxLength(3);
            inputField.setChangedListener(s -> {
                if (s.matches("[0-9]+") && !s.isEmpty()) {
                    int v = Integer.parseInt(s);
                    if (v >= 1 && v <= 100) minHoleSize = v;
                }
            });
            addDrawableChild(inputField);
            addDrawableChild(ButtonWidget.builder(Text.literal("OK"), b -> client.setScreen(parent))
                .dimensions(cx - 25, cy + 15, 50, 20).build());
        }

        @Override
        public void render(DrawContext ctx, int mx, int my, float delta) {
            ctx.fill(0, 0, width, height, 0xCC000000);
            int bx = width/2 - 90, by = height/2 - 45;
            ctx.fill(bx, by, bx + 180, by + 90, 0xFF0B0F17);
            ctx.fill(bx, by, bx + 180, by + 22, 0xFF0D1220);
            ctx.fill(bx, by + 21, bx + 180, by + 22, 0xFF1C2335);
            ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("MIN HOLE SIZE (1-100)"), width / 2, by + 7, 0xFF44BBFF);
            super.render(ctx, mx, my, delta);
        }

        @Override public boolean shouldPause() { return false; }
    }

    // ==========================================
    // HAUPTMENÜ
    // ==========================================
    public static class ClickGuiScreen extends Screen {

        // ── Animations ──────────────────────────────────────────────────────
        private float   openAnim              = 0f;
        private boolean isRebindingFreecam    = false;
        private boolean isRebindingBonesFarmer = false;
        private boolean isEnteringHoleSize  = false;
        private String  holeSizeInput       = "";
        private boolean isEnteringDropCount = false;
        private String  dropCountInput      = "";
        private boolean wasMouseDown        = false;
        private final float[] dotAnim      = new float[24]; // per-module 0→1

        // ── Layout ──────────────────────────────────────────────────────────
        // Column width: max 185 px, shrinks if screen is too narrow to fit all cols
        private static final int MAX_CW  = 185;
        private static final int HEAD_H  = 20;  // header height  (px)
        private static final int ROW_H   = 16;  // module row height
        private static final int COL_GAP = 1;   // gap between columns
        private static final int GUI_Y   = 0;   // columns start at top edge
        private static final int PADX    = 6;   // text left-padding

        // ── Colors (matching reference screenshot) ──────────────────────────
        private static final int C_OVERLAY  = 0xBB000000; // full-screen dark bg
        private static final int C_COL_BG   = 0xE8090C12; // column body (dark navy)
        private static final int C_HEAD_BG  = 0xFF0B0F17; // header (slightly diff)
        private static final int C_HEAD_SEP = 0xFF1C2335; // 1-px separator line
        private static final int C_CAT_TXT  = 0xFF6B7585; // category label
        private static final int C_DASH     = 0xFF3A4050; // "-" collapse button
        private static final int C_MOD_OFF  = 0xFF6E7687; // inactive module text
        private static final int C_MOD_ON   = 0xFFCCCEd4; // active module text
        private static final int C_DOT_OFF  = 0xFF2E3340; // toggle dot OFF
        private static final int C_DOT_ON   = 0xFF00AAFF; // toggle dot ON (blue)
        private static final int C_ROW_ACT  = 0x110055FF; // active row tint
        private static final int C_ROW_HOV  = 0x14FFFFFF; // hover tint

        // ── Category / module data ───────────────────────────────────────────
        // idx 0=Freecam  1=AutoSpawner  2=AutoReconnect  3=DisableOnDmg
        //     4=BedrockFinder  5=PlayerESP  6=SpawnerESP  7=Fullbright
        //     8=PlayerLogs(screen)  9=LogoutLogs(screen)  10=FreecamKey(rebind)
        //     11=ReconnectCfg(screen)  12=HoleSize(input)  13=BonesFarm(toggle)
        //     14=BonesKey(rebind)  15=BonesDropBase(input)  16=Tracers(toggle)
        //     17=Whitelist(screen)  18=SessionFix(toggle)  19=StaffScan(screen)
        //     20=SessionTest(action) 21=RejoinLock(toggle) 22=DisconnectLog(screen)
        //     23=SessionMode(cycle)
        private static final String[] CATS  = { "MISC",  "BASEFINDING", "RENDER",  "CLIENT" };
        private static final int[]    IC_COL = { 0xFF8B8FA8, 0xFF44BBFF, 0xFFAA55FF, 0xFF44CCFF };
        private static final int[][]  MODS   = {
            //  MISC: Freecam, FreecamKey, DisableOnDmg, StaffScan, DisconnectLog, SessionTest, RejoinLock
            { 0, 10, 3, 19, 22, 20, 21 },
            //  BASEFINDING: BedrockFinder, MinHoleSize
            { 4, 12 },
            //  RENDER: PlayerESP, Tracers, SpawnerESP, Fullbright
            { 5, 16, 6, 7 },
            //  CLIENT: AutoSpawner, AutoReconnect, SessionFix, ReconnectSet, Whitelist, PlayerLogs, LogoutLogs, BonesFarm, BonesKey, BonesDropBase
            { 1, 2, 18, 23, 11, 17, 8, 9, 13, 14, 15 }
        };
        private static final String[] MNAME  = {
            /* 0 */ "FREECAM",
            /* 1 */ "AUTO SPAWNER",
            /* 2 */ "AUTO RECONNECT",
            /* 3 */ "DISABLE ON DMG",
            /* 4 */ "BEDROCK FINDER",
            /* 5 */ "PLAYER ESP",
            /* 6 */ "SPAWNER ESP",
            /* 7 */ "FULLBRIGHT",
            /* 8 */ "PLAYER LOGS",
            /* 9 */ "LOGOUT LOGS",
            /* 10*/ "FREECAM KEY",
            /* 11*/ "RECONNECT SET",
            /* 12*/ "MIN HOLE SIZE",
            /* 13*/ "BONES FARM",
            /* 14*/ "BONES KEY",
            /* 15*/ "BONES DROP",
            /* 16*/ "TRACERS",
            /* 17*/ "WHITELIST",
            /* 18*/ "SESSION FIX",
            /* 19*/ "STAFF SCAN",
            /* 20*/ "SESSION TEST",
            /* 21*/ "REJOIN LOCK",
            /* 22*/ "DISCONNECT LOG",
            /* 23*/ "SESSION MODE"
        };

        protected ClickGuiScreen() { super(Text.literal("Krypton")); }

        // ── Module state helpers ─────────────────────────────────────────────
        private boolean modOn(int i) {
            return switch (i) {
                case 0 -> isFreecamActive;
                case 1 -> isAutoSpawnerActive;
                case 2 -> isAutoReconnectActive;
                case 3 -> disableFreecamOnDamage;
                case 4 -> isBedrockFinderActive;
                case 5 -> isPlayerEspActive;
                case 6 -> isSpawnerEspActive;
                case 7  -> isFullbrightActive;
                case 13 -> isBonesFarmerActive;
                case 16 -> isTracersActive;
                case 18 -> isSessionFixActive;
                case 21 -> wasSafetyLogout;
                default -> false;
            };
        }

        private void modToggle(int i) {
            switch (i) {
                case 0  -> { isFreecamActive       = !isFreecamActive;       toggleFreecam(client); }
                case 1  ->   isAutoSpawnerActive    = !isAutoSpawnerActive;
                case 2  -> { isAutoReconnectActive  = !isAutoReconnectActive; saveReconnect(); }
                case 3  -> { disableFreecamOnDamage = !disableFreecamOnDamage; saveFreecamSettings(); }
                case 4  ->   isBedrockFinderActive  = !isBedrockFinderActive;
                case 5  ->   isPlayerEspActive      = !isPlayerEspActive;
                case 6  ->   isSpawnerEspActive     = !isSpawnerEspActive;
                case 7  -> { isFullbrightActive     = !isFullbrightActive;   saveFullbright(); }
                case 16 ->   isTracersActive        = !isTracersActive;
                case 18 -> { isSessionFixActive     = !isSessionFixActive;   saveCheatStates(); }
                case 19 ->   client.setScreen(new StaffScanScreen(this));
                case 22 ->   client.setScreen(new DisconnectLogScreen(this));
                // Wie breit der Session-Fix reagiert: STRIKT -> TECHNIK -> ALLES
                case 23 -> { sessionFixMode = (sessionFixMode + 1) % 3; saveCheatStates(); }
                case 20 -> {
                    // TEST: simuliert einen "Invalid Session"-Kick, damit sich der
                    // Session-Fix-Pfad (Erkennung → Reconnect) ohne echten
                    // Serverkick pruefen laesst. Die Notfall-Logout-Sperre gilt
                    // dabei ganz normal – genau das will man ja mittesten.
                    if (client.getNetworkHandler() != null) {
                        client.setScreen(null);
                        client.getNetworkHandler().getConnection().disconnect(
                            Text.literal("Invalid session (Try restarting your game and the launcher)"));
                    }
                }
                // Notfall-Logout-Sperre manuell setzen/aufheben. Ohne das koennte
                // man die Sperre nur durch einen manuellen Join wieder loswerden.
                case 21 ->   setSafetyLogout(!wasSafetyLogout);
                case 17 ->   client.setScreen(new WhitelistScreen(this));
                case 8  ->   client.setScreen(new PlayerLogScreen(this));
                case 9  ->   client.setScreen(new LogoutLogScreen(this));
                case 10 ->   isRebindingFreecam = true;
                case 11 ->   client.setScreen(new ReconnectSettingsScreen(this));
                case 12 -> { isEnteringHoleSize = true; holeSizeInput = ""; }
                case 15 -> { isEnteringDropCount = true; dropCountInput = ""; }
                case 13 -> {
                    isBonesFarmerActive = !isBonesFarmerActive;
                    if (!isBonesFarmerActive) {
                        bonesFarmerState = 0; bonesFarmerDelay = 0; bonesFarmerDeliveryTimer = -1; bonesFarmerDeliveryDone = false;
                        if (client.currentScreen instanceof HandledScreen<?>) client.player.closeHandledScreen();
                    }
                }
                case 14 -> isRebindingBonesFarmer = true;
            }
        }

        private void modRightClick(int i) {
            // no right-click actions currently
        }

        private void commitDropCount() {
            if (!dropCountInput.isEmpty()) {
                try {
                    int v = Integer.parseInt(dropCountInput);
                    if (v >= 1 && v <= 99) {
                        bonesFarmerDropBase = v;
                        saveDropBase();
                    }
                } catch (NumberFormatException ignored) {}
            }
        }

        // ── Layout helpers ───────────────────────────────────────────────────
        private int cw()            { return Math.min(MAX_CW, (width - (CATS.length-1)*COL_GAP) / CATS.length); }
        private int colX(int col)   { return col * (cw() + COL_GAP); }
        private int colH(int col)   { return HEAD_H + MODS[col].length * ROW_H; }

        // Linear color interpolation (RGB only, alpha = 0xFF)
        private int lerp(int a, int b, float t) {
            int ar=(a>>16)&0xFF, ag=(a>>8)&0xFF, ab2=a&0xFF;
            int br=(b>>16)&0xFF, bg=(b>>8)&0xFF, bb2=b&0xFF;
            return 0xFF000000
                | ((ar+(int)((br-ar)*t))<<16)
                | ((ag+(int)((bg-ag)*t))<< 8)
                |  (ab2+(int)((bb2-ab2)*t));
        }

        // ── Pixel-art category icons (6×6 px each) ──────────────────────────
        // col 0 MISC    = gray cross/plus
        // col 1 BASEFINDING = cyan diamond
        // col 2 RENDER  = purple ring
        // col 3 CLIENT  = teal sparkle/star
        private void drawCatIcon(DrawContext ctx, int ix, int iy, int cat) {
            int c = IC_COL[cat];
            switch (cat) {
                case 0 -> { // MISC – cross/plus
                    ctx.fill(ix+2, iy,   ix+4, iy+2, c); // top arm
                    ctx.fill(ix,   iy+2, ix+6, iy+4, c); // horizontal bar
                    ctx.fill(ix+2, iy+4, ix+4, iy+6, c); // bottom arm
                }
                case 1 -> { // BASEFINDING – diamond
                    ctx.fill(ix+2, iy,   ix+4, iy+1, c);
                    ctx.fill(ix+1, iy+1, ix+5, iy+2, c);
                    ctx.fill(ix,   iy+2, ix+6, iy+4, c);
                    ctx.fill(ix+1, iy+4, ix+5, iy+5, c);
                    ctx.fill(ix+2, iy+5, ix+4, iy+6, c);
                }
                case 2 -> { // RENDER – circle ring
                    ctx.fill(ix+1, iy,   ix+5, iy+1, c); // top arc
                    ctx.fill(ix,   iy+1, ix+1, iy+5, c); // left arc
                    ctx.fill(ix+5, iy+1, ix+6, iy+5, c); // right arc
                    ctx.fill(ix+1, iy+5, ix+5, iy+6, c); // bottom arc
                    // center dot
                    ctx.fill(ix+2, iy+2, ix+4, iy+4, c);
                }
                case 3 -> { // CLIENT – 4-point star/sparkle
                    ctx.fill(ix+2, iy,   ix+4, iy+2, c); // top
                    ctx.fill(ix,   iy+1, ix+2, iy+3, c); // left diagonal
                    ctx.fill(ix+4, iy+1, ix+6, iy+3, c); // right diagonal
                    ctx.fill(ix,   iy+2, ix+6, iy+4, c); // middle bar
                    ctx.fill(ix,   iy+3, ix+2, iy+5, c); // left diagonal lower
                    ctx.fill(ix+4, iy+3, ix+6, iy+5, c); // right diagonal lower
                    ctx.fill(ix+2, iy+4, ix+4, iy+6, c); // bottom
                }
            }
        }

        // Circular toggle dot (6×6, rounded corners removed)
        // 16×8 pill toggle: thumb slides left (off) → right (on)
        private void drawToggle(DrawContext ctx, int x, int y, float t) {
            int bgC = lerp(0xFF161B27, 0xFF003366, t);
            int tmC = lerp(0xFF394050, 0xFF00AAFF, t);
            // Track-Hintergrund (Pill-Form)
            ctx.fill(x+1, y,   x+15, y+8,  bgC);
            ctx.fill(x,   y+1, x+16, y+7,  bgC);
            // Daumen (Pill-Form), 6×6, fährt 8px von links nach rechts
            int tx = x + 1 + (int)(t * 8);
            ctx.fill(tx+1, y+1, tx+5, y+7,  tmC);
            ctx.fill(tx,   y+2, tx+6, y+6,  tmC);
        }

        // ── Render ───────────────────────────────────────────────────────────
        @Override
        public void render(DrawContext ctx, int mx, int my, float delta) {

            // Advance animations
            openAnim = Math.min(1f, openAnim + 0.10f);
            for (int i = 0; i < 8; i++) {
                float t = modOn(i) ? 1f : 0f;
                dotAnim[i] += (t - dotAnim[i]) * 0.22f;
            }
            dotAnim[13] += ((isBonesFarmerActive ? 1f : 0f) - dotAnim[13]) * 0.22f;
            dotAnim[16] += ((isTracersActive      ? 1f : 0f) - dotAnim[16]) * 0.22f;
            dotAnim[18] += ((isSessionFixActive   ? 1f : 0f) - dotAnim[18]) * 0.22f;
            dotAnim[21] += ((wasSafetyLogout      ? 1f : 0f) - dotAnim[21]) * 0.22f;

            // Mouse edge-detection via LWJGL (API-version agnostic)
            long win = client.getWindow().getHandle();
            boolean down  = GLFW.glfwGetMouseButton(win, GLFW.GLFW_MOUSE_BUTTON_LEFT)  == GLFW.GLFW_PRESS;
            boolean rdown = GLFW.glfwGetMouseButton(win, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
            if (down  && !wasMouseDown)  click(mx, my, false);
            if (rdown && !wasMouseDown)  click(mx, my, true);
            wasMouseDown = down || rdown;

            // ── Full-screen overlay ──────────────────────────────────────────
            int oa = (int)(0xBB * openAnim);
            ctx.fill(0, 0, width, height, (oa << 24));

            // Slide columns in from top
            int ySlide = (int)((1f - openAnim) * -24);
            int cw     = cw();

            for (int col = 0; col < CATS.length; col++) {
                int x  = colX(col);
                int y  = GUI_Y + ySlide;
                int ch = colH(col);

                // ── Column body ──────────────────────────────────────────────
                ctx.fill(x, y, x + cw, y + ch, C_COL_BG);

                // ── Header ───────────────────────────────────────────────────
                ctx.fill(x, y, x + cw, y + HEAD_H, C_HEAD_BG);
                // pixel-art icon (6×6), vertically centered in header
                int iy = y + (HEAD_H - 6) / 2;
                drawCatIcon(ctx, x + 5, iy, col);
                // category name starts after icon (5px margin + 6px icon + 4px gap = 15px)
                ctx.drawText(textRenderer, CATS[col], x + 15, y + (HEAD_H-8)/2, C_CAT_TXT, false);
                // "-" collapse button right-aligned
                ctx.drawText(textRenderer, "-", x + cw - 9, y + (HEAD_H-8)/2, C_DASH, false);
                // 1-px separator below header
                ctx.fill(x, y + HEAD_H - 1, x + cw, y + HEAD_H, C_HEAD_SEP);

                // ── Module rows ──────────────────────────────────────────────
                for (int r = 0; r < MODS[col].length; r++) {
                    int mi   = MODS[col][r];
                    int ry   = y + HEAD_H + r * ROW_H;
                    boolean on    = modOn(mi);
                    boolean hover = mx >= x && mx < x+cw && my >= ry && my < ry+ROW_H;

                    boolean isToggle = (mi < 8 || mi == 13 || mi == 16 || mi == 18 || mi == 21);
                    if (on && isToggle) ctx.fill(x, ry, x+cw, ry+ROW_H, C_ROW_ACT);
                    if (hover)         ctx.fill(x, ry, x+cw, ry+ROW_H, C_ROW_HOV);

                    // Modulname kürzen wenn nötig (gilt für alle Zeilen)
                    int tc = (on && isToggle) ? C_MOD_ON : C_MOD_OFF;
                    String drawName = MNAME[mi];
                    // Toggle: 16px + 4px Rand; Pfeil/Label-Zeilen: 6px + 8px Rand
                    int maxW = isToggle ? (cw - PADX - 22) : (cw - PADX - 16);
                    if (textRenderer.getWidth(drawName) > maxW) {
                        while (drawName.length() > 1 && textRenderer.getWidth(drawName + "..") > maxW)
                            drawName = drawName.substring(0, drawName.length() - 1);
                        drawName += "..";
                    }
                    ctx.drawText(textRenderer, drawName, x+PADX, ry+(ROW_H-8)/2, tc, false);

                    // Right-side indicator
                    if (isToggle) {
                        float a  = dotAnim[mi];
                        int   tx = x + cw - 20;          // 16px Toggle + 4px Rand
                        int   ty = ry + (ROW_H - 8) / 2; // vertikal zentriert
                        drawToggle(ctx, tx, ty, a);

                    } else if (mi == 10) {
                        // Freecam key label
                        String kl = isRebindingFreecam ? "..." :
                            "[" + (GLFW.glfwGetKeyName(freecamKey,0)!=null
                                   ? GLFW.glfwGetKeyName(freecamKey,0).toUpperCase() : "KEY") + "]";
                        ctx.drawText(textRenderer, kl,
                            x+cw-textRenderer.getWidth(kl)-5, ry+(ROW_H-8)/2, C_DASH, false);
                    } else if (mi == 14) {
                        // Bones farmer key label
                        String bkl = isRebindingBonesFarmer ? "..." :
                            (bonesFarmerHotkey != GLFW.GLFW_KEY_UNKNOWN && GLFW.glfwGetKeyName(bonesFarmerHotkey, 0) != null
                                ? "[" + GLFW.glfwGetKeyName(bonesFarmerHotkey, 0).toUpperCase() + "]" : "[NONE]");
                        ctx.drawText(textRenderer, bkl,
                            x+cw-textRenderer.getWidth(bkl)-5, ry+(ROW_H-8)/2, C_DASH, false);
                    } else if (mi == 11) {
                        // Reconnect config – open arrow
                        ctx.drawText(textRenderer, ">", x+cw-11, ry+(ROW_H-8)/2, C_DASH, false);
                    } else if (mi == 12) {
                        String hs = isEnteringHoleSize ? (holeSizeInput + "|") : ("[" + minHoleSize + "]");
                        ctx.drawText(textRenderer, hs,
                            x+cw-textRenderer.getWidth(hs)-5, ry+(ROW_H-8)/2,
                            isEnteringHoleSize ? 0xFF44BBFF : C_DASH, false);
                    } else if (mi == 23) {
                        String sm = "[" + sessionFixModeName() + "]";
                        ctx.drawText(textRenderer, sm,
                            x+cw-textRenderer.getWidth(sm)-5, ry+(ROW_H-8)/2, C_DASH, false);
                    } else if (mi == 15) {
                        String dl = isEnteringDropCount ? (dropCountInput + "|") : ("[" + bonesFarmerDropBase + "]");
                        ctx.drawText(textRenderer, dl,
                            x+cw-textRenderer.getWidth(dl)-5, ry+(ROW_H-8)/2,
                            isEnteringDropCount ? 0xFF44BBFF : C_DASH, false);
                    } else {
                        ctx.drawText(textRenderer, ">", x+cw-11, ry+(ROW_H-8)/2, C_DASH, false);
                    }
                }

                // Right border (1 px) – visual column separator
                ctx.fill(x+cw, y, x+cw+1, y+ch, 0xFF0D1220);
            }
        }

        // ── Click handler ─────────────────────────────────────────────────────
        private void click(double mx, double my, boolean rightBtn) {
            int cw     = cw();
            int ySlide = openAnim >= 1f ? 0 : (int)((1f-openAnim)*-24);
            for (int col = 0; col < CATS.length; col++) {
                int x = colX(col);
                int y = GUI_Y + ySlide;
                for (int r = 0; r < MODS[col].length; r++) {
                    int ry = y + HEAD_H + r * ROW_H;
                    if (mx >= x && mx < x+cw && my >= ry && my < ry+ROW_H) {
                        if (rightBtn) modRightClick(MODS[col][r]);
                        else          modToggle(MODS[col][r]);
                    }
                }
            }
        }

        // ── Key handler ───────────────────────────────────────────────────────
        @Override
        public boolean keyPressed(KeyInput input) {
            if (isRebindingFreecam) {
                freecamKey = input.key();
                setKeyBindingBoundKey(freecamKeyBinding, freecamKey);
                saveKeybind();
                isRebindingFreecam = false;
                return true;
            }
            if (isRebindingBonesFarmer) {
                bonesFarmerHotkey = input.key();
                setKeyBindingBoundKey(bonesFarmerKeyBinding, bonesFarmerHotkey);
                saveBonesFarmerKey();
                isRebindingBonesFarmer = false;
                return true;
            }
            if (isEnteringDropCount) {
                int k = input.key();
                if (k >= GLFW.GLFW_KEY_0 && k <= GLFW.GLFW_KEY_9) {
                    if (dropCountInput.length() < 2) dropCountInput += (char)('0' + k - GLFW.GLFW_KEY_0);
                } else if (k >= GLFW.GLFW_KEY_KP_0 && k <= GLFW.GLFW_KEY_KP_9) {
                    if (dropCountInput.length() < 2) dropCountInput += (char)('0' + k - GLFW.GLFW_KEY_KP_0);
                } else if (k == GLFW.GLFW_KEY_BACKSPACE) {
                    if (!dropCountInput.isEmpty()) dropCountInput = dropCountInput.substring(0, dropCountInput.length()-1);
                } else if (k == GLFW.GLFW_KEY_ENTER || k == GLFW.GLFW_KEY_KP_ENTER) {
                    commitDropCount();
                    isEnteringDropCount = false;
                } else if (k == GLFW.GLFW_KEY_ESCAPE) {
                    commitDropCount();
                    isEnteringDropCount = false;
                    client.setScreen(null);
                }
                return true;
            }
            if (isEnteringHoleSize) {
                int k = input.key();
                if (k >= GLFW.GLFW_KEY_0 && k <= GLFW.GLFW_KEY_9) {
                    if (holeSizeInput.length() < 3) holeSizeInput += (char)('0' + k - GLFW.GLFW_KEY_0);
                } else if (k >= GLFW.GLFW_KEY_KP_0 && k <= GLFW.GLFW_KEY_KP_9) {
                    if (holeSizeInput.length() < 3) holeSizeInput += (char)('0' + k - GLFW.GLFW_KEY_KP_0);
                } else if (k == GLFW.GLFW_KEY_BACKSPACE) {
                    if (!holeSizeInput.isEmpty()) holeSizeInput = holeSizeInput.substring(0, holeSizeInput.length()-1);
                } else if (k == GLFW.GLFW_KEY_ENTER || k == GLFW.GLFW_KEY_KP_ENTER) {
                    if (!holeSizeInput.isEmpty()) {
                        try {
                            int v = Integer.parseInt(holeSizeInput);
                            if (v >= 1 && v <= 100) minHoleSize = v;
                        } catch (NumberFormatException ignored) {}
                    }
                    isEnteringHoleSize = false;
                } else if (k == GLFW.GLFW_KEY_ESCAPE) {
                    // Wert übernehmen UND GUI direkt schließen
                    if (!holeSizeInput.isEmpty()) {
                        try {
                            int v = Integer.parseInt(holeSizeInput);
                            if (v >= 1 && v <= 100) minHoleSize = v;
                        } catch (NumberFormatException ignored) {}
                    }
                    isEnteringHoleSize = false;
                    client.setScreen(null);
                }
                return true;
            }
            return super.keyPressed(input);
        }

        @Override public boolean shouldPause() { return false; }
    }
}