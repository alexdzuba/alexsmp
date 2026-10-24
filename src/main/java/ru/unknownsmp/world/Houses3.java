package ru.unknownsmp.world;

import org.bukkit.Material;
import ru.unknownsmp.world.Houses.Pal;

import java.util.Random;

/** Третья часть набора: мельница, дозорная башня, торговые палатки и ещё несколько жилых домов. */
final class Houses3 {
    private Houses3() {}

    static void init() {
        Houses.reg("windmill", 13, 13, Houses3::windmill);
        Houses.reg("watchtower", 9, 9, Houses3::watchtower);
        for (int i = 0; i < 6; i++) { final int v = i; Houses.reg("stall" + i, 7, 5, (c, r) -> stall(c, r, v)); }
        Houses.reg("cottage_brick", 9, 9, Houses3::cottageBrick);
        Houses.reg("cottage_thatch", 11, 7, Houses3::cottageThatch);
        Houses.reg("stable", 15, 9, Houses3::stable);
    }

    // ------------------------------------------------------------------ Мельница

    private static void windmill(Canvas c, Random r) {
        int cx = 6, cz = 6, h = 12;
        c.fill(-1, -3, -1, 13, -1, 13, Material.COBBLESTONE);
        for (int y = 0; y <= h; y++) {
            double rad = 4.4 - y * 0.12;
            for (int dx = -5; dx <= 5; dx++) for (int dz = -5; dz <= 5; dz++) {
                double dd = Math.hypot(dx, dz);
                if (dd > rad) continue;
                boolean shell = dd > rad - 1.1;
                Material m = Material.AIR;
                if (y == 0) m = Material.SPRUCE_PLANKS;
                else if (shell) m = y <= 3 ? Material.STONE_BRICKS : Material.WHITE_TERRACOTTA;
                if (y == 0 || shell || y % 4 == 0) c.set(cx + dx, y, cz + dz, y % 4 == 0 && y > 0 && !shell ? Material.SPRUCE_PLANKS : m);
            }
        }
        for (int y : new int[]{5, 9}) for (int[] q : new int[][]{{3, 0}, {-3, 0}, {0, 3}, {0, -3}}) c.fill(cx + q[0], y, cz + q[1], cx + q[0], y + 1, cz + q[1], Material.GLASS_PANE);
        c.fill(cx, 1, cz - 4, cx, 3, cz - 4, Material.AIR);
        c.door(cx, 1, cz - 4, Material.DARK_OAK_DOOR, 0, -1);
        c.stair(cx, 0, cz - 5, Material.STONE_BRICK_STAIRS, 0, 1, false);
        // Колпак.
        int top = Kit.cone(c, cx, cz, 4, h + 1, Material.DARK_OAK_STAIRS, Material.DARK_OAK_PLANKS);
        c.set(cx, top, cz, Material.LIGHTNING_ROD);
        // Лопасти на фасаде: ступица и четыре плеча крестом (диагонали), полотнища из шерсти.
        int hy = 10, hz = cz - 5;
        c.fill(cx, hy - 1, hz, cx, hy + 1, hz, Material.DARK_OAK_LOG);
        c.set(cx, hy, hz - 1, Material.DARK_OAK_LOG);
        for (int k = 1; k <= 8; k++) {
            int[][] dirs = {{1, 1}, {-1, 1}, {1, -1}, {-1, -1}};
            for (int[] dd : dirs) {
                c.set(cx + dd[0] * k, hy + dd[1] * k, hz - 1, Material.DARK_OAK_FENCE);
                if (k >= 3) {
                    c.set(cx + dd[0] * k + dd[0], hy + dd[1] * k, hz - 1, Material.WHITE_WOOL);
                    c.set(cx + dd[0] * k, hy + dd[1] * k + (dd[0] == dd[1] ? -1 : 1), hz - 1, Material.WHITE_WOOL);
                }
            }
        }
        for (int i = 0; i < 4; i++) c.stair(cx - 2 + i % 2, 1 + i, cz + 2, Material.SPRUCE_STAIRS, 0, 1, false);
        c.dir(cx + 2, 1, cz - 2, Material.BARREL, 0, -1);
        c.dir(cx - 2, 1, cz - 2, Material.CHEST, 1, 0);
        for (int i = 0; i < 3; i++) c.set(cx - 3 + i, 1, cz + 3, Material.HAY_BLOCK);
        Kit.flowerBed(c, 0, -3, 3, 3, r);
        Kit.flowerBed(c, 10, -3, 3, 3, r);
    }

