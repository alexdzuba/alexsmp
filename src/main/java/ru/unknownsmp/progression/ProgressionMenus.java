package ru.unknownsmp.progression;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import ru.unknownsmp.core.ChatInput;
import ru.unknownsmp.core.Msg;
import ru.unknownsmp.core.Text;
import ru.unknownsmp.core.UnknownSMP;
import ru.unknownsmp.gui.ConfirmMenu;
import ru.unknownsmp.gui.Items;
import ru.unknownsmp.gui.Menu;
import ru.unknownsmp.gui.PagedMenu;
import ru.unknownsmp.profile.Profile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Профиль, титулы, достижения, ранги и редактор рангов владельца. */
public final class ProgressionMenus {
    private final UnknownSMP plugin;
    private final ProgressionService prog;

    ProgressionMenus(UnknownSMP plugin, ProgressionService prog) {
        this.plugin = plugin;
        this.prog = prog;
    }

    public void profile(Player viewer, Player target, Menu parent) {
        Profile pr = plugin.profiles().get(target);
        if (pr == null) return;
        new Menu(viewer, 5, "&8Профиль " + target.getName()) {
            @Override
            protected void build() {
                fill(Items.filler());
                var clan = plugin.clans() == null ? null : plugin.clans().clanOf(target);
                long next = prog.xpFor(pr.level + 1), cur = prog.xpFor(pr.level);
                double frac = next <= cur ? 1 : (pr.xp - cur) / (double) (next - cur);
                set(4, Items.head(target).name("&f" + target.getName()).lore(
                        "&7Ранг: " + prog.rankName(pr.progRank),
                        "&7Титул: " + (pr.title == null ? "&8нет" : prog.titleName(pr.title)),
                        "&7Клан: " + (clan == null ? "&8нет" : "&d[" + clan.tag + "]"),
                        "&7Уровень: &b" + pr.level + " " + Text.bar(frac, 15, "&b", "&8"),
                        "&7Репутация: &b" + pr.reputation,
                        "&7Баланс: &6" + Text.money(pr.balance),
                        "&7Время в игре: &f" + Text.duration(pr.playtime * 1000),
                        "&7Боевой бонус уровня: &f+" + String.format("%.2f", prog.combatBonus(pr) * 100) + "%").build());
                set(19, Items.of(Material.IRON_SWORD).name("&cPvP").lore("&7Убийств: &f" + pr.stat("kills"), "&7Смертей: &f" + pr.stat("deaths"),
                        "&7Наград за головы: &f" + pr.stat("bounties_claimed"), "&7Дуэли: &a" + pr.stat("duel_wins") + "&7/&c" + pr.stat("duel_losses")).build());
                set(20, Items.of(Material.DIAMOND_PICKAXE).name("&bМир").lore("&7Блоков добыто: &f" + pr.stat("blocks_broken"), "&7Блоков поставлено: &f" + pr.stat("blocks_placed"),
                        "&7Мобов убито: &f" + pr.stat("mobs_killed"), "&7Дальше всего от спавна: &f" + pr.stat("max_distance"), "&7Секретов: &f" + pr.stat("secrets_found")).build());
                set(21, Items.of(Material.NETHER_STAR).name("&dСобытия и задания").lore("&7Событий: &f" + pr.stat("events_done"), "&7Заданий: &f" + pr.stat("quests_done")).build());
                boolean self = viewer.equals(target);
                if (self) {
                    set(23, Items.of(Material.NAME_TAG).name("&dТитулы").lore("&7Выбор титула над головой и в чате").build(), c -> titles(viewer, this));
                    set(24, Items.of(Material.GOLDEN_APPLE).name("&6Достижения").lore("&7Открыто: &f" + pr.stat("achievements") + "/" + prog.achievements().size()).build(), c -> achievements(viewer, this));
                    set(25, Items.of(Material.BEACON).name("&eРанги").lore("&7Требования и награды").build(), c -> ranks(viewer, this));
                    set(31, Items.of(Material.BLAZE_POWDER).name("&5Косметика").build(), c -> plugin.lobby().cosmetics().open(viewer, this));
                }
                if (parent != null) parent(parent);
                backButton(40);
            }
        }.open();
    }

