/*
 * The MIT License (MIT)
 *
 * Copyright 2022 Vladimir Mikhailov <beykerykt@gmail.com>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package ru.beykerykt.minecraft.lightapi.bukkit.internal.handler.craftbukkit.nms.v1_19_R2;

import ca.spottedleaf.concurrentutil.executor.standard.PrioritisedExecutor;
import io.papermc.paper.chunk.system.light.LightQueue;
import io.papermc.paper.chunk.system.scheduling.ChunkLightTask;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.v1_19_R2.CraftWorld;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

import ca.spottedleaf.starlight.common.light.BlockStarLightEngine;
import ca.spottedleaf.starlight.common.light.SkyStarLightEngine;
import ca.spottedleaf.starlight.common.light.StarLightEngine;
import ca.spottedleaf.starlight.common.light.StarLightInterface;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import ru.beykerykt.minecraft.lightapi.bukkit.internal.BukkitPlatformImpl;
import ru.beykerykt.minecraft.lightapi.bukkit.internal.handler.craftbukkit.nms.BaseNMSHandler;
import ru.beykerykt.minecraft.lightapi.common.api.ResultCode;
import ru.beykerykt.minecraft.lightapi.common.api.engine.LightFlag;
import ru.beykerykt.minecraft.lightapi.common.internal.chunks.data.BitChunkData;
import ru.beykerykt.minecraft.lightapi.common.internal.chunks.data.IChunkData;
import ru.beykerykt.minecraft.lightapi.common.internal.engine.LightEngineType;
import ru.beykerykt.minecraft.lightapi.common.internal.engine.LightEngineVersion;
import ru.beykerykt.minecraft.lightapi.common.internal.utils.FlagUtils;

import static ru.beykerykt.minecraft.lightapi.bukkit.internal.handler.craftbukkit.nms.v1_19_R2.VanillaNMSHandler.toRuntimeException;

// Due to the chunk system and light system changes in paper, these code needs to be rewritten
// For current usage, use compatibility mode first.
// https://github.com/PaperMC/Paper/pull/8177
public class StarlightNMSHandler extends BaseNMSHandler {

    private final int ALL_DIRECTIONS_BITSET = (1 << 6) - 1;
    private final long FLAG_HAS_SIDED_TRANSPARENT_BLOCKS = Long.MIN_VALUE;
    private final Map<ChunkPos, Set<LightPos>> blockQueueMap = new ConcurrentHashMap<>();
    private final Map<ChunkPos, Set<LightPos>> skyQueueMap = new ConcurrentHashMap<>();
    // StarLightInterface
    private Field starInterface;
    private Field starInterface_coordinateOffset;
    private Method starInterface_getBlockLightEngine;
    private Method starInterface_getSkyLightEngine;

    // StarLightEngine
    private Method starEngine_setLightLevel;
    private Method starEngine_appendToIncreaseQueue;
    private Method starEngine_appendToDecreaseQueue;
    private Method starEngine_performLightIncrease;
    private Method starEngine_performLightDecrease;
    private Method starEngine_updateVisible;
    private Method starEngine_setupCaches;
    private Method starEngine_destroyCaches;

    private final class LightTask implements BooleanSupplier {
        private final ServerLevel worldServer;
        private final StarLightEngine sle;
        private final ChunkPos chunkCoordIntPair;
        private final Set<LightPos> lightPoints;

        public LightTask(ServerLevel worldServer, StarLightEngine sle,
                         ChunkPos chunkCoordIntPair, Set<LightPos> lightPoints) {
            this.worldServer = worldServer;
            this.sle = sle;
            this.chunkCoordIntPair = chunkCoordIntPair;
            this.lightPoints = lightPoints;
        }

        public boolean getAsBoolean() {
            try {
                int chunkX = chunkCoordIntPair.x;
                int chunkZ = chunkCoordIntPair.z;
                int type = (sle instanceof BlockStarLightEngine) ? LightFlag.BLOCK_LIGHTING : LightFlag.SKY_LIGHTING;
                if (!worldServer.getChunkSource().isChunkLoaded(chunkX, chunkZ)) {
                    return false;
                }

                // blocksChangedInChunk -- start
                // setup cache
                starEngine_setupCaches.invoke(sle, worldServer.getChunkSource(), chunkX * 16 + 7, 128, chunkZ * 16 + 7,
                        true, true);
                try {
                    // propagateBlockChanges -- start
                    Iterator<LightPos> it = lightPoints.iterator();
                    while (it.hasNext()) {
                        try {
                            LightPos lightPos = it.next();
                            BlockPos blockPos = lightPos.blockPos;
                            int lightLevel = lightPos.lightLevel;
                            int currentLightLevel = getRawLightLevel(worldServer.getWorld(), blockPos.getX(),
                                    blockPos.getY(), blockPos.getZ(), type);
                            if (lightLevel <= currentLightLevel) {
                                // do nothing
                                continue;
                            }
                            int encodeOffset = starInterface_coordinateOffset.getInt(sle);
                            Block.BlockStateBase blockData = worldServer.getBlockState(blockPos);
                            starEngine_setLightLevel.invoke(sle, blockPos.getX(), blockPos.getY(), blockPos.getZ(),
                                    lightLevel);
                            if (lightLevel != 0) {
                                starEngine_appendToIncreaseQueue.invoke(sle,
                                        ((blockPos.getX() + (blockPos.getZ() << 6) + (blockPos.getY() << (6 + 6))
                                                + encodeOffset) & ((1L << (6 + 6 + 16)) - 1)) | (lightLevel & 0xFL) << (
                                                6 + 6 + 16) | (((long) ALL_DIRECTIONS_BITSET) << (6 + 6 + 16 + 4)) | (
                                                blockData.isConditionallyFullOpaque()
                                                        ? FLAG_HAS_SIDED_TRANSPARENT_BLOCKS : 0));
                            }
                        } finally {
                            it.remove();
                        }
                    }
                    starEngine_performLightIncrease.invoke(sle, worldServer.getChunkSource());
                    // propagateBlockChanges -- end
                    starEngine_updateVisible.invoke(sle, worldServer.getChunkSource());
                } finally {
                    starEngine_destroyCaches.invoke(sle);
                }
                // blocksChangedInChunk -- end
            } catch (Exception ex) {
                ex.printStackTrace();
                return false;
            }
            return true;
        }
    }
    private void addTaskToQueue(ServerLevel worldServer, StarLightInterface starLightInterface, StarLightEngine sle,
                                ChunkPos chunkCoordIntPair, Set<LightPos> lightPoints) {
        final LightQueue lightQueue = starLightInterface.lightQueue;
        ChunkLightTask chunkLightTask = new ChunkLightTask()
        lightQueue.queueChunkLightTask(chunkCoordIntPair, new LightTask(worldServer, sle, chunkCoordIntPair, lightPoints), PrioritisedExecutor.Priority.HIGHEST);
    }

    protected void executeSync(ThreadedLevelLightEngine lightEngine, Runnable task) {
            task.run();
    }

    @Override
    public void onInitialization(BukkitPlatformImpl impl) throws Exception {
        super.onInitialization(impl);
        try {
            starEngine_setLightLevel = StarLightEngine.class.getDeclaredMethod("setLightLevel", int.class, int.class,
                    int.class, int.class);
            starEngine_setLightLevel.setAccessible(true);
            starEngine_appendToIncreaseQueue = StarLightEngine.class.getDeclaredMethod("appendToIncreaseQueue",
                    long.class);
            starEngine_appendToIncreaseQueue.setAccessible(true);
            starEngine_appendToDecreaseQueue = StarLightEngine.class.getDeclaredMethod("appendToDecreaseQueue",
                    long.class);
            starEngine_appendToDecreaseQueue.setAccessible(true);
            starEngine_performLightIncrease = StarLightEngine.class.getDeclaredMethod("performLightIncrease",
                    LightChunkGetter.class);
            starEngine_performLightIncrease.setAccessible(true);
            starEngine_performLightDecrease = StarLightEngine.class.getDeclaredMethod("performLightDecrease",
                    LightChunkGetter.class);
            starEngine_performLightDecrease.setAccessible(true);
            starEngine_updateVisible = StarLightEngine.class.getDeclaredMethod("updateVisible", LightChunkGetter.class);
            starEngine_updateVisible.setAccessible(true);
            starEngine_setupCaches = StarLightEngine.class.getDeclaredMethod("setupCaches", LightChunkGetter.class,
                    int.class, int.class, int.class, boolean.class, boolean.class);
            starEngine_setupCaches.setAccessible(true);
            starEngine_destroyCaches = StarLightEngine.class.getDeclaredMethod("destroyCaches");
            starEngine_destroyCaches.setAccessible(true);
            starInterface = ThreadedLevelLightEngine.class.getDeclaredField("theLightEngine");
            starInterface.setAccessible(true);
            starInterface_getBlockLightEngine = StarLightInterface.class.getDeclaredMethod("getBlockLightEngine");
            starInterface_getBlockLightEngine.setAccessible(true);
            starInterface_getSkyLightEngine = StarLightInterface.class.getDeclaredMethod("getSkyLightEngine");
            starInterface_getSkyLightEngine.setAccessible(true);
            starInterface_coordinateOffset = StarLightEngine.class.getDeclaredField("coordinateOffset");
            starInterface_coordinateOffset.setAccessible(true);
        } catch (Exception e) {
            throw toRuntimeException(e);
        }
    }

    @Override
    public void onShutdown(BukkitPlatformImpl impl) {}

    @Override
    public LightEngineType getLightEngineType() {
        return LightEngineType.STARLIGHT;
    }

    @Override
    public LightEngineVersion getLightEngineVersion() {
        return LightEngineVersion.V2;
    }

    @Override
    public void onWorldLoad(WorldLoadEvent event) {

    }

    @Override
    public void onWorldUnload(WorldUnloadEvent event) {

    }

    @Override
    public boolean isLightingSupported(World world, int lightFlags) {
        ServerLevel worldServer = ((CraftWorld) world).getHandle();
        ThreadedLevelLightEngine lightEngine = worldServer.getChunkSource().getLightEngine();
        if (FlagUtils.isFlagSet(lightFlags, LightFlag.SKY_LIGHTING)) {
            return lightEngine.getLayerListener(LightLayer.SKY) != null;
        } else if (FlagUtils.isFlagSet(lightFlags, LightFlag.BLOCK_LIGHTING)) {
            return lightEngine.getLayerListener(LightLayer.BLOCK) != null;
        }
        return false;
    }

    @Override
    public int setRawLightLevel(World world, int blockX, int blockY, int blockZ, int lightLevel, int flags) {
        ServerLevel worldServer = ((CraftWorld) world).getHandle();
        final BlockPos position = new BlockPos(blockX, blockY, blockZ);
        final ThreadedLevelLightEngine lightEngine = worldServer.getChunkSource().getLightEngine();
        final int finalLightLevel = lightLevel < 0 ? 0 : Math.min(lightLevel, 15);
        ChunkPos chunkCoordIntPair = new ChunkPos(blockX >> 4, blockZ >> 4);

        if (!worldServer.getChunkSource().isChunkLoaded(blockX >> 4, blockZ >> 4)) {
            return ResultCode.CHUNK_NOT_LOADED;
        }
        executeSync(lightEngine, () -> {
            // block lighting
            if (FlagUtils.isFlagSet(flags, LightFlag.BLOCK_LIGHTING)) {
                if (isLightingSupported(world, LightFlag.BLOCK_LIGHTING)) {
                    LayerLightEventListener lele = lightEngine.getLayerListener(LightLayer.BLOCK);
                    if (finalLightLevel == 0) {
                        try {
                            StarLightInterface starLightInterface = (StarLightInterface) starInterface.get(lightEngine);
                            starLightInterface.blockChange(position);
                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    } else if (lele.getDataLayerData(SectionPos.of(position)) != null) {
                        try {
                            if (blockQueueMap.containsKey(chunkCoordIntPair)) {
                                Set<LightPos> lightPoints = blockQueueMap.get(chunkCoordIntPair);
                                lightPoints.add(new LightPos(position, finalLightLevel));
                            } else {
                                Set<LightPos> lightPoints = new HashSet<>();
                                lightPoints.add(new LightPos(position, finalLightLevel));
                                blockQueueMap.put(chunkCoordIntPair, lightPoints);
                            }
                        } catch (NullPointerException ignore) {
                            // To prevent problems with the absence of the NibbleArray, even
                            // if leb.a(SectionPosition.a(position)) returns non-null value (corrupted data)
                        }
                    }
                }
            }

            // sky lighting
            if (FlagUtils.isFlagSet(flags, LightFlag.SKY_LIGHTING)) {
                if (isLightingSupported(world, LightFlag.SKY_LIGHTING)) {
                    LayerLightEventListener lele = lightEngine.getLayerListener(LightLayer.SKY);
                    if (finalLightLevel == 0) {
                        try {
                            StarLightInterface starLightInterface = (StarLightInterface) starInterface.get(lightEngine);
                            starLightInterface.blockChange(position);
                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    } else if (lele.getDataLayerData(SectionPos.of(position)) != null) {
                        try {
                            if (skyQueueMap.containsKey(chunkCoordIntPair)) {
                                Set<LightPos> lightPoints = skyQueueMap.get(chunkCoordIntPair);
                                lightPoints.add(new LightPos(position, finalLightLevel));
                            } else {
                                Set<LightPos> lightPoints = new HashSet<>();
                                lightPoints.add(new LightPos(position, finalLightLevel));
                                skyQueueMap.put(chunkCoordIntPair, lightPoints);
                            }
                        } catch (NullPointerException ignore) {
                            // To prevent problems with the absence of the NibbleArray, even
                            // if les.a(SectionPosition.a(position)) returns non-null value (corrupted data)
                        }
                    }
                }
            }
        });
        Map<ChunkPos, Set<LightPos>> targetMap = null;
        if (FlagUtils.isFlagSet(flags, LightFlag.SKY_LIGHTING)) {
            targetMap = skyQueueMap;
        } else if (FlagUtils.isFlagSet(flags, LightFlag.BLOCK_LIGHTING)) {
            targetMap = blockQueueMap;
        }
        if (lightEngine.hasLightWork() || targetMap != null && targetMap.containsKey(chunkCoordIntPair)) {
            return ResultCode.SUCCESS;
        }
        return ResultCode.FAILED;
    }

    @Override
    public int getRawLightLevel(World world, int blockX, int blockY, int blockZ, int flags) {
        int lightLevel = -1;
        ServerLevel worldServer = ((CraftWorld) world).getHandle();
        BlockPos position = new BlockPos(blockX, blockY, blockZ);
        if (FlagUtils.isFlagSet(flags, LightFlag.BLOCK_LIGHTING) && FlagUtils.isFlagSet(flags,
                LightFlag.SKY_LIGHTING)) {
            lightLevel = worldServer.getLightEmission(position);
        } else if (FlagUtils.isFlagSet(flags, LightFlag.BLOCK_LIGHTING)) {
            lightLevel = worldServer.getBrightness(LightLayer.BLOCK, position);
        } else if (FlagUtils.isFlagSet(flags, LightFlag.SKY_LIGHTING)) {
            lightLevel = worldServer.getBrightness(LightLayer.SKY, position);
        }
        return lightLevel;
    }

    @Override
    public int recalculateLighting(World world, int blockX, int blockY, int blockZ, int flags) {
        ServerLevel worldServer = ((CraftWorld) world).getHandle();
        final ThreadedLevelLightEngine lightEngine = worldServer.getChunkSource().getLightEngine();

        if (!worldServer.getChunkSource().isChunkLoaded(blockX >> 4, blockZ >> 4)) {
            return ResultCode.CHUNK_NOT_LOADED;
        }

        // Do not recalculate if no changes!
        if (!lightEngine.hasLightWork() && blockQueueMap.isEmpty() && skyQueueMap.isEmpty()) {
            return ResultCode.RECALCULATE_NO_CHANGES;
        }

        try {
            StarLightInterface starLightInterface = (StarLightInterface) starInterface.get(lightEngine);
            Iterator<Map.Entry<ChunkPos, Set<LightPos>>> blockIt = blockQueueMap.entrySet().iterator();
            while (blockIt.hasNext()) {
                BlockStarLightEngine bsle = (BlockStarLightEngine) starInterface_getBlockLightEngine.invoke(
                        starLightInterface);
                Map.Entry<ChunkPos, Set<LightPos>> pair = (Map.Entry<ChunkPos, Set<LightPos>>) blockIt.next();
                ChunkPos chunkCoordIntPair = pair.getKey();
                Set<LightPos> lightPoints = pair.getValue();
                addTaskToQueue(worldServer, starLightInterface, bsle, chunkCoordIntPair, lightPoints);
                blockIt.remove();
            }

            Iterator skyIt = skyQueueMap.entrySet().iterator();
            while (skyIt.hasNext()) {
                SkyStarLightEngine ssle = (SkyStarLightEngine) starInterface_getSkyLightEngine.invoke(
                        starLightInterface);
                Map.Entry<ChunkPos, Set<LightPos>> pair = (Map.Entry<ChunkPos, Set<LightPos>>) skyIt.next();
                ChunkPos chunkCoordIntPair = pair.getKey();
                Set<LightPos> lightPoints = pair.getValue();
                addTaskToQueue(worldServer, starLightInterface, ssle, chunkCoordIntPair, lightPoints);
                skyIt.remove();
            }
        } catch (IllegalAccessException | InvocationTargetException e) {
            e.printStackTrace();
        }

        return ResultCode.SUCCESS;
    }

    @Override
    public IChunkData createChunkData(String worldName, int chunkX, int chunkZ) {
        return createBitChunkData(worldName, chunkX, chunkZ);
    }

    private IChunkData searchChunkDataFromList(List<IChunkData> list, World world, int chunkX, int chunkZ) {
        for (IChunkData data : list) {
            if (data.getWorldName().equals(world.getName()) && data.getChunkX() == chunkX
                    && data.getChunkZ() == chunkZ) {
                return data;
            }
        }
        return createChunkData(world.getName(), chunkX, chunkZ);
    }

    private IChunkData createBitChunkData(String worldName, int chunkX, int chunkZ) {
        World world = Bukkit.getWorld(worldName);
        ServerLevel worldServer = ((CraftWorld) world).getHandle();
        final ThreadedLevelLightEngine lightEngine = worldServer.getChunkSource().getLightEngine();
        int bottom = lightEngine.getMinLightSection();
        int top = lightEngine.getMaxLightSection();
        return new BitChunkData(worldName, chunkX, chunkZ, top, bottom);
    }
    private int getDeltaLight(int x, int dx) {
        return (((x ^ ((-dx >> 4) & 15)) + 1) & (-(dx & 1)));
    }

    @Override
    public List<IChunkData> collectChunkSections(World world, int blockX, int blockY, int blockZ, int lightLevel,
                                                 int lightFlags) {
        ServerLevel worldServer = ((CraftWorld) world).getHandle();
        List<IChunkData> list = new ArrayList<>();
        int finalLightLevel = lightLevel < 0 ? 0 : Math.min(lightLevel, 15);

        if (world == null) {
            return list;
        }

        for (int dX = -1; dX <= 1; dX++) {
            int lightLevelX = finalLightLevel - getDeltaLight(blockX & 15, dX);
            if (lightLevelX > 0) {
                for (int dZ = -1; dZ <= 1; dZ++) {
                    int lightLevelZ = lightLevelX - getDeltaLight(blockZ & 15, dZ);
                    if (lightLevelZ > 0) {
                        int chunkX = (blockX >> 4) + dX;
                        int chunkZ = (blockZ >> 4) + dZ;
                        if (!worldServer.getChunkSource().isChunkLoaded(chunkX, chunkZ)) {
                            continue;
                        }
                        for (int dY = -1; dY <= 1; dY++) {
                            if (lightLevelZ > getDeltaLight(blockY & 15, dY)) {
                                int sectionY = (blockY >> 4) + dY;
                                if (isValidChunkSection(world, sectionY)) {
                                    IChunkData data = searchChunkDataFromList(list, world, chunkX, chunkZ);
                                    if (!list.contains(data)) {
                                        list.add(data);
                                    }
                                    data.markSectionForUpdate(lightFlags, sectionY);
                                }
                            }
                        }
                    }
                }
            }
        }
        return list;
    }

    @Override
    public boolean isValidChunkSection(World world, int sectionY) {
        ServerLevel worldServer = ((CraftWorld) world).getHandle();
        ThreadedLevelLightEngine lightEngine = worldServer.getChunkSource().getLightEngine();
        return (sectionY >= lightEngine.getMinLightSection()) && (sectionY <= lightEngine.getMaxLightSection());
    }

    @Override
    public int sendChunk(IChunkData data) { return 0; }

    @Override
    public int sendCmd(int cmdId, Object... args) { return 0; }

    private static final class LightPos {

        public BlockPos blockPos;
        public int lightLevel;

        public LightPos(BlockPos blockPos, int lightLevel) {
            this.blockPos = blockPos;
            this.lightLevel = lightLevel;
        }
    }
}
