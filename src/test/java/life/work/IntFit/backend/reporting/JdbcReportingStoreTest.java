package life.work.IntFit.backend.reporting;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static life.work.IntFit.backend.reporting.ReportingModel.*;

/** SQL/transaction wiring checks with a stub JdbcTemplate. Opens NO JDBC connection. */
class JdbcReportingStoreTest {
    @Test void transactionCommitsOnlySuccessfulUnitAndRollsBackFailureWithoutDatabase() {
        StubJdbc jdbc=new StubJdbc();StubTransactions tx=new StubTransactions();JdbcReportingStore store=new JdbcReportingStore(jdbc,tx);
        assertEquals("done",store.transaction(() -> "done"));assertEquals(1,tx.commits);
        assertThrows(IllegalStateException.class,() -> store.transaction(() -> {throw new IllegalStateException("synthetic");}));assertEquals(1,tx.rollbacks);
    }
    @Test void mutationsAreNarrowAndRequestsGuardSubmittedRows() {
        StubJdbc jdbc=new StubJdbc();JdbcReportingStore store=new JdbcReportingStore(jdbc,new StubTransactions());
        store.configureWorker(7,true,"970599000007");assertEquals("UPDATE team_members SET whatsapp_reporting_eligible=?,whatsapp_number=? WHERE id=?",jdbc.sql);
        assertArrayEquals(new Object[]{true,"970599000007",7L},jdbc.args);
        Request r=new Request("id",7,"970599000007","123400007",LocalDate.of(2026,10,22),"SUBMITTED",3,null,"DID_NOT_WORK",Instant.EPOCH,Instant.EPOCH,Instant.EPOCH);
        store.saveRequest(r);assertTrue(jdbc.sql.endsWith("WHERE id=? AND submitted_at IS NULL"));assertFalse(jdbc.sql.contains("work_date="));
        assertEquals(Instant.EPOCH,((java.sql.Timestamp)jdbc.args[4]).toInstant());
        store.lockWorker(7);assertTrue(jdbc.sql.endsWith("FOR UPDATE"));store.request("id");assertTrue(jdbc.sql.endsWith("FOR UPDATE"));store.outbound("id");assertTrue(jdbc.sql.endsWith("FOR UPDATE"));
    }
    @Test void additiveScriptIsExplicitUnconfiguredAndSeparateFromAutomaticStartupSql() throws Exception {
        String sql=Files.readString(Path.of("docs/sql/001-whatsapp-reporting.sql"));
        assertEquals(5,sql.split("CREATE TABLE ",-1).length-1);assertTrue(sql.contains("UNIQUE (whatsapp_number)"));assertTrue(sql.contains("UNIQUE (worker_id,work_date)"));
        assertTrue(sql.contains("UNIQUE (request_id,revision)"));assertTrue(sql.contains("PRIMARY KEY (request_id,site_id,worker_id)"));
        assertTrue(sql.contains("DEFAULT FALSE"));assertFalse(sql.contains("INSERT INTO"));assertFalse(sql.contains("DROP TABLE"));
        assertFalse(Files.exists(Path.of("src/main/resources/schema.sql")));
    }
    static final class StubJdbc extends JdbcTemplate {
        String sql;Object[] args;
        @Override public int update(String s,Object... a){sql=s;args=a;return 1;}
        @Override public <T> List<T> query(String s,RowMapper<T> mapper,Object... a){sql=s;args=a;return List.of();}
    }
    static final class StubTransactions extends AbstractPlatformTransactionManager {
        int commits,rollbacks;
        @Override protected Object doGetTransaction(){return new Object();}
        @Override protected void doBegin(Object t,TransactionDefinition d){}
        @Override protected void doCommit(DefaultTransactionStatus s){commits++;}
        @Override protected void doRollback(DefaultTransactionStatus s){rollbacks++;}
    }
}
