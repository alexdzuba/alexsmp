package ru.unknownsmp.core;

import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;

/** Помощники планировщика. Работа с миром и игроками — только в основном потоке. */
public final class Tasks {
    private Tasks() {}

    public static void sync(Runnable r) {
        UnknownSMP pl = UnknownSMP.get();
        if (Bukkit.isPrimaryThread()) {
            safe(r);
        } else if (pl.isEnabled()) {
            Bukkit.getScheduler().runTask(pl, () -> safe(r));
        }
    }

    public static BukkitTask later(long ticks, Runnable r) {
        return Bukkit.getScheduler().runTaskLater(UnknownSMP.get(), () -> safe(r), ticks);
    }

    public static BukkitTask timer(long delay, long period, Runnable r) {
        return Bukkit.getScheduler().runTaskTimer(UnknownSMP.get(), () -> safe(r), delay, period);
    }

    public static BukkitTask async(Runnable r) {
        return Bukkit.getScheduler().runTaskAsynchronously(UnknownSMP.get(), () -> safe(r));
    }

    public static BukkitTask asyncTimer(long delay, long period, Runnable r) {
        return Bukkit.getScheduler().runTaskTimerAsynchronously(UnknownSMP.get(), () -> safe(r), delay, period);
    }

    /** Выполнить обработчик результата в основном потоке. */
    public static <T> void then(CompletableFuture<T> f, Consumer<T> ok, Consumer<Throwable> err) {
        f.whenComplete((v, t) -> sync(() -> {
            if (t != null) {
                Throwable cause = t.getCause() != null && t instanceof java.util.concurrent.CompletionException ? t.getCause() : t;
                if (err != null) err.accept(cause);
                else UnknownSMP.get().getLogger().log(Level.WARNING, "Ошибка операции", cause);
            } else if (ok != null) {
                ok.accept(v);
            }
        }));
    }

    private static void safe(Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            UnknownSMP.get().getLogger().log(Level.SEVERE, "Ошибка в задаче", t);
            if (UnknownSMP.get().monitor() != null) UnknownSMP.get().monitor().error("task", t);
        }
    }
}
