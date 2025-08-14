//package life.work.IntFit.backend.model.entity;
//
//import jakarta.persistence.*;
//import lombok.*;
//import java.time.LocalDate;
//
//@Getter
//@Setter
//@NoArgsConstructor
//@AllArgsConstructor
//@Entity
//@Table(name = "pets")
//public class Pet {
//
//    @Id
//    @GeneratedValue(strategy = GenerationType.IDENTITY)
//    private Long id;
//
//    @Column(nullable = false)
//    private String name;
//
//    @Column(nullable = false)
//    private String type;
//
//    private String breed;
//
//    @Column(name = "birth_date")
//    private LocalDate birthDate;
//
//    @Column(name = "medical_history",length = 1000) // Adjust length based on requirements
//    private String medicalHistory;
//
//    @Column(name = "image_url") // New column to store the image URL
//    private String imageUrl;
//
//    @ManyToOne
//    @JoinColumn(name = "owner_id", nullable = false)
//    private Client owner; // Use Client explicitly instead of User
//}
