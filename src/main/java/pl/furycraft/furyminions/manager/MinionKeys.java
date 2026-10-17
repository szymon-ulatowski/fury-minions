package pl.furycraft.furyminions.manager;

import org.bukkit.NamespacedKey;
import pl.furycraft.furyminions.FuryMinions;

/**
 * Holds all {@link NamespacedKey} constants used to tag minion {@link org.bukkit.entity.ArmorStand}
 * entities and minion item {@link org.bukkit.persistence.PersistentDataContainer} entries.
 * <p>
 * Must be initialised once via {@link #initialize(FuryMinions)} before any key is accessed,
 * as keys require a plugin reference for namespacing.
 */
public class MinionKeys {

    private static NamespacedKey minionUuidKey;
    private static NamespacedKey ownerUuidKey;
    private static NamespacedKey minionTypeKey;
    private static NamespacedKey minionLevelKey;

    /**
     * Initialises all namespaced keys. Must be called once during plugin startup,
     * before any manager that reads or writes PDC data is constructed.
     *
     * @param plugin the plugin instance used for key namespacing
     */
    public static void initialize(FuryMinions plugin) {
        minionUuidKey = new NamespacedKey(plugin, "minion_uuid");
        ownerUuidKey = new NamespacedKey(plugin, "owner_uuid");
        minionTypeKey = new NamespacedKey(plugin, "minion_type");
        minionLevelKey = new NamespacedKey(plugin, "minion_level");
    }

    public static NamespacedKey getMinionUuidKey() {
        return minionUuidKey;
    }

    public static NamespacedKey getOwnerUuidKey() {
        return ownerUuidKey;
    }

    public static NamespacedKey getMinionTypeKey() {
        return minionTypeKey;
    }

    public static NamespacedKey getMinionLevelKey() {
        return minionLevelKey;
    }
}
