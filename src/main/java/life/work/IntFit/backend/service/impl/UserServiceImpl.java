//package life.work.IntFit.backend.service.impl;
//
//import life.work.IntFit.backend.dto.UserDTO;
//import life.work.IntFit.backend.exception.ResourceNotFoundException;
//import life.work.IntFit.backend.mapper.UserMapper;
//import life.work.IntFit.backend.model.entity.User;
//import life.work.IntFit.backend.repository.UserRepo;
//import life.work.IntFit.backend.service.UserService;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.stereotype.Service;
//
//import java.time.LocalDateTime;
//import java.util.List;
//import java.util.stream.Collectors;
//
//@Service
//public class UserServiceImpl implements UserService {
//
//    @Autowired
//    private final UserRepo userRepo;
//
//    public UserServiceImpl(UserRepo userRepo) {
//        this.userRepo = userRepo;
//    }
//
//    @Override
//    public UserDTO createUser(UserDTO userDTO) {
////        FunctionalRole role = funcRoleRepo.findById(userDTO.getFunctionalRoleId())
////                .orElseThrow(() -> new ResourceNotFoundException("FunctionalRole not found with id : " + userDTO.getFunctionalRoleId()));
//        //        //        Role role = FuncRoleRepo.findByRoleName(userDTO.getRole());
////
////        Role role = FuncRoleRepo.findById(userDTO.getRole())
////                .orElseThrow(() -> new ResourceNotFoundException("Role not found"));
////
////        if (role == null) {
////            throw new RuntimeException("Role not found");
////        }
////
////        User user = new User();
////        user.setRole(role);
////
////        UserMapper.INSTANCE.toUserEntity(userDTO);
//////        user.setRole(role);
//
////        Role role = FuncRoleRepo.findByRoleName(userDTO.getRole());
//        if (userDTO.getRole() == null) {
//            throw new RuntimeException("Role cannot be null");
//        }
//
//        User user = UserMapper.INSTANCE.toUserEntity(userDTO);
//        user.setRole(userDTO.getRole());
//
//        User savedUser = userRepo.save(user);
//        return UserMapper.INSTANCE.toUserDTO(savedUser);
//    }
//
//    @Override
//    public UserDTO getUserByEmail(String email) {
//        User user = userRepo.findByEmail(email)
//                .orElseThrow(() -> new ResourceNotFoundException("User not found with email: " + email));
//        return UserMapper.INSTANCE.toUserDTO(user);
//    }
//
//    @Override
//    public UserDTO getUserById(Long userId) {
//        User user = userRepo.findById(userId)
//                .orElseThrow(() -> new ResourceNotFoundException("User not exists with given id: " + userId));
//        return UserMapper.INSTANCE.toUserDTO(user);
//    }
//
//    @Override
//    public List<UserDTO> getAllUsers() {
//        List<User> users = userRepo.findAll();
//        return users.stream().map(UserMapper.INSTANCE::toUserDTO).collect(Collectors.toList());
//    }
//
//    @Override
//    public List<UserDTO> getUsersByRole(User.UserRole role) {
//        List<User> users = userRepo.findUsersByRole(role);
//        return users.stream().map(UserMapper.INSTANCE::toUserDTO).collect(Collectors.toList());
//    }
//
//    @Override
//    public UserDTO updateUser(Long userId, UserDTO updatedUser) {
//        User user = userRepo.findById(userId)
//                .orElseThrow(() -> new ResourceNotFoundException("User does not exist with the given id: " + userId));
//
//        // Fetch the FunctionalRole entity from the database
////        String role = funcRoleRepo.findById(updatedUser.getFunctionalRoleId())
////                .orElseThrow(() -> new ResourceNotFoundException("Role not found with id : " + updatedUser.getFunctionalRoleId()));
//        if (updatedUser.getRole() == null) {
//            throw new RuntimeException("Role cannot be null");
//        }
//
////        user.setUpdatedAt(updatedUser.getUpdatedAt());
//        user.setEmail(updatedUser.getEmail());
//
////        user.setPassword(updatedUser.getPassword());
////        user.setHash(updatedUser.getHashedPassword());
////        user.setPassword(updatedUser.getHashedPassword());
//
//        user.setRole(updatedUser.getRole()); // Set the FuncRole entity
////        user.setIsTeamLeader(updatedUser.getIsTeamLeader());
////        user.setUsername(updatedUser.getUsername());
//
//        user.setFirstName(updatedUser.getFirstName());
//
//        user.setLastName(updatedUser.getLastName());
//        user.setRole(updatedUser.getRole());
//        user.setUpdatedAt(LocalDateTime.now()); // Set current time for updatedAt
//
//        User updatedUserObj = userRepo.save(user);
//        return UserMapper.INSTANCE.toUserDTO(updatedUserObj);
//    }
//
//    @Override
//    public void deleteUser(Long userId) {
//        userRepo.findById(userId)
//                .orElseThrow(() -> new ResourceNotFoundException("User does not exist with given id: " + userId));
//        userRepo.deleteById(userId);
//    }
//
////    @Override
////    public UserDTO getUserByUsername(String email) {
//////        return Optional.empty();
////
////        User user = userRepo.findByEmail(email).orElseThrow(() -> new NoUserFoundException(String.format("No user found with email '%s'.", email)));
//////        User user = userRepo.findByEmail(email).orElseThrow(() -> new NoUserFoundException(String.format("No user found with email '%s'.", email)));
////
////
////
////        return UserMapper.INSTANCE.toUserDTO(user);
////    }
//}