package life.work.IntFit.backend.closeout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import life.work.IntFit.backend.dto.CloseoutDraftDTO;
import life.work.IntFit.backend.model.entity.MasterWorksite;
import life.work.IntFit.backend.repository.CloseoutDraftRepository;
import life.work.IntFit.backend.repository.MasterWorksiteRepository;
import life.work.IntFit.backend.service.CloseoutDraftService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Closeout draft concurrency through the real application path, on a real MySQL server.
 *
 * This is the test that establishes the guarantee. Its companion,
 * {@link CloseoutDraftConcurrencyMySqlIntegrationTest}, only demonstrates that MySQL's own
 * conditional UPDATE and unique key behave as expected; it uses raw SQL and proves nothing
 * about our code. Here the write goes through {@code CloseoutDraftServiceImpl}, the mapped
 * {@code CloseoutDraft} entity with its {@code @Version} field, the Spring Data repository
 * and a real Spring transaction, which is where an optimistic-lock failure actually has to
 * be caught and turned into a conflict.
 *
 * Guarded: the datasource is built from the disposable-container runner's environment and the
 * class does not run otherwise.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "INTFITOUT_MYSQL_TEST_ENABLED", matches = "true")
class CloseoutDraftServiceMySqlIntegrationTest {

    /**
     * This suite lets Hibernate create the schema from the entity mappings, so it gets its
     * own throwaway schema. The raw-SQL suites create deliberately narrow fixture tables
     * under the same names in the other schema, and the two shapes must not collide.
     */
    private static final String DATABASE_ENV = "INTFITOUT_MYSQL_CLOSEOUT_DB";
    private static final String NEW_DRAFT = "-1";

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        String port = System.getenv("INTFITOUT_MYSQL_TEST_PORT");
        String user = System.getenv("INTFITOUT_MYSQL_TEST_USER");
        String password = System.getenv("INTFITOUT_MYSQL_TEST_PASSWORD");
        String database = System.getenv(DATABASE_ENV);

        // Refuse to build a context against anything but this run's throwaway container.
        if (port == null || !port.matches("[0-9]{1,5}")
                || user == null || !user.matches("rv_[a-f0-9]{12}")
                || password == null || !password.matches("[a-f0-9]{64}")
                || database == null || !database.equals("intfitout_closeout_validation")) {
            throw new IllegalStateException(
                    "Use the disposable-container runner; no default datasource is allowed");
        }

