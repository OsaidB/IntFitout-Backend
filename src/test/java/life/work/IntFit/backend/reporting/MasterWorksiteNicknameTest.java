package life.work.IntFit.backend.reporting;

import com.fasterxml.jackson.databind.ObjectMapper;
import life.work.IntFit.backend.dto.MasterWorksiteDTO;
import life.work.IntFit.backend.mapper.MasterWorksiteMapper;
import life.work.IntFit.backend.model.entity.MasterWorksite;
import life.work.IntFit.backend.repository.*;
import life.work.IntFit.backend.service.MasterWorksiteService;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MasterWorksiteNicknameTest {
    final MasterWorksiteRepository repo=mock(MasterWorksiteRepository.class);
    final MasterWorksiteMapper mapper=Mappers.getMapper(MasterWorksiteMapper.class);
    final MasterWorksiteService service=new MasterWorksiteService(repo,mock(WorksiteRepository.class),mapper,mock(CityRepository.class),mock(AreaRepository.class));
    MasterWorksite entity() {
        MasterWorksite e=MasterWorksite.builder().id(11L).approvedName("Approved business name").nickname("Original nickname").notes("Original notes").subArea("Original sub-area").locationDetails("Original details").estimatedAreaM2(150.0).build();
        when(repo.findById(11L)).thenReturn(Optional.of(e));when(repo.save(any())).thenAnswer(i -> i.getArgument(0));return e;
    }
    @Test void dedicatedNicknameEditAndClearPreserveAllOtherDetails() {
        MasterWorksite e=entity();MasterWorksiteDTO result=service.updateNickname(11L,"  اسم مختصر  ");
        assertEquals("اسم مختصر",result.getNickname());assertEquals("Approved business name",result.getApprovedName());assertEquals("Original notes",result.getNotes());assertEquals("Original sub-area",result.getSubArea());assertEquals(150.0,result.getEstimatedAreaM2());
        service.updateNickname(11L,"");assertNull(e.getNickname());assertEquals("Original details",e.getLocationDetails());
    }
    @Test void olderClientsOmittingOrNullingNicknameDoNotEraseItAndEmptyExplicitlyClears() throws Exception {
        MasterWorksite e=entity();ObjectMapper json=new ObjectMapper();
        service.update(11L,json.readValue("{\"approvedName\":\"Approved business name\"}",MasterWorksiteDTO.class));assertEquals("Original nickname",e.getNickname());
        service.update(11L,json.readValue("{\"approvedName\":\"Approved business name\",\"nickname\":null}",MasterWorksiteDTO.class));assertEquals("Original nickname",e.getNickname());
        service.update(11L,json.readValue("{\"approvedName\":\"Approved business name\",\"nickname\":\" \"}",MasterWorksiteDTO.class));assertNull(e.getNickname());
    }
    @Test void creationAndMapperSupportNicknameWithExistingNameValidation() {
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));MasterWorksiteDTO dto=MasterWorksiteDTO.builder().approvedName("  New group  ").nickname("  Nick  ").build();
        var saved=service.add(dto);assertEquals("Nick",saved.getNickname());assertEquals("New group",saved.getApprovedName());assertEquals("Nick",mapper.toEntity(saved).getNickname());
        dto.setApprovedName(" ");assertThrows(IllegalArgumentException.class,() -> service.add(dto));
        dto.setApprovedName("Existing");when(repo.existsByApprovedNameIgnoreCase("Existing")).thenReturn(true);assertThrows(IllegalArgumentException.class,() -> service.add(dto));
    }
    @Test void invalidNicknameRejectedWithoutChangingExistingData() {
        MasterWorksite e=entity();for(String bad:new String[]{"x".repeat(81),"bad\nvalue","bad\u202evalue"})assertThrows(IllegalArgumentException.class,() -> service.updateNickname(11L,bad));
        assertEquals("Original nickname",e.getNickname());verify(repo,never()).save(any());
    }
}
