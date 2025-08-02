package life.work.IntFit.backend.mapper;

import life.work.IntFit.backend.dto.BankCheckDTO;
import life.work.IntFit.backend.model.entity.BankCheck;
import org.springframework.stereotype.Component;

@Component
public class BankCheckMapper {

    public BankCheckDTO toDTO(BankCheck entity) {
        BankCheckDTO dto = new BankCheckDTO();
        dto.setId(entity.getId());
        dto.setAmount(entity.getAmount());
        dto.setDueDate(entity.getDueDate());
        dto.setRecipientName(entity.getRecipientName());
        dto.setNotes(entity.getNotes());
        dto.setCleared(entity.isCleared());
        dto.setFromWhom(entity.getFromWhom());       // NEW
        dto.setSerialNumber(entity.getSerialNumber()); // NEW
        return dto;
    }

    public BankCheck toEntity(BankCheckDTO dto) {
        return BankCheck.builder()
                .id(dto.getId())
                .amount(dto.getAmount())
                .dueDate(dto.getDueDate())
                .recipientName(dto.getRecipientName())
                .notes(dto.getNotes())
                .cleared(dto.isCleared())
                .fromWhom(dto.getFromWhom())           // NEW
                .serialNumber(dto.getSerialNumber())   // NEW
                .build();
    }
}
