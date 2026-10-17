package pl.furycraft.furyminions.model;

import lombok.Getter;
import lombok.Setter;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Runtime representation of an active minion.
 * <p>
 * Holds all mutable state needed by the scheduler, GUI, and persistence layer.
 * Inventory serialisation uses Bukkit's built-in {@link BukkitObjectOutputStream} with
 * Base64 encoding so that the content can be stored as a plain text column in MariaDB.
 */
@Getter
public class Minion {

    private final UUID minionUuid;
    private final UUID ownerUuid;
    private final MinionType type;
    @Setter
    private int level;
    private final Location location;
    @Setter
    private String facingDirection;
    private final List<ItemStack> inventory;
    private ArmorStand armorStand;
    private long lastActionTime;
    @Setter
    private boolean isFull;
    @Setter
    private boolean isPaused;
    @Setter
    private String extraData;

    public Minion(UUID minionUuid, UUID ownerUuid, MinionType type, int level,
                  Location location, String facingDirection) {
        this.minionUuid = minionUuid;
        this.ownerUuid = ownerUuid;
        this.type = type;
        this.level = level;
        this.location = location;
        this.facingDirection = facingDirection;
        this.inventory = new ArrayList<>();
        this.lastActionTime = System.currentTimeMillis();
        this.isFull = false;
        this.isPaused = false;
        this.extraData = "";
    }

    /**
     * Returns {@code true} if the minion's cooldown has elapsed and it is neither paused nor full.
     * The cooldown is derived from the type's {@code actionIntervalTicks} scaled to milliseconds.
     */
    public boolean canPerformAction() {
        if (isPaused || isFull) {
            return false;
        }

        long intervalMs = type.getActionInterval(level) * 50L;
        return System.currentTimeMillis() - lastActionTime >= intervalMs;
    }

    /**
     * Resets the action cooldown timer to the current time. Called after each completed action.
     */
    public void markActionPerformed() {
        this.lastActionTime = System.currentTimeMillis();
    }

    /**
     * Determines whether this minion should maintain a player-configured static facing direction.
     * <p>
     * MINER, TREE_FARMER, and FISHER rotate dynamically towards their target block/tile each tick,
     * so a fixed direction is meaningless for them and is always ignored.
     *
     * @return {@code true} if the minion should hold its facing direction set in the GUI
     */
    public boolean hasFixedFacing() {
        MinionBehavior behavior = type.getEffectiveBehavior();
        if (behavior == MinionBehavior.MINER || behavior == MinionBehavior.TREE_FARMER
                || behavior == MinionBehavior.FISHER) {
            return false;
        }
        return facingDirection != null && !facingDirection.isEmpty() && !facingDirection.equals("NONE");
    }

    /**
     * Returns the wood log type selected for this minion (only relevant for TREE_FARMER).
     * Falls back to {@link org.bukkit.Material#OAK_LOG} if {@code extraData} is absent or invalid.
     */
    public org.bukkit.Material getSelectedLogType() {
        if (extraData == null || extraData.isEmpty()) {
            return org.bukkit.Material.OAK_LOG;
        }
        try {
            return org.bukkit.Material.valueOf(extraData);
        } catch (IllegalArgumentException e) {
            return org.bukkit.Material.OAK_LOG;
        }
    }

    /**
     * Attempts to add the given item to the minion's inventory.
     * Items are first stacked onto existing compatible stacks before occupying a new slot.
     * Sets {@link #isFull} automatically when capacity is exhausted.
     *
     * @param item the item to add (amount may be partially consumed)
     * @return {@code true} if at least a portion of the item was added
     */
    public boolean addToInventory(ItemStack item) {
        if (isFull) {
            return false;
        }

        int maxSlots = getInventorySlots();
        boolean anyAdded = false;

        for (ItemStack existing : inventory) {
            if (existing.isSimilar(item) && existing.getAmount() < item.getMaxStackSize()) {
                int spaceLeft = item.getMaxStackSize() - existing.getAmount();
                int toAdd = Math.min(spaceLeft, item.getAmount());
                existing.setAmount(existing.getAmount() + toAdd);
                item.setAmount(item.getAmount() - toAdd);
                anyAdded = true;

                if (item.getAmount() <= 0) {
                    if (isInventoryFull()) isFull = true;
                    return true;
                }
            }
        }

        if (item.getAmount() > 0 && inventory.size() < maxSlots) {
            inventory.add(item.clone());
            item.setAmount(0);
            anyAdded = true;
        }

        if (isInventoryFull()) {
            isFull = true;
        }

        return anyAdded;
    }

