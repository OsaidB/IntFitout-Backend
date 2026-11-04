package life.work.IntFit.backend.closeout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Allocation of permanent measurement numbers.
 *
 * The rules under test: a number is never reassigned, never reused after a deletion, never
 * duplicated within a draft, and never silently erased by a client that does not understand
 * the field.
 */
class MeasurementNumberingTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode draft(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Builds a draft of one type from "id:seq" specifications; omit the seq for none. */
    private JsonNode rows(String... specs) {
        StringBuilder sb = new StringBuilder("{\"types\":[{\"id\":\"t1\",\"rows\":[");
        for (int i = 0; i < specs.length; i++) {
            String[] parts = specs[i].split(":");
            if (i > 0) sb.append(',');
            sb.append("{\"id\":\"").append(parts[0]).append('"');
            if (parts.length > 1 && !parts[1].isBlank()) {
                sb.append(",\"seq\":").append(parts[1]);
            }
            sb.append('}');
        }
        return draft(sb.append("]}]}").toString());
    }

    private List<Long> seqsOf(JsonNode d) {
        List<Long> out = new ArrayList<>();
        for (JsonNode t : d.get("types")) {
            for (JsonNode r : t.get("rows")) {
                out.add(r.hasNonNull("seq") ? r.get("seq").asLong() : null);
            }
        }
        return out;
    }

    // ----------------------------------------------------------------- first allocation

    @Test
    @DisplayName("a brand-new draft numbers its measurements from 1, in document order")
    void numbersFromOne() {
        MeasurementNumbering.Result r = MeasurementNumbering.allocate(
                rows("a", "b", "c"), null, 1L, false);

        assertEquals(List.of(1L, 2L, 3L), seqsOf(r.draft));
        assertEquals(4L, r.nextSeq);
        assertTrue(r.strippedRowIds.isEmpty());
    }

    @Test
    @DisplayName("legacy rows are numbered deterministically: the same draft, the same result")
    void legacyAssignmentIsDeterministic() {
        // The order is type order then row order, which is a reconstruction rather than
        // proven chronology - but it is stable, which is what matters for a reference.
        List<Long> first = seqsOf(MeasurementNumbering.allocate(rows("x", "y", "z"), null, 1L, false).draft);
        List<Long> again = seqsOf(MeasurementNumbering.allocate(rows("x", "y", "z"), null, 1L, false).draft);

        assertEquals(first, again);
        assertEquals(List.of(1L, 2L, 3L), first);
    }

    @Test
    @DisplayName("numbers span types, so two types never share a number")
    void numbersAreUniqueAcrossTypes() {
        JsonNode two = draft("{\"types\":["
                + "{\"id\":\"t1\",\"rows\":[{\"id\":\"a\"},{\"id\":\"b\"}]},"
                + "{\"id\":\"t2\",\"rows\":[{\"id\":\"c\"}]}]}");

        MeasurementNumbering.Result r = MeasurementNumbering.allocate(two, null, 1L, false);

        assertEquals(List.of(1L, 2L, 3L), seqsOf(r.draft));
    }

    // ----------------------------------------------------------------- stability

    @Test
    @DisplayName("an existing number is never changed by a later save")
    void existingNumbersAreKept() {
        JsonNode stored = rows("a:1", "b:2");
        MeasurementNumbering.Result r = MeasurementNumbering.allocate(
                rows("a:1", "b:2", "c"), stored, 3L, false);

        assertEquals(List.of(1L, 2L, 3L), seqsOf(r.draft));
    }

    @Test
    @DisplayName("deleting a row does not renumber the others, and its number is not reused")
    void deletionNeitherRenumbersNorRecycles() {
        JsonNode stored = rows("a:1", "b:2", "c:3");

        // b is gone, and a new row is added.
        MeasurementNumbering.Result r = MeasurementNumbering.allocate(
                rows("a:1", "c:3", "d"), stored, 4L, false);

        assertEquals(List.of(1L, 3L, 4L), seqsOf(r.draft));
        assertEquals(5L, r.nextSeq, "the mark keeps rising; 2 is retired for good");
    }

    @Test
    @DisplayName("reordering rows does not renumber them")
    void reorderingKeepsNumbers() {
        JsonNode stored = rows("a:1", "b:2", "c:3");
        MeasurementNumbering.Result r = MeasurementNumbering.allocate(
                rows("c:3", "a:1", "b:2"), stored, 4L, false);

        assertEquals(List.of(3L, 1L, 2L), seqsOf(r.draft));
    }

    @Test
    @DisplayName("a duplicated row gets its own number rather than sharing one")
    void duplicateGetsANewNumber() {
        JsonNode stored = rows("a:1");
        // A client-side duplicate copies the row, so the same number arrives twice.
        MeasurementNumbering.Result r = MeasurementNumbering.allocate(
                rows("a:1", "a-copy:1"), stored, 2L, false);

        List<Long> seqs = seqsOf(r.draft);
        assertEquals(1L, seqs.get(0), "the original keeps its number");
        assertEquals(2L, seqs.get(1), "the copy is given a fresh one");
    }

    @Test
    @DisplayName("the mark never falls, even if the stored draft is empty")
    void markNeverFalls() {
        MeasurementNumbering.Result r = MeasurementNumbering.allocate(
                rows("a"), draft("{\"types\":[]}"), 97L, false);

        assertEquals(List.of(97L), seqsOf(r.draft));
        assertEquals(98L, r.nextSeq);
    }

    @Test
    @DisplayName("a number arriving above the mark pushes the mark past it")
    void incomingNumbersRaiseTheMark() {
        // After a "keep mine" resolution, the client's rows may be the only record of
        // numbers the server allocated before the draft was replaced.
        MeasurementNumbering.Result r = MeasurementNumbering.allocate(
                rows("a:40", "b:41", "c"), null, 1L, false);

        assertEquals(List.of(40L, 41L, 42L), seqsOf(r.draft));
        assertEquals(43L, r.nextSeq);
    }

    // ----------------------------------------------------------------- older clients

    @Test
    @DisplayName("a save that drops numbers from numbered rows is reported, not accepted")
    void strippedMetadataIsReported() {
        JsonNode stored = rows("a:1", "b:2");

        // The released app round-trips the draft without fields it does not know.
        MeasurementNumbering.Result r = MeasurementNumbering.allocate(rows("a", "b"), stored, 3L, false);

        assertEquals(List.of("a", "b"), r.strippedRowIds);
        // The numbers are restored in the returned draft, so nothing is lost even if a
        // caller ignored the report.
        assertEquals(List.of(1L, 2L), seqsOf(r.draft));
    }

    @Test
    @DisplayName("a new row without a number is normal and not reported as stripped")
    void newRowsAreNotStripped() {
        JsonNode stored = rows("a:1");
        MeasurementNumbering.Result r = MeasurementNumbering.allocate(rows("a:1", "new"), stored, 2L, false);

        assertTrue(r.strippedRowIds.isEmpty(), "a genuinely new row has no number yet");
        assertEquals(List.of(1L, 2L), seqsOf(r.draft));
    }

    @Test
    @DisplayName("the very first save of a legacy draft is not reported as stripped")
    void legacyFirstSaveIsNotStripped() {
        // Nothing stored carries a number, so nothing can have been stripped.
        MeasurementNumbering.Result r = MeasurementNumbering.allocate(
                rows("a", "b"), rows("a", "b"), 1L, false);

        assertTrue(r.strippedRowIds.isEmpty());
        assertEquals(List.of(1L, 2L), seqsOf(r.draft));
    }

    @Test
    @DisplayName("a client that declares metadata support is reconciled, not refused")
    void declaringClientIsReconciled() {
        // Its copy predates allocation: it loaded the draft before any numbers existed and
        // then kept its own work through a conflict. That is legitimate, so the server's
        // numbers are restored by row id instead of the save being refused.
        JsonNode stored = rows("a:1", "b:2");

        MeasurementNumbering.Result r = MeasurementNumbering.allocate(
                rows("a", "b", "mine"), stored, 3L, true);

        assertTrue(r.strippedRowIds.isEmpty(), "a declaring client is not treated as stripping");
        assertEquals(List.of(1L, 2L, 3L), seqsOf(r.draft), "existing numbers restored by row id");
        assertEquals(4L, r.nextSeq, "and the mark still only rises");
    }

    @Test
    @DisplayName("declaring support does not let a client invent or move a number")
    void declaringClientCannotRewriteNumbers() {
        JsonNode stored = rows("a:1", "b:2");

        // The caller sends a's row claiming b's number. The stored assignment is
        // authoritative for both rows, so each keeps the number it was given and the claim
        // is simply ignored.
        MeasurementNumbering.Result r = MeasurementNumbering.allocate(
                rows("a:2", "b"), stored, 3L, true);

        List<Long> seqs = seqsOf(r.draft);
        assertEquals(List.of(1L, 2L), seqs, "stored numbers win; no row is renumbered");
        assertEquals(2, seqs.stream().distinct().count(), "no two rows share a number: " + seqs);
    }

    // ----------------------------------------------------------------- robustness

    @Test
    @DisplayName("a malformed draft is tolerated rather than throwing")
    void malformedDraftIsTolerated() {
        assertFalse(MeasurementNumbering.allocate(draft("{}"), null, 1L, false).draft.has("types"));
        MeasurementNumbering.allocate(draft("{\"types\":\"nonsense\"}"), null, 1L, false);
        MeasurementNumbering.allocate(draft("{\"types\":[{\"rows\":5}]}"), null, 1L, false);
        MeasurementNumbering.allocate(null, null, 1L, false);
    }

    @Test
    @DisplayName("a non-numeric or zero seq counts as absent")
    void unusableSeqCountsAsAbsent() {
        JsonNode d = draft("{\"types\":[{\"id\":\"t1\",\"rows\":["
                + "{\"id\":\"a\",\"seq\":\"nonsense\"},{\"id\":\"b\",\"seq\":0},"
                + "{\"id\":\"c\",\"seq\":\"7\"}]}]}");

        MeasurementNumbering.Result r = MeasurementNumbering.allocate(d, null, 1L, false);

        List<Long> seqs = seqsOf(r.draft);
        assertEquals(7L, seqs.get(2), "a numeric string is a usable number");
        assertTrue(seqs.get(0) >= 8L && seqs.get(1) >= 8L,
                "unusable values are replaced from above the highest real one: " + seqs);
    }

    @Test
    @DisplayName("the floor field is left exactly as it arrived")
    void floorIsUntouched() {
        JsonNode d = draft("{\"types\":[{\"id\":\"t1\",\"rows\":["
                + "{\"id\":\"a\",\"floor\":\"2\"},{\"id\":\"b\",\"floor\":\"\"},{\"id\":\"c\"}]}]}");

        MeasurementNumbering.Result r = MeasurementNumbering.allocate(d, null, 1L, false);

        JsonNode rowsOut = r.draft.get("types").get(0).get("rows");
        assertEquals("2", rowsOut.get(0).get("floor").asText());
        assertEquals("", rowsOut.get(1).get("floor").asText());
        assertFalse(rowsOut.get(2).has("floor"), "no floor is not invented");
    }
}
