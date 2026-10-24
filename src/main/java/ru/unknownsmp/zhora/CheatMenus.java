package ru.unknownsmp.zhora;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import ru.unknownsmp.core.Msg;
import ru.unknownsmp.core.Tasks;
import ru.unknownsmp.core.Text;
import ru.unknownsmp.core.UnknownSMP;
import ru.unknownsmp.gui.Items;
import ru.unknownsmp.gui.Menu;
import ru.unknownsmp.gui.PagedMenu;
import ru.unknownsmp.zhora.ZhoraStore.Case;
import ru.unknownsmp.zhora.ZhoraStore.Evidence;

import java.util.ArrayList;
import java.util.List;

/** Меню «Проверки на читы»: дела, собранные детекторами и жалобами, и улики по каждому. Только для персонала. */
public final class CheatMenus {
    private CheatMenus() {}

    private static final String PERM = "usmp.mod.alerts";

    private static Material icon(String cat) {
        return switch (cat == null ? "" : cat) {
            case "xray" -> Material.DIAMOND_PICKAXE;
            case "pvp" -> Material.DIAMOND_SWORD;
            case "dupe" -> Material.CHEST;
            case "economy" -> Material.GOLD_INGOT;
            default -> Material.SPYGLASS;
        };
    }

    private static String catName(String cat) {
        return switch (cat == null ? "" : cat) {
            case "xray" -> "X-ray / руды";
            case "pvp" -> "PvP / бой";
            case "dupe" -> "дюп / предметы";
            case "economy" -> "экономика";
            default -> "прочее";
        };
    }

    private static String sevColor(int s) {
        return s >= 5 ? "&4" : s >= 4 ? "&c" : s >= 3 ? "&6" : s >= 2 ? "&e" : "&7";
    }

