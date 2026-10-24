package ru.unknownsmp.npc;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import ru.unknownsmp.core.Msg;
import ru.unknownsmp.core.UnknownSMP;
import ru.unknownsmp.gui.Items;
import ru.unknownsmp.gui.Menu;
import ru.unknownsmp.profile.Profile;
import ru.unknownsmp.quests.QuestService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Разветвлённые диалоги NPC. Варианты ответа могут требовать условий (выполненные задания, флаги,
 * ранг, репутация) и выполнять действия (выдать задание, сдать предметы, установить флаг, изменить репутацию).
 * Выборы игрока сохраняются как флаги и влияют на дальнейшие ответы.
 */
public final class DialogueService {
    public record Option(String text, String next, List<String> actions, String requires) {}

    public record Node(String id, List<String> text, List<Option> options) {}

    public record Dialogue(String id, String start, Map<String, Node> nodes) {}

    private final UnknownSMP plugin;
    private final NpcService npcs;
    private final Map<String, Dialogue> dialogues = new LinkedHashMap<>();

    DialogueService(UnknownSMP plugin, NpcService npcs) {
        this.plugin = plugin;
        this.npcs = npcs;
        reload();
    }

    public void reload() {
        dialogues.clear();
        ConfigurationSection ds = plugin.configs().get("dialogues.yml").getConfigurationSection("dialogues");
        if (ds == null) return;
        for (String id : ds.getKeys(false)) {
            ConfigurationSection d = ds.getConfigurationSection(id);
            Map<String, Node> nodes = new LinkedHashMap<>();
            ConfigurationSection ns = d.getConfigurationSection("nodes");
            if (ns == null) continue;
            for (String nid : ns.getKeys(false)) {
                ConfigurationSection n = ns.getConfigurationSection(nid);
                List<Option> opts = new ArrayList<>();
                for (Map<?, ?> o : n.getMapList("options")) {
                    List<String> acts = new ArrayList<>();
                    Object a = o.get("action");
                    if (a instanceof List<?> l) for (Object x : l) acts.add(String.valueOf(x));
                    else if (a != null) acts.add(String.valueOf(a));
                    opts.add(new Option(String.valueOf(o.get("text")), o.get("next") == null ? null : String.valueOf(o.get("next")), acts,
                            o.get("requires") == null ? null : String.valueOf(o.get("requires"))));
                }
                nodes.put(nid, new Node(nid, n.getStringList("text"), opts));
            }
            dialogues.put(id, new Dialogue(id, d.getString("start", nodes.isEmpty() ? null : nodes.keySet().iterator().next()), nodes));
        }
    }

    public void start(Player p, NpcService.Npc npc, String dialogueId) {
        Dialogue d = dialogues.get(dialogueId);
        if (d == null || d.start() == null) {
            Msg.send(p, "npc.no-dialogue", "&7{n}&7 не хочет разговаривать.", "n", npc.name());
            return;
        }
        show(p, npc, d, d.start());
    }

    private void show(Player p, NpcService.Npc npc, Dialogue d, String nodeId) {
        Node node = d.nodes().get(nodeId);
        if (node == null) { p.closeInventory(); return; }
        List<Option> visible = new ArrayList<>();
        for (Option o : node.options()) if (check(p, o.requires())) visible.add(o);
        new Menu(p, 4, "&8" + ru.unknownsmp.core.Text.strip(npc.name())) {
            @Override
            protected void build() {
                fill(Items.filler());
                List<String> lore = new ArrayList<>();
                for (String t : node.text()) lore.add("&f" + t.replace("{player}", viewer.getName()));
                set(13, Items.of(Material.WRITABLE_BOOK).name(npc.name()).lore(lore).build());
                int[] slots = {19, 20, 21, 22, 23, 24, 25};
                for (int i = 0; i < visible.size() && i < slots.length; i++) {
                    Option o = visible.get(i);
                    set(slots[i], Items.of(Material.PAPER).name("&e» " + o.text()).build(), c -> choose(viewer, npc, d, o));
                }
                if (visible.isEmpty()) set(22, Items.of(Material.BARRIER).name("&7Закончить разговор").build(), c -> viewer.closeInventory());
            }
        }.open();
    }

    private void choose(Player p, NpcService.Npc npc, Dialogue d, Option o) {
        if (!check(p, o.requires())) return;
        for (String a : o.actions()) {
            if (!act(p, npc, a)) return;
        }
        if (o.next() != null && d.nodes().containsKey(o.next())) show(p, npc, d, o.next());
        else if (o.actions().stream().noneMatch(a -> a.startsWith("open:") || a.startsWith("npc:"))) p.closeInventory();
    }

    /** Выполнить действие. false — прервать цепочку (например, задание нельзя взять). */
    private boolean act(Player p, NpcService.Npc npc, String action) {
        String[] a = action.split(":", 2);
        String arg = a.length > 1 ? a[1] : "";
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "close" -> p.closeInventory();
            case "quest" -> { return plugin.quests().accept(p, arg); }
            case "deliver" -> { return plugin.quests().deliver(p, arg); }
            case "flag" -> plugin.profiles().unlock(p, "flag", arg);
            case "unflag" -> plugin.profiles().lock(p, "flag", arg);
            case "rep" -> plugin.progression().addReputation(p, Long.parseLong(arg), "dialogue:" + npc.id());
            case "title" -> {
                if (plugin.profiles().unlock(p, "title", arg)) Msg.ok(p, "title.unlocked", "Открыт титул: {t}", "t", plugin.progression().titleName(arg));
            }
            case "message" -> Msg.send(p, "npc.msg", "&7{m}", "m", arg);
            case "open", "npc" -> npcs.runAction(p, npc, arg);
            default -> plugin.getLogger().warning("Неизвестное действие диалога: " + action);
        }
        return true;
    }

    /**
     * Условия через запятую: quest_done:id, quest_active:id, !quest_done:id, flag:x, !flag:x, rank:id,
     * rep>=N, level>=N, nether_open, end_open.
     */
    public boolean check(Player p, String requires) {
        if (requires == null || requires.isBlank()) return true;
        Profile pr = plugin.profiles().get(p);
        if (pr == null) return false;
        Map<String, QuestService.Progress> q = plugin.quests().of(p);
        for (String raw : requires.split(",")) {
            String r = raw.trim();
            boolean neg = r.startsWith("!");
            if (neg) r = r.substring(1);
            boolean ok;
            if (r.startsWith("quest_done:")) {
                var x = q.get(r.substring(11));
                ok = x != null && x.state.equals("DONE");
            } else if (r.startsWith("quest_active:")) {
                var x = q.get(r.substring(13));
                ok = x != null && x.state.equals("ACTIVE");
            } else if (r.startsWith("quest_taken:")) {
                ok = q.containsKey(r.substring(12));
            } else if (r.startsWith("flag:")) {
                ok = pr.has("flag", r.substring(5));
            } else if (r.startsWith("rank:")) {
                ok = r.substring(5).equals(pr.progRank);
            } else if (r.startsWith("rep>=")) {
                ok = pr.reputation >= Long.parseLong(r.substring(5));
            } else if (r.startsWith("level>=")) {
                ok = pr.level >= Integer.parseInt(r.substring(7));
            } else if (r.equals("nether_open")) {
                ok = plugin.dimensions().netherOpen();
            } else if (r.equals("end_open")) {
                ok = plugin.dimensions().endOpen();
            } else {
                ok = false;
            }
            if (ok == neg) return false;
        }
        return true;
    }
}
