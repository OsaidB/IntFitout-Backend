package life.work.IntFit.backend.service.impl;

import org.h2.api.Trigger;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Test-only H2 trigger: when overtime is inserted for {@link #poisonMemberId},
 * it records what the current transaction has already written and then fails,
 * simulating a database error in the middle of a daily save.
 */
public class FailingOvertimeTrigger implements Trigger {

    public static volatile long poisonMemberId = -1;
    public static volatile List<String> observedAssignments = List.of();
    public static volatile List<String> observedOvertime = List.of();

    private int memberColumn = -1;

    @Override
    public void init(Connection conn, String schemaName, String triggerName, String tableName,
                     boolean before, int type) throws SQLException {
        try (ResultSet columns = conn.getMetaData().getColumns(null, schemaName, tableName, null)) {
            while (columns.next()) {
                if ("TEAM_MEMBER_ID".equalsIgnoreCase(columns.getString("COLUMN_NAME"))) {
                    memberColumn = columns.getInt("ORDINAL_POSITION") - 1;
                }
            }
        }
        if (memberColumn < 0) {
            throw new SQLException("team_member_id column not found on " + tableName);
        }
    }

    @Override
    public void fire(Connection conn, Object[] oldRow, Object[] newRow) throws SQLException {
        if (newRow == null || ((Number) newRow[memberColumn]).longValue() != poisonMemberId) {
            return;
        }
        observedAssignments = query(conn, "select team_member_id, master_worksite_id, "
                + "regular_hours_override, overtime_hours_override from work_assignments "
                + "order by team_member_id, master_worksite_id");
        observedOvertime = query(conn, "select team_member_id, overtime_hours from work_assignment_overtime "
                + "order by team_member_id");
        throw new SQLException("forced failure after writes began");
    }

    private static List<String> query(Connection conn, String sql) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder row = new StringBuilder();
                for (int i = 1; i <= n; i++) {
                    Object value = rs.getObject(i);
                    if (value instanceof java.math.BigDecimal bd) {
                        value = bd.stripTrailingZeros().toPlainString();
                    }
                    row.append(i > 1 ? "|" : "").append(value);
                }
                rows.add(row.toString());
            }
        }
        return rows;
    }
}