    // ------------------------------------------------------------------ Дозорная башня

    private static void watchtower(Canvas c, Random r) {
        int top = 17;
        Pal p = new Pal(Material.STONE_BRICKS, Material.DARK_OAK_LOG, Material.COBBLESTONE, Material.DARK_OAK_STAIRS, Material.DARK_OAK_PLANKS,
                Material.SPRUCE_TRAPDOOR, Material.SPRUCE_DOOR, Material.SPRUCE_PLANKS, Material.GLASS_PANE);
        c.fill(-1, -3, -1, 9, -1, 9, Material.COBBLESTONE);
        for (int dx = 1; dx <= 7; dx++) for (int dz = 1; dz <= 7; dz++) {
            boolean shell = dx == 1 || dx == 7 || dz == 1 || dz == 7;
            boolean corner = (dx == 1 || dx == 7) && (dz == 1 || dz == 7);
            c.set(dx, 0, dz, Material.COBBLESTONE);
            for (int y = 1; y <= top; y++) {
                Material m = Material.AIR;
                if (shell) m = corner ? Material.DARK_OAK_LOG : (y <= 3 ? Material.COBBLESTONE : ((dx * 3 + dz * 7 + y) % 9 == 0 ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS));
                else if (y % 6 == 0) m = Material.SPRUCE_PLANKS;
                c.set(dx, y, dz, m);
            }
        }
        for (int y = 4; y <= top - 3; y += 6) for (int o = -1; o <= 1; o += 2) {
            c.set(4 + o, y, 1, Material.GLASS_PANE); c.set(4 + o, y, 7, Material.GLASS_PANE);
            c.set(1, y, 4 + o, Material.GLASS_PANE); c.set(7, y, 4 + o, Material.GLASS_PANE);
        }
        for (int y = 1; y <= top; y++) {
            c.dir(4, y, 6, Material.LADDER, 0, -1);
            if (y % 6 == 0) { c.set(4, y, 6, Material.AIR); c.dir(4, y, 6, Material.LADDER, 0, -1); }
        }
        c.fill(4, 1, 1, 4, 2, 1, Material.AIR);
        c.door(4, 1, 1, p.door(), 0, -1);
        c.stair(4, 0, 0, Material.COBBLESTONE_STAIRS, 0, 1, false);
        // Смотровая площадка шире башни, с ограждением и крышей на столбах.
        for (int dx = -1; dx <= 9; dx++) for (int dz = -1; dz <= 9; dz++) {
            c.set(dx, top + 1, dz, Material.SPRUCE_PLANKS);
            if (dx == -1 || dx == 9 || dz == -1 || dz == 9) c.set(dx, top + 2, dz, Material.SPRUCE_FENCE);
        }
        c.set(4, top + 1, 6, Material.LADDER == null ? Material.AIR : Material.AIR);
        c.dir(4, top + 1, 6, Material.LADDER, 0, -1);
        for (int[] q : new int[][]{{-1, -1}, {9, -1}, {-1, 9}, {9, 9}}) { c.fill(q[0], top + 2, q[1], q[0], top + 5, q[1], Material.DARK_OAK_LOG); c.lantern(q[0], top + 6, q[1], false); }
        Kit.hip(c, -1, 9, -1, 9, top + 6, p.roofStair(), p.roofBlock(), 1);
        c.fill(4, top + 12, 4, 4, top + 14, 4, Material.OAK_FENCE);
        c.dir(4, top + 13, 3, Material.RED_WALL_BANNER, 0, -1);
        c.lantern(4, top + 5, 4, true);
        c.sign(5, 3, -1, 0, -1, "", "&bДозор", "", "");
    }

    // ------------------------------------------------------------------ Торговые палатки (6 вариантов)

