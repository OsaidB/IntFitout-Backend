package life.work.IntFit.backend.closeout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import life.work.IntFit.backend.model.entity.CloseoutFinal;
import life.work.IntFit.backend.model.entity.CloseoutFinalRow;
import life.work.IntFit.backend.model.entity.CloseoutFinalType;
import life.work.IntFit.backend.model.entity.MasterWorksite;
import life.work.IntFit.backend.repository.CloseoutDraftRepository;
import life.work.IntFit.backend.repository.CloseoutFinalRepository;
import life.work.IntFit.backend.repository.MasterWorksiteRepository;
import life.work.IntFit.backend.service.impl.CloseoutFinalizeServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Characterization of what finalization computes TODAY.
 *
 * These tests do not assert what the arithmetic ought to be. They pin the behaviour that
 * existing finalized records were produced by, so that the concurrency and deletion work in
 * this batch cannot change a total without a test going red. Nothing here should be
 * "corrected" without a deliberate decision about the records already stored.
 *
 * The rounding mismatch in {@link #roundingMismatchAgainstTheScreen()} is recorded, not
 * fixed: see docs/closeout-rounding-discrepancy.md.
 */
class CloseoutFinalizeCharacterizationTest {

    private static final long MASTER_ID = 3L;

    private CloseoutFinalizeServiceImpl service;
    private CloseoutFinalRepository finals;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        finals = mock(CloseoutFinalRepository.class);
        CloseoutDraftRepository drafts = mock(CloseoutDraftRepository.class);
        MasterWorksiteRepository masters = mock(MasterWorksiteRepository.class);

        MasterWorksite master = new MasterWorksite();
        master.setId(MASTER_ID);
        when(masters.findById(MASTER_ID)).thenReturn(Optional.of(master));
        when(finals.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service = new CloseoutFinalizeServiceImpl(finals, drafts, masters, mapper);
    }

    private JsonNode draft(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Finalizes the draft and hands back the entity that would have been persisted. */
    private CloseoutFinal finalize(String json) {
        service.finalizeCloseout(MASTER_ID, draft(json));
        org.mockito.ArgumentCaptor<CloseoutFinal> captor =
                org.mockito.ArgumentCaptor.forClass(CloseoutFinal.class);
        org.mockito.Mockito.verify(finals).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("m2 rows in dims mode: quantity is length x width, at 4 decimals")
    void squareMetresFromDimensions() {
        CloseoutFinal f = finalize("{\"types\":[{\"id\":\"t1\",\"name\":\"Ceiling\",\"unit\":\"m²\","
                + "\"category\":\"gypsum\",\"unitPrice\":\"10\",\"rows\":["
                + "{\"id\":\"r1\",\"room\":\"Hall\",\"mode\":\"dims\",\"length\":\"3.5\",\"width\":\"2\"}]}]}");

        CloseoutFinalType t = f.getTypes().get(0);
        CloseoutFinalRow r = t.getRows().get(0);
        assertEquals(new BigDecimal("7.0000"), r.getQuantity());
        assertEquals(new BigDecimal("70.00"), r.getSubtotal());
        assertEquals(new BigDecimal("70.00"), t.getSubtotal());
        assertEquals(new BigDecimal("70.00"), f.getGrandTotal());
    }

    @Test
    @DisplayName("a count on an m2 row multiplies the area")
    void countActsAsMultiplier() {
        CloseoutFinal f = finalize("{\"types\":[{\"id\":\"t1\",\"name\":\"Bulkhead\",\"unit\":\"m²\","
                + "\"category\":\"gypsum\",\"unitPrice\":\"10\",\"rows\":["
                + "{\"id\":\"r1\",\"mode\":\"dims\",\"length\":\"2\",\"width\":\"1.5\",\"count\":\"3\"}]}]}");

        assertEquals(new BigDecimal("9.0000"), f.getTypes().get(0).getRows().get(0).getQuantity());
    }

    @Test
    @DisplayName("gypsum and painting totals are reported separately and summed")
    void categoryTotals() {
        CloseoutFinal f = finalize("{\"types\":["
                + "{\"id\":\"t1\",\"name\":\"Ceiling\",\"unit\":\"m²\",\"category\":\"gypsum\","
                + "\"unitPrice\":\"10\",\"rows\":[{\"id\":\"r1\",\"mode\":\"area\",\"area\":\"5\"}]},"
                + "{\"id\":\"t2\",\"name\":\"Walls\",\"unit\":\"m²\",\"category\":\"painting\","
                + "\"unitPrice\":\"4\",\"rows\":[{\"id\":\"r2\",\"mode\":\"area\",\"area\":\"25\"}]}]}");

        assertEquals(new BigDecimal("50.00"), f.getGypsumTotal());
        assertEquals(new BigDecimal("100.00"), f.getPaintingTotal());
        assertEquals(new BigDecimal("150.00"), f.getGrandTotal());
    }

    @Test
    @DisplayName("row identity and order are carried into the finalized record")
    void rowIdentityIsPreserved() {
        CloseoutFinal f = finalize("{\"types\":[{\"id\":\"t1\",\"name\":\"Ceiling\",\"unit\":\"m\","
                + "\"category\":\"gypsum\",\"unitPrice\":\"5\",\"rows\":["
                + "{\"id\":\"row-aaa\",\"room\":\"Kitchen\",\"length\":\"2\"},"
                + "{\"id\":\"row-bbb\",\"room\":\"Bath\",\"length\":\"3\"}]}]}");

        CloseoutFinalType t = f.getTypes().get(0);
        assertEquals("t1", t.getClientTypeId());
        assertEquals("row-aaa", t.getRows().get(0).getClientRowId());
        assertEquals(0, t.getRows().get(0).getRowIndex());
        assertEquals("row-bbb", t.getRows().get(1).getClientRowId());
        assertEquals(1, t.getRows().get(1).getRowIndex());
        assertEquals("Kitchen", t.getRows().get(0).getRoom());
    }

    @Test
    @DisplayName("RECORDED DISCREPANCY: the finalized total can differ from the screen total")
    void roundingMismatchAgainstTheScreen() {
        // Four rows of 0.125 m² at 1.00 each.
        //
        // The backend rounds each row to money first:  money(0.125 x 1.00) = 0.13, x4 = 0.52
        // The app multiplies the summed quantity once:      0.5 x 1.00     = 0.50
        //
        // Both are internally consistent; they are simply different conventions. This test
        // pins the stored one so it cannot drift silently while the decision is open.
        String json = "{\"types\":[{\"id\":\"t1\",\"name\":\"Trim\",\"unit\":\"m²\","
                + "\"category\":\"gypsum\",\"unitPrice\":\"1\",\"rows\":["
                + "{\"id\":\"r1\",\"mode\":\"dims\",\"length\":\"0.25\",\"width\":\"0.5\"},"
                + "{\"id\":\"r2\",\"mode\":\"dims\",\"length\":\"0.25\",\"width\":\"0.5\"},"
                + "{\"id\":\"r3\",\"mode\":\"dims\",\"length\":\"0.25\",\"width\":\"0.5\"},"
                + "{\"id\":\"r4\",\"mode\":\"dims\",\"length\":\"0.25\",\"width\":\"0.5\"}]}]}";

        CloseoutFinal f = finalize(json);
        CloseoutFinalType t = f.getTypes().get(0);

        assertEquals(new BigDecimal("0.5000"), t.getQuantityTotal(), "quantities agree");
        assertEquals(new BigDecimal("0.52"), t.getSubtotal(), "stored: per-row rounding, summed");

        BigDecimal screenConvention = new BigDecimal("0.50");
        assertNotEquals(screenConvention, t.getSubtotal(),
                "the discrepancy is real and is recorded for a separate decision");
    }

    @Test
    @DisplayName("unknown row fields do not break finalization")
    void unknownFieldsAreTolerated() {
        // seq and floor arrive in the next batch; finalization must not reject them today.
        CloseoutFinal f = finalize("{\"types\":[{\"id\":\"t1\",\"name\":\"Ceiling\",\"unit\":\"m²\","
                + "\"category\":\"gypsum\",\"unitPrice\":\"10\",\"rows\":["
                + "{\"id\":\"r1\",\"seq\":12,\"floor\":\"2\",\"mode\":\"area\",\"area\":\"2\"}]}]}");

        assertEquals(new BigDecimal("20.00"), f.getGrandTotal());
        // The whole draft is snapshotted, so the new fields survive even though the
        // relational row has no column for them.
        org.junit.jupiter.api.Assertions.assertTrue(
                f.getDraftSnapshotJson().contains("\"seq\":12"),
                "the snapshot must retain fields the relational columns do not model");
    }
}
