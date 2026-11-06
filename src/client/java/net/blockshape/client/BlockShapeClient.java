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
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
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
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
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
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.AABB;
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
import java.lang.reflect.Method;
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
    private static final int MAX_RADIUS = 32;
    private static final int MIN_TEXTURE_RADIUS = 1;
    private static final int MAX_TEXTURE_RADIUS = 32;
    private static final int DEFAULT_TEXTURE_RADIUS = 16;
    private static final int MIN_VERTICAL_RANGE = 0;
    private static final int MAX_VERTICAL_RANGE = 16;
    private static final int DEFAULT_REFRESH_INTERVAL_TICKS = 10;
    private static final int MOVEMENT_REFRESH_DISTANCE_SQUARED = 4;
    private static final int MIN_EVENT_REFRESH_INTERVAL_TICKS = 3;
    private static int refreshIntervalTicks = DEFAULT_REFRESH_INTERVAL_TICKS;
    private static int scanBudgetPerTick = 4096;
    private static int textureBuildBudgetPerTick = 24;
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
    // The outer radius can show outlines; texture restoration has its own smaller LOD radius.
    private static int textureRadius = DEFAULT_TEXTURE_RADIUS;
    private static int downRange = 3;
    private static int upRange = 3;
    private static float lineWidth = 2.0f;
    private static boolean perBlockColors = true;
    private static int fixedColor = 0xFF35D7FF;
    private static int ticksSinceRefresh = refreshIntervalTicks;
    private static String qualityPreset = "balanced";
    private static BlockPos lastCacheOrigin = BlockPos.ZERO;
    private static ClientLevel cachedLevel;
    private static boolean blockHiderApiResolved;
    private static Method blockHiderShouldShowBlockEntity;
    // Two alternating buffers and reusable indexes reduce temporary allocation/GC
    // during repeated cache rebuilds, especially on mobile devices.
    private static ArrayList<ShapeEntry> cachedShapes = new ArrayList<>();
    private static ArrayList<ShapeEntry> cacheBuildEntries = new ArrayList<>();
    private static final Map<BlockPos, ShapeEntry> previousByPosition = new HashMap<>();
    private static final Map<Long, Boolean> chunkAvailability = new HashMap<>();
    private static CacheBuildJob cacheBuildJob;
    private static boolean cacheDirty = true;
    private static int lastReusedEntries;
    private static int lastRebuiltEntries;
    private static int lastScannedPositions;
    private static double lastRebuildMillis;

    private record NeighborSnapshot(BlockState state, int packedLight) {}

    private record TextureCacheKey(
            BlockState state,
            NeighborSnapshot north,
            NeighborSnapshot south,
            NeighborSnapshot east,
            NeighborSnapshot west,
            NeighborSnapshot above,
            NeighborSnapshot below,
            int packedLight,
            Holder<Biome> biome,
            boolean ambientOcclusion
    ) {}

    private record ShapeEntry(
            BlockPos pos,
            AABB bounds,
            BlockState state,
            VoxelShape shape,
            int color,
            TextureCacheKey textureCacheKey,
            Mesh hiddenTextureMesh,
            boolean translucentHiddenTexture,
            boolean specialHiddenTexture
    ) {}

    private record CachedTexture(Mesh mesh, boolean translucent, boolean specialModel) {}

    /** A cache build is spread across several client ticks to avoid one-frame spikes. */
    private static final class CacheBuildJob {
        private final ClientLevel level;
        private final Player player;
        private final BlockPos origin;
        private final int radius;
        private final int textureRadius;
        private final int radiusSquared;
        private final int textureRadiusSquared;
        private final int down;
        private final int up;
        private final boolean ambientOcclusion;
        private final CollisionContext collisionContext;
        private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        private int x;
        private int y;
        private int z;
        private int reusedEntries;
        private int rebuiltEntries;
        private int scannedPositions;
        private long cpuNanos;
        private boolean complete;

        private CacheBuildJob(
                ClientLevel level,
                Player player,
                BlockPos origin,
                int radius,
                int textureRadius,
                int down,
                int up,
                boolean ambientOcclusion
        ) {
            this.level = level;
            this.player = player;
            this.origin = origin;
            this.radius = radius;
            this.textureRadius = Math.min(radius, textureRadius);
            this.radiusSquared = radius * radius;
            this.textureRadiusSquared = this.textureRadius * this.textureRadius;
            this.down = down;
            this.up = up;
            this.ambientOcclusion = ambientOcclusion;
            this.collisionContext = CollisionContext.of(player);
            this.x = -radius;
            this.y = -down;
            this.z = -radius;
        }
    }

    @Override
    public void onInitializeClient() {
        loadConfig();
        registerResourceReloadListener();

        ClientChunkEvents.CHUNK_LOAD.register((world, chunk) -> invalidateChunkCache(chunk));
        ClientChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> invalidateChunkCache(chunk));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level == null || client.player == null) {
                cachedLevel = null;
                invalidateCache();
                return;
            }
            if (!enabled) {
                cancelCacheBuild();
                cachedShapes.clear();
                cacheBuildEntries.clear();
                lastReusedEntries = 0;
                lastRebuiltEntries = 0;
                lastScannedPositions = 0;
                ticksSinceRefresh = refreshIntervalTicks;
                return;
            }
            if (cachedLevel != client.level) {
                cachedLevel = client.level;
                invalidateCache();
            }

            boolean ambientOcclusion = client.options.ambientOcclusion().get();
            if (cacheBuildJob != null) {
                if (cacheBuildJob.level != client.level
                        || cacheBuildJob.ambientOcclusion != ambientOcclusion) {
                    cancelCacheBuild();
                    cacheDirty = true;
                } else {
                    continueCacheBuild();
                    if (cacheBuildJob != null) return;
                }
            }

            BlockPos currentOrigin = client.player.blockPosition();
            long moveX = (long) currentOrigin.getX() - lastCacheOrigin.getX();
            long moveY = (long) currentOrigin.getY() - lastCacheOrigin.getY();
            long moveZ = (long) currentOrigin.getZ() - lastCacheOrigin.getZ();
            boolean playerMovedEnough = moveX * moveX + moveY * moveY + moveZ * moveZ
                    >= MOVEMENT_REFRESH_DISTANCE_SQUARED;

            ticksSinceRefresh++;
            boolean eventRefreshDue = cacheDirty
                    && ticksSinceRefresh >= MIN_EVENT_REFRESH_INTERVAL_TICKS;
            if (playerMovedEnough
                    || ticksSinceRefresh >= refreshIntervalTicks
                    || eventRefreshDue
                    || cachedAmbientOcclusion != ambientOcclusion) {
                cacheDirty = false;
                ticksSinceRefresh = 0;
                beginCacheBuild(client.level, client.player);
                continueCacheBuild();
            }
        });

        LevelRenderEvents.COLLECT_SUBMITS.register(this::submitShapes);
        registerCommands();
    }

    private static void beginCacheBuild(ClientLevel level, Player player) {
        cacheBuildEntries.clear();
        previousByPosition.clear();
        for (ShapeEntry previous : cachedShapes) {
            previousByPosition.put(previous.pos(), previous);
        }
        chunkAvailability.clear();

        boolean ambientOcclusion = Minecraft.getInstance().options.ambientOcclusion().get();
        cacheBuildJob = new CacheBuildJob(
                level,
                player,
                player.blockPosition().immutable(),
                radius,
                textureRadius,
                downRange,
                upRange,
                ambientOcclusion
        );
        int verticalSpan = downRange + upRange + 1;
        int expectedEntries = Math.max(16, (int) (Math.PI * radius * radius * verticalSpan));
        cacheBuildEntries.ensureCapacity(Math.min(expectedEntries, 120_000));
    }

    private static void continueCacheBuild() {
        CacheBuildJob job = cacheBuildJob;
        if (job == null) return;

        long passStarted = System.nanoTime();
        int positionsRemaining = scanBudgetPerTick;
        int textureBuildsRemaining = textureBuildBudgetPerTick;

        while (positionsRemaining > 0 && !job.complete) {
            int x = job.x;
            int y = job.y;
            int z = job.z;

            if (x * x + z * z <= job.radiusSquared) {
                job.cursor.set(
                        job.origin.getX() + x,
                        job.origin.getY() + y,
                        job.origin.getZ() + z
                );

                if (isChunkLoaded(job.level, job.cursor, chunkAvailability)) {
                    BlockState state = job.level.getBlockState(job.cursor);
                    if (!state.isAir()) {
                        BlockPos pos = job.cursor.immutable();
                        boolean hiddenTextureBlock = renderHiddenTextures
                                && x * x + z * z <= job.textureRadiusSquared
                                && state.getRenderShape() == RenderShape.INVISIBLE
                                && shouldRestoreHiddenTextureCategory(state)
                                && !isBlockEntityRenderedByBlockHider(state);

                        VoxelShape shape = net.minecraft.world.phys.shapes.Shapes.empty();
                        if (renderOutlines) {
                            if (state.getBlock() instanceof SnowLayerBlock) {
                                shape = state.getShape(job.level, job.cursor, job.collisionContext);
                            } else {
                                shape = state.getCollisionShape(job.level, job.cursor, job.collisionContext);
                                if (shape.isEmpty()) {
                                    shape = state.getShape(job.level, job.cursor, job.collisionContext);
                                }
                            }
                        }

                        if (!shape.isEmpty() || hiddenTextureBlock) {
                            int color = renderOutlines
                                    ? (perBlockColors ? colorForBlock(state) : fixedColor)
                                    : fixedColor;
                            TextureCacheKey textureCacheKey = hiddenTextureBlock
                                    ? createTextureCacheKey(
                                            job.level, pos, state, chunkAvailability, job.ambientOcclusion
                                    )
                                    : null;
                            Mesh textureMesh = null;
                            boolean translucentTexture = false;
                            boolean specialTexture = false;

                            if (hiddenTextureBlock) {
                                ShapeEntry previous = previousByPosition.get(pos);
                                if (previous != null && textureCacheKey.equals(previous.textureCacheKey())) {
                                    textureMesh = previous.hiddenTextureMesh();
                                    translucentTexture = previous.translucentHiddenTexture();
                                    specialTexture = previous.specialHiddenTexture();
                                    job.reusedEntries++;
                                } else {
                                    // Pause before an expensive model build when this tick's budget is used.
                                    if (textureBuildsRemaining <= 0) break;
                                    textureBuildsRemaining--;
                                    job.rebuiltEntries++;
                                    CachedTexture texture;
                                    try {
                                        texture = buildHiddenTextureMesh(job.level, pos, state);
                                    } catch (RuntimeException exception) {
                                        logRenderFailure(state, exception);
                                        texture = new CachedTexture(null, false, true);
                                    }
                                    textureMesh = texture.mesh();
                                    translucentTexture = texture.translucent();
                                    specialTexture = texture.specialModel();
                                }
                            }

                            AABB bounds = new AABB(
                                    pos.getX() - 0.25,
                                    pos.getY() - 0.25,
                                    pos.getZ() - 0.25,
                                    pos.getX() + 1.25,
                                    pos.getY() + 1.25,
                                    pos.getZ() + 1.25
                            );
                            cacheBuildEntries.add(new ShapeEntry(
                                    pos, bounds, state, shape, color, textureCacheKey,
                                    textureMesh, translucentTexture, specialTexture
                            ));
                        }
                    }
                }
            }

            job.scannedPositions++;
            positionsRemaining--;
            advanceCacheBuildPosition(job);
        }

        job.cpuNanos += System.nanoTime() - passStarted;
        if (job.complete) {
            List<ShapeEntry> previousBuffer = cachedShapes;
            cachedShapes = cacheBuildEntries;
            cacheBuildEntries = previousBuffer;
            lastCacheOrigin = job.origin;
            cachedAmbientOcclusion = job.ambientOcclusion;
            lastReusedEntries = job.reusedEntries;
            lastRebuiltEntries = job.rebuiltEntries;
            lastScannedPositions = job.scannedPositions;
            lastRebuildMillis = job.cpuNanos / 1_000_000.0;
            cacheBuildJob = null;
            previousByPosition.clear();
            chunkAvailability.clear();
        }
    }

    private static void advanceCacheBuildPosition(CacheBuildJob job) {
        job.x++;
        if (job.x > job.radius) {
            job.x = -job.radius;
            job.z++;
        }
        if (job.z > job.radius) {
            job.z = -job.radius;
            job.y++;
        }
        if (job.y > job.up) {
            job.complete = true;
        }
    }

    private static void cancelCacheBuild() {
        cacheBuildJob = null;
        cacheBuildEntries.clear();
        previousByPosition.clear();
        chunkAvailability.clear();
    }

    private static void registerResourceReloadListener() {
        ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(
                Identifier.fromNamespaceAndPath("blockshape", "texture_cache_invalidator"),
                new ResourceManagerReloadListener() {
                    @Override
                    public void onResourceManagerReload(ResourceManager resourceManager) {
                        // Cached meshes reference baked models and atlas sprites. They must
                        // never survive a resource-pack reload or F3+T.
                        invalidateCache();
                        hiddenTextureMesh = null;
                        hiddenTextureRenderer = null;
                        hiddenTextureRendererInitialized = false;
                        SPECIAL_MODEL_RENDER_STATE.clear();
                        LOGGED_RENDER_ERRORS.clear();
                        LOGGER.debug("Resource reload invalidated BlockShape texture meshes.");
                    }
                }
        );
    }

    private static boolean isChunkLoaded(
            ClientLevel level,
            BlockPos pos,
            Map<Long, Boolean> chunkAvailability
    ) {
        long chunkKey = ((long) (pos.getX() >> 4) << 32)
                | ((pos.getZ() >> 4) & 0xFFFFFFFFL);
        Boolean loaded = chunkAvailability.get(chunkKey);
        if (loaded == null) {
            loaded = level.hasChunkAt(pos);
            chunkAvailability.put(chunkKey, loaded);
        }
        return loaded;
    }

    private static NeighborSnapshot snapshotNeighbor(
            ClientLevel level,
            BlockPos pos,
            Map<Long, Boolean> chunkAvailability
    ) {
        if (!isChunkLoaded(level, pos, chunkAvailability)) {
            return new NeighborSnapshot(Blocks.AIR.defaultBlockState(), 0);
        }
        return new NeighborSnapshot(
                level.getBlockState(pos),
                LightCoordsUtil.getLightCoords(level, pos)
        );
    }

    private static TextureCacheKey createTextureCacheKey(
            ClientLevel level,
            BlockPos pos,
            BlockState state,
            Map<Long, Boolean> chunkAvailability,
            boolean ambientOcclusion
    ) {
        return new TextureCacheKey(
                state,
                snapshotNeighbor(level, pos.north(), chunkAvailability),
                snapshotNeighbor(level, pos.south(), chunkAvailability),
                snapshotNeighbor(level, pos.east(), chunkAvailability),
                snapshotNeighbor(level, pos.west(), chunkAvailability),
                snapshotNeighbor(level, pos.above(), chunkAvailability),
                snapshotNeighbor(level, pos.below(), chunkAvailability),
                LightCoordsUtil.getLightCoords(level, pos),
                level.getBiome(pos),
                ambientOcclusion
        );
    }

    private static void invalidateCache() {
        cancelCacheBuild();
        cachedShapes.clear();
        lastReusedEntries = 0;
        lastRebuiltEntries = 0;
        lastScannedPositions = 0;
        lastRebuildMillis = 0.0;
        cacheDirty = true;
        ticksSinceRefresh = refreshIntervalTicks;
    }

    /** Queue block changes for one coalesced cache refresh instead of rebuilding per event. */
    public static void invalidateBlockAndNeighbors(BlockPos changedPos) {
        if (cachedLevel == null) return;
        long dx = (long) changedPos.getX() - lastCacheOrigin.getX();
        long dy = (long) changedPos.getY() - lastCacheOrigin.getY();
        long dz = (long) changedPos.getZ() - lastCacheOrigin.getZ();
        if (dx * dx + dz * dz > (radius + 1L) * (radius + 1L)
                || dy < -downRange - 1 || dy > upRange + 1) {
            return;
        }
        cacheDirty = true;
    }

    private static void invalidateChunkCache(net.minecraft.world.level.chunk.LevelChunk chunk) {
        if (cachedLevel == null) return;
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        int maxX = minX + 15;
        int maxZ = minZ + 15;
        int nearestX = Math.max(minX, Math.min(maxX, lastCacheOrigin.getX()));
        int nearestZ = Math.max(minZ, Math.min(maxZ, lastCacheOrigin.getZ()));
        long dx = (long) nearestX - lastCacheOrigin.getX();
        long dz = (long) nearestZ - lastCacheOrigin.getZ();
        long activeRadius = (long) radius + 1L;
        if (dx * dx + dz * dz > activeRadius * activeRadius) return;
        cacheDirty = true;
    }

    private static void logRenderFailure(BlockState state, RuntimeException exception) {
        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        if (LOGGED_RENDER_ERRORS.size() < MAX_LOGGED_RENDER_ERRORS && LOGGED_RENDER_ERRORS.add(blockId)) {
            LOGGER.warn("Could not restore hidden texture for block {}. The renderer will keep running.", blockId, exception);
        }
    }

    /**
     * Optional integration with BlockHider without a compile-time dependency.
     * If BlockHider's public category filter says its native block-entity renderer
     * is active, do not submit a second baked-model fallback for the same object.
     * If BlockHider suppresses that renderer, BlockShape may still try its local
     * fallback when Special Models is enabled.
     */
    private static boolean isBlockEntityRenderedByBlockHider(BlockState state) {
        if (!state.hasBlockEntity() || !FabricLoader.getInstance().isModLoaded("blockhider")) {
            return false;
        }

        if (!blockHiderApiResolved) {
            blockHiderApiResolved = true;
            try {
                Class<?> configClass = Class.forName(
                        "net.blockhider.client.BlockHiderConfig",
                        false,
                        BlockShapeClient.class.getClassLoader()
                );
                blockHiderShouldShowBlockEntity = configClass.getMethod(
                        "shouldShowBlockEntity",
                        BlockState.class
                );
            } catch (ReflectiveOperationException | LinkageError exception) {
                LOGGER.debug("BlockHider optional category API is unavailable; keeping BlockShape fallback behavior.", exception);
            }
        }

        if (blockHiderShouldShowBlockEntity == null) return false;
        try {
            return Boolean.TRUE.equals(blockHiderShouldShowBlockEntity.invoke(null, state));
        } catch (ReflectiveOperationException | RuntimeException exception) {
            LOGGER.debug("Could not query BlockHider's block-entity visibility; keeping BlockShape fallback behavior.", exception);
            return false;
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
        // Some block entities use an ordinary-looking baked block model. They
        // still belong to Special Models and must respect that category switch.
        if (state.hasBlockEntity()) return categorySpecialModels;
        return true;
    }

    private static boolean shouldRestoreSpecialModelCategory(BlockState state) {
        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        if (DISABLED_TEXTURE_BLOCK_IDS.contains(blockId)) return false;
        int namespaceSeparator = blockId.indexOf(':');
        String namespace = namespaceSeparator > 0 ? blockId.substring(0, namespaceSeparator) : "minecraft";
        if (DISABLED_TEXTURE_NAMESPACES.contains(namespace)) return false;
        if (state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) return categoryLiquids;
        if (state.is(Blocks.END_PORTAL) || state.is(BlockTags.PORTALS) || state.is(Blocks.END_GATEWAY)) return categoryPortals;
        if (state.is(BlockTags.FIRE) || state.is(BlockTags.CAMPFIRES)) return categoryFire;
        if (isPlantBlock(state)) return categoryPlants;
        return categorySpecialModels;
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

        var cameraState = context.levelState().cameraRenderState;
        Vec3 camera = cameraState.pos;
        var frustum = cameraState.cullFrustum;
        PoseStack poseStack = context.poseStack();
        SubmitNodeCollector collector = context.submitNodeCollector();

        for (ShapeEntry entry : cachedShapes) {
            // Cull blocks outside the camera view before creating submit nodes.
            if (frustum != null && !frustum.isVisible(entry.bounds())) continue;

            boolean drawHiddenTexture = renderHiddenTextures
                    && entry.state().getRenderShape() == RenderShape.INVISIBLE
                    && (entry.hiddenTextureMesh() != null || entry.specialHiddenTexture());
            boolean drawOutline = renderOutlines && !entry.shape().isEmpty();
            if (!drawHiddenTexture && !drawOutline) continue;

            poseStack.pushPose();
            try {
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
            } finally {
                // Preserve the caller's pose even if a renderer or outline submission fails.
                poseStack.popPose();
            }
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
            // Dedicated categories (portals, fire, plants, liquids) control their own
            // special renderers; only otherwise-unclassified models use Special Models.
            return new CachedTexture(null, false, shouldRestoreSpecialModelCategory(state));
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
                                    + ", radius=" + radius + ", textureRadius=" + textureRadius
                                    + ", down=" + downRange + ", up=" + upRange + ", width=" + lineWidth
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
                            feedback(context.getSource(), "Cached entries=" + cachedShapes.size()
                                    + ", reused=" + lastReusedEntries + ", rebuilt=" + lastRebuiltEntries
                                    + ", scanned=" + lastScannedPositions
                                    + ", cache CPU time=" + Math.round(lastRebuildMillis * 100.0) / 100.0 + " ms"
                                    + ", unique render errors=" + LOGGED_RENDER_ERRORS.size());
                            feedback(context.getSource(), "Commands: /bshape on|off|toggle|inspect|refresh|reload|reset|block <namespace:id> <on|off>|namespace <name> <on|off>|quality performance|low|balanced|high|category <liquids|portals|fire|plants|special> <on|off>|outlines on|off|textures on|off|radius <2..32>|texture-radius <1..32|auto>|down <0..16>|up <0..16>|width <1..6>|color rainbow|color RRGGBB");
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
                        .then(ClientCommands.literal("reload").executes(context -> {
                            feedback(context.getSource(), "Starting Minecraft resource-pack reload. The game may pause briefly.");
                            Minecraft.getInstance().reloadResourcePacks().whenComplete((ignored, error) -> {
                                if (error != null) {
                                    LOGGER.error("Resource-pack reload failed while applying BlockShape category settings.", error);
                                }
                            });
                            return 1;
                        }))
                        .then(ClientCommands.literal("reset").executes(context -> resetSettings(context.getSource())))
                        .then(ClientCommands.literal("quality")
                                .then(ClientCommands.literal("performance").executes(context -> setQuality(context.getSource(), "performance")))
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
                                    if (textureRadius > radius) textureRadius = radius;
                                    qualityPreset = "custom";
                                    invalidateCache();
                                    saveConfig();
                                    feedback(context.getSource(), "Outline/display radius: " + radius + " blocks");
                                    return 1;
                                })))
                        .then(ClientCommands.literal("texture-radius")
                                .then(ClientCommands.literal("auto").executes(context -> {
                                    textureRadius = Math.min(radius, DEFAULT_TEXTURE_RADIUS);
                                    invalidateCache();
                                    saveConfig();
                                    feedback(context.getSource(), "Texture restoration radius: " + textureRadius + " blocks (automatic LOD)");
                                    return 1;
                                }))
                                .then(ClientCommands.argument("blocks", IntegerArgumentType.integer(MIN_TEXTURE_RADIUS, MAX_TEXTURE_RADIUS))
                                        .executes(context -> {
                                            textureRadius = IntegerArgumentType.getInteger(context, "blocks");
                                            invalidateCache();
                                            saveConfig();
                                            feedback(context.getSource(), "Texture restoration radius: " + textureRadius + " blocks");
                                            return 1;
                                        })))
                        .then(ClientCommands.literal("down").then(ClientCommands.argument("blocks", IntegerArgumentType.integer(MIN_VERTICAL_RANGE, MAX_VERTICAL_RANGE))
                                .executes(context -> {
                                    downRange = IntegerArgumentType.getInteger(context, "blocks");
                                    qualityPreset = "custom";
                                    invalidateCache();
                                    saveConfig();
                                    feedback(context.getSource(), "Visible range below player: " + downRange + " blocks");
                                    return 1;
                                })))
                        .then(ClientCommands.literal("up").then(ClientCommands.argument("blocks", IntegerArgumentType.integer(MIN_VERTICAL_RANGE, MAX_VERTICAL_RANGE))
                                .executes(context -> {
                                    upRange = IntegerArgumentType.getInteger(context, "blocks");
                                    qualityPreset = "custom";
                                    invalidateCache();
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
            case "performance" -> {
                // Aggressive mobile profile: small scan volume and infrequent rebuilds.
                radius = 3;
                textureRadius = 3;
                downRange = 1;
                upRange = 1;
                refreshIntervalTicks = 30;
                scanBudgetPerTick = 1024;
                textureBuildBudgetPerTick = 8;
            }
            case "low" -> {
                radius = 4;
                textureRadius = 4;
                downRange = 2;
                upRange = 2;
                refreshIntervalTicks = 20;
                scanBudgetPerTick = 2048;
                textureBuildBudgetPerTick = 16;
            }
            case "high" -> {
                radius = 8;
                textureRadius = 8;
                downRange = 4;
                upRange = 4;
                refreshIntervalTicks = 5;
                scanBudgetPerTick = 8192;
                textureBuildBudgetPerTick = 48;
            }
            case "balanced" -> {
                radius = 6;
                textureRadius = 6;
                downRange = 3;
                upRange = 3;
                refreshIntervalTicks = DEFAULT_REFRESH_INTERVAL_TICKS;
                scanBudgetPerTick = 4096;
                textureBuildBudgetPerTick = 24;
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
        String restorationStatus = getTextureRestorationStatus(state, specialModel);
        boolean restorationEnabled = restorationStatus.startsWith("ready");
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
        source.sendFeedback(Component.literal("[BlockShape] State: " + state));
        source.sendFeedback(Component.literal("[BlockShape] Category: " + category
                + " | RenderShape: " + state.getRenderShape()
                + " | BlockEntity: " + state.hasBlockEntity()
                + " | Model: " + modelType));
        source.sendFeedback(Component.literal("[BlockShape] Texture restore: "
                + (restorationEnabled ? "enabled" : "disabled")
                + " (" + restorationStatus + ") | Cache: " + cacheState));
        return 1;
    }

    private static String getTextureRestorationStatus(BlockState state, boolean specialModel) {
        if (!enabled) return "BlockShape disabled";
        if (!renderHiddenTextures) return "texture drawing disabled";
        if (state.getRenderShape() != RenderShape.INVISIBLE) return "vanilla render path";
        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        if (DISABLED_TEXTURE_BLOCK_IDS.contains(blockId)) return "excluded by block ID";
        int separator = blockId.indexOf(':');
        String namespace = separator > 0 ? blockId.substring(0, separator) : "minecraft";
        if (DISABLED_TEXTURE_NAMESPACES.contains(namespace)) return "excluded by namespace";
        if (state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock && !categoryLiquids) {
            return "Liquids category OFF";
        }
        if ((state.is(Blocks.END_PORTAL) || state.is(BlockTags.PORTALS) || state.is(Blocks.END_GATEWAY))
                && !categoryPortals) return "Portals category OFF";
        if ((state.is(BlockTags.FIRE) || state.is(BlockTags.CAMPFIRES)) && !categoryFire) {
            return "Fire category OFF";
        }
        if (isPlantBlock(state) && !categoryPlants) return "Plants category OFF";
        if ((state.hasBlockEntity() || specialModel) && !shouldRestoreSpecialModelCategory(state)) {
            return "Special Models category OFF";
        }
        if (isBlockEntityRenderedByBlockHider(state)) return "native BlockHider renderer";
        return "ready in active radius";
    }

    private static String textureCategoryName(BlockState state, boolean specialModel) {
        if (state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) return "liquids";
        if (state.is(Blocks.END_PORTAL) || state.is(BlockTags.PORTALS) || state.is(Blocks.END_GATEWAY)) return "portals";
        if (state.is(BlockTags.FIRE) || state.is(BlockTags.CAMPFIRES)) return "fire";
        if (isPlantBlock(state)) return "plants";
        return specialModel || state.hasBlockEntity() ? "special models" : "regular block";
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
        textureRadius = 6;
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
        invalidateCache();
        saveConfig();
        feedback(source, "BlockShape " + (enabled ? "enabled" : "disabled"));
        return 1;
    }

    private static int setOutlines(FabricClientCommandSource source, boolean value) {
        renderOutlines = value;
        invalidateCache();
        saveConfig();
        feedback(source, "Block outlines: " + (renderOutlines ? "enabled" : "disabled"));
        return 1;
    }

    private static int setHiddenTextures(FabricClientCommandSource source, boolean value) {
        renderHiddenTextures = value;
        invalidateCache();
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
            if (!qualityPreset.equals("performance") && !qualityPreset.equals("low")
                    && !qualityPreset.equals("balanced") && !qualityPreset.equals("high")
                    && !qualityPreset.equals("custom")) {
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
            textureRadius = clamp(
                    parseInt(properties.getProperty("textureRadius"), Math.min(radius, DEFAULT_TEXTURE_RADIUS)),
                    MIN_TEXTURE_RADIUS,
                    MAX_TEXTURE_RADIUS
            );
            textureRadius = Math.min(textureRadius, radius);
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
        properties.setProperty("textureRadius", Integer.toString(textureRadius));
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
