package pl.furycraft.furyminions.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bukkit.Color;
import org.bukkit.Material;

import java.util.Collections;
import java.util.List;

/**
 * Immutable configuration descriptor for a minion type, loaded from {@code config.yml}.
 * <p>
 * Instances are created via the Lombok builder and stored in
 * {@link pl.furycraft.furyminions.config.ConfigManager}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MinionType {

    private String id;
    private String displayName;
    private String headTexture;
    private Material handItem;
    private int actionIntervalTicks;
    private int platformRadius;
    private double baseUpgradeCost;
    private double upgradeCostMultiplier;
    private double shopPrice;
    private int maxLevel;
    private Material generatedBlock;
    private String particleType;
    private String soundType;
    private String armorColor;
    private int shopSlot;

    /**
     * Legacy field kept for backward compatibility: when non-null and {@code behavior == MINER},
     * {@link #getEffectiveBehavior()} will return {@link MinionBehavior#CROP_FARMER} instead.
     */
    private Material seedItem;

    /** Explicit behaviour mode; overrides the {@code seedItem} legacy path when set. */
    @Builder.Default
    private MinionBehavior behavior = MinionBehavior.MINER;

    /**
     * Possible drop items for behaviours with randomised loot (ANIMAL_FARMER, FISHER).
     * An empty list signals that {@code generatedBlock} should be used as the sole drop source.
     */
    @Builder.Default
    private List<Material> dropItems = Collections.emptyList();

    /**
     * Animal species hint for ANIMAL_FARMER (e.g. {@code "COW"}, {@code "CHICKEN"}).
     * Informational only – actual drops are defined by {@code dropItems}.
     */
    private String animalType;

    /**
     * Calculates the cost to upgrade from {@code currentLevel} to the next level.
     *
     * @param currentLevel the minion's current level
     * @return the upgrade cost, or {@code -1} if already at {@link #maxLevel}
     */
    public double getUpgradeCost(int currentLevel) {
        if (currentLevel >= maxLevel) {
            return -1;
        }
        return baseUpgradeCost * Math.pow(upgradeCostMultiplier, currentLevel - 1);
    }

    /**
     * Returns the action cooldown in ticks at the given level,
     * decreasing by 20 ticks per level with a minimum of 1.
     *
     * @param level the minion level to calculate for
     * @return cooldown in ticks (always &ge; 1)
     */
    public int getActionInterval(int level) {
        return Math.max(1, actionIntervalTicks - (level * 20));
    }

    /**
     * Parses {@link #armorColor} (format {@code "R,G,B"}) into a Bukkit {@link Color}.
     * Falls back to brown {@code (139, 69, 19)} if the value is absent or malformed.
     *
     * @return the resolved armor {@link Color}
     */
    public Color getArmorColorObject() {
        if (armorColor == null || armorColor.isEmpty()) {
            return Color.fromRGB(139, 69, 19);
        }

        try {
            String[] rgb = armorColor.split(",");
            if (rgb.length == 3) {
                return Color.fromRGB(
                    Integer.parseInt(rgb[0].trim()),
                    Integer.parseInt(rgb[1].trim()),
                    Integer.parseInt(rgb[2].trim())
                );
            }
        } catch (Exception e) {
            // Fallback to default brown
        }

        return Color.fromRGB(139, 69, 19);
    }

    /**
     * Resolves the effective behaviour, accounting for the legacy {@code seedItem} migration path.
     * If {@code behavior} is {@link MinionBehavior#MINER} but {@code seedItem} is set,
     * returns {@link MinionBehavior#CROP_FARMER} for backward compatibility with old configs.
     *
     * @return the effective {@link MinionBehavior}
     */
    public MinionBehavior getEffectiveBehavior() {
        if (behavior == MinionBehavior.MINER && seedItem != null) {
            return MinionBehavior.CROP_FARMER;
        }
        return behavior;
    }
}
