package ru.unknownsmp.world;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.structure.Structure;
import org.bukkit.util.BlockVector;
import ru.unknownsmp.core.Msg;
import ru.unknownsmp.core.Tasks;
import ru.unknownsmp.core.UnknownSMP;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Готовые постройки: загрузка и сохранение структур Minecraft (.nbt) из папки plugins/UnknownSMP/structures.
 * Структуры из structures.yml ставятся автоматически один раз (повторно — при увеличении version), остальные — командой
 * /usmp structure place. Чанки подгружаются асинхронно, поэтому большие постройки не вешают сервер.
 */
public final class StructureTools {
    private final UnknownSMP plugin;
    private final File dir;
    private final Map<UUID, Location[]> selection = new ConcurrentHashMap<>();

    public StructureTools(UnknownSMP plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "structures");
        dir.mkdirs();
    }

    public File dir() { return dir; }

    public List<String> files() {
        List<String> out = new ArrayList<>();
        File[] fs = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".nbt"));
        if (fs != null) for (File f : fs) out.add(f.getName());
        out.sort(String::compareTo);
        return out;
    }

    private File file(String name) {
        String n = name.replace("/", "").replace("\\", "").replace("..", "");
        if (!n.toLowerCase().endsWith(".nbt")) n += ".nbt";
        return new File(dir, n);
    }

    private static StructureRotation rotation(int deg) {
        return switch (((deg % 360) + 360) % 360) {
            case 90 -> StructureRotation.CLOCKWISE_90;
            case 180 -> StructureRotation.CLOCKWISE_180;
            case 270 -> StructureRotation.COUNTERCLOCKWISE_90;
            default -> StructureRotation.NONE;
        };
    }

    private static Mirror mirror(String s) {
        try { return s == null ? Mirror.NONE : Mirror.valueOf(s.toUpperCase()); } catch (IllegalArgumentException e) { return Mirror.NONE; }
    }

    /** Поставить структуру. origin — минимальный угол (или центр, если center=true). */
    public CompletableFuture<String> place(String fileName, Location origin, int rot, String mirror, boolean entities, boolean center) {
        CompletableFuture<String> res = new CompletableFuture<>();
        File f = file(fileName);
        if (!f.isFile()) { res.complete("Файл не найден: " + f.getName() + " (папка structures)"); return res; }
        World w = origin.getWorld();
        if (w == null) { res.complete("Мир не найден"); return res; }
        Structure st;
        try {
            st = Bukkit.getStructureManager().loadStructure(f);
        } catch (Exception e) {
            res.complete("Не удалось прочитать " + f.getName() + ": " + e.getMessage());
            return res;
        }
        BlockVector size = st.getSize();
        int span = Math.max(size.getBlockX(), size.getBlockZ());
        Location o = origin.clone();
        if (center) o.subtract(size.getBlockX() / 2.0, 0, size.getBlockZ() / 2.0);
        int c0x = (o.getBlockX() - 2) >> 4, c1x = (o.getBlockX() + span + 2) >> 4, c0z = (o.getBlockZ() - 2) >> 4, c1z = (o.getBlockZ() + span + 2) >> 4;
        List<CompletableFuture<?>> futs = new ArrayList<>();
        for (int x = c0x; x <= c1x; x++) for (int z = c0z; z <= c1z; z++) {
            w.addPluginChunkTicket(x, z, plugin);
            futs.add(w.getChunkAtAsync(x, z, true));
        }
        final Structure fst = st;
        CompletableFuture.allOf(futs.toArray(new CompletableFuture[0])).whenComplete((v, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                fst.place(o, entities, rotation(rot), mirror(mirror), 0, 1f, new Random());
                res.complete("OK");
            } catch (Throwable t) {
                res.complete("Ошибка постройки: " + t);
            } finally {
                for (int x = c0x; x <= c1x; x++) for (int z = c0z; z <= c1z; z++) w.removePluginChunkTicket(x, z, plugin);
            }
        }));
        return res;
    }

    /** Сохранить область между двумя точками в файл структуры. */
    public String save(String name, Location a, Location b, boolean entities) {
        try {
            Structure st = Bukkit.getStructureManager().createStructure();
            Location lo = new Location(a.getWorld(), Math.min(a.getBlockX(), b.getBlockX()), Math.min(a.getBlockY(), b.getBlockY()), Math.min(a.getBlockZ(), b.getBlockZ()));
            Location hi = new Location(a.getWorld(), Math.max(a.getBlockX(), b.getBlockX()) + 1, Math.max(a.getBlockY(), b.getBlockY()) + 1, Math.max(a.getBlockZ(), b.getBlockZ()) + 1);
            st.fill(lo, hi, entities);
            Bukkit.getStructureManager().saveStructure(file(name), st);
            BlockVector s = st.getSize();
            return "OK " + s.getBlockX() + "x" + s.getBlockY() + "x" + s.getBlockZ();
        } catch (Exception e) {
            return "Ошибка сохранения: " + e.getMessage();
        }
    }

    public void select(Player p, int idx, Location l) {
        Location[] sel = selection.computeIfAbsent(p.getUniqueId(), k -> new Location[2]);
        sel[idx] = l.getBlock().getLocation();
    }

    public Location[] selection(Player p) { return selection.get(p.getUniqueId()); }

    /** Автоматическая постановка структур из structures.yml (по одному разу на версию). */
    public void autoPlace() {
        var cfg = plugin.configs().get("structures.yml");
        ConfigurationSection s = cfg.getConfigurationSection("structures");
        if (s == null) return;
        for (String id : s.getKeys(false)) {
            ConfigurationSection e = s.getConfigurationSection(id);
            if (e == null) continue;
            long ver = e.getLong("version", 1);
            String key = "structure." + id;
            if (plugin.state().getLong(key, 0) >= ver) continue;
            String wn = e.getString("world", plugin.lobbyWorldName());
            World w = Bukkit.getWorld(wn);
            if (w == null) { plugin.getLogger().warning("[Структуры] " + id + ": мир " + wn + " не найден"); continue; }
            String[] p = e.getString("location", "0;100;0").split(";");
            Location loc;
            try {
                loc = new Location(w, Double.parseDouble(p[0].trim()), Double.parseDouble(p[1].trim()), Double.parseDouble(p[2].trim()));
            } catch (Exception ex) {
                plugin.getLogger().warning("[Структуры] " + id + ": неверная location");
                continue;
            }
            plugin.getLogger().info("[Структуры] Ставлю " + id + " (" + e.getString("file") + ") в " + wn + " " + e.getString("location"));
            place(e.getString("file", id), loc, e.getInt("rotation", 0), e.getString("mirror", "NONE"), e.getBoolean("entities", false), e.getBoolean("center", false))
                    .thenAccept(r -> Tasks.sync(() -> {
                        if (r.equals("OK")) {
                            plugin.state().set(key, ver);
                            plugin.getLogger().info("[Структуры] " + id + " построена.");
                        } else {
                            plugin.getLogger().warning("[Структуры] " + id + ": " + r);
                        }
                    }));
        }
    }

    public static void send(org.bukkit.command.CommandSender s, String res) {
        if (res.startsWith("OK")) Msg.ok(s, "struct.ok", "Готово. {r}", "r", res.length() > 2 ? res.substring(3) : "");
        else Msg.error(s, "struct.err", "{r}", "r", res);
    }
}
