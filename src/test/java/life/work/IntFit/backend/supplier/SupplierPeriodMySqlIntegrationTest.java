package life.work.IntFit.backend.supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The period aggregates against real MySQL.
 *
 * H2 in MySQL mode is close but not identical: DATETIME comparison at the boundary,
 * DECIMAL summation of negatives, correlated subqueries and DISTINCT over a join can all
 * differ. These are the queries that decide what a week is worth, so they are exercised on
 * the engine that will actually run them.
 *
 * Runs only inside the guarded disposable-container runner.
 */
@EnabledIfEnvironmentVariable(named = "INTFITOUT_MYSQL_TEST_ENABLED", matches = "true")
class SupplierPeriodMySqlIntegrationTest {

    private static final String DATABASE = "intfitout_reporting_validation";

    private static final LocalDate WEEK_START = LocalDate.of(2026, 10, 24); // Saturday
    private static final LocalDate WEEK_END = LocalDate.of(2026, 10, 30);   // Friday
    private static final long SITE_A = 801L, SITE_B = 802L;

    private JdbcTemplate jdbc;
    private SupplierAggregateDao dao;

    private static BigDecimal n(String v) { return new BigDecimal(v); }

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

        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setUrl("jdbc:mysql://127.0.0.1:" + Integer.parseInt(port) + "/" + DATABASE
                + "?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC"
                + "&forceConnectionTimeZoneToSession=true&connectTimeout=3000&socketTimeout=10000");
        ds.setUsername(user);
        ds.setPassword(password);
        jdbc = new JdbcTemplate(ds);
        jdbc.setQueryTimeout(10);

        assertEquals(DATABASE, jdbc.queryForObject("SELECT DATABASE()", String.class));
        assertEquals(List.of(runId),
                jdbc.queryForList("SELECT run_id FROM intfitout_validation_guard", String.class),
                "Refusing fixture DDL without this fresh run's guard");
        assertEquals("9.7.2", jdbc.queryForObject("SELECT VERSION()", String.class));

