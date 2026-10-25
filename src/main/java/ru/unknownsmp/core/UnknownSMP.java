package ru.unknownsmp.core;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;
import ru.unknownsmp.admin.AdminService;
import ru.unknownsmp.admin.BackupService;
import ru.unknownsmp.admin.LogService;
import ru.unknownsmp.admin.MaintenanceService;
import ru.unknownsmp.admin.ModerationService;
import ru.unknownsmp.admin.MonitorService;
import ru.unknownsmp.admin.ReportService;
import ru.unknownsmp.admin.RollbackService;
import ru.unknownsmp.auth.AuthService;
import ru.unknownsmp.chat.ChatService;
import ru.unknownsmp.chat.DisplayService;
import ru.unknownsmp.clans.ClanService;
import ru.unknownsmp.clans.WarService;
import ru.unknownsmp.combat.CombatService;
import ru.unknownsmp.db.Database;
import ru.unknownsmp.db.Migrations;
import ru.unknownsmp.duels.DuelService;
import ru.unknownsmp.economy.EconomyService;
import ru.unknownsmp.events.AfkTracker;
import ru.unknownsmp.events.DimensionService;
import ru.unknownsmp.events.EventService;
import ru.unknownsmp.events.LuckService;
import ru.unknownsmp.events.MiniEventService;
import ru.unknownsmp.gui.MenuListener;
import ru.unknownsmp.items.ItemService;
import ru.unknownsmp.lobby.LobbyService;
import ru.unknownsmp.market.AuctionService;
import ru.unknownsmp.market.MailService;
import ru.unknownsmp.market.OrderService;
import ru.unknownsmp.market.ShopService;
import ru.unknownsmp.menus.CoreCommands;
import ru.unknownsmp.mobs.MobService;
import ru.unknownsmp.npc.NpcService;
import ru.unknownsmp.profile.ProfileService;
import ru.unknownsmp.progression.LeaderboardService;
import ru.unknownsmp.progression.ProgressionService;
import ru.unknownsmp.quests.BountyService;
import ru.unknownsmp.quests.QuestService;
import ru.unknownsmp.teleport.TeleportService;
import ru.unknownsmp.world.WorldService;

import java.time.ZoneId;
import java.util.logging.Level;

/**
 * ALEX SMP — единый плагин сервера. Все игровые системы — внутренние модули.
 */
public final class UnknownSMP extends JavaPlugin implements Listener {
    public static final String TARGET_MC = "1.21.11";
    private static UnknownSMP instance;

    private Configs configs;
    private Database db;
    private WorldState state;
    private ModuleManager modules;
    private Roles roles;
    private Commands commands;
    private ProfileService profiles;
    private AuthService auth;
    private LobbyService lobby;
    private DuelService duels;
    private WorldService world;
    private TeleportService teleports;
    private CombatService combat;
    private EconomyService economy;
    private ShopService shop;
    private AuctionService auction;
    private OrderService orders;
    private MailService mail;
    private ClanService clans;
    private ru.unknownsmp.clans.ClaimService claims;
    private ru.unknownsmp.clans.PointService points;
    private ru.unknownsmp.items.CustomEnchantService enchants;
    private ru.unknownsmp.economy.MoneyStoneService moneyStones;
    private ru.unknownsmp.admin.LagGuard lagGuard;
    private ru.unknownsmp.world.ChunkLoaderService chunkLoaders;
    private ru.unknownsmp.market.SpawnerShopService spawners;
    private ru.unknownsmp.admin.InventorySnapshotService snapshots;
    private ru.unknownsmp.market.TradeService trades;
    private WarService wars;
    private EventService events;
    private ru.unknownsmp.zhora.ZhoraService zhora;
    private DimensionService dimensions;
    private AfkTracker afk;
    private LuckService luck;
    private MiniEventService miniEvents;
    private ItemService items;
    private QuestService quests;
    private BountyService bounties;
    private NpcService npcs;
    private ProgressionService progression;
    private LeaderboardService leaderboards;
    private ChatService chat;
    private DisplayService display;
    private LogService logs;
    private RollbackService rollback;
    private ModerationService moderation;
    private ReportService reports;
    private BackupService backups;
    private MaintenanceService maintenance;
    private MonitorService monitor;
    private AdminService admin;
    private MobService mobs;
    private boolean fullyStarted;
    private volatile String startupBlock;

    public static UnknownSMP get() { return instance; }

