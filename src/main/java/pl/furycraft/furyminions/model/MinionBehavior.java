package pl.furycraft.furyminions.model;

/**
 * Enumerates all supported minion action modes.
 * Used by {@link pl.furycraft.furyminions.manager.MinionScheduler} to dispatch the correct
 * behaviour implementation and by {@link pl.furycraft.furyminions.manager.HologramManager}
 * to validate platform requirements.
 */
public enum MinionBehavior {

    /** Places ore blocks on the platform and mines them when full. */
    MINER,

    /** Plants crops (wheat, carrot, potato) on farmland and harvests them when fully grown. */
    CROP_FARMER,

    /** Harvests the top block of sugar cane columns, leaving the base to regrow. */
    CANE_FARMER,

    /** Places melon or pumpkin fruit blocks on the platform and harvests them. */
    MELON_FARMER,

    /** Simulates animal husbandry and produces drops without spawning real entities. */
    ANIMAL_FARMER,

    /** Fishes on a player-built 5×5 water platform. */
    FISHER,

    /** Places log blocks on the platform and chops them. Supports all vanilla wood types. */
    TREE_FARMER,

    /** Plants nether wart on player-placed soul sand and harvests it when fully grown. */
    NETHER_HERB,

    /** Places cactus blocks on a checkerboard pattern of sand and harvests them. */
    CACTUS_FARMER
}
