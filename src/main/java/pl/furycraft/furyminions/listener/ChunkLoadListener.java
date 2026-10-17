package pl.furycraft.furyminions.listener;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import pl.furycraft.furyminions.FuryMinions;
import pl.furycraft.furyminions.manager.ArmorStandManager;
import pl.furycraft.furyminions.model.Minion;
import pl.furycraft.furyminions.model.MinionData;
import pl.furycraft.furyminions.model.MinionType;

import java.util.List;
import java.util.UUID;

/**
 * Listens for chunk load events and spawns minions that belong to newly loaded chunks.
 * <p>
 * Minion data is served from the in-memory {@link pl.furycraft.furyminions.manager.MinionCacheManager},
 * avoiding a database query on every chunk load. A {@code loadingMinions} set prevents duplicate
 * spawn attempts when a chunk is loaded multiple times in quick succession.
 */
public class ChunkLoadListener implements Listener {

    private final FuryMinions plugin;
    private final ArmorStandManager armorStandManager;
    /** Guards against concurrent/duplicate spawn attempts for the same minion UUID. */
    private final java.util.Set<UUID> loadingMinions = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public ChunkLoadListener(FuryMinions plugin) {
        this.plugin = plugin;
        this.armorStandManager = plugin.getArmorStandManager();
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        loadMinionsInChunk(event.getChunk());
    }

    /**
     * Fetches all minion records cached for the given chunk and initiates their spawn sequence.
     */
    private void loadMinionsInChunk(Chunk chunk) {
        String worldName = chunk.getWorld().getName();
        long chunkKey = chunk.getChunkKey();

        List<MinionData> minionsData = plugin.getMinionCacheManager().getMinionsInChunk(worldName, chunkKey);

        for (MinionData data : minionsData) {
            loadMinion(data, chunk);
        }
    }

    /**
     * Spawns a single minion from its persisted data.
     * Applies offline progression, reuses an existing ArmorStand if present, or spawns a new one.
     * All entity interactions are executed synchronously on the main thread via {@code runTask}.
     *
     * @param data  the persisted minion record
     * @param chunk the chunk the minion belongs to
     */
    private void loadMinion(MinionData data, Chunk chunk) {
        UUID minionUuid = data.getMinionUuid();

        if (armorStandManager.getActiveMinion(minionUuid) != null || !loadingMinions.add(minionUuid)) {
            return;
        }

        try {
            MinionType type = plugin.getConfigManager().getMinionType(data.getType());
            if (type == null) {
                plugin.getLogger().warning("Nieznany typ miniona: " + data.getType() + " dla UUID: " + minionUuid);
                loadingMinions.remove(minionUuid);
                return;
            }

            Minion minion = new Minion(
                minionUuid,
                data.getOwnerUuid(),
                type,
                data.getLevel(),
                data.toLocation(),
                data.getFacingDirection()
            );

            minion.deserializeInventory(data.getInventoryContent());
            minion.setExtraData(data.getExtraData() != null ? data.getExtraData() : "");

            // Apply offline progression before the hologram and ArmorStand are created
            plugin.getOfflineProgressionManager().processOfflineTime(minion, data.getUpdatedAt());

            armorStandManager.findAndCleanupArmorStands(minion.getLocation(), minionUuid).thenAccept(existingArmorStand -> {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        if (!chunk.isLoaded()) {
                            return;
                        }

                        if (existingArmorStand != null) {
                            minion.setArmorStand(existingArmorStand);
                            armorStandManager.addActiveMinion(minion);
                            plugin.getHologramManager().updateHologram(minion);
                        } else {
                            armorStandManager.spawnMinionArmorStand(minion).thenAccept(armorStand -> {
                                Bukkit.getScheduler().runTask(plugin, () -> {
                                    try {
                                        if (!chunk.isLoaded()) {
                                            if (armorStand != null) {
                                                armorStand.remove();
                                            }
                                            return;
                                        }
                                        if (armorStand != null) {
                                            minion.setArmorStand(armorStand);
                                            armorStandManager.addActiveMinion(minion);
                                            plugin.getHologramManager().createHologram(minion);
                                        }
                                    } finally {
                                        loadingMinions.remove(minionUuid);
                                    }
                                });
                            }).exceptionally(ex -> {
                                plugin.getLogger().severe("Błąd podczas spawnowania Armor Stand: " + ex.getMessage());
                                loadingMinions.remove(minionUuid);
                                return null;
                            });
                            return;
                        }
                    } finally {
                        if (existingArmorStand != null || !chunk.isLoaded()) {
                            loadingMinions.remove(minionUuid);
                        }
                    }
                });
            }).exceptionally(ex -> {
                plugin.getLogger().severe("Błąd podczas szukania Armor Stand: " + ex.getMessage());
                loadingMinions.remove(minionUuid);
                return null;
            });
        } catch (Exception ex) {
            plugin.getLogger().severe("Błąd podczas przetwarzania miniona: " + ex.getMessage());
            loadingMinions.remove(minionUuid);
        }
    }
}
