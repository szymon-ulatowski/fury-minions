package pl.furycraft.furyminions.manager;

import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import pl.furycraft.furyminions.FuryMinions;
import pl.furycraft.furyminions.model.Minion;
import pl.furycraft.furyminions.model.MinionType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Manages spawning, tracking, and despawning of minion {@link ArmorStand} entities.
 * Maintains both a {@link Map} for O(1) UUID lookups and a pre-allocated {@link List}
 * for zero-copy iteration in the scheduler hot path.
 */
@Getter
public class ArmorStandManager {

    private final FuryMinions plugin;
    private final Map<UUID, Minion> activeMinions;

    // Pre-allocated list shared with MinionScheduler to eliminate per-tick GC pressure during iteration
    private final List<Minion> activeMinionsList;

    public ArmorStandManager(FuryMinions plugin) {
        this.plugin = plugin;
        this.activeMinions = new HashMap<>();
        this.activeMinionsList = new ArrayList<>();
    }

    /**
     * Spawns a fully configured minion ArmorStand at the minion's location on the main thread.
     * Any stale ArmorStands already present at the target coordinates are removed first.
     * The future is resolved off the main thread; the actual spawn is bridged via {@code callSyncMethod}.
     *
     * @param minion the minion to spawn an ArmorStand for
     * @return a future that resolves to the spawned {@link ArmorStand}, or {@code null} on failure
     */
    public CompletableFuture<ArmorStand> spawnMinionArmorStand(Minion minion) {
        return CompletableFuture.supplyAsync(() -> {
            Location location = minion.getLocation();
            MinionType type = minion.getType();

            try {
                return Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                    for (Entity entity : location.getWorld().getNearbyEntities(location, 0.5, 0.5, 0.5)) {
                        if (entity instanceof ArmorStand as && isMinionArmorStand(as)) {
                            as.remove();
                        }
                    }

                    ArmorStand armorStand = (ArmorStand) location.getWorld().spawnEntity(location, EntityType.ARMOR_STAND);

                    armorStand.setVisible(true);
                    armorStand.setBasePlate(false);
                    armorStand.setArms(true);
                    armorStand.setSmall(true);
                    armorStand.setGravity(false);
                    armorStand.setInvulnerable(true);
                    armorStand.setSilent(true);
                    armorStand.setCustomNameVisible(false);
                    armorStand.setCollidable(false);

                    armorStand.getEquipment().setHelmet(createHeadItem(type.getHeadTexture()));
                    armorStand.getEquipment().setItemInMainHand(new ItemStack(type.getHandItem()));

                    equipColoredLeatherArmor(armorStand, type);

                    PersistentDataContainer pdc = armorStand.getPersistentDataContainer();
                    pdc.set(MinionKeys.getMinionUuidKey(), PersistentDataType.STRING, minion.getMinionUuid().toString());
                    pdc.set(MinionKeys.getOwnerUuidKey(), PersistentDataType.STRING, minion.getOwnerUuid().toString());
                    pdc.set(MinionKeys.getMinionTypeKey(), PersistentDataType.STRING, type.getId());
                    pdc.set(MinionKeys.getMinionLevelKey(), PersistentDataType.INTEGER, minion.getLevel());

                    minion.setArmorStand(armorStand);

                    return armorStand;
                }).get();
            } catch (Exception e) {
                plugin.getLogger().severe("Błąd podczas spawnowania ArmorStand: " + e.getMessage());
                return null;
            }
        });
    }

    /**
     * Creates a player skull {@link ItemStack} with the given Base64-encoded texture applied
     * via the Paper {@code PlayerProfile} API, without reflection or NMS.
     *
     * @param textureBase64 Base64-encoded texture string
     * @return the configured skull item
     */
    private ItemStack createHeadItem(String textureBase64) {
        ItemStack headItem = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta skullMeta = (SkullMeta) headItem.getItemMeta();

        if (skullMeta != null) {
            try {
                com.destroystokyo.paper.profile.PlayerProfile profile = Bukkit.createProfile(UUID.randomUUID(), "minion");
                profile.setProperty(new com.destroystokyo.paper.profile.ProfileProperty("textures", textureBase64));
                skullMeta.setPlayerProfile(profile);
                headItem.setItemMeta(skullMeta);
            } catch (Exception e) {
                plugin.getLogger().warning("Nie udało się ustawić tekstury głowy miniona: " + e.getMessage());
                e.printStackTrace();
            }
        }

        return headItem;
    }

    /**
     * Equips the given ArmorStand with dyed leather armor in the colour defined by the {@link MinionType}.
     */
    private void equipColoredLeatherArmor(ArmorStand armorStand, MinionType minionType) {
        Color armorColor = minionType.getArmorColorObject();

        armorStand.getEquipment().setChestplate(createColoredLeatherArmor(Material.LEATHER_CHESTPLATE, armorColor));
        armorStand.getEquipment().setLeggings(createColoredLeatherArmor(Material.LEATHER_LEGGINGS, armorColor));
        armorStand.getEquipment().setBoots(createColoredLeatherArmor(Material.LEATHER_BOOTS, armorColor));
    }

    private ItemStack createColoredLeatherArmor(Material material, Color color) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        if (meta instanceof LeatherArmorMeta leatherMeta) {
            leatherMeta.setColor(color);
            item.setItemMeta(leatherMeta);
        }

        return item;
    }

    /**
     * Returns {@code true} if the given entity is a tracked minion ArmorStand,
     * identified by the presence of the minion UUID key in its {@link PersistentDataContainer}.
     *
     * @param entity the entity to check
     * @return whether the entity is a minion ArmorStand
     */
    public boolean isMinionArmorStand(Entity entity) {
        if (!(entity instanceof ArmorStand)) {
            return false;
        }

        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        return pdc.has(MinionKeys.getMinionUuidKey(), PersistentDataType.STRING);
    }

    /**
     * Reads the minion UUID stored in the given ArmorStand's {@link PersistentDataContainer}.
     *
     * @param armorStand the ArmorStand to inspect
     * @return the minion {@link UUID}, or {@code null} if missing or malformed
     */
    public UUID getMinionUuidFromArmorStand(ArmorStand armorStand) {
        PersistentDataContainer pdc = armorStand.getPersistentDataContainer();
        String uuidString = pdc.get(MinionKeys.getMinionUuidKey(), PersistentDataType.STRING);

        if (uuidString != null) {
            try {
                return UUID.fromString(uuidString);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Nieprawidłowy UUID miniona w PDC: " + uuidString);
            }
        }
        return null;
    }

    /**
     * Scans the area around the given location for minion ArmorStands, removes duplicates,
     * and returns the single valid ArmorStand matching the expected UUID.
     * Runs the scan asynchronously but executes entity interaction on the main thread.
     *
     * @param location     the expected ArmorStand location
     * @param expectedUuid the UUID to match against
     * @return a future resolving to the valid {@link ArmorStand}, or {@code null} if none found
     */
    public CompletableFuture<ArmorStand> findAndCleanupArmorStands(Location location, UUID expectedUuid) {
        return CompletableFuture.supplyAsync(() -> {
            if (!location.isWorldLoaded()) {
                return null;
            }

            try {
                return Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                    ArmorStand validStand = null;
                    for (Entity entity : location.getWorld().getNearbyEntities(location, 1, 1, 1)) {
                        if (entity instanceof ArmorStand as && isMinionArmorStand(as) && as.getLocation().distance(location) < 0.5) {
                            if (validStand == null && isArmorStandValid(as, expectedUuid)) {
                                validStand = as;
                            } else {
                                as.remove();
                            }
                        }
                    }
                    return validStand;
                }).get();
            } catch (Exception e) {
                plugin.getLogger().severe("Błąd podczas szukania/czyszczenia ArmorStand: " + e.getMessage());
                return null;
            }
        });
    }

    /**
     * Checks whether the given ArmorStand is valid and its PDC UUID matches the expected minion UUID.
     *
     * @param armorStand          the ArmorStand to validate
     * @param expectedMinionUuid  the UUID to compare against
     * @return {@code true} if the ArmorStand is alive and owned by the specified minion
     */
    public boolean isArmorStandValid(ArmorStand armorStand, UUID expectedMinionUuid) {
        if (armorStand == null || !armorStand.isValid()) {
            return false;
        }

        UUID actualUuid = getMinionUuidFromArmorStand(armorStand);
        return actualUuid != null && actualUuid.equals(expectedMinionUuid);
    }

    /**
     * Removes and despawns the ArmorStand associated with the given minion UUID,
     * and deregisters the minion from the active tracking collections.
     *
     * @param minionUuid the UUID of the minion to remove
     */
    public void removeMinionArmorStand(UUID minionUuid) {
        Minion minion = activeMinions.get(minionUuid);
        if (minion != null) {
            minion.removeArmorStand();
            activeMinions.remove(minionUuid);
            activeMinionsList.remove(minion);
        }
    }

    /**
     * Schedules a recurring cleanup task that removes orphaned minion entries
     * whose ArmorStands have become invalid (e.g. killed by a plugin or after a crash).
     * Runs every 5 minutes (6000 ticks).
     */
    public void cleanupInvalidArmorStands() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            activeMinionsList.removeIf(minion -> {
                ArmorStand armorStand = minion.getArmorStand();
                if (armorStand == null || !armorStand.isValid()) {
                    activeMinions.remove(minion.getMinionUuid());
                    return true;
                }
                return false;
            });
        }, 6000L, 6000L);
    }

    /**
     * Returns the active {@link Minion} instance for the given UUID, or {@code null} if not loaded.
     *
     * @param minionUuid the minion UUID to look up
     * @return the active minion, or {@code null}
     */
    public Minion getActiveMinion(UUID minionUuid) {
        return activeMinions.get(minionUuid);
    }

    /**
     * Registers a minion as active. If the minion is already tracked, only the map entry is updated
     * (prevents duplicate entries in {@code activeMinionsList}).
     *
     * @param minion the minion to register
     */
    public void addActiveMinion(Minion minion) {
        if (!activeMinions.containsKey(minion.getMinionUuid())) {
            activeMinionsList.add(minion);
        }
        activeMinions.put(minion.getMinionUuid(), minion);
    }

    /**
     * Deregisters a minion from active tracking without despawning its ArmorStand.
     *
     * @param minionUuid the UUID of the minion to deregister
     */
    public void removeActiveMinion(UUID minionUuid) {
        Minion minion = activeMinions.remove(minionUuid);
        if (minion != null) {
            activeMinionsList.remove(minion);
        }
    }

    /**
     * Returns a direct reference to the shared active minions list.
     * Callers must not modify this list concurrently without synchronization,
     * as it is iterated on the main thread by {@link MinionScheduler} every tick.
     *
     * @return the live, mutable list of active minions
     */
    public List<Minion> getActiveMinionsList() {
        return activeMinionsList;
    }
}