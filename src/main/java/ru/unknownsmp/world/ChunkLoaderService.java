package ru.unknownsmp.world;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import ru.unknownsmp.core.Keys;
import ru.unknownsmp.core.Module;
import ru.unknownsmp.core.Msg;
import ru.unknownsmp.core.Tasks;
import ru.unknownsmp.core.Text;
import ru.unknownsmp.core.UnknownSMP;
import ru.unknownsmp.db.Database;
import ru.unknownsmp.economy.EconomyService;
import ru.unknownsmp.gui.Items;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Загрузчики чанков: дорогой блок, который держит загруженной область вокруг себя (по умолчанию 3×3 чанка),
 * даже когда рядом никого нет. Работают воронки, печи, редстоун. Не больше max-per-player на игрока.
 */
public final class ChunkLoaderService implements Module, Listener {
    private record Loader(UUID owner, String world, int x, int y, int z) {
        String key() { return world + ":" + x + ":" + y + ":" + z; }
    }

    private final UnknownSMP plugin;
    private final Map<String, Loader> loaders = new ConcurrentHashMap<>();
    /** Сколько загрузчиков держат каждый чанк: world -> chunkKey -> count. */
    private final Map<String, Map<Long, Integer>> refs = new HashMap<>();
    private final Set<UUID> busy = ConcurrentHashMap.newKeySet();
    private boolean enabled = true;
    private long price = 1_000_000;
    private int maxPerPlayer = 2, radius = 1;

    public ChunkLoaderService(UnknownSMP plugin) {
        this.plugin = plugin;
    }

    @Override public String id() { return "chunkloaders"; }
    @Override public String title() { return "Загрузчики чанков"; }
    @Override public String[] depends() { return new String[]{"economy"}; }

