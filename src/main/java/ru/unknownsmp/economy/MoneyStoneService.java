package ru.unknownsmp.economy;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
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
import ru.unknownsmp.gui.Items;
import ru.unknownsmp.gui.Menu;
import ru.unknownsmp.gui.PagedMenu;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Камни заработка (/secretmoney). Игрок покупает камень, ставит его как блок, и камень регулярно приносит деньги
 * владельцу. Каждый камень окупается примерно за час. Сломать камень может только владелец — он возвращается предметом.
 */
public final class MoneyStoneService implements Module, Listener {
    public record Tier(String id, String name, Material block, long price, long income) {}

    private static final class Stone {
        final UUID owner;
        final String tier, world;
        final int x, y, z;
        long earned;

        Stone(UUID owner, String tier, String world, int x, int y, int z, long earned) {
            this.owner = owner; this.tier = tier; this.world = world; this.x = x; this.y = y; this.z = z; this.earned = earned;
        }

        String key() { return world + ":" + x + ":" + y + ":" + z; }
    }

    private final UnknownSMP plugin;
    private final Map<String, Tier> tiers = new LinkedHashMap<>();
    private final Map<String, Stone> stones = new ConcurrentHashMap<>();
    private final Set<UUID> busy = ConcurrentHashMap.newKeySet();
    private boolean enabled = true, requireOnline = true;
    private int maxPerPlayer = 10, paySeconds = 60;
    private org.bukkit.scheduler.BukkitTask payTask;

    public MoneyStoneService(UnknownSMP plugin) {
        this.plugin = plugin;
    }

    @Override public String id() { return "moneystones"; }
    @Override public String title() { return "Камни заработка"; }
    @Override public String[] depends() { return new String[]{"economy"}; }

