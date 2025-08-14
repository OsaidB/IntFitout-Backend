//package life.work.IntFit.backend.mapper;
//
//import life.work.IntFit.backend.dto.UserDTO;
//import life.work.IntFit.backend.model.entity.User;
//import org.mapstruct.Mapper;
//import org.mapstruct.factory.Mappers;
//import org.mapstruct.Mapping;
//
//@Mapper(componentModel = "spring")
//public interface UserMapper {
//
//    UserMapper INSTANCE = Mappers.getMapper(UserMapper.class);
//
//    @Mapping(source = "id", target = "userId")
//    @Mapping(source = "role", target = "role") // Mapping UserRole enum to UserRole
//    @Mapping(target = "createdAt", ignore = true) // Ignoring createdAt and updatedAt for user creation
//    @Mapping(target = "updatedAt", ignore = true)
//    UserDTO toUserDTO(User user);
//
//    @Mapping(source = "userId", target = "id")
//    @Mapping(source = "role", target = "role") // Mapping UserRole enum back to UserRole
//    @Mapping(target = "password", ignore = true) // Ignore password for DTO to Entity conversion
//    User toUserEntity(UserDTO userDTO);
//}
