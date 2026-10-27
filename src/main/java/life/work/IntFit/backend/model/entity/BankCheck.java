package life.work.IntFit.backend.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.math.BigDecimal;

@Entity
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class BankCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    private LocalDate dueDate;

    /** "Given to" — the final recipient (store / supplier). */
    private String recipientName;

    private String notes;

    /** If true, already cleared/settled. */
    private boolean cleared = false;

    /** Issuer name exactly as written on the check (this is your old "fromWhom"). */
    @Column
    private String fromWhom;

    @Column
    private String serialNumber;

    /** Optional image URL for the check photo. */
    @Column(length = 512)
    private String imageUrl;

    /** NEW — whether the issuer is personal (for your Al-Etimad dashboard logic). */
    @Column(name = "personal_issuer", nullable = false)
    private boolean personalIssuer = false;

    /** NEW — “Taken from”: exact Master Worksite that handed us this check as a payment. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_master_worksite_id")
    private MasterWorksite sourceMasterWorksite;

    /** NEW — denormalized snapshot (fast list/search without joins). */
    @Column(name = "source_master_worksite_name")
    private String sourceMasterWorksiteName;

    // ------------------------------------------------------------------
    // Supplier-payment tracker fields.
    //
    // Every column below is nullable and is NULL on all pre-existing rows. NULL means
    // "not known", never a value, and `fromWhom` text is not evidence of who issued it.
    // Nothing is backfilled or inferred. An administrator confirms issuer and custody
    // before a legacy cheque may back a new payment.
    //
    // The legacy `cleared` flag above is untouched by this feature and keeps its existing
    // meaning and behaviour; the tracker neither reads it nor writes it.
    // ------------------------------------------------------------------

    /** Who wrote it. NULL = unknown (legacy). */
    @Enumerated(EnumType.STRING)
    @Column(name = "issuer_type", length = 20)
    private life.work.IntFit.backend.supplier.SupplierEnums.IssuerType issuerType;

    /** Who holds it now. NULL = unknown (legacy), which is not "we hold it". */
    @Enumerated(EnumType.STRING)
    @Column(name = "current_holder", length = 20)
    private life.work.IntFit.backend.supplier.SupplierEnums.Holder currentHolder;

    /** When an administrator confirmed issuer + availability. Verification alone creates no coverage. */
    @Column(name = "availability_verified_at")
    private java.time.Instant availabilityVerifiedAt;

    @Column(name = "availability_verified_by", length = 120)
    private String availabilityVerifiedBy;
}
