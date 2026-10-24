package ru.unknownsmp.events.types;

import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.inventory.ItemStack;
import ru.unknownsmp.core.Msg;
import ru.unknownsmp.core.UnknownSMP;
import ru.unknownsmp.events.EventService;
import ru.unknownsmp.events.GameEvent;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/** Падение метеорита: кратер с метеоритными блоками. Вклад — добыча блоков метеорита. */
public final class MeteorEvent extends GameEvent {
    private final Set<String> ore = new HashSet<>();
    private int total;
    private int mined;
    private final Random rnd = new Random();

    public MeteorEvent(UnknownSMP plugin, EventService s) {
        super(plugin, s);
    }

    @Override public String type() { return "meteor"; }
    @Override public String title() { return "Падение метеорита"; }
    @Override public String warning() { return "Небо прорезает огненный след... Что-то падает на землю."; }
    @Override public int minutes() { return plugin.configs().get("events.yml").getInt("types.meteor.minutes", 20); }
    @Override public String description() { return "Добывайте метеоритные блоки в кратере."; }

    private static String key(Block b) { return b.getX() + ":" + b.getY() + ":" + b.getZ(); }

    @Override
    protected boolean setup() {
        World w = loc.getWorld();
        int r = 4;
        int cx = loc.getBlockX(), cy = loc.getBlockY() - 1, cz = loc.getBlockZ();
        w.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 4f, 0.5f);
        w.spawnParticle(Particle.EXPLOSION_EMITTER, loc, 3);
        for (int x = -r - 2; x <= r + 2; x++) for (int y = -3; y <= 3; y++) for (int z = -r - 2; z <= r + 2; z++) {
            double d = Math.sqrt(x * x + (y * 1.6) * (y * 1.6) + z * z);
            Block b = w.getBlockAt(cx + x, cy + y, cz + z);
            if (plugin.world().inSpawnZone(b.getLocation())) continue;
            if (y >= 0 && d <= r + 1.5) {
                if (!b.getType().isAir()) b.setType(Material.AIR, false);
            } else if (y < 0 && d <= r + 0.5) {
                Material m;
                double roll = rnd.nextDouble();
                if (d < 2) m = roll < 0.35 ? Material.ANCIENT_DEBRIS : Material.CRYING_OBSIDIAN;
                else m = roll < 0.4 ? Material.MAGMA_BLOCK : roll < 0.7 ? Material.BLACKSTONE : roll < 0.85 ? Material.OBSIDIAN : Material.CRYING_OBSIDIAN;
                b.setType(m, false);
                ore.add(key(b));
            } else if (y < 0 && d <= r + 2 && b.getType().isSolid() && rnd.nextDouble() < 0.5) {
                b.setType(Material.BASALT, false);
            }
        }
        total = ore.size();
        if (rnd.nextDouble() < 0.5) w.getBlockAt(cx, cy + 1, cz).setType(Material.FIRE, false);
        return total > 10;
    }

    @Override
    protected void tick() {
        World w = loc.getWorld();
        w.spawnParticle(Particle.LAVA, loc, 4, 2, 0.5, 2);
        w.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, loc.clone().add(0, 2, 0), 2, 1, 1, 1, 0.01);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void brk(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (!b.getWorld().equals(loc.getWorld()) || !ore.remove(key(b))) return;
        mined++;
        double val = b.getType() == Material.ANCIENT_DEBRIS ? 3 : b.getType() == Material.CRYING_OBSIDIAN ? 2 : 1;
        contribute(e.getPlayer(), val, "mine");
        e.setDropItems(false);
        // Небольшая материальная добыча помимо наград за вклад.
        if (b.getType() == Material.ANCIENT_DEBRIS) b.getWorld().dropItemNaturally(b.getLocation(), new ItemStack(Material.ANCIENT_DEBRIS));
        else if (rnd.nextDouble() < 0.2) b.getWorld().dropItemNaturally(b.getLocation(), new ItemStack(Material.IRON_NUGGET, 1 + rnd.nextInt(4)));
        Msg.actionBar(e.getPlayer(), "&6Метеорит: " + mined + "/" + total + " &7(ваш вклад учтён)");
        if (ore.isEmpty()) finishNow();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void explode(EntityExplodeEvent e) {
        // Взрывы не засчитываются как вклад, но блоки метеорита защищены от подрыва.
        e.blockList().removeIf(b -> b.getWorld().equals(loc.getWorld()) && ore.contains(key(b)));
    }

    @Override
    protected boolean succeeded() {
        double need = plugin.configs().get("events.yml").getDouble("types.meteor.success-fraction", 0.5);
        return total > 0 && mined >= total * need;
    }

    @Override
    protected void cleanup() {
        super.cleanup();
        ore.clear();
    }
}