    private static void stall(Canvas c, Random r, int v) {
        int w = 7, d = 4;
        Material[][] awn = {{Material.RED_WOOL, Material.WHITE_WOOL}, {Material.BLUE_WOOL, Material.WHITE_WOOL}, {Material.ORANGE_WOOL, Material.YELLOW_WOOL},
                {Material.PINK_WOOL, Material.WHITE_WOOL}, {Material.GREEN_WOOL, Material.WHITE_WOOL}, {Material.PURPLE_WOOL, Material.LIGHT_BLUE_WOOL}};
        Material[] goods = {Material.MELON, Material.BLUE_ICE, Material.HAY_BLOCK, Material.PINK_TULIP, Material.COMPOSTER, Material.CAULDRON};
        c.fill(0, 0, 0, w - 1, 0, d - 1, v % 2 == 0 ? Material.SPRUCE_PLANKS : Material.DARK_OAK_PLANKS);
        for (int x : new int[]{0, w - 1}) for (int z : new int[]{0, d - 1}) c.fill(x, 1, z, x, 3, z, Material.SPRUCE_FENCE);
        // Навес чуть скошенный: передняя кромка ниже задней.
        for (int x = -1; x <= w; x++) {
            Material m = (x + 1) % 2 == 0 ? awn[v][0] : awn[v][1];
            c.set(x, 4, -1, m);
            c.set(x, 4, 0, m);
            c.set(x, 5, 1, m);
            c.set(x, 5, 2, m);
            c.set(x, 4, 3, m);
            c.set(x, 4, 4, m);
        }
        // Задняя стенка, боковые решётки и полка с товаром — чтобы палатка не была «дырявой».
        Material back = v % 2 == 0 ? Material.SPRUCE_PLANKS : Material.DARK_OAK_PLANKS;
        c.fill(1, 1, d - 1, w - 2, 3, d - 1, back);
        for (int z = 1; z <= d - 2; z++) {
            for (int x : new int[]{0, w - 1}) {
                c.set(x, 1, z, Material.SPRUCE_FENCE);
                c.set(x, 2, z, Material.SPRUCE_FENCE);
                c.set(x, 3, z, Material.SPRUCE_FENCE);
            }
        }
        c.fill(1, 1, 0, w - 2, 1, 0, Material.OAK_PLANKS);
        c.fill(1, 2, 0, w - 2, 2, 0, Material.OAK_SLAB);
        for (int x = 1; x <= w - 2; x++) {
            switch (v) {
                case 0 -> c.set(x, 3, 0, x % 2 == 0 ? Material.RED_CANDLE : Material.AIR);
                case 1 -> c.set(x, 3, 0, Material.AIR);
                case 3 -> c.set(x, 3, 0, new Material[]{Material.POTTED_PINK_TULIP, Material.POTTED_ALLIUM, Material.POTTED_RED_TULIP}[x % 3]);
                default -> { }
            }
            c.set(x, 2, 0, v == 0 ? new Material[]{Material.MELON, Material.PUMPKIN, Material.MELON}[x % 3] : v == 2 ? Material.HAY_BLOCK : v == 4 ? Material.COMPOSTER : v == 1 ? Material.SNOW_BLOCK : v == 3 ? Material.MOSS_BLOCK : Material.CAULDRON);
            if (v == 2) c.set(x, 3, 0, Material.OAK_PRESSURE_PLATE);
        }
        for (int x : new int[]{1, 2, w - 3, w - 2}) c.dir(x, 1, d - 2, r.nextBoolean() ? Material.BARREL : Material.CHEST, 0, -1);
        c.lantern(w / 2, 3, 1, true);
        c.lantern(1, 3, 2, true);
        c.lantern(w - 2, 3, 2, true);
        c.sign(w / 2, 3, -1, 0, -1, "", new String[]{"&cФрукты", "&bРыба", "&6Хлеб", "&dЦветы", "&aОвощи", "&5Зелья"}[v], "", "");
        c.set(w / 2, 3, -1, Material.AIR);
        c.sign(w / 2, 3, -2, 0, -1, "", new String[]{"&cФрукты", "&bРыба", "&6Хлеб", "&dЦветы", "&aОвощи", "&5Зелья"}[v], "", "");
    }

