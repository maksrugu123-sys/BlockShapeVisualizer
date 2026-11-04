package net.blockshape.client;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import com.mojang.blaze3d.vertex.PoseStack;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** Client-only block collision-shape visualizer for Minecraft 26.3. */
public final class BlockShapeClient implements ClientModInitializer {
    private static final int MIN_RADIUS = 2;
    private static final int MAX_RADIUS = 16;
    private static final int REFRESH_INTERVAL_TICKS = 5;
    private static final Path CONFIG_FILE = FabricLoader.getInstance().getConfigDir()
            .resolve("blockshape-visualizer.properties");

    private static boolean enabled = true;
    private static int radius = 6;
    private static float lineWidth = 2.0f;
    private static boolean perBlockColors = true;
    private static int fixedColor = 0xFF35D7FF;
    private static int ticksSinceRefresh = REFRESH_INTERVAL_TICKS;
    private static List<ShapeEntry> cachedShapes = List.of();

    private record ShapeEntry(BlockPos pos, VoxelShape shape, int color) {}

    @Override
    public void onInitializeClient() {
        loadConfig();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level == null || client.player == null || !enabled) {
                cachedShapes = List.of();
                ticksSinceRefresh = REFRESH_INTERVAL_TICKS;
                return;
            }
            if (++ticksSinceRefresh >= REFRESH_INTERVAL_TICKS) {
                ticksSinceRefresh = 0;
                rebuildCache(client.level, client.player);
            }
        });

        LevelRenderEvents.COLLECT_SUBMITS.register(this::submitShapes);
        registerCommands();
    }

    private static void rebuildCache(Level level, Player player) {
        int r = radius;
        BlockPos origin = player.blockPosition();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        CollisionContext collisionContext = CollisionContext.of(player);
        List<ShapeEntry> result = new ArrayList<>();
        int radiusSquared = r * r;

        for (int y = -r; y <= r; y++) {
            for (int z = -r; z <= r; z++) {
                for (int x = -r; x <= r; x++) {
                    if (x * x + y * y + z * z > radiusSquared) continue;
                    cursor.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    if (!level.hasChunkAt(cursor)) continue;

                    BlockState state = level.getBlockState(cursor);
                    VoxelShape shape = state.getCollisionShape(level, cursor, collisionContext);
                    if (shape.isEmpty()) continue;

                    // Keep candidates in the forward hemisphere. Actual visibility is
                    // determined per pixel by the normal depth-tested outline renderer.
                    // Do not ray-filter individual shapes here: a center/face ray can miss
                    // a genuinely visible edge or partial surface and create gaps.
                    if (!isInForwardHemisphere(player, cursor, shape)) continue;

                    int color = perBlockColors ? colorForBlock(state) : fixedColor;
                    result.add(new ShapeEntry(cursor.immutable(), shape, color));
                }
            }
        }
        cachedShapes = List.copyOf(result);
    }

    /**
     * Restrict outline candidates to the forward 180-degree field around the
     * player's look direction. Per-pixel depth testing in the renderer decides
     * which parts of each shape are actually visible.
     */
    private static boolean isInForwardHemisphere(Player player, BlockPos pos, VoxelShape shape) {
        AABB bounds = shape.bounds();
        Vec3 center = new Vec3(
                pos.getX() + (bounds.minX + bounds.maxX) * 0.5,
                pos.getY() + (bounds.minY + bounds.maxY) * 0.5,
                pos.getZ() + (bounds.minZ + bounds.maxZ) * 0.5
        );
        Vec3 toBlock = center.subtract(player.getEyePosition());
        return toBlock.lengthSqr() >= 1.0E-6
                && toBlock.normalize().dot(player.getLookAngle()) >= 0.0;
    }

    private static int colorForBlock(BlockState state) {
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        int hash = id.hashCode();
        float hue = Math.floorMod(hash, 360) / 360.0f;
        int rgb = hsvToRgb(hue, 0.82f, 1.0f);
        return 0xFF000000 | rgb;
    }

    private static int hsvToRgb(float hue, float saturation, float value) {
        float scaled = hue * 6.0f;
        int sector = (int) Math.floor(scaled);
        float fraction = scaled - sector;
        float p = value * (1.0f - saturation);
        float q = value * (1.0f - saturation * fraction);
        float t = value * (1.0f - saturation * (1.0f - fraction));
        float red, green, blue;
        switch (Math.floorMod(sector, 6)) {
            case 0 -> { red = value; green = t; blue = p; }
            case 1 -> { red = q; green = value; blue = p; }
            case 2 -> { red = p; green = value; blue = t; }
            case 3 -> { red = p; green = q; blue = value; }
            case 4 -> { red = t; green = p; blue = value; }
            default -> { red = value; green = p; blue = q; }
        }
        return ((int) (red * 255.0f) << 16) | ((int) (green * 255.0f) << 8) | (int) (blue * 255.0f);
    }

    private void submitShapes(LevelRenderContext context) {
        if (!enabled || cachedShapes.isEmpty()) return;

        Vec3 camera = context.levelState().cameraRenderState.pos;
        PoseStack poseStack = context.poseStack();
        SubmitNodeCollector collector = context.submitNodeCollector();

        for (ShapeEntry entry : cachedShapes) {
            poseStack.pushPose();
            poseStack.translate(
                    entry.pos().getX() - camera.x,
                    entry.pos().getY() - camera.y,
                    entry.pos().getZ() - camera.z
            );
            collector.submitShapeOutline(
                    poseStack,
                    entry.shape(),
                    RenderTypes.lines(),
                    entry.color(),
                    lineWidth,
                    false
            );
            poseStack.popPose();
        }
    }

    private static void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("bshape")
                        .executes(context -> {
                            feedback(context.getSource(), "BlockShape: " + (enabled ? "ON" : "OFF")
                                    + ", radius=" + radius + ", width=" + lineWidth
                                    + ", colors=" + (perBlockColors ? "per-block" : "fixed"));
                            feedback(context.getSource(), "Commands: /bshape on|off|toggle|radius <2..16>|width <1..6>|color rainbow|color RRGGBB");
                            return 1;
                        })
                        .then(ClientCommands.literal("on").executes(context -> setEnabled(context.getSource(), true)))
                        .then(ClientCommands.literal("off").executes(context -> setEnabled(context.getSource(), false)))
                        .then(ClientCommands.literal("toggle").executes(context -> setEnabled(context.getSource(), !enabled)))
                        .then(ClientCommands.literal("radius").then(ClientCommands.argument("blocks", IntegerArgumentType.integer(MIN_RADIUS, MAX_RADIUS))
                                .executes(context -> {
                                    radius = IntegerArgumentType.getInteger(context, "blocks");
                                    ticksSinceRefresh = REFRESH_INTERVAL_TICKS;
                                    saveConfig();
                                    feedback(context.getSource(), "Display radius: " + radius + " blocks");
                                    return 1;
                                })))
                        .then(ClientCommands.literal("width").then(ClientCommands.argument("pixels", FloatArgumentType.floatArg(1.0f, 6.0f))
                                .executes(context -> {
                                    lineWidth = FloatArgumentType.getFloat(context, "pixels");
                                    saveConfig();
                                    feedback(context.getSource(), "Outline width: " + lineWidth);
                                    return 1;
                                })))
                        .then(ClientCommands.literal("color").then(ClientCommands.argument("value", StringArgumentType.word())
                                .executes(context -> {
                                    String value = StringArgumentType.getString(context, "value");
                                    if (value.equalsIgnoreCase("rainbow")) {
                                        perBlockColors = true;
                                        saveConfig();
                                        feedback(context.getSource(), "Each block type now has its own stable color");
                                        return 1;
                                    }
                                    String hex = value.startsWith("#") ? value.substring(1) : value;
                                    if (!hex.matches("[0-9a-fA-F]{6}")) {
                                        context.getSource().sendError(Component.literal("[BlockShape] Use /bshape color rainbow or /bshape color RRGGBB"));
                                        return 0;
                                    }
                                    fixedColor = 0xFF000000 | Integer.parseInt(hex, 16);
                                    perBlockColors = false;
                                    saveConfig();
                                    feedback(context.getSource(), "All block outlines color: #" + hex.toUpperCase());
                                    return 1;
                                })))
                )
        );
    }

    private static int setEnabled(FabricClientCommandSource source, boolean value) {
        enabled = value;
        ticksSinceRefresh = REFRESH_INTERVAL_TICKS;
        saveConfig();
        feedback(source, "BlockShape " + (enabled ? "enabled" : "disabled"));
        return 1;
    }

    private static void feedback(FabricClientCommandSource source, String message) {
        source.sendFeedback(Component.literal("[BlockShape] " + message));
    }

    private static void loadConfig() {
        if (!Files.exists(CONFIG_FILE)) return;
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(CONFIG_FILE)) {
            properties.load(reader);
            enabled = Boolean.parseBoolean(properties.getProperty("enabled", "true"));
            radius = clamp(parseInt(properties.getProperty("radius"), 6), MIN_RADIUS, MAX_RADIUS);
            lineWidth = Math.max(1.0f, Math.min(6.0f, parseFloat(properties.getProperty("lineWidth"), 2.0f)));
            perBlockColors = Boolean.parseBoolean(properties.getProperty("perBlockColors", "true"));
            fixedColor = (int) Long.parseLong(properties.getProperty("fixedColor", "FF35D7FF"), 16);
            fixedColor |= 0xFF000000;
        } catch (Exception exception) {
            System.err.println("[BlockShape] Could not read config: " + exception.getMessage());
        }
    }

    private static void saveConfig() {
        Properties properties = new Properties();
        properties.setProperty("enabled", Boolean.toString(enabled));
        properties.setProperty("radius", Integer.toString(radius));
        properties.setProperty("lineWidth", Float.toString(lineWidth));
        properties.setProperty("perBlockColors", Boolean.toString(perBlockColors));
        properties.setProperty("fixedColor", String.format("%06X", fixedColor & 0x00FFFFFF));
        try {
            Files.createDirectories(CONFIG_FILE.getParent());
            try (Writer writer = Files.newBufferedWriter(CONFIG_FILE)) {
                properties.store(writer, "Block Shape Visualizer settings");
            }
        } catch (IOException exception) {
            System.err.println("[BlockShape] Could not save config: " + exception.getMessage());
        }
    }

    private static int parseInt(String value, int fallback) {
        try { return Integer.parseInt(value); } catch (Exception ignored) { return fallback; }
    }

    private static float parseFloat(String value, float fallback) {
        try { return Float.parseFloat(value); } catch (Exception ignored) { return fallback; }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
