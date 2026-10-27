package life.work.IntFit.backend.supplier;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Wire shapes for the supplier-payment tracker. */
public final class SupplierDtos {
    private SupplierDtos() { }

    /**
     * One master worksite's cumulative position. Every figure is all-time; the browsing
     * window only decides which rows are listed and in what order.
     *
     * paid counts every effective allocation whatever the method, because a handed-over
     * cheque is a payment exactly like cash.
     */
    public record WorksiteRow(
            long masterWorksiteId,
            String name,
            LocalDate lastActivityOn,
            String group,
            BigDecimal supplies,
            BigDecimal receipts,
            BigDecimal paid,
            BigDecimal remainingToCover,
            BigDecimal credit
    ) { }

    public record WorksitePage(
            String snapshotId,
            List<WorksiteRow> rows,
            Integer nextOffset,
            boolean hasMore,
            int total,
            BigDecimal unassignedSupplies
    ) { }

    public record AllocationInput(Long masterWorksiteId, BigDecimal amount) { }

    public record CreatePaymentRequest(
            String idempotencyKey,
            SupplierEnums.Method method,
            BigDecimal amount,
            LocalDate paymentDate,
            Long bankCheckId,
            LocalDate handedOverOn,
            String reference,
            String notes,
            String recordedBy,
            List<AllocationInput> allocations
    ) { }

    public record AllocationView(Long id, long masterWorksiteId, String masterWorksiteName, BigDecimal amount) { }

    public record PaymentView(
            long id,
            String supplierName,
            LocalDate paymentDate,
            BigDecimal amount,
            SupplierEnums.Method method,
            Long bankCheckId,
            boolean effective,
            BigDecimal allocated,
            BigDecimal unallocated,
            boolean reversed,
            String reversalReason,
            Instant reversedAt,
            String reference,
            String notes,
            List<AllocationView> allocations
    ) { }

    /** A cheque offered for a NEW handover, with what is and is not known about it. */
    public record ChequeOption(
            long id,
            String serialNumber,
            BigDecimal amount,
            LocalDate dueDate,
            String fromWhom,
            SupplierEnums.IssuerType issuerType,
            SupplierEnums.Holder currentHolder,
            boolean needsVerification,
            boolean hasPaymentHistory,
            Long activePaymentId
    ) { }

    /** Confirms what a cheque is and where it is. Not a bank-clearance step. */
    public record VerifyChequeRequest(
            SupplierEnums.IssuerType issuerType,
            SupplierEnums.Holder currentHolder,
            String verifiedBy,
            String note
    ) { }

    public record MovementRequest(
            SupplierEnums.Holder fromHolder,
            SupplierEnums.Holder toHolder,
            String counterparty,
            LocalDate movedOn,
            String note,
            String recordedBy
    ) { }

    public record ReverseRequest(String reason, String recordedBy) { }

    public record AllocationChangeRequest(
            Long masterWorksiteId,
            BigDecimal amount,
            String reason,
            String changedBy
    ) { }

    public record UnassignedSuppliesView(BigDecimal total, Map<String, BigDecimal> byWorksite) { }
}