        registry.add("spring.datasource.url", () -> "jdbc:mysql://127.0.0.1:" + Integer.parseInt(port)
                + "/" + database + "?useSSL=false&allowPublicKeyRetrieval=true"
                + "&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.datasource.username", () -> user);
        registry.add("spring.datasource.password", () -> password);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.MySQLDialect");
        registry.add("spring.jpa.show-sql", () -> "false");
    }

    @Autowired private CloseoutDraftService service;
    @Autowired private CloseoutDraftRepository drafts;
    @Autowired private MasterWorksiteRepository masters;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private EntityManagerFactory emf;

    private Long masterId;

    private JsonNode body(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeEach
    void seedMaster() {
        // ddl-auto=update created the schema from the entities, so the version column is
        // whatever the mapping says it is - which is part of what this test checks.
        MasterWorksite m = new MasterWorksite();
        // approvedName is unique in the schema, so each run gets its own fixture.
        m.setApprovedName("CONCURRENCY-FIXTURE-" + System.nanoTime());
        masterId = masters.save(m).getId();
    }

    @AfterEach
    void cleanUp() {
        if (masterId != null) {
            jdbc.update("DELETE FROM closeout_drafts WHERE master_worksite_id = ?", masterId);
            jdbc.update("DELETE FROM master_worksites WHERE id = ?", masterId);
        }
    }

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

    private long draftRowCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM closeout_drafts WHERE master_worksite_id = ?", Long.class, masterId);
    }

    private String storedJson() {
        return jdbc.queryForObject(
                "SELECT draft_json FROM closeout_drafts WHERE master_worksite_id = ?", String.class, masterId);
    }

    private long storedVersion() {
        return jdbc.queryForObject(
                "SELECT version FROM closeout_drafts WHERE master_worksite_id = ?", Long.class, masterId);
    }

    // ----------------------------------------------------------------- mapping

    @Test
    @DisplayName("the mapped entity really carries a version column that the database increments")
    void versionColumnIsMappedAndIncremented() {
        CloseoutDraftDTO first = service.upsertDraft(masterId, body("{\"types\":[]}"), NEW_DRAFT);
        assertEquals(0L, first.getVersion(), "a new draft starts at revision 0");
        assertEquals(0L, storedVersion());

        CloseoutDraftDTO second = service.upsertDraft(masterId, body("{\"types\":[1]}"), first.getRevision());
        assertEquals(1L, second.getVersion(), "an accepted save advances the revision");
        assertEquals(1L, storedVersion());
    }

    // ----------------------------------------------------------------- creation race

    @Test
    @DisplayName("two concurrent first saves through the service produce one draft")
    void concurrentFirstCreateThroughService() throws Exception {
        List<Callable<Object>> jobs = List.of(
                () -> service.upsertDraft(masterId, body("{\"device\":\"A\"}"), NEW_DRAFT),
                () -> service.upsertDraft(masterId, body("{\"device\":\"B\"}"), NEW_DRAFT));

        List<Object> results = race(jobs);

        assertEquals(1L, draftRowCount(), "the unique key must admit exactly one draft");

        long ok = results.stream().filter(r -> r instanceof CloseoutDraftDTO).count();
        long refused = results.stream()
                .filter(r -> r instanceof CloseoutDraftService.DraftConflictException).count();
        assertEquals(1L, ok, "exactly one creator succeeds");
        assertEquals(1L, refused,
                "the other must get a conflict, not a raw database exception: " + results);

        String json = storedJson();
        assertTrue(json.contains("\"A\"") || json.contains("\"B\""),
                "one device's draft stands whole: " + json);
    }

    // ----------------------------------------------------------------- update race

    @Test
    @DisplayName("two concurrent updates on the same revision: one wins, one gets a conflict")
    void concurrentUpdateThroughService() throws Exception {
        String base = service.upsertDraft(masterId, body("{\"rows\":\"original\"}"), NEW_DRAFT).getRevision();

        List<Callable<Object>> jobs = List.of(
                () -> service.upsertDraft(masterId, body("{\"rows\":\"deviceA\"}"), base),
                () -> service.upsertDraft(masterId, body("{\"rows\":\"deviceB\"}"), base));

        List<Object> results = race(jobs);

        long ok = results.stream().filter(r -> r instanceof CloseoutDraftDTO).count();
        long refused = results.stream()
                .filter(r -> r instanceof CloseoutDraftService.DraftConflictException).count();

        assertEquals(1L, ok, "exactly one write lands: " + results);
        assertEquals(1L, refused,
                "the loser must surface as a conflict, however the failure was raised: " + results);
        assertEquals(1L, storedVersion(), "the revision advanced exactly once");

        String json = storedJson();
        assertTrue(json.contains("deviceA") || json.contains("deviceB"),
                "the survivor is one device's complete draft, never a blend: " + json);
    }

    @Test
    @DisplayName("the conflict carries the stored draft so the client can show it")
    void conflictCarriesCurrentDraft() {
        String stale = service.upsertDraft(masterId, body("{\"rows\":\"theirs\"}"), NEW_DRAFT).getRevision();
        service.upsertDraft(masterId, body("{\"rows\":\"newer\"}"), stale);

        CloseoutDraftService.DraftConflictException e = assertThrows(
                CloseoutDraftService.DraftConflictException.class,
                () -> service.upsertDraft(masterId, body("{\"rows\":\"stale\"}"), stale));

        assertNotNull(e.current, "the refusal must carry what is stored");
        assertEquals(1L, e.current.getVersion());
        assertTrue(e.current.getDraft().toString().contains("newer"));
        assertTrue(storedJson().contains("newer"), "the stored draft is untouched");
    }

    /**
     * The case the raw-SQL test cannot reach: a writer whose entity was loaded before another
     * transaction committed. The version it holds is current at load time, so the service's
     * own pre-check passes and the failure can only be raised by Hibernate when the UPDATE
     * runs. It must still come out as a conflict.
     */
    @Test
    @DisplayName("an optimistic-lock failure raised at flush still becomes a conflict")
    void lockFailureAtFlushBecomesConflict() throws Exception {
        String base = service.upsertDraft(masterId, body("{\"rows\":\"v0\"}"), NEW_DRAFT).getRevision();

        // Another device advances the row behind the service's back, exactly as a
        // concurrent transaction would, after the caller read this revision.
        jdbc.update("UPDATE closeout_drafts SET draft_json = ?, version = version + 1"
                + " WHERE master_worksite_id = ?", "{\"rows\":\"other-device\"}", masterId);

        CloseoutDraftService.DraftConflictException e = assertThrows(
                CloseoutDraftService.DraftConflictException.class,
                () -> service.upsertDraft(masterId, body("{\"rows\":\"mine\"}"), base));

        assertTrue(storedJson().contains("other-device"),
                "the other device's work must survive: " + storedJson());
        assertEquals(1L, storedVersion());
        assertNotNull(e.current);
    }

    @Test
    @DisplayName("a caller sending no revision is refused and writes nothing")
    void missingRevisionWritesNothing() {
        service.upsertDraft(masterId, body("{\"rows\":\"v0\"}"), NEW_DRAFT);

        assertThrows(CloseoutDraftService.DraftVersionRequiredException.class,
                () -> service.upsertDraft(masterId, body("{\"rows\":\"unversioned\"}"), null));

        assertTrue(storedJson().contains("v0"), "nothing may be written without a revision");
        assertEquals(0L, storedVersion());
    }

    // ----------------------------------------------------------------- delete

    @Test
    @DisplayName("deleting a draft requires the current revision")
    void deleteRequiresCurrentRevision() {
        String stale = service.upsertDraft(masterId, body("{\"rows\":\"v0\"}"), NEW_DRAFT).getRevision();
        String current = service.upsertDraft(masterId, body("{\"rows\":\"v1\"}"), stale).getRevision();

        // A device holding a stale revision must not be able to wipe newer work.
        assertThrows(CloseoutDraftService.DraftConflictException.class,
                () -> service.deleteDraft(masterId, stale));
        assertEquals(1L, draftRowCount(), "the draft must survive a stale delete");

        service.deleteDraft(masterId, current);
        assertEquals(0L, draftRowCount(), "a delete on the current revision succeeds");
    }

    @Test
    @DisplayName("concurrent deletes: one removes the draft, the other is refused")
    void concurrentDelete() throws Exception {
        String base = service.upsertDraft(masterId, body("{\"rows\":\"v0\"}"), NEW_DRAFT).getRevision();

        List<Callable<Object>> jobs = List.of(
                () -> { service.deleteDraft(masterId, base); return "deleted"; },
                () -> { service.deleteDraft(masterId, base); return "deleted"; });

        List<Object> results = race(jobs);

        assertEquals(0L, draftRowCount());
        long ok = results.stream().filter("deleted"::equals).count();
        assertEquals(1L, ok, "only one delete may report success: " + results);
    }

    @Test
    @DisplayName("deleting and recreating a draft invalidates the old revision")
    void recreatedDraftRejectsOldRevision() {
        String oldRevision = service.upsertDraft(masterId, body("{\"rows\":\"original\"}"), NEW_DRAFT)
                .getRevision();

        service.deleteDraft(masterId, oldRevision);
        CloseoutDraftDTO recreated = service.upsertDraft(masterId, body("{\"rows\":\"replacement\"}"), NEW_DRAFT);

        // The replacement row starts its counter at 0 again, so the counter alone would
        // make the old revision look current. The row identity is what refuses it.
        assertEquals(0L, recreated.getVersion(), "a new row restarts the counter");
        org.junit.jupiter.api.Assertions.assertNotEquals(oldRevision, recreated.getRevision(),
                "the token must differ even though the counter matches");

        assertThrows(CloseoutDraftService.DraftConflictException.class,
                () -> service.upsertDraft(masterId, body("{\"rows\":\"stale\"}"), oldRevision));

        assertTrue(storedJson().contains("replacement"),
                "the replacement draft must survive: " + storedJson());
    }

    // ----------------------------------------------------------------- numbering

    private long storedMark() {
        return jdbc.queryForObject(
                "SELECT next_measurement_seq FROM closeout_drafts WHERE master_worksite_id = ?",
                Long.class, masterId);
    }

    private static String draftWithRows(String... rowJson) {
        return "{\"types\":[{\"id\":\"t1\",\"name\":\"Ceiling\",\"unit\":\"m²\","
                + "\"category\":\"gypsum\",\"unitPrice\":\"10\",\"rows\":["
                + String.join(",", rowJson) + "]}]}";
    }

    private java.util.List<Long> seqsOf(CloseoutDraftDTO dto) {
        java.util.List<Long> out = new ArrayList<>();
        for (JsonNode t : dto.getDraft().get("types")) {
            for (JsonNode r : t.get("rows")) {
                out.add(r.hasNonNull("seq") ? r.get("seq").asLong() : null);
            }
        }
        return out;
    }

    @Test
    @DisplayName("the server allocates measurement numbers and persists the high-water mark")
    void allocatesAndPersistsTheMark() {
        CloseoutDraftDTO first = service.upsertDraft(masterId,
                body(draftWithRows("{\"id\":\"a\"}", "{\"id\":\"b\"}")), NEW_DRAFT);

        assertEquals(java.util.List.of(1L, 2L), seqsOf(first));
        assertEquals(3L, storedMark(), "the mark is kept in its own column, not in the blob");
    }

    @Test
    @DisplayName("two devices adding a row concurrently cannot be given the same number")
    void concurrentAllocationCannotCollide() throws Exception {
        CloseoutDraftDTO base = service.upsertDraft(masterId,
                body(draftWithRows("{\"id\":\"a\"}")), NEW_DRAFT);
        String rev = base.getRevision();
        assertEquals(2L, storedMark());

        // Both devices add their own new row, from the same revision.
        List<Callable<Object>> jobs = List.of(
                () -> service.upsertDraft(masterId,
                        body(draftWithRows("{\"id\":\"a\",\"seq\":1}", "{\"id\":\"deviceA\"}")), rev),
                () -> service.upsertDraft(masterId,
                        body(draftWithRows("{\"id\":\"a\",\"seq\":1}", "{\"id\":\"deviceB\"}")), rev));

        List<Object> results = race(jobs);

        long accepted = results.stream().filter(r -> r instanceof CloseoutDraftDTO).count();
        assertEquals(1L, accepted, "the revision check still admits exactly one: " + results);

        // Exactly one allocation happened, so the mark advanced by exactly one.
        assertEquals(3L, storedMark(), "a refused save must not consume a number");

        CloseoutDraftDTO winner = (CloseoutDraftDTO) results.stream()
                .filter(r -> r instanceof CloseoutDraftDTO).findFirst().orElseThrow();
        assertEquals(java.util.List.of(1L, 2L), seqsOf(winner));

        // The loser retries from the new revision. Its row must get 3, never the 2 the
        // winner's row already holds.
        String winnerRevision = winner.getRevision();
        CloseoutDraftDTO retried = service.upsertDraft(masterId,
                body(draftWithRows("{\"id\":\"a\",\"seq\":1}", "{\"id\":\"winner\",\"seq\":2}",
                        "{\"id\":\"loser\"}")), winnerRevision);

        assertEquals(java.util.List.of(1L, 2L, 3L), seqsOf(retried));
        assertEquals(4L, storedMark());
    }

    @Test
    @DisplayName("a number retired by a deletion is never handed out again")
    void deletedNumbersAreNotRecycled() {
        CloseoutDraftDTO first = service.upsertDraft(masterId,
                body(draftWithRows("{\"id\":\"a\"}", "{\"id\":\"b\"}")), NEW_DRAFT);

        // Delete b (number 2), then add a replacement.
        CloseoutDraftDTO second = service.upsertDraft(masterId,
                body(draftWithRows("{\"id\":\"a\",\"seq\":1}", "{\"id\":\"c\"}")),
                first.getRevision());

        assertEquals(java.util.List.of(1L, 3L), seqsOf(second), "2 is retired for good");
        assertEquals(4L, storedMark());
    }

    @Test
    @DisplayName("an older client that drops measurement numbers is refused, and nothing is written")
    void strippedMetadataIsRefused() {
        CloseoutDraftDTO first = service.upsertDraft(masterId,
                body(draftWithRows("{\"id\":\"a\"}", "{\"id\":\"b\"}")), NEW_DRAFT);
        String rev = first.getRevision();

        // A valid revision, but the rows come back with their numbers erased. Revision
        // checking cannot catch this, which is the whole point of the separate rule.
        assertThrows(CloseoutDraftService.DraftMetadataRequiredException.class,
                () -> service.upsertDraft(masterId,
                        body(draftWithRows("{\"id\":\"a\"}", "{\"id\":\"b\"}")), rev));

        // Untouched: same revision, same numbers, same mark.
        CloseoutDraftDTO after = service.getDraft(masterId).orElseThrow();
        assertEquals(rev, after.getRevision(), "a refused save must not advance the revision");
        assertEquals(java.util.List.of(1L, 2L), seqsOf(after));
        assertEquals(3L, storedMark());
    }

    @Test
    @DisplayName("replacing a draft wholesale cannot reuse numbers or change existing ones")
    void conflictResolutionPreservesNumbers() {
        CloseoutDraftDTO first = service.upsertDraft(masterId,
                body(draftWithRows("{\"id\":\"a\"}", "{\"id\":\"b\"}", "{\"id\":\"c\"}")), NEW_DRAFT);
        assertEquals(java.util.List.of(1L, 2L, 3L), seqsOf(first));

        // A "keep mine" resolution pushes a device's own draft over the stored one. It holds
        // only two of the rows, with the numbers it was given, plus one new row.
        CloseoutDraftDTO kept = service.upsertDraft(masterId,
                body(draftWithRows("{\"id\":\"a\",\"seq\":1}", "{\"id\":\"c\",\"seq\":3}",
                        "{\"id\":\"mine\"}")),
                first.getRevision());

        // Existing rows keep their numbers; the new row gets 4, not the retired 2.
        assertEquals(java.util.List.of(1L, 3L, 4L), seqsOf(kept));
        assertEquals(5L, storedMark());
    }

    @Test
    @DisplayName("the floor field survives a real round trip and is never invented")
    void floorRoundTrip() {
        CloseoutDraftDTO saved = service.upsertDraft(masterId,
                body(draftWithRows("{\"id\":\"a\",\"floor\":\"2\"}", "{\"id\":\"b\"}")), NEW_DRAFT);

        JsonNode rows = saved.getDraft().get("types").get(0).get("rows");
        assertEquals("2", rows.get(0).get("floor").asText());
        assertTrue(!rows.get(1).has("floor") || rows.get(1).get("floor").asText().isEmpty(),
                "a measurement with no floor must not acquire one");
    }

    // ----------------------------------------------------------------- round trip

    @Test
    @DisplayName("the draft JSON survives a real round trip through MySQL unchanged")
    void jsonRoundTripIsVerbatim() {
        String json = "{\"types\":[{\"id\":\"t1\",\"name\":\"\\u0633\\u0642\\u0641\","
                + "\"rows\":[{\"id\":\"r1\",\"seq\":12,\"floor\":\"2\",\"room\":\"Hall\"}]}]}";

        service.upsertDraft(masterId, body(json), NEW_DRAFT);
        CloseoutDraftDTO read = service.getDraft(masterId).orElseThrow();

        String round = read.getDraft().toString();
        assertTrue(round.contains("\"seq\":12"), "numbering metadata must survive: " + round);
        assertTrue(round.contains("\"floor\":\"2\""), "floor metadata must survive: " + round);
        // Arabic type names go through LONGTEXT and the connector unharmed.
        assertTrue(round.contains("سقف"), "Arabic text must survive: " + round);
    }
}
