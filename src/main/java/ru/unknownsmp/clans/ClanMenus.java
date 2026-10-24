package ru.unknownsmp.clans;

import org.bukkit.Bukkit;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Все меню кланов. */
public final class ClanMenus {
    private final UnknownSMP plugin;
    private final ClanService clans;

    ClanMenus(UnknownSMP plugin, ClanService clans) {
        this.plugin = plugin;
        this.clans = clans;
    }

    public void open(Player p, Menu parent) {
        Clan cl = clans.clanOf(p);
        if (cl == null) noClan(p, parent);
        else main(p, cl, parent);
    }

    private void noClan(Player p, Menu parent) {
        new Menu(p, 3, "&8Кланы") {
            @Override
            protected void build() {
                fill(Items.filler());
                set(11, Items.of(Material.WHITE_BANNER).name("&a&lСоздать клан").lore("&7Стоимость: &6" + Text.money(clans.createCost()),
                        "&7До " + clans.maxMembers() + " участников, банк, точка появления,", "&7альянсы и войны.").build(), c ->
                        ChatInput.ask(viewer, Msg.t("clan.ask-tag", "&fВведите тег клана (2–5 букв/цифр):"), tag ->
                                ChatInput.ask(viewer, Msg.t("clan.ask-name", "&fВведите название клана (до 24 символов):"), name ->
                                        ConfirmMenu.open(viewer, "&8Создать клан?", null, List.of("&7Тег: &d" + tag, "&7Название: &f" + name,
                                                "&7Стоимость: &6" + Text.money(clans.createCost())), () -> clans.create(viewer, tag, name)))));
                set(13, Items.of(Material.PAPER).name("&eПринять приглашение").lore("&7Если вас пригласили в клан").build(), c -> {
                    viewer.closeInventory();
                    clans.accept(viewer);
                });
                set(15, Items.of(Material.BOOKSHELF).name("&bСписок кланов").build(), c -> list(viewer, this));
                if (parent != null) parent(parent);
                backButton(22);
            }
        }.open();
    }

    public void list(Player p, Menu parent) {
        new PagedMenu(p, "&8Кланы сервера") {
            @Override
            protected List<Entry> entries() {
                List<Clan> all = new ArrayList<>(clans.all());
                all.sort(Comparator.comparingInt((Clan c) -> -c.warsWon).thenComparingLong(c -> -c.bank));
                List<Entry> out = new ArrayList<>();
                for (Clan c : all) {
                    out.add(new Entry(Items.of(Material.CYAN_BANNER).name("&d[" + c.tag + "] &f" + c.name)
                            .lore("&7Участников: &f" + c.members.size() + "/" + clans.maxMembers(), "&7Побед в войнах: &a" + c.warsWon + " &7/ поражений: &c" + c.warsLost,
                                    "&7Союзников: &f" + c.allies.size(), "", "&eПодробнее").build(), cl -> info(viewer, c, this)));
                }
                return out;
            }
        }.parent(parent).open();
    }

    public void info(Player p, Clan c, Menu parent) {
        new Menu(p, 3, "&8Клан [" + c.tag + "]") {
            @Override
            protected void build() {
                fill(Items.filler());
                List<String> lore = new ArrayList<>();
                lore.add("&7Название: &f" + c.name);
                lore.add("&7Основан: &f" + Text.date(c.createdAt));
                lore.add("&7Банк: &6" + Text.money(c.bank));
                lore.add("&7Войны: &a" + c.warsWon + "&7/&c" + c.warsLost);
                lore.add("");
                lore.add("&7Участники:");
                for (Clan.Member m : c.members.values()) lore.add("  " + m.role().display + " &f" + m.name());
                set(13, Items.of(Material.CYAN_BANNER).name("&d[" + c.tag + "]").lore(lore).build());
                if (parent != null) parent(parent);
                backButton(22);
            }
        }.open();
    }

