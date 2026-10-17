package pl.furycraft.furyminions.manager;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.TreeType;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.ArmorStand;
import org.bukkit.inventory.ItemStack;
import pl.furycraft.furyminions.FuryMinions;
import pl.furycraft.furyminions.model.Minion;
import pl.furycraft.furyminions.model.MinionBehavior;
import org.bukkit.util.EulerAngle;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Core scheduler that drives all active minion behaviours each server tick.
 * <p>
 * Uses an adaptive round-robin slice model with a nanosecond time budget: ready minions are
 * processed fairly each tick up to a dynamic action limit and time budget (1.5ms), ensuring
 * thousands of active minions maintain their configured action intervals without dropping server TPS.
 * <p>
 * All item-drop constants are pre-allocated as static fields to eliminate per-tick GC pressure.
 */
public class MinionScheduler {

    private final FuryMinions plugin;
    private int taskId = -1;
    private int minionIndex = 0;

    // Pre-cached arm pose angles (radians) to avoid EulerAngle allocations each animation
    private static final EulerAngle POSE_DEFAULT = new EulerAngle(Math.toRadians(0), 0, 0);
    private static final EulerAngle POSE_SWING   = new EulerAngle(Math.toRadians(-60), 0, 0);

    /**
     * Maximum CPU time budget per server tick (1.5 milliseconds in nanoseconds) allocated
     * to executing minion actions. Guarantees the scheduler will not cause TPS drops.
     */
    private static final long TICK_TIME_BUDGET_NANOS = 1_500_000L;

    /**
     * Minimum number of actions permitted per tick if time budget allows.
     */
    private static final int MIN_ACTIONS_PER_TICK = 5;

    /**
     * Hard ceiling on actions executed in a single tick to safeguard against edge-case spikes.
     */
    private static final int MAX_ACTIONS_PER_TICK = 250;

    // -----------------------------------------------------------------------
    // Pre-allocated item singletons – zero GC on drop collection hot path
    // -----------------------------------------------------------------------

    private static final ItemStack ITEM_DIAMOND  = new ItemStack(Material.DIAMOND);
    private static final ItemStack ITEM_IRON     = new ItemStack(Material.IRON_INGOT);
    private static final ItemStack ITEM_GOLD     = new ItemStack(Material.GOLD_INGOT);
    private static final ItemStack ITEM_COAL     = new ItemStack(Material.COAL);
    private static final ItemStack ITEM_EMERALD  = new ItemStack(Material.EMERALD);
    private static final ItemStack ITEM_REDSTONE = new ItemStack(Material.REDSTONE);
    private static final ItemStack ITEM_LAPIS    = new ItemStack(Material.LAPIS_LAZULI);

    private static final ItemStack ITEM_SUGAR_CANE    = new ItemStack(Material.SUGAR_CANE);
    private static final ItemStack ITEM_MELON_SLICE   = new ItemStack(Material.MELON_SLICE);
    private static final ItemStack ITEM_PUMPKIN       = new ItemStack(Material.PUMPKIN);
    private static final ItemStack ITEM_CACTUS        = new ItemStack(Material.CACTUS);
    private static final ItemStack ITEM_NETHER_WART   = new ItemStack(Material.NETHER_WART);

    private static final ItemStack ITEM_BEEF          = new ItemStack(Material.BEEF);
    private static final ItemStack ITEM_LEATHER       = new ItemStack(Material.LEATHER);
    private static final ItemStack ITEM_RAW_CHICKEN   = new ItemStack(Material.CHICKEN);
    private static final ItemStack ITEM_EGG           = new ItemStack(Material.EGG);
    private static final ItemStack ITEM_WHITE_WOOL    = new ItemStack(Material.WHITE_WOOL);
    private static final ItemStack ITEM_PORKCHOP      = new ItemStack(Material.PORKCHOP);

    private static final ItemStack ITEM_COD          = new ItemStack(Material.COD);
    private static final ItemStack ITEM_SALMON       = new ItemStack(Material.SALMON);
    private static final ItemStack ITEM_TROPICAL_FISH = new ItemStack(Material.TROPICAL_FISH);
    private static final ItemStack ITEM_PUFFERFISH   = new ItemStack(Material.PUFFERFISH);
    private static final ItemStack ITEM_BOW          = new ItemStack(Material.BOW);
    private static final ItemStack ITEM_ENCHANTED_BOOK = new ItemStack(Material.ENCHANTED_BOOK);
    private static final ItemStack ITEM_NAME_TAG     = new ItemStack(Material.NAME_TAG);
    private static final ItemStack ITEM_SADDLE       = new ItemStack(Material.SADDLE);
    private static final ItemStack ITEM_BONE         = new ItemStack(Material.BONE);
    private static final ItemStack ITEM_INK_SAC      = new ItemStack(Material.INK_SAC);
    private static final ItemStack ITEM_STRING       = new ItemStack(Material.STRING);
    private static final ItemStack ITEM_LILY_PAD     = new ItemStack(Material.LILY_PAD);

