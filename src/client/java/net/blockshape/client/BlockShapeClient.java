package net.blockshape.client;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
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
import net.minecraft.tags.BlockTags;
import net.minecraft.network.chat.Component;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
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
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.HashSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Client-only block collision-shape visualizer for Minecraft 26.3. */
public final class BlockShapeClient implements ClientModInitializer {
    private static final int MIN_RADIUS = 2;
    private static final int MAX_RADIUS = 16;
    private static final int MIN_VERTICAL_RANGE = 0;
    private static final int MAX_VERTICAL_RANGE = 16;
    // The balanced profile rebuilds twice per second; other profiles trade detail for speed.
    private static final int DEFAULT_REFRESH_INTERVAL_TICKS = 10;
    private static int refreshIntervalTicks = DEFAULT_REFRESH_INTERVAL_TICKS;
    private static final int[] EMPTY_QUAD_INDICES = new int[0];
    private static final Logger LOGGER = LoggerFactory.getLogger("blockshape");
    private static final int MAX_LOGGED_RENDER_ERRORS = 128;
    private static final Set<String> LOGGED_RENDER_ERRORS = new HashSet<>();
    private static final Set<String> DISABLED_TEXTURE_BLOCK_IDS = new HashSet<>();
    private static final Set<String> DISABLED_TEXTURE_NAMESPACES = new HashSet<>();
    private static final Map<Block, Integer> BLOCK_COLOR_CACHE = new IdentityHashMap<>();
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
    private static boolean renderOutlines = true;
    private static boolean renderHiddenTextures = true;
    private static boolean categoryLiquids = true;
    private static boolean categoryPortals = true;
    private static boolean categoryFire = true;
    private static boolean categoryPlants = true;
    private static boolean categorySpecialModels = true;
    private static int radius = 6;
    private static int downRange = 3;
    private static int upRange = 3;
    private static float lineWidth = 2.0f;
    private static boolean perBlockColors = true;
    private static int fixedColor = 0xFF35D7FF;
    private static int ticksSinceRefresh = refreshIntervalTicks;
    private static String qualityPreset = "balanced";
    private static BlockPos lastCacheOrigin = BlockPos.ZERO;
    private static ClientLevel cachedLevel;
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

