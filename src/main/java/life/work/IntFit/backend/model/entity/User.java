//package life.work.IntFit.backend.model.entity;
//
//import jakarta.persistence.*;
//import lombok.*;
//import org.hibernate.annotations.CreationTimestamp;
//import org.hibernate.annotations.UpdateTimestamp;
//
//import java.io.Serializable;
//import java.time.LocalDate;
//import java.time.LocalDateTime;
//import java.util.Date;
//import java.util.Objects;
//
//@Getter
//@Setter
//@NoArgsConstructor
//@AllArgsConstructor
//@Builder
//@Entity
//@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
//@Table(name = "users") // This is the table for the hierarchy
//public class User implements Serializable {
//
//    private static final long serialVersionUID = 2353528370345499815L;
//
//    @Id
//    @GeneratedValue(strategy = GenerationType.IDENTITY) // Using IDENTITY for auto-increment
//    private Long id;
//
//    @Column(nullable = false, unique = true)
//    private String email;
//
////    @Column(nullable = false)
//    @Column()
//    private String password;
//
//    @Column(name = "first_name", nullable = false)
//    private String firstName;
//
//    @Column(name = "last_name", nullable = false)
//    private String lastName;
//
//    @Enumerated(EnumType.STRING)
//    @Column(nullable = false)
//    private UserRole role; // Specifies if the user is a Client, Manager, etc.
//
//    @Column(name = "phone_number")
//    private String phoneNumber; // Contact number for the user
//
//    @Column(name = "date_of_birth")
//    private LocalDate dateOfBirth; // User's date of birth (optional)
//
//    @Column(name = "last_password_reset")
//    private Date lastPasswordReset; // Time when the password was last reset
//
//    @CreationTimestamp
//    @Column(name = "created_at", updatable = false)
//    private LocalDateTime createdAt; // Time of account creation
//
//    @UpdateTimestamp
//    @Column(name = "updated_at")
//    private LocalDateTime updatedAt; // Last time the account info was updated
//
//    @Override
//    public boolean equals(Object o) {
//        if (this == o) return true;
//        if (o == null || getClass() != o.getClass()) return false;
//        User user = (User) o;
//        return Objects.equals(id, user.id);
//    }
//
//    @Override
//    public int hashCode() {
//        return Objects.hash(id);
//    }
//
//    @Override
//    public String toString() {
//        return "User{" +
//                "id=" + id +
//                ", email='" + email + '\'' +
//                ", firstName='" + firstName + '\'' +
//                ", lastName='" + lastName + '\'' +
//                ", role=" + role +
//                ", phoneNumber='" + phoneNumber + '\'' +
//                ", dateOfBirth=" + dateOfBirth +
//                ", lastPasswordReset=" + lastPasswordReset +
//                ", createdAt=" + createdAt +
//                ", updatedAt=" + updatedAt +
//                '}';
//    }
//
//    // Enum to define the different types of roles within the clinic
//    public enum UserRole {
//        CLIENT, VET_ASSISTANT, SECRETARY, MANAGER
//    }
//}
