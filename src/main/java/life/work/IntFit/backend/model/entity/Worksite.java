package life.work.IntFit.backend.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.util.List;
@Entity
@Table(name = "worksites")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Worksite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name; //هني بني البيرة

    private String city; //Ramallah
    private String area; //AL-Bireh
    @Column(nullable = false)
    private String locationDetails; //مقابل بلدية البيرة

    private String type;         // e.g., Apartment, Villa, Shop (restaurant)
    private String status;       // e.g., Active, Paused, Completed (completed)
    private String budget;      //(20,000 - 50,000)

    private LocalDate startDate; // 1 feb 2025
    private LocalDate deadline;   // Planned completion date (20-march)
    private LocalDate endDate;    // Actual completion date (18-march)

    private String description; //تجديد المطعم من الصفر
    private Integer progress;     // e.g., 75 = 75% complete (100%)
    private boolean isArchived;  //false

    @Column(length = 1000)
    private String notes;

    @OneToMany(mappedBy = "worksite", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<WorksiteContact> contacts;
}
