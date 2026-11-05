package net.blockshape.client;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.model.BlockDisplayContext;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.renderer.block.model.BlockStateModelWrapper;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.fabricmc.fabric.api.client.renderer.v1.Renderer;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableMesh;
import net.fabricmc.fabric.api.client.renderer.v1.model.FabricBlockStateModel;
import net.fabricmc.fabric.api.client.renderer.v1.render.AltModelBlockRenderer;
import net.fabricmc.fabric.api.client.renderer.v1.render.ChunkSectionLayerHelper;
import net.fabricmc.fabric.api.client.renderer.v1.render.FabricOrderedSubmitNodeCollector;

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
    private static final int MIN_VERTICAL_RANGE = 0;
    private static final int MAX_VERTICAL_RANGE = 16;
    private static final int REFRESH_INTERVAL_TICKS = 5;
    private static final Path CONFIG_FILE = FabricLoader.getInstance().getConfigDir()
            .resolve("blockshape-visualizer.properties");
    private static final BlockDisplayContext BLOCK_DISPLAY_CONTEXT = BlockDisplayContext.create();
    private static final BlockModelRenderState SPECIAL_MODEL_RENDER_STATE = new BlockModelRenderState();

    private static MutableMesh hiddenTextureMesh;
    private static AltModelBlockRenderer hiddenTextureRenderer;
    private static boolean hiddenTextureRendererAmbientOcclusion;
    private static boolean hiddenTextureRendererInitialized;
    private static boolean cachedAmbientOcclusion;

    private static boolean enabled = true;
    private static boolean renderHiddenTextures = true;
    private static int radius = 6;
    private static int downRange = 3;
    private static int upRange = 3;
    private static float lineWidth = 2.0f;
    private static boolean perBlockColors = true;
    private static int fixedColor = 0xFF35D7FF;
    private static int ticksSinceRefresh = REFRESH_INTERVAL_TICKS;
    private static List<ShapeEntry> cachedShapes = List.of();

    private record ShapeEntry(
            BlockPos pos,
            BlockState state,
            VoxelShape shape,
            int color,
            Mesh hiddenTextureMesh,
            boolean translucentHiddenTexture,
            boolean specialHiddenTexture
    ) {}

    private record CachedTexture(Mesh mesh, boolean translucent, boolean specialModel) {}

    @Override
    public void onInitializeClient() {
        loadConfig();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level == null || client.player == null || !enabled) {
                cachedShapes = List.of();
                ticksSinceRefresh = REFRESH_INTERVAL_TICKS;
                return;
            }
            boolean ambientOcclusion = client.options.ambientOcclusion().get();
            if (++ticksSinceRefresh >= REFRESH_INTERVAL_TICKS
                    || cachedAmbientOcclusion != ambientOcclusion) {
                ticksSinceRefresh = 0;
                rebuildCache(client.level, client.player);
            }
        });

        LevelRenderEvents.COLLECT_SUBMITS.register(this::submitShapes);
        registerCommands();
    }

    private static void rebuildCache(ClientLevel level, Player player) {
        cachedAmbientOcclusion = Minecraft.getInstance().options.ambientOcclusion().get();
        int r = radius;
        int down = downRange;
        int up = upRange;
        BlockPos origin = player.blockPosition();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        CollisionContext collisionContext = CollisionContext.of(player);
        List<ShapeEntry> result = new ArrayList<>();
        int radiusSquared = r * r;

        // Vertical limits are independent of the horizontal search radius.
        // Defaults show up to 3 blocks above and 3 blocks below the player.
        for (int y = -down; y <= up; y++) {
            for (int z = -r; z <= r; z++) {
                for (int x = -r; x <= r; x++) {
                    if (x * x + z * z > radiusSquared) continue;
                    cursor.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    if (!level.hasChunkAt(cursor)) continue;

                    BlockState state = level.getBlockState(cursor);
                    VoxelShape shape = state.getCollisionShape(level, cursor, collisionContext);
                    if (shape.isEmpty()) continue;

                    int color = perBlockColors ? colorForBlock(state) : fixedColor;
                    BlockPos pos = cursor.immutable();
                    Mesh textureMesh = null;
                    boolean translucentTexture = false;
                    boolean specialTexture = false;
                    if (renderHiddenTextures && state.getRenderShape() == RenderShape.INVISIBLE) {
                        CachedTexture texture = buildHiddenTextureMesh(level, pos, state);
                        textureMesh = texture.mesh();
                        translucentTexture = texture.translucent();
                        specialTexture = texture.specialModel();
                    }
                    result.add(new ShapeEntry(
                            pos, state, shape, color,
                            textureMesh, translucentTexture, specialTexture
                    ));
                }
            }
        }
        cachedShapes = List.copyOf(result);
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

        Level level = Minecraft.getInstance().level;
        if (level == null) return;

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

            // BlockHider marks world blocks INVISIBLE but keeps their baked models
            // and the block texture atlas loaded. Submit those original models here.
            // Normal visible blocks stay on the vanilla path, avoiding z-fighting.
            if (renderHiddenTextures && entry.state().getRenderShape() == RenderShape.INVISIBLE) {
                try {
                    if (entry.hiddenTextureMesh() != null) {
                        submitCachedHiddenBlockTexture(entry, poseStack, collector);
                    } else if (entry.specialHiddenTexture()) {
                        submitSpecialHiddenBlockTexture(entry, poseStack, collector);
                    }
                } catch (RuntimeException ignored) {
                    // An unsupported third-party model must not break the whole world render.
                }
            }

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

    /**
     * Builds each hidden block's immutable world mesh only when the nearby-block
     * cache is refreshed. Reusing that mesh between frames avoids rebuilding
     * hundreds of block models on every render frame.
     */
    private static CachedTexture buildHiddenTextureMesh(
            ClientLevel clientLevel,
            BlockPos pos,
            BlockState state
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        boolean ambientOcclusion = minecraft.options.ambientOcclusion().get();
        Renderer renderer = Renderer.get();

        if (hiddenTextureMesh == null) {
            hiddenTextureMesh = renderer.mutableMesh();
        }
        if (!hiddenTextureRendererInitialized
                || hiddenTextureRendererAmbientOcclusion != ambientOcclusion) {
            hiddenTextureRenderer = renderer.altModelBlockRenderer(
                    ambientOcclusion,
                    true,
                    minecraft.getBlockColors()
            );
            hiddenTextureRendererAmbientOcclusion = ambientOcclusion;
            hiddenTextureRendererInitialized = true;
        }

        BlockModel displayModel = minecraft.getModelManager()
                .getBlockModelSet().get(state);

        // Keep vanilla's special display path for chests, heads, bells, portals, etc.
        if (!(displayModel instanceof BlockStateModelWrapper)) {
            return new CachedTexture(null, false, true);
        }

        BlockStateModel model = minecraft.getModelManager()
                .getBlockStateModelSet().get(state);
        BlockAndTintGetter blockView = clientLevel;
        long seed = state.getSeed(pos);
        boolean translucent = ((FabricBlockStateModel) model).hasMaterialFlag(
                blockView,
                pos,
                state,
                RandomSource.createThreadLocalInstance(seed),
                BakedQuad.FLAG_TRANSLUCENT
        );

        // immutableCopy() preserves its source mesh. Clear it before and after
        // each block so geometry can never accumulate between blocks or frames.
        hiddenTextureMesh.clear();
        try {
            hiddenTextureRenderer.tesselateBlock(
                    hiddenTextureMesh.emitter(),
                    0.0f,
                    0.0f,
                    0.0f,
                    blockView,
                    pos,
                    state,
                    model,
                    seed
            );

            if (hiddenTextureMesh.size() == 0) {
                return new CachedTexture(null, false, false);
            }
            return new CachedTexture(hiddenTextureMesh.immutableCopy(), translucent, false);
        } finally {
            hiddenTextureMesh.clear();
        }
    }

    private static void submitCachedHiddenBlockTexture(
            ShapeEntry entry,
            PoseStack poseStack,
            SubmitNodeCollector collector
    ) {
        ((FabricOrderedSubmitNodeCollector) collector).submitBlockModel(
                poseStack,
                ChunkSectionLayerHelper::getMovingBlockRenderType,
                entry.translucentHiddenTexture(),
                List.of(),
                entry.hiddenTextureMesh(),
                new int[0],
                0,
                OverlayTexture.NO_OVERLAY,
                0
        );
    }

    /** Special baked models retain the normal vanilla display-state submission path. */
    private static void submitSpecialHiddenBlockTexture(
            ShapeEntry entry,
            PoseStack poseStack,
            SubmitNodeCollector collector
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel clientLevel = minecraft.level;
        if (clientLevel == null) return;

        BlockModel displayModel = minecraft.getModelManager()
                .getBlockModelSet().get(entry.state());
        SPECIAL_MODEL_RENDER_STATE.clear();
        displayModel.update(
                SPECIAL_MODEL_RENDER_STATE,
                entry.state(),
                BLOCK_DISPLAY_CONTEXT,
                entry.state().getSeed(entry.pos())
        );
        SPECIAL_MODEL_RENDER_STATE.submit(
                poseStack,
                collector,
                LightCoordsUtil.getLightCoords(clientLevel, entry.pos()),
                OverlayTexture.NO_OVERLAY,
                0
        );
    }

    private static void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("bshape")
                        .executes(context -> {
                            feedback(context.getSource(), "BlockShape: " + (enabled ? "ON" : "OFF")
                                    + ", radius=" + radius + ", down=" + downRange + ", up=" + upRange + ", width=" + lineWidth
                                    + ", colors=" + (perBlockColors ? "per-block" : "fixed")
                                    + ", hidden textures=" + (renderHiddenTextures ? "ON" : "OFF"));
                            feedback(context.getSource(), "Commands: /bshape on|off|toggle|textures on|off|radius <2..16>|down <0..16>|up <0..16>|width <1..6>|color rainbow|color RRGGBB");
                            return 1;
                        })
                        .then(ClientCommands.literal("on").executes(context -> setEnabled(context.getSource(), true)))
                        .then(ClientCommands.literal("off").executes(context -> setEnabled(context.getSource(), false)))
                        .then(ClientCommands.literal("toggle").executes(context -> setEnabled(context.getSource(), !enabled)))
                        .then(ClientCommands.literal("textures")
                                .then(ClientCommands.literal("on").executes(context -> setHiddenTextures(context.getSource(), true)))
                                .then(ClientCommands.literal("off").executes(context -> setHiddenTextures(context.getSource(), false))))
                        .then(ClientCommands.literal("radius").then(ClientCommands.argument("blocks", IntegerArgumentType.integer(MIN_RADIUS, MAX_RADIUS))
                                .executes(context -> {
                                    radius = IntegerArgumentType.getInteger(context, "blocks");
                                    ticksSinceRefresh = REFRESH_INTERVAL_TICKS;
                                    saveConfig();
                                    feedback(context.getSource(), "Display radius: " + radius + " blocks");
                                    return 1;
                                })))
                        .then(ClientCommands.literal("down").then(ClientCommands.argument("blocks", IntegerArgumentType.integer(MIN_VERTICAL_RANGE, MAX_VERTICAL_RANGE))
                                .executes(context -> {
                                    downRange = IntegerArgumentType.getInteger(context, "blocks");
                                    ticksSinceRefresh = REFRESH_INTERVAL_TICKS;
                                    saveConfig();
                                    feedback(context.getSource(), "Visible range below player: " + downRange + " blocks");
                                    return 1;
                                })))
                        .then(ClientCommands.literal("up").then(ClientCommands.argument("blocks", IntegerArgumentType.integer(MIN_VERTICAL_RANGE, MAX_VERTICAL_RANGE))
                                .executes(context -> {
                                    upRange = IntegerArgumentType.getInteger(context, "blocks");
                                    ticksSinceRefresh = REFRESH_INTERVAL_TICKS;
                                    saveConfig();
                                    feedback(context.getSource(), "Visible range above player: " + upRange + " blocks");
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

    private static int setHiddenTextures(FabricClientCommandSource source, boolean value) {
        renderHiddenTextures = value;
        ticksSinceRefresh = REFRESH_INTERVAL_TICKS;
        saveConfig();
        feedback(source, "Hidden block textures: " + (renderHiddenTextures ? "enabled" : "disabled"));
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
            renderHiddenTextures = Boolean.parseBoolean(properties.getProperty("renderHiddenTextures", "true"));
            radius = clamp(parseInt(properties.getProperty("radius"), 6), MIN_RADIUS, MAX_RADIUS);
            downRange = clamp(parseInt(properties.getProperty("downRange"), 3), MIN_VERTICAL_RANGE, MAX_VERTICAL_RANGE);
            upRange = clamp(parseInt(properties.getProperty("upRange"), 3), MIN_VERTICAL_RANGE, MAX_VERTICAL_RANGE);
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
        properties.setProperty("renderHiddenTextures", Boolean.toString(renderHiddenTextures));
        properties.setProperty("radius", Integer.toString(radius));
        properties.setProperty("downRange", Integer.toString(downRange));
        properties.setProperty("upRange", Integer.toString(upRange));
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