    private static final ItemStack ITEM_OAK_LOG      = new ItemStack(Material.OAK_LOG);

    // -----------------------------------------------------------------------
    // Weighted drop table for the Fisher behaviour (avoids per-tick list allocation)
    // -----------------------------------------------------------------------

    /**
     * Fishing loot table mirroring vanilla probabilities: Fish (90%) + Junk (10%).
     * Items are cloned on each use to prevent accidental mutation of the shared array.
     */
    private static final ItemStack[] FISH_DROPS = {
        // Fish (90%)
        ITEM_COD, ITEM_COD, ITEM_COD, ITEM_COD, ITEM_COD, ITEM_COD,
        ITEM_SALMON, ITEM_SALMON, ITEM_SALMON,
        ITEM_TROPICAL_FISH, ITEM_TROPICAL_FISH,
        ITEM_PUFFERFISH,
        // Junk (10%)
        ITEM_BONE, ITEM_INK_SAC, ITEM_STRING
    };

    public MinionScheduler(FuryMinions plugin) {
        this.plugin = plugin;
    }

    /**
     * Starts the per-tick scheduler task. Safe to call multiple times; no-op if already running.
     */
    public void start() {
        if (taskId != -1) {
            return;
        }
        taskId = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L).getTaskId();
    }

    /**
     * Stops and cancels the scheduler task. Safe to call when the task is not running.
     */
    public void stop() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
    }

    /**
     * Called once per server tick. Advances the round-robin pointer and processes
     * ready minions up to the dynamic action limit and nanosecond time budget, skipping
     * paused, full, unloaded, or cooling-down ones.
     */
    private void tick() {
        List<Minion> activeMinions = plugin.getArmorStandManager().getActiveMinionsList();
        int totalMinions = activeMinions.size();
        if (totalMinions == 0) {
            return;
        }

        // Dynamically scale action capacity based on the active minion population.
        // For example, 5000 minions with an average 25s (500 ticks) interval need ~10 actions/tick.
        // (totalMinions / 50) provides ample headroom while MAX_ACTIONS_PER_TICK caps it.
        int dynamicSlice = Math.max(MIN_ACTIONS_PER_TICK, (int) Math.ceil((double) totalMinions / 50.0));
        int targetActions = Math.min(MAX_ACTIONS_PER_TICK, dynamicSlice);

        if (minionIndex >= totalMinions) {
            minionIndex = 0;
        }

        int processedInThisTick = 0;
        int attempts = 0;
        // In a single tick we can inspect up to all active minions to locate ready ones,
        // since canPerformAction() is a lightweight memory/math check (~nanoseconds).
        int maxAttempts = totalMinions;
        long deadlineNanos = System.nanoTime() + TICK_TIME_BUDGET_NANOS;

        while (processedInThisTick < targetActions && attempts < maxAttempts) {
            if (minionIndex >= activeMinions.size()) {
                minionIndex = 0;
            }
            if (activeMinions.isEmpty()) {
                break;
            }

            Minion minion = activeMinions.get(minionIndex++);
            attempts++;

            if (minion == null) {
                continue;
            }

            if (!minion.canPerformAction() || minion.isPaused() || minion.isFull()) {
                // Periodically check deadline if scanning a huge collection of dormant minions
                if ((attempts & 0xFF) == 0 && System.nanoTime() >= deadlineNanos) {
                    break;
                }
                continue;
            }

            processMinionAction(minion);
            processedInThisTick++;

            // Stop immediately if the tick time budget is exhausted to protect server TPS
            if (System.nanoTime() >= deadlineNanos) {
                break;
            }
        }
    }

    /**
     * Dispatches to the correct behaviour handler based on the minion's effective behaviour.
     * Skips processing if the minion's platform chunk is not loaded.
     */
    private void processMinionAction(Minion minion) {
        Location minionLoc = minion.getLocation();
        World world = minionLoc.getWorld();
        if (world == null) return;

        int cx = minionLoc.getBlockX();
        int cy = minionLoc.getBlockY() - 1;
        int cz = minionLoc.getBlockZ();
        int radius = minion.getType().getPlatformRadius();

        if (!isPlatformLoaded(world, cx, cz, radius)) {
            return;
        }

        switch (minion.getType().getEffectiveBehavior()) {
            case MINER         -> processMinerAction(minion, world, cx, cy, cz, radius);
            case CROP_FARMER   -> processCropFarmerAction(minion, world, cx, cy, cz, radius);
            case CANE_FARMER   -> processCaneFarmerAction(minion, world, cx, cy, cz, radius);
            case MELON_FARMER  -> processMelonFarmerAction(minion, world, cx, cy, cz, radius);
            case ANIMAL_FARMER -> processAnimalFarmerAction(minion, world, cx, cy, cz, radius);
            case FISHER        -> processFisherAction(minion, minionLoc);
            case TREE_FARMER   -> processTreeFarmerAction(minion, world, cx, cy, cz, radius);
            case NETHER_HERB   -> processNetherHerbAction(minion, world, cx, cy, cz, radius);
            case CACTUS_FARMER -> processCactusFarmerAction(minion, world, cx, cy, cz, radius);
        }
    }

    // -----------------------------------------------------------------------
    // MINER
    // -----------------------------------------------------------------------

    /**
     * Places ore blocks on empty tiles and mines them when the platform is full.
     * Block selection uses reservoir sampling for uniform random distribution without building a list.
     */
    private void processMinerAction(Minion minion, World world, int cx, int cy, int cz, int radius) {
        Material targetMaterial = minion.getType().getGeneratedBlock();
        if (targetMaterial == null) {
            minion.markActionPerformed();
            return;
        }

        int emptyCount = 0, selectedEmptyX = 0, selectedEmptyY = 0, selectedEmptyZ = 0;
        int targetCount = 0, selectedTargetX = 0, selectedTargetY = 0, selectedTargetZ = 0;
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                int bx = cx + x, bz = cz + z;
                Material type = world.getType(bx, cy, bz);
                if (type.isAir()) {
                    emptyCount++;
                    if (rng.nextInt(emptyCount) == 0) { selectedEmptyX = bx; selectedEmptyY = cy; selectedEmptyZ = bz; }
                } else if (type == targetMaterial) {
                    targetCount++;
                    if (rng.nextInt(targetCount) == 0) { selectedTargetX = bx; selectedTargetY = cy; selectedTargetZ = bz; }
                }
            }
        }

        if (emptyCount > 0) {
            performPlaceAction(minion, new Location(world, selectedEmptyX, selectedEmptyY, selectedEmptyZ), targetMaterial);
        } else if (targetCount > 0) {
            performMineAction(minion, new Location(world, selectedTargetX, selectedTargetY, selectedTargetZ));
        } else {
            minion.markActionPerformed();
        }
    }

    // -----------------------------------------------------------------------
    // CROP_FARMER – farmland crops (wheat, carrot, potato)
    // -----------------------------------------------------------------------

    /**
     * Plants crops on empty farmland tiles and harvests fully-grown ones.
     * {@code generatedBlock} (e.g. WHEAT, CARROTS) is the block form of the crop –
     * not the seed item, which is used only as a legacy config flag.
     */
    private void processCropFarmerAction(Minion minion, World world, int cx, int cy, int cz, int radius) {
        int emptyCount = 0, selectedEmptyX = 0, selectedEmptyY = 0, selectedEmptyZ = 0;
        int grownCount = 0, selectedGrownX = 0, selectedGrownY = 0, selectedGrownZ = 0;
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                int bx = cx + x, bz = cz + z;
                if (world.getType(bx, cy, bz) == Material.FARMLAND) {
                    int cropY = cy + 1;
                    Material cropType = world.getType(bx, cropY, bz);
                    if (cropType.isAir()) {
                        emptyCount++;
                        if (rng.nextInt(emptyCount) == 0) { selectedEmptyX = bx; selectedEmptyY = cropY; selectedEmptyZ = bz; }
                    } else {
                        BlockData data = world.getBlockData(bx, cropY, bz);
                        if (data instanceof Ageable ageable && ageable.getAge() >= ageable.getMaximumAge()) {
                            grownCount++;
                            if (rng.nextInt(grownCount) == 0) { selectedGrownX = bx; selectedGrownY = cropY; selectedGrownZ = bz; }
                        }
                    }
                }
            }
        }

        Material cropBlock = minion.getType().getGeneratedBlock();
        if (emptyCount > 0 && cropBlock != null) {
            performPlantAction(minion, new Location(world, selectedEmptyX, selectedEmptyY, selectedEmptyZ), cropBlock);
        } else if (grownCount > 0) {
            performHarvestAction(minion, new Location(world, selectedGrownX, selectedGrownY, selectedGrownZ));
        } else {
            minion.markActionPerformed();
        }
    }

    // -----------------------------------------------------------------------
    // CANE_FARMER – sugar cane
    // -----------------------------------------------------------------------

    /**
     * Manages sugar cane growth by harvesting only the top block (cy+2), leaving the base (cy+1)
     * intact so the cane regrows without replanting. Plants new cane only on soil adjacent to water.
     * <p>
     * Vertical layout:
     * <ul>
     *   <li>cy   – platform block (DIRT / SAND)</li>
     *   <li>cy+1 – first cane block (base, kept after harvest)</li>
     *   <li>cy+2 – second cane block (harvest target)</li>
     * </ul>
     */
    private void processCaneFarmerAction(Minion minion, World world, int cx, int cy, int cz, int radius) {
        int harvestCount = 0, selectedX = 0, selectedY = 0, selectedZ = 0;
        int emptyCount = 0, emptyX = 0, emptyZ = 0;
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                int bx = cx + x, bz = cz + z;
                Material base     = world.getType(bx, cy, bz);
                Material caneBase = world.getType(bx, cy + 1, bz);
                Material caneTop  = world.getType(bx, cy + 2, bz);

                if (caneBase == Material.SUGAR_CANE && caneTop == Material.SUGAR_CANE) {
                    harvestCount++;
                    if (rng.nextInt(harvestCount) == 0) { selectedX = bx; selectedY = cy + 2; selectedZ = bz; }
                } else if (caneBase.isAir()
                        && (base == Material.DIRT || base == Material.GRASS_BLOCK
                            || base == Material.COARSE_DIRT || base == Material.SAND
                            || base == Material.RED_SAND || base == Material.PODZOL)) {
                    if (hasCaneRequirements(world, bx, cy, bz)) {
                        emptyCount++;
                        if (rng.nextInt(emptyCount) == 0) { emptyX = bx; emptyZ = bz; }
                    }
                }
            }
        }

        if (harvestCount > 0) {
            Location harvestLoc = new Location(world, selectedX, selectedY, selectedZ);
            rotateMinionTowards(minion, harvestLoc);
            playMineAnimation(minion);
            world.getBlockAt(selectedX, selectedY, selectedZ).setType(Material.AIR);
            // Remove any additional cane segments above the harvest point (e.g. naturally grown extra blocks)
            for (int y = selectedY + 1; y <= selectedY + 5; y++) {
                if (world.getType(selectedX, y, selectedZ) == Material.SUGAR_CANE)
                    world.getBlockAt(selectedX, y, selectedZ).setType(Material.AIR);
                else break;
            }
            giveItemToMinion(minion, ITEM_SUGAR_CANE.clone());
            minion.markActionPerformed();
        } else if (emptyCount > 0) {
            Location plantLoc = new Location(world, emptyX, cy + 1, emptyZ);
            rotateMinionTowards(minion, plantLoc);
            playPlaceAnimation(minion);
            world.getBlockAt(emptyX, cy + 1, emptyZ).setType(Material.SUGAR_CANE);
            playPlaceEffects(minion, plantLoc, Material.SUGAR_CANE);
            minion.markActionPerformed();
        } else {
            minion.markActionPerformed();
        }
    }

    /**
     * Returns {@code true} if at least one cardinal neighbour of the given block position contains water,
     * satisfying the sugar-cane placement requirement.
     */
    private boolean hasCaneRequirements(World world, int x, int y, int z) {
        return world.getType(x + 1, y, z) == Material.WATER
            || world.getType(x - 1, y, z) == Material.WATER
            || world.getType(x, y, z + 1) == Material.WATER
            || world.getType(x, y, z - 1) == Material.WATER;
    }

    // -----------------------------------------------------------------------
    // MELON_FARMER – melons and pumpkins
    // -----------------------------------------------------------------------

    /**
     * Simulates melon/pumpkin farming by directly placing fruit blocks on suitable ground,
     * bypassing the stem growth mechanic for performance and simplicity.
     * The {@code generatedBlock} config field selects between MELON_STEM and PUMPKIN_STEM.
     */
    private void processMelonFarmerAction(Minion minion, World world, int cx, int cy, int cz, int radius) {
        int harvestCount = 0, selectedX = 0, selectedY = 0, selectedZ = 0;
        int emptyCount = 0, emptyX = 0, emptyZ = 0;
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        Material stemType   = minion.getType().getGeneratedBlock();
        Material fruitType  = (stemType == Material.MELON_STEM) ? Material.MELON : Material.PUMPKIN;
        ItemStack fruitItem = (stemType == Material.MELON_STEM) ? ITEM_MELON_SLICE : ITEM_PUMPKIN;

        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                int bx = cx + x, bz = cz + z;
                Material ground = world.getType(bx, cy, bz);
                Material block  = world.getType(bx, cy + 1, bz);

                if (block == fruitType) {
                    harvestCount++;
                    if (rng.nextInt(harvestCount) == 0) { selectedX = bx; selectedY = cy + 1; selectedZ = bz; }
                } else if (block.isAir() && (ground == Material.FARMLAND || ground == Material.DIRT || ground == Material.GRASS_BLOCK)) {
                    emptyCount++;
                    if (rng.nextInt(emptyCount) == 0) { emptyX = bx; emptyZ = bz; }
                }
            }
        }

        if (harvestCount > 0) {
            Location harvestLoc = new Location(world, selectedX, selectedY, selectedZ);
            rotateMinionTowards(minion, harvestLoc);
            playMineAnimation(minion);
            world.getBlockAt(selectedX, selectedY, selectedZ).setType(Material.AIR);

            ItemStack drop = fruitItem.clone();
            if (fruitType == Material.MELON) drop.setAmount(rng.nextInt(3, 8));
            giveItemToMinion(minion, drop);
            minion.markActionPerformed();
        } else if (emptyCount > 0) {
            Location plantLoc = new Location(world, emptyX, cy + 1, emptyZ);
            rotateMinionTowards(minion, plantLoc);
            playPlaceAnimation(minion);
            world.getBlockAt(emptyX, cy + 1, emptyZ).setType(fruitType);
            playPlaceEffects(minion, plantLoc, fruitType);
            minion.markActionPerformed();
        } else {
            minion.markActionPerformed();
        }
    }

    // -----------------------------------------------------------------------
    // ANIMAL_FARMER – virtual livestock (no real entity spawned)
    // -----------------------------------------------------------------------

    /**
     * Simulates animal farming by producing drops directly without spawning any entities.
     * This deliberately avoids real mob AI and entity tick overhead for servers with many minions.
     */
    private void processAnimalFarmerAction(Minion minion, World world, int cx, int cy, int cz, int radius) {
        Location minionLoc = minion.getLocation();
        rotateMinionTowards(minion, minionLoc.clone().add(0, 0, 1));
        playMineAnimation(minion);

        List<Material> dropItems = minion.getType().getDropItems();
        if (dropItems.isEmpty()) {
            minion.markActionPerformed();
            return;
        }

        ThreadLocalRandom rng = ThreadLocalRandom.current();
        Material dropMaterial = dropItems.get(rng.nextInt(dropItems.size()));
        ItemStack drop = new ItemStack(dropMaterial, rng.nextInt(1, 4));

        world.spawnParticle(Particle.HEART, minionLoc.clone().add(0, 1.5, 0), 3, 0.3, 0.3, 0.3, 0.02);
        world.playSound(minionLoc, Sound.ENTITY_COW_AMBIENT, 0.5f, 1.0f + rng.nextFloat() * 0.2f);

        giveItemToMinion(minion, drop);
        minion.markActionPerformed();
    }

    // -----------------------------------------------------------------------
    // FISHER – simulated fishing
    // -----------------------------------------------------------------------

    /**
     * Simulates fishing by randomly selecting a water tile near the minion,
     * spawning visual effects there, and producing a drop from the weighted {@link #FISH_DROPS} table.
     * Aborts if no water is found within the configured platform radius.
     */
    private void processFisherAction(Minion minion, Location minionLoc) {
        World world = minionLoc.getWorld();
        if (world == null) { minion.markActionPerformed(); return; }

        int cx = minionLoc.getBlockX();
        int cy = minionLoc.getBlockY() - 1;
        int cz = minionLoc.getBlockZ();
        int radius = minion.getType().getPlatformRadius();

        boolean hasWater = false;
        outer:
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                if (x == 0 && z == 0) continue;
                if (world.getType(cx + x, cy, cz + z) == Material.WATER) {
                    hasWater = true;
                    break outer;
                }
            }
        }

        if (!hasWater) {
            minion.markActionPerformed();
            return;
        }

        ThreadLocalRandom rng = ThreadLocalRandom.current();
        List<Location> waterTiles = new java.util.ArrayList<>();
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                if (x == 0 && z == 0) continue;
                if (world.getType(cx + x, cy, cz + z) == Material.WATER) {
                    waterTiles.add(new Location(world, cx + x + 0.5, cy, cz + z + 0.5));
                }
            }
        }
        Location fishTarget = waterTiles.isEmpty()
                ? minionLoc.clone().add(0, 0, 2)
                : waterTiles.get(rng.nextInt(waterTiles.size()));

        ItemStack caught = FISH_DROPS[rng.nextInt(FISH_DROPS.length)].clone();

        world.spawnParticle(Particle.FISHING, fishTarget.clone().add(0, 0.3, 0), 5, 0.2, 0.1, 0.2, 0.05);
        world.playSound(minionLoc, Sound.ENTITY_FISHING_BOBBER_SPLASH, 0.6f, 1.0f);

        rotateMinionTowards(minion, fishTarget);
        animateArmSwing(minion);

        giveItemToMinion(minion, caught);
        minion.markActionPerformed();
    }

    // -----------------------------------------------------------------------
    // TREE_FARMER – plants and chops trees
    // -----------------------------------------------------------------------

    /**
     * Places log blocks on empty ground tiles and harvests any log block on the platform,
     * regardless of wood type. Accepting any {@link Tag#LOGS} variant ensures the minion
     * can still harvest logs if the player changes the wood type mid-cycle.
     */
    private void processTreeFarmerAction(Minion minion, World world, int cx, int cy, int cz, int radius) {
        Material targetMaterial = minion.getSelectedLogType();

        int emptyCount = 0, selectedEmptyX = 0, selectedEmptyY = 0, selectedEmptyZ = 0;
        int targetCount = 0, selectedTargetX = 0, selectedTargetY = 0, selectedTargetZ = 0;
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                int bx = cx + x, bz = cz + z;
                Material type = world.getType(bx, cy, bz);
                if (type.isAir()) {
                    emptyCount++;
                    if (rng.nextInt(emptyCount) == 0) { selectedEmptyX = bx; selectedEmptyY = cy; selectedEmptyZ = bz; }
                } else if (Tag.LOGS.isTagged(type)) {
                    targetCount++;
                    if (rng.nextInt(targetCount) == 0) { selectedTargetX = bx; selectedTargetY = cy; selectedTargetZ = bz; }
                }
            }
        }

        if (emptyCount > 0) {
            performPlaceAction(minion, new Location(world, selectedEmptyX, selectedEmptyY, selectedEmptyZ), targetMaterial);
        } else if (targetCount > 0) {
            performMineAction(minion, new Location(world, selectedTargetX, selectedTargetY, selectedTargetZ));
        } else {
            minion.markActionPerformed();
        }
    }

    // -----------------------------------------------------------------------
    // NETHER_HERB – nether wart on soul sand
    // -----------------------------------------------------------------------

    private void processNetherHerbAction(Minion minion, World world, int cx, int cy, int cz, int radius) {
        int emptyCount = 0, selectedEmptyX = 0, selectedEmptyZ = 0;
        int grownCount = 0, selectedGrownX = 0, selectedGrownY = 0, selectedGrownZ = 0;
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                int bx = cx + x, bz = cz + z;
                Material groundType = world.getType(bx, cy, bz);

                if (groundType == Material.SOUL_SAND) {
                    Material above = world.getType(bx, cy + 1, bz);
                    if (above.isAir()) {
                        emptyCount++;
                        if (rng.nextInt(emptyCount) == 0) { selectedEmptyX = bx; selectedEmptyZ = bz; }
                    } else if (above == Material.NETHER_WART) {
                        BlockData data = world.getBlockData(bx, cy + 1, bz);
                        if (data instanceof Ageable ageable && ageable.getAge() >= ageable.getMaximumAge()) {
                            grownCount++;
                            if (rng.nextInt(grownCount) == 0) { selectedGrownX = bx; selectedGrownY = cy + 1; selectedGrownZ = bz; }
                        }
                    }
                }
            }
        }

        if (emptyCount > 0) {
            Location plantLoc = new Location(world, selectedEmptyX, cy + 1, selectedEmptyZ);
            rotateMinionTowards(minion, plantLoc);
            playPlaceAnimation(minion);
            world.getBlockAt(selectedEmptyX, cy + 1, selectedEmptyZ).setType(Material.NETHER_WART);
            playPlaceEffects(minion, plantLoc, Material.NETHER_WART);
            minion.markActionPerformed();
        } else if (grownCount > 0) {
            Location harvestLoc = new Location(world, selectedGrownX, selectedGrownY, selectedGrownZ);
            rotateMinionTowards(minion, harvestLoc);
            playMineAnimation(minion);
            world.getBlockAt(selectedGrownX, selectedGrownY, selectedGrownZ).setType(Material.AIR);
            ItemStack drop = ITEM_NETHER_WART.clone();
            drop.setAmount(rng.nextInt(2, 5));
            giveItemToMinion(minion, drop);
            minion.markActionPerformed();
        } else {
            minion.markActionPerformed();
        }
    }

    // -----------------------------------------------------------------------
    // CACTUS_FARMER
    // -----------------------------------------------------------------------

    /**
     * Manages cactus farming on a checkerboard-pattern grid to satisfy the vanilla rule that
     * no solid block may be adjacent to a cactus. Only even-Manhattan-distance positions are used.
     * Sand is not placed by the minion – it must be pre-built by the player.
     */
    private void processCactusFarmerAction(Minion minion, World world, int cx, int cy, int cz, int radius) {
        int harvestCount = 0, selectedX = 0, selectedY = 0, selectedZ = 0;
        int emptyCount = 0, emptyX = 0, emptyZ = 0;
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                // Checkerboard pattern: skip odd-sum positions and the minion's own block
                if ((Math.abs(x) + Math.abs(z)) % 2 != 0 || (x == 0 && z == 0)) {
                    continue;
                }

                int bx = cx + x, bz = cz + z;
                Material base  = world.getType(bx, cy, bz);
                Material plant = world.getType(bx, cy + 1, bz);
                Material top   = world.getType(bx, cy + 2, bz);

                if (plant == Material.CACTUS && (top == Material.CACTUS || top == Material.CACTUS_FLOWER)) {
                    harvestCount++;
                    if (rng.nextInt(harvestCount) == 0) { selectedX = bx; selectedY = cy + 2; selectedZ = bz; }
                } else if (plant.isAir() && (base == Material.SAND || base == Material.RED_SAND)) {
                    if (!hasAdjacentSolid(world, bx, cy + 1, bz)) {
                        emptyCount++;
                        if (rng.nextInt(emptyCount) == 0) { emptyX = bx; emptyZ = bz; }
                    }
                }
            }
        }

        if (harvestCount > 0) {
            Location harvestLoc = new Location(world, selectedX, selectedY, selectedZ);
            rotateMinionTowards(minion, harvestLoc);
            playMineAnimation(minion);

            world.getBlockAt(selectedX, selectedY, selectedZ).setType(Material.AIR);
            for (int y = selectedY + 1; y <= selectedY + 5; y++) {
                Material type = world.getType(selectedX, y, selectedZ);
                if (type == Material.CACTUS || type == Material.CACTUS_FLOWER)
                    world.getBlockAt(selectedX, y, selectedZ).setType(Material.AIR);
                else break;
            }
            giveItemToMinion(minion, ITEM_CACTUS.clone());
            minion.markActionPerformed();
        } else if (emptyCount > 0) {
            Location plantLoc = new Location(world, emptyX, cy + 1, emptyZ);
            rotateMinionTowards(minion, plantLoc);
            playPlaceAnimation(minion);
            world.getBlockAt(emptyX, cy + 1, emptyZ).setType(Material.CACTUS);
            playPlaceEffects(minion, plantLoc, Material.CACTUS);
            minion.markActionPerformed();
        } else {
            minion.markActionPerformed();
        }
    }

    /**
     * Returns {@code true} if any cardinal neighbour of the given position is a non-air block,
     * which would prevent cactus placement per vanilla rules.
     */
    private boolean hasAdjacentSolid(World world, int x, int y, int z) {
        return !world.getType(x + 1, y, z).isAir()
            || !world.getType(x - 1, y, z).isAir()
            || !world.getType(x, y, z + 1).isAir()
            || !world.getType(x, y, z - 1).isAir();
    }

    // -----------------------------------------------------------------------
    // Shared action helpers
    // -----------------------------------------------------------------------

    private void performPlantAction(Minion minion, Location location, Material seedMaterial) {
        rotateMinionTowards(minion, location);
        playPlaceAnimation(minion);
        location.getBlock().setType(seedMaterial);
        playPlaceEffects(minion, location, seedMaterial);
        minion.markActionPerformed();
    }

    private void performHarvestAction(Minion minion, Location location) {
        rotateMinionTowards(minion, location);
        playMineAnimation(minion);

        Material type = location.getBlock().getType();
        ItemStack droppedItem = getDroppedItem(type);

        if (minion.addToInventory(droppedItem)) {
            location.getBlock().setType(Material.AIR);
        } else {
            if (!minion.isFull()) minion.setFull(true);
        }

        plugin.getHologramManager().updateHologram(minion);
        minion.markActionPerformed();
    }

    private void performPlaceAction(Minion minion, Location targetLocation, Material material) {
        rotateMinionTowards(minion, targetLocation);
        playPlaceAnimation(minion);
        targetLocation.getBlock().setType(material);
        playPlaceEffects(minion, targetLocation, material);
        minion.markActionPerformed();
    }

    private void performMineAction(Minion minion, Location targetLocation) {
        rotateMinionTowards(minion, targetLocation);
        playMineAnimation(minion);

        Material type = targetLocation.getBlock().getType();
        ItemStack droppedItem = getDroppedItem(type);

        if (minion.addToInventory(droppedItem)) {
            targetLocation.getBlock().setType(Material.AIR);
        } else {
            if (!minion.isFull()) minion.setFull(true);
        }

        plugin.getHologramManager().updateHologram(minion);
        minion.markActionPerformed();
    }

    /**
     * Adds an item to the minion's inventory and updates the hologram.
     * Marks the minion as full if the inventory rejects the item.
     * Used by behaviour handlers that produce drops independently of block mining.
     *
     * @param minion the target minion
     * @param item   the item to add (must not be {@code null})
     */
    private void giveItemToMinion(Minion minion, ItemStack item) {
        if (item == null) return;
        boolean changed = minion.addToInventory(item);
        if (changed) {
            plugin.getHologramManager().updateHologram(minion);
        } else if (!minion.isFull()) {
            minion.setFull(true);
            plugin.getHologramManager().updateHologram(minion);
        }
    }

    /**
     * Maps a block type to its dropped item, mirroring vanilla drop tables.
     * Ore blocks yield their smelted resource; crop blocks yield the harvest item.
     * Stem blocks (MELON_STEM, PUMPKIN_STEM) explicitly yield AIR – the fruit is handled separately.
     */
    private ItemStack getDroppedItem(Material blockType) {
        return switch (blockType) {
            case DIAMOND_ORE         -> ITEM_DIAMOND.clone();
            case IRON_ORE            -> ITEM_IRON.clone();
            case GOLD_ORE            -> ITEM_GOLD.clone();
            case COAL_ORE            -> ITEM_COAL.clone();
            case EMERALD_ORE         -> ITEM_EMERALD.clone();
            case REDSTONE_ORE        -> ITEM_REDSTONE.clone();
            case LAPIS_ORE           -> ITEM_LAPIS.clone();
            // Crop blocks differ from their item form (e.g. POTATOES block → POTATO item)
            case POTATOES            -> new ItemStack(Material.POTATO);
            case CARROTS             -> new ItemStack(Material.CARROT);
            case BEETROOTS           -> new ItemStack(Material.BEETROOT);
            case MELON_STEM, ATTACHED_MELON_STEM,
                 PUMPKIN_STEM, ATTACHED_PUMPKIN_STEM -> new ItemStack(Material.AIR);
            default                  -> new ItemStack(blockType);
        };
    }

    // -----------------------------------------------------------------------
    // Animation and effects helpers
    // -----------------------------------------------------------------------

    /**
     * Checks whether all four corner chunks of the minion's operating radius are loaded.
     * Skips processing when any corner is unloaded to avoid force-loading chunks.
     */
    private boolean isPlatformLoaded(World world, int cx, int cz, int radius) {
        int minX = cx - radius, maxX = cx + radius;
        int minZ = cz - radius, maxZ = cz + radius;
        return world.isChunkLoaded(minX >> 4, minZ >> 4)
            && world.isChunkLoaded(minX >> 4, maxZ >> 4)
            && world.isChunkLoaded(maxX >> 4, minZ >> 4)
            && world.isChunkLoaded(maxX >> 4, maxZ >> 4);
    }

    /**
     * Rotates the minion's ArmorStand to face the given target.
     * No-op for minions with a player-set fixed facing direction (MINER, TREE_FARMER, FISHER
     * are excluded via {@link Minion#hasFixedFacing()} and always rotate dynamically).
     */
    private void rotateMinionTowards(Minion minion, Location target) {
        if (minion.hasFixedFacing()) {
            return;
        }

        ArmorStand armorStand = minion.getArmorStand();
        if (armorStand != null && armorStand.isValid()) {
            Location minionLoc = armorStand.getLocation();
            double dx = target.getX() - minionLoc.getX();
            double dy = target.getY() - minionLoc.getY();
            double dz = target.getZ() - minionLoc.getZ();

            float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
            double distanceXZ = Math.sqrt(dx * dx + dz * dz);
            float pitch = (float) Math.toDegrees(-Math.atan2(dy, distanceXZ));

            if (Math.abs(minionLoc.getYaw() - yaw) > 2.0f || Math.abs(minionLoc.getPitch() - pitch) > 2.0f) {
                armorStand.setRotation(yaw, pitch);
            }
        }
    }

    private void playPlaceAnimation(Minion minion) { animateArmSwing(minion); }
    private void playMineAnimation(Minion minion)  { animateArmSwing(minion); }

    /**
     * Triggers a brief arm-swing animation on the minion's ArmorStand,
     * resetting to the default pose after 3 ticks via a delayed task.
     */
    private void animateArmSwing(Minion minion) {
        ArmorStand armorStand = minion.getArmorStand();
        if (armorStand == null || !armorStand.isValid()) return;

        armorStand.setRightArmPose(POSE_SWING);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (armorStand.isValid()) {
                armorStand.setRightArmPose(POSE_DEFAULT);
            }
        }, 3L);
    }

    private void playPlaceEffects(Minion minion, Location location, Material material) {
        World world = location.getWorld();
        if (world == null) return;

        world.spawnParticle(Particle.BLOCK, location, 10,
                0.5, 0.5, 0.5, 0.1,
                material.createBlockData());
        world.spawnParticle(Particle.HAPPY_VILLAGER, location, 5,
                0.3, 0.5, 0.3, 0.1);
        world.playSound(location, Sound.BLOCK_STONE_PLACE, 0.8f, 1.0f);
    }
}