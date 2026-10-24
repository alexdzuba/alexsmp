package ru.unknownsmp.admin;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import ru.unknownsmp.core.Module;
import ru.unknownsmp.core.Msg;
import ru.unknownsmp.core.Tasks;
import ru.unknownsmp.core.Text;
import ru.unknownsmp.core.UnknownSMP;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Защита от лаг-машин:
 *  - предел существ в чанке для размножения, яиц, спавнеров и построенных големов;
 *  - предел воронок в чанке;
 *  - детектор быстрых редстоун-схем: чанк, где сигнал меняется слишком часто несколько секунд подряд,
 *    «замораживается» на время (сигнал перестаёт меняться, поршни не работают), администрация получает уведомление;
 *  - /usmp lag — самые нагруженные чанки с телепортом.
 */
public final class LagGuard implements Module, Listener {
    private record ChunkRef(String world, int x, int z) {
        Location center() {
            World w = Bukkit.getWorld(world);
            if (w == null) return null;
            int bx = (x << 4) + 8, bz = (z << 4) + 8;
            return new Location(w, bx + 0.5, w.getHighestBlockYAt(bx, bz) + 1, bz + 0.5);
        }
        String describe() { return world + " " + ((x << 4) + 8) + ", " + ((z << 4) + 8); }
    }

    private static final Set<CreatureSpawnEvent.SpawnReason> LIMITED = EnumSet.of(
            CreatureSpawnEvent.SpawnReason.BREEDING, CreatureSpawnEvent.SpawnReason.SPAWNER_EGG, CreatureSpawnEvent.SpawnReason.DISPENSE_EGG,
            CreatureSpawnEvent.SpawnReason.EGG, CreatureSpawnEvent.SpawnReason.SPAWNER, CreatureSpawnEvent.SpawnReason.BUILD_IRONGOLEM,
            CreatureSpawnEvent.SpawnReason.BUILD_SNOWMAN, CreatureSpawnEvent.SpawnReason.SLIME_SPLIT, CreatureSpawnEvent.SpawnReason.VILLAGE_DEFENSE,
            CreatureSpawnEvent.SpawnReason.TRIAL_SPAWNER, CreatureSpawnEvent.SpawnReason.OCELOT_BABY, CreatureSpawnEvent.SpawnReason.FROZEN);

    private final UnknownSMP plugin;
    private boolean enabled = true;
    private int maxEntities = 60, maxSameType = 30, maxHoppers = 64, redstoneLimit = 400, strikesNeeded = 5;
    private long freezeMs = 60_000;
    /** Счётчики изменений редстоуна в текущую секунду: world -> chunkKey -> count. */
    private final Map<String, Map<Long, int[]>> counts = new HashMap<>();
    private final Map<String, Map<Long, Integer>> strikes = new HashMap<>();
    private final Map<String, Map<Long, Long>> frozen = new ConcurrentHashMap<>();
    private final Map<String, Map<Long, Integer>> lastSecond = new HashMap<>();
    private final Map<UUID, List<ChunkRef>> lastReport = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastWarn = new ConcurrentHashMap<>();

    public LagGuard(UnknownSMP plugin) {
        this.plugin = plugin;
    }

    @Override public String id() { return "lagguard"; }
    @Override public String title() { return "Защита от лаг-машин"; }

    @Override
    public void enable() {
        reload();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Tasks.timer(20, 20, this::second);
    }

    @Override
    public void reload() {
        YamlConfiguration c = plugin.configs().get("performance.yml");
        enabled = c.getBoolean("lag-guard.enabled", true);
        maxEntities = Math.max(10, c.getInt("lag-guard.max-entities-per-chunk", 60));
        maxSameType = Math.max(5, c.getInt("lag-guard.max-same-type-per-chunk", 30));
        maxHoppers = Math.max(1, c.getInt("lag-guard.max-hoppers-per-chunk", 64));
        redstoneLimit = Math.max(50, c.getInt("lag-guard.redstone-changes-per-second", 400));
        strikesNeeded = Math.max(1, c.getInt("lag-guard.redstone-seconds", 5));
        freezeMs = Math.max(5, c.getLong("lag-guard.redstone-freeze-seconds", 60)) * 1000L;
    }

    private static long key(int cx, int cz) { return ((long) cx << 32) ^ (cz & 0xffffffffL); }

    private static int kx(long k) { return (int) (k >> 32); }

    private static int kz(long k) { return (int) k; }

    // ------------------------------------------------------------------ Существа

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void spawn(CreatureSpawnEvent e) {
        if (!enabled || !LIMITED.contains(e.getSpawnReason())) return;
        Chunk ch = e.getLocation().getChunk();
        EntityType type = e.getEntityType();
        int all = 0, same = 0;
        for (Entity en : ch.getEntities()) {
            if (!(en instanceof LivingEntity) || en instanceof Player) continue;
            all++;
            if (en.getType() == type) same++;
        }
        if (all >= maxEntities || same >= maxSameType) {
            e.setCancelled(true);
            if (e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.BREEDING || e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.SPAWNER_EGG) {
                for (Player p : e.getLocation().getNearbyPlayers(8)) warn(p, "&cВ этом чанке слишком много существ (" + all + "). Расселите их.");
            }
        }
    }

    // ------------------------------------------------------------------ Воронки

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void place(BlockPlaceEvent e) {
        if (!enabled || e.getBlockPlaced().getType() != Material.HOPPER) return;
        if (plugin.roles().has(e.getPlayer(), "usmp.admin.build")) return;
        int n = 0;
        for (BlockState st : e.getBlockPlaced().getChunk().getTileEntities(false)) if (st.getType() == Material.HOPPER) n++;
        if (n > maxHoppers) {
            e.setCancelled(true);
            warn(e.getPlayer(), "&cВ одном чанке не больше " + maxHoppers + " воронок.");
        }
    }

    private void warn(Player p, String text) {
        long now = System.currentTimeMillis();
        Long last = lastWarn.get(p.getUniqueId());
        if (last != null && now - last < 2000) return;
        lastWarn.put(p.getUniqueId(), now);
        Msg.actionBar(p, text);
    }

    // ------------------------------------------------------------------ Редстоун

    private boolean isFrozen(Block b) {
        Map<Long, Long> m = frozen.get(b.getWorld().getName());
        if (m == null || m.isEmpty()) return false;
        Long until = m.get(key(b.getX() >> 4, b.getZ() >> 4));
        return until != null && until > System.currentTimeMillis();
    }