    // ------------------------------------------------------------------ Кирпичный дом с ступенчатым фронтоном

    private static void cottageBrick(Canvas c, Random r) {
        int w = 9, d = 9, h = 5;
        Pal p = new Pal(Material.BRICKS, Material.STONE_BRICKS, Material.STONE_BRICKS, Material.DEEPSLATE_TILE_STAIRS, Material.DEEPSLATE_TILES,
                Material.WARPED_TRAPDOOR, Material.DARK_OAK_DOOR, Material.SPRUCE_PLANKS, Material.GLASS_PANE);
        Houses.foundation(c, 0, 0, w, d, Material.COBBLESTONE, p.floor());
        Houses.walls(c, 0, 0, w, d, 1, h, p, false);
        for (int y = 1; y <= h; y++) for (int x = 0; x < w; x++) if ((x + y) % 4 == 0) { c.set(x, y, 0, Material.STONE_BRICKS); c.set(x, y, d - 1, Material.STONE_BRICKS); }
        Houses.frontWindows(c, 0, w, 2, p, 4, true);
        c.fill(4, 1, 0, 4, 3, 0, Material.AIR);
        c.door(4, 1, 0, p.door(), 0, -1);
        c.stair(3, 1, 0, Material.BRICK_STAIRS, 1, 0, false);
        c.stair(5, 1, 0, Material.BRICK_STAIRS, -1, 0, false);
        c.stair(4, 0, -1, Material.STONE_BRICK_STAIRS, 0, 1, false);
        Houses.sideWindows(c, 0, 0, w, d, 2, p);
        Kit.window(c, 4, 5, 0, -1, p.glass(), p.shutter(), true, false);
        // Ступенчатый (голландский) фронтон на фасаде над двускатной крышей вдоль z.
        Kit.gable(c, 0, w - 1, 0, d - 1, h + 1, p.roofStair(), p.roofBlock(), p.plaster(), 0);
        for (int step = 0; step < 4; step++) {
            int y = h + 1 + step;
            for (int x = 0; x < w; x++) if (x < step || x > w - 1 - step) { c.set(x, y, 0, Material.AIR); }
            for (int x = step; x <= w - 1 - step; x++) c.set(x, y, 0, p.plaster());
            c.set(step, y + 1, 0, Material.STONE_BRICK_SLAB);
            c.set(w - 1 - step, y + 1, 0, Material.STONE_BRICK_SLAB);
        }
        c.set(4, h + 4, 0, Material.GOLD_BLOCK);
        Kit.chimney(c, 7, 6, h + 1, h + 6, Material.STONE_BRICKS);
        Kit.flowerBed(c, -2, -3, 3, 3, r);
        Kit.flowerBed(c, 8, -3, 3, 3, r);
        c.bed(1, 1, 6, Material.RED_BED, 0, 1);
    }

    // ------------------------------------------------------------------ Дом с бамбуковой «соломенной» крышей

