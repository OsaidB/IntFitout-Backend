//package life.work.IntFit.backend.controller;
//
//import life.work.IntFit.backend.dto.UserDTO;
//import life.work.IntFit.backend.model.entity.User;
//import life.work.IntFit.backend.service.UserService;
//import jakarta.validation.Valid;
//import lombok.AllArgsConstructor;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.http.HttpStatus;
//import org.springframework.http.ResponseEntity;
//import org.springframework.web.bind.annotation.*;
//import java.util.List;
//
//@CrossOrigin("*")
//@AllArgsConstructor
//@RestController
//@RequestMapping("/api/users")
//public class UserController {
//
//    @Autowired
//    private final UserService userService;
//
//    @PostMapping
//    public ResponseEntity<UserDTO> createUser(@Valid @RequestBody UserDTO userDto) {
//        UserDTO createdUserDTO = userService.createUser(userDto);
//        return new ResponseEntity<>(createdUserDTO, HttpStatus.CREATED);
//    }
//
//    @GetMapping("{userId}")
//    public ResponseEntity<UserDTO> getUserById(@PathVariable("userId") long userId) {
//        UserDTO userDto = userService.getUserById(userId);
//        return ResponseEntity.ok(userDto);
//    }
//
//    @GetMapping
//    public ResponseEntity<List<UserDTO>> getAllUsers() {
//        List<UserDTO> usersDTO = userService.getAllUsers();
//        return ResponseEntity.ok(usersDTO);
//    }
//
//    @GetMapping("roles/{role}")
//    public ResponseEntity<List<UserDTO>> getUsersByRole(@PathVariable("role") User.UserRole role) {
//        List<UserDTO> usersDTO = userService.getUsersByRole(role);
//        return ResponseEntity.ok(usersDTO);
//    }
//
//    @PutMapping("{userId}")
//    public ResponseEntity<UserDTO> updateUser(@PathVariable("userId") long userId,
//                                              @Valid @RequestBody UserDTO updatedUser) {
//        UserDTO userDto = userService.updateUser(userId, updatedUser);
//        return ResponseEntity.ok(userDto);
//    }
//
//    @DeleteMapping("{userId}")
//    public ResponseEntity<String> deleteUser(@PathVariable("userId") long userId) {
//        userService.deleteUser(userId);
//        return ResponseEntity.ok("User Deleted Successfully");
//    }
//}
