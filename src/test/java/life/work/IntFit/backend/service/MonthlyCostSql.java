package life.work.IntFit.backend.service;

import java.time.LocalDate;

/**
 * Test access to the monthly labor SQL.
 *
 * <p>H2 2.2 returns no rows when a parameterised CTE is referenced from another
 * parameterised CTE, so on H2 the tests run the production text with the
 * parameters written as literals, the same way Connector/J sends them to MySQL.
 */
public final class MonthlyCostSql {

    private MonthlyCostSql() {
    }

    public static String laborSumsWithLiterals(LocalDate from, LocalDate to) {
        return withLiterals(MonthlyCostService.LABOR_SUMS_SQL, from, to);
    }

    public static String withLiterals(String sql, LocalDate from, LocalDate to) {
        return sql
                .replace(":from", "DATE '" + from + "'")
                .replace(":to", "DATE '" + to + "'")
                .replace(":stdHours", "8.0")
                .replace(":otMultiplier", "1.0");
    }
}
