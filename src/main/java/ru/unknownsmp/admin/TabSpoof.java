package ru.unknownsmp.admin;

import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.EnumSet;
import java.util.List;

/**
 * Подмена режима игры в списке игроков (табе): наблюдающий персонал выглядит как обычный игрок в режиме выживания
 * (без «серого курсива» наблюдателя). Пакеты отправляются только другим игрокам; если сервер изменит внутренности
 * пакета, подмена тихо отключится.
 */
final class TabSpoof {
    private TabSpoof() {}

    private static volatile boolean broken;
    private static Class<?> actionClass, entryClass, gameTypeClass, serverPlayerClass, packetClass;
    private static Constructor<?> entryFromPlayer, entryCanonical, packetCtor;
    private static RecordComponent[] components;
    private static Object survival, updateGameMode;
    private static boolean ready;

    private static synchronized void init() throws Exception {
        if (ready) return;
        packetClass = Class.forName("net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket");
        actionClass = Class.forName("net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket$Action");
        entryClass = Class.forName("net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket$Entry");
        gameTypeClass = Class.forName("net.minecraft.world.level.GameType");
        serverPlayerClass = Class.forName("net.minecraft.server.level.ServerPlayer");
        entryFromPlayer = entryClass.getDeclaredConstructor(serverPlayerClass);
        entryFromPlayer.setAccessible(true);
        components = entryClass.getRecordComponents();
        Class<?>[] types = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) types[i] = components[i].getType();
        entryCanonical = entryClass.getDeclaredConstructor(types);
        entryCanonical.setAccessible(true);
        packetCtor = packetClass.getDeclaredConstructor(EnumSet.class, List.class);
        packetCtor.setAccessible(true);
        survival = gameTypeClass.getField("SURVIVAL").get(null);
        updateGameMode = Enum.valueOf((Class<Enum>) actionClass.asSubclass(Enum.class), "UPDATE_GAME_MODE");
        ready = true;
    }

    private static Object handle(Player p) throws Exception {
        return p.getClass().getMethod("getHandle").invoke(p);
    }

    /** Показать viewer'у игрока target в табе как игрока в режиме выживания. */
    static void send(Player viewer, Player target) {
        if (broken || viewer.equals(target)) return;
        try {
            init();
            Object entry = entryFromPlayer.newInstance(handle(target));
            Object[] args = new Object[components.length];
            for (int i = 0; i < components.length; i++) {
                Object v = components[i].getAccessor().invoke(entry);
                if (components[i].getType() == gameTypeClass) v = survival;
                args[i] = v;
            }
            Object fake = entryCanonical.newInstance(args);
            @SuppressWarnings({"rawtypes", "unchecked"})
            EnumSet set = EnumSet.of((Enum) updateGameMode);
            Object packet = packetCtor.newInstance(set, List.of(fake));
            Object vsp = handle(viewer);
            Object conn = vsp.getClass().getField("connection").get(vsp);
            Method send = null;
            for (Method m : conn.getClass().getMethods()) {
                if (m.getName().equals("send") && m.getParameterCount() == 1 && m.getParameterTypes()[0].isAssignableFrom(packetClass)) { send = m; break; }
            }
            if (send == null) throw new NoSuchMethodException("send");
            send.invoke(conn, packet);
        } catch (Throwable t) {
            broken = true;
            org.bukkit.Bukkit.getLogger().warning("[UnknownSMP] Подмена режима в табе недоступна: " + t);
        }
    }
}
