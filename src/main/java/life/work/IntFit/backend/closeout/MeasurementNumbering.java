package life.work.IntFit.backend.closeout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Permanent measurement numbers.
 *
 * Each measurement gets a number that is stable for the life of the closeout: visible, unique
 * within that closeout, and never reassigned. The numbers are a human-readable reference only
 * - the row's own id remains its identity - so nothing keys off them.
 *
 * <h2>Why the server allocates them</h2>
 * A client-side counter cannot do this. Two devices editing the same closeout would both mint
 * the same next number, and neither would know. Allocation therefore happens here, inside the
 * same transaction as the revision check, against a high-water mark held in a column of
 * closeout_drafts. The mark only ever moves upwards, so a number is never handed out twice -
 * not after a deletion, not after a conflict, and not after a draft is replaced wholesale.
 *
 * <h2>What the numbers actually mean</h2>
 * A number records the order in which the SERVER first accepted that measurement. It is not
 * proof of the order someone typed them. Two people measuring offline will have their work
 * numbered in whatever order their devices reconnect, and that is the honest limit of what any
 * server-allocated sequence can claim.
 *
 * <h2>Legacy rows</h2>
 * Rows from before this feature have no number. They are numbered on the first save that
 * carries them, in the order they appear in the draft - type order, then row order within the
 * type. That is deterministic and stable, so the same draft always produces the same
 * assignment, but it is a reconstruction. Their original entry order cannot be proven and this
 * code does not pretend otherwise.
 *
 * <h2>Older clients</h2>
 * The released app sends a valid revision but drops JSON fields it does not recognise, so the
 * revision check alone does not protect these numbers: such a client would round-trip the
 * draft with every seq erased. {@link Result#strippedRowIds} reports exactly that, by
 * comparing against what is stored, so the save can be refused rather than silently
 * renumbering everybody's measurements.
 */
public final class MeasurementNumbering {

    public static final String SEQ_FIELD = "seq";
    public static final String FLOOR_FIELD = "floor";

    private MeasurementNumbering() {
    }

    /** The outcome of allocating numbers for one incoming draft. */
    public static final class Result {
        /** The incoming draft, with a number on every measurement. */
        public final JsonNode draft;
        /** The next number to hand out after this save. Never lower than it was. */
        public final long nextSeq;
        /**
         * Rows that are stored WITH a number but arrived without one. Non-empty means the
         * caller stripped metadata it did not understand, and the save must be refused.
         */
        public final List<String> strippedRowIds;

        Result(JsonNode draft, long nextSeq, List<String> strippedRowIds) {
            this.draft = draft;
            this.nextSeq = nextSeq;
            this.strippedRowIds = strippedRowIds;
        }
    }

    /**
     * Assign numbers to any measurement that lacks one.
     *
     * @param incoming  the draft the client is saving. Mutated in place: it is already a
     *                  private copy parsed from the request body.
     * @param stored    the draft currently held, or null when this is the first save.
     * @param currentNextSeq the stored high-water mark.
     * @param clientUnderstandsMetadata whether the caller declared that it round-trips
     *        measurement metadata. A caller that does is RECONCILED when a row arrives
     *        without a number the server holds - its copy simply predates allocation, which
     *        happens legitimately whenever a device loaded a draft before any numbers
     *        existed and then resolved a conflict by keeping its own work. A caller that
     *        does not declare it is REPORTED, because for that caller a missing number means
     *        the field was dropped.
     */
    public static Result allocate(JsonNode incoming, JsonNode stored, long currentNextSeq,
                                  boolean clientUnderstandsMetadata) {
        Map<String, Long> storedSeqByRowId = collectSeqByRowId(stored);

        // The mark can only rise. Three things push it up: where it already was, any number
        // the stored draft is using, and any number the incoming draft claims. That last one
        // matters because a client keeps numbers for rows it already has, and after a
        // "keep mine" conflict resolution those rows may be the only record of them.
        long next = Math.max(1L, currentNextSeq);
        for (Long seq : storedSeqByRowId.values()) {
            if (seq != null && seq >= next) next = seq + 1;
        }
        for (Long seq : collectSeqByRowId(incoming).values()) {
            if (seq != null && seq >= next) next = seq + 1;
        }

        List<String> stripped = new ArrayList<>();
        Set<Long> used = new LinkedHashSet<>();

        for (JsonNode type : typesOf(incoming)) {
            for (JsonNode row : rowsOf(type)) {
                if (!(row instanceof ObjectNode)) continue;
                ObjectNode rowNode = (ObjectNode) row;
                String rowId = text(rowNode, "id");

                Long claimed = seqOf(rowNode);
                Long stored_ = rowId == null ? null : storedSeqByRowId.get(rowId);

                if (stored_ != null) {
                    /*
                     * The stored number is AUTHORITATIVE for a row the server has already
                     * numbered. Whatever the caller sent is replaced by it.
                     *
                     * This is what keeps the guarantee that an existing measurement never
                     * acquires a different number. It also closes a hole: a caller that
                     * sent one row another row's number used to make the second row restore
                     * to the same value, leaving two measurements sharing a number.
                     *
                     * Only the number is taken from the stored copy. Dimensions, room,
                     * floor, notes, additions and deletions all come from the caller.
                     */
                    rowNode.put(SEQ_FIELD, stored_);
                    used.add(stored_);
                    // A caller that sent nothing where the server holds a number has either
                    // dropped the field, or is working from a copy made before allocation.
                    // Only a caller that cannot tell us which is reported.
                    if (claimed == null && !clientUnderstandsMetadata) {
                        stripped.add(rowId);
                    }
                    continue;
                }

                if (claimed == null) {
                    // Genuinely new, or a legacy row being numbered for the first time.
                    long assigned = next++;
                    rowNode.put(SEQ_FIELD, assigned);
                    used.add(assigned);
                    continue;
                }

                // No stored number, but the caller has one - after a draft was replaced
                // wholesale, its rows may be the only record. Honour it unless it is already
                // taken in this draft, which is what a client-side duplicate produces.
                if (used.contains(claimed)) {
                    long assigned = next++;
                    rowNode.put(SEQ_FIELD, assigned);
                    used.add(assigned);
                } else {
                    used.add(claimed);
                }
            }
        }

        return new Result(incoming, next, stripped);
    }

    /** Every row id that currently holds a number, with the number. */
    private static Map<String, Long> collectSeqByRowId(JsonNode draft) {
        Map<String, Long> out = new HashMap<>();
        for (JsonNode type : typesOf(draft)) {
            for (JsonNode row : rowsOf(type)) {
                String id = text(row, "id");
                Long seq = seqOf(row);
                if (id != null && seq != null) out.put(id, seq);
            }
        }
        return out;
    }

    private static Iterable<JsonNode> typesOf(JsonNode draft) {
        if (draft == null || !draft.isObject()) return List.of();
        JsonNode types = draft.get("types");
        return (types != null && types.isArray()) ? types : List.of();
    }

    private static Iterable<JsonNode> rowsOf(JsonNode type) {
        if (type == null || !type.isObject()) return List.of();
        JsonNode rows = type.get("rows");
        return (rows != null && rows.isArray()) ? rows : List.of();
    }

    private static Long seqOf(JsonNode row) {
        if (row == null || !row.isObject()) return null;
        JsonNode seq = row.get(SEQ_FIELD);
        if (seq == null || seq.isNull()) return null;
        if (seq.isIntegralNumber()) {
            long value = seq.asLong();
            return value > 0 ? value : null;
        }
        if (seq.isTextual()) {
            try {
                long value = Long.parseLong(seq.asText().trim());
                return value > 0 ? value : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.isObject()) return null;
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? null : value.asText();
    }
}
