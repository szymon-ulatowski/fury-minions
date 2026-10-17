package pl.furycraft.furyminions.config;

import lombok.Getter;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import pl.furycraft.furyminions.FuryMinions;
import pl.furycraft.furyminions.model.MinionBehavior;
import pl.furycraft.furyminions.model.MinionType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads and exposes the plugin configuration, primarily the {@link MinionType} registry.
 * Config reloads are fully in-memory; no restarts are required.
 */
@Getter
public class ConfigManager {

    private final FuryMinions plugin;
    private final Map<String, MinionType> minionTypes;

    public ConfigManager(FuryMinions plugin) {
        this.plugin = plugin;
        this.minionTypes = new HashMap<>();
    }

    /**
     * Reloads the backing {@code config.yml} from disk and re-parses all minion type definitions.
     */
    public void loadConfig() {
        plugin.reloadConfig();
        loadMinionTypes();
        plugin.getLogger().info("Konfiguracja została załadowana pomyślnie.");
    }

    /**
     * Alias for {@link #loadConfig()} exposed as a named reload operation.
     */
    public void reloadConfig() {
        loadConfig();
        plugin.getLogger().info("Konfiguracja została przeładowana.");
    }

    /**
     * Parses the {@code minion-types} section of {@code config.yml} into {@link MinionType} instances.
     * Unknown materials and behaviours produce warnings but do not abort the load.
     * The {@code behavior} field defaults to {@link MinionBehavior#MINER} for backward compatibility.
     */
    private void loadMinionTypes() {
        minionTypes.clear();

        ConfigurationSection typesSection = plugin.getConfig().getConfigurationSection("minion-types");
        if (typesSection == null) {
            plugin.getLogger().warning("Nie znaleziono sekcji 'minion-types' w config.yml!");
            return;
        }

        for (String typeId : typesSection.getKeys(false)) {
            ConfigurationSection typeSection = typesSection.getConfigurationSection(typeId);
            if (typeSection == null) {
                continue;
            }

            try {
                // Defaults to MINER for backward compatibility with configs that omit the 'behavior' key
                MinionBehavior behavior = MinionBehavior.MINER;
                String behaviorStr = typeSection.getString("behavior");
                if (behaviorStr != null && !behaviorStr.isEmpty()) {
                    try {
                        behavior = MinionBehavior.valueOf(behaviorStr.toUpperCase());
                    } catch (IllegalArgumentException e) {
                        plugin.getLogger().warning("Nieznany behavior '" + behaviorStr + "' dla miniona '" + typeId + "', używam MINER.");
                    }
                }

                List<Material> dropItems = new ArrayList<>();
                List<String> dropStrings = typeSection.getStringList("drop-items");
                for (String dropStr : dropStrings) {
                    Material mat = Material.matchMaterial(dropStr);
                    if (mat != null) {
                        dropItems.add(mat);
                    } else {
                        plugin.getLogger().warning("Nieznany materiał '" + dropStr + "' w drop-items dla '" + typeId + "'");
                    }
                }

                Material generatedBlock = null;
                String generatedBlockStr = typeSection.getString("generated-block");
                if (generatedBlockStr != null && !generatedBlockStr.isEmpty()) {
                    generatedBlock = Material.matchMaterial(generatedBlockStr);
                    if (generatedBlock == null) {
                        plugin.getLogger().warning("Nieznany materiał '" + generatedBlockStr + "' w generated-block dla '" + typeId + "'");
                    }
                }

                // 'seed-item' is a legacy field kept for backward compatibility with older configs
                Material seedItem = null;
                if (typeSection.contains("seed-item")) {
                    String seedStr = typeSection.getString("seed-item");
                    if (seedStr != null) {
                        seedItem = Material.matchMaterial(seedStr);
                    }
                }

                MinionType minionType = MinionType.builder()
                    .id(typeId)
                    .displayName(typeSection.getString("display-name", typeId))
                    .headTexture(typeSection.getString("head-texture", ""))
                    .handItem(parseMaterial(typeSection.getString("hand-item", "DIAMOND_PICKAXE"), Material.DIAMOND_PICKAXE))
                    .actionIntervalTicks(typeSection.getInt("action-interval-ticks", 60))
                    .platformRadius(typeSection.getInt("platform-radius", 2))
                    .baseUpgradeCost(typeSection.getDouble("base-upgrade-cost", 1000.0))
                    .upgradeCostMultiplier(typeSection.getDouble("upgrade-cost-multiplier", 1.5))
                    .shopPrice(typeSection.getDouble("shop-price", 1000.0))
                    .maxLevel(typeSection.getInt("max-level", 10))
                    .generatedBlock(generatedBlock)
                    .particleType(typeSection.getString("particle-type", "BLOCK_CRACK"))
                    .soundType(typeSection.getString("sound-type", "BLOCK_STONE_BREAK"))
                    .armorColor(typeSection.getString("armor-color", "139,69,19"))
                    .shopSlot(typeSection.getInt("shop-slot", -1))
                    .seedItem(seedItem)
                    .behavior(behavior)
                    .dropItems(dropItems)
                    .animalType(typeSection.getString("animal-type", null))
                    .build();

                minionTypes.put(typeId.toLowerCase(), minionType);
                plugin.getLogger().info("Załadowano typ miniona: " + typeId + " [" + minionType.getEffectiveBehavior() + "]");
            } catch (Exception e) {
                plugin.getLogger().severe("Błąd podczas ładowania typu miniona '" + typeId + "': " + e.getMessage());
                e.printStackTrace();
            }
        }

        plugin.getLogger().info("Załadowano " + minionTypes.size() + " typów minionów.");
    }

    /**
     * Parses a material name with a safe fallback.
     *
     * @param name     the material name string to parse
     * @param fallback the material to return if {@code name} is null or unrecognised
     * @return the resolved {@link Material}
     */
    private Material parseMaterial(String name, Material fallback) {
        if (name == null) return fallback;
        Material mat = Material.matchMaterial(name);
        return mat != null ? mat : fallback;
    }

    /**
     * Returns the {@link MinionType} registered under the given ID (case-insensitive).
     *
     * @param typeId the minion type identifier
     * @return the matching {@link MinionType}, or {@code null} if not found
     */
    public MinionType getMinionType(String typeId) {
        return minionTypes.get(typeId.toLowerCase());
    }

    /**
     * Returns {@code true} if a minion type with the given ID is registered.
     *
     * @param typeId the minion type identifier (case-insensitive)
     */
    public boolean hasMinionType(String typeId) {
        return minionTypes.containsKey(typeId.toLowerCase());
    }
}
