package life.work.IntFit.backend.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.util.List;

@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
@Entity
@Table(name = "worksites")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
@EqualsAndHashCode
public class Worksite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String name; // هني بني البيرة

    private String city;        // Ramallah
    private String area;        // AL-Bireh
    private String locationDetails; // مقابل بلدية البيرة

    private String type;        // Apartment, Villa, Shop (restaurant)
    private String status;      // Active, Paused, Completed
    private String budget;      // (20,000 - 50,000)

    private LocalDate startDate;   // 1 feb 2025
    private LocalDate deadline;    // planned
    private LocalDate endDate;     // actual

    private String description; // تجديد المطعم من الصفر
    private Integer progress;   // e.g., 75
    private boolean isArchived;

    @Column(length = 1000)
    private String notes;

    // 🔒 Hide from JSON to prevent LazyInitializationException
    @JsonIgnore
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "master_worksite_id")
    private MasterWorksite masterWorksite;

    @JsonIgnore
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @OneToMany(mappedBy = "worksite", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    private List<WorksiteContact> contacts;
}
