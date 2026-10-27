package life.work.IntFit.backend.supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The one supplier-tracker guarantee that cannot be proven on H2: a unique index over
 * active_bank_check_id must admit many NULLs (so reversed payments keep their history) while
 * still allowing exactly one live use of a cheque, under real concurrency on real MySQL.
 *
 * Runs only inside the guarded disposable-container runner; there is no default datasource.
 */
@EnabledIfEnvironmentVariable(named = "INTFITOUT_MYSQL_TEST_ENABLED", matches = "true")
class SupplierPaymentMySqlIntegrationTest {

    private static final String DATABASE = "intfitout_reporting_validation";

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void connectToTheDisposableServerOnly() throws Exception {
        String port = System.getenv("INTFITOUT_MYSQL_TEST_PORT");
        String user = System.getenv("INTFITOUT_MYSQL_TEST_USER");
        String password = System.getenv("INTFITOUT_MYSQL_TEST_PASSWORD");
        String runId = System.getenv("INTFITOUT_MYSQL_TEST_RUN_ID");
        assertTrue(port != null && port.matches("[0-9]{1,5}"),
                "Use the disposable-container runner; no default datasource is allowed");
        assertTrue(user != null && user.matches("rv_[a-f0-9]{12}"));
        assertTrue(password != null && password.matches("[a-f0-9]{64}"));
        assertTrue(runId != null && runId.matches("[a-f0-9]{32}"));

        int p = Integer.parseInt(port);
        dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl("jdbc:mysql://127.0.0.1:" + p + "/" + DATABASE
                + "?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC"
                + "&forceConnectionTimeZoneToSession=true&connectTimeout=3000&socketTimeout=10000");
        dataSource.setUsername(user);
        dataSource.setPassword(password);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(10);

        assertEquals(DATABASE, jdbc.queryForObject("SELECT DATABASE()", String.class));
        assertEquals(List.of(runId),
                jdbc.queryForList("SELECT run_id FROM intfitout_validation_guard", String.class),
                "Refusing fixture DDL without this fresh run's guard");
        assertEquals("9.7.2", jdbc.queryForObject("SELECT VERSION()", String.class));

        // The table exactly as the JPA mapping declares it, including the unique index.
        jdbc.execute("DROP TABLE IF EXISTS supplier_payment");
        jdbc.execute("""
            CREATE TABLE supplier_payment (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                supplier_name VARCHAR(120) NOT NULL,
                payment_date DATE NOT NULL,
                amount DECIMAL(14,2) NOT NULL,
                method VARCHAR(20) NOT NULL,
                bank_check_id BIGINT NULL,
                active_bank_check_id BIGINT NULL,
                reference VARCHAR(120) NULL,
                notes VARCHAR(1000) NULL,
                reversed_at DATETIME(6) NULL,
                reversal_reason VARCHAR(500) NULL,
                recorded_by VARCHAR(120) NULL,
                created_at DATETIME(6) NOT NULL,
                CONSTRAINT uk_supplier_payment_active_check UNIQUE (active_bank_check_id)
            )
            """);
        try (Connection c = dataSource.getConnection()) {
            System.out.println("supplier_mysql_version=" + c.getMetaData().getDatabaseProductVersion()
                    + " driver=" + c.getMetaData().getDriverVersion());
        }
    }

    private int insertActive(long chequeId) {
        return jdbc.update("""
                INSERT INTO supplier_payment
                    (supplier_name, payment_date, amount, method, bank_check_id, active_bank_check_id, created_at)
                VALUES ('AL-Eatimad', '2026-10-25', 4000.00, 'CLIENT_CHEQUE', ?, ?, NOW(6))
                """, chequeId, chequeId);
    }

    @Test void oneChequeCannotBackTwoActivePayments() {
        assertEquals(1, insertActive(41L));
        assertThrows(DuplicateKeyException.class, () -> insertActive(41L),
                "MySQL must refuse a second live use of the same cheque");
    }

    @Test void reversedPaymentsKeepTheirLinkAndDoNotBlockReuse() {
        insertActive(41L);
        // Reversing clears only the active marker; bank_check_id stays as evidence.
        jdbc.update("UPDATE supplier_payment SET reversed_at = NOW(6), active_bank_check_id = NULL "
                + "WHERE active_bank_check_id = ?", 41L);
        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM supplier_payment WHERE bank_check_id = 41", Integer.class));

        // The cheque can be used again, and many reversed rows may coexist: unique allows NULLs.
        assertEquals(1, insertActive(41L));
        jdbc.update("UPDATE supplier_payment SET reversed_at = NOW(6), active_bank_check_id = NULL "
                + "WHERE active_bank_check_id = ?", 41L);
        assertEquals(1, insertActive(41L));
        assertEquals(3, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM supplier_payment WHERE bank_check_id = 41", Integer.class),
                "every historical use is preserved");
        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM supplier_payment WHERE active_bank_check_id = 41", Integer.class),
                "but only one is live");
    }

    @Test void concurrentInsertsForOneChequeLeaveExactlyOneWinner() throws Exception {
        int threads = 8;
        var ready = new CountDownLatch(threads);
        var go = new CountDownLatch(1);
        var wins = new AtomicInteger();
        var pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        if (!go.await(10, TimeUnit.SECONDS)) return;
                        insertActive(99L);
                        wins.incrementAndGet();
                    } catch (RuntimeException | InterruptedException ignored) {
                        // Losers are refused by the unique index; that is the point.
                    }
                });
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, wins.get(), "exactly one concurrent handover may win");
        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM supplier_payment WHERE active_bank_check_id = 99", Integer.class));
    }

    @Test void differentChequesAreUnaffectedByTheConstraint() {
        assertEquals(1, insertActive(1L));
        assertEquals(1, insertActive(2L));
        assertEquals(1, insertActive(3L));
        assertEquals(3, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM supplier_payment WHERE active_bank_check_id IS NOT NULL", Integer.class));
    }
}
