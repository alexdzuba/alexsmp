package ru.unknownsmp.core;

/** Внутренний модуль плагина. Ошибка одного модуля не останавливает остальные. */
public interface Module {
    /** Машинное имя модуля (используется в журналах и статусе). */
    String id();

    /** Название для людей. */
    String title();

    /** Модули, без которых этот модуль работать не может. */
    default String[] depends() { return new String[0]; }

    void enable() throws Exception;

    default void disable() {}

    /** Безопасная горячая перезагрузка настроек. Не должна прерывать активные транзакции. */
    default void reload() {}
}
