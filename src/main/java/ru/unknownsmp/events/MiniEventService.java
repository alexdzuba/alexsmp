package ru.unknownsmp.events;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import ru.unknownsmp.core.Module;
import ru.unknownsmp.core.Msg;
import ru.unknownsmp.core.Tasks;
import ru.unknownsmp.core.UnknownSMP;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

/**
 * Короткие глобальные мини-события (10–30 сек) для оживления сервера: «переворот мира», звездопад,
 * час двойной добычи и т.п. Безопасны — не наносят урона и не ломают мир. Запускаются случайно и командой.
 */
public final class MiniEventService implements Module, org.bukkit.event.Listener {
    public record Mini(String id, String name, String announce, int seconds, Consumer<MiniEventService> run) {}

    private final UnknownSMP plugin;
    private final Random rnd = new Random();
    private final List<Mini> pool = new ArrayList<>();
    private volatile Mini active;
    private volatile long activeUntil;
    private volatile double dropMultiplier = 1.0;

    public MiniEventService(UnknownSMP plugin) {
        this.plugin = plugin;
        pool.add(new Mini("flip", "&dПереворот мира", "&d✦ ПЕРЕВОРОТ МИРА! Всех подбрасывает вверх — держитесь!", 10, MiniEventService::flip));
        pool.add(new Mini("starfall", "&bЗвездопад", "&b✦ ЗВЕЗДОПАД! Загляните в небо.", 20, MiniEventService::starfall));
        pool.add(new Mini("haste", "&eПрилив сил", "&e✦ ПРИЛИВ СИЛ! Скорость и спешка для всех на 30 секунд.", 30, MiniEventService::haste));
        pool.add(new Mini("double", "&aДвойная добыча", "&a✦ ДВОЙНАЯ ДОБЫЧА из мобов на 30 секунд!", 30, MiniEventService::doubleDrops));
        pool.add(new Mini("feather", "&fНевесомость", "&f✦ НЕВЕСОМОСТЬ! Медленное падение на 20 секунд.", 20, MiniEventService::feather));
        pool.add(new Mini("night", "&9Короткая ночь", "&9✦ Ночь сгущается... осторожнее.", 15, MiniEventService::night));
    }

    @Override public String id() { return "minievents"; }
    @Override public String title() { return "Мини-события"; }
    @Override public String[] depends() { return new String[]{"world"}; }

    @Override
    public void enable() {
        long period = Math.max(5, plugin.configs().get("events.yml").getLong("mini.every-minutes", 25)) * 60 * 20;
        Tasks.timer(period, period, () -> {
            if (!plugin.configs().get("events.yml").getBoolean("mini.enabled", true)) return;
            if (active != null) return;
            if (survivalPlayers().size() < plugin.configs().get("events.yml").getInt("mini.min-players", 1)) return;
            trigger(pool.get(rnd.nextInt(pool.size())));
        });
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Tasks.timer(20, 20, () -> {
            if (active != null && System.currentTimeMillis() >= activeUntil) {
                active = null;
                dropMultiplier = 1.0;
            }
        });
    }

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(org.bukkit.event.entity.EntityDeathEvent e) {
        if (dropMultiplier <= 1.0 || e.getEntity().getKiller() == null) return;
        if (e.getEntity().getPersistentDataContainer().has(ru.unknownsmp.core.Keys.of("spawner"), org.bukkit.persistence.PersistentDataType.BYTE)) return;
        java.util.List<org.bukkit.inventory.ItemStack> extra = new java.util.ArrayList<>();
        for (org.bukkit.inventory.ItemStack it : e.getDrops()) if (it != null && !it.getType().isAir()) extra.add(it.clone());
        org.bukkit.Location l = e.getEntity().getLocation();
        for (org.bukkit.inventory.ItemStack it : extra) l.getWorld().dropItemNaturally(l, it);
    }

    public double dropMultiplier() { return dropMultiplier; }

    public List<Mini> pool() { return pool; }

    public void trigger(String id) {
        for (Mini m : pool) if (m.id().equals(id)) { trigger(m); return; }
    }

    private void trigger(Mini m) {
        active = m;
        activeUntil = System.currentTimeMillis() + m.seconds() * 1000L;
        Msg.broadcast("mini." + m.id(), m.announce());
        for (Player p : survivalPlayers()) {
            Msg.title(p, m.name(), "", 5, 40, 10);
            Msg.sound(p, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 0.8f);
        }
        try { m.run().accept(this); } catch (Throwable t) { plugin.getLogger().warning("Мини-событие " + m.id() + ": " + t); }
        plugin.logs().sys("event", "mini", null, m.id());
    }

    private List<Player> survivalPlayers() {
        List<Player> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!plugin.auth().isAuthed(p)) continue;
            if (plugin.lobby().isLobby(p.getWorld()) || (plugin.duels() != null && plugin.duels().isDuelWorld(p.getWorld()))) continue;
            out.add(p);
        }
        return out;
    }

    // ------------------------------------------------------------------ Эффекты

    private void flip() {
        for (Player p : survivalPlayers()) {
            p.setVelocity(p.getVelocity().setY(1.4));
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 20 * 8, 0, true, false));
            p.playSound(p.getLocation(), Sound.ENTITY_BREEZE_WIND_BURST, 1f, 1f);
        }
    }

    private void starfall() {
        final int[] ticks = {0};
        Tasks.timer(0, 5, new Runnable() {
            final org.bukkit.scheduler.BukkitTask[] self = new org.bukkit.scheduler.BukkitTask[1];
            { self[0] = null; }
            @Override public void run() {
                if (active == null || !active.id().equals("starfall")) return;
                for (Player p : survivalPlayers()) {
                    Location l = p.getLocation().add(rnd.nextInt(16) - 8, 20, rnd.nextInt(16) - 8);
                    p.getWorld().spawnParticle(Particle.FIREWORK, l, 5, 0.2, 2, 0.2, 0.1);
                    p.getWorld().spawnParticle(Particle.END_ROD, l, 3, 0.1, 3, 0.1, 0.05);
                }
            }
        });
    }

    private void haste() {
        for (Player p : survivalPlayers()) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20 * 30, 1, true, true));
            p.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, 20 * 30, 1, true, true));
        }
    }

    private void doubleDrops() {
        dropMultiplier = 2.0;
    }

    private void feather() {
        for (Player p : survivalPlayers()) p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 20 * 20, 0, true, true));
    }

    private void night() {
        for (World w : Bukkit.getWorlds()) {
            if (w.getEnvironment() == World.Environment.NORMAL && w.equals(plugin.survivalWorld())) {
                w.setTime(15000);
            }
        }
        for (Player p : survivalPlayers()) p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 20 * 20, 0, true, false));
    }
}
