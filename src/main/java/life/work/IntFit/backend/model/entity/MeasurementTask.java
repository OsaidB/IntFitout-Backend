package life.work.IntFit.backend.model.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MeasurementTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String taskType; // e.g., "Hidden Lights", "Liners"

    private double length;
    private double width;
    private double height;

    private String unit; // e.g., "m", "cm", etc.
    private double unitCost;

    private double totalCost;

    @Enumerated(EnumType.STRING)
    private CalculationType calculationType; // FLAT or VOLUME

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_id")
    private Room room;

    @PrePersist
    @PreUpdate
    public void calculateTotalCost() {
        if (calculationType == null) {
            throw new IllegalArgumentException("calculationType must not be null");
        }

        switch (calculationType) {
            case FLAT -> totalCost = unitCost * length * width;
            case VOLUME -> totalCost = unitCost * length * width * height;
        }
    }


    public enum CalculationType {
        FLAT,    // 2D (e.g., liners, tiles)
        VOLUME   // 3D (e.g., concrete fill, insulation)
    }
}
