package ru.unknownsmp.world;

import org.bukkit.Material;
import ru.unknownsmp.world.Houses.Pal;

import java.util.Random;

/** Продолжение набора построек: общественные здания деревни. */
final class Houses2 {
    private Houses2() {}

    static void init() {
        Houses.reg("bank", 15, 15, Houses2::bank);
        Houses.reg("alchemist", 14, 11, Houses2::alchemist);
        Houses.reg("townhall", 23, 17, Houses2::townHall);
        Houses.reg("lodge", 18, 9, Houses2::lodge);
        Houses.reg("guardhouse", 11, 9, Houses2::guardhouse);
        Houses.reg("markethall", 17, 9, Houses2::marketHall);
    }

    // ------------------------------------------------------------------ Банк с портиком

    private static void bank(Canvas c, Random r) {
        int w = 15, d = 11, h = 6;
        Pal p = new Pal(Material.SMOOTH_SANDSTONE, Material.QUARTZ_PILLAR, Material.SANDSTONE, Material.QUARTZ_STAIRS, Material.QUARTZ_BLOCK,
                Material.DARK_OAK_TRAPDOOR, Material.DARK_OAK_DOOR, Material.POLISHED_ANDESITE, Material.GLASS_PANE);
        Houses.foundation(c, 0, 0, w, d, Material.STONE_BRICKS, p.floor());
        Houses.walls(c, 0, 0, w, d, 1, h, p, true);
        for (int i = 1; i <= 2; i++) for (int x = 1; x <= w - 2; x++) c.stair(x, 0, -i - 3, Material.QUARTZ_STAIRS, 0, 1, false);
        for (int x = 1; x <= w - 2; x++) for (int z = -3; z <= -1; z++) c.set(x, 0, z, Material.POLISHED_ANDESITE);
        for (int x = 2; x <= w - 3; x += 2) {
            c.fill(x, 1, -2, x, h - 1, -2, Material.QUARTZ_PILLAR);
            c.set(x, 0, -2, Material.CHISELED_QUARTZ_BLOCK);
            c.set(x, h, -2, Material.CHISELED_QUARTZ_BLOCK);
        }
        for (int x = 1; x <= w - 2; x++) {
            c.set(x, h, -3, Material.QUARTZ_BLOCK);
            c.set(x, h, -1, Material.QUARTZ_BLOCK);
            if (x % 2 != 0) c.set(x, h, -2, Material.QUARTZ_BLOCK);
        }
        for (int x : new int[]{3, 11}) Kit.archWindow(c, x, 2, 0, p.glass(), Material.SANDSTONE_STAIRS);
        c.fill(7, 1, 0, 7, 3, 0, Material.AIR);
        c.door(7, 1, 0, p.door(), 0, -1);
        c.fill(6, 1, 0, 6, 3, 0, Material.AIR);
        c.door(6, 1, 0, p.door(), 0, -1);
        c.fill(8, 1, 0, 8, 3, 0, p.frame());
        c.fill(5, 1, 0, 5, 3, 0, p.frame());
        c.set(6, 4, 0, Material.GOLD_BLOCK);
        c.set(7, 4, 0, Material.GOLD_BLOCK);
        for (int z : new int[]{3, 7}) { c.fill(0, 2, z, 0, 3, z, p.glass()); c.fill(w - 1, 2, z, w - 1, 3, z, p.glass()); }
        Kit.gable(c, 0, w - 1, -4, d - 1, h + 1, p.roofStair(), p.roofBlock(), Material.SMOOTH_SANDSTONE, 0);
        for (int dy = 0; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) c.set(7 + dx, h + 2 + dy, -4, Material.GOLD_BLOCK);
        c.set(7, h + 4, -4, Material.GOLD_BLOCK);
        Kit.chimney(c, 12, 8, h + 1, h + 4, Material.SANDSTONE);
        c.fill(2, 1, 8, 12, 3, 8, Material.IRON_BARS);
        for (int x = 3; x <= 11; x += 2) { c.set(x, 1, 9, Material.GOLD_BLOCK); c.dir(x, 1, 10, Material.CHEST, 0, -1); }
        c.fill(2, 1, 4, 12, 1, 4, Material.DARK_OAK_PLANKS);
        c.fill(2, 2, 4, 12, 2, 4, Material.DARK_OAK_SLAB);
        c.lantern(7, h, 5, true);
        c.sign(7, 5, -3, 0, -1, "", "&6Банк", "", "");
        Kit.lampPost(c, -2, -5, Material.POLISHED_ANDESITE);
        Kit.lampPost(c, 16, -5, Material.POLISHED_ANDESITE);
    }

