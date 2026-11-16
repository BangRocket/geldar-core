package dev.geldar.core;

/**
 * The Bukkit {@code DamageCause} name for one of the game's damage types. Geldar's damage and death events carry Bukkit's
 * names ({@code FALL}, {@code ENTITY_ATTACK}), and the gravestone's cause word is chosen from them, so the names are kept
 * on a server that has no Bukkit. A damage type from a mod reads as {@code CUSTOM}.
 */
public final class DamageCauses {
    private DamageCauses() {}

    /**
     * @param type the damage type's id without its namespace, such as {@code mob_attack}
     * @param byEntity true when an entity caused the damage; it decides which kind of explosion it was
     */
    public static String name(String type, boolean byEntity) {
        return switch (type) {
            case "in_fire" -> "FIRE";
            case "campfire" -> "CAMPFIRE";
            case "on_fire" -> "FIRE_TICK";
            case "lava" -> "LAVA";
            case "hot_floor" -> "HOT_FLOOR";
            case "lightning_bolt" -> "LIGHTNING";
            case "in_wall" -> "SUFFOCATION";
            case "cramming" -> "CRAMMING";
            case "drown" -> "DROWNING";
            case "starve" -> "STARVATION";
            case "cactus", "sweet_berry_bush", "stalagmite" -> "CONTACT";
            case "fall", "ender_pearl" -> "FALL";
            case "fly_into_wall" -> "FLY_INTO_WALL";
            case "out_of_world" -> "VOID";
            case "outside_border" -> "WORLD_BORDER";
            case "magic", "indirect_magic" -> "MAGIC";
            case "wither" -> "WITHER";
            case "dragon_breath" -> "DRAGON_BREATH";
            case "dry_out" -> "DRYOUT";
            case "freeze" -> "FREEZE";
            case "falling_block", "falling_anvil", "falling_stalactite" -> "FALLING_BLOCK";
            case "sting", "mob_attack", "mob_attack_no_aggro", "player_attack", "mace_smash" -> "ENTITY_ATTACK";
            case "arrow", "trident", "mob_projectile", "spit", "wind_charge", "fireball", "unattributed_fireball",
                    "wither_skull", "thrown" -> "PROJECTILE";
            case "thorns" -> "THORNS";
            case "sonic_boom" -> "SONIC_BOOM";
            case "fireworks", "player_explosion" -> "ENTITY_EXPLOSION";
            case "explosion" -> byEntity ? "ENTITY_EXPLOSION" : "BLOCK_EXPLOSION";
            case "bad_respawn_point" -> "BLOCK_EXPLOSION";
            case "generic_kill" -> "KILL";
            default -> "CUSTOM";
        };
    }
}