    private void main(Player p, Clan cl, Menu parent) {
        new Menu(p, 5, "&8Клан [" + cl.tag + "] " + cl.name) {
            @Override
            protected void build() {
                fill(Items.filler());
                boolean leader = cl.leader.equals(viewer.getUniqueId());
                set(4, Items.of(Material.CYAN_BANNER).name("&d[" + cl.tag + "] &f" + cl.name)
                        .lore("&7Ваша роль: " + cl.role(viewer.getUniqueId()).display, "&7Участников: &f" + cl.members.size() + "/" + clans.maxMembers(),
                                "&7Банк: &6" + Text.money(cl.bank), "&7Войны: &a" + cl.warsWon + "&7/&c" + cl.warsLost).build());
                set(19, Items.of(Material.PLAYER_HEAD).name("&eУчастники").lore("&7Роли, исключение, лидерство").build(), c -> members(viewer, cl, this));
                set(20, Items.of(Material.GOLD_BLOCK).name("&6Банк клана").lore("&7Баланс: &6" + Text.money(cl.bank), "", "&eЛКМ &7— внести",
                        leader ? "&eПКМ &7— снять (лидер)" : "&8Снимать может только лидер").build(), c -> {
                    if (c.right() && leader) ChatInput.ask(viewer, Msg.t("clan.ask-withdraw", "&fСколько снять из банка?"), s -> clans.withdraw(viewer, Text.parseAmount(s)));
                    else ChatInput.ask(viewer, Msg.t("clan.ask-deposit", "&fСколько внести в банк клана?"), s -> clans.deposit(viewer, Text.parseAmount(s)));
                });
                set(21, Items.of(Material.RED_BED).name("&aТочка клана").lore(cl.home == null ? "&7Не установлена" : "&7" + Text.coords(Text.loc(cl.home)),
                        "", "&eЛКМ &7— телепорт", "&eПКМ &7— установить здесь").build(), c -> {
                    viewer.closeInventory();
                    if (c.right()) clans.setHome(viewer);
                    else clans.home(viewer);
                });
                set(22, Items.of(Material.WRITABLE_BOOK).name("&eПригласить игрока").build(), c ->
                        ChatInput.ask(viewer, Msg.t("clan.ask-invite", "&fНик игрока для приглашения:"), s -> clans.invite(viewer, Bukkit.getPlayerExact(s))));
                set(23, Items.of(Material.LIME_BANNER).name("&aАльянсы").lore("&7Союзников: &f" + cl.allies.size()).build(), c -> allies(viewer, cl, this));
                set(24, Items.of(Material.IRON_SWORD).name("&cВойны").lore("&7Активных: &f" + plugin.wars().warsOf(cl).size()).build(), c -> wars(viewer, this));
                set(25, Items.of(Material.BOOKSHELF).name("&bДругие кланы").build(), c -> list(viewer, this));
                if (leader) set(31, Items.of(Material.COMPARATOR).name("&6Права ролей").lore("&7Настройка прав офицеров и участников").build(), c -> perms(viewer, cl, this));
                set(38, Items.of(Material.OAK_DOOR).name("&cПокинуть клан").build(), c -> confirmLeave(viewer));
                if (leader) set(42, Items.of(Material.TNT).name("&4Распустить клан").lore("&7Средства банка вернутся лидеру").build(), c -> confirmDisband(viewer, cl));
                if (parent != null) parent(parent);
                backButton(40);
            }
        }.open();
    }

    private void members(Player p, Clan cl, Menu parent) {
        new PagedMenu(p, "&8Участники [" + cl.tag + "]") {
            @Override
            protected List<Entry> entries() {
                List<Entry> out = new ArrayList<>();
                boolean leader = cl.leader.equals(viewer.getUniqueId());
                for (Clan.Member m : cl.members.values()) {
                    boolean online = Bukkit.getPlayer(m.uuid()) != null;
                    List<String> lore = new ArrayList<>(List.of("&7Роль: " + m.role().display, "&7В клане с: &f" + Text.date(m.joinedAt()), online ? "&aВ сети" : "&8Не в сети"));
                    if (leader && !m.uuid().equals(viewer.getUniqueId())) lore.addAll(List.of("", "&eЛКМ &7— повысить/понизить", "&eShift+ЛКМ &7— сделать лидером", "&cПКМ &7— исключить"));
                    else if (cl.can(viewer.getUniqueId(), Clan.Perm.KICK) && m.role() == Clan.Role.MEMBER) lore.addAll(List.of("", "&cПКМ &7— исключить"));
                    out.add(new Entry(Items.head(m.uuid()).name("&f" + m.name()).lore(lore).build(), c -> {
                        if (m.uuid().equals(viewer.getUniqueId())) return;
                        if (c.right()) {
                            ConfirmMenu.open(viewer, "&8Исключить " + m.name() + "?", null, List.of("&7Игрок покинет клан"), () -> clans.kick(viewer, m.uuid()));
                        } else if (leader && c.shift()) {
                            ConfirmMenu.open(viewer, "&8Передать лидерство?", null, List.of("&7Новый лидер: &f" + m.name()),
                                    () -> clans.setLeader(cl, m.uuid(), () -> clans.broadcastClan(cl, "clan.new-leader-manual", "&6{n} назначен лидером клана.", "n", m.name())));
                        } else if (leader) {
                            clans.setRole(viewer, m.uuid(), m.role() == Clan.Role.MEMBER ? Clan.Role.OFFICER : Clan.Role.MEMBER);
                            ru.unknownsmp.core.Tasks.later(10, this::refresh);
                        }
                    }));
                }
                return out;
            }
        }.parent(parent).open();
    }

