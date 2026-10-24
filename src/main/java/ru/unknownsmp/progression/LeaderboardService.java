package ru.unknownsmp.progression;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import ru.unknownsmp.core.Module;
import ru.unknownsmp.core.Tasks;
import ru.unknownsmp.core.Text;
import ru.unknownsmp.core.UnknownSMP;
import ru.unknownsmp.gui.Items;
import ru.unknownsmp.gui.Menu;
import ru.unknownsmp.gui.PagedMenu;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Публичные таблицы лидеров: богатство, PvP, кланы, достижения, события, репутация, дуэли. Кэш 5 минут. */
public final class LeaderboardService implements Module {
    public record Row(UUID uuid, String name, long value) {}

    private record Board(String id, String title, Material icon, String sql, boolean money) {}

    private final UnknownSMP plugin;
    private final Map<String, Board> boards = new LinkedHashMap<>();
    private final Map<String, List<Row>> cache = new ConcurrentHashMap<>();
    private final Map<String, Long> cachedAt = new ConcurrentHashMap<>();

    public LeaderboardService(UnknownSMP plugin) {
        this.plugin = plugin;
        boards.put("wealth", new Board("wealth", "&6Богатство", Material.GOLD_BLOCK,
                "SELECT uuid, name, balance FROM players ORDER BY balance DESC LIMIT 45", true));
        boards.put("pvp", new Board("pvp", "&cPvP", Material.IRON_SWORD,
                "SELECT s.uuid, p.name, s.value FROM player_stats s JOIN players p ON p.uuid = s.uuid WHERE s.stat='kills' ORDER BY s.value DESC LIMIT 45", false));
        boards.put("clans", new Board("clans", "&dКланы", Material.CYAN_BANNER,
                "SELECT CAST(id AS TEXT), tag, wars_won * 1000000 + (bank / 1000) FROM clans ORDER BY wars_won DESC, bank DESC LIMIT 45", false));
        boards.put("achievements", new Board("achievements", "&eДостижения", Material.GOLDEN_APPLE,
                "SELECT s.uuid, p.name, s.value FROM player_stats s JOIN players p ON p.uuid = s.uuid WHERE s.stat='achievements' ORDER BY s.value DESC LIMIT 45", false));
        boards.put("events", new Board("events", "&5События", Material.NETHER_STAR,
                "SELECT s.uuid, p.name, s.value FROM player_stats s JOIN players p ON p.uuid = s.uuid WHERE s.stat='events_done' ORDER BY s.value DESC LIMIT 45", false));
        boards.put("reputation", new Board("reputation", "&bРепутация", Material.EMERALD,
                "SELECT uuid, name, reputation FROM players ORDER BY reputation DESC LIMIT 45", false));
        boards.put("duels", new Board("duels", "&cРейтинг дуэлей", Material.DIAMOND_SWORD,
                "SELECT s.uuid, p.name, s.value FROM player_stats s JOIN players p ON p.uuid = s.uuid WHERE s.stat='duel_rating' ORDER BY s.value DESC LIMIT 45", false));
        boards.put("clicks", new Board("clicks", "&6Кликер", Material.RAW_GOLD_BLOCK,
                "SELECT s.uuid, p.name, s.value FROM player_stats s JOIN players p ON p.uuid = s.uuid WHERE s.stat='clicks' ORDER BY s.value DESC LIMIT 45", false));
        boards.put("tir", new Board("tir", "&cТир", Material.TARGET,
                "SELECT s.uuid, p.name, s.value FROM player_stats s JOIN players p ON p.uuid = s.uuid WHERE s.stat='tir_points' ORDER BY s.value DESC LIMIT 45", false));
        boards.put("snow", new Board("snow", "&bСнежки", Material.SNOWBALL,
                "SELECT s.uuid, p.name, s.value FROM player_stats s JOIN players p ON p.uuid = s.uuid WHERE s.stat='snow_hits' ORDER BY s.value DESC LIMIT 45", false));
    }

    @Override public String id() { return "leaderboards"; }
    @Override public String title() { return "Таблицы лидеров"; }
    @Override public String[] depends() { return new String[]{"database"}; }

    @Override
    public void enable() {
        plugin.commands().add("top").perm("usmp.player.top").module("leaderboards").cat("Статистика").usage("/top [категория]")
                .desc("Таблицы лидеров").player().exec((s, a) -> {
                    Player p = (Player) s;
                    if (a.length > 0 && boards.containsKey(a[0])) board(p, a[0], null);
                    else open(p, null);
                }).tab((s, a) -> new ArrayList<>(boards.keySet()));
        plugin.commands().add("baltop").perm("usmp.player.top").module("leaderboards").cat("Экономика").usage("/baltop")
                .desc("Самые богатые игроки").player().exec((s, a) -> board((Player) s, "wealth", null));
    }

    public CompletableFuture<List<Row>> get(String id) {
        Board b = boards.get(id);
        if (b == null) return CompletableFuture.completedFuture(List.of());
        Long at = cachedAt.get(id);
        if (at != null && System.currentTimeMillis() - at < 300_000 && cache.containsKey(id)) return CompletableFuture.completedFuture(cache.get(id));
        if (id.equals("wealth") || id.equals("reputation")) plugin.profiles().flushAll();
        return plugin.db().query(c -> {
            List<Row> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(b.sql()); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID u;
                    try { u = UUID.fromString(rs.getString(1)); } catch (Exception e) { u = null; }
                    out.add(new Row(u, rs.getString(2), rs.getLong(3)));
                }
            }
            cache.put(id, out);
            cachedAt.put(id, System.currentTimeMillis());
            return out;
        });
    }

    public void open(Player p, Menu parent) {
        new Menu(p, 3, "&8Таблицы лидеров") {
            @Override
            protected void build() {
                fill(Items.filler());
                int slot = 10;
                for (Board b : boards.values()) {
                    set(slot++, Items.of(b.icon()).name(b.title()).lore("&eОткрыть топ-45").build(), c -> board(viewer, b.id(), this));
                }
                if (parent != null) parent(parent);
                backButton(22);
            }
        }.open();
    }

    public void board(Player p, String id, Menu parent) {
        Board b = boards.get(id);
        Tasks.then(get(id), rows -> new PagedMenu(p, "&8Топ: " + Text.strip(b.title())) {
            @Override
            protected List<Entry> entries() {
                List<Entry> out = new ArrayList<>();
                int place = 1;
                for (Row r : rows) {
                    String val = b.money() ? Text.money(r.value()) : id.equals("clans")
                            ? "побед: " + (r.value() / 1_000_000) + ", банк ~" + Text.money((r.value() % 1_000_000) * 1000) : Text.num(r.value());
                    String medal = place == 1 ? "&6&l#1 " : place == 2 ? "&f&l#2 " : place == 3 ? "&c&l#3 " : "&7#" + place + " ";
                    var icon = id.equals("clans") || r.uuid() == null ? Items.of(Material.CYAN_BANNER) : Items.head(r.uuid());
                    out.add(new Entry(icon.name(medal + "&f" + (id.equals("clans") ? "[" + r.name() + "]" : r.name())).lore("&7" + val).build(), null));
                    place++;
                }
                return out;
            }
        }.parent(parent).open(), null);
    }
}
