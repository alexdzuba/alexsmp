package ru.unknownsmp.events.types;

import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import ru.unknownsmp.core.Keys;
import ru.unknownsmp.core.Msg;
import ru.unknownsmp.core.Text;
import ru.unknownsmp.core.UnknownSMP;
import ru.unknownsmp.events.EventService;
import ru.unknownsmp.events.GameEvent;

import java.util.List;
import java.util.Random;

/** Тайник со стражами: сундук открывается, только когда стражи повержены. Загадка указывает район. */
public final class CacheEvent extends GameEvent {
    private Block chest;
    private int guardsAlive;
    private boolean opened;
    private final Random rnd = new Random();
    private String riddle;

    public CacheEvent(UnknownSMP plugin, EventService s) {
        super(plugin, s);
        List<String> riddles = plugin.configs().get("events.yml").getStringList("riddles");
        riddle = riddles.isEmpty() ? "Там, где тень длиннее дня, лежит то, что ищут многие." : riddles.get(rnd.nextInt(riddles.size()));
    }

    @Override public String type() { return "cache"; }
    @Override public String title() { return "Тайник Стражей"; }

    @Override
    public String warning() {
        int ax = loc == null ? 0 : (loc.getBlockX() / 250) * 250, az = loc == null ? 0 : (loc.getBlockZ() / 250) * 250;
        return "Загадка: «" + riddle + "» Ищите в районе " + ax + ", " + az + ".";
    }

    @Override public int minutes() { return plugin.configs().get("events.yml").getInt("types.cache.minutes", 15); }
    @Override public String description() { return "Победите стражей и откройте тайник."; }

    @Override
    protected boolean setup() {
        World w = loc.getWorld();
        chest = loc.getBlock();
        chest.setType(Material.CHEST, false);
        chest.getRelative(0, -1, 0).setType(Material.MOSSY_COBBLESTONE, false);
        for (int[] o : new int[][]{{2, 0}, {-2, 0}, {0, 2}, {0, -2}}) {
            Block b = w.getHighestBlockAt(chest.getX() + o[0], chest.getZ() + o[1]).getRelative(0, 1, 0);
            b.setType(Material.MOSSY_STONE_BRICK_WALL, false);
            b.getRelative(0, 1, 0).setType(Material.SOUL_LANTERN, false);
        }
        if (chest.getState() instanceof Chest c) {
            c.getInventory().clear();
            int rolls = 3 + rnd.nextInt(3);
            for (int i = 0; i < rolls; i++) {
                ItemStack it = plugin.items().resolve(pickLoot(), "cache", "cache").getNow(null);
                if (it != null) c.getInventory().setItem(rnd.nextInt(27), it);
            }
        }
        int guards = plugin.configs().get("events.yml").getInt("types.cache.guards", 6);
        EntityType[] types = {EntityType.ZOMBIE, EntityType.SKELETON, EntityType.HUSK, EntityType.STRAY};
        for (int i = 0; i < guards; i++) {
            double a = Math.PI * 2 * i / guards;
            var spot = w.getHighestBlockAt((int) (loc.getX() + Math.cos(a) * 4), (int) (loc.getZ() + Math.sin(a) * 4)).getLocation().add(0.5, 1, 0.5);
            LivingEntity g = (LivingEntity) w.spawnEntity(spot, types[rnd.nextInt(types.length)]);
            g.customName(Text.c("&cСтраж тайника"));
            g.setCustomNameVisible(true);
            g.setRemoveWhenFarAway(false);
            g.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.FIRE_RESISTANCE, 20 * 60 * 30, 0, false, false));
            var hp = g.getAttribute(Attribute.MAX_HEALTH);
            if (hp != null) { hp.setBaseValue(hp.getBaseValue() * 1.6); g.setHealth(hp.getBaseValue()); }
            EntityEquipment eq = g.getEquipment();
            if (eq != null) {
                eq.setHelmet(new ItemStack(Material.CHAINMAIL_HELMET));
                eq.setHelmetDropChance(0);
                eq.setChestplate(new ItemStack(Material.IRON_CHESTPLATE));
                eq.setChestplateDropChance(0);
            }
            g.getPersistentDataContainer().set(Keys.GUARD_MOB, PersistentDataType.BYTE, (byte) 1);
            mark(g);
        }
        guardsAlive = guards;
        return true;
    }

    private String pickLoot() {
        List<String> loot = plugin.configs().get("events.yml").getStringList("types.cache.chest-items");
        if (loot.isEmpty()) loot = List.of("DIAMOND:2", "GOLDEN_APPLE:2", "EMERALD:6", "EXPERIENCE_BOTTLE:8", "potion:speed_plus");
        return loot.get(rnd.nextInt(loot.size()));
    }

    @Override
    protected void tick() {
        loc.getWorld().spawnParticle(Particle.SOUL, loc.clone().add(0, 1, 0), 3, 0.4, 0.4, 0.4, 0.01);
        // Если стражи пропали (выгрузка чанка, сброс) — не держим сундук запечатанным.
        if (guardsAlive > 0) {
            int alive = 0;
            for (var g : spawned) if (g.isValid() && !g.isDead()) alive++;
            if (alive < guardsAlive) {
                guardsAlive = alive;
                if (alive <= 0) announce("event.cache-open", "&dВсе стражи тайника повержены! Тайник можно открыть.");
            }
        }
        for (var e : spawned) {
            if (e instanceof Monster m && e.isValid() && m.getTarget() == null) {
                for (Player p : loc.getWorld().getPlayers()) {
                    if (near(p, 16) && plugin.auth().isAuthed(p)) { m.setTarget(p); break; }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void death(EntityDeathEvent e) {
        if (!spawned.contains(e.getEntity())) return;
        guardsAlive--;
        Player k = e.getEntity().getKiller();
        if (k != null) contribute(k, 3, "guard");
        e.getDrops().clear();
        if (guardsAlive <= 0) announce("event.cache-open", "&dВсе стражи тайника повержены! Тайник можно открыть.");
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void open(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null || !e.getClickedBlock().equals(chest)) return;
        if (guardsAlive > 0) {
            e.setCancelled(true);
            Msg.error(e.getPlayer(), "event.cache-guarded", "Тайник запечатан, пока живы стражи ({n}).", "n", guardsAlive);
            return;
        }
        if (!opened) {
            opened = true;
            contribute(e.getPlayer(), 5, "open");
            announce("event.cache-opened", "&d{p} открыл тайник стражей!", "p", e.getPlayer().getName());
            ru.unknownsmp.core.Tasks.later(1, this::finishNow);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void brk(BlockBreakEvent e) {
        if (e.getBlock().equals(chest) && guardsAlive > 0) {
            e.setCancelled(true);
            Msg.error(e.getPlayer(), "event.cache-guarded", "Тайник запечатан, пока живы стражи ({n}).", "n", guardsAlive);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void explode(EntityExplodeEvent e) {
        if (guardsAlive > 0) e.blockList().remove(chest);
    }

    @Override
    protected boolean succeeded() { return opened; }

    @Override
    protected void cleanup() {
        super.cleanup();
        // Сундук с остатками добычи остаётся в мире — уникальные находки не возрождаются.
    }
}
