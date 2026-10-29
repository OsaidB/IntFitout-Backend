package life.work.IntFit.backend.supplier;

import life.work.IntFit.backend.supplier.SupplierDtos.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * HTTP surface for the AL-Eatimad supplier-payment tracker.
 *
 * Access control is whatever already protects the rest of the API; this feature introduces
 * no role system of its own and takes the acting person as a free-text field the existing
 * app already has.
 */
@RestController
@RequestMapping("/api/supplier-payments")
public class SupplierPaymentController {

    private final SupplierPaymentService service;

    public SupplierPaymentController(SupplierPaymentService service) { this.service = service; }

    @ExceptionHandler(SupplierPaymentService.Problem.class)
    public ResponseEntity<Map<String, Object>> onProblem(SupplierPaymentService.Problem p) {
        return ResponseEntity.status(p.status).body(Map.of("error", p.getMessage(), "status", p.status));
    }

    // ---------------- browsing ----------------

    /** Worksites ranked by latest activity, each once. Omit snapshotId to start/refresh. */
    /**
     * Worksites for the tracker list.
     *
     * Sending nothing keeps the original behaviour: the whole history, every project,
     * newest activity first. `from`/`to` select a FINANCIAL period over dated supplies and
     * payments; `lastActiveWithinDays` is a separate restriction on when a project was last
     * worked, so the two date ideas never share a control.
     */
    @GetMapping("/worksites")
    public WorksitePage worksites(@RequestParam(required = false) String snapshotId,
                                  @RequestParam(defaultValue = "0") int offset,
                                  @RequestParam(defaultValue = "25") int limit,
                                  @RequestParam(required = false)
                                  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate today,
                                  @RequestParam(required = false)
                                  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                  @RequestParam(required = false)
                                  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                  @RequestParam(required = false) String q,
                                  @RequestParam(required = false) String status,
                                  @RequestParam(required = false) Integer lastActiveWithinDays,
                                  @RequestParam(required = false) String sort,
                                  @RequestParam(required = false) String dir) {
        return service.browse(snapshotId, offset, limit, today,
                WorksiteQuery.of(from, to, q, status, lastActiveWithinDays, sort, dir));
    }

    /** Totals for exactly the set the list is showing, under the same criteria. */
    @GetMapping("/worksites/summary")
    public Summary summary(@RequestParam(required = false)
                           @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate today,
                           @RequestParam(required = false)
                           @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                           @RequestParam(required = false)
                           @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                           @RequestParam(required = false) String q,
                           @RequestParam(required = false) String status,
                           @RequestParam(required = false) Integer lastActiveWithinDays,
                           @RequestParam(required = false) String sort,
                           @RequestParam(required = false) String dir) {
        return service.summary(
                WorksiteQuery.of(from, to, q, status, lastActiveWithinDays, sort, dir), today);
    }

    /** Everything still unresolved, including inactive worksites. Not paged. */
    @GetMapping("/worksites/outstanding")
    public List<WorksiteRow> outstanding() { return service.outstanding(); }

    @GetMapping("/unassigned-supplies")
    public UnassignedSuppliesView unassigned() { return service.unassignedSupplies(); }

    // ---------------- payments ----------------

    @GetMapping
    public List<PaymentView> list(@RequestParam(required = false)
                                  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                  @RequestParam(required = false)
                                  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.list(from, to);
    }

    @GetMapping("/{id}")
    public PaymentView get(@PathVariable long id) { return service.get(id); }

    @PostMapping
    public PaymentView create(@RequestBody CreatePaymentRequest body) { return service.create(body); }

    @PostMapping("/{id}/reverse")
    public PaymentView reverse(@PathVariable long id, @RequestBody(required = false) ReverseRequest body) {
        return service.reverse(id, body);
    }

    @PostMapping("/{id}/allocations")
    public PaymentView allocate(@PathVariable long id, @RequestBody AllocationChangeRequest body) {
        return service.changeAllocation(id, body);
    }

    @GetMapping("/{id}/allocation-history")
    public List<AllocationChange> history(@PathVariable long id) { return service.historyOf(id); }

    // ---------------- cheques ----------------

    @GetMapping("/cheques/available")
    public List<ChequeOption> available(@RequestParam(required = false) SupplierEnums.IssuerType issuerType) {
        return service.availableForHandover(issuerType);
    }

    @PostMapping("/cheques/{chequeId}/verify")
    public ChequeOption verify(@PathVariable long chequeId, @RequestBody VerifyChequeRequest body) {
        return service.verify(chequeId, body);
    }

    @PostMapping("/cheques/{chequeId}/movements")
    public ResponseEntity<Void> move(@PathVariable long chequeId, @RequestBody MovementRequest body) {
        service.recordMovement(chequeId, body);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/cheques/{chequeId}/movements")
    public List<ChequeMovement> movements(@PathVariable long chequeId) { return service.movementsOf(chequeId); }
}
