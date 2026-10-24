package ru.unknownsmp.world;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.FaceAttachable;
import org.bukkit.block.data.type.Lectern;


import java.util.Map;
import java.util.Random;
import java.util.function.BiConsumer;

/**
 * Развлечения для лобби и города: диско-пол, батуты, кликер, колесо удачи, тир, снежки, салют, колодец желаний,
 * парящие острова и тории. Все точки взаимодействия сохраняются как «якоря» с суффиксом площадки (_l — лобби, _c — город).
 * Логика взаимодействия — в классе lobby.LobbyInteractions.
 */
public final class Fun {
    /** Подписи-голограммы: id площадки -> текст (создаёт lobby.FunInteractions по якорям holo_<id><суффикс>). */
    public static final Map<String, String> TEXT = Map.of(
            "disco", "&d&lДИСКОТЕКА\n&7Танцуйте — пол светится",
            "tramp", "&a&lБАТУТЫ\n&7Прыгайте до небес",
            "clicker", "&6&lКЛИКЕР\n&7Жмите на золото — кто больше?",
            "wheel", "&e&lКОЛЕСО УДАЧИ\n&7Рычаг — раз в 20 минут",
            "snow", "&b&lСНЕЖКИ\n&7ПКМ по снегу — взять снежки\n&7Попадайте друг в друга!",
            "tir", "&c&lТИР\n&7ПКМ по кафедре — лук и стрелы\n&7Очки: чем ближе к центру, тем больше",
            "fw", "&c&lСАЛЮТ\n&7Дерните рычаг!",
            "launch", "&b&lКАТАПУЛЬТА",
            "well", "&3&lКОЛОДЕЦ ЖЕЛАНИЙ\n&7ПКМ — бросить монету (100)\n&7Вдруг повезёт?");

    public static final int DISCO_SIZE = 11;

    private final World w;
    private final Canvas g;
    private final BiConsumer<String, Location> anchor;
    private final Random rnd;
    private final String sfx;

    public Fun(World w, Canvas g, BiConsumer<String, Location> anchor, Random rnd, String sfx) {
        this.w = w;
        this.g = g;
        this.anchor = anchor;
        this.rnd = rnd;
        this.sfx = sfx;
    }

    private int oy() { return g.oy; }

    private void label(String id, double x, double ly, double z, String text) {
        String name = "holo_" + id + sfx;
        anchor.accept(name, new Location(w, x, oy() + ly, z));

    }

    private void button(int x, int ly, int z, Material m) {
        Block b = g.at(x, ly, z);
        b.setType(m, false);
        if (b.getBlockData() instanceof FaceAttachable fa) {
            fa.setAttachedFace(FaceAttachable.AttachedFace.FLOOR);
            b.setBlockData(fa, false);
        }
    }

    // ------------------------------------------------------------------ Диско

    /** Танцпол 11×11: плитки меняют цвет, пока рядом есть игроки. */
    public void disco(int x, int z) {
        int h = DISCO_SIZE / 2;
        for (int dx = -h - 1; dx <= h + 1; dx++) for (int dz = -h - 1; dz <= h + 1; dz++) {
            boolean edge = Math.abs(dx) == h + 1 || Math.abs(dz) == h + 1;
            g.set(x + dx, 0, z + dz, edge ? Material.POLISHED_BLACKSTONE_BRICKS : Material.WHITE_CONCRETE);
            for (int dy = 1; dy <= 6; dy++) if (!edge || dy > 0) g.set(x + dx, dy, z + dz, Material.AIR);
        }
        for (int[] c : new int[][]{{-h - 1, -h - 1}, {h + 1, -h - 1}, {-h - 1, h + 1}, {h + 1, h + 1}}) {
            for (int dy = 1; dy <= 5; dy++) g.set(x + c[0], dy, z + c[1], dy == 5 ? Material.SEA_LANTERN : Material.POLISHED_BLACKSTONE_BRICKS);
            g.set(x + c[0], 6, z + c[1], Material.END_ROD);
        }
        // Колонки по краям.
        for (int i = -3; i <= 3; i += 3) {
            g.set(x + i, 1, z - h - 1, Material.NOTE_BLOCK);
            g.set(x + i, 2, z - h - 1, Material.NOTE_BLOCK);
            g.set(x + i, 1, z + h + 1, Material.NOTE_BLOCK);
        }
        g.set(x, 7, z, Material.SEA_LANTERN);
        anchor.accept("disco" + sfx, new Location(w, x + 0.5, oy() + 1, z + 0.5));
        label("disco", x + 0.5, 3.5, z + 0.5 - h - 1, "&d&lДИСКОТЕКА\n&7Танцуйте — пол светится");
    }

