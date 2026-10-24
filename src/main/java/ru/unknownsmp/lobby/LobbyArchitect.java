package ru.unknownsmp.lobby;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Note;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.FaceAttachable;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.type.Ladder;
import org.bukkit.block.data.type.NoteBlock;
import ru.unknownsmp.core.UnknownSMP;
import ru.unknownsmp.world.Canvas;
import ru.unknownsmp.world.Fun;
import ru.unknownsmp.world.Structures;
import ru.unknownsmp.world.Structures.Pal;

import java.util.Random;
import java.util.function.BiConsumer;

/**
 * Главное лобби: парящий остров с логотипом на полу, двумя вратами (SMP — север, дуэли — юг), четырьмя павильонами
 * (рынок, аукцион, летопись, ранги), мини-играми и тремя паркурами. Места NPC и секретов сохраняются как «якоря».
 */
public final class LobbyArchitect {
    public static final int R = 44;
    public static final int Y = 100;
    private static final int DISC = 20;

    private static final Material[] LOGO = {Material.LIGHT_BLUE_CONCRETE, Material.BLUE_CONCRETE, Material.LIGHT_BLUE_TERRACOTTA,
            Material.CYAN_TERRACOTTA, Material.BLUE_TERRACOTTA};
    private static final Material[] RAINBOW = {Material.RED_WOOL, Material.ORANGE_WOOL, Material.YELLOW_WOOL, Material.LIME_WOOL,
            Material.CYAN_WOOL, Material.LIGHT_BLUE_WOOL, Material.BLUE_WOOL, Material.PURPLE_WOOL, Material.MAGENTA_WOOL, Material.PINK_WOOL};
    private static final Material[] MED = {Material.ORANGE_CONCRETE, Material.YELLOW_CONCRETE, Material.LIME_CONCRETE,
            Material.CYAN_CONCRETE, Material.LIGHT_BLUE_CONCRETE, Material.PURPLE_CONCRETE};

    private final World w;
    private final BiConsumer<String, Location> anchor;
    private final Random rnd = new Random(20240601L);
    private final Canvas g;

    private LobbyArchitect(World w, BiConsumer<String, Location> anchor) {
        this.w = w;
        this.anchor = anchor;
        this.g = new Canvas(w, 0, Y, 0, 1, 0, 0, 1);
    }

    public static void build(World w, BiConsumer<String, Location> anchor) {
        LobbyArchitect a = new LobbyArchitect(w, anchor);
        a.platform();
        a.stages();
        a.gateNorth();
        a.gateSouth();
        a.pavilions();
        a.plaza();
        a.minigames();
        a.secrets();
        a.parkours();
        a.games();
        a.authRoom();
        a.anchors();
        Canvas.flushConnections();
    }

    /** Удалить остатки старого лобби (версии 1–2) перед новой постройкой. */
    /** Только комната входа (логин/регистрация) — для случая, когда лобби загружено из готовой карты. */
    public static void buildAuthRoomOnly(World w) {
        new LobbyArchitect(w, (n, l) -> { }).authRoom();
        Canvas.flushConnections();
    }

    public static void clearOld(World w) {
        for (int x = -20; x <= 20; x++) for (int z = -20; z <= 20; z++) for (int y = 94; y <= 112; y++) w.getBlockAt(x, y, z).setType(Material.AIR, false);
        for (int x = 18; x <= 63; x++) for (int z = -3; z <= 5; z++) for (int y = 98; y <= 116; y++) w.getBlockAt(x, y, z).setType(Material.AIR, false);
    }

    // ------------------------------------------------------------------ Остров

    private void s(int x, int y, int z, Material m) {
        Block b = w.getBlockAt(x, y, z);
        b.setType(m, false);
        Canvas.track(b);
    }

