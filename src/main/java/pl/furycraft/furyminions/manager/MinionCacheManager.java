package pl.furycraft.furyminions.manager;

import org.bukkit.Chunk;
import pl.furycraft.furyminions.FuryMinions;
import pl.furycraft.furyminions.model.MinionData;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory, chunk-keyed cache of {@link MinionData} records loaded from the database.
 * <p>
 * The two-level structure {@code Map<WorldName, Map<ChunkKey, List<MinionData>>}
 * allows O(1) chunk-based lookup in {@link pl.furycraft.furyminions.listener.ChunkLoadListener},
 * avoiding a full database round-trip on every chunk load event.
 * <p>
 * All collections use {@link ConcurrentHashMap} because chunk load/unload events and
 * async database callbacks may update the cache from different threads concurrently.
 */
public class MinionCacheManager {

    private final FuryMinions plugin;
    private final Map<String, Map<Long, List<MinionData>>> cache = new ConcurrentHashMap<>();

    public MinionCacheManager(FuryMinions plugin) {
        this.plugin = plugin;
    }

    /**
     * Asynchronously loads all minion records from the database and populates the cache.
     * Called once during plugin startup; subsequent updates are incremental.
     */
    public void loadAllFromDatabase() {
        plugin.getLogger().info("Ładowanie minionów do cache...");
        plugin.getDatabaseManager().loadAllMinionsAsync().thenAccept(minionsData -> {
            cache.clear();
            for (MinionData data : minionsData) {
                addMinionToCache(data);
            }
            plugin.getLogger().info("Pomyślnie załadowano " + minionsData.size() + " minionów do cache.");
        }).exceptionally(ex -> {
            plugin.getLogger().severe("Błąd podczas ładowania cache minionów: " + ex.getMessage());
            return null;
        });
    }

    /**
     * Inserts or replaces a minion record in the cache. If a record with the same UUID
     * already exists in the target chunk bucket, it is removed before insertion to keep
     * the cache consistent after upgrades or moves.
     *
     * @param data the minion data to cache
     */
    public void addMinionToCache(MinionData data) {
        String world = data.getWorld();
        int chunkX = ((int) Math.floor(data.getX())) >> 4;
        int chunkZ = ((int) Math.floor(data.getZ())) >> 4;
        long chunkKey = Chunk.getChunkKey(chunkX, chunkZ);

        List<MinionData> chunkMinions = cache.computeIfAbsent(world, k -> new ConcurrentHashMap<>())
             .computeIfAbsent(chunkKey, k -> new ArrayList<>());

        chunkMinions.removeIf(m -> m.getMinionUuid().equals(data.getMinionUuid()));
        chunkMinions.add(data);
    }

    /**
     * Removes a minion record from the cache by its full {@link MinionData} descriptor.
     * Empty chunk buckets are pruned to prevent unbounded memory growth.
     *
     * @param data the minion data to remove
     */
    public void removeMinionFromCache(MinionData data) {
        String world = data.getWorld();
        int chunkX = ((int) Math.floor(data.getX())) >> 4;
        int chunkZ = ((int) Math.floor(data.getZ())) >> 4;
        long chunkKey = Chunk.getChunkKey(chunkX, chunkZ);

        Map<Long, List<MinionData>> worldCache = cache.get(world);
        if (worldCache != null) {
            List<MinionData> chunkMinions = worldCache.get(chunkKey);
            if (chunkMinions != null) {
                chunkMinions.removeIf(m -> m.getMinionUuid().equals(data.getMinionUuid()));
                if (chunkMinions.isEmpty()) {
                    worldCache.remove(chunkKey);
                }
            }
        }
    }

    /**
     * Removes a minion record from the cache by UUID, scanning all worlds and chunks.
     * Used when only the UUID is known (e.g. deletion triggered by an admin command).
     *
     * @param minionUuid the UUID of the minion to evict
     */
    public void removeMinionFromCache(UUID minionUuid) {
        for (Map.Entry<String, Map<Long, List<MinionData>>> worldEntry : cache.entrySet()) {
            Map<Long, List<MinionData>> worldCache = worldEntry.getValue();
            worldCache.values().forEach(chunkMinions -> chunkMinions.removeIf(m -> m.getMinionUuid().equals(minionUuid)));
            worldCache.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        }
    }

    /**
     * Returns a snapshot of all minion records stored in the specified chunk.
     *
     * @param worldName the name of the world
     * @param chunkKey  the chunk key as returned by {@link Chunk#getChunkKey()}
     * @return a defensive copy of the chunk's minion list, or an empty list if none
     */
    public List<MinionData> getMinionsInChunk(String worldName, long chunkKey) {
        Map<Long, List<MinionData>> worldCache = cache.get(worldName);
        if (worldCache != null) {
            List<MinionData> chunkMinions = worldCache.get(chunkKey);
            if (chunkMinions != null) {
                return new ArrayList<>(chunkMinions);
            }
        }
        return new ArrayList<>();
    }
}
