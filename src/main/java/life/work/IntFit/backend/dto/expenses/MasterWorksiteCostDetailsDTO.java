package life.work.IntFit.backend.dto.expenses;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MasterWorksiteCostDetailsDTO {

    private Long masterWorksiteId;
    private String masterWorksiteName;

    private LocalDate start;
    private LocalDate end;

    private Totals totals;

    private List<DailyRow> daily;            // per day totals (+ optional details)
    private List<WorkerRow> workers;         // per worker totals
    private List<ExtraRow> generalExtras;    // general extras list

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Totals {
        private BigDecimal supplies;
        private BigDecimal workers;
        private BigDecimal extrasDated;
        private BigDecimal extrasGeneral;
        private BigDecimal grandTotal;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class DailyRow {
        private LocalDate date;
        private String dayOfWeek; // e.g. MONDAY (frontend can localize)

        private BigDecimal supplies;
        private BigDecimal workers;
        private BigDecimal extras;
        private BigDecimal total;

        private Long invoicesCount;
        private Long assignmentsCount;

        // optional detail lists (keep if you want the day accordion to show data without extra calls)
        private List<InvoiceLite> invoices;
        private List<WorkerLite> workerLines;
        private List<ExtraRow> extrasLines;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class WorkerRow {
        private Long teamMemberId;
        private String name;
        private String role;
        private BigDecimal totalCost;
        private BigDecimal totalAllocatedHours;
        private Long daysWorked;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class InvoiceLite {
        private Long id;
        private LocalDate businessDate;
        private BigDecimal total;
        private String worksiteName;
        private String pdfUrl;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class WorkerLite {
        private Long teamMemberId;
        private String name;
        private BigDecimal allocatedHours;
        private BigDecimal cost;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ExtraRow {
        private Long id;
        private LocalDate costDate; // null for general
        private BigDecimal amount;
        private String description;
        private boolean general;
    }
}
