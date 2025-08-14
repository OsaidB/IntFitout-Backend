//package life.work.IntFit.backend.dto;
//
//import life.work.IntFit.backend.model.entity.User;
//import lombok.AllArgsConstructor;
//import lombok.Getter;
//import lombok.NoArgsConstructor;
//import lombok.Setter;
//import jakarta.validation.constraints.NotNull;
//import jakarta.validation.constraints.Email;
//
//import java.time.LocalDate;
//import java.time.LocalDateTime;
//
//@Getter
//@Setter
//@NoArgsConstructor
//@AllArgsConstructor
//public class UserDTO {
//    private Long userId;
////    private String username;
//
//    @Email(message = "Email should be valid")
//    private String email;
////    private String password;//should not stay here
//
//    private String firstName;
//    private String lastName;
//
//    @NotNull(message = "The Role can't be null")
//    private User.UserRole role; // Changed from String to UserRole enum
//
//    private String phoneNumber; // Optional contact number
//
//    private LocalDate dateOfBirth; // Optional birthdate
//
//    private LocalDateTime createdAt;
//    private LocalDateTime updatedAt;
//
////    public String getPassword() {
////        String pass="tempTemp For Test";
////
////        return pass;
////    }
//}