    public void titles(Player p, Menu parent) {
        Profile pr = plugin.profiles().get(p);
        new PagedMenu(p, "&8Титулы") {
            @Override
            protected List<Entry> entries() {
                List<Entry> out = new ArrayList<>();
                for (var e : prog.titles().entrySet()) {
                    boolean has = pr.has("title", e.getKey()) || plugin.roles().has(viewer, "usmp.admin.titles");
                    boolean sel = e.getKey().equals(pr.title);
                    out.add(new Entry(Items.of(has ? Material.NAME_TAG : Material.GRAY_DYE).name(has ? e.getValue() : "&8???").glow(sel)
                            .lore(sel ? "&aВыбран" : has ? "&eНажмите, чтобы выбрать" : "&cНе открыт").build(), c -> {
                        if (!has) return;
                        pr.title = e.getKey();
                        pr.dirty = true;
                        plugin.display().refresh(viewer);
                        refresh();
                    }));
                }
                return out;
            }

            @Override
            protected void bottomBar() {
                set(47, Items.of(Material.BARRIER).name("&cСнять титул").build(), c -> {
                    pr.title = null;
                    pr.dirty = true;
                    plugin.display().refresh(viewer);
                    refresh();
                });
            }
        }.parent(parent).open();
    }

    public void achievements(Player p, Menu parent) {
        Profile pr = plugin.profiles().get(p);
        new PagedMenu(p, "&8Достижения") {
            @Override
            protected List<Entry> entries() {
                List<Entry> out = new ArrayList<>();
                for (var a : prog.achievements().values()) {
                    boolean has = pr.has("ach", a.id());
                    if (a.secret() && !has) {
                        out.add(new Entry(Items.of(Material.GRAY_DYE).name("&8??? Секретное достижение").lore("&7Условие скрыто").build(), null));
                        continue;
                    }
                    Material m = Material.matchMaterial(a.icon());
                    long v = pr.stat(a.stat());
                    out.add(new Entry(Items.of(has ? (m == null ? Material.GOLD_INGOT : m) : Material.LIGHT_GRAY_DYE).name((has ? "&6" : "&7") + a.name()).glow(has)
                            .lore("&7" + a.description(), "", has ? "&a✔ Получено" : "&7Прогресс: &f" + Math.min(v, a.value()) + "/" + a.value(),
                                    a.reputation() > 0 ? "&bНаграда: " + a.reputation() + " репутации" : "",
                                    a.title() != null ? "&dТитул: " + prog.titleName(a.title()) : "").build(), null));
                }
                return out;
            }
        }.parent(parent).open();
    }

    public void ranks(Player p, Menu parent) {
        Profile pr = plugin.profiles().get(p);
        new PagedMenu(p, "&8Ранги прогрессии") {
            @Override
            protected List<Entry> entries() {
                List<Entry> out = new ArrayList<>();
                for (var r : prog.ranks().values()) {
                    boolean cur = r.id().equals(pr.progRank);
                    List<String> lore = new ArrayList<>();
                    for (var e : r.requires().entrySet()) {
                        long v = prog.requirementValue(viewer, pr, e.getKey());
                        lore.add((v >= e.getValue() ? "&a✔ " : "&c✘ ") + "&7" + reqName(e.getKey()) + ": &f" + Math.min(v, e.getValue()) + "/" + e.getValue());
                    }
                    lore.add("");
                    if (r.money() > 0) lore.add("&6Награда: " + Text.money(r.money()));
                    if (r.cosmetic() != null) lore.add("&5Косметика: след «" + r.cosmetic() + "»");
                    if (r.title() != null) lore.add("&dТитул: " + prog.titleName(r.title()));
                    lore.add("&8Ранги дают только косметические преимущества");
                    out.add(new Entry(Items.of(cur ? Material.BEACON : Material.GLASS).name(r.name() + (cur ? " &a(текущий)" : "")).glow(cur).lore(lore).build(), null));
                }
                return out;
            }

            @Override
            protected void bottomBar() {
                if (plugin.roles().isOwner(viewer))
                    set(47, Items.of(Material.COMMAND_BLOCK).name("&cРедактор рангов").lore("&7Только для владельца").build(), c -> editor(viewer, this));
            }
        }.parent(parent).open();
    }