    /**
     * Removes and returns all items from the inventory, resetting the full flag.
     *
     * @return a snapshot of the items that were collected
     */
    public List<ItemStack> collectInventory() {
        List<ItemStack> collected = new ArrayList<>(inventory);
        inventory.clear();
        isFull = false;
        return collected;
    }

    /**
     * Returns the number of inventory slots available at the current level.
     * Formula: {@code 1 + level} (1 slot at level 1, +1 per level).
     */
    public int getInventorySlots() {
        return 1 + level;
    }

    /**
     * Returns {@code true} if all slots are occupied and every existing stack is at max size.
     */
    public boolean isInventoryFull() {
        int maxSlots = getInventorySlots();

        if (inventory.size() > maxSlots) {
            return true;
        }

        if (inventory.size() == maxSlots) {
            for (ItemStack item : inventory) {
                if (item.getAmount() < item.getMaxStackSize()) {
                    return false;
                }
            }
            return true;
        }

        return false;
    }

    /**
     * Assigns an ArmorStand entity to this minion.
     *
     * @param armorStand the spawned ArmorStand
     */
    public void setArmorStand(ArmorStand armorStand) {
        this.armorStand = armorStand;
    }

    /**
     * Despawns the associated ArmorStand entity and clears the reference.
     */
    public void removeArmorStand() {
        if (armorStand != null && armorStand.isValid()) {
            armorStand.remove();
        }
        this.armorStand = null;
    }

    /**
     * Converts this runtime minion into a {@link MinionData} DTO suitable for database persistence.
     *
     * @return a fully populated {@link MinionData} builder result
     */
    public MinionData toMinionData() {
        return MinionData.builder()
            .minionUuid(minionUuid)
            .ownerUuid(ownerUuid)
            .type(type.getId())
            .level(level)
            .world(location.getWorld().getName())
            .x(location.getX())
            .y(location.getY())
            .z(location.getZ())
            .pitch(location.getPitch())
            .yaw(location.getYaw())
            .facingDirection(facingDirection)
            .inventoryContent(serializeInventory())
            .extraData(extraData)
            .build();
    }

    /**
     * Serialises the inventory to a Base64-encoded binary string for database storage.
     * Returns the literal {@code "[]"} if the inventory is empty to keep the column non-null.
     */
    private String serializeInventory() {
        if (inventory.isEmpty()) {
            return "[]";
        }
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream);

            dataOutput.writeInt(inventory.size());
            for (ItemStack item : inventory) {
                dataOutput.writeObject(item);
            }
            dataOutput.close();

            return Base64.getEncoder().encodeToString(outputStream.toByteArray());
        } catch (Exception e) {
            e.printStackTrace();
            return "[]";
        }
    }

    /**
     * Deserialises a Base64-encoded inventory string produced by {@link #serializeInventory()}
     * and restores the contents into this minion's inventory. Also updates the {@link #isFull} flag.
     *
     * @param serialized the Base64 string, or {@code "[]"} / empty for an empty inventory
     */
    public void deserializeInventory(String serialized) {
        inventory.clear();
        if (serialized == null || serialized.equals("[]") || serialized.isEmpty()) {
            return;
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(serialized);
            ByteArrayInputStream inputStream = new ByteArrayInputStream(bytes);
            BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream);

            int size = dataInput.readInt();
            for (int i = 0; i < size; i++) {
                ItemStack item = (ItemStack) dataInput.readObject();
                if (item != null) {
                    inventory.add(item);
                }
            }
            dataInput.close();

            isFull = isInventoryFull();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