    private static List<String> wrap(String text, int width, String prefix) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        StringBuilder line = new StringBuilder();
        for (String w : text.split(" ")) {
            if (line.length() + w.length() + 1 > width) { out.add(prefix + line); line.setLength(0); }
            if (line.length() > 0) line.append(' ');
            line.append(w);
        }
        if (line.length() > 0) out.add(prefix + line);
        return out;
    }

    /** Список дел. state: "open" (открытые) или null (все). */
    public static void show(UnknownSMP plugin, Player p, String state, Menu parent) {
        ZhoraService z = plugin.zhora();
        if (z == null || !plugin.roles().has(p, PERM)) { Msg.error(p, "zhora.noperm", "Недостаточно прав."); return; }
        Tasks.then(z.store().cases(state, null, 200), list -> {
            PagedMenu m = new PagedMenu(p, "&8Проверки на читы: " + (state == null ? "все дела" : "открытые")) {
                @Override
                protected List<Entry> entries() {
                    List<Entry> out = new ArrayList<>();
                    for (Case c : list) {
                        List<String> lore = new ArrayList<>();
                        lore.add("&7Категория: &f" + catName(c.category()));
                        lore.add("&7Серьёзность: " + sevColor(c.priority()) + c.priority() + " &8| &7Статус: &f" + c.state());
                        lore.add("&7Обновлено: &f" + Text.date(c.updatedAt()));
                        Player on = c.target() == null ? null : safePlayer(c.target());
                        lore.add("&7Игрок: " + (on != null ? "&aв сети" : "&8не в сети") + " &8| &7риск: &f" + String.format("%.1f", z.analytics().riskOf(uuid(c.target()))));
                        lore.addAll(wrap(c.summary(), 36, "&8"));
                        lore.add("");
                        lore.add("&eЛКМ — улики и действия");
                        out.add(new Entry(Items.of(icon(c.category())).name(sevColor(c.priority()) + c.targetName() + " &8#" + c.id()).lore(lore).build(),
                                click -> details(plugin, p, c, this)));
                    }
                    return out;
                }

                @Override
                protected void bottomBar() {
                    set(46, Items.of(Material.HOPPER).name(state == null ? "&eПоказать только открытые" : "&eПоказать все дела").build(),
                            c -> CheatMenus.show(plugin, p, state == null ? "open" : null, parent));
                    set(47, Items.of(Material.CLOCK).name("&eОбновить").build(), c -> CheatMenus.show(plugin, p, state, parent));
                    set(51, Items.of(Material.BOOK).name("&7Как это работает").lore("&8Детекторы собирают сигналы постоянно:", "&8руды, бой, предметы, движение, темп действий,", "&8экономика. Это подсказки, а не доказательства.", "&8Решение и наказание — всегда за человеком.").build());
                }
            };
            if (parent != null) m.parent(parent);
            m.open();
        }, err -> Msg.error(p, "zhora.db", "Ошибка базы данных."));
    }

    private static Player safePlayer(String uuid) {
        try { return Bukkit.getPlayer(java.util.UUID.fromString(uuid)); } catch (Exception e) { return null; }
    }

    private static java.util.UUID uuid(String s) {
        try { return java.util.UUID.fromString(s); } catch (Exception e) { return new java.util.UUID(0, 0); }
    }

    /** Улики по делу и кнопки действий. */
    private static void details(UnknownSMP plugin, Player p, Case c, Menu parent) {
        ZhoraService z = plugin.zhora();
        Tasks.then(z.store().evidence(c.id(), 200), ev -> {
            PagedMenu m = new PagedMenu(p, "&8Дело #" + c.id() + ": " + c.targetName()) {
                @Override
                protected List<Entry> entries() {
                    List<Entry> out = new ArrayList<>();
                    for (Evidence e : ev) {
                        List<String> lore = new ArrayList<>();
                        lore.add("&7Детектор: &f" + e.detector() + " &8| &7серьёзность: " + sevColor(e.severity()) + e.severity());
                        lore.add("&7Время: &f" + Text.date(e.at()));
                        lore.addAll(wrap(e.details(), 38, "&f"));
                        if (e.confirmed()) lore.add("&aПодтверждено человеком");
                        Material mat = e.detector().startsWith("complaint") ? Material.WRITABLE_BOOK : icon(c.category());
                        out.add(new Entry(Items.of(mat).name(sevColor(e.severity()) + e.detector()).lore(lore).build(), null));
                    }
                    return out;
                }

                @Override
                protected void bottomBar() {
                    Player target = safePlayer(c.target());
                    set(46, Items.of(Material.ENDER_PEARL).name("&bТелепорт к игроку").lore(target == null ? "&8Игрок не в сети" : "&7Перейти к " + target.getName()).build(),
                            click -> { if (target != null && plugin.roles().has(p, "usmp.mod.tp")) p.performCommand("usmp tp " + target.getName()); else Msg.error(p, "zhora.noperm", "Недоступно."); });
                    set(47, Items.of(Material.CHEST).name("&bИнвентарь игрока").lore("&7/usmp invsee").build(),
                            click -> { if (target != null && plugin.roles().has(p, "usmp.mod.invsee")) p.performCommand("usmp invsee " + target.getName()); });
                    set(48, Items.of(Material.WRITABLE_BOOK).name("&bЖурнал действий").lore("&7/usmp lookup за сутки").build(),
                            click -> { p.closeInventory(); p.performCommand("usmp lookup " + c.targetName()); });
                    set(50, Items.of(Material.EMERALD).name("&aПодтверждено человеком").build(), click -> { p.performCommand("zhora case " + c.id() + " подтвердить"); p.closeInventory(); });
                    set(51, Items.of(Material.REDSTONE).name("&cЗакрыть дело").lore("&7Ложная тревога или решено").build(), click -> { p.performCommand("zhora case " + c.id() + " закрыть"); p.closeInventory(); });
                    set(52, Items.of(Material.NETHER_STAR).name("&dИИ-анализ дела").lore("&7Сводка от Жоры (подсказка администратору)").build(), click -> { p.closeInventory(); p.performCommand("zhora case " + c.id() + " анализ"); });
                    if (c.aiSummary() != null && !c.aiSummary().isBlank()) {
                        List<String> lore = new ArrayList<>(wrap(c.aiSummary(), 38, "&7"));
                        set(52, Items.of(Material.NETHER_STAR).name("&dИИ-анализ дела").lore(lore).build(), click -> { p.closeInventory(); p.performCommand("zhora case " + c.id() + " анализ"); });
                    }
                }
            };
            m.parent(parent);
            m.open();
        }, err -> Msg.error(p, "zhora.db", "Ошибка базы данных."));
    }
}
