package pl.furycraft.furyminions.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;

/**
 * Serialisable data transfer object representing a persisted minion record.
 * Used to transfer minion state between the database layer and the runtime {@link Minion} model.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MinionData {

    private UUID minionUuid;
    private UUID ownerUuid;
    private String type;
    private int level;
    private String world;
    private double x;
    private double y;
    private double z;
    private float pitch;
    private float yaw;
    private String facingDirection;
    /** Base64-encoded binary inventory content serialised via {@link org.bukkit.util.io.BukkitObjectOutputStream}. */
    private String inventoryContent;
    /** Optional extra settings stored as a plain string (e.g. selected log type for TREE_FARMER). */
    private String extraData;
    private long updatedAt;

    /**
     * Reconstructs a Bukkit {@link Location} from this record's coordinates.
     *
     * @return the world location, or a location with a {@code null} world if the world is not loaded
     */
    public Location toLocation() {
        return new Location(
            org.bukkit.Bukkit.getWorld(world),
            x, y, z,
            yaw, pitch
        );
    }

    /**
     * Factory method that builds a {@link MinionData} from a Bukkit {@link Location},
     * with an empty inventory and no extra data. Convenience for the placement flow.
     *
     * @param minionUuid     unique identifier for the new minion
     * @param ownerUuid      UUID of the placing player
     * @param type           minion type identifier string
     * @param level          initial minion level
     * @param location       placement location
     * @param facingDirection initial facing direction string
     * @return a populated {@link MinionData} instance
     */
    public static MinionData fromLocation(UUID minionUuid, UUID ownerUuid, String type,
                                          int level, Location location, String facingDirection) {
        return MinionData.builder()
            .minionUuid(minionUuid)
            .ownerUuid(ownerUuid)
            .type(type)
            .level(level)
            .world(location.getWorld().getName())
            .x(location.getX())
            .y(location.getY())
            .z(location.getZ())
            .pitch(location.getPitch())
            .yaw(location.getYaw())
            .facingDirection(facingDirection)
            .inventoryContent("[]")
            .extraData("")
            .updatedAt(System.currentTimeMillis())
            .build();
    }
}
