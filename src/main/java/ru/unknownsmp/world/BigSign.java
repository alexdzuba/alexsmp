package ru.unknownsmp.world;

import org.bukkit.Material;

/**
 * Гигантская вывеска, парящая высоко в воздухе: объёмные буквы названия (толщина 3 блока, сзади светящийся слой),
 * слово «SMP» и логотип. Строится на холсте {@link Canvas}: зритель стоит со стороны z &lt; 0, точка (0,0) — низ по центру.
 */
public final class BigSign {
    private BigSign() {}

    public static final int WIDTH = 116;
    public static final Material[] LOGO = {Material.LIGHT_BLUE_CONCRETE, Material.BLUE_CONCRETE, Material.LIGHT_BLUE_TERRACOTTA,
            Material.CYAN_TERRACOTTA, Material.BLUE_TERRACOTTA};

    /** Три слоя буквы: лицевой, средний, задний (светится). */
    private static void layers(Canvas c, String s, int x0, int y0, int scale, Material front, Material mid) {
        c.text(s, x0, y0, 0, front, scale);
        c.text(s, x0, y0, 1, mid, scale);
        c.text(s, x0, y0, 2, Material.SEA_LANTERN, scale);
    }

    public static int scaleFor(String s, int width) {
        return Math.max(1, Math.min(6, width / Math.max(1, s.length() * 6 - 1)));
    }

    /** @return высота вывески в блоках. */
    public static int build(Canvas c, String title, String sub) {
        int ts = scaleFor(title, WIDTH), ss = scaleFor(sub, WIDTH);
        int ySub = 0, yTitle = ySub + 5 * ss + 5, yLogo = yTitle + 5 * ts + 6;
        layers(c, sub, -Canvas.textWidth(sub, ss) / 2, ySub, ss, Material.LIGHT_BLUE_CONCRETE, Material.BLUE_CONCRETE);
        layers(c, title, -Canvas.textWidth(title, ts) / 2, yTitle, ts, Material.WHITE_CONCRETE, Material.LIGHT_GRAY_CONCRETE);
        int h = 18, t = 5;
        for (int[] px : Canvas.Glyphs.logo(h, t)) {
            c.set(px[0], yLogo + px[1], 0, LOGO[px[2]]);
            c.set(px[0], yLogo + px[1], 1, Material.BLUE_CONCRETE);
            c.set(px[0], yLogo + px[1], 2, Material.SEA_LANTERN);
        }
        return yLogo + h;
    }
}
