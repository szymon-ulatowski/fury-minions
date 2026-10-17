package pl.furycraft.furyminions.manager;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import pl.furycraft.furyminions.FuryMinions;
import pl.furycraft.furyminions.model.Minion;
import pl.furycraft.furyminions.model.MinionBehavior;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Simulates minion work that occurred while the chunk was unloaded ("offline progression").
 * <p>
 * Rather than running real block interactions retrospectively, this manager calculates how many
 * simulated actions the minion would have performed based on the elapsed offline time and
 * adds the corresponding drops directly to the minion's inventory.
 * This avoids chunk loading side-effects while still rewarding players for offline time.
 */
public class OfflineProgressionManager {

    private final FuryMinions plugin;

    /**
     * Pre-built fish drop table matching vanilla fishing loot probabilities.
     * Shared array avoids per-tick allocation; individual items are cloned on use.
     */
    private static final ItemStack[] FISH_DROPS = {
        new ItemStack(Material.COD), new ItemStack(Material.COD), new ItemStack(Material.COD),
        new ItemStack(Material.COD), new ItemStack(Material.COD), new ItemStack(Material.COD),
        new ItemStack(Material.SALMON), new ItemStack(Material.SALMON), new ItemStack(Material.SALMON),
        new ItemStack(Material.TROPICAL_FISH), new ItemStack(Material.TROPICAL_FISH),
        new ItemStack(Material.PUFFERFISH),
        new ItemStack(Material.BONE), new ItemStack(Material.INK_SAC), new ItemStack(Material.STRING)
    };

    public OfflineProgressionManager(FuryMinions plugin) {
        this.plugin = plugin;
    }

    /**
     * Calculates and applies offline progression for a minion based on when it was last saved.
     * Skips minions that are already full or were offline for less than one minute
     * (too short to produce meaningful output).
     * <p>
     * The action count is deliberately throttled per behaviour type to reflect real-world
     * growth cycles (e.g. farmers require plant → wait → harvest, not just one action per tick).
     *
     * @param minion    the minion to process
     * @param updatedAt epoch timestamp (ms) of the minion's last save
     */
    public void processOfflineTime(Minion minion, long updatedAt) {
        if (updatedAt <= 0 || minion.isFull()) return;

        long offlineTimeMs = System.currentTimeMillis() - updatedAt;
        if (offlineTimeMs < 60_000) {
            return;
        }

        long intervalMs = minion.getType().getActionInterval(minion.getLevel()) * 50L;
        if (intervalMs <= 0) return;

        long totalActions = offlineTimeMs / intervalMs;
        if (totalActions <= 0) return;

        MinionBehavior behavior = minion.getType().getEffectiveBehavior();
        long successfulActions = 0;

        // Action efficiency is halved or quartered per behaviour to model real growth cycles.
        // MINER/TREE_FARMER: place + break = 2 ticks per yield → 50% efficiency.
        // Crop/plant farmers: plant → wait (random tick) → harvest = ~4 ticks per yield → 25% efficiency.
        switch (behavior) {
            case MINER:
            case TREE_FARMER:
                successfulActions = totalActions / 2;
                break;
            case CROP_FARMER:
            case CANE_FARMER:
            case MELON_FARMER:
            case NETHER_HERB:
            case CACTUS_FARMER:
                successfulActions = totalActions / 4;
                break;
            case ANIMAL_FARMER:
            case FISHER:
                successfulActions = totalActions;
                break;
        }

        if (successfulActions <= 0) return;

        ThreadLocalRandom rng = ThreadLocalRandom.current();

        long actionsProcessed = 0;
        while (actionsProcessed < successfulActions) {
            if (minion.isFull()) {
                break;
            }

            ItemStack drop = getSimulatedDrop(minion, rng);
            if (drop != null && drop.getType() != Material.AIR) {
                boolean addedAny = minion.addToInventory(drop);
                if (!addedAny || drop.getAmount() > 0) {
                    minion.setFull(true);
                }
            }
            actionsProcessed++;
        }
    }

    /**
     * Produces a single simulated drop appropriate for the minion's behaviour type.
     *
     * @param minion the minion performing the action
     * @param rng    thread-local RNG instance for performance
     * @return the simulated drop, or {@code null} if no drop applies
     */
    private ItemStack getSimulatedDrop(Minion minion, ThreadLocalRandom rng) {
        MinionBehavior behavior = minion.getType().getEffectiveBehavior();

        switch (behavior) {
            case ANIMAL_FARMER:
                List<Material> dropItems = minion.getType().getDropItems();
                if (!dropItems.isEmpty()) {
                    Material mat = dropItems.get(rng.nextInt(dropItems.size()));
                    return new ItemStack(mat, rng.nextInt(1, 4));
                }
                return null;

            case FISHER:
                return FISH_DROPS[rng.nextInt(FISH_DROPS.length)].clone();

            case MELON_FARMER:
                Material stemType = minion.getType().getGeneratedBlock();
                if (stemType == Material.MELON_STEM) {
                    return new ItemStack(Material.MELON_SLICE, rng.nextInt(3, 8));
                } else {
                    return new ItemStack(Material.PUMPKIN, 1);
                }

            case CANE_FARMER:
                return new ItemStack(Material.SUGAR_CANE, 1);

            case CACTUS_FARMER:
                return new ItemStack(Material.CACTUS, 1);

            case NETHER_HERB:
                return new ItemStack(Material.NETHER_WART, rng.nextInt(2, 5));

            case TREE_FARMER:
                return new ItemStack(minion.getSelectedLogType(), 1);

            case MINER:
            case CROP_FARMER:
            default:
                Material target = minion.getType().getGeneratedBlock();
                if (target == null) return null;
                return getDroppedItem(target);
        }
    }

    /**
     * Maps a block material to the item it drops (mirrors vanilla drop logic for mineable blocks).
     * Ore blocks yield smelted/processed resources; crop blocks yield their harvest items.
     * Stem blocks (melon/pumpkin) yield nothing – the fruit is handled separately.
     *
     * @param blockType the block material to resolve
     * @return the corresponding drop {@link ItemStack}
     */
    private ItemStack getDroppedItem(Material blockType) {
        return switch (blockType) {
            case DIAMOND_ORE         -> new ItemStack(Material.DIAMOND);
            case IRON_ORE            -> new ItemStack(Material.IRON_INGOT);
            case GOLD_ORE            -> new ItemStack(Material.GOLD_INGOT);
            case COAL_ORE            -> new ItemStack(Material.COAL);
            case EMERALD_ORE         -> new ItemStack(Material.EMERALD);
            case REDSTONE_ORE        -> new ItemStack(Material.REDSTONE);
            case LAPIS_ORE           -> new ItemStack(Material.LAPIS_LAZULI);
            case POTATOES            -> new ItemStack(Material.POTATO);
            case CARROTS             -> new ItemStack(Material.CARROT);
            case BEETROOTS           -> new ItemStack(Material.BEETROOT);
            case MELON_STEM, ATTACHED_MELON_STEM, PUMPKIN_STEM, ATTACHED_PUMPKIN_STEM -> new ItemStack(Material.AIR);
            default                  -> new ItemStack(blockType);
        };
    }
}