    @Override
    public void enable() throws Exception {
        reload();
        plugin.db().queryBlocking(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT owner, world, x, y, z FROM chunk_loaders"); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Loader l = new Loader(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getInt(5));
                    loaders.put(l.key(), l);
                }
            }
            return null;
        });
        for (Loader l : loaders.values()) hold(l, true);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Tasks.timer(100, 100, this::particles);
        plugin.getLogger().info("Загрузчики чанков: " + loaders.size() + ".");
    }

    @Override
    public void disable() {
        for (Loader l : loaders.values()) hold(l, false);
    }

    @Override
    public void reload() {
        YamlConfiguration c = plugin.configs().get("economy.yml");
        enabled = c.getBoolean("chunk-loaders.enabled", true);
        price = Math.max(1, c.getLong("chunk-loaders.price", 1_000_000));
        maxPerPlayer = Math.max(1, c.getInt("chunk-loaders.max-per-player", 2));
        radius = Math.max(0, Math.min(2, c.getInt("chunk-loaders.radius-chunks", 1)));
    }

    public long price() { return price; }

    public int maxPerPlayer() { return maxPerPlayer; }

    public boolean enabled() { return enabled; }

    // ------------------------------------------------------------------ Билеты чанков

    private static long ck(int cx, int cz) { return ((long) cx << 32) ^ (cz & 0xffffffffL); }

    private void hold(Loader l, boolean add) {
        World w = Bukkit.getWorld(l.world());
        if (w == null) return;
        Map<Long, Integer> m = refs.computeIfAbsent(l.world(), k -> new HashMap<>());
        int bx = l.x() >> 4, bz = l.z() >> 4;
        for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
            int cx = bx + dx, cz = bz + dz;
            long k = ck(cx, cz);
            int n = m.getOrDefault(k, 0);
            if (add) {
                if (n == 0) w.addPluginChunkTicket(cx, cz, plugin);
                m.put(k, n + 1);
            } else if (n > 0) {
                if (n == 1) { w.removePluginChunkTicket(cx, cz, plugin); m.remove(k); }
                else m.put(k, n - 1);
            }
        }
    }

    private int countOf(UUID u) {
        int n = 0;
        for (Loader l : loaders.values()) if (l.owner().equals(u)) n++;
        return n;
    }

    private Loader at(Block b) { return loaders.get(b.getWorld().getName() + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ()); }

    // ------------------------------------------------------------------ Предмет

    public ItemStack item() {
        int side = radius * 2 + 1;
        ItemStack it = Items.of(Material.LODESTONE).name("&5&lЗагрузчик чанков")
                .lore("&7Держит загруженной область " + side + "×" + side + " чанков вокруг себя,", "&7даже когда рядом никого нет:",
                        "&7воронки, печи и редстоун работают.", "", "&8Не больше " + maxPerPlayer + " на игрока. Снять может только владелец.").glow().build();
        ItemMeta meta = it.getItemMeta();
        meta.getPersistentDataContainer().remove(Keys.MENU_ITEM);
        meta.getPersistentDataContainer().set(Keys.of("chunk_loader"), PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(meta);
        return it;
    }

    public static boolean isLoader(ItemStack it) {
        return it != null && it.getType() == Material.LODESTONE && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(Keys.of("chunk_loader"), PersistentDataType.BYTE);
    }

    public void buy(Player p) {
        if (!enabled) { Msg.error(p, "loader.disabled", "Загрузчики чанков отключены."); return; }
        if (!plugin.economy().tradeAllowed(p)) return;
        if (countOf(p.getUniqueId()) >= maxPerPlayer) {
            Msg.error(p, "loader.limit", "У вас уже максимум загрузчиков ({n}). Снимите старый, чтобы поставить новый.", "n", maxPerPlayer);
            return;
        }
        if (ru.unknownsmp.market.MailService.freeSlots(p) < 1) { Msg.error(p, "shop.no-space", "Недостаточно места в инвентаре (нужно {n} свободных ячеек).", "n", 1); return; }
        UUID u = p.getUniqueId();
        if (!busy.add(u)) return;
        Tasks.then(plugin.db().tx(c -> EconomyService.debit(c, u, price, "chunk_loader_buy", null)), bal -> {
            busy.remove(u);
            plugin.economy().apply(u, bal);
            plugin.mail().giveOrMail(p, item(), "shop");
            Msg.ok(p, "loader.bought", "Куплен загрузчик чанков за {c}. Поставьте его в нужном месте.", "c", Text.money(price));
            plugin.logs().log(p, "economy", "loader_buy", p.getLocation(), String.valueOf(price));
        }, err -> {
            busy.remove(u);
            if (err instanceof Database.TxAbort ab) Msg.error(p, ab.key, ab.def);
            else Msg.error(p, "shop.error", "Покупка не выполнена. Деньги не списаны.");
        });
    }

    // ------------------------------------------------------------------ Установка и снятие

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void place(BlockPlaceEvent e) {
        if (!isLoader(e.getItemInHand())) return;
        Player p = e.getPlayer();
        if (!enabled) { e.setCancelled(true); Msg.error(p, "loader.disabled", "Загрузчики чанков отключены."); return; }
        if (plugin.lobby().isLobby(p.getWorld()) || (plugin.duels() != null && plugin.duels().isDuelWorld(p.getWorld()))) {
            e.setCancelled(true);
            Msg.error(p, "loader.world", "Загрузчик можно поставить только в мирах выживания.");
            return;
        }
        if (countOf(p.getUniqueId()) >= maxPerPlayer) {
            e.setCancelled(true);
            Msg.error(p, "loader.limit", "У вас уже максимум загрузчиков ({n}). Снимите старый, чтобы поставить новый.", "n", maxPerPlayer);
            return;
        }
        Block b = e.getBlockPlaced();
        Loader l = new Loader(p.getUniqueId(), b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
        loaders.put(l.key(), l);
        hold(l, true);
        plugin.db().tx(c -> Database.update(c, "INSERT INTO chunk_loaders(owner, world, x, y, z, placed_at) VALUES (?,?,?,?,?,?)",
                l.owner().toString(), l.world(), l.x(), l.y(), l.z(), System.currentTimeMillis()));
        Msg.ok(p, "loader.placed", "Загрузчик чанков работает: область {s}×{s} чанков остаётся загруженной.", "s", radius * 2 + 1);
        Msg.sound(p, Sound.BLOCK_BEACON_ACTIVATE, 0.8f, 1.2f);
        plugin.logs().log(p, "world", "loader_place", b.getLocation(), "");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void brk(BlockBreakEvent e) {
        Loader l = at(e.getBlock());
        if (l == null) return;
        Player p = e.getPlayer();
        if (!l.owner().equals(p.getUniqueId()) && !plugin.roles().has(p, "usmp.admin.build")) {
            e.setCancelled(true);
            Msg.actionBar(p, "&cЭто чужой загрузчик чанков.");
            return;
        }
        e.setDropItems(false);
        remove(l);
        Player owner = Bukkit.getPlayer(l.owner());
        if (owner != null) plugin.mail().giveOrMail(owner, item(), "loader");
        else plugin.db().tx(c -> { ru.unknownsmp.market.MailService.insert(c, l.owner(), item(), "loader"); return null; });
        Msg.ok(p, "loader.removed", "Загрузчик снят и возвращён владельцу.");
        plugin.logs().log(p, "world", "loader_remove", e.getBlock().getLocation(), "owner=" + l.owner());
    }

    private void remove(Loader l) {
        if (loaders.remove(l.key()) == null) return;
        hold(l, false);
        plugin.db().tx(c -> Database.update(c, "DELETE FROM chunk_loaders WHERE world=? AND x=? AND y=? AND z=?", l.world(), l.x(), l.y(), l.z()));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void explode(EntityExplodeEvent e) { e.blockList().removeIf(b -> at(b) != null); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void explode2(BlockExplodeEvent e) { e.blockList().removeIf(b -> at(b) != null); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void piston(BlockPistonExtendEvent e) { for (Block b : e.getBlocks()) if (at(b) != null) { e.setCancelled(true); return; } }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void piston2(BlockPistonRetractEvent e) { for (Block b : e.getBlocks()) if (at(b) != null) { e.setCancelled(true); return; } }

    @EventHandler(priority = EventPriority.HIGH)
    public void craft(PrepareItemCraftEvent e) {
        for (ItemStack it : e.getInventory().getMatrix()) if (isLoader(it)) { e.getInventory().setResult(null); return; }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void info(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND || e.getClickedBlock() == null) return;
        Loader l = at(e.getClickedBlock());
        if (l == null) return;
        // Компас на магнетит не «привязываем» — это загрузчик, а не обычный магнетит.
        e.setCancelled(true);
        String owner = Bukkit.getOfflinePlayer(l.owner()).getName();
        Msg.send(e.getPlayer(), "loader.info", "&5Загрузчик чанков &7— владелец &f{o}&7, держит {s}×{s} чанков.",
                "o", owner == null ? "?" : owner, "s", radius * 2 + 1);
    }

    private void particles() {
        for (Loader l : loaders.values()) {
            World w = Bukkit.getWorld(l.world());
            if (w == null || !w.isChunkLoaded(l.x() >> 4, l.z() >> 4)) continue;
            // Блок убрали в обход игры (WorldEdit и т.п.) — снимаем загрузчик.
            if (w.getBlockAt(l.x(), l.y(), l.z()).getType() != Material.LODESTONE) { remove(l); continue; }
            w.spawnParticle(Particle.PORTAL, l.x() + 0.5, l.y() + 1.2, l.z() + 0.5, 12, 0.3, 0.3, 0.3, 0.2);
        }
    }

    /** Для /usmp lag: сколько чанков держат загрузчики. */
    public int heldChunks() {
        int n = 0;
        for (Map<Long, Integer> m : refs.values()) n += m.size();
        return n;
    }
}