    static String reqName(String k) {
        return switch (k) {
            case "level" -> "Уровень";
            case "reputation" -> "Репутация";
            case "playtime-hours" -> "Часов в игре";
            case "achievements" -> "Достижений";
            case "kills" -> "Убийств игроков";
            case "events" -> "Событий";
            case "quests" -> "Заданий";
            case "wealth" -> "Баланс";
            case "secrets" -> "Секретов";
            case "duel-wins" -> "Побед в дуэлях";
            default -> k;
        };
    }

    // ------------------------------------------------------------------ Редактор рангов (владелец)

    private static final String[] REQ_KEYS = {"level", "reputation", "playtime-hours", "achievements", "kills", "events", "quests", "wealth", "secrets", "duel-wins"};

    public void editor(Player p, Menu parent) {
        if (!plugin.roles().isOwner(p)) return;
        Map<String, ProgressionService.Rank> work = new LinkedHashMap<>(prog.ranks());
        new PagedMenu(p, "&8Редактор рангов") {
            @Override
            protected List<Entry> entries() {
                List<Entry> out = new ArrayList<>();
                for (var r : work.values()) {
                    out.add(new Entry(Items.of(Material.BEACON).name(r.name()).lore("&8id: " + r.id(), "&eЛКМ — изменить", "&cShift+ПКМ — удалить").build(), c -> {
                        if (c.shift() && c.right()) {
                            ConfirmMenu.open(viewer, "&8Удалить ранг " + r.id() + "?", null, List.of("&7Игроки с этим рангом сохранят его до пересчёта"), () -> {
                                work.remove(r.id());
                                prog.saveRanks(work);
                                editor(viewer, parent);
                            });
                            return;
                        }
                        editRank(viewer, work, r.id(), this);
                    }));
                }
                return out;
            }

            @Override
            protected void bottomBar() {
                set(47, Items.of(Material.EMERALD).name("&aДобавить ранг").lore("&7Добавится в конец цепочки").build(), c ->
                        ChatInput.ask(viewer, Msg.t("ranks.ask-id", "&fВведите id нового ранга (латиница):"), id -> {
                            String clean = id.toLowerCase().replaceAll("[^a-z0-9_]", "");
                            if (clean.isEmpty() || work.containsKey(clean)) { Msg.error(viewer, "ranks.bad-id", "Некорректный или занятый id."); return; }
                            work.put(clean, new ProgressionService.Rank(clean, "&f" + clean, new LinkedHashMap<>(Map.of("level", 1L)), 0, null, null));
                            prog.saveRanks(work);
                            editRank(viewer, new LinkedHashMap<>(prog.ranks()), clean, parent);
                        }));
            }
        }.parent(parent).open();
    }