    private Material surface(int x, int z, double d) {
        if (d > 43) return Material.QUARTZ_BRICKS;
        if (d > 41) {
            int seg = (int) Math.floor((Math.atan2(z, x) + Math.PI) / (Math.PI * 2) * 48);
            return seg % 2 == 0 ? Material.LIGHT_BLUE_CONCRETE : Material.BLUE_CONCRETE;
        }
        if (d <= DISC) {
            if (d > DISC - 1.5) return ((x + z) & 1) == 0 ? Material.QUARTZ_BLOCK : Material.LIGHT_BLUE_CONCRETE;
            return Material.BLACK_CONCRETE;
        }
        int ax = Math.abs(x), az = Math.abs(z);
        if (ax <= 2 && z < 0 && z > -38) return ax <= 1 ? Material.SMOOTH_QUARTZ : Material.LIGHT_BLUE_CONCRETE;
        if (ax <= 2 && z > 0 && z < 38) return ax <= 1 ? Material.RED_NETHER_BRICKS : Material.BLACK_CONCRETE;
        if (az <= 1 && ax > DISC && ax < 41) return az == 0 ? Material.POLISHED_ANDESITE : Material.STONE_BRICKS;
        if (x % 8 == 0 || z % 8 == 0) return Material.LIGHT_BLUE_TERRACOTTA;
        return ((x + z) & 1) == 0 ? Material.POLISHED_DIORITE : Material.QUARTZ_BRICKS;
    }

    private boolean inStage(int x, int z) {
        return (Math.abs(x) <= 10 && z <= -37) || (Math.abs(x) <= 10 && z >= 37);
    }

    private void platform() {
        for (int x = -R - 1; x <= R + 1; x++) for (int z = -R - 1; z <= R + 1; z++) {
            double d = Math.sqrt(x * x + z * z);
            if (d > R + 0.5) continue;
            s(x, Y, z, surface(x, z, d));
            int depth = (int) ((R + 0.5 - d) * 0.6) + 2;
            for (int k = 1; k <= depth; k++) {
                Material m = k == depth ? Material.CRYING_OBSIDIAN : k == 1 ? Material.DEEPSLATE_BRICKS
                        : rnd.nextInt(45) == 0 ? Material.AMETHYST_BLOCK : rnd.nextInt(60) == 0 ? Material.SEA_LANTERN : Material.DEEPSLATE;
                s(x, Y - k, z, m);
            }
            // Бортик и фонари по краю (кроме проходов).
            boolean opening = inStage(x, z) || (Math.abs(z) <= 3 && Math.abs(x) > 40) || (x >= 27 && x <= 37 && z >= 27) || (Math.abs(Math.abs(x) - Math.abs(z)) <= 3 && !(x > 0 && z > 0) && Math.abs(x) > 28);
            if (d > 43 && d <= R + 0.5 && !opening) {
                s(x, Y + 1, z, Material.DIORITE_WALL);
                if ((x + 2 * z) % 6 == 0) {
                    s(x, Y + 2, z, Material.POLISHED_BLACKSTONE_BRICK_WALL);
                    s(x, Y + 3, z, Material.SEA_LANTERN);
                }
            }
        }
        // Логотип на полу: вершина «А» смотрит на врата SMP (север).
        int h = 14, t = 4;
        for (int[] px : Canvas.Glyphs.logo(h, t)) {
            int lx = px[0];
            int lz = (h - 1) / 2 - px[1];
            s(lx, Y, lz, LOGO[px[2]]);
        }
        // Кольцо света вокруг логотипа.
        for (int a = 0; a < 360; a += 3) {
            int x = (int) Math.round(Math.cos(Math.toRadians(a)) * (DISC + 1.5)), z = (int) Math.round(Math.sin(Math.toRadians(a)) * (DISC + 1.5));
            s(x, Y, z, a % 30 < 3 ? Material.SEA_LANTERN : Material.CHISELED_QUARTZ_BLOCK);
        }
    }

    private void stages() {
        for (int x = -10; x <= 10; x++) for (int z = -45; z <= -37; z++) stageCell(x, z, Math.min(Math.min(x + 11, 11 - x), z + 46));
        for (int x = -10; x <= 10; x++) for (int z = 37; z <= 45; z++) stageCell(x, z, Math.min(Math.min(x + 11, 11 - x), 46 - z));
    }

    private void stageCell(int x, int z, int edge) {
        Material top = ((x + z) & 1) == 0 ? Material.POLISHED_DIORITE : Material.QUARTZ_BRICKS;
        if (Math.abs(x) <= 2) top = z < 0 ? Material.SMOOTH_QUARTZ : Material.RED_NETHER_BRICKS;
        s(x, Y, z, top);
        int depth = Math.max(1, Math.min(edge, 4));
        for (int k = 1; k <= depth; k++) s(x, Y - k, z, k == depth ? Material.CRYING_OBSIDIAN : Material.DEEPSLATE_BRICKS);
    }

    // ------------------------------------------------------------------ Врата

