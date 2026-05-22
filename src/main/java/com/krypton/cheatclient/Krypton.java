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
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.ItemTags;
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
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class Krypton implements ModInitializer {

    // --- KEYBINDS ---
    private static KeyBinding openGuiKey;
    public static int freecamKey = GLFW.GLFW_KEY_V;
    public static int lastSavedGuiKey = -1;
    private static boolean wasFreecamKeyPressed = false;

    // --- STATUS VARIABLEN ---
    public static boolean isBedrockFinderActive = false;
    public static boolean isPlayerEspActive = false;
    public static boolean isAutoSpawnerActive = false;
    public static boolean isSpawnerEspActive = false;
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
    public static boolean wasSafetyLogout = false;
    public static int reconnectTicks = -1;
    public static int attemptIndex = 0;
    public static net.minecraft.client.network.ServerInfo lastServer = null;

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

    // --- AUTO SPAWNER ---
    private static boolean isMining = false;
    public static boolean hasMinedSpawner = false;
    private static int safetyLogoutTimer = -1;
    private static BlockPos lastTargetSpawner = null;
    private static double targetOffsetX = 0.5;
    private static double targetOffsetY = 0.5;
    private static double targetOffsetZ = 0.5;

    // --- FREECAM VARIABLEN ---
    public static double freecamX, freecamY, freecamZ;
    public static double prevFreecamX, prevFreecamY, prevFreecamZ;
    public static float freecamYaw, freecamPitch;
    public static float prevFreecamYaw, prevFreecamPitch;
    // Eingefrorene Spielerposition (verhindert Fallen durch Gravitation)
    public static double savedPlayerX, savedPlayerY, savedPlayerZ;
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
            writer.write(String.valueOf(isSpawnerEspActive));
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
    // FREECAM LOGIK
    // ==========================================

    public static void toggleFreecam(MinecraftClient client) {
        if (client.world == null || client.player == null) return;

        if (isFreecamActive) {
            freecamX = client.player.getX();
            freecamY = client.player.getEyeY();
            freecamZ = client.player.getZ();
            freecamYaw = client.player.getYaw();
            freecamPitch = client.player.getPitch();
            // Position einfrieren, damit der Spieler nicht fällt
            savedPlayerX = client.player.getX();
            savedPlayerY = client.player.getY();
            savedPlayerZ = client.player.getZ();

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

        openGuiKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.krypton.gui",
                InputUtil.Type.KEYSYM,
                lastSavedGuiKey != -1 ? lastSavedGuiKey : GLFW.GLFW_KEY_RIGHT_SHIFT,
                KeyBinding.Category.MISC
        ));

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> saveCheatStates());

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
            try {
                for (java.lang.reflect.Field f : KeyBinding.class.getDeclaredFields()) {
                    if (f.getType() == InputUtil.Key.class) {
                        f.setAccessible(true);
                        InputUtil.Key key = (InputUtil.Key) f.get(openGuiKey);
                        if (key != null && key != openGuiKey.getDefaultKey()) {
                            if (key.getCode() != lastSavedGuiKey && key.getCode() != -1) {
                                lastSavedGuiKey = key.getCode();
                                saveGuiKey(lastSavedGuiKey);
                            }
                            break;
                        }
                    }
                }
            } catch (Exception e) {}

            // --- SERVER TRACKING FÜR RECONNECT ---
            if (client.getCurrentServerEntry() != null) {
                lastServer = client.getCurrentServerEntry();
            }
            if (client.world != null) {
                wasSafetyLogout = false;
                attemptIndex = 0;
                reconnectTicks = -1;
            }

            // --- AUTO RECONNECT TICK LOGIK ---
            if (client.currentScreen instanceof net.minecraft.client.gui.screen.DisconnectedScreen && !(client.currentScreen instanceof KryptonReconnectScreen)) {
                if (isAutoReconnectActive && !wasSafetyLogout && lastServer != null) {
                    int delay = -1;
                    if (attemptIndex < reconnectDelays.size()) {
                        delay = reconnectDelays.get(attemptIndex);
                    } else if (isInfiniteReconnect && !reconnectDelays.isEmpty()) {
                        delay = reconnectDelays.get(reconnectDelays.size() - 1);
                    }

                    if (delay != -1) {
                        reconnectTicks = delay * 20 + (int)(Math.random() * 30 - 15);
                        if (reconnectTicks < 20) reconnectTicks = 20;

                        Text reasonText = Text.literal("Verbindung vom Server getrennt.");
                        try {
                            for (java.lang.reflect.Field f : net.minecraft.client.gui.screen.DisconnectedScreen.class.getDeclaredFields()) {
                                if (f.getType() == Text.class) {
                                    f.setAccessible(true);
                                    reasonText = (Text) f.get(client.currentScreen);
                                    break;
                                }
                            }
                        } catch (Exception e) {}
                        client.setScreen(new KryptonReconnectScreen(reasonText));
                    }
                }
            }

            if (client.currentScreen instanceof KryptonReconnectScreen) {
                if (reconnectTicks > 0) {
                    reconnectTicks--;
                } else if (reconnectTicks == 0) {
                    reconnectTicks = -1;
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
                        client.setScreen(new net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen(new net.minecraft.client.gui.screen.TitleScreen()));
                    }
                }
            }

            if (client.world == null) {
                isFreecamActive = false;
                hasMinedSpawner = false;
                autoSpawnerState = 0;
                actionDelayTimer = 0;
                sessionSeenPlayers.clear();
                return;
            }

            while (openGuiKey.wasPressed()) {
                if (client.currentScreen == null) client.setScreen(new ClickGuiScreen());
            }

            if (client.getWindow() != null) {
                boolean isFKeyDown = InputUtil.isKeyPressed(client.getWindow(), freecamKey);
                if (isFKeyDown && !wasFreecamKeyPressed && client.currentScreen == null) {
                    isFreecamActive = !isFreecamActive;
                    toggleFreecam(client);
                }
                wasFreecamKeyPressed = isFKeyDown;
            }

            if (isFreecamActive && client.player != null) {

                if (disableFreecamOnDamage && client.player.hurtTime > 0) {
                    isFreecamActive = false;
                    toggleFreecam(client);
                    return;
                }

                // Position und Geschwindigkeit einfrieren → kein Fallen, konsistenter Raycast
                client.player.setPosition(savedPlayerX, savedPlayerY, savedPlayerZ);
                client.player.setVelocity(0, 0, 0);

                prevFreecamX = freecamX;
                prevFreecamY = freecamY;
                prevFreecamZ = freecamZ;
                prevFreecamYaw = freecamYaw;
                prevFreecamPitch = freecamPitch;

                if (client.getWindow() != null) {
                    double[] xpos = new double[1];
                    double[] ypos = new double[1];
                    GLFW.glfwGetCursorPos(client.getWindow().getHandle(), xpos, ypos);

                    if (firstMouseTick) {
                        lastMouseX = xpos[0];
                        lastMouseY = ypos[0];
                        firstMouseTick = false;
                    }

                    if (client.currentScreen == null) {
                        double deltaX = xpos[0] - lastMouseX;
                        double deltaY = ypos[0] - lastMouseY;
                        lastMouseX = xpos[0];
                        lastMouseY = ypos[0];

                        double sens = client.options.getMouseSensitivity().getValue() * 0.6 + 0.2;
                        double mult = sens * sens * sens * 8.0 * 0.15;

                        freecamYaw += (float)(deltaX * mult);
                        freecamPitch += (float)(deltaY * mult * (client.options.getInvertMouseY().getValue() ? -1 : 1));
                        freecamPitch = MathHelper.clamp(freecamPitch, -90f, 90f);
                    } else {
                        lastMouseX = xpos[0];
                        lastMouseY = ypos[0];
                    }
                }

                if (client.currentScreen == null) {
                    boolean isLeftClicking = GLFW.glfwGetMouseButton(client.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
                    boolean isRightClicking = GLFW.glfwGetMouseButton(client.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;

                    if (isLeftClicking || isRightClicking) {
                        // Ray vom eingefrorenen Auge des Spielers in Freecam-Blickrichtung.
                        // Position ist eingefroren → Server-Reichweite stimmt immer.
                        Vec3d start = client.player.getEyePos();
                        Vec3d dir   = Vec3d.fromPolar(freecamPitch, freecamYaw);
                        double reach = client.player.getBlockInteractionRange();
                        Vec3d end = start.add(dir.multiply(reach));

                        BlockHitResult hit = client.world.raycast(new RaycastContext(start, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, client.player));

                        if (hit.getType() == HitResult.Type.BLOCK && client.interactionManager != null) {
                            isManualInteraction = true;

                            if (isLeftClicking) {
                                client.interactionManager.updateBlockBreakingProgress(hit.getBlockPos(), hit.getSide());
                                client.player.swingHand(Hand.MAIN_HAND);
                            } else if (isRightClicking && rightClickCooldown <= 0) {
                                client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit);
                                client.player.swingHand(Hand.MAIN_HAND);
                                rightClickCooldown = 4;
                            }

                            isManualInteraction = false;
                        }
                    } else {
                        if (client.interactionManager != null) {
                            client.interactionManager.cancelBlockBreaking();
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
                    if (p != client.player && !whitelistedPlayers.contains(p.getName().getString().toLowerCase())) {
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

            if (isSpawnerEspActive || isAutoSpawnerActive) {
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
                        // Staff → Guard aus, still halten, nichts abbauen
                        isAutoSpawnerActive = false;
                        client.options.attackKey.setPressed(false);
                        client.options.sneakKey.setPressed(false);
                        if (client.interactionManager != null) client.interactionManager.cancelBlockBreaking();
                        isMining = false;
                        hasMinedSpawner = false;
                        autoSpawnerState = 0;
                        actionDelayTimer = 0;
                        lastTargetSpawner = null;
                        safetyLogoutTimer = -1;
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

                BlockPos spawnerPos = null;
                if (enemyFound) {
                    for (int x = -4; x <= 4; x++) {
                        for (int y = -4; y <= 4; y++) {
                            for (int z = -4; z <= 4; z++) {
                                BlockPos checkPos = client.player.getBlockPos().add(x, y, z);
                                if (client.world.getBlockState(checkPos).isOf(Blocks.SPAWNER)) {
                                    if (client.world.raycast(new RaycastContext(client.player.getEyePos(), new Vec3d(checkPos.getX()+0.5, checkPos.getY()+0.5, checkPos.getZ()+0.5), RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, client.player)).getType() == HitResult.Type.BLOCK) {
                                        spawnerPos = checkPos;
                                        break;
                                    }
                                }
                            }
                            if (spawnerPos != null) break;
                        }
                        if (spawnerPos != null) break;
                    }
                }

                if (isMining && lastTargetSpawner != null) {
                    if (!client.world.getBlockState(lastTargetSpawner).isOf(Blocks.SPAWNER)) {
                        client.options.attackKey.setPressed(false);
                        client.options.sneakKey.setPressed(false);
                        isMining = false;
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
                            client.options.sneakKey.setPressed(true);
                            autoSpawnerState = 4;
                            actionDelayTimer = 1 + (int)(Math.random() * 2);
                            break;

                        case 4:
                            client.options.attackKey.setPressed(true);
                            isMining = true;
                            autoSpawnerState = 5;
                            break;

                        case 5:
                            client.options.attackKey.setPressed(true);

                            long time = System.currentTimeMillis();
                            float rawYawDrift = (float) (Math.sin(time / 250.0) * 0.06);
                            float rawPitchDrift = (float) (Math.cos(time / 300.0) * 0.06);

                            float sensDrift = client.options.getMouseSensitivity().getValue().floatValue();
                            float fDrift = sensDrift * 0.6F + 0.2F;
                            float gcdDrift = fDrift * fDrift * fDrift * 8.0F * 0.15F;

                            float safeYawDrift = rawYawDrift - (rawYawDrift % gcdDrift);
                            float safePitchDrift = rawPitchDrift - (rawPitchDrift % gcdDrift);

                            client.player.setYaw(client.player.getYaw() + safeYawDrift);
                            client.player.setPitch(client.player.getPitch() + safePitchDrift);
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
                            client.options.sneakKey.setPressed(false);
                            isMining = false;
                        }
                        autoSpawnerState = 0;
                        actionDelayTimer = 0;
                        isAutoSpawnerActive = false;
                        wasSafetyLogout = true;
                        safetyLogoutTimer = -1;
                        lastTargetSpawner = null;
                        hasMinedSpawner = false;

                        if (client.getNetworkHandler() != null) {
                            client.getNetworkHandler().getConnection().disconnect(Text.literal("§aAlle Spawner im Umkreis gesichert! §4Notfall-Logout."));
                        }
                    }
                } else {
                    safetyLogoutTimer = -1;
                    autoSpawnerState = 0;
                    actionDelayTimer = 0;
                    lastTargetSpawner = null;
                    if (isMining) {
                        client.options.attackKey.setPressed(false);
                        client.options.sneakKey.setPressed(false);
                        isMining = false;
                    }
                }
            } else {
                safetyLogoutTimer = -1;
                autoSpawnerState = 0;
                actionDelayTimer = 0;
                lastTargetSpawner = null;
                if (isMining) {
                    client.options.attackKey.setPressed(false);
                    client.options.sneakKey.setPressed(false);
                    isMining = false;
                }
            }

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
            if (isFreecamActive) activeCheats.add("Freecam: §aON");
            if (isFullbrightActive) activeCheats.add("Fullbright: §eON");
            if (isAutoSpawnerActive) activeCheats.add("Guard: §eON");
            if (isSpawnerEspActive) activeCheats.add("Spawner ESP: §dON");
            if (isAutoReconnectActive) activeCheats.add("Reconnect: §aON");

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
                GL11.glLineWidth(2.5f);
                VertexConsumer lines = imm.getBuffer(RenderLayers.lines());
                for (net.minecraft.entity.Entity ent : mc.world.getEntities()) {
                    if (!(ent instanceof PlayerEntity)) continue;
                    PlayerEntity p = (PlayerEntity) ent;
                    if (p == mc.player || p.getId() < 0) continue;

                    Box b = p.getBoundingBox().offset(-cam.x, -cam.y, -cam.z);
                    String lowerName = p.getName().getString().toLowerCase();
                    boolean isWhitelisted = whitelistedPlayers.contains(lowerName);

                    int color = isWhitelisted ? 0xFF00FF80 : 0xFF0080FF;

                    // Body hitbox
                    VertexRendering.drawOutline(st, lines,
                        VoxelShapes.cuboid(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ),
                        0, 0, 0, color, 1.0f);
                    // Head box
                    double hcx = (b.minX + b.maxX) / 2.0;
                    double hcz = (b.minZ + b.maxZ) / 2.0;
                    VertexRendering.drawOutline(st, lines,
                        VoxelShapes.cuboid(hcx-0.3, b.maxY-0.5, hcz-0.3, hcx+0.3, b.maxY, hcz+0.3),
                        0, 0, 0, color, 1.0f);
                }
                imm.draw();
                GL11.glLineWidth(1.0f);
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

    private static String getPlayerRank(MinecraftClient client, PlayerEntity p) {
        StringBuilder combined = new StringBuilder();

        // 1. Tab-Listen Display-Name
        if (client.getNetworkHandler() != null) {
            net.minecraft.client.network.PlayerListEntry entry =
                client.getNetworkHandler().getPlayerListEntry(p.getUuid());
            if (entry != null && entry.getDisplayName() != null)
                combined.append(entry.getDisplayName().getString()).append(" ");
        }

        // 2. Entity Display-Name (Name über dem Kopf)
        combined.append(p.getDisplayName().getString()).append(" ");

        // 3. Scoreboard Team
        net.minecraft.scoreboard.Team team = p.getScoreboardTeam();
        if (team != null) {
            combined.append(team.getName()).append(" ");
            combined.append(team.getDisplayName().getString()).append(" ");
        }

        String display = combined.toString().toLowerCase();

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
        private ButtonWidget reconnectBtn;

        public KryptonReconnectScreen(Text reason) {
            super(Text.literal("Connection Lost"));
            this.reason = reason;
        }

        @Override
        protected void init() {
            int cX = width / 2;
            int cY = height / 2;

            reconnectBtn = addDrawableChild(ButtonWidget.builder(Text.literal("Reconnect in " + (reconnectTicks / 20) + "..."), b -> {
                reconnectTicks = 0;
            }).dimensions(cX - 100, cY + 20, 200, 20).build());

            addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), b -> {
                isAutoReconnectActive = false;
                saveReconnect();
                client.setScreen(new net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen(new net.minecraft.client.gui.screen.TitleScreen()));
            }).dimensions(cX - 100, cY + 45, 200, 20).build());
        }

        @Override
        public void render(DrawContext c, int mouseX, int mouseY, float delta) {
            super.render(c, mouseX, mouseY, delta);
            c.drawCenteredTextWithShadow(textRenderer, title, width / 2, height / 2 - 50, 0xFFFFFF);
            c.drawCenteredTextWithShadow(textRenderer, reason, width / 2, height / 2 - 30, 0xAAAAAA);
            if (reconnectTicks >= 0 && reconnectBtn != null) {
                reconnectBtn.setMessage(Text.literal("Reconnect in " + (reconnectTicks / 20) + "..."));
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
            String title = "§eLetzte 20 Sichtungen:";
            c.drawText(textRenderer, title, (width - textRenderer.getWidth(title)) / 2, 20, -1, true);
            int y = 38;
            for (int i = 0; i < Math.min(playerHistory.size(), 20); i++) {
                String entry = playerHistory.get(i);
                c.drawText(textRenderer, entry, (width - textRenderer.getWidth(entry)) / 2, y, -1, true);
                y += 12;
            }
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
        private float   openAnim          = 0f;
        private boolean isRebindingFreecam = false;
        private boolean isEnteringHoleSize = false;
        private String  holeSizeInput      = "";
        private boolean wasMouseDown       = false;
        private final float[] dotAnim     = new float[8]; // per-module 0→1

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
        //     11=ReconnectCfg(screen)  12=HoleSize(cycle +1)  13=Logo(texture header)
        private static final String[] CATS  = { "MISC",  "BASEFINDING", "RENDER",  "CLIENT" };
        private static final int[]    IC_COL = { 0xFF8B8FA8, 0xFF44BBFF, 0xFFAA55FF, 0xFF44CCFF };
        private static final int[][]  MODS   = {
            //  MISC: Freecam, FreecamKey, DisableOnDmg, AutoSpawner, AutoReconnect, ReconnectSet
            { 0, 10, 3, 1, 2, 11 },
            //  BASEFINDING: only SpawnerESP
            { 6 },
            //  RENDER: PlayerESP, Fullbright, BedrockFinder, MinHoleSize
            { 5, 7, 4, 12 },
            //  CLIENT: PlayerLogs, LogoutLogs
            { 8, 9 }
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
            /* 11*/ "AUTO RECONNECT SET",
            /* 12*/ "MIN HOLE SIZE"
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
                case 7 -> isFullbrightActive;
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
                case 8  ->   client.setScreen(new PlayerLogScreen(this));
                case 9  ->   client.setScreen(new LogoutLogScreen(this));
                case 10 ->   isRebindingFreecam = true;
                case 11 ->   client.setScreen(new ReconnectSettingsScreen(this));
                case 12 -> { isEnteringHoleSize = true; holeSizeInput = ""; }
            }
        }

        private void modRightClick(int i) {
            // no right-click actions currently
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

                    if (on && mi < 8) ctx.fill(x, ry, x+cw, ry+ROW_H, C_ROW_ACT);
                    if (hover)        ctx.fill(x, ry, x+cw, ry+ROW_H, C_ROW_HOV);

                    // Modulname – bei Toggle-Zeilen kürzen, damit kein Überlappen
                    int tc = (on && mi < 8) ? C_MOD_ON : C_MOD_OFF;
                    String drawName = MNAME[mi];
                    if (mi < 8) {
                        int maxW = cw - PADX - 22; // 16px Toggle + 4px Abstand + PADX
                        while (drawName.length() > 1 && textRenderer.getWidth(drawName) > maxW)
                            drawName = drawName.substring(0, drawName.length() - 1);
                    }
                    ctx.drawText(textRenderer, drawName, x+PADX, ry+(ROW_H-8)/2, tc, false);

                    // Right-side indicator
                    if (mi < 8) {
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
                    } else if (mi == 11) {
                        // Reconnect config – open arrow
                        ctx.drawText(textRenderer, ">", x+cw-11, ry+(ROW_H-8)/2, C_DASH, false);
                    } else if (mi == 12) {
                        String hs = isEnteringHoleSize ? (holeSizeInput + "|") : ("[" + minHoleSize + "]");
                        ctx.drawText(textRenderer, hs,
                            x+cw-textRenderer.getWidth(hs)-5, ry+(ROW_H-8)/2,
                            isEnteringHoleSize ? 0xFF44BBFF : C_DASH, false);
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
                saveKeybind();
                isRebindingFreecam = false;
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
                } else if (k == GLFW.GLFW_KEY_ENTER || k == GLFW.GLFW_KEY_KP_ENTER || k == GLFW.GLFW_KEY_ESCAPE) {
                    if (!holeSizeInput.isEmpty()) {
                        try {
                            int v = Integer.parseInt(holeSizeInput);
                            if (v >= 1 && v <= 100) minHoleSize = v;
                        } catch (NumberFormatException ignored) {}
                    }
                    isEnteringHoleSize = false;
                }
                return true;
            }
            return super.keyPressed(input);
        }

        @Override public boolean shouldPause() { return false; }
    }
}