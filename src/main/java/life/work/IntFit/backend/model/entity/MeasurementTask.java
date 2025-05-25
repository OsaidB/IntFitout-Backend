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

    private String taskType;

    private double length;
    private double width;
    private double height;

    private String unit; // e.g., "m", "cm", etc.
    private double unitCost;

    private double measurement; // computed measurement value (e.g., area or volume)
    private double totalCost;   // computed total cost

    @Enumerated(EnumType.STRING)
    private CalculationType calculationType;

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
            case FLAT -> measurement = length * width;
            case VOLUME -> measurement = length * width * height;
        }

        totalCost = unitCost * measurement;
    }

    public enum CalculationType {
        FLAT, VOLUME
    }
}