    // ------------------------------------------------------------------ Башня алхимика

    private static void alchemist(Canvas c, Random r) {
        int cx = 9, cz = 5, rad = 4;
        c.fill(-1, -3, -1, 14, -1, 11, Material.COBBLESTONE);
        for (int dx = -rad - 1; dx <= rad + 1; dx++) for (int dz = -rad - 1; dz <= rad + 1; dz++) {
            double dd = Math.hypot(dx, dz);
            if (dd > rad + 0.4) continue;
            int x = cx + dx, z = cz + dz;
            c.set(x, 0, z, Material.DARK_OAK_PLANKS);
            boolean shell = dd > rad - 1;
            for (int y = 1; y <= 11; y++) {
                Material m = Material.AIR;
                if (shell) m = y <= 1 ? Material.STONE_BRICKS : (y % 4 == 0 ? Material.DARK_OAK_PLANKS : Material.PURPLE_TERRACOTTA);
                c.set(x, y, z, m);
            }
            if (!shell) for (int fl : new int[]{4, 8}) c.set(x, fl, z, Material.DARK_OAK_PLANKS);
        }
        for (int fl = 0; fl < 3; fl++) {
            int y = 2 + fl * 4;
            for (int[] q : new int[][]{{rad, 0}, {-rad, 0}, {0, rad}, {0, -rad}, {3, 3}, {-3, 3}}) {
                if (fl == 0 && q[0] == 0 && q[1] == -rad) continue;
                c.fill(cx + q[0], y, cz + q[1], cx + q[0], y + 1, cz + q[1], Material.PURPLE_STAINED_GLASS_PANE);
            }
        }
        c.fill(cx, 1, cz - rad, cx, 3, cz - rad, Material.AIR);
        c.door(cx, 1, cz - rad, Material.DARK_OAK_DOOR, 0, -1);
        c.stair(cx, 0, cz - rad - 1, Material.STONE_BRICK_STAIRS, 0, 1, false);
        c.lantern(cx + 1, 4, cz - rad - 1, true);
        c.set(cx + 1, 4, cz - rad, Material.DARK_OAK_FENCE);
        for (int dx = -rad - 2; dx <= rad + 2; dx++) for (int dz = -rad - 2; dz <= rad + 2; dz++) {
            double dd = Math.hypot(dx, dz);
            if (dd > rad + 1.5 && dd <= rad + 2.4) c.set(cx + dx, 12, cz + dz, Material.DARK_OAK_FENCE);
            if (dd > rad - 0.4 && dd <= rad + 1.5) c.set(cx + dx, 11, cz + dz, Material.DARK_OAK_SLAB);
        }
        int top = Kit.cone(c, cx, cz, rad, 13, Material.PURPUR_STAIRS, Material.PURPUR_BLOCK);
        c.fill(cx, top, cz, cx, top + 2, cz, Material.AMETHYST_BLOCK);
        c.set(cx, top + 3, cz, Material.AMETHYST_CLUSTER);
        for (int a = 0; a < 360; a += 60) c.lantern(cx + (int) Math.round(Math.cos(Math.toRadians(a)) * (rad + 2)), 11, cz + (int) Math.round(Math.sin(Math.toRadians(a)) * (rad + 2)), false);
        // Теплица слева.
        for (int x = -1; x <= 3; x++) for (int z = 2; z <= 8; z++) {
            c.set(x, 0, z, Material.PODZOL);
            boolean edge = x == -1 || x == 3 || z == 2 || z == 8;
            for (int y = 1; y <= 3; y++) c.set(x, y, z, edge ? Material.GLASS_PANE : Material.AIR);
            if (!edge) c.set(x, 1, z, new Material[]{Material.RED_TULIP, Material.CORNFLOWER, Material.ALLIUM, Material.FERN}[r.nextInt(4)]);
            c.set(x, 4, z, Material.GLASS);
        }
        for (int[] q : new int[][]{{-1, 2}, {3, 2}, {-1, 8}, {3, 8}}) c.fill(q[0], 0, q[1], q[0], 3, q[1], Material.DARK_OAK_LOG);
        c.set(3, 1, 5, Material.AIR);
        c.set(3, 2, 5, Material.AIR);
        c.set(cx - 2, 1, cz, Material.CAULDRON);
        c.set(cx + 2, 1, cz, Material.BREWING_STAND);
        c.set(cx, 1, cz + 2, Material.BREWING_STAND);
        for (int i = 0; i < 4; i++) c.stair(cx - 3 + (i % 2), 1 + i, cz + 1 - i / 2, Material.DARK_OAK_STAIRS, 0, 1, false);
        c.sign(cx + 2, 3, cz - rad - 1, 0, -1, "", "&5Алхимик", "", "");
        c.set(5, 1, -1, Material.CAULDRON);
        for (int i = 0; i < 3; i++) Kit.barrel(c, 11 + i % 2, 1, -3 + i);
        Kit.flowerBed(c, -2, -3, 4, 2, r);
    }