    // ------------------------------------------------------------------ Батуты

    public void trampolines(int x, int z, int wd, int dp) {
        for (int dx = -1; dx <= wd; dx++) for (int dz = -1; dz <= dp; dz++) {
            boolean edge = dx == -1 || dz == -1 || dx == wd || dz == dp;
            g.set(x + dx, 0, z + dz, edge ? Material.STONE_BRICKS : ((dx / 3 + dz / 3) % 2 == 0 ? Material.SLIME_BLOCK : Material.LIME_CONCRETE));
            for (int dy = 1; dy <= 3; dy++) g.set(x + dx, dy, z + dz, Material.AIR);
            if (edge) g.set(x + dx, 1, z + dz, Material.OAK_FENCE);
        }
        // Вход с юга.
        for (int dx = wd / 2 - 1; dx <= wd / 2 + 1; dx++) g.set(x + dx, 1, z + dp, Material.AIR);
        for (int dx = 0; dx < wd; dx++) for (int dz = 0; dz < dp; dz++) {
            if ((dx / 3 + dz / 3) % 2 == 0) g.set(x + dx, 0, z + dz, Material.SLIME_BLOCK);
        }
        g.lantern(x - 1, 2, z - 1, false);
        g.lantern(x + wd, 2, z - 1, false);
        label("tramp", x + wd / 2.0 + 0.5, 3.2, z + dp / 2.0, "&a&lБАТУТЫ\n&7Прыгайте до небес");
    }

    // ------------------------------------------------------------------ Кликер

