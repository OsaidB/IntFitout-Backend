package life.work.IntFit.backend.closeout;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The database-level guarantees behind closeout draft concurrency, exercised on a real
 * disposable MySQL server.
 *
 * These properties cannot be established with mocks or with H2: what is being tested is
 * precisely how a real InnoDB engine resolves two writers racing on the same row and on the
 * same unique key. A mock would replay whatever the test assumed, and H2's locking and error
 * reporting differ from MySQL's.
 *
 * A closeout draft is one JSON blob holding every room, type and measurement, so the loser
 * of a race must be refused rather than allowed to replace the winner's work. Two mechanisms
 * do that, and both are checked here against the real engine:
 *
 *   - the unique key on master_worksite_id, for two devices creating the first draft;
 *   - the conditional "UPDATE ... WHERE id = ? AND version = ?" that Hibernate's @Version
 *     compiles into, for two devices editing a draft that already exists.
 *
 * Guarded by the disposable-container runner; it refuses to run against any other server.
 */
@EnabledIfEnvironmentVariable(named = "INTFITOUT_MYSQL_TEST_ENABLED", matches = "true")
class CloseoutDraftConcurrencyMySqlIntegrationTest {

    private static final String DATABASE = "intfitout_reporting_validation";

    /** Fixture id kept clear of the other suites' ranges. */
    private static final long MASTER_A = 9101L;

    private JdbcTemplate jdbc;
    private DriverManagerDataSource ds;

    @BeforeEach
    void connectAndBuildFixture() {
        String port = System.getenv("INTFITOUT_MYSQL_TEST_PORT");
        String user = System.getenv("INTFITOUT_MYSQL_TEST_USER");
        String password = System.getenv("INTFITOUT_MYSQL_TEST_PASSWORD");
        String runId = System.getenv("INTFITOUT_MYSQL_TEST_RUN_ID");
        assertTrue(port != null && port.matches("[0-9]{1,5}"),
                "Use the disposable-container runner; no default datasource is allowed");
        assertTrue(user != null && user.matches("rv_[a-f0-9]{12}"));
        assertTrue(password != null && password.matches("[a-f0-9]{64}"));
        assertTrue(runId != null && runId.matches("[a-f0-9]{32}"));

        ds = new DriverManagerDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setUrl("jdbc:mysql://127.0.0.1:" + Integer.parseInt(port) + "/" + DATABASE
                + "?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC"
                + "&forceConnectionTimeZoneToSession=true&connectTimeout=3000&socketTimeout=10000");
        ds.setUsername(user);
        ds.setPassword(password);
        jdbc = new JdbcTemplate(ds);
        jdbc.setQueryTimeout(10);

        // Create only this feature's own table, shaped exactly as the migration shapes it,
        // and never drop a table another suite may share.
        jdbc.execute("CREATE TABLE IF NOT EXISTS closeout_drafts ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "master_worksite_id BIGINT NOT NULL,"
                + "draft_json LONGTEXT NOT NULL,"
                + "version BIGINT NOT NULL DEFAULT 0,"
                + "created_at DATETIME(6) NOT NULL,"
                + "updated_at DATETIME(6) NOT NULL,"
                + "CONSTRAINT uk_closeout_drafts_master_worksite UNIQUE (master_worksite_id))");
        clean();
    }

    @AfterEach
    void cleanUp() {
        if (jdbc != null) clean();
    }

    private void clean() {
        jdbc.update("DELETE FROM closeout_drafts WHERE master_worksite_id = ?", MASTER_A);
    }

    /** The statement Hibernate's @Version produces. Returns rows affected. */
    private int conditionalUpdate(JdbcTemplate t, long master, String json, long baseVersion) {
        return t.update(
                "UPDATE closeout_drafts SET draft_json = ?, version = version + 1,"
                        + " updated_at = NOW(6) WHERE master_worksite_id = ? AND version = ?",
                json, master, baseVersion);
    }

    private int insertFirst(JdbcTemplate t, long master, String json) {
        return t.update("INSERT INTO closeout_drafts"
                + "(master_worksite_id, draft_json, version, created_at, updated_at)"
                + " VALUES (?,?,0,NOW(6),NOW(6))", master, json);
    }

