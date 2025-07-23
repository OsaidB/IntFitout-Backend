package life.work.IntFit.backend.model.entity;


import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PendingInvoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDateTime date;

    private Double netTotal;

    private Double total;

    private String worksiteName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "worksite_id")
    private Worksite worksite; // nullable — will be linked on confirmation

    @OneToMany(mappedBy = "pendingInvoice", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PendingInvoiceItem> items;

    @Column(name = "total_match")
    private Boolean totalMatch;

    private String pdfUrl;

    private Boolean confirmed = false;

    private LocalDateTime parsedAt;

    @ManyToOne
    @JoinColumn(name = "reprocessed_from_id")
    private PendingInvoice reprocessedFrom; // self-reference
}