    public void clicker(int x, int z) {
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            g.set(x + dx, 0, z + dz, Math.max(Math.abs(dx), Math.abs(dz)) == 2 ? Material.POLISHED_BLACKSTONE_BRICKS : Material.GOLD_BLOCK);
            for (int dy = 1; dy <= 4; dy++) g.set(x + dx, dy, z + dz, Material.AIR);
        }
        g.set(x, 1, z, Material.CHISELED_QUARTZ_BLOCK);
        g.set(x, 2, z, Material.RAW_GOLD_BLOCK);
        for (int[] c : new int[][]{{-2, -2}, {2, -2}, {-2, 2}, {2, 2}}) {
            g.fill(x + c[0], 1, z + c[1], x + c[0], 2, z + c[1], Material.POLISHED_BLACKSTONE_BRICK_WALL);
            g.lantern(x + c[0], 3, z + c[1], false);
        }
        anchor.accept("clicker" + sfx, new Location(w, x + 0.5, oy() + 2, z + 0.5));
        label("clicker", x + 0.5, 4.2, z + 0.5, "&6&lКЛИКЕР\n&7Жмите на золото — кто больше?");
        anchor.accept("board_clicks" + sfx, new Location(w, x + 0.5, oy() + 8.2, z + 0.5));
    }

    // ------------------------------------------------------------------ Колесо удачи

    /** Колесо удачи. south — лицевая сторона (цветная, с рычагом) смотрит на юг (+z), иначе на север. */
    public void wheel(int x, int z, boolean south) {
        int cy = 8, r = 6;
        int sg = south ? 1 : -1;
        int front = south ? z + 1 : z, back = south ? z : z + 1;
        Material[] sector = {Material.RED_CONCRETE, Material.YELLOW_CONCRETE, Material.LIME_CONCRETE, Material.LIGHT_BLUE_CONCRETE,
                Material.PURPLE_CONCRETE, Material.ORANGE_CONCRETE, Material.MAGENTA_CONCRETE, Material.CYAN_CONCRETE};
        for (int dx = -r - 1; dx <= r + 1; dx++) for (int dy = -r - 1; dy <= r + 1; dy++) {
            double d = Math.hypot(dx, dy);
            if (d > r + 0.5) continue;
            int sec = (int) Math.floor((Math.atan2(dy, dx) + Math.PI) / (Math.PI * 2) * 8);
            Material m = d > r - 0.6 ? Material.GOLD_BLOCK : d < 1.5 ? Material.GOLD_BLOCK : sector[sec % 8];
            g.set(x + dx, cy + dy, front, m);
            g.set(x + dx, cy + dy, back, Material.DEEPSLATE_BRICKS);
        }
        for (int dy = 1; dy <= 3; dy++) { g.set(x - r - 2, dy, z, Material.POLISHED_BLACKSTONE_BRICKS); g.set(x + r + 2, dy, z, Material.POLISHED_BLACKSTONE_BRICKS); }
        g.fill(x - r - 2, 0, z, x + r + 2, 0, z, Material.POLISHED_BLACKSTONE_BRICKS);
        g.fill(x - 1, 1, z, x + 1, cy - r - 1, z, Material.POLISHED_BLACKSTONE_BRICKS);
        g.set(x, cy + r + 2, front, Material.RED_CONCRETE);
        g.set(x, cy + r + 3, front, Material.RED_CONCRETE);
        int lz = south ? z + 3 : z - 3;
        g.set(x, 0, lz, Material.POLISHED_BLACKSTONE_BRICKS);
        button(x, 1, lz, Material.LEVER);
        g.lantern(x - 2, 1, lz, false);
        g.lantern(x + 2, 1, lz, false);
        anchor.accept("wheel_lever" + sfx, new Location(w, x + 0.5, oy() + 1, lz + 0.5));
        label("wheel", x + 0.5, 2.6, lz + 0.5, "&e&lКОЛЕСО УДАЧИ\n&7Рычаг — раз в 20 минут");
    }

    // ------------------------------------------------------------------ Снежки

    public void snowPile(int x, int z, boolean withLabel) {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            g.set(x + dx, 0, z + dz, Material.SNOW_BLOCK);
            int h = (Math.abs(dx) + Math.abs(dz) == 0) ? 3 : (Math.abs(dx) + Math.abs(dz) == 1 ? 2 : 1);
            for (int dy = 1; dy <= h; dy++) g.set(x + dx, dy, z + dz, Material.SNOW_BLOCK);
        }
        if (withLabel) label("snow", x + 0.5, 4.4, z + 0.5, "&b&lСНЕЖКИ\n&7ПКМ по снегу — взять снежки\n&7Попадайте друг в друга!");
    }

    // ------------------------------------------------------------------ Тир

    public void tirLectern(int x, int z) {
        g.set(x, 0, z, Material.POLISHED_BLACKSTONE_BRICKS);
        g.set(x, 1, z, Material.AIR);
        Block b = g.at(x, 1, z);
        b.setType(Material.LECTERN, false);
        if (b.getBlockData() instanceof Lectern l) {
            l.setFacing(g.face(0, -1));
            b.setBlockData(l, false);
        }
        anchor.accept("tir" + sfx, new Location(w, x + 0.5, oy() + 1, z + 0.5));
        label("tir", x + 0.5, 2.8, z + 0.5, "&c&lТИР\n&7ПКМ по кафедре — лук и стрелы\n&7Стреляйте по мишеням: ближе к центру — больше очков");
        anchor.accept("board_tir" + sfx, new Location(w, x + 0.5, oy() + 5.4, z + 0.5));
    }

    /** Ряд мишеней на стойках по линии z = z0, x от x1 до x2 (шаг 4). */
    public void targets(int x1, int x2, int z0, int ly) {
        for (int x = x1; x <= x2; x += 4) {
            g.fill(x, 0, z0, x, ly - 1, z0, Material.OAK_FENCE);
            g.set(x, ly, z0, Material.TARGET);
            g.set(x, ly + 1, z0, Material.AIR);
        }
    }

    // ------------------------------------------------------------------ Салют

    public void fireworkPedestal(int x, int z) {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) g.set(x + dx, 0, z + dz, Material.POLISHED_ANDESITE);
        g.set(x, 1, z, Material.CHISELED_STONE_BRICKS);
        button(x, 2, z, Material.LEVER);
        anchor.accept("fw_lever" + sfx, new Location(w, x + 0.5, oy() + 2, z + 0.5));
        int[][] pts = {{-9, -9}, {9, -9}, {-9, 9}, {9, 9}};
        for (int i = 0; i < 4; i++) anchor.accept("fw" + i + sfx, new Location(w, x + pts[i][0] + 0.5, oy() + 3, z + pts[i][1] + 0.5));
        label("fw", x + 0.5, 3.8, z + 0.5, "&c&lСАЛЮТ\n&7Дерните рычаг!");
    }

    // ------------------------------------------------------------------ Катапульта

    public void launchPad(int x, int z) {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) g.set(x + dx, 0, z + dz, Material.LIGHT_BLUE_CONCRETE);
        g.set(x, 0, z, Material.SLIME_BLOCK);
        g.set(x, 1, z, Material.STONE_PRESSURE_PLATE);
        label("launch", x + 0.5, 2.4, z + 0.5, "&b&lКАТАПУЛЬТА");
    }

    // ------------------------------------------------------------------ Колодец желаний

    public void wishingWell(int x, int z) {
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            g.set(x + dx, 0, z + dz, Material.STONE_BRICKS);
            if (Math.max(Math.abs(dx), Math.abs(dz)) == 1) g.set(x + dx, 1, z + dz, Material.COBBLESTONE_WALL);
            else if (Math.max(Math.abs(dx), Math.abs(dz)) == 2) g.set(x + dx, 1, z + dz, Material.AIR);
        }
        g.set(x, 0, z, Material.WATER);
        g.set(x, -1, z, Material.WATER);
        g.set(x, 1, z, Material.AIR);
        for (int[] o : new int[][]{{-1, -1}, {1, -1}, {-1, 1}, {1, 1}}) g.fill(x + o[0], 2, z + o[1], x + o[0], 3, z + o[1], Material.OAK_FENCE);
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) g.slab(x + dx, 4, z + dz, Material.SPRUCE_SLAB, false);
        g.lantern(x, 3, z, true);
        anchor.accept("well" + sfx, new Location(w, x + 0.5, oy() + 1, z + 0.5));
        label("well", x + 0.5, 5.6, z + 0.5, "&3&lКОЛОДЕЦ ЖЕЛАНИЙ\n&7ПКМ — бросить монету (100)\n&7Вдруг повезёт?");
    }

    // ------------------------------------------------------------------ Декор

    /** Парящий островок с деревом. y — абсолютная высота верхнего слоя. */
    public void floatingIsland(int x, int y, int z, int r, boolean cherry) {
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            double d = Math.hypot(dx, dz) + Math.sin(dx * 0.7) * 0.6;
            if (d > r) continue;
            int depth = (int) ((r - d) * 1.1) + 1;
            w.getBlockAt(x + dx, y, z + dz).setType(d > r - 1.5 ? Material.MOSS_BLOCK : Material.GRASS_BLOCK, false);
            for (int k = 1; k <= depth; k++) {
                w.getBlockAt(x + dx, y - k, z + dz).setType(k <= 2 ? Material.DIRT : (rnd.nextInt(12) == 0 ? Material.COBBLESTONE : Material.STONE), false);
            }
            if (rnd.nextInt(6) == 0 && d < r - 1.5) w.getBlockAt(x + dx, y + 1, z + dz).setType(Material.SHORT_GRASS, false);
            else if (rnd.nextInt(14) == 0 && d < r - 1.5) w.getBlockAt(x + dx, y + 1, z + dz).setType(new Material[]{Material.PINK_TULIP, Material.ALLIUM, Material.CORNFLOWER}[rnd.nextInt(3)], false);
        }
        Canvas c = new Canvas(w, 0, y, 0, 1, 0, 0, 1);
        w.getBlockAt(x, y + 1, z).setType(Material.AIR, false);
        Structures.tree(c, x, z, cherry ? "cherry" : "oak", rnd);
        w.getBlockAt(x + 2, y + 1, z + 2).setType(Material.LANTERN, false);
    }

    /** Тории: два столба и перекладины. alongX — проём вдоль оси X (идёт дорога вдоль X). */
    public void torii(int x, int z, boolean roadAlongX) {
        int span = 4;
        for (int s : new int[]{-span, span}) {
            int px = roadAlongX ? x : x + s, pz = roadAlongX ? z + s : z;
            for (int dy = 0; dy <= 7; dy++) g.set(px, dy, pz, Material.RED_TERRACOTTA);
            g.set(px, 0, pz, Material.POLISHED_BLACKSTONE);
        }
        for (int t = -span - 2; t <= span + 2; t++) {
            int px = roadAlongX ? x : x + t, pz = roadAlongX ? z + t : z;
            g.set(px, 8, pz, Material.DARK_OAK_PLANKS);
            if (Math.abs(t) <= span + 1) g.set(px, 6, pz, Material.BLACK_CONCRETE);
            if (Math.abs(t) >= span) g.slab(px, 9, pz, Material.DARK_OAK_SLAB, false);
        }
        for (int t = -span - 1; t <= span + 1; t++) {
            int px = roadAlongX ? x : x + t, pz = roadAlongX ? z + t : z;
            g.slab(px, 9, pz, Material.DARK_OAK_SLAB, false);
        }
        g.lantern(roadAlongX ? x : x, 5, roadAlongX ? z : z, true);
        g.set(roadAlongX ? x : x, 6, roadAlongX ? z : z, Material.BLACK_CONCRETE);
    }
}
