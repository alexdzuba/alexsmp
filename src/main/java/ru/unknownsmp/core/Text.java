package ru.unknownsmp.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Утилиты текста: цвета, форматирование денег и времени, сериализация координат. */
public final class Text {
    private Text() {}

    public static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&').hexColors().build();
    private static final LegacyComponentSerializer SECTION = LegacyComponentSerializer.legacySection();
    private static final Pattern DURATION = Pattern.compile("(\\d+)\\s*(d|h|m|s|д|ч|м|с)", Pattern.CASE_INSENSITIVE);
    private static ZoneId zone = ZoneId.systemDefault();
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    public static void setZone(ZoneId z) { zone = z; }
    public static ZoneId zone() { return zone; }

    /** Цветной компонент из строки с &-кодами (и &#RRGGBB). */
    public static Component c(String s) {
        if (s == null) return Component.empty();
        return LEGACY.deserialize(s.replace('§', '&'));
    }

    /** Компонент для предметов/GUI: без курсива по умолчанию. */
    public static Component item(String s) {
        return c(s).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public static List<Component> lore(List<String> lines) {
        List<Component> out = new ArrayList<>();
        if (lines != null) for (String l : lines) out.add(item(l));
        return out;
    }

    public static String plain(Component c) {
        return c == null ? "" : PlainTextComponentSerializer.plainText().serialize(c);
    }

    public static String strip(String s) {
        return plain(c(s));
    }

    public static String legacySection(String amp) {
        return SECTION.serialize(c(amp));
    }

    public static String money(long v) {
        String s = String.format(Locale.ROOT, "%,d", v).replace(',', ' ');
        return s + " ⛃";
    }

    public static String num(long v) {
        return String.format(Locale.ROOT, "%,d", v).replace(',', ' ');
    }

    public static String date(long epochMs) {
        if (epochMs <= 0) return "—";
        return DATE.format(Instant.ofEpochMilli(epochMs).atZone(zone));
    }

    public static String duration(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        long d = s / 86400; s %= 86400;
        long h = s / 3600; s %= 3600;
        long m = s / 60; s %= 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("д ");
        if (h > 0) sb.append(h).append("ч ");
        if (m > 0) sb.append(m).append("м ");
        if (s > 0 || sb.isEmpty()) sb.append(s).append("с");
        return sb.toString().trim();
    }

    /** Разбор длительности вида 1d2h30m, 15m, 7д. Возвращает -1 при ошибке. */
    public static long parseDuration(String in) {
        if (in == null || in.isBlank()) return -1;
        Matcher m = DURATION.matcher(in.trim());
        long total = 0;
        int end = 0;
        boolean any = false;
        while (m.find()) {
            if (m.start() != end) return -1;
            end = m.end();
            any = true;
            long n = Long.parseLong(m.group(1));
            switch (m.group(2).toLowerCase(Locale.ROOT)) {
                case "d", "д" -> total += n * 86_400_000L;
                case "h", "ч" -> total += n * 3_600_000L;
                case "m", "м" -> total += n * 60_000L;
                default -> total += n * 1000L;
            }
        }
        if (!any || end != in.trim().length()) return -1;
        return total;
    }

    /** Разбор суммы: 1000, 10k, 1.5m, 2кк. -1 при ошибке. */
    public static long parseAmount(String in) {
        if (in == null) return -1;
        String s = in.trim().toLowerCase(Locale.ROOT).replace(" ", "").replace(',', '.');
        long mult = 1;
        if (s.endsWith("кк") || s.endsWith("m")) { mult = 1_000_000L; s = s.substring(0, s.length() - (s.endsWith("кк") ? 2 : 1)); }
        else if (s.endsWith("к") || s.endsWith("k")) { mult = 1_000L; s = s.substring(0, s.length() - 1); }
        try {
            double d = Double.parseDouble(s);
            if (Double.isNaN(d) || Double.isInfinite(d) || d <= 0) return -1;
            double r = Math.floor(d * mult);
            if (r > 1e15) return -1;
            return (long) r;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static String loc(Location l) {
        if (l == null || l.getWorld() == null) return null;
        return l.getWorld().getName() + ";" + round(l.getX()) + ";" + round(l.getY()) + ";" + round(l.getZ())
                + ";" + round(l.getYaw()) + ";" + round(l.getPitch());
    }

    private static String round(double d) {
        return String.format(Locale.ROOT, "%.2f", d);
    }

    public static Location loc(String s) {
        if (s == null || s.isBlank()) return null;
        String[] p = s.split(";");
        if (p.length < 4) return null;
        World w = Bukkit.getWorld(p[0]);
        if (w == null) return null;
        try {
            Location l = new Location(w, Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]));
            if (p.length >= 6) { l.setYaw(Float.parseFloat(p[4])); l.setPitch(Float.parseFloat(p[5])); }
            return l;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String coords(Location l) {
        if (l == null) return "?";
        return l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ() + " (" + worldName(l.getWorld()) + ")";
    }

    public static String worldName(World w) {
        if (w == null) return "?";
        return switch (w.getEnvironment()) {
            case NETHER -> "Незер";
            case THE_END -> "Энд";
            default -> w.getName().equalsIgnoreCase(UnknownSMP.get().lobbyWorldName()) ? "Лобби" : "Мир";
        };
    }

    public static String fill(String s, Object... kv) {
        if (s == null) return "";
        for (int i = 0; i + 1 < kv.length; i += 2) {
            s = s.replace("{" + kv[i] + "}", String.valueOf(kv[i + 1]));
        }
        return s;
    }

    public static String bar(double progress, int len, String full, String empty) {
        progress = Math.max(0, Math.min(1, progress));
        int f = (int) Math.round(progress * len);
        return full + "|".repeat(f) + empty + "|".repeat(len - f);
    }
}
