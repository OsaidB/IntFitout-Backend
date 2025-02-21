package life.work.IntFit.backend.service;

import life.work.IntFit.backend.dto.UserDTO;
import life.work.IntFit.backend.model.entity.User;

import java.util.List;

public interface UserService {
    UserDTO createUser(UserDTO userDto);
    UserDTO getUserById(Long userId);
    List<UserDTO> getAllUsers();
    List<UserDTO> getUsersByRole(User.UserRole role); // Accepting UserRole enum directly from User entity
    UserDTO updateUser(Long userId, UserDTO updatedUser);
    void deleteUser(Long userId);
    UserDTO getUserByEmail(String email); // Changed from username to email
}
