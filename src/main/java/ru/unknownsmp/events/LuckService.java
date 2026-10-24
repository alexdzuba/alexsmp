package ru.unknownsmp.events;

import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.world.LootGenerateEvent;
import ru.unknownsmp.core.Module;
import ru.unknownsmp.core.Msg;
import ru.unknownsmp.core.Tasks;
import ru.unknownsmp.core.UnknownSMP;

import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Модуль удачи/невезения. Каждому игроку можно задать уровень удачи (от -3 до +3) командой владельца.
 * Отрицательный уровень снижает дроп, качество зачарований и изредка подбрасывает безобидные неприятности
 * (звук, частицы, кратковременный эффект). Положительный — наоборот. Значение НИКОГДА не убивает игрока.
 *
 * Уровни хранятся в world_state (luck.<uuid>). Все срабатывания записываются в журнал (категория luck),
 * так что владелец всегда видит, что происходило. Никаких скрытых смертей.
 */
public final class LuckService implements Module, Listener {
    private final UnknownSMP plugin;
    private final Map<UUID, Integer> luck = new ConcurrentHashMap<>();
    private final Random rnd = new Random();
    private boolean needPlaytime;
    private long playtimeMs;

    public LuckService(UnknownSMP plugin) {
        this.plugin = plugin;
    }

    @Override public String id() { return "luck"; }
    @Override public String title() { return "Удача"; }
    @Override public String[] depends() { return new String[]{"state", "profiles"}; }

    @Override
    public void enable() {
        reload();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        // Загрузка сохранённых уровней.
        Tasks.timer(20 * 20, 20 * 20, this::ambientTick);
        plugin.commands().add("luck").perm("usmp.admin.luck").cat("Администрирование")
                .usage("/luck <игрок> <-3..3>").desc("Задать уровень удачи игроку").exec((s, a) -> {
                    if (a.length < 2) { Msg.error(s, "luck.usage", "Использование: /luck <игрок> <-3..3> (0 — обычная удача)"); return; }
                    int lvl;
                    try { lvl = Math.max(-3, Math.min(3, Integer.parseInt(a[1]))); } catch (NumberFormatException e) { Msg.error(s, "luck.num", "Нужно число от -3 до 3."); return; }
                    Tasks.then(plugin.profiles().findUuid(a[0]), u -> {
                        if (u == null) { Msg.error(s, "player.unknown", "Игрок не найден."); return; }
                        set(u, lvl);
                        Msg.ok(s, "luck.set", "Уровень удачи {p}: {l}", "p", a[0], "l", lvl);
                        plugin.logs().log(s instanceof Player pp ? pp : null, "luck", "set", null, a[0] + " = " + lvl);
                    }, null);
                }).tab((s, a) -> a.length == 1 ? ru.unknownsmp.core.Commands.players(a[0]) : java.util.List.of("-3", "-2", "-1", "0", "1", "2", "3"));
    }

    @Override
    public void reload() {
        var c = plugin.configs().get("events.yml");
        needPlaytime = c.getBoolean("luck.require-playtime", false);
        playtimeMs = c.getLong("luck.min-playtime-hours", 3) * 3_600_000L;
    }

    public int level(UUID u) {
        return luck.computeIfAbsent(u, k -> (int) plugin.state().getLong("luck." + k, 0));
    }

    public void set(UUID u, int lvl) {
        luck.put(u, lvl);
        plugin.state().set("luck." + u, lvl == 0 ? null : lvl);
    }

    /** Активно ли невезение прямо сейчас (учёт порога по времени в игре, если включён). */
    private boolean active(Player p) {
        int l = level(p.getUniqueId());
        if (l == 0) return false;
        if (l < 0 && needPlaytime) {
            var pr = plugin.profiles().get(p);
            if (pr == null || pr.playtime * 1000L < playtimeMs) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ Влияние на игру

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void blockDrop(BlockBreakEvent e) {
        Player p = e.getPlayer();
        int l = level(p.getUniqueId());
        if (l == 0 || !active(p)) return;
        Block b = e.getBlock();
        boolean ore = b.getType().name().endsWith("_ORE");
        if (l < 0 && ore && rnd.nextDouble() < 0.12 * -l) {
            // Невезение: руда иногда ломается «впустую».
            e.setDropItems(false);
            e.setExpToDrop(0);
            Tasks.sync(() -> { if (p.isOnline()) p.playSound(p.getLocation(), Sound.BLOCK_STONE_BREAK, 0.6f, 0.6f); });
            plugin.logs().log(p, "luck", "ore_fail", b.getLocation(), b.getType().name());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void mobDrop(EntityDeathEvent e) {
        Player p = e.getEntity().getKiller();
        if (p == null) return;
        int l = level(p.getUniqueId());
        if (l == 0 || !active(p)) return;
        if (l < 0 && rnd.nextDouble() < 0.10 * -l) e.getDrops().clear();
        if (l > 0) e.setDroppedExp((int) (e.getDroppedExp() * (1 + 0.15 * l)));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void enchant(EnchantItemEvent e) {
        Player p = e.getEnchanter();
        int l = level(p.getUniqueId());
        if (l == 0 || !active(p)) return;
        if (l < 0) {
            // Невезение: часть зачарований слабее.
            for (var en : e.getEnchantsToAdd().entrySet()) {
                int nv = Math.max(1, en.getValue() - (rnd.nextDouble() < 0.5 ? 1 : 0));
                e.getEnchantsToAdd().put(en.getKey(), nv);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void loot(LootGenerateEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        int l = level(p.getUniqueId());
        if (l == 0 || !active(p)) return;
        if (l < 0 && rnd.nextDouble() < 0.20 * -l && !e.getLoot().isEmpty()) {
            e.getLoot().remove(rnd.nextInt(e.getLoot().size()));
        }
    }

    private void ambientTick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!active(p) || !plugin.auth().isAuthed(p)) continue;
            int l = level(p.getUniqueId());
            if (l < 0 && rnd.nextDouble() < 0.04 * -l) {
                // Безобидная атмосферная неприятность: звук и частицы. Урона нет.
                p.playSound(p.getLocation(), rnd.nextBoolean() ? Sound.ENTITY_CREEPER_PRIMED : Sound.AMBIENT_CAVE, 0.7f, rnd.nextFloat() + 0.5f);
                p.spawnParticle(Particle.SMOKE, p.getLocation().add(0, 1, 0), 8, 0.3, 0.5, 0.3, 0.01);
                plugin.logs().log(p, "luck", "ambient", p.getLocation(), "level=" + l);
            } else if (l > 0 && rnd.nextDouble() < 0.03 * l) {
                p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.4f);
                p.spawnParticle(Particle.HAPPY_VILLAGER, p.getLocation().add(0, 1, 0), 6, 0.3, 0.5, 0.3);
            }
        }
    }

    /** Множитель удачи для других систем (события, редкий дроп): 1.0 — обычная. */
    public double multiplier(Player p) {
        int l = level(p.getUniqueId());
        if (l == 0) return 1.0;
        return l > 0 ? 1 + 0.2 * l : 1.0 / (1 + 0.25 * -l);
    }
}
