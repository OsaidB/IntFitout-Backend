// File: src/main/java/life/work/IntFit/backend/service/MasterWorksiteStartService.java
package life.work.IntFit.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import life.work.IntFit.backend.dto.MasterWorksiteStartSummaryDTO;
import life.work.IntFit.backend.model.entity.Invoice;
import life.work.IntFit.backend.model.entity.WorkAssignment;
import life.work.IntFit.backend.repository.InvoiceRepository;
import life.work.IntFit.backend.repository.WorkAssignmentRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class MasterWorksiteStartService {

    private final InvoiceRepository invoiceRepo;
    private final WorkAssignmentRepository assignmentRepo;

    public MasterWorksiteStartSummaryDTO getStartSummary(Long masterId) {
        // --- First invoice (child Worksite → MasterWorksite) ---
        Optional<Invoice> firstInvoiceOpt =
                invoiceRepo.findFirstByWorksite_MasterWorksite_IdOrderByDateAsc(masterId);

        LocalDate firstInvoiceDate = firstInvoiceOpt
                .map(inv -> toLocalDate(inv.getDate()))
                .orElse(null);

        Long firstInvoiceId = firstInvoiceOpt.map(Invoice::getId).orElse(null);
        Long firstInvoiceWorksiteId = firstInvoiceOpt
                .map(inv -> inv.getWorksite() != null ? inv.getWorksite().getId() : null)
                .orElse(null);

        // --- First assignment (direct MasterWorksite relation) ---
        Optional<WorkAssignment> firstAssignOpt =
                assignmentRepo.findFirstByMasterWorksite_IdOrderByDateAsc(masterId);

        LocalDate firstAssignmentDate = firstAssignOpt
                .map(WorkAssignment::getDate) // your field is LocalDate
                .orElse(null);

        Long firstAssignmentId = firstAssignOpt.map(WorkAssignment::getId).orElse(null);
        // You don't store a child Worksite on WorkAssignment; return the master id here or null.
        Long firstAssignmentWorksiteId = null;

        // --- Computed start ---
        LocalDate startDate = minDate(firstInvoiceDate, firstAssignmentDate);
        String startSource = computeSource(firstInvoiceDate, firstAssignmentDate);

        return MasterWorksiteStartSummaryDTO.builder()
                .masterWorksiteId(masterId)
                .firstInvoiceDate(firstInvoiceDate)
                .firstInvoiceId(firstInvoiceId)
                .firstInvoiceWorksiteId(firstInvoiceWorksiteId)
                .firstAssignmentDate(firstAssignmentDate)
                .firstAssignmentId(firstAssignmentId)
                .firstAssignmentWorksiteId(firstAssignmentWorksiteId)
                .startDate(startDate)
                .startSource(startSource)
                .build();
    }

    // ---------- helpers ----------
    private LocalDate minDate(LocalDate a, LocalDate b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isBefore(b) ? a : b;
    }

    private String computeSource(LocalDate invoice, LocalDate assignment) {
        if (invoice == null && assignment == null) return "NONE";
        if (invoice == null) return "ASSIGNMENT";
        if (assignment == null) return "INVOICE";
        return invoice.isBefore(assignment) ? "INVOICE" : "ASSIGNMENT";
    }

    /** Convert various date types to LocalDate without reflection. */
    private LocalDate toLocalDate(Object val) {
        if (val == null) return null;
        if (val instanceof LocalDate ld) return ld;
        if (val instanceof LocalDateTime ldt) return ldt.toLocalDate();
        if (val instanceof java.util.Date d)
            return d.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        // Unknown type
        return null;
    }
}
