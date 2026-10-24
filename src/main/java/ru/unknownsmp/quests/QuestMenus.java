package ru.unknownsmp.quests;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import ru.unknownsmp.core.Text;
import ru.unknownsmp.core.UnknownSMP;
import ru.unknownsmp.gui.Items;
import ru.unknownsmp.gui.Menu;
import ru.unknownsmp.gui.PagedMenu;

import java.util.ArrayList;
import java.util.List;

/** Меню заданий: ежедневные, еженедельные, активные, доступные, выполненные. */
public final class QuestMenus {
    private final UnknownSMP plugin;
    private final QuestService quests;

    QuestMenus(UnknownSMP plugin, QuestService quests) {
        this.plugin = plugin;
        this.quests = quests;
    }

    public void open(Player p, Menu parent) {
        new Menu(p, 3, "&8Задания") {
            @Override
            protected void build() {
                fill(Items.filler());
                set(10, Items.of(Material.SUNFLOWER).name("&eЕжедневные").lore("&7Обновляются каждый день").build(), c -> list(viewer, "daily", this));
                set(11, Items.of(Material.CLOCK).name("&6Еженедельные").lore("&7Обновляются каждую неделю").build(), c -> list(viewer, "weekly", this));
                set(13, Items.of(Material.WRITABLE_BOOK).name("&aАктивные").lore("&7Сюжетные, исследовательские,", "&7боевые и задания NPC").build(), c -> list(viewer, "active", this));
                set(14, Items.of(Material.MAP).name("&bДоступные").lore("&7Задания, которые можно взять здесь").build(), c -> list(viewer, "available", this));
                set(15, Items.of(Material.BOOK).name("&7Выполненные").build(), c -> list(viewer, "done", this));
                set(16, Items.of(Material.SKELETON_SKULL).name("&cPvP-контракты").lore("&7Награды за головы игроков").build(), c -> plugin.bounties().open(viewer, this));
                if (parent != null) parent(parent);
                backButton(22);
            }
        }.open();
    }

    private void list(Player p, String tab, Menu parent) {
        new PagedMenu(p, "&8Задания: " + switch (tab) {
            case "daily" -> "ежедневные";
            case "weekly" -> "еженедельные";
            case "active" -> "активные";
            case "available" -> "доступные";
            default -> "выполненные";
        }) {
            @Override
            protected List<Entry> entries() {
                List<Entry> out = new ArrayList<>();
                var mine = quests.of(viewer);
                for (QuestService.Def d : quests.defs().values()) {
                    QuestService.Progress pr = mine.get(d.id());
                    boolean show = switch (tab) {
                        case "daily" -> d.type() == QuestService.Type.DAILY && pr != null;
                        case "weekly" -> d.type() == QuestService.Type.WEEKLY && pr != null;
                        case "active" -> pr != null && pr.state.equals("ACTIVE") && d.type() != QuestService.Type.DAILY && d.type() != QuestService.Type.WEEKLY;
                        case "available" -> d.npc() == null && d.type() != QuestService.Type.DAILY && d.type() != QuestService.Type.WEEKLY
                                && d.type() != QuestService.Type.STORY && quests.canAccept(viewer, d) == null;
                        default -> pr != null && pr.state.equals("DONE") && d.type() != QuestService.Type.DAILY && d.type() != QuestService.Type.WEEKLY;
                    };
                    if (!show) continue;
                    out.add(new Entry(icon(d, pr), c -> {
                        if (tab.equals("available")) {
                            quests.accept(viewer, d.id());
                            refresh();
                        }
                    }));
                }
                return out;
            }
        }.parent(parent).open();
    }

    private org.bukkit.inventory.ItemStack icon(QuestService.Def d, QuestService.Progress pr) {
        Material m = Material.matchMaterial(d.icon());
        boolean done = pr != null && pr.state.equals("DONE");
        List<String> lore = new ArrayList<>();
        lore.add("&8" + typeName(d.type()));
        for (String l : d.description().split("\n")) lore.add("&7" + l);
        lore.add("");
        long v = pr == null ? 0 : pr.value;
        lore.add("&7Прогресс: &f" + v + "/" + d.amount());
        lore.add(Text.bar(v / (double) d.amount(), 20, "&a", "&8"));
        lore.add("");
        lore.add("&7Награда:");
        if (d.money() > 0) lore.add("&6 • " + Text.money(d.money()));
        if (d.reputation() > 0) lore.add("&b • " + d.reputation() + " репутации");
        if (d.xp() > 0) lore.add("&a • " + d.xp() + " опыта уровня");
        for (String it : d.items()) lore.add("&f • " + it.replace("unique:", "★ ").replace("potion:", "зелье "));
        if (d.title() != null) lore.add("&d • титул " + plugin.progression().titleName(d.title()));
        if (d.npc() != null) lore.add("&8Выдаёт NPC: " + d.npc());
        if (pr == null) lore.add("&eНажмите, чтобы взять");
        else if (done) lore.add("&a✔ Выполнено");
        return Items.of(m == null ? Material.PAPER : m).name((done ? "&a" : "&e") + d.name()).glow(done).lore(lore).build();
    }

    static String typeName(QuestService.Type t) {
        return switch (t) {
            case DAILY -> "Ежедневное";
            case WEEKLY -> "Еженедельное";
            case STORY -> "Сюжетное";
            case EXPLORE -> "Исследование";
            case COMBAT -> "Боевое";
            case EVENT -> "Событийное";
            case NPC -> "Задание NPC";
        };
    }
}
