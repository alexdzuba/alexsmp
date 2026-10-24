package ru.unknownsmp.core;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import ru.unknownsmp.db.Database;
import ru.unknownsmp.profile.Profile;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Собственная система служебных ролей и разрешений (не связана с прогрессией игроков).
 * Роли и их разрешения задаются в permissions.yml. Консоль имеет все права.
 */
public final class Roles implements Module {
    public record Role(String id, String display, int weight, Set<String> perms) {}

    private final UnknownSMP plugin;
    private final Map<String, Role> roles = new LinkedHashMap<>();

    public Roles(UnknownSMP plugin) {
        this.plugin = plugin;
    }

    @Override public String id() { return "roles"; }
    @Override public String title() { return "Роли и права"; }

    @Override
    public void enable() {
        load();
    }

    @Override
    public void reload() {
        load();
    }

    private void load() {
        roles.clear();
        ConfigurationSection sec = plugin.configs().get("permissions.yml").getConfigurationSection("roles");
        Map<String, List<String>> inherits = new LinkedHashMap<>();
        Map<String, Set<String>> raw = new LinkedHashMap<>();
        Map<String, String> display = new LinkedHashMap<>();
        Map<String, Integer> weight = new LinkedHashMap<>();
        if (sec != null) {
            for (String id : sec.getKeys(false)) {
                raw.put(id, new HashSet<>(sec.getStringList(id + ".permissions")));
                inherits.put(id, sec.getStringList(id + ".inherits"));
                display.put(id, sec.getString(id + ".display", id));
                weight.put(id, sec.getInt(id + ".weight", 0));
            }
        }
        if (!raw.containsKey("player")) { raw.put("player", new HashSet<>(List.of("usmp.player.*"))); display.put("player", "Игрок"); weight.put("player", 0); }
        if (!raw.containsKey("owner")) { raw.put("owner", new HashSet<>(List.of("*"))); display.put("owner", "&4Владелец"); weight.put("owner", 100); }
        for (String id : raw.keySet()) {
            Set<String> all = new HashSet<>();
            collect(id, raw, inherits, all, new HashSet<>());
            roles.put(id, new Role(id, display.get(id), weight.getOrDefault(id, 0), all));
        }
    }

    private void collect(String id, Map<String, Set<String>> raw, Map<String, List<String>> inh, Set<String> out, Set<String> seen) {
        if (!seen.add(id)) return;
        Set<String> r = raw.get(id);
        if (r != null) out.addAll(r);
        for (String p : inh.getOrDefault(id, List.of())) collect(p, raw, inh, out, seen);
    }

    public Role role(String id) {
        Role r = roles.get(id == null ? "player" : id);
        return r == null ? roles.get("player") : r;
    }

    public Role roleOf(Player p) {
        Profile pr = plugin.profiles() == null ? null : plugin.profiles().get(p);
        return role(pr == null ? "player" : pr.staffRole);
    }

    public Map<String, Role> all() { return roles; }

    public boolean has(CommandSender s, String perm) {
        if (!(s instanceof Player p)) return true;
        if (perm == null || perm.isEmpty()) return true;
        if (plugin.auth() != null && !plugin.auth().isAuthed(p)) return false;
        return matches(roleOf(p).perms(), perm);
    }

    public static boolean matches(Set<String> perms, String perm) {
        if (perms.contains("-" + perm)) return false;
        if (perms.contains("*") || perms.contains(perm)) return true;
        String cur = perm;
        int idx;
        while ((idx = cur.lastIndexOf('.')) > 0) {
            cur = cur.substring(0, idx);
            if (perms.contains("-" + cur + ".*")) return false;
            if (perms.contains(cur + ".*")) return true;
        }
        return false;
    }

    public boolean check(CommandSender s, String perm) {
        if (has(s, perm)) return true;
        Msg.error(s, "perm.denied", "Недостаточно прав.");
        return false;
    }

    public boolean isStaff(Player p) {
        return roleOf(p).weight() >= role("moderator").weight() && !roleOf(p).id().equals("player");
    }

    public boolean isOwner(CommandSender s) {
        if (!(s instanceof Player p)) return true;
        return roleOf(p).id().equals("owner");
    }

    /** Нельзя управлять ролью/наказанием того, у кого вес роли выше или равен (кроме владельца и консоли). */
    public boolean outranks(CommandSender actor, UUID target) {
        if (!(actor instanceof Player p)) return true;
        Profile t = plugin.profiles().get(target);
        if (t == null) return roleOf(p).weight() >= 100;
        if (roleOf(p).id().equals("owner")) return true;
        return roleOf(p).weight() > role(t.staffRole).weight();
    }

    public CompletableFuture<Integer> setRole(UUID target, String role) {
        Profile pr = plugin.profiles().get(target);
        if (pr != null) pr.staffRole = role;
        return plugin.db().tx(c -> Database.update(c, "UPDATE players SET staff_role=? WHERE uuid=?", role, target.toString()));
    }

    public List<String> ids() { return new ArrayList<>(roles.keySet()); }
}