    private void perms(Player p, Clan cl, Menu parent) {
        new Menu(p, 4, "&8Права ролей") {
            @Override
            protected void build() {
                fill(Items.filler());
                set(9, Items.of(Material.GOLDEN_HELMET).name("&eОфицер").build());
                set(18, Items.of(Material.LEATHER_HELMET).name("&7Участник").build());
                int col = 1;
                for (Clan.Perm perm : Clan.Perm.values()) {
                    set(col, Items.of(Material.PAPER).name("&f" + perm.display).build());
                    for (Clan.Role r : new Clan.Role[]{Clan.Role.OFFICER, Clan.Role.MEMBER}) {
                        boolean on = cl.perms.getOrDefault(r, java.util.Set.of()).contains(perm);
                        int slot = (r == Clan.Role.OFFICER ? 9 : 18) + col;
                        set(slot, Items.of(on ? Material.LIME_DYE : Material.GRAY_DYE).name((on ? "&aРазрешено" : "&cЗапрещено") + " &7— " + perm.display)
                                .lore("&eНажмите, чтобы переключить").build(), c -> {
                            clans.togglePerm(viewer, r, perm);
                            refresh();
                        });
                    }
                    col++;
                }
                set(27, Items.of(Material.BOOK).name("&7Лидер имеет все права").lore("&7Снимать деньги из банка может только лидер").build());
                if (parent != null) parent(parent);
                backButton(31);
            }
        }.open();
    }

    private void allies(Player p, Clan cl, Menu parent) {
        new PagedMenu(p, "&8Альянсы [" + cl.tag + "]") {
            @Override
            protected List<Entry> entries() {
                List<Entry> out = new ArrayList<>();
                for (Long id : cl.allies) {
                    Clan a = clans.byId(id);
                    if (a == null) continue;
                    out.add(new Entry(Items.of(Material.LIME_BANNER).name("&a[" + a.tag + "] &f" + a.name).lore("&cПКМ — расторгнуть союз").build(), c -> {
                        if (c.right()) ConfirmMenu.open(viewer, "&8Расторгнуть союз?", null, List.of("&7С кланом [" + a.tag + "]"), () -> clans.breakAlly(viewer, a));
                    }));
                }
                return out;
            }

            @Override
            protected void bottomBar() {
                set(47, Items.of(Material.EMERALD).name("&aПредложить союз").lore("&7Или принять входящее предложение").build(), c ->
                        ChatInput.ask(viewer, Msg.t("clan.ask-ally", "&fТег клана для союза:"), s -> clans.requestAlly(viewer, clans.byTag(s))));
            }
        }.parent(parent).open();
    }

    public void wars(Player p, Menu parent) {
        Clan cl = clans.clanOf(p);
        new PagedMenu(p, "&8Войны кланов") {
            @Override
            protected List<Entry> entries() {
                List<Entry> out = new ArrayList<>();
                for (WarService.War w : plugin.wars().active()) {
                    Clan a = clans.byId(w.attacker), d = clans.byId(w.defender);
                    if (a == null || d == null) continue;
                    boolean mine = cl != null && w.involves(cl.id);
                    long left = ("OVERTIME".equals(w.state) ? w.overtimeUntil : w.endsAt) - System.currentTimeMillis();
                    List<String> lore = new ArrayList<>(List.of("&7Счёт: &f" + w.scoreA + " : " + w.scoreD,
                            "OVERTIME".equals(w.state) ? "&6Дополнительное время: до первого очка" : "&7Осталось: &f" + Text.duration(left)));
                    if (mine) lore.add("&eНажмите, чтобы предложить/принять мир");
                    out.add(new Entry(Items.of(mine ? Material.NETHERITE_SWORD : Material.IRON_SWORD).name("&c[" + a.tag + "] &7vs &c[" + d.tag + "]").lore(lore).build(), c -> {
                        if (!mine) return;
                        Clan enemy = clans.byId(w.enemyOf(cl.id));
                        viewer.closeInventory();
                        plugin.wars().proposePeace(viewer, enemy);
                    }));
                }
                return out;
            }

            @Override
            protected void bottomBar() {
                if (cl != null) set(47, Items.of(Material.TNT).name("&cОбъявить войну").lore("&7Длительность до 7 дней", "&7Очки: убийства и разрушение построек врага").build(), c ->
                        ChatInput.ask(viewer, Msg.t("clan.ask-war", "&fТег клана, которому объявить войну:"), s -> plugin.wars().declare(viewer, clans.byTag(s))));
            }
        }.parent(parent).open();
    }

    public void confirmLeave(Player p) {
        Clan cl = clans.clanOf(p);
        if (cl == null) { Msg.error(p, "clan.none", "Вы не состоите в клане."); return; }
        boolean leader = cl.leader.equals(p.getUniqueId());
        ConfirmMenu.open(p, "&8Покинуть клан?", null, leader && cl.members.size() > 1
                ? List.of("&7Вы лидер. Чтобы выбрать преемника вручную:", "&f/clan leader <ник>", "&7Иначе преемник будет выбран автоматически", "&7по активности и вкладу в банк.")
                : leader ? List.of("&cВы последний участник — клан будет распущен.", "&7Средства банка вернутся вам.") : List.of("&7Ваши вещи останутся у вас,", "&7средства клана — в банке."),
                () -> clans.leave(p));
    }

    public void confirmDisband(Player p, Clan cl) {
        ConfirmMenu.open(p, "&4Распустить клан?", null, List.of("&cДействие необратимо.", "&7Банк (" + Text.money(cl.bank) + ") вернётся лидеру.",
                "&7Активные войны будут проиграны."), () -> clans.disband(p, cl));
    }
}