        // Invalidate only entries belonging to the changed chunk. The regular
        // quality interval then rebuilds nearby meshes without clearing the whole view.
        ClientChunkEvents.CHUNK_LOAD.register((world, chunk) -> invalidateChunkCache(chunk));
        ClientChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> invalidateChunkCache(chunk));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level == null || client.player == null) {
                cachedLevel = null;
                invalidateCache();
                return;
            }
            if (!enabled) {
                cachedShapes = List.of();
                ticksSinceRefresh = refreshIntervalTicks;
                return;
            }
            if (cachedLevel != client.level) {
                cachedLevel = client.level;
                invalidateCache();
            }
            boolean ambientOcclusion = client.options.ambientOcclusion().get();
            boolean playerChangedBlock = !client.player.blockPosition().equals(lastCacheOrigin);
            if (playerChangedBlock
                    || ++ticksSinceRefresh >= refreshIntervalTicks
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
        lastCacheOrigin = origin.immutable();
        if (!renderOutlines && !renderHiddenTextures) {
            cachedShapes = List.of();
            return;
        }

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        CollisionContext collisionContext = CollisionContext.of(player);
        int radiusSquared = r * r;
        int verticalSpan = down + up + 1;
        int expectedEntries = Math.max(16, (int) (Math.PI * radiusSquared * verticalSpan));
        List<ShapeEntry> result = new ArrayList<>(expectedEntries);
        Map<Long, Boolean> chunkAvailability = new HashMap<>();

        // Check each chunk only once instead of asking Level for every block position.
        for (int y = -down; y <= up; y++) {
            for (int z = -r; z <= r; z++) {
                for (int x = -r; x <= r; x++) {
                    if (x * x + z * z > radiusSquared) continue;
                    cursor.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);

                    long chunkKey = ((long) (cursor.getX() >> 4) << 32)
                            | ((cursor.getZ() >> 4) & 0xFFFFFFFFL);
                    Boolean chunkLoaded = chunkAvailability.get(chunkKey);
                    if (chunkLoaded == null) {
                        chunkLoaded = level.hasChunkAt(cursor);
                        chunkAvailability.put(chunkKey, chunkLoaded);
                    }
                    if (!chunkLoaded) continue;

                    BlockState state = level.getBlockState(cursor);
                    if (state.isAir()) continue;

                    BlockPos pos = cursor.immutable();
                    boolean hiddenTextureBlock = renderHiddenTextures
                            && state.getRenderShape() == RenderShape.INVISIBLE
                            && shouldRestoreHiddenTextureCategory(state);

                    VoxelShape shape = net.minecraft.world.phys.shapes.Shapes.empty();
                    if (renderOutlines) {
                        // Snow's selection shape matches the visible layer height, while its
                        // collision shape is shorter (and the first layer has no collision).
                        if (state.getBlock() instanceof SnowLayerBlock) {
                            shape = state.getShape(level, cursor, collisionContext);
                        } else {
                            shape = state.getCollisionShape(level, cursor, collisionContext);
                            // Non-collidable decorations can still have a useful selection shape.
                            if (shape.isEmpty()) {
                                shape = state.getShape(level, cursor, collisionContext);
                            }
                        }
                    }

                    if (shape.isEmpty() && !hiddenTextureBlock) continue;

                    int color = renderOutlines
                            ? (perBlockColors ? colorForBlock(state) : fixedColor)
                            : fixedColor;
                    Mesh textureMesh = null;
                    boolean translucentTexture = false;
                    boolean specialTexture = false;
                    if (hiddenTextureBlock) {
                        CachedTexture texture;
                        try {
                            texture = buildHiddenTextureMesh(level, pos, state);
                        } catch (RuntimeException exception) {
                            logRenderFailure(state, exception);
                            // Try the vanilla display-state path for a model the world tessellator rejects.
                            texture = new CachedTexture(null, false, true);
                        }
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

    private static void invalidateCache() {
        cachedShapes = List.of();
        ticksSinceRefresh = refreshIntervalTicks;
    }

    private static void invalidateChunkCache(net.minecraft.world.level.chunk.LevelChunk chunk) {
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        int maxX = minX + 15;
        int maxZ = minZ + 15;

        if (!cachedShapes.isEmpty()) {
            cachedShapes = cachedShapes.stream()
                    .filter(entry -> {
                        int x = entry.pos().getX();
                        int z = entry.pos().getZ();
                        boolean insideChangedChunk = x >= minX && x <= maxX
                                && z >= minZ && z <= maxZ;
                        // Face culling for blocks directly across a chunk edge depends
                        // on the neighbor chunk too, so invalidate this one-block border.
                        boolean touchesChangedXEdge = (x == minX - 1 || x == maxX + 1)
                                && z >= minZ && z <= maxZ;
                        boolean touchesChangedZEdge = (z == minZ - 1 || z == maxZ + 1)
                                && x >= minX && x <= maxX;
                        return !insideChangedChunk && !touchesChangedXEdge && !touchesChangedZEdge;
                    })
                    .toList();
        }
    }

    private static void logRenderFailure(BlockState state, RuntimeException exception) {
        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        if (LOGGED_RENDER_ERRORS.size() < MAX_LOGGED_RENDER_ERRORS && LOGGED_RENDER_ERRORS.add(blockId)) {
            LOGGER.warn("Could not restore hidden texture for block {}. The renderer will keep running.", blockId, exception);
        }
    }

    private static boolean shouldRestoreHiddenTextureCategory(BlockState state) {
        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        if (DISABLED_TEXTURE_BLOCK_IDS.contains(blockId)) return false;
        int namespaceSeparator = blockId.indexOf(':');
        String namespace = namespaceSeparator > 0 ? blockId.substring(0, namespaceSeparator) : "minecraft";
        if (DISABLED_TEXTURE_NAMESPACES.contains(namespace)) return false;
        // Keep liquid blocks under their own switch; waterlogged solid blocks are not LiquidBlock instances.
        if (state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) return categoryLiquids;
        if (state.is(Blocks.END_PORTAL) || state.is(BlockTags.PORTALS) || state.is(Blocks.END_GATEWAY)) return categoryPortals;
        if (state.is(BlockTags.FIRE) || state.is(BlockTags.CAMPFIRES)) return categoryFire;
        if (isPlantBlock(state)) return categoryPlants;
        return true;
    }

    private static boolean isPlantBlock(BlockState state) {
        if (state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) return false;
        return state.getBlock() instanceof BushBlock
                || state.is(BlockTags.FLOWERS)
                || state.is(BlockTags.LEAVES)
                || state.is(BlockTags.CROPS)
                || state.is(BlockTags.SAPLINGS)
                || state.is(BlockTags.CAVE_VINES)
                || state.is(BlockTags.CORAL_PLANTS)
                || state.is(BlockTags.FLOWER_POTS)
                // Includes 26.3 vegetation such as red shrubs and shelf mushrooms,
                // and modded plants that opt into these standard tags.
                || state.is(BlockTags.REPLACEABLE_BY_TREES)
                || state.is(BlockTags.REPLACEABLE_BY_MUSHROOMS);
    }

    /** Called by the sprite mixin to decide whether a vanilla effect stays on frame one. */
    public static boolean shouldKeepSpriteStatic(String spriteName) {
        if (spriteName.equals("minecraft:block/water_still")
                || spriteName.equals("minecraft:block/water_flow")
                || spriteName.equals("minecraft:block/lava_still")
                || spriteName.equals("minecraft:block/lava_flow")) {
            return categoryLiquids;
        }
        if (spriteName.equals("minecraft:block/nether_portal")) return categoryPortals;
        if (spriteName.equals("minecraft:block/fire_0")
                || spriteName.equals("minecraft:block/fire_1")
                || spriteName.equals("minecraft:block/soul_fire_0")
                || spriteName.equals("minecraft:block/soul_fire_1")) {
            return categoryFire;
        }
        return false;
    }

    public static boolean isPortalCategoryEnabled() {
        return categoryPortals;
    }

    private static int colorForBlock(BlockState state) {
        return BLOCK_COLOR_CACHE.computeIfAbsent(state.getBlock(), block -> {
            String id = BuiltInRegistries.BLOCK.getKey(block).toString();
            int hash = id.hashCode();
            float hue = Math.floorMod(hash, 360) / 360.0f;
            int rgb = hsvToRgb(hue, 0.82f, 1.0f);
            return 0xFF000000 | rgb;
        });
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
            boolean drawHiddenTexture = renderHiddenTextures
                    && entry.state().getRenderShape() == RenderShape.INVISIBLE
                    && (entry.hiddenTextureMesh() != null || entry.specialHiddenTexture());
            boolean drawOutline = renderOutlines && !entry.shape().isEmpty();
            if (!drawHiddenTexture && !drawOutline) continue;

            poseStack.pushPose();
            poseStack.translate(
                    entry.pos().getX() - camera.x,
                    entry.pos().getY() - camera.y,
                    entry.pos().getZ() - camera.z
            );

            // BlockHider marks world blocks INVISIBLE but keeps their baked models
            // and the block texture atlas loaded. Submit those original models here.
            // Normal visible blocks stay on the vanilla path, avoiding z-fighting.
            if (drawHiddenTexture) {
                try {
                    if (entry.hiddenTextureMesh() != null) {
                        submitCachedHiddenBlockTexture(entry, poseStack, collector);
                    } else {
                        submitSpecialHiddenBlockTexture(entry, poseStack, collector);
                    }
                } catch (RuntimeException exception) {
                    // One broken third-party model must not break the whole world render.
                    logRenderFailure(entry.state(), exception);
                }
            }

            if (drawOutline) {
                collector.submitShapeOutline(
                        poseStack,
                        entry.shape(),
                        RenderTypes.lines(),
                        entry.color(),
                        lineWidth,
                        false
                );
            }
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
            return new CachedTexture(null, false, categorySpecialModels);
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
                // Some non-standard models do not emit world quads through this renderer.
                // Give Minecraft's regular block display path one chance before giving up.
                return new CachedTexture(null, false, true);
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
                EMPTY_QUAD_INDICES,
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
                                    + ", quality=" + qualityPreset
                                    + ", categories=" + (categoryLiquids ? "L" : "-")
                                    + (categoryPortals ? "P" : "-")
                                    + (categoryFire ? "F" : "-")
                                    + (categoryPlants ? "G" : "-")
                                    + (categorySpecialModels ? "M" : "-")
                                    + ", outlines=" + (renderOutlines ? "ON" : "OFF")
                                    + ", colors=" + (perBlockColors ? "per-block" : "fixed")
                                    + ", hidden textures=" + (renderHiddenTextures ? "ON" : "OFF"));
                            feedback(context.getSource(), "Категории L/P/F/G/M = жидкости/порталы/огонь/растения/спецмодели; ON означает обработку категории.");
                            feedback(context.getSource(), "Cached entries=" + cachedShapes.size() + ", unique render errors=" + LOGGED_RENDER_ERRORS.size());
                            feedback(context.getSource(), "Commands: /bshape on|off|toggle|inspect|refresh|reset|block <namespace:id> <on|off>|namespace <name> <on|off>|quality low|balanced|high|category <liquids|portals|fire|plants|special> <on|off>|outlines on|off|textures on|off|radius <2..16>|down <0..16>|up <0..16>|width <1..6>|color rainbow|color RRGGBB");
                            return 1;
                        })
                        .then(ClientCommands.literal("on").executes(context -> setEnabled(context.getSource(), true)))
                        .then(ClientCommands.literal("off").executes(context -> setEnabled(context.getSource(), false)))
                        .then(ClientCommands.literal("toggle").executes(context -> setEnabled(context.getSource(), !enabled)))
                        .then(ClientCommands.literal("inspect").executes(context -> inspectTargetBlock(context.getSource())))
                        .then(ClientCommands.literal("refresh").executes(context -> {
                            invalidateCache();
                            feedback(context.getSource(), "Nearby block and texture cache will be rebuilt now");
                            return 1;
                        }))
                        .then(ClientCommands.literal("reset").executes(context -> resetSettings(context.getSource())))
                        .then(ClientCommands.literal("quality")
                                .then(ClientCommands.literal("low").executes(context -> setQuality(context.getSource(), "low")))
                                .then(ClientCommands.literal("balanced").executes(context -> setQuality(context.getSource(), "balanced")))
                                .then(ClientCommands.literal("high").executes(context -> setQuality(context.getSource(), "high"))))
                        .then(ClientCommands.literal("category")
                                .then(textureCategoryCommand("liquids"))
                                .then(textureCategoryCommand("portals"))
                                .then(textureCategoryCommand("fire"))
                                .then(textureCategoryCommand("plants"))
                                .then(textureCategoryCommand("special")))
                        .then(ClientCommands.literal("block")
                                .then(ClientCommands.argument("id", StringArgumentType.word())
                                        .then(ClientCommands.literal("on").executes(context ->
                                                setBlockTexture(context.getSource(), StringArgumentType.getString(context, "id"), true)))
                                        .then(ClientCommands.literal("off").executes(context ->
                                                setBlockTexture(context.getSource(), StringArgumentType.getString(context, "id"), false)))))
                        .then(ClientCommands.literal("namespace")
                                .then(ClientCommands.argument("name", StringArgumentType.word())
                                        .then(ClientCommands.literal("on").executes(context ->
                                                setTextureNamespace(context.getSource(), StringArgumentType.getString(context, "name"), true)))
                                        .then(ClientCommands.literal("off").executes(context ->
                                                setTextureNamespace(context.getSource(), StringArgumentType.getString(context, "name"), false)))))
                        .then(ClientCommands.literal("outlines")
                                .then(ClientCommands.literal("on").executes(context -> setOutlines(context.getSource(), true)))
                                .then(ClientCommands.literal("off").executes(context -> setOutlines(context.getSource(), false))))
                        .then(ClientCommands.literal("textures")
                                .then(ClientCommands.literal("on").executes(context -> setHiddenTextures(context.getSource(), true)))
                                .then(ClientCommands.literal("off").executes(context -> setHiddenTextures(context.getSource(), false))))
                        .then(ClientCommands.literal("radius").then(ClientCommands.argument("blocks", IntegerArgumentType.integer(MIN_RADIUS, MAX_RADIUS))
                                .executes(context -> {
                                    radius = IntegerArgumentType.getInteger(context, "blocks");
                                    qualityPreset = "custom";
                                    ticksSinceRefresh = refreshIntervalTicks;
                                    saveConfig();
                                    feedback(context.getSource(), "Display radius: " + radius + " blocks");
                                    return 1;
                                })))
                        .then(ClientCommands.literal("down").then(ClientCommands.argument("blocks", IntegerArgumentType.integer(MIN_VERTICAL_RANGE, MAX_VERTICAL_RANGE))
                                .executes(context -> {
                                    downRange = IntegerArgumentType.getInteger(context, "blocks");
                                    qualityPreset = "custom";
                                    ticksSinceRefresh = refreshIntervalTicks;
                                    saveConfig();
                                    feedback(context.getSource(), "Visible range below player: " + downRange + " blocks");
                                    return 1;
                                })))
                        .then(ClientCommands.literal("up").then(ClientCommands.argument("blocks", IntegerArgumentType.integer(MIN_VERTICAL_RANGE, MAX_VERTICAL_RANGE))
                                .executes(context -> {
                                    upRange = IntegerArgumentType.getInteger(context, "blocks");
                                    qualityPreset = "custom";
                                    ticksSinceRefresh = refreshIntervalTicks;
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

    private static int setBlockTexture(FabricClientCommandSource source, String id, boolean enabled) {
        if (!id.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) {
            source.sendError(Component.literal("[BlockShape] Use a block ID such as minecraft:stone"));
            return 0;
        }
        if (enabled) {
            DISABLED_TEXTURE_BLOCK_IDS.remove(id);
        } else {
            DISABLED_TEXTURE_BLOCK_IDS.add(id);
        }
        invalidateCache();
        saveConfig();
        feedback(source, "Block texture restoration " + (enabled ? "enabled" : "disabled") + " for " + id);
        return 1;
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> textureCategoryCommand(String name) {
        return ClientCommands.literal(name)
                .then(ClientCommands.literal("on").executes(context -> setTextureCategory(context.getSource(), name, true)))
                .then(ClientCommands.literal("off").executes(context -> setTextureCategory(context.getSource(), name, false)));
    }

    private static int setTextureNamespace(FabricClientCommandSource source, String namespace, boolean enabled) {
        if (!namespace.matches("[a-z0-9_.-]+")) {
            source.sendError(Component.literal("[BlockShape] Namespace must contain only lowercase letters, digits, '.', '_' or '-'."));
            return 0;
        }
        if (enabled) {
            DISABLED_TEXTURE_NAMESPACES.remove(namespace);
        } else {
            DISABLED_TEXTURE_NAMESPACES.add(namespace);
        }
        invalidateCache();
        saveConfig();
        feedback(source, "Texture restoration " + (enabled ? "enabled" : "disabled")
                + " for namespace " + namespace);
        return 1;
    }

    private static int setTextureCategory(FabricClientCommandSource source, String name, boolean value) {
        switch (name) {
            case "liquids" -> categoryLiquids = value;
            case "portals" -> categoryPortals = value;
            case "fire" -> categoryFire = value;
            case "plants" -> categoryPlants = value;
            case "special" -> categorySpecialModels = value;
            default -> {
                source.sendError(Component.literal("[BlockShape] Unknown texture category: " + name));
                return 0;
            }
        }
        invalidateCache();
        saveConfig();
        String reloadHint = (name.equals("liquids") || name.equals("portals") || name.equals("fire"))
                ? " Resource reload (F3+T) applies sprite animation changes."
                : "";
        feedback(source, "Texture category " + name + ": " + (value ? "ON" : "OFF") + reloadHint);
        return 1;
    }

    private static int setQuality(FabricClientCommandSource source, String preset) {
        qualityPreset = preset;
        applyQualityPreset(preset);
        invalidateCache();
        saveConfig();
        feedback(source, "Quality preset: " + preset + " (radius=" + radius
                + ", vertical=" + downRange + "/" + upRange
                + ", refresh=" + refreshIntervalTicks + " ticks)");
        return 1;
    }

    private static void applyQualityPreset(String preset) {
        switch (preset) {
            case "low" -> {
                radius = 4;
                downRange = 2;
                upRange = 2;
                refreshIntervalTicks = 20;
            }
            case "high" -> {
                radius = 8;
                downRange = 4;
                upRange = 4;
                refreshIntervalTicks = 5;
            }
            case "balanced" -> {
                radius = 6;
                downRange = 3;
                upRange = 3;
                refreshIntervalTicks = DEFAULT_REFRESH_INTERVAL_TICKS;
            }
            default -> {
                qualityPreset = "custom";
                refreshIntervalTicks = DEFAULT_REFRESH_INTERVAL_TICKS;
            }
        }
    }

    private static int inspectTargetBlock(FabricClientCommandSource source) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            source.sendError(Component.literal("[BlockShape] You must be in a world to inspect a block."));
            return 0;
        }
        if (!(minecraft.hitResult instanceof BlockHitResult hit)) {
            source.sendError(Component.literal("[BlockShape] Aim at a block first."));
            return 0;
        }

        BlockPos pos = hit.getBlockPos();
        BlockState state = minecraft.level.getBlockState(pos);
        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        String modelType = "unknown";
        boolean specialModel = false;
        try {
            BlockModel model = minecraft.getModelManager().getBlockModelSet().get(state);
            modelType = model.getClass().getSimpleName();
            specialModel = !(model instanceof BlockStateModelWrapper);
        } catch (RuntimeException exception) {
            logRenderFailure(state, exception);
            modelType = "lookup failed";
        }

        String category = textureCategoryName(state, specialModel);
        boolean restorationEnabled = renderHiddenTextures
                && shouldRestoreHiddenTextureCategory(state)
                && (!specialModel || categorySpecialModels);
        ShapeEntry cached = cachedShapes.stream()
                .filter(entry -> entry.pos().equals(pos))
                .findFirst()
                .orElse(null);
        String cacheState = cached == null ? "not cached"
                : cached.hiddenTextureMesh() != null ? "mesh cached"
                : cached.specialHiddenTexture() ? "special fallback"
                : "shape only";

        String coordinates = pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
        source.sendFeedback(Component.literal("[BlockShape] " + blockId + " at " + coordinates));
        source.sendFeedback(Component.literal("[BlockShape] Category: " + category
                + " | RenderShape: " + state.getRenderShape() + " | Model: " + modelType));
        source.sendFeedback(Component.literal("[BlockShape] Texture restore: "
                + (restorationEnabled ? "enabled" : "disabled")
                + " | Cache: " + cacheState));
        return 1;
    }

    private static String textureCategoryName(BlockState state, boolean specialModel) {
        if (state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) return "liquids";
        if (state.is(Blocks.END_PORTAL) || state.is(BlockTags.PORTALS) || state.is(Blocks.END_GATEWAY)) return "portals";
        if (state.is(BlockTags.FIRE) || state.is(BlockTags.CAMPFIRES)) return "fire";
        if (isPlantBlock(state)) return "plants";
        return specialModel ? "special models" : "regular block";
    }

    private static int resetSettings(FabricClientCommandSource source) {
        enabled = true;
        renderOutlines = true;
        renderHiddenTextures = true;
        categoryLiquids = true;
        categoryPortals = true;
        categoryFire = true;
        categoryPlants = true;
        categorySpecialModels = true;
        DISABLED_TEXTURE_BLOCK_IDS.clear();
        DISABLED_TEXTURE_NAMESPACES.clear();
        radius = 6;
        downRange = 3;
        upRange = 3;
        lineWidth = 2.0f;
        perBlockColors = true;
        fixedColor = 0xFF35D7FF;
        qualityPreset = "balanced";
        applyQualityPreset(qualityPreset);
        invalidateCache();
        saveConfig();
        feedback(source, "All BlockShape settings restored to defaults. Reload resources (F3+T, resource-pack toggle, or game restart) to apply liquid/portal/fire animation changes.");
        return 1;
    }

    private static int setEnabled(FabricClientCommandSource source, boolean value) {
        enabled = value;
        ticksSinceRefresh = refreshIntervalTicks;
        saveConfig();
        feedback(source, "BlockShape " + (enabled ? "enabled" : "disabled"));
        return 1;
    }

    private static int setOutlines(FabricClientCommandSource source, boolean value) {
        renderOutlines = value;
        ticksSinceRefresh = refreshIntervalTicks;
        saveConfig();
        feedback(source, "Block outlines: " + (renderOutlines ? "enabled" : "disabled"));
        return 1;
    }

    private static int setHiddenTextures(FabricClientCommandSource source, boolean value) {
        renderHiddenTextures = value;
        ticksSinceRefresh = refreshIntervalTicks;
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
            qualityPreset = properties.getProperty("quality", "balanced");
            if (!qualityPreset.equals("low") && !qualityPreset.equals("balanced")
                    && !qualityPreset.equals("high") && !qualityPreset.equals("custom")) {
                qualityPreset = "balanced";
            }
            applyQualityPreset(qualityPreset);
            refreshIntervalTicks = clamp(parseInt(properties.getProperty("refreshIntervalTicks"), refreshIntervalTicks), 5, 40);
            enabled = Boolean.parseBoolean(properties.getProperty("enabled", "true"));
            renderOutlines = Boolean.parseBoolean(properties.getProperty("renderOutlines", "true"));
            renderHiddenTextures = Boolean.parseBoolean(properties.getProperty("renderHiddenTextures", "true"));
            categoryLiquids = Boolean.parseBoolean(properties.getProperty("categoryLiquids", "true"));
            categoryPortals = Boolean.parseBoolean(properties.getProperty("categoryPortals", "true"));
            categoryFire = Boolean.parseBoolean(properties.getProperty("categoryFire", "true"));
            categoryPlants = Boolean.parseBoolean(properties.getProperty("categoryPlants", "true"));
            categorySpecialModels = Boolean.parseBoolean(properties.getProperty("categorySpecialModels", "true"));
            DISABLED_TEXTURE_BLOCK_IDS.clear();
            String disabledIds = properties.getProperty("disabledTextureBlockIds", "");
            for (String id : disabledIds.split(",")) {
                if (id.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) DISABLED_TEXTURE_BLOCK_IDS.add(id);
            }
            DISABLED_TEXTURE_NAMESPACES.clear();
            String disabledNamespaces = properties.getProperty("disabledTextureNamespaces", "");
            for (String namespace : disabledNamespaces.split(",")) {
                if (namespace.matches("[a-z0-9_.-]+")) DISABLED_TEXTURE_NAMESPACES.add(namespace);
            }
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
        properties.setProperty("quality", qualityPreset);
        properties.setProperty("refreshIntervalTicks", Integer.toString(refreshIntervalTicks));
        properties.setProperty("enabled", Boolean.toString(enabled));
        properties.setProperty("renderOutlines", Boolean.toString(renderOutlines));
        properties.setProperty("renderHiddenTextures", Boolean.toString(renderHiddenTextures));
        properties.setProperty("categoryLiquids", Boolean.toString(categoryLiquids));
        properties.setProperty("categoryPortals", Boolean.toString(categoryPortals));
        properties.setProperty("categoryFire", Boolean.toString(categoryFire));
        properties.setProperty("categoryPlants", Boolean.toString(categoryPlants));
        properties.setProperty("categorySpecialModels", Boolean.toString(categorySpecialModels));
        properties.setProperty("disabledTextureBlockIds", String.join(",", DISABLED_TEXTURE_BLOCK_IDS));
        properties.setProperty("disabledTextureNamespaces", String.join(",", DISABLED_TEXTURE_NAMESPACES));
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