    private void count(Block b) {
        counts.computeIfAbsent(b.getWorld().getName(), k -> new HashMap<>())
                .computeIfAbsent(key(b.getX() >> 4, b.getZ() >> 4), k -> new int[1])[0]++;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void redstone(BlockRedstoneEvent e) {
        if (!enabled) return;
        Block b = e.getBlock();
        if (isFrozen(b)) { e.setNewCurrent(e.getOldCurrent()); return; }
        count(b);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void pistonOut(BlockPistonExtendEvent e) {
        if (!enabled) return;
        if (isFrozen(e.getBlock())) { e.setCancelled(true); return; }
        count(e.getBlock());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void pistonIn(BlockPistonRetractEvent e) {
        if (!enabled) return;
        if (isFrozen(e.getBlock())) { e.setCancelled(true); return; }
        count(e.getBlock());
    }

    private void second() {
        long now = System.currentTimeMillis();
        lastSecond.clear();
        for (var w : counts.entrySet()) {
            Map<Long, Integer> snap = new HashMap<>();
            Map<Long, Integer> st = strikes.computeIfAbsent(w.getKey(), k -> new HashMap<>());
            for (var c : w.getValue().entrySet()) {
                int n = c.getValue()[0];
                snap.put(c.getKey(), n);
                if (n >= redstoneLimit) {
                    int s = st.merge(c.getKey(), 1, Integer::sum);
                    if (s >= strikesNeeded) {
                        st.remove(c.getKey());
                        freeze(w.getKey(), c.getKey(), n, now);
                    }
                } else {
                    st.remove(c.getKey());
                }
            }
            lastSecond.put(w.getKey(), snap);
        }
        counts.clear();
        for (Map<Long, Long> m : frozen.values()) m.values().removeIf(until -> until <= now);
    }

    private void freeze(String world, long k, int perSecond, long now) {
        frozen.computeIfAbsent(world, x -> new ConcurrentHashMap<>()).put(k, now + freezeMs);
        ChunkRef ref = new ChunkRef(world, kx(k), kz(k));
        String text = "&c[Лаг-защита] &fРедстоун заморожен на " + (freezeMs / 1000) + " сек в чанке &e" + ref.describe()
                + " &7(" + perSecond + " изменений/сек)";
        plugin.getLogger().warning(Text.strip(text));
        plugin.logs().sys("lag", "redstone_freeze", ref.center(), perSecond + "/s");
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (plugin.roles().has(p, "usmp.admin.monitor")) Msg.raw(p, text);
            else if (p.getWorld().getName().equals(world) && (p.getLocation().getBlockX() >> 4) == ref.x() && (p.getLocation().getBlockZ() >> 4) == ref.z())
                Msg.raw(p, "&cСлишком быстрая редстоун-схема — она временно остановлена, чтобы сервер не лагал.");
        }
    }

    // ------------------------------------------------------------------ /usmp lag

    public void command(CommandSender s, String[] r) {
        if (r.length >= 2 && r[0].equalsIgnoreCase("tp") && s instanceof Player p) {
            List<ChunkRef> list = lastReport.get(p.getUniqueId());
            int i;
            try { i = Integer.parseInt(r[1]) - 1; } catch (NumberFormatException e) { i = -1; }
            if (list == null || i < 0 || i >= list.size()) { Msg.error(s, "lag.tp-bad", "Сначала /usmp lag, затем /usmp lag tp <номер>."); return; }
            Location l = list.get(i).center();
            if (l != null) p.teleport(l);
            return;
        }
        if (r.length >= 1 && r[0].equalsIgnoreCase("unfreeze")) {
            frozen.clear();
            Msg.ok(s, "lag.unfrozen", "Все замороженные чанки разморожены.");
            return;
        }
        double[] tps = Bukkit.getTPS();
        Msg.raw(s, "&8--- &6Нагрузка сервера &8---");
        Msg.raw(s, String.format("&7TPS: &f%.1f / %.1f / %.1f &7| MSPT: &f%.1f", tps[0], tps[1], tps[2], Bukkit.getAverageTickTime()));
        List<Object[]> rows = new ArrayList<>();
        for (World w : Bukkit.getWorlds()) {
            Map<Long, Integer> rs = lastSecond.getOrDefault(w.getName(), Map.of());
            for (Chunk ch : w.getLoadedChunks()) {
                Entity[] ents = ch.getEntities();
                int living = 0, items = 0;
                for (Entity en : ents) {
                    if (en instanceof Item) items++;
                    else if (en instanceof LivingEntity && !(en instanceof Player)) living++;
                }
                int tiles = ch.getTileEntities(false).length;
                int red = rs.getOrDefault(key(ch.getX(), ch.getZ()), 0);
                int score = living * 3 + items + tiles + red / 5;
                if (score < 30) continue;
                rows.add(new Object[]{new ChunkRef(w.getName(), ch.getX(), ch.getZ()), score, living, items, tiles, red});
            }
        }
        rows.sort((a, b) -> Integer.compare((int) b[1], (int) a[1]));
        List<ChunkRef> refs = new ArrayList<>();
        for (int i = 0; i < Math.min(10, rows.size()); i++) {
            Object[] o = rows.get(i);
            ChunkRef ref = (ChunkRef) o[0];
            refs.add(ref);
            Msg.raw(s, "&e" + (i + 1) + ". &f" + ref.describe() + " &7— мобы: &f" + o[2] + " &7предметы: &f" + o[3]
                    + " &7блоки-сущности: &f" + o[4] + " &7редстоун/сек: &f" + o[5]);
        }
        if (rows.isEmpty()) Msg.raw(s, "&aНагруженных чанков не найдено.");
        int fz = 0;
        for (Map<Long, Long> m : frozen.values()) fz += m.size();
        if (fz > 0) Msg.raw(s, "&cЗаморожено чанков с редстоуном: " + fz + " &7(/usmp lag unfreeze)");
        if (plugin.chunkLoaders() != null) Msg.raw(s, "&7Чанков держат загрузчики игроков: &f" + plugin.chunkLoaders().heldChunks());
        if (s instanceof Player p) {
            lastReport.put(p.getUniqueId(), refs);
            if (!refs.isEmpty()) Msg.raw(s, "&7Телепорт: &e/usmp lag tp <номер>");
        }
    }
}