    private void editRank(Player p, Map<String, ProgressionService.Rank> work, String id, Menu parent) {
        new Menu(p, 5, "&8Ранг: " + id) {
            @Override
            protected void build() {
                fill(Items.filler());
                ProgressionService.Rank r = work.get(id);
                if (r == null) { viewer.closeInventory(); return; }
                set(4, Items.of(Material.NAME_TAG).name(r.name()).lore("&eНажмите, чтобы переименовать").build(), c ->
                        ChatInput.ask(viewer, Msg.t("ranks.ask-name", "&fНовое название ранга (с &-цветами):"), n -> {
                            work.put(id, new ProgressionService.Rank(id, n, r.requires(), r.money(), r.cosmetic(), r.title()));
                            prog.saveRanks(work);
                            editRank(viewer, work, id, parent);
                        }));
                int slot = 18;
                for (String k : REQ_KEYS) {
                    long v = r.requires().getOrDefault(k, 0L);
                    set(slot++, Items.of(v > 0 ? Material.LIME_DYE : Material.GRAY_DYE).name("&f" + reqName(k) + ": &e" + v)
                            .lore("&7ЛКМ +1, ПКМ -1", "&7Shift: ×10", "&70 — требование отключено").build(), c -> {
                        long step = c.shift() ? 10 : 1;
                        if (k.equals("wealth")) step *= 1000;
                        long nv = Math.max(0, v + (c.right() ? -step : step));
                        Map<String, Long> req = new LinkedHashMap<>(r.requires());
                        if (nv == 0) req.remove(k);
                        else req.put(k, nv);
                        work.put(id, new ProgressionService.Rank(id, r.name(), req, r.money(), r.cosmetic(), r.title()));
                        refresh();
                    });
                }
                set(30, Items.of(Material.GOLD_INGOT).name("&6Денежная награда: " + Text.money(r.money())).lore("&eНажмите, чтобы изменить").build(), c ->
                        ChatInput.ask(viewer, Msg.t("ranks.ask-money", "&fДенежная награда за ранг (0 — нет):"), s -> {
                            long m = s.trim().equals("0") ? 0 : Math.max(0, Text.parseAmount(s));
                            work.put(id, new ProgressionService.Rank(id, r.name(), r.requires(), m, r.cosmetic(), r.title()));
                            prog.saveRanks(work);
                            editRank(viewer, work, id, parent);
                        }));
                set(31, Items.of(Material.BLAZE_POWDER).name("&5Косметика: " + (r.cosmetic() == null ? "нет" : r.cosmetic()))
                        .lore("&7Доступно: " + String.join(", ", ru.unknownsmp.lobby.CosmeticsService.TRAILS.keySet()), "&eНажмите, чтобы изменить").build(), c ->
                        ChatInput.ask(viewer, Msg.t("ranks.ask-cosmetic", "&fid следа (или «нет»):"), s -> {
                            String v = s.equalsIgnoreCase("нет") ? null : s.trim();
                            work.put(id, new ProgressionService.Rank(id, r.name(), r.requires(), r.money(), v, r.title()));
                            prog.saveRanks(work);
                            editRank(viewer, work, id, parent);
                        }));
                set(32, Items.of(Material.NAME_TAG).name("&dТитул: " + (r.title() == null ? "нет" : prog.titleName(r.title())))
                        .lore("&7id титула из progression.yml → titles", "&eНажмите, чтобы изменить").build(), c ->
                        ChatInput.ask(viewer, Msg.t("ranks.ask-title", "&fid титула (или «нет»):"), s -> {
                            String v = s.equalsIgnoreCase("нет") ? null : s.trim();
                            work.put(id, new ProgressionService.Rank(id, r.name(), r.requires(), r.money(), r.cosmetic(), v));
                            prog.saveRanks(work);
                            editRank(viewer, work, id, parent);
                        }));
                set(40, Items.of(Material.LIME_CONCRETE).name("&a&lСохранить").lore("&7Записать в progression.yml").build(), c -> {
                    prog.saveRanks(work);
                    plugin.logs().log(viewer, "admin", "ranks_edit", null, id);
                    Msg.ok(viewer, "ranks.saved", "Ранги сохранены.");
                    editor(viewer, parent);
                });
                set(36, Items.of(Material.ARROW).name("&eНазад без сохранения").build(), c -> editor(viewer, parent));
            }
        }.open();
    }
}