    @Override
    public void onLoad() {
        instance = this;
        // Применение отложенного восстановления резервной копии — до загрузки миров и БД.
        try {
            BackupService.applyPendingRestore(this);
        } catch (Throwable t) {
            getLogger().log(Level.SEVERE, "Не удалось применить отложенное восстановление", t);
        }
    }

    @Override
    public void onEnable() {
        instance = this;
        long t0 = System.currentTimeMillis();
        getServer().getPluginManager().registerEvents(this, this);
        configs = new Configs(this);
        configs.loadAll();
        try {
            Text.setZone(ZoneId.of(configs.main().getString("timezone", "Europe/Moscow")));
        } catch (Exception e) {
            getLogger().warning("Неверный часовой пояс в config.yml, используется системный.");
        }
        Keys.init(this);
        for (String p : configs.problems()) getLogger().warning("[Конфигурация] " + p);

        // ---- Проверка совместимости ----
        String problem = CompatCheck.check(this);
        if (problem != null) {
            getLogger().severe("==============================================");
            getLogger().severe(" ALEX SMP: НЕСОВМЕСТИМОЕ ОКРУЖЕНИЕ");
            getLogger().severe(" " + problem);
            getLogger().severe(" Плагин безопасно отключён. Данные не изменялись.");
            getLogger().severe("==============================================");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        modules = new ModuleManager(this);
        commands = new Commands(this);
        roles = modules.add(new Roles(this));
        monitor = new MonitorService(this);
        logs = new LogService(this);
        backups = new BackupService(this);

        // ---- База данных ----
        db = new Database(this);
        Module dbModule = new Module() {
            @Override public String id() { return "database"; }
            @Override public String title() { return "База данных"; }
            @Override public void enable() throws Exception {
                db.connect(configs.get("database.yml"));
                BackupService.applyPendingImport(UnknownSMP.this, db);
                Migrations.migrate(UnknownSMP.this, db);
            }
        };
        modules.add(dbModule);
        state = modules.add(new WorldState(this));
        modules.add(monitor);
        modules.add(logs);
        modules.add(backups);

        profiles = modules.add(new ProfileService(this));
        auth = modules.add(new AuthService(this));
        moderation = modules.add(new ModerationService(this));
        maintenance = modules.add(new MaintenanceService(this));
        afk = modules.add(new AfkTracker(this));
        luck = modules.add(new LuckService(this));
        world = modules.add(new WorldService(this));
        lobby = modules.add(new LobbyService(this));
        duels = modules.add(new DuelService(this));
        economy = modules.add(new EconomyService(this));
        mail = modules.add(new MailService(this));
        items = modules.add(new ItemService(this));
        progression = modules.add(new ProgressionService(this));
        teleports = modules.add(new TeleportService(this));
        combat = modules.add(new CombatService(this));
        shop = modules.add(new ShopService(this));
        auction = modules.add(new AuctionService(this));
        orders = modules.add(new OrderService(this));
        clans = modules.add(new ClanService(this));
        wars = modules.add(new WarService(this));
        claims = modules.add(new ru.unknownsmp.clans.ClaimService(this));
        points = modules.add(new ru.unknownsmp.clans.PointService(this));
        dimensions = modules.add(new DimensionService(this));
        events = modules.add(new EventService(this));
        miniEvents = modules.add(new MiniEventService(this));
        quests = modules.add(new QuestService(this));
        bounties = modules.add(new BountyService(this));
        npcs = modules.add(new NpcService(this));
        leaderboards = modules.add(new LeaderboardService(this));
        chat = modules.add(new ChatService(this));
        display = modules.add(new DisplayService(this));
        rollback = modules.add(new RollbackService(this));
        reports = modules.add(new ReportService(this));
        mobs = modules.add(new MobService(this));
        enchants = modules.add(new ru.unknownsmp.items.CustomEnchantService(this));
        moneyStones = modules.add(new ru.unknownsmp.economy.MoneyStoneService(this));
        lagGuard = modules.add(new ru.unknownsmp.admin.LagGuard(this));
        chunkLoaders = modules.add(new ru.unknownsmp.world.ChunkLoaderService(this));
        spawners = modules.add(new ru.unknownsmp.market.SpawnerShopService(this));
        snapshots = modules.add(new ru.unknownsmp.admin.InventorySnapshotService(this));
        trades = modules.add(new ru.unknownsmp.market.TradeService(this));
        zhora = modules.add(new ru.unknownsmp.zhora.ZhoraService(this));
        admin = modules.add(new AdminService(this));
        modules.add(new CoreCommands(this));

        getServer().getPluginManager().registerEvents(new MenuListener(), this);
        getServer().getPluginManager().registerEvents(new ChatInput(), this);

        modules.enableAll();

        if (!modules.isActive("database")) {
            startupBlock = "База данных недоступна: проверьте database.yml и журнал сервера.";
            getLogger().severe("БАЗА ДАННЫХ НЕДОСТУПНА. Вход игроков заблокирован, чтобы не допустить потери данных.");
        } else if (!modules.isActive("auth")) {
            startupBlock = "Модуль авторизации не запустился.";
            getLogger().severe("Модуль авторизации не запустился. Вход игроков заблокирован.");
        }

        Tasks.timer(20 * 30, 20 * 30, Msg::saveIfDirty);
        fullyStarted = true;
        SetupWizard.consoleReport(this);
        getLogger().info("ALEX SMP " + getPluginMeta().getVersion() + " запущен за " + (System.currentTimeMillis() - t0) + " мс.");
    }

    @Override
    public void onDisable() {
        fullyStarted = false;
        if (modules != null) modules.disableAll();
        if (db != null) db.close();
        Msg.saveIfDirty();
    }

    /** Если ядро не смогло безопасно запуститься — вход запрещён. */
    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        if (startupBlock != null) {
            e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    Text.c("&cСервер временно недоступен (техническая проверка данных).\n&7Попробуйте позже."));
        }
    }

    public boolean isFullyStarted() { return fullyStarted; }

    public String lobbyWorldName() {
        return configs == null ? "usmp_lobby" : configs.main().getString("lobby.world", "usmp_lobby");
    }

    public World survivalWorld() {
        String n = configs.main().getString("survival.world", "world");
        World w = Bukkit.getWorld(n);
        return w != null ? w : Bukkit.getWorlds().get(0);
    }

    public String startupBlock() { return startupBlock; }

    public Configs configs() { return configs; }
    public Database db() { return db; }
    public WorldState state() { return state; }
    public ModuleManager modules() { return modules; }
    public Roles roles() { return roles; }
    public Commands commands() { return commands; }
    public ProfileService profiles() { return profiles; }
    public AuthService auth() { return auth; }
    public LobbyService lobby() { return lobby; }
    public DuelService duels() { return duels; }
    public WorldService world() { return world; }
    public TeleportService teleports() { return teleports; }
    public CombatService combat() { return combat; }
    public EconomyService economy() { return economy; }
    public ShopService shop() { return shop; }
    public AuctionService auction() { return auction; }
    public OrderService orders() { return orders; }
    public MailService mail() { return mail; }
    public ClanService clans() { return clans; }
    public WarService wars() { return wars; }
    public EventService events() { return events; }
    public ru.unknownsmp.zhora.ZhoraService zhora() { return zhora; }
    public DimensionService dimensions() { return dimensions; }
    public AfkTracker afk() { return afk; }
    public LuckService luck() { return luck; }
    public MiniEventService miniEvents() { return miniEvents; }
    public ItemService items() { return items; }
    public ru.unknownsmp.clans.ClaimService claims() { return claims; }
    public ru.unknownsmp.clans.PointService points() { return points; }
    public ru.unknownsmp.items.CustomEnchantService enchants() { return enchants; }
    public ru.unknownsmp.economy.MoneyStoneService moneyStones() { return moneyStones; }
    public ru.unknownsmp.admin.LagGuard lagGuard() { return lagGuard; }
    public ru.unknownsmp.world.ChunkLoaderService chunkLoaders() { return chunkLoaders; }
    public ru.unknownsmp.market.SpawnerShopService spawners() { return spawners; }
    public ru.unknownsmp.admin.InventorySnapshotService snapshots() { return snapshots; }
    public ru.unknownsmp.market.TradeService trades() { return trades; }
    public QuestService quests() { return quests; }
    public BountyService bounties() { return bounties; }
    public NpcService npcs() { return npcs; }
    public ProgressionService progression() { return progression; }
    public LeaderboardService leaderboards() { return leaderboards; }
    public ChatService chat() { return chat; }
    public DisplayService display() { return display; }
    public LogService logs() { return logs; }
    public RollbackService rollback() { return rollback; }
    public ModerationService moderation() { return moderation; }
    public ReportService reports() { return reports; }
    public BackupService backups() { return backups; }
    public MaintenanceService maintenance() { return maintenance; }
    public MonitorService monitor() { return monitor; }
    public AdminService admin() { return admin; }
    public MobService mobs() { return mobs; }
}