    // ------------------------------------------------------------------ Ратуша с часовой башней

    private static void townHall(Canvas c, Random r) {
        int w = 23, d = 13, h = 7;
        Pal p = new Pal(Material.WHITE_TERRACOTTA, Material.DARK_OAK_LOG, Material.STONE_BRICKS, Material.DEEPSLATE_TILE_STAIRS, Material.DEEPSLATE_TILES,
                Material.WARPED_TRAPDOOR, Material.DARK_OAK_DOOR, Material.SPRUCE_PLANKS, Material.GLASS_PANE);
        Houses.foundation(c, 0, 0, w, d, Material.STONE_BRICKS, p.floor());
        Houses.walls(c, 0, 0, w, d, 1, h, p, true);
        c.fill(0, 4, 0, w - 1, 4, 0, p.frame());
        c.fill(0, 4, d - 1, w - 1, 4, d - 1, p.frame());
        int tx0 = 8, tw = 7, th = 14;
        for (int x = tx0; x < tx0 + tw; x++) for (int z = -2; z < 5; z++) for (int y = 1; y <= th; y++) {
            boolean shell = x == tx0 || x == tx0 + tw - 1 || z == -2 || z == 4;
            c.set(x, y, z, shell ? (y == 1 ? Material.STONE_BRICKS : p.plaster()) : Material.AIR);
        }
        for (int x : new int[]{tx0, tx0 + tw - 1}) for (int z : new int[]{-2, 4}) c.fill(x, 1, z, x, th, z, p.frame());
        c.fill(tx0, th, -2, tx0 + tw - 1, th, 4, Material.DARK_OAK_PLANKS);
        for (int y : new int[]{h, h + 4}) c.fill(tx0, y, -2, tx0 + tw - 1, y, -2, p.frame());
        for (int dx = -2; dx <= 2; dx++) for (int dy = -2; dy <= 2; dy++) if (Math.hypot(dx, dy) <= 2.4) c.set(tx0 + 3 + dx, 11 + dy, -3, Material.WHITE_CONCRETE);
        c.set(tx0 + 3, 11, -3, Material.BLACK_CONCRETE);
        c.set(tx0 + 3, 12, -3, Material.BLACK_CONCRETE);
        c.set(tx0 + 3, 13, -3, Material.BLACK_CONCRETE);
        c.set(tx0 + 4, 11, -3, Material.BLACK_CONCRETE);
        c.set(tx0 + 5, 11, -3, Material.BLACK_CONCRETE);
        for (int a = 0; a < 360; a += 90) c.set(tx0 + 3 + (int) Math.round(Math.cos(Math.toRadians(a)) * 3), 11 + (int) Math.round(Math.sin(Math.toRadians(a)) * 3), -3, Material.GOLD_BLOCK);
        Kit.hip(c, tx0, tx0 + tw - 1, -2, 4, th + 1, Material.OXIDIZED_CUT_COPPER_STAIRS, Material.OXIDIZED_CUT_COPPER, 1);
        c.fill(tx0 + 3, th + 5, 1, tx0 + 3, th + 9, 1, Material.OXIDIZED_COPPER);
        c.set(tx0 + 3, th + 10, 1, Material.GOLD_BLOCK);
        c.fill(tx0 + 3, 1, -2, tx0 + 3, 4, -2, Material.AIR);
        c.door(tx0 + 3, 1, -2, p.door(), 0, -1);
        c.fill(tx0 + 3, 1, 0, tx0 + 3, 3, 0, Material.AIR);
        for (int x : new int[]{tx0 + 2, tx0 + 4}) c.stair(x, 1, -2, Material.STONE_BRICK_STAIRS, x < tx0 + 3 ? 1 : -1, 0, false);
        for (int i = 1; i <= 3; i++) for (int x = tx0 + 2; x <= tx0 + 4; x++) c.stair(x, 0, -2 - i, Material.STONE_BRICK_STAIRS, 0, 1, false);
        for (int x : new int[]{2, 5, 17, 20}) Kit.window(c, x, 2, 0, -1, p.glass(), p.shutter(), true, true);
        for (int x : new int[]{2, 5, 17, 20}) Kit.window(c, x, 5, 0, -1, p.glass(), p.shutter(), true, false);
        Houses.sideWindows(c, 0, 0, w, d, 2, p);
        Kit.gable(c, 0, 6, 0, d - 1, h + 1, p.roofStair(), p.roofBlock(), p.plaster(), 1);
        Kit.gable(c, 16, 22, 0, d - 1, h + 1, p.roofStair(), p.roofBlock(), p.plaster(), 1);
        Kit.gableX(c, 7, 15, 5, d - 1, h + 1, p.roofStair(), p.roofBlock(), p.plaster(), 1);
        Kit.chimney(c, 2, 9, h + 3, h + 8, p.base());
        Kit.chimney(c, 20, 9, h + 3, h + 8, p.base());
        for (int x : new int[]{4, 18}) { c.dir(x, 6, -1, Material.RED_WALL_BANNER, 0, -1); c.dir(x + 1, 6, -1, Material.RED_WALL_BANNER, 0, -1); }
        c.dir(tx0 + 1, 8, -3, Material.RED_WALL_BANNER, 0, -1);
        c.dir(tx0 + 5, 8, -3, Material.RED_WALL_BANNER, 0, -1);
        c.fill(tx0 + 2, 1, 1, tx0 + 4, 1, 10, Material.RED_CARPET);
        c.set(tx0 + 3, 1, 11, Material.LECTERN);
        for (int x : new int[]{3, 6, 16, 19}) c.fill(x, 1, 11, x, 2, 11, Material.BOOKSHELF);
        c.lantern(tx0 + 3, 6, 5, true);
        c.sign(tx0 + 3, 5, -3, 0, -1, "", "&eРатуша", "", "");
        Kit.lampPost(c, -2, -2, p.frame());
        Kit.lampPost(c, 24, -2, p.frame());
        Kit.flowerBed(c, 1, -4, 5, 3, r);
        Kit.flowerBed(c, 17, -4, 5, 3, r);
    }

