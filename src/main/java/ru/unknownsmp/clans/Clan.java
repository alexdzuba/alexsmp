package ru.unknownsmp.clans;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Кэш клана (источник истины — БД). */
public final class Clan {
    public enum Role {
        LEADER("&6Лидер"), OFFICER("&eОфицер"), MEMBER("&7Участник");

        public final String display;

        Role(String d) { this.display = d; }
    }

    public enum Perm {
        INVITE("Приглашать игроков"), KICK("Исключать участников"), HOME_SET("Устанавливать точку клана"),
        WAR("Объявлять войну и предлагать мир"), ALLY("Управлять альянсами");

        public final String display;

        Perm(String d) { this.display = d; }
    }

    public record Member(UUID uuid, String name, Role role, long joinedAt) {}

    public final long id;
    public String tag;
    public String name;
    public UUID leader;
    public volatile long bank;
    public String home;
    public long createdAt;
    public int warsWon, warsLost;
    public final Map<UUID, Member> members = new LinkedHashMap<>();
    public final Set<Long> allies = ConcurrentHashMap.newKeySet();
    public final Map<Role, Set<Perm>> perms = new HashMap<>();

    public Clan(long id) {
        this.id = id;
        perms.put(Role.OFFICER, EnumSet.of(Perm.INVITE, Perm.KICK, Perm.HOME_SET));
        perms.put(Role.MEMBER, EnumSet.noneOf(Perm.class));
    }

    public boolean can(UUID u, Perm p) {
        Member m = members.get(u);
        if (m == null) return false;
        if (m.role() == Role.LEADER) return true;
        return perms.getOrDefault(m.role(), Set.of()).contains(p);
    }

    public Role role(UUID u) {
        Member m = members.get(u);
        return m == null ? null : m.role();
    }

    public String permsString() {
        StringBuilder sb = new StringBuilder();
        for (Role r : new Role[]{Role.OFFICER, Role.MEMBER}) {
            if (!sb.isEmpty()) sb.append(';');
            sb.append(r.name()).append('=');
            boolean first = true;
            for (Perm p : perms.getOrDefault(r, Set.of())) {
                if (!first) sb.append(',');
                sb.append(p.name());
                first = false;
            }
        }
        return sb.toString();
    }

    public void parsePerms(String s) {
        if (s == null || s.isBlank()) return;
        for (String part : s.split(";")) {
            String[] kv = part.split("=", 2);
            try {
                Role r = Role.valueOf(kv[0]);
                Set<Perm> set = EnumSet.noneOf(Perm.class);
                if (kv.length > 1 && !kv[1].isBlank()) for (String p : kv[1].split(",")) set.add(Perm.valueOf(p));
                perms.put(r, set);
            } catch (IllegalArgumentException ignored) {}
        }
    }

    public String display() {
        return "&8[&d" + tag + "&8]";
    }
}
