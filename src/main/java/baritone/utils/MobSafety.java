/* SPDX-License-Identifier: LGPL-3.0-or-later */
package baritone.utils;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.utils.Helper;
import baritone.api.utils.IPlayerContext;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.Guardian;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.monster.Ravager;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.monster.Zoglin;
import net.minecraft.world.entity.monster.breeze.Breeze;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.entity.monster.illager.SpellcasterIllager;
import net.minecraft.world.entity.monster.illager.AbstractIllager;
import net.minecraft.world.entity.monster.illager.Vindicator;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.monster.zombie.Drowned;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.entity.monster.spider.Spider;
import net.minecraft.world.entity.player.Player;

/** Hostile-mob classification and the persistent avoidance preset shared by planning and retreat. */
public final class MobSafety {
    public static final double PATH_COEFFICIENT = 4.0;
    private static volatile boolean enabled = true;
    private static boolean preferenceLoaded;
    private static Object[] previousSettings;
    private MobSafety() {}

    /** Path-cost filter. Replaces upstream's {@code instanceof Mob}, which also matched passive animals and villagers. */
    public static boolean hostile(Entity entity) {
        return entity instanceof Mob && entity instanceof Enemy;
    }

    /** Hostile mobs that can currently harm the player; neutral mobs count only once they are angry. */
    public static boolean dangerous(Entity entity, Player player) {
        if (!hostile(entity) || !entity.isAlive() || ((Mob) entity).isNoAi()) return false;
        // Retreating on foot cannot outrun these; planning still avoids their surroundings.
        if (entity instanceof Phantom || entity instanceof Ghast || entity instanceof Vex || entity instanceof EnderDragon) return false;
        if (entity instanceof Enderman enderman) return enderman.isCreepy();
        Mob mob = (Mob) entity;
        // Melee, bow and crossbow attackers raise the synced aggressive flag only while going for a target; idle or
        // wandering ones (and spiders in daylight) are not a threat. Trident drowned use a goal that never sets it.
        boolean flagged = entity instanceof Zombie && !(entity instanceof Drowned && mob.getMainHandItem().getItem() instanceof TridentItem)
                || entity instanceof AbstractSkeleton || entity instanceof Spider || entity instanceof AbstractPiglin
                || entity instanceof AbstractIllager && !(entity instanceof SpellcasterIllager);
        return !flagged || mob.isAggressive();
    }

    /**
     * Distances in blocks: retreat trigger with and without line of sight, retreat target distance,
     * path-cost radius and the vertical gap within which a mob can reach the player.
     */
    public record Profile(double sight, double blind, double run, int radius, double reachY, boolean ranged, boolean urgent) {}

    public static Profile profile(Mob mob) {
        if (mob instanceof Creeper creeper) {
            boolean swelling = creeper.getSwellDir() > 0 || creeper.isIgnited() || creeper.getSwelling(1f) > 0;
            return swelling ? new Profile(9, 9, 16, 10, 6, false, true) : new Profile(8, 5, 16, 10, 6, false, false);
        }
        if (mob instanceof Warden) return new Profile(20, 20, 30, 20, 20, false, true);
        // Bows reach about 15 blocks; hiding behind cover counts as escaping these.
        if (ranged(mob)) return new Profile(16, 4, 24, 14, 12, true, false);
        if (mob instanceof Spider) return new Profile(8, 4, 18, 10, 8, false, false);
        if (mob instanceof Ravager || mob instanceof Hoglin || mob instanceof Zoglin || mob instanceof Vindicator
                || mob instanceof Enderman || mob instanceof AbstractPiglin) return new Profile(8, 4, 18, 10, 4, false, false);
        return new Profile(6, 3, 16, 8, 4, false, false);
    }

    private static boolean ranged(Mob mob) {
        if (mob instanceof Witch || mob instanceof Blaze || mob instanceof Breeze || mob instanceof Shulker
                || mob instanceof Guardian || mob instanceof SpellcasterIllager || mob instanceof WitherBoss) return true;
        // Skeletons, strays, bogged and pillagers by their weapon; wither skeletons and unarmed drowned stay melee.
        Item held = mob.getMainHandItem().getItem();
        return held instanceof BowItem || held instanceof CrossbowItem || held instanceof TridentItem && mob instanceof Drowned;
    }

    /** Path-cost radius for upstream Avoidance: ranged mobs and creepers get a wider berth than the default. */
    public static int avoidRadius(Entity entity, int fallback) {
        return entity instanceof Mob mob && hostile(entity) ? Math.max(fallback, profile(mob).radius()) : fallback;
    }

    /** Walls on all four sides at feet and head height: staying put is safer than breaking out to flee. */
    public static boolean enclosed(IPlayerContext ctx) {
        var world = ctx.world();
        var feet = ctx.playerFeet();
        for (int dy = 0; dy <= 1; dy++) for (var d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            var p = feet.relative(d).above(dy);
            if (world.getBlockState(p).getCollisionShape(world, p).isEmpty()) return false;
        }
        return true;
    }

    public static boolean enabled() { return enabled; }

    public static void enable(boolean value) {
        var settings = BaritoneAPI.getSettings();
        if (value && previousSettings == null) previousSettings = new Object[]{settings.avoidance.value, settings.mobAvoidanceCoefficient.value};
        enabled = value;
        if (value) {
            settings.avoidance.value = true;
            settings.mobAvoidanceCoefficient.value = PATH_COEFFICIENT;
        } else if (previousSettings != null) {
            // Deliberate changes made while enabled are kept.
            if (settings.avoidance.value) settings.avoidance.value = (Boolean) previousSettings[0];
            if (settings.mobAvoidanceCoefficient.value == PATH_COEFFICIENT) settings.mobAvoidanceCoefficient.value = (Double) previousSettings[1];
            previousSettings = null;
        }
    }

    public static void savePreference(IPlayerContext context) {
        try {
            var file = context.minecraft().gameDirectory.toPath().resolve("baritone/mob-safety.properties");
            java.nio.file.Files.createDirectories(file.getParent());
            var temporary = file.resolveSibling("mob-safety.properties.tmp");
            java.nio.file.Files.writeString(temporary, "enabled=" + enabled + "\n");
            java.nio.file.Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.IOException error) { Helper.HELPER.logDirect("Could not save mob avoidance: " + error.getMessage()); }
    }

    public static void register(IBaritone baritone) {
        if (!preferenceLoaded) {
            preferenceLoaded = true;
            var file = baritone.getPlayerContext().minecraft().gameDirectory.toPath().resolve("baritone/mob-safety.properties");
            try {
                if (java.nio.file.Files.isRegularFile(file)) {
                    var properties = new java.util.Properties();
                    try (var reader = java.nio.file.Files.newBufferedReader(file)) { properties.load(reader); }
                    enabled = Boolean.parseBoolean(properties.getProperty("enabled", "true"));
                }
            } catch (java.io.IOException error) { Helper.HELPER.logDirect("Could not read mob avoidance preference; using default."); }
            if (enabled) enable(true);
        }
        baritone.getCommandManager().getRegistry().register(new MobSafetyCommand(baritone));
    }
}