    // ------------------------------------------------------------------ Охотничий домик

    private static void lodge(Canvas c, Random r) {
        int w = 13, d = 9, h = 4;
        Pal p = new Pal(Material.SPRUCE_LOG, Material.STRIPPED_SPRUCE_LOG, Material.COBBLESTONE, Material.DARK_OAK_STAIRS, Material.DARK_OAK_PLANKS,
                Material.SPRUCE_TRAPDOOR, Material.SPRUCE_DOOR, Material.SPRUCE_PLANKS, Material.GLASS_PANE);
        Houses.foundation(c, 0, 0, w, d, Material.COBBLESTONE, p.floor());
        Houses.walls(c, 0, 0, w, d, 1, h, p, true);
        for (int y = 2; y <= h - 1; y += 2) { c.fill(0, y, 0, w - 1, y, 0, Material.SPRUCE_PLANKS); c.fill(0, y, d - 1, w - 1, y, d - 1, Material.SPRUCE_PLANKS); }
        Houses.frontWindows(c, 0, w, 2, p, 6, false);
        Houses.sideWindows(c, 0, 0, w, d, 2, p);
        c.fill(6, 1, 0, 6, 3, 0, Material.AIR);
        c.door(6, 1, 0, p.door(), 0, -1);
        for (int x : new int[]{5, 7}) c.stair(x, 1, 0, Material.COBBLESTONE_STAIRS, x < 6 ? 1 : -1, 0, false);
        for (int i = 1; i <= 2; i++) c.stair(6, 0, -i, Material.COBBLESTONE_STAIRS, 0, 1, false);
        for (int x : new int[]{4, 8}) c.fill(x, 0, -3, x, 3, -3, p.frame());
        for (int x = 3; x <= 9; x++) { c.stair(x, 4, -3, p.roofStair(), 0, 1, false); c.set(x, 4, -2, p.roofBlock()); c.set(x, 4, -1, p.roofBlock()); }
        c.set(6, 3, -3, Material.BONE_BLOCK);
        c.set(5, 3, -3, Material.SPRUCE_FENCE);
        c.set(7, 3, -3, Material.SPRUCE_FENCE);
        Kit.gableX(c, 0, w - 1, 0, d - 1, h + 1, p.roofStair(), p.roofBlock(), p.plaster(), 1);
        for (int x = 0; x < w; x++) c.set(x, h + 5, 4, Material.MOSS_BLOCK);
        Kit.chimney(c, 10, 6, h + 1, h + 5, Material.COBBLESTONE);
        for (int i = 0; i < 3; i++) c.set(-3 + i, 3, -2, new Material[]{Material.BROWN_WOOL, Material.WHITE_WOOL, Material.LIGHT_GRAY_WOOL}[i]);
        c.fill(-3, 0, -2, -3, 3, -2, Material.SPRUCE_FENCE);
        c.fill(-1, 0, -2, -1, 3, -2, Material.SPRUCE_FENCE);
        c.fill(-3, 4, -2, -1, 4, -2, Material.SPRUCE_FENCE);
        for (int x = 14; x <= 16; x++) c.fill(x, 1, -1, x, 2, -1, Material.SPRUCE_LOG);
        c.set(15, 3, -1, Material.SPRUCE_LOG);
        c.set(16, 1, -3, Material.CAMPFIRE);
        c.set(15, 1, -3, Material.SPRUCE_FENCE);
        c.set(17, 1, -3, Material.SPRUCE_FENCE);
        c.fill(5, 3, -3, 7, 3, -3, Material.SPRUCE_PLANKS);
        c.sign(6, 3, -4, 0, -1, "", "&cОхотник", "", "");
        c.bed(2, 1, 6, Material.BROWN_BED, 0, 1);
        c.dir(10, 1, 7, Material.CHEST, -1, 0);
        c.set(3, 1, 2, Material.CRAFTING_TABLE);
        for (int i = 0; i < 3; i++) c.set(1 + i, 3, d - 2, Material.SKELETON_SKULL);
    }