    /** Runs the given jobs at the same instant, each on its own connection. */
    private List<Object> race(List<Callable<Object>> jobs) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(jobs.size());
        CyclicBarrier gate = new CyclicBarrier(jobs.size());
        List<Future<Object>> futures = new ArrayList<>();
        try {
            for (Callable<Object> job : jobs) {
                futures.add(pool.submit(() -> {
                    gate.await(10, TimeUnit.SECONDS);
                    try {
                        return job.call();
                    } catch (Exception e) {
                        return e;
                    }
                }));
            }
            List<Object> out = new ArrayList<>();
            for (Future<Object> f : futures) out.add(f.get(30, TimeUnit.SECONDS));
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    private JdbcTemplate freshConnection() {
        JdbcTemplate t = new JdbcTemplate(ds);
        t.setQueryTimeout(10);
        return t;
    }

    private long storedVersion() {
        return jdbc.queryForObject(
                "SELECT version FROM closeout_drafts WHERE master_worksite_id = ?", Long.class, MASTER_A);
    }

    private String storedJson() {
        return jdbc.queryForObject(
                "SELECT draft_json FROM closeout_drafts WHERE master_worksite_id = ?", String.class, MASTER_A);
    }

    @Test
    @DisplayName("two simultaneous first saves produce one draft, not two")
    void concurrentFirstCreate() throws Exception {
        List<Callable<Object>> jobs = List.of(
                () -> insertFirst(freshConnection(), MASTER_A, "{\"device\":\"A\"}"),
                () -> insertFirst(freshConnection(), MASTER_A, "{\"device\":\"B\"}"));

        List<Object> results = race(jobs);

        long drafts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM closeout_drafts WHERE master_worksite_id = ?", Long.class, MASTER_A);
        assertEquals(1L, drafts, "the unique key must admit exactly one first draft");

        long succeeded = results.stream().filter(r -> Integer.valueOf(1).equals(r)).count();
        long failed = results.stream().filter(r -> r instanceof Exception).count();
        assertEquals(1L, succeeded, "exactly one writer may create it");
        assertEquals(1L, failed, "the other must be refused, not silently merged");
    }

    @Test
    @DisplayName("two devices editing the same saved draft: one wins, the other is refused")
    void concurrentUpdateSameBaseVersion() throws Exception {
        insertFirst(jdbc, MASTER_A, "{\"rows\":\"original\"}");

        List<Callable<Object>> jobs = List.of(
                () -> conditionalUpdate(freshConnection(), MASTER_A, "{\"rows\":\"deviceA\"}", 0L),
                () -> conditionalUpdate(freshConnection(), MASTER_A, "{\"rows\":\"deviceB\"}", 0L));

        List<Object> results = race(jobs);

        long winners = results.stream().filter(r -> Integer.valueOf(1).equals(r)).count();
        long losers = results.stream().filter(r -> Integer.valueOf(0).equals(r)).count();
        assertEquals(1L, winners, "exactly one write may land");
        assertEquals(1L, losers, "the loser must update zero rows so it can be told");

        assertEquals(1L, storedVersion(), "the revision advances once per accepted write");

        String json = storedJson();
        assertTrue(json.equals("{\"rows\":\"deviceA\"}") || json.equals("{\"rows\":\"deviceB\"}"),
                "the stored draft is one device's complete draft, never a blend: " + json);
    }

    @Test
    @DisplayName("a stale revision cannot overwrite a newer one")
    void staleBaseVersionRejected() {
        insertFirst(jdbc, MASTER_A, "{\"rows\":\"v0\"}");
        assertEquals(1, conditionalUpdate(jdbc, MASTER_A, "{\"rows\":\"v1\"}", 0L));

        // A device that still believes it holds revision 0 tries again.
        assertEquals(0, conditionalUpdate(jdbc, MASTER_A, "{\"rows\":\"stale\"}", 0L),
                "a writer holding an old revision must not land");

        assertEquals("{\"rows\":\"v1\"}", storedJson(), "the newer draft survives untouched");
    }

    @Test
    @DisplayName("overlapping autosaves all based on one revision: exactly one lands")
    void overlappingAutosaves() throws Exception {
        insertFirst(jdbc, MASTER_A, "{\"n\":0}");

        AtomicInteger accepted = new AtomicInteger();
        List<Callable<Object>> jobs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            final int n = i;
            jobs.add(() -> {
                int rows = conditionalUpdate(freshConnection(), MASTER_A, "{\"n\":" + n + "}", 0L);
                if (rows == 1) accepted.incrementAndGet();
                return rows;
            });
        }
        race(jobs);

        assertEquals(1, accepted.get(),
                "only the write based on the stored revision may land; the rest must retry");
        assertEquals(1L, storedVersion(), "no write may be applied twice");
    }

    @Test
    @DisplayName("an accepted write whose response was lost: the retry is refused, not applied twice")
    void acceptedWriteWithLostResponse() {
        insertFirst(jdbc, MASTER_A, "{\"rows\":\"v0\"}");

        // The server accepted this write; imagine the response never reached the device.
        assertEquals(1, conditionalUpdate(jdbc, MASTER_A, "{\"rows\":\"v1\"}", 0L));

        // The device retries with the only revision it knows. It must be refused rather
        // than applying the same edit twice or reverting the stored draft.
        assertEquals(0, conditionalUpdate(jdbc, MASTER_A, "{\"rows\":\"v1\"}", 0L));

        assertEquals(1L, storedVersion(), "the accepted write counted exactly once");
        assertEquals("{\"rows\":\"v1\"}", storedJson());
    }
}