    @Override
    public void enable() throws Exception {
        reload();
        plugin.db().queryBlocking(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT owner, tier, world, x, y, z, earned FROM money_stones"); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Stone s = new Stone(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getLong(7));
                    stones.put(s.key(), s);
                }
            }
            return null;
        });
        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.commands().add("secretmoney").player().perm("usmp.player.market").module("moneystones").cat("Экономика")
                .usage("/secretmoney").desc("Камни заработка").exec((s, a) -> open((Player) s, null));
    }

    @Override
    public void reload() {
        YamlConfiguration c = plugin.configs().get("economy.yml");
        enabled = c.getBoolean("money-stones.enabled", true);
        maxPerPlayer = Math.max(1, c.getInt("money-stones.max-per-player", 10));
        paySeconds = Math.max(1, c.getInt("money-stones.pay-every-seconds", 60));
        requireOnline = c.getBoolean("money-stones.require-online", true);
        tiers.clear();
        ConfigurationSection sec = c.getConfigurationSection("money-stones.tiers");
        if (sec != null) {
            for (String id : sec.getKeys(false)) {
                Material m = Material.matchMaterial(sec.getString(id + ".block", "STONE"));
                if (m == null || !m.isBlock()) m = Material.STONE;
                long price = Math.max(1, sec.getLong(id + ".price", 10_000));
                tiers.put(id, new Tier(id, sec.getString(id + ".name", id), m, price, Math.max(0, sec.getLong(id + ".income", price / 60))));
            }
        }
        if (tiers.isEmpty()) {
            for (Tier t : List.of(
                    new Tier("coal", "&8Угольный камень заработка", Material.COAL_BLOCK, 30_000, 500),
                    new Tier("iron", "&fЖелезный камень заработка", Material.IRON_BLOCK, 60_000, 1_000),
                    new Tier("gold", "&6Золотой камень заработка", Material.GOLD_BLOCK, 120_000, 2_000),
                    new Tier("diamond", "&bАлмазный камень заработка", Material.DIAMOND_BLOCK, 240_000, 4_000),
                    new Tier("emerald", "&aИзумрудный камень заработка", Material.EMERALD_BLOCK, 360_000, 6_000),
                    new Tier("netherite", "&5Незеритовый камень заработка", Material.NETHERITE_BLOCK, 500_000, 8_350))) tiers.put(t.id(), t);
        }
        if (payTask != null) payTask.cancel();
        payTask = Tasks.timer(20L * paySeconds, 20L * paySeconds, this::payout);
    }

    /** Окупаемость в минутах. */
    private long paybackMinutes(Tier t) {
        if (t.income() <= 0) return 0;
        double perMinute = t.income() * 60.0 / paySeconds;
        return Math.round(t.price() / perMinute);
    }

    private long perMinute(Tier t) { return Math.round(t.income() * 60.0 / paySeconds); }

    // ------------------------------------------------------------------ Предмет

    private static org.bukkit.NamespacedKey key() { return Keys.of("money_stone"); }

    public ItemStack item(Tier t) {
        ItemStack it = Items.of(t.block()).name(t.name())
                .lore("&7Приносит &6" + Text.money(perMinute(t)) + " &7в минуту", "&7Окупается примерно за " + paybackMinutes(t) + " мин",
                        "", "&eПоставьте блок — и он начнёт работать", "&8Сломать может только владелец").glow().build();
        ItemMeta meta = it.getItemMeta();
        meta.getPersistentDataContainer().remove(Keys.MENU_ITEM);
        meta.getPersistentDataContainer().set(key(), PersistentDataType.STRING, t.id());
        it.setItemMeta(meta);
        return it;
    }

    public static String tierOf(ItemStack it) {
        if (it == null || it.getType().isAir() || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(key(), PersistentDataType.STRING);
    }

    private int countOf(UUID u) {
        int n = 0;
        for (Stone s : stones.values()) if (s.owner.equals(u)) n++;
        return n;
    }

    private Stone at(Block b) { return stones.get(b.getWorld().getName() + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ()); }

    // ------------------------------------------------------------------ Установка и снятие

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void place(BlockPlaceEvent e) {
        String tierId = tierOf(e.getItemInHand());
        if (tierId == null) return;
        Player p = e.getPlayer();
        Tier t = tiers.get(tierId);
        if (!enabled || t == null) { e.setCancelled(true); Msg.error(p, "stone.disabled", "Камни заработка сейчас отключены."); return; }
        if (plugin.lobby().isLobby(p.getWorld()) || (plugin.duels() != null && plugin.duels().isDuelWorld(p.getWorld()))) {
            e.setCancelled(true);
            Msg.error(p, "stone.world", "Камень можно поставить только в мире выживания.");
            return;
        }
        if (countOf(p.getUniqueId()) >= maxPerPlayer) {
            e.setCancelled(true);
            Msg.error(p, "stone.limit", "У вас уже максимум камней заработка ({n}).", "n", maxPerPlayer);
            return;
        }
        Block b = e.getBlockPlaced();
        Stone s = new Stone(p.getUniqueId(), tierId, b.getWorld().getName(), b.getX(), b.getY(), b.getZ(), 0);
        stones.put(s.key(), s);
        plugin.db().tx(c -> Database.update(c, "INSERT INTO money_stones(owner, tier, world, x, y, z, placed_at, earned) VALUES (?,?,?,?,?,?,?,0)",
                s.owner.toString(), s.tier, s.world, s.x, s.y, s.z, System.currentTimeMillis()));
        Msg.ok(p, "stone.placed", "Камень заработка установлен: +{a} в минуту.", "a", Text.money(perMinute(t)));
        b.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, b.getLocation().add(0.5, 1, 0.5), 15, 0.4, 0.4, 0.4);
        plugin.logs().log(p, "economy", "stone_place", b.getLocation(), tierId);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void brk(BlockBreakEvent e) {
        Stone s = at(e.getBlock());
        if (s == null) return;
        Player p = e.getPlayer();
        if (!s.owner.equals(p.getUniqueId()) && !plugin.roles().has(p, "usmp.admin.build")) {
            e.setCancelled(true);
            Msg.actionBar(p, "&cЭто чужой камень заработка.");
            return;
        }
        e.setDropItems(false);
        e.setExpToDrop(0);
        removeStone(s);
        Tier t = tiers.get(s.tier);
        if (t != null) {
            Player owner = Bukkit.getPlayer(s.owner);
            if (owner != null) plugin.mail().giveOrMail(owner, item(t), "stone");
            else plugin.db().tx(c -> { ru.unknownsmp.market.MailService.insert(c, s.owner, item(t), "stone"); return null; });
        }
        Msg.ok(p, "stone.removed", "Камень заработка снят и возвращён владельцу.");
        plugin.logs().log(p, "economy", "stone_remove", e.getBlock().getLocation(), s.tier + " owner=" + s.owner);
    }

    private void removeStone(Stone s) {
        stones.remove(s.key());
        plugin.db().tx(c -> Database.update(c, "DELETE FROM money_stones WHERE world=? AND x=? AND y=? AND z=?", s.world, s.x, s.y, s.z));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void explode(EntityExplodeEvent e) { e.blockList().removeIf(b -> at(b) != null); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void explode2(BlockExplodeEvent e) { e.blockList().removeIf(b -> at(b) != null); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void piston(BlockPistonExtendEvent e) { for (Block b : e.getBlocks()) if (at(b) != null) { e.setCancelled(true); return; } }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void piston2(BlockPistonRetractEvent e) { for (Block b : e.getBlocks()) if (at(b) != null) { e.setCancelled(true); return; } }

    /** Камень нельзя разобрать на ресурсы (например, незеритовый блок на слитки). */
    @EventHandler(priority = EventPriority.HIGH)
    public void craft(PrepareItemCraftEvent e) {
        for (ItemStack it : e.getInventory().getMatrix()) if (tierOf(it) != null) { e.getInventory().setResult(null); return; }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void info(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND || e.getClickedBlock() == null) return;
        if (!e.getPlayer().getInventory().getItemInMainHand().getType().isAir()) return;
        Stone s = at(e.getClickedBlock());
        if (s == null) return;
        Tier t = tiers.get(s.tier);
        String owner = Bukkit.getOfflinePlayer(s.owner).getName();
        Msg.send(e.getPlayer(), "stone.info", "&6{n}&7: владелец &f{o}&7, приносит &6{a}/мин&7, заработал всего &6{e}",
                "n", t == null ? s.tier : Text.strip(t.name()), "o", owner == null ? "?" : owner,
                "a", t == null ? "0" : Text.money(perMinute(t)), "e", Text.money(s.earned));
    }

    // ------------------------------------------------------------------ Выплаты

    private void payout() {
        if (!enabled || stones.isEmpty()) return;
        Map<UUID, List<Stone>> byOwner = new HashMap<>();
        for (Stone s : new ArrayList<>(stones.values())) {
            Tier t = tiers.get(s.tier);
            if (t == null || t.income() <= 0) continue;
            World w = Bukkit.getWorld(s.world);
            if (w == null) continue;
            // Блок убрали в обход игры (например, WorldEdit) — камень больше не работает.
            if (w.isChunkLoaded(s.x >> 4, s.z >> 4) && w.getBlockAt(s.x, s.y, s.z).getType() != t.block()) { removeStone(s); continue; }
            if (requireOnline && Bukkit.getPlayer(s.owner) == null) continue;
            byOwner.computeIfAbsent(s.owner, k -> new ArrayList<>()).add(s);
        }
        for (var en : byOwner.entrySet()) {
            UUID u = en.getKey();
            List<Stone> list = en.getValue();
            long total = 0;
            for (Stone s : list) total += tiers.get(s.tier).income();
            final long sum = total;
            Tasks.then(plugin.db().tx(c -> {
                long bal = EconomyService.credit(c, u, sum, "money_stone", list.size() + " шт.");
                for (Stone s : list) Database.update(c, "UPDATE money_stones SET earned = earned + ? WHERE world=? AND x=? AND y=? AND z=?",
                        tiers.get(s.tier).income(), s.world, s.x, s.y, s.z);
                return bal;
            }), bal -> {
                plugin.economy().apply(u, bal);
                for (Stone s : list) s.earned += tiers.get(s.tier).income();
                Player p = Bukkit.getPlayer(u);
                if (p != null) Msg.actionBar(p, "&6+" + Text.money(sum) + " &7от камней заработка (" + list.size() + ")");
            }, null);
        }
    }

    // ------------------------------------------------------------------ Магазин /secretmoney

    public void open(Player p, Menu parent) {
        if (!enabled) { Msg.error(p, "stone.disabled", "Камни заработка сейчас отключены."); return; }
        new Menu(p, 4, "&8Камни заработка") {
            @Override
            protected void build() {
                fill(Items.filler());
                int slot = 10;
                for (Tier t : tiers.values()) {
                    set(slot, Items.of(t.block()).name(t.name()).lore("&7Цена: &6" + Text.money(t.price()),
                            "&7Доход: &6" + Text.money(perMinute(t)) + " &7в минуту", "&7Окупаемость: ~" + paybackMinutes(t) + " мин",
                            "", "&eНажмите, чтобы купить").glow().build(), c -> buy(viewer, t));
                    slot++;
                    if (slot == 17) slot = 19;
                    if (slot > 25) break;
                }
                int mine = countOf(viewer.getUniqueId());
                set(31, Items.of(Material.CHEST).name("&eМои камни").lore("&7Установлено: &f" + mine + "/" + maxPerPlayer,
                        requireOnline ? "&7Доход идёт, пока вы на сервере" : "&7Доход идёт всегда", "", "&eСписок с координатами").build(), c -> mine(viewer, this));
                if (parent != null) parent(parent);
                backButton(27);
            }
        }.open();
    }

    private void mine(Player p, Menu parent) {
        new PagedMenu(p, "&8Мои камни заработка") {
            @Override
            protected List<Entry> entries() {
                List<Entry> out = new ArrayList<>();
                for (Stone s : stones.values()) {
                    if (!s.owner.equals(viewer.getUniqueId())) continue;
                    Tier t = tiers.get(s.tier);
                    out.add(new Entry(Items.of(t == null ? Material.STONE : t.block()).name(t == null ? s.tier : t.name())
                            .lore("&7" + s.world + ": &f" + s.x + ", " + s.y + ", " + s.z, "&7Заработал: &6" + Text.money(s.earned)).build(), null));
                }
                return out;
            }
        }.parent(parent).open();
    }

    private void buy(Player p, Tier t) {
        if (!plugin.economy().tradeAllowed(p)) return;
        if (ru.unknownsmp.market.MailService.freeSlots(p) < 1) { Msg.error(p, "shop.no-space", "Недостаточно места в инвентаре (нужно {n} свободных ячеек).", "n", 1); return; }
        UUID u = p.getUniqueId();
        if (!busy.add(u)) return;
        Tasks.then(plugin.db().tx(c -> EconomyService.debit(c, u, t.price(), "money_stone_buy", t.id())), bal -> {
            busy.remove(u);
            plugin.economy().apply(u, bal);
            plugin.mail().giveOrMail(p, item(t), "shop");
            Msg.sound(p, Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.4f);
            Msg.ok(p, "stone.bought", "Куплен {n} за {c}. Поставьте его в мире выживания.", "n", Text.strip(t.name()), "c", Text.money(t.price()));
            plugin.logs().log(p, "economy", "stone_buy", p.getLocation(), t.id() + " = " + t.price());
        }, err -> {
            busy.remove(u);
            if (err instanceof Database.TxAbort ab) Msg.error(p, ab.key, ab.def);
            else Msg.error(p, "shop.error", "Покупка не выполнена. Деньги не списаны.");
        });
    }
}