        dao = new SupplierAggregateDao(jdbc);
        schema();
        seed();
    }

    /**
     * Creates only the tables this feature owns, and leaves everything the reporting suite
     * shares alone: master_worksites already exists and is referenced by foreign keys, so
     * dropping it would break the other suite running against the same database. Rows are
     * removed by id instead, within a range no other fixture uses.
     */
    private void schema() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS worksites ("
                + "id BIGINT PRIMARY KEY, name VARCHAR(255), master_worksite_id BIGINT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS invoice ("
                + "id BIGINT PRIMARY KEY, worksite_id BIGINT NULL, total DOUBLE NULL, "
                + "net_total DOUBLE NULL, date DATETIME(6) NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS ar_payments ("
                + "id BIGINT PRIMARY KEY, master_worksite_id BIGINT NULL, amount DECIMAL(19,2) NOT NULL)");
        // Mirrors the entity's NOT NULL columns so this definition agrees with the cheque
        // suite's, whichever test class creates the table first.
        jdbc.execute("CREATE TABLE IF NOT EXISTS supplier_payment ("
                + "id BIGINT PRIMARY KEY, supplier_name VARCHAR(120) NOT NULL, "
                + "amount DECIMAL(14,2) NOT NULL, payment_date DATE NOT NULL, "
                + "method VARCHAR(20) NOT NULL, reversed_at DATETIME(6) NULL, "
                + "created_at DATETIME(6) NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS supplier_payment_allocation ("
                + "id BIGINT PRIMARY KEY, payment_id BIGINT NOT NULL, "
                + "master_worksite_id BIGINT NOT NULL, amount DECIMAL(14,2) NOT NULL)");

        // The production schema carries a nickname; the reduced reporting baseline does not.
        Integer hasNickname = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'master_worksites' "
                        + "AND column_name = 'nickname'", Integer.class);
        if (hasNickname == null || hasNickname == 0) {
            jdbc.execute("ALTER TABLE master_worksites ADD COLUMN nickname VARCHAR(255) NULL");
        }

        // Only this fixture's own rows are cleared.
        jdbc.update("DELETE FROM supplier_payment_allocation");
        jdbc.update("DELETE FROM supplier_payment");
        jdbc.update("DELETE FROM invoice");
        jdbc.update("DELETE FROM worksites WHERE id IN (8010,8011,8020,8099)");
        jdbc.update("DELETE FROM master_worksites WHERE id IN (?,?)", SITE_A, SITE_B);
    }

    private void seed() {
        jdbc.update("INSERT INTO master_worksites(id, approved_name, nickname, notes) VALUES (?,?,?,?)",
                SITE_A, "Period Site A", "alpha", "alt spelling");
        jdbc.update("INSERT INTO master_worksites(id, approved_name, nickname, notes) VALUES (?,?,?,?)",
                SITE_B, "Period Site B", null, null);
        jdbc.update("INSERT INTO worksites(id, name, master_worksite_id) VALUES (?,?,?)", 8010L, "A sub", SITE_A);
        jdbc.update("INSERT INTO worksites(id, name, master_worksite_id) VALUES (?,?,?)", 8011L, "A second sub", SITE_A);
        jdbc.update("INSERT INTO worksites(id, name, master_worksite_id) VALUES (?,?,?)", 8020L, "B sub", SITE_B);
        jdbc.update("INSERT INTO worksites(id, name, master_worksite_id) VALUES (?,?,NULL)", 8099L, "orphan");
    }


    private void invoice(long id, long worksiteId, String total, String ts) {
        jdbc.update("INSERT INTO invoice(id, worksite_id, total, net_total, date) VALUES (?,?,?,?,?)", id, worksiteId,
                Double.valueOf(total), Double.valueOf(total), java.sql.Timestamp.valueOf(ts));
    }

    private void payment(long id, String amount, String date, boolean reversed) {
        // Every NOT NULL column the entity declares is supplied by name. The cheque suite
        // shares this database and may create the table first with its full shape, so an
        // insert that named only the columns these queries read would be rejected.
        jdbc.update("INSERT INTO supplier_payment"
                        + "(id, supplier_name, amount, payment_date, method, reversed_at, created_at) "
                        + "VALUES (?,?,?,?,?,?,?)",
                id, "AL-Eatimad", n(amount), java.sql.Date.valueOf(date), "CASH",
                reversed ? java.sql.Timestamp.valueOf("2026-10-27 10:00:00") : null,
                java.sql.Timestamp.valueOf("2026-10-26 08:00:00"));
    }

    private void alloc(long id, long paymentId, long master, String amount) {
        jdbc.update("INSERT INTO supplier_payment_allocation"
                + "(id, payment_id, master_worksite_id, amount) VALUES (?,?,?,?)",
                id, paymentId, master, n(amount));
    }

    // =================================================================

    @Test void supplyTotalsSumAcrossSubWorksitesAndNetAppliedReturns() {
        invoice(1, 8010L, "5000", "2026-10-26 09:00:00");
        invoice(2, 8011L, "2000", "2026-10-27 09:00:00");   // a second sub of the same master
        invoice(3, 8010L, "-1500", "2026-10-28 09:00:00");  // an applied return

        Map<Long, BigDecimal> sums = dao.suppliesByMasterBetween(WEEK_START, WEEK_END);
        assertEquals(0, sums.get(SITE_A).compareTo(n("5500.00")),
                "both sub-worksites roll up and the return is netted");
    }

    @Test void aPeriodTotalMayBeNegativeOnRealMySql() {
        invoice(1, 8010L, "-900", "2026-10-26 09:00:00");
        assertEquals(0, dao.suppliesByMasterBetween(WEEK_START, WEEK_END).get(SITE_A).compareTo(n("-900.00")),
                "DECIMAL summation keeps the sign");
    }

    @Test void midnightBoundariesAreHalfOpen() {
        invoice(1, 8010L, "100", "2026-10-24 00:00:00.000000");  // first instant inside
        invoice(2, 8010L, "200", "2026-10-30 23:59:59.999999");  // last instant inside
        invoice(3, 8010L, "400", "2026-10-23 23:59:59.999999");  // just before
        invoice(4, 8010L, "800", "2026-10-31 00:00:00.000000");  // just after

        assertEquals(0, dao.suppliesByMasterBetween(WEEK_START, WEEK_END).get(SITE_A).compareTo(n("300.00")),
                "only the two inside the Saturday-to-Friday window count");
    }

    @Test void supplyActivityCountsRowsNotNetTotals() {
        invoice(1, 8010L, "1500", "2026-10-26 09:00:00");
        invoice(2, 8010L, "-1500", "2026-10-27 09:00:00");

        Set<Long> active = dao.mastersWithSupplyRecordsBetween(WEEK_START, WEEK_END);
        assertTrue(active.contains(SITE_A), "a zero net is still a week with records");
        assertEquals(0, dao.suppliesByMasterBetween(WEEK_START, WEEK_END).get(SITE_A).compareTo(n("0.00")));
    }

    @Test void splitPaymentGivesSharesPerProjectAndIsCountedOnceOverall() {
        payment(1, "10000", "2026-10-26", false);
        alloc(1, 1, SITE_A, "6000");
        alloc(2, 1, SITE_B, "3000");   // 1,000 left unassigned

        Map<Long, BigDecimal> shares = dao.allocatedByMasterBetween(WEEK_START, WEEK_END);
        assertEquals(0, shares.get(SITE_A).compareTo(n("6000.00")));
        assertEquals(0, shares.get(SITE_B).compareTo(n("3000.00")));

        assertEquals(0, dao.actualPaidBetween(WEEK_START, WEEK_END).compareTo(n("10000.00")),
                "the payment is counted once at its full amount");
        assertEquals(0, dao.unallocatedPaidBetween(WEEK_START, WEEK_END).compareTo(n("1000.00")));
    }

    @Test void reversedPaymentsAreExcludedFromEveryPeriodFigure() {
        payment(1, "4000", "2026-10-26", false);
        alloc(1, 1, SITE_A, "4000");
        payment(2, "9000", "2026-10-26", true);    // reversed
        alloc(2, 2, SITE_A, "9000");

        assertEquals(0, dao.allocatedByMasterBetween(WEEK_START, WEEK_END).get(SITE_A).compareTo(n("4000.00")));
        assertEquals(0, dao.actualPaidBetween(WEEK_START, WEEK_END).compareTo(n("4000.00")));
        assertEquals(0, dao.unallocatedPaidBetween(WEEK_START, WEEK_END).compareTo(n("0.00")));
        assertEquals(Set.of(SITE_A), dao.mastersWithPaymentRecordsBetween(WEEK_START, WEEK_END));
    }

    @Test void paymentDatesBoundTheWeekInclusively() {
        payment(1, "100", "2026-10-24", false);   // Saturday, first day
        payment(2, "200", "2026-10-30", false);   // Friday, last day
        payment(3, "400", "2026-10-23", false);   // the day before
        payment(4, "800", "2026-10-31", false);   // the day after

        assertEquals(0, dao.actualPaidBetween(WEEK_START, WEEK_END).compareTo(n("300.00")));
    }

    @Test void unassignedSuppliesArePeriodScopedAndSeparateFromProjects() {
        invoice(1, 8099L, "400", "2026-10-26 09:00:00");   // worksite with no master
        invoice(2, 8099L, "900", "2026-05-02 09:00:00");   // outside the period
        invoice(3, 8010L, "5000", "2026-10-26 09:00:00");  // a real project

        assertEquals(0, dao.unassignedSuppliesBetween(WEEK_START, WEEK_END).compareTo(n("400.00")));
        assertFalse(dao.suppliesByMasterBetween(WEEK_START, WEEK_END).containsKey(null),
                "orphan supplies never appear under a master");
        assertEquals(0, dao.suppliesByMasterBetween(WEEK_START, WEEK_END).get(SITE_A).compareTo(n("5000.00")));
    }

    @Test void invoicesWithNoWorksiteAtAllCountAsUnassigned() {
        jdbc.update("INSERT INTO invoice(id, worksite_id, total, net_total, date) VALUES (9, NULL, 250, 250, ?)",
                java.sql.Timestamp.valueOf("2026-10-26 09:00:00"));
        assertEquals(0, dao.unassignedSuppliesBetween(WEEK_START, WEEK_END).compareTo(n("250.00")));
    }

    @Test void existingMasterIdsAnswersCorrectlyForAMixedList() {
        assertEquals(Set.of(SITE_A, SITE_B), dao.existingMasterIds(List.of(SITE_A, SITE_B, 999999L)));
        assertTrue(dao.existingMasterIds(List.of()).isEmpty());
    }

    @Test void searchableTextCarriesNicknameAndNotes() {
        Map<Long, String[]> text = dao.searchableMasterText();
        assertEquals("Period Site A", text.get(SITE_A)[0]);
        assertEquals("alpha", text.get(SITE_A)[1]);
        assertEquals("alt spelling", text.get(SITE_A)[2]);
        assertEquals("", text.get(SITE_B)[1], "a null nickname reads as empty, never as the literal null");
    }

    @Test void netTotalIsUsedWhenTotalIsMissing() {
        jdbc.update("INSERT INTO invoice(id, worksite_id, total, net_total, date) VALUES (1, ?, NULL, 700, ?)", 8010L,
                java.sql.Timestamp.valueOf("2026-10-26 09:00:00"));
        assertEquals(0, dao.suppliesByMasterBetween(WEEK_START, WEEK_END).get(SITE_A).compareTo(n("700.00")));
    }
}
