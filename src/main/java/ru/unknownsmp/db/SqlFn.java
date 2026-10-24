package ru.unknownsmp.db;

import java.sql.Connection;

@FunctionalInterface
public interface SqlFn<T> {
    T apply(Connection c) throws Exception;
}