    private static void cottageThatch(Canvas c, Random r) {
        int w = 11, d = 7, h = 4;
        Pal p = new Pal(Material.WHITE_TERRACOTTA, Material.OAK_LOG, Material.COBBLESTONE, Material.BAMBOO_MOSAIC_STAIRS, Material.BAMBOO_MOSAIC,
                Material.OAK_TRAPDOOR, Material.OAK_DOOR, Material.OAK_PLANKS, Material.GLASS_PANE);
        Houses.foundation(c, 0, 0, w, d, Material.COBBLESTONE, p.floor());
        Houses.walls(c, 0, 0, w, d, 1, h, p, true);
        Houses.frontWindows(c, 0, w, 2, p, 5, true);
        c.fill(5, 1, 0, 5, 3, 0, Material.AIR);
        c.door(5, 1, 0, p.door(), 0, -1);
        c.stair(4, 1, 0, Material.OAK_STAIRS, 1, 0, false);
        c.stair(6, 1, 0, Material.OAK_STAIRS, -1, 0, false);
        c.stair(5, 0, -1, Material.COBBLESTONE_STAIRS, 0, 1, false);
        Houses.sideWindows(c, 0, 0, w, d, 2, p);
        Kit.gableX(c, 0, w - 1, 0, d - 1, h + 1, p.roofStair(), p.roofBlock(), p.plaster(), 1);
        // Полукруглое слуховое окно: «глаз» из полублоков над дверью.
        c.set(5, h + 2, 0, Material.AIR);
        c.set(5, h + 3, 0, p.glass());
        c.stair(4, h + 3, 0, p.roofStair(), 1, 0, false);
        c.stair(6, h + 3, 0, p.roofStair(), -1, 0, false);
        Kit.chimney(c, 9, 4, h + 1, h + 5, Material.COBBLESTONE);
        for (int i = 0; i < 3; i++) { c.set(-2, 1 + i, -1 + i, Material.BARREL); }
        c.set(-2, 1, -1, Material.BARREL);
        c.fill(11, 1, -1, 12, 1, -1, Material.HAY_BLOCK);
        c.set(12, 2, -1, Material.HAY_BLOCK);
        Kit.flowerBed(c, 1, -3, 3, 2, r);
        Kit.flowerBed(c, 7, -3, 3, 2, r);
    }

    // ------------------------------------------------------------------ Конюшня с загоном

    private static void stable(Canvas c, Random r) {
        int w = 15, d = 9, h = 4;
        Pal p = new Pal(Material.SPRUCE_PLANKS, Material.DARK_OAK_LOG, Material.COBBLESTONE, Material.ACACIA_STAIRS, Material.ACACIA_PLANKS,
                Material.SPRUCE_TRAPDOOR, Material.SPRUCE_DOOR, Material.DIRT_PATH, Material.GLASS_PANE);
        Houses.foundation(c, 0, 0, w, d, Material.COBBLESTONE, Material.DIRT_PATH);
        Houses.walls(c, 0, 0, w, d, 1, h, p, true);
        // Фасад открыт: три стойла по 4 блока, перегородки.
        c.fill(1, 1, 0, w - 2, h - 1, 0, Material.AIR);
        for (int x : new int[]{0, 4, 8, 12, w - 1}) c.fill(x, 1, 0, x, h, 0, p.frame());
        for (int x : new int[]{4, 8, 12}) { c.fill(x, 1, 1, x, 2, 5, Material.SPRUCE_FENCE); }
        for (int s = 0; s < 3; s++) {
            int x0 = 1 + s * 4;
            for (int x = x0; x < x0 + 3; x++) { c.set(x, 0, 5, Material.HAY_BLOCK); c.set(x, 1, 6, Material.HAY_BLOCK); }
            c.set(x0 + 1, 1, 7, Material.BARREL);
            c.fill(x0, 1, 1, x0 + 2, 1, 1, Material.SPRUCE_FENCE);
            c.set(x0 + 1, 1, 1, Material.AIR);
            c.set(x0, 3, 1, Material.LANTERN);
        }
        Houses.sideWindows(c, 0, 0, w, d, 2, p);
        Kit.gableX(c, 0, w - 1, 0, d - 1, h + 1, p.roofStair(), p.roofBlock(), p.plaster(), 1);
        // Забор загона перед стойлами.
        for (int x = -2; x <= w + 1; x++) { c.set(x, 1, -8, Material.SPRUCE_FENCE); }
        for (int z = -8; z <= -1; z++) { c.set(-2, 1, z, Material.SPRUCE_FENCE); c.set(w + 1, 1, z, Material.SPRUCE_FENCE); }
        c.set(7, 1, -8, Material.SPRUCE_FENCE_GATE);
        for (int x = -1; x <= w; x++) for (int z = -7; z <= -1; z++) c.set(x, 0, z, (x + z) % 5 == 0 ? Material.COARSE_DIRT : Material.GRASS_BLOCK);
        c.set(2, 1, -4, Material.WATER_CAULDRON);
        c.fill(12, 1, -3, 13, 1, -3, Material.HAY_BLOCK);
        c.set(13, 2, -3, Material.HAY_BLOCK);
        c.sign(7, 3, -1, 0, -1, "", "&6Конюшня", "", "");
    }
}
