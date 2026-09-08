package com.technomorph.lck.examples.postgres;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * A bounded connection pool and the schema, shared by both worked ledgers.
 *
 * <p>The pool size is not incidental. A pool of one serialises every submission and every
 * concurrency invariant passes, whatever the ledger does — the defect is hidden by the client,
 * not absent from the server. Sixteen is enough contention for the races to be real and few
 * enough for a default Postgres to accept.
 */
final class Pg implements AutoCloseable {

    static final int POOL = 16;

    private final ArrayBlockingQueue<Connection> pool = new ArrayBlockingQueue<>(POOL);

    Pg(String url, String user, String password) throws SQLException {
        for (int i = 0; i < POOL; i++) {
            Connection c = DriverManager.getConnection(url, user, password);
            c.setAutoCommit(false);
            // READ COMMITTED: the default in PostgreSQL, MySQL, SQL Server and Oracle, and the
            // isolation level the naive ledger below is wrong under. Stating it rather than
            // inheriting it, because the whole demonstration turns on which one is in force.
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            pool.add(c);
        }
    }

    interface Work<T> { T run(Connection c) throws SQLException; }

    <T> T tx(Work<T> body) throws SQLException {
        Connection c;
        try { c = pool.take(); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("interrupted waiting for a connection", e);
        }
        try {
            T out = body.run(c);
            c.commit();
            return out;
        } catch (SQLException | RuntimeException e) {
            try { c.rollback(); } catch (SQLException ignored) { }
            throw e;
        } finally {
            pool.add(c);
        }
    }

    void ddl(String schema) throws SQLException {
        tx(c -> {
            try (Statement s = c.createStatement()) {
                s.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
                s.execute("""
                    CREATE TABLE IF NOT EXISTS %s.accounts (
                      account_id       TEXT   NOT NULL,
                      currency         CHAR(3) NOT NULL,
                      balance_subunits BIGINT NOT NULL DEFAULT 0,
                      PRIMARY KEY (account_id, currency))""".formatted(schema));
                s.execute("""
                    CREATE TABLE IF NOT EXISTS %s.journal_entries (
                      entry_id         BIGSERIAL PRIMARY KEY,
                      transaction_id   TEXT   NOT NULL,
                      account_id       TEXT   NOT NULL,
                      entry_type       TEXT   NOT NULL,
                      amount_subunits  BIGINT NOT NULL,
                      currency         CHAR(3) NOT NULL,
                      account_sequence BIGINT NOT NULL)""".formatted(schema));
            }
            return null;
        });
    }

    void truncate(String schema) throws SQLException {
        tx(c -> {
            try (Statement s = c.createStatement()) {
                s.execute("TRUNCATE %s.accounts, %s.journal_entries, %s.idempotency_keys"
                        .formatted(schema, schema, schema));
            }
            return null;
        });
    }

    @Override public void close() {
        pool.forEach(c -> { try { c.close(); } catch (SQLException ignored) { } });
    }
}