    private void gateNorth() {
        var cfg = UnknownSMP.get().configs().main();
        String title = cfg.getString("branding.sign", "ALEX").toUpperCase();
        String sub = cfg.getString("branding.sign-sub", "SMP").toUpperCase();
        if (title.length() > 9) title = title.substring(0, 9);
        if (sub.length() > 6) sub = sub.substring(0, 6);
        Canvas c = Canvas.facing(w, 0, Y + 28, -96, 0, -1);
        ru.unknownsmp.world.BigSign.build(c, title, sub);
        portal(-38, Material.QUARTZ_PILLAR, Material.SEA_LANTERN);
        button(0, Y + 9, -38, Material.POLISHED_BLACKSTONE_BUTTON);
        anchor.accept("secret_portal_top", new Location(w, 0.5, Y + 9, -37.5));
    }

    private void gateSouth() {
        // Парящая вывеска «DUELS» со скрещёнными мечами далеко над южным краем.
        Canvas c = Canvas.facing(w, 0, Y + 22, 96, 0, 1);
        int sc = 4;
        for (int lz = 0; lz < 3; lz++) {
            c.text("DUELS", -Canvas.textWidth("DUELS", sc) / 2, 0, lz, lz == 0 ? Material.RED_CONCRETE : lz == 1 ? Material.RED_TERRACOTTA : Material.SEA_LANTERN, sc);
        }
        for (int i = 0; i < 14; i++) {
            for (int dx = 0; dx < 3; dx++) for (int dy = 0; dy < 3; dy++) {
                for (int lz = 0; lz < 3; lz++) {
                    Material m = lz == 2 ? Material.SEA_LANTERN : (dx + dy) % 2 == 0 ? Material.LIGHT_GRAY_CONCRETE : Material.WHITE_CONCRETE;
                    c.set(-21 + i * 3 + dx, 30 + i * 3 + dy, lz, m);
                    c.set(20 - i * 3 + dx, 30 + i * 3 + dy, lz, m);
                }
            }
        }
        for (int[] hilt : new int[][]{{-24, 27}, {-27, 24}, {-18, 27}, {-21, 30}, {23, 27}, {26, 24}, {17, 27}, {20, 30}}) {
            for (int dx = 0; dx < 3; dx++) for (int dy = 0; dy < 3; dy++) for (int lz = 0; lz < 3; lz++) c.set(hilt[0] + dx, hilt[1] + dy, lz, lz == 2 ? Material.SEA_LANTERN : Material.RED_CONCRETE);
        }
        portal(38, Material.RED_NETHER_BRICKS, Material.REDSTONE_LAMP);
    }

    private void portal(int z, Material deco, Material light) {
        for (int x = -4; x <= 4; x++) for (int y = 0; y <= 8; y++) {
            boolean outer = x == -4 || x == 4 || y == 8;
            boolean frame = x == -3 || x == 3 || y == 0 || y == 7;
            if (outer) s(x, Y + y, z, (y == 8 && Math.abs(x) % 2 == 0) || (Math.abs(x) == 4 && y % 3 == 0) ? light : deco);
            else if (frame) s(x, Y + y, z, Material.OBSIDIAN);
        }
        for (int x = -2; x <= 2; x++) for (int y = 1; y <= 6; y++) {
            Block b = w.getBlockAt(x, Y + y, z);
            b.setType(Material.NETHER_PORTAL, false);
            if (b.getBlockData() instanceof Orientable o) {
                o.setAxis(org.bukkit.Axis.X);
                b.setBlockData(o, false);
            }
        }
        for (int x = -4; x <= 4; x++) for (int y = 0; y <= 8; y++) {
            Block b = w.getBlockAt(x, Y + y, z);
            if (b.getType() == Material.REDSTONE_LAMP && b.getBlockData() instanceof org.bukkit.block.data.Lightable l) {
                l.setLit(true);
                b.setBlockData(l, false);
            }
        }
    }

    // ------------------------------------------------------------------ Павильоны

    private void pavilion(int x, int z, int fx, int width, int depth, int h, Pal p, String type, String sign, String npc) {
        Canvas c = Canvas.front(w, x, Y, z, fx, 0, width);
        Structures.house(c, width, depth, h, p, type, new String[]{"", sign, "", ""});
        if (npc != null) anchor.accept(npc, c.spot(width / 2, 1, 4));
    }

