package ru.unknownsmp.events.types;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.PiglinAbstract;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.inventory.ItemStack;
import ru.unknownsmp.core.Text;
import ru.unknownsmp.core.UnknownSMP;
import ru.unknownsmp.events.EventService;
import ru.unknownsmp.events.GameEvent;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Ритуал открытия Незера: алтарь из Ключа Незера; три волны существ. Алтарь теряет прочность,
 * когда существа подходят вплотную. Успех — алтарь выстоял все волны. При неудаче ключ возвращается
 * активировавшему, повтор — через час.
 */
public final class RitualEvent extends GameEvent {
    private final UUID activator;
    private final ItemStack key;
    private final Set<Block> altar = new HashSet<>();
    private final Random rnd = new Random();
    private BossBar bar;
    private int hp = 100;
    private int wave;
    private int seconds;
    private int noPlayers;

    public RitualEvent(UnknownSMP plugin, EventService s, UUID activator, ItemStack key) {
        super(plugin, s);
        this.activator = activator;
        this.key = key;
    }

    @Override public String type() { return "ritual"; }
    @Override public String title() { return "Ритуал открытия Незера"; }
    @Override public String warning() { return "Земля раскаляется... Ключ Незера пробуждается."; }
    @Override public int minutes() { return plugin.configs().get("events.yml").getInt("types.ritual.minutes", 10); }
    @Override public String description() { return "Защитите алтарь от трёх волн существ!"; }

    @Override
    protected boolean setup() {
        World w = loc.getWorld();
        Block center = loc.getBlock();
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) {
            double d = Math.sqrt(x * x + z * z);
            if (d > 3.5) continue;
            Block floor = center.getRelative(x, -1, z);
            floor.setType(d < 1.5 ? Material.CRYING_OBSIDIAN : d < 2.5 ? Material.POLISHED_BLACKSTONE_BRICKS : Material.NETHER_BRICKS, false);
            altar.add(floor);
            for (int y = 0; y <= 3; y++) {
                Block b = center.getRelative(x, y, z);
                if (!b.getType().isAir()) b.setType(Material.AIR, false);
            }
        }
        Block core = center;
        core.setType(Material.RESPAWN_ANCHOR, false);
        altar.add(core);
        for (int[] o : new int[][]{{3, 0}, {-3, 0}, {0, 3}, {0, -3}}) {
            Block pillar = center.getRelative(o[0], 0, o[1]);
            pillar.setType(Material.OBSIDIAN, false);
            pillar.getRelative(0, 1, 0).setType(Material.OBSIDIAN, false);
            pillar.getRelative(0, 2, 0).setType(Material.SOUL_LANTERN, false);
            altar.add(pillar);
            altar.add(pillar.getRelative(0, 1, 0));
        }
        bar = org.bukkit.Bukkit.createBossBar(Text.legacySection("&cАлтарь Незера"), BarColor.RED, BarStyle.SEGMENTED_10);
        w.playSound(loc, Sound.BLOCK_END_PORTAL_SPAWN, 3f, 0.6f);
        return true;
    }

    @Override
    protected void tick() {
        seconds++;
        World w = loc.getWorld();
        w.spawnParticle(Particle.FLAME, loc.clone().add(0, 1.2, 0), 12, 0.5, 0.8, 0.5, 0.02);
        w.spawnParticle(Particle.SOUL_FIRE_FLAME, loc.clone().add(0, 2, 0), 6, 2.5, 0.2, 2.5, 0.01);
        int total = minutes() * 60;
        if (wave < 3 && seconds >= wave * (total / 3) + 5) {
            wave++;
            spawnWave(wave);
            announce("ritual.wave", "&c&lРитуал: волна {w}/3!", "w", wave);
        }
        int nearby = 0;
        for (Player p : w.getPlayers()) {
            boolean in = near(p, 40) && plugin.auth().isAuthed(p);
            if (in) {
                nearby++;
                bar.addPlayer(p);
                if (seconds % 20 == 0) contribute(p, 1, "defend");
            } else bar.removePlayer(p);
        }
        noPlayers = nearby == 0 ? noPlayers + 1 : 0;
        for (Entity e : spawned) {
            if (!e.isValid()) continue;
            if (e.getLocation().distanceSquared(loc) <= 9) hp -= 1;
            if (e instanceof Monster m && m.getTarget() == null) {
                for (Player p : w.getPlayers()) if (near(p, 40)) { m.setTarget(p); break; }
            }
        }
        bar.setProgress(Math.max(0, Math.min(1, hp / 100.0)));
        bar.setTitle(Text.legacySection("&cАлтарь Незера &7— прочность " + Math.max(0, hp) + "% &8| волна " + wave + "/3"));
        if (hp <= 0 || noPlayers >= 60) {
            finishNow();
            return;
        }
        boolean allDead = spawned.stream().noneMatch(Entity::isValid);
        if (wave == 3 && allDead && seconds > 30) finishNow();
    }

    private void spawnWave(int n) {
        World w = loc.getWorld();
        EntityType[] types = n == 1 ? new EntityType[]{EntityType.ZOMBIE, EntityType.SKELETON, EntityType.SPIDER}
                : n == 2 ? new EntityType[]{EntityType.PIGLIN, EntityType.WITCH, EntityType.SKELETON, EntityType.BLAZE}
                : new EntityType[]{EntityType.WITHER_SKELETON, EntityType.PIGLIN_BRUTE, EntityType.BLAZE};
        int count = 4 + n * 3;
        for (int i = 0; i < count; i++) {
            double a = rnd.nextDouble() * Math.PI * 2;
            Location spot = w.getHighestBlockAt((int) (loc.getX() + Math.cos(a) * 14), (int) (loc.getZ() + Math.sin(a) * 14)).getLocation().add(0.5, 1, 0.5);
            LivingEntity m = (LivingEntity) w.spawnEntity(spot, types[rnd.nextInt(types.length)]);
            if (m instanceof PiglinAbstract pa) pa.setImmuneToZombification(true);
            m.customName(Text.c("&4Порождение Незера"));
            m.setRemoveWhenFarAway(false);
            mark(m);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void death(EntityDeathEvent e) {
        if (!spawned.contains(e.getEntity())) return;
        e.getDrops().clear();
        Player k = e.getEntity().getKiller();
        if (k != null) contribute(k, 2, "kill");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void brk(BlockBreakEvent e) {
        if (altar.contains(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void explode(EntityExplodeEvent e) {
        e.blockList().removeAll(altar);
    }

    @Override
    protected boolean succeeded() {
        return hp > 0 && wave >= 3 && noPlayers < 60 && spawned.stream().noneMatch(Entity::isValid);
    }

    @Override
    protected void cleanup() {
        super.cleanup();
        if (bar != null) bar.removeAll();
    }

    /** Итог ритуала: открыть Незер или вернуть ключ. */
    public void onFinish(boolean success, List<Map.Entry<UUID, Double>> qualified) {
        plugin.dimensions().ritualFinished(success, activator, key, loc);
    }
}