    // ------------------------------------------------------------------ Караульня-суд

    private static void guardhouse(Canvas c, Random r) {
        int w = 9, d = 9, h = 5;
        Pal p = new Pal(Material.STONE_BRICKS, Material.POLISHED_ANDESITE, Material.COBBLESTONE, Material.STONE_BRICK_STAIRS, Material.STONE_BRICKS,
                Material.DARK_OAK_TRAPDOOR, Material.DARK_OAK_DOOR, Material.STONE_BRICKS, Material.IRON_BARS);
        Houses.foundation(c, 0, 0, w, d, Material.COBBLESTONE, p.floor());
        Houses.walls(c, 0, 0, w, d, 1, h, p, true);
        for (int x : new int[]{2, 6}) c.fill(x, 2, 0, x, 3, 0, Material.IRON_BARS);
        c.fill(4, 1, 0, 4, 3, 0, Material.AIR);
        c.door(4, 1, 0, p.door(), 0, -1);
        for (int x : new int[]{3, 5}) c.stair(x, 1, 0, Material.STONE_BRICK_STAIRS, x < 4 ? 1 : -1, 0, false);
        for (int i = 1; i <= 2; i++) c.stair(4, 0, -i, Material.STONE_BRICK_STAIRS, 0, 1, false);
        Houses.sideWindows(c, 0, 0, w, d, 2, p);
        for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) {
            c.set(x, h + 1, z, Material.STONE_BRICK_SLAB);
            boolean edge = x == 0 || z == 0 || x == w - 1 || z == d - 1;
            if (edge && (x + z) % 2 == 0) c.set(x, h + 2, z, Material.STONE_BRICK_WALL);
        }
        for (int x = 0; x <= 2; x++) for (int z = 0; z <= 2; z++) for (int y = h + 1; y <= h + 4; y++) c.set(x, y, z, (x == 1 && z == 1) ? Material.AIR : Material.STONE_BRICKS);
        c.fill(0, h + 5, 0, 2, h + 5, 2, Material.STONE_BRICK_SLAB);
        Kit.hip(c, 0, 2, 0, 2, h + 5, Material.DARK_OAK_STAIRS, Material.DARK_OAK_PLANKS, 1);
        c.fill(1, h + 8, 1, 1, h + 11, 1, Material.OAK_FENCE);
        c.dir(1, h + 10, 0, Material.RED_WALL_BANNER, 0, -1);
        c.fill(7, 1, -3, 9, 1, -3, Material.SPRUCE_FENCE);
        c.set(8, 2, -3, Material.SPRUCE_TRAPDOOR);
        c.set(7, 2, -3, Material.SPRUCE_FENCE);
        c.set(9, 2, -3, Material.SPRUCE_FENCE);
        c.lantern(4, 4, -1, true);
        c.sign(5, 4, -1, 0, -1, "", "&fСуд", "", "");
        c.fill(2, 1, 5, 6, 1, 5, Material.DARK_OAK_PLANKS);
        c.set(4, 2, 5, Material.LECTERN);
        for (int x : new int[]{1, 7}) c.fill(x, 1, 7, x, 2, 7, Material.IRON_BARS);
        Kit.lampPost(c, -2, -2, p.frame());
    }

    // ------------------------------------------------------------------ Торговый ряд с аркадой

    private static void marketHall(Canvas c, Random r) {
        int w = 17, d = 9, h = 5;
        Pal p = new Pal(Material.SMOOTH_SANDSTONE, Material.STRIPPED_OAK_LOG, Material.STONE_BRICKS, Material.OXIDIZED_CUT_COPPER_STAIRS, Material.OXIDIZED_CUT_COPPER,
                Material.JUNGLE_TRAPDOOR, Material.OAK_DOOR, Material.OAK_PLANKS, Material.GLASS_PANE);
        Houses.foundation(c, 0, 0, w, d, Material.COBBLESTONE, p.floor());
        Houses.walls(c, 0, 0, w, d, 1, h, p, true);
        Material[] goods = {Material.MELON, Material.PUMPKIN, Material.HAY_BLOCK, Material.BARREL, Material.COMPOSTER};
        for (int a = 0; a < 5; a++) {
            int x = 2 + a * 3;
            c.fill(x, 1, 0, x, 3, 0, Material.AIR);
            c.stair(x - 1, 3, 0, Material.SANDSTONE_STAIRS, 1, 0, true);
            c.stair(x + 1, 3, 0, Material.SANDSTONE_STAIRS, -1, 0, true);
            c.fill(x - 1, 1, 0, x - 1, 2, 0, Material.AIR);
            c.fill(x + 1, 1, 0, x + 1, 2, 0, Material.AIR);
            c.set(x, 4, 0, p.plaster());
            c.set(x, 1, -1, Material.OAK_PLANKS);
            c.set(x, 2, -1, goods[a]);
            c.set(x - 1, 1, -1, Material.OAK_SLAB);
            c.set(x + 1, 1, -1, Material.OAK_SLAB);
            c.set(x - 1, 2, -1, Material.CAKE);
        }
        Houses.sideWindows(c, 0, 0, w, d, 2, p);
        Material[] awn = {Material.RED_WOOL, Material.YELLOW_WOOL, Material.LIME_WOOL, Material.LIGHT_BLUE_WOOL, Material.ORANGE_WOOL};
        for (int a = 0; a < 5; a++) {
            int x = 1 + a * 3;
            for (int dx = 0; dx < 3; dx++) for (int k = 0; k < 2; k++) c.set(x + dx, 4, -1 - k, (dx + k) % 2 == 0 ? awn[a] : Material.WHITE_WOOL);
        }
        Kit.gableX(c, 0, w - 1, 0, d - 1, h + 1, p.roofStair(), p.roofBlock(), p.plaster(), 1);
        Kit.chimney(c, 13, 6, h + 1, h + 5, p.base());
        c.sign(8, 5, -2, 0, -1, "", "&aРынок", "", "");
        c.fill(7, 5, -2, 9, 5, -2, Material.OAK_PLANKS);
        for (int x : new int[]{2, 14}) { Kit.barrel(c, x, 1, 6); Kit.barrel(c, x + 1, 1, 6); }
        c.fill(4, 1, 4, 12, 1, 4, Material.OAK_PLANKS);
        c.fill(4, 2, 4, 12, 2, 4, Material.OAK_SLAB);
        c.lantern(8, h, 5, true);
    }
}
