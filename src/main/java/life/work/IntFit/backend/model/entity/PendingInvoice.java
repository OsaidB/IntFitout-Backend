package life.work.IntFit.backend.model.entity;


import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    private Set<PendingInvoiceItem> items = new HashSet<>();


    @Column(name = "total_match")
    private Boolean totalMatch;

    private String pdfUrl;

    private Boolean confirmed = false;

    private LocalDateTime parsedAt;

    private Long reprocessedFromId;
}