    private void pavilions() {
        pavilion(24, -14, 1, 13, 10, 5, Structures.CHERRY, "SHOP", "&a&lРынок", "lobby_merchant");
        pavilion(24, 14, 1, 13, 10, 5, Structures.BRICK, "AUCTION", "&6&lАукцион", "lobby_auctioneer");
        pavilion(-24, -14, -1, 13, 10, 5, Structures.TIMBER, "LIBRARY", "&b&lЛетопись", null);
        pavilion(-24, 14, -1, 13, 10, 5, Structures.DARK, "RANKS", "&e&lРанги", null);
    }

    // ------------------------------------------------------------------ Площадь

    private void plaza() {
        // Колонны света вокруг логотипа.
        for (int sx : new int[]{-1, 1}) for (int sz : new int[]{-1, 1}) {
            int x = sx * 18, z = sz * 18;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) s(x + dx, Y, z + dz, Material.POLISHED_BLACKSTONE_BRICKS);
            for (int y = 1; y <= 7; y++) s(x, Y + y, z, y % 3 == 0 ? Material.CHISELED_QUARTZ_BLOCK : Material.QUARTZ_PILLAR);
            s(x, Y + 8, z, Material.SEA_LANTERN);
            s(x, Y + 9, z, Material.END_ROD);
            for (int[] o : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) s(x + o[0], Y + 1, z + o[1], Material.QUARTZ_STAIRS);
        }
        // Скамейки лицом к центру (спинка наружу).
        int[][] benches = {{6, -22}, {-6, -22}, {6, 22}, {-6, 22}, {-27, 5}, {27, 5}, {-27, -5}, {27, -5}};
        for (int[] b : benches) {
            boolean alongX = Math.abs(b[0]) > Math.abs(b[1]);
            int ox = alongX ? (int) Math.signum(b[0]) : 0, oz = alongX ? 0 : (int) Math.signum(b[1]);
            g.stair(b[0], 1, b[1], Material.DARK_OAK_STAIRS, ox, oz, false);
            g.stair(b[0] + Math.abs(oz), 1, b[1] + Math.abs(ox), Material.DARK_OAK_STAIRS, ox, oz, false);
        }
        // Вишнёвые деревья в клумбах.
        int[][] trees = {{-28, -26}, {28, -26}, {-28, 26}, {28, 26}, {-23, -33}, {23, -33}, {-18, 34}, {18, 34}};
        for (int[] t : trees) {
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                boolean border = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                s(t[0] + dx, Y, t[1] + dz, border ? Material.STONE_BRICKS : Material.GRASS_BLOCK);
                if (border) s(t[0] + dx, Y + 1, t[1] + dz, Material.STONE_BRICK_SLAB);
                else if (rnd.nextInt(3) == 0) s(t[0] + dx, Y + 1, t[1] + dz, new Material[]{Material.PINK_TULIP, Material.ALLIUM, Material.AZURE_BLUET}[rnd.nextInt(3)]);
            }
            s(t[0], Y + 1, t[1], Material.AIR);
            Structures.tree(g, t[0], t[1], "cherry", rnd);
        }
        // Светильники вдоль дорожек.
        for (int z = -36; z <= 36; z += 6) {
            if (Math.abs(z) < DISC) continue;
            for (int sx : new int[]{-3, 3}) lampPost(sx, z);
        }
        for (int x = -39; x <= 39; x += 6) {
            if (Math.abs(x) < DISC + 2) continue;
            lampPost(x, 3);
            lampPost(x, -3);
        }
    }

    private void lampPost(int x, int z) {
        if (w.getBlockAt(x, Y + 1, z).getType() != Material.AIR) return;
        s(x, Y + 1, z, Material.DIORITE_WALL);
        s(x, Y + 2, z, Material.POLISHED_BLACKSTONE_BRICK_WALL);
        s(x, Y + 3, z, Material.SEA_LANTERN);
    }

    // ------------------------------------------------------------------ Мини-игры

    private void minigames() {
        // Пианино: 8 нотных блоков.
        int[] notes = {6, 8, 10, 11, 13, 15, 17, 18};
        for (int i = 0; i < 8; i++) {
            Block b = w.getBlockAt(12 + i, Y, -30);
            b.setType(Material.NOTE_BLOCK, false);
            if (b.getBlockData() instanceof NoteBlock nb) {
                nb.setNote(new Note(notes[i]));
                b.setBlockData(nb, false);
            }
            s(12 + i, Y - 1, -30, Material.DEEPSLATE_BRICKS);
        }
        for (int x = 11; x <= 20; x++) {
            s(x, Y, -31, Material.POLISHED_BLACKSTONE_BRICKS);
            s(x, Y + 1, -31, Material.DEEPSLATE_BRICKS);
            s(x, Y + 2, -31, x == 11 || x == 20 ? Material.SEA_LANTERN : Material.POLISHED_BLACKSTONE_BRICKS);
        }
        g.sign(11, 1, -30, 0, 1, "", "&d&lПиано", "&7Нажмите на клавиши", "");
        anchor.accept("holo_piano", new Location(w, 15.5, Y + 3.8, -30.5));
        // Беседка «Подарок»: ящик Края для /daily.
        int gx = -14, gz = -30;
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) s(gx + dx, Y, gz + dz, Math.max(Math.abs(dx), Math.abs(dz)) == 3 ? Material.QUARTZ_BRICKS : Material.SMOOTH_QUARTZ);
        for (int[] c : new int[][]{{-3, -3}, {3, -3}, {-3, 3}, {3, 3}}) {
            for (int y = 1; y <= 5; y++) s(gx + c[0], Y + y, gz + c[1], y == 5 ? Material.CHISELED_QUARTZ_BLOCK : Material.QUARTZ_PILLAR);
        }
        for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
            boolean edge = Math.abs(dx) == 4 || Math.abs(dz) == 4;
            if (edge) g.slab(gx + dx, 6, gz + dz, Material.QUARTZ_SLAB, false);
            else g.slab(gx + dx, 6, gz + dz, Material.SMOOTH_QUARTZ_SLAB, false);
        }
        s(gx, Y + 6, gz, Material.SEA_LANTERN);
        s(gx, Y + 1, gz, Material.ENDER_CHEST);
        s(gx - 1, Y + 1, gz, Material.ENCHANTING_TABLE);
        s(gx + 1, Y + 1, gz, Material.LECTERN);
        g.lantern(gx, 5, gz + 2, true);
        anchor.accept("holo_daily", new Location(w, gx + 0.5, Y + 3.4, gz + 0.5));
        // Стелы таблиц лидеров.
        for (int sx : new int[]{-1, 1}) {
            int x = sx * 9, z = -24;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) s(x + dx, Y, z + dz, Material.POLISHED_BLACKSTONE_BRICKS);
            s(x, Y + 1, z, Material.CHISELED_POLISHED_BLACKSTONE);
            s(x, Y + 2, z, Material.GOLD_BLOCK);
            s(x, Y + 3, z, Material.SEA_LANTERN);
        }
        anchor.accept("board_lobby_wealth", new Location(w, -8.5, Y + 6.2, -23.5));
        anchor.accept("board_lobby_pvp", new Location(w, 9.5, Y + 6.2, -23.5));
        // Катапульты (нажимная плита).
        for (int sx : new int[]{-1, 1}) {
            int x = sx * 12, z = 26;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) s(x + dx, Y, z + dz, Material.LIGHT_BLUE_CONCRETE);
            s(x, Y, z, Material.SLIME_BLOCK);
            s(x, Y + 1, z, Material.STONE_PRESSURE_PLATE);
            anchor.accept("holo_launch" + (sx < 0 ? 0 : 1), new Location(w, x + 0.5, Y + 2.2, z + 0.5));
        }
    }

    // ------------------------------------------------------------------ Секреты

    private void secrets() {
        // Колодец в полу с лестницей, внизу — ниша с кнопкой.
        int x = -35, z = 4;
        for (int y = Y - 4; y <= Y; y++) s(x, y, z, Material.AIR);
        s(x - 1, Y - 4, z, Material.AIR);
        for (int y = Y - 3; y <= Y; y++) {
            Block b = w.getBlockAt(x, y, z);
            b.setType(Material.LADDER, false);
            if (b.getBlockData() instanceof Ladder l) {
                l.setFacing(BlockFace.NORTH);
                b.setBlockData(l, false);
            }
        }
        for (int y = Y - 3; y <= Y; y++) s(x, y, z + 1, Material.DEEPSLATE_BRICKS);
        s(x - 1, Y - 5, z, Material.AMETHYST_BLOCK);
        s(x - 2, Y - 4, z, Material.AMETHYST_CLUSTER);
        s(x, Y - 5, z, Material.AMETHYST_BLOCK);
        button(x - 1, Y - 4, z, Material.STONE_BUTTON);
        anchor.accept("secret_under", new Location(w, x - 0.5, Y - 4, z + 0.5));
    }

    private void button(int x, int y, int z, Material m) {
        Block b = w.getBlockAt(x, y, z);
        b.setType(m, false);
        if (b.getBlockData() instanceof FaceAttachable fa) {
            fa.setAttachedFace(FaceAttachable.AttachedFace.FLOOR);
            b.setBlockData(fa, false);
        }
    }

    // ------------------------------------------------------------------ Спутниковые острова с играми

    private void satellite(int sx, int sz) {
        int r = 17;
        for (int dx = -r - 1; dx <= r + 1; dx++) for (int dz = -r - 1; dz <= r + 1; dz++) {
            double d = Math.hypot(dx, dz);
            if (d > r + 0.5) continue;
            int x = sx + dx, z = sz + dz;
            Material top = d > r - 1.5 ? Material.QUARTZ_BRICKS : d > r - 3 ? Material.LIGHT_BLUE_CONCRETE : Material.GRASS_BLOCK;
            s(x, Y, z, top);
            for (int dy = 1; dy <= 3; dy++) s(x, Y + dy, z, Material.AIR);
            int depth = (int) ((r + 0.5 - d) * 0.7) + 2;
            for (int k = 1; k <= depth; k++) s(x, Y - k, z, k == depth ? Material.CRYING_OBSIDIAN : k == 1 ? Material.DIRT : rnd.nextInt(30) == 0 ? Material.AMETHYST_BLOCK : Material.DEEPSLATE);
            if (d < r - 3 && rnd.nextInt(9) == 0) s(x, Y + 1, z, Material.SHORT_GRASS);
            else if (d < r - 3 && rnd.nextInt(30) == 0) s(x, Y + 1, z, new Material[]{Material.PINK_TULIP, Material.ALLIUM, Material.CORNFLOWER}[rnd.nextInt(3)]);
        }
        // Фонари по краю.
        for (int a = 0; a < 360; a += 30) {
            int x = sx + (int) Math.round(Math.cos(Math.toRadians(a)) * (r - 1)), z = sz + (int) Math.round(Math.sin(Math.toRadians(a)) * (r - 1));
            s(x, Y + 1, z, Material.DIORITE_WALL);
            s(x, Y + 2, z, Material.SEA_LANTERN);
        }
    }

    private void bridge(int sx, int sz) {
        double inv = 0.70710678;
        int end = (int) (Math.hypot(sx, sz) - 15);
        for (int n = 44; n <= end; n++) {
            int bx = (int) Math.round(Math.signum(sx) * n * inv), bz = (int) Math.round(Math.signum(sz) * n * inv);
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                s(bx + dx, Y, bz + dz, (n % 6 == 0 && dx == 0 && dz == 0) ? Material.SEA_LANTERN : Material.QUARTZ_BRICKS);
                s(bx + dx, Y - 1, bz + dz, Material.DEEPSLATE_BRICKS);
                for (int dy = 1; dy <= 3; dy++) s(bx + dx, Y + dy, bz + dz, Material.AIR);
            }
            if (n % 3 == 0) {
                int px = bx + (int) -Math.signum(sz) * 2, pz = bz + (int) Math.signum(sx) * 2;
                s(px, Y, pz, Material.QUARTZ_BRICKS);
                s(px, Y + 1, pz, Material.DIORITE_WALL);
                int qx = bx - (int) -Math.signum(sz) * 2, qz = bz - (int) Math.signum(sx) * 2;
                s(qx, Y, qz, Material.QUARTZ_BRICKS);
                s(qx, Y + 1, qz, Material.DIORITE_WALL);
            }
        }
    }

    private void games() {
        satellite(62, -62);
        satellite(-62, -62);
        satellite(-62, 62);
        bridge(62, -62);
        bridge(-62, -62);
        bridge(-62, 62);
        Fun f = new Fun(w, g, anchor, rnd, "_l");
        // Северо-восток: дискотека и кликер.
        f.disco(62, -68);
        f.clicker(62, -53);
        // Северо-запад: батуты и колесо удачи.
        f.trampolines(-70, -71, 15, 8);
        f.wheel(-62, -52, true);
        // Юго-запад: снежки, тир и салют.
        f.snowPile(-72, 56, true);
        f.snowPile(-52, 56, false);
        f.fireworkPedestal(-62, 62);
        f.tirLectern(-62, 72);
        f.targets(-70, -54, 49, 3);
        // Декоративные парящие островки.
        int[][] isl = {{80, 25, -30, 6}, {-80, 30, -34, 6}, {72, 34, 52, 7}, {-84, 28, 40, 6}, {0, 40, -72, 5}, {0, 36, 74, 5}};
        for (int i = 0; i < isl.length; i++) f.floatingIsland(isl[i][0], Y + isl[i][1], isl[i][2], isl[i][3], i % 2 == 0);
    }

    // ------------------------------------------------------------------ Паркуры

    private void parkours() {
        for (ParkourCourses.Course course : ParkourCourses.all()) {
            for (ParkourCourses.Jump j : course.jumps()) buildJump(course, j);
            ParkourCourses.Jump st = course.start(), fin = course.finish();
            anchor.accept("park_" + course.id(), new Location(w, st.px() + 0.5, st.y() + 3.3, st.pz() + 0.5));
            anchor.accept("park_" + course.id() + "_end", new Location(w, fin.px() + 0.5, fin.y() + 3.3, fin.pz() + 0.5));
            // Арка старта.
            archAt(st, course);
            archAt(fin, course);
        }
        // Финал сложного паркура: пьедестал с тайной кнопкой и отшельник.
        ParkourCourses.Jump hf = ParkourCourses.get("hard").finish();
        s(hf.x(), hf.y() + 1, hf.z(), Material.POLISHED_BLACKSTONE);
        button(hf.x(), hf.y() + 2, hf.z(), Material.POLISHED_BLACKSTONE_BUTTON);
        anchor.accept("secret_hard_end", new Location(w, hf.x() + 0.5, hf.y() + 2, hf.z() + 0.5));
        anchor.accept("hermit", new Location(w, hf.x() + hf.size() - 0.5, hf.y() + 1, hf.z() + hf.size() - 0.5, 0, 0));
    }

    private void buildJump(ParkourCourses.Course c, ParkourCourses.Jump j) {
        boolean special = j.kind() != ParkourCourses.Kind.NORMAL;
        for (int dx = 0; dx < j.size(); dx++) for (int dz = 0; dz < j.size(); dz++) {
            Material m;
            if (j.kind() == ParkourCourses.Kind.START) m = ((dx + dz) & 1) == 0 ? Material.GOLD_BLOCK : Material.QUARTZ_BLOCK;
            else if (j.kind() == ParkourCourses.Kind.FINISH) m = ((dx + dz) & 1) == 0 ? Material.EMERALD_BLOCK : Material.QUARTZ_BLOCK;
            else if (j.kind() == ParkourCourses.Kind.CHECKPOINT) m = Material.IRON_BLOCK;
            else m = switch (c.id()) {
                case "easy" -> RAINBOW[j.index() % RAINBOW.length];
                case "medium" -> MED[j.index() % MED.length];
                default -> j.index() % 2 == 0 ? Material.BLACK_CONCRETE : Material.GRAY_CONCRETE;
            };
            s(j.x() + dx, j.y(), j.z() + dz, m);
            if (special) for (int k = 1; k <= 3; k++) s(j.x() + dx, j.y() - k, j.z() + dz, k == 3 ? Material.CRYING_OBSIDIAN : Material.DEEPSLATE_BRICKS);
            else if (c.id().equals("hard") && j.index() % 3 == 0) s(j.x() + dx, j.y() - 1, j.z() + dz, Material.SEA_LANTERN);
            else if (c.id().equals("medium") && j.index() % 4 == 0) s(j.x() + dx, j.y() - 1, j.z() + dz, Material.GLOWSTONE);
        }
        Material plate = switch (j.kind()) {
            case START, FINISH -> Material.LIGHT_WEIGHTED_PRESSURE_PLATE;
            case CHECKPOINT -> Material.HEAVY_WEIGHTED_PRESSURE_PLATE;
            default -> null;
        };
        if (plate != null) s(j.px(), j.y() + 1, j.pz(), plate);
    }

    private void archAt(ParkourCourses.Jump j, ParkourCourses.Course c) {
        // Два столба по бокам площадки вдоль оси хода и перекладина с лучом света.
        int cx = j.px(), cz = j.pz();
        int ax = c.dx() != 0 ? 0 : 2, az = c.dx() != 0 ? 2 : 0;
        Material pillar = switch (c.id()) { case "easy" -> Material.LIME_CONCRETE; case "medium" -> Material.ORANGE_CONCRETE; default -> Material.RED_CONCRETE; };
        for (int y = 1; y <= 5; y++) {
            s(cx + ax, j.y() + y, cz + az, y == 5 ? Material.SEA_LANTERN : pillar);
            s(cx - ax, j.y() + y, cz - az, y == 5 ? Material.SEA_LANTERN : pillar);
        }
        for (int t = -2; t <= 2; t++) s(cx + (az != 0 ? 0 : t), j.y() + 6, cz + (az != 0 ? t : 0), pillar);
        s(cx, j.y() + 6, cz, Material.SEA_LANTERN);
        s(cx, j.y() + 7, cz, Material.END_ROD);
    }

    // ------------------------------------------------------------------ Комната входа

    private void authRoom() {
        int cx = 1000, cz = 1000, y0 = 149;
        for (int x = cx - 6; x <= cx + 6; x++) for (int y = y0; y <= y0 + 7; y++) for (int z = cz - 6; z <= cz + 6; z++) {
            int dx = Math.abs(x - cx), dz = Math.abs(z - cz);
            boolean wall = dx == 6 || dz == 6;
            Material m = Material.AIR;
            if (y == y0) {
                if (dx <= 1 && dz <= 1) m = Material.SEA_LANTERN;
                else if ((dx + dz) % 2 == 0) m = Material.POLISHED_BLACKSTONE_BRICKS;
                else m = Material.POLISHED_DEEPSLATE;
                if (wall) m = Material.DEEPSLATE_TILES;
            } else if (y == y0 + 7) {
                m = (dx % 3 == 0 && dz % 3 == 0) ? Material.SEA_LANTERN : Material.DARK_OAK_PLANKS;
            } else if (wall) {
                boolean pillar = (dx == 6 && (dz == 6 || dz == 3 || dz == 0)) || (dz == 6 && (dx == 3 || dx == 0));
                if (pillar) m = y == y0 + 1 || y == y0 + 6 ? Material.CHISELED_POLISHED_BLACKSTONE : Material.POLISHED_BLACKSTONE_BRICKS;
                else m = (y == y0 + 3 || y == y0 + 4) ? Material.LIGHT_BLUE_STAINED_GLASS : Material.DARK_OAK_PLANKS;
            }
            s(x, y, z, m);
        }
        // Мини-логотип на полу комнаты.
        for (int[] px : Canvas.Glyphs.logo(5, 2)) s(cx + px[0], y0, cz + 2 - px[1], LOGO[px[2]]);
        for (int[] l : new int[][]{{-3, -3}, {3, -3}, {-3, 3}, {3, 3}}) {
            s(cx + l[0], y0 + 6, cz + l[1], Material.IRON_CHAIN);
            Block lantern = w.getBlockAt(cx + l[0], y0 + 5, cz + l[1]);
            lantern.setType(Material.SOUL_LANTERN, false);
            if (lantern.getBlockData() instanceof org.bukkit.block.data.type.Lantern ln) {
                ln.setHanging(true);
                lantern.setBlockData(ln, false);
            }
        }
        for (int[] a : new int[][]{{-5, -5}, {5, -5}, {-5, 5}, {5, 5}}) {
            s(cx + a[0], y0 + 1, cz + a[1], Material.AMETHYST_BLOCK);
            s(cx + a[0], y0 + 2, cz + a[1], Material.AMETHYST_CLUSTER);
        }
    }

    // ------------------------------------------------------------------ Якоря

    private void anchors() {
        anchor.accept("guide", new Location(w, -4.5, Y + 1, -29.5, 0, 0));
        anchor.accept("duel_master", new Location(w, 4.5, Y + 1, 29.5, 180, 0));
        anchor.accept("holo_rules", new Location(w, -15.5, Y + 4.0, 0.5));
        anchor.accept("holo_tips", new Location(w, 15.5, Y + 4.0, 0.5));
        anchor.accept("holo_smp", new Location(w, 0.5, Y + 11.2, -36.5));
        anchor.accept("holo_duels", new Location(w, 0.5, Y + 11.2, 36.5));
        anchor.accept("holo_logo", new Location(w, 0.5, Y + 26.0, 0.5));
        anchor.accept("lobby_spawn", new Location(w, 0.5, Y + 1, 0.5, 180, 0));
    }
}
