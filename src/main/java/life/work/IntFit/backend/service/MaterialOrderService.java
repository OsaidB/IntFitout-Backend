package life.work.IntFit.backend.service;

import life.work.IntFit.backend.dto.MaterialOrderDTO;
import life.work.IntFit.backend.mapper.MaterialOrderMapper;
import life.work.IntFit.backend.model.entity.MaterialOrder;
import life.work.IntFit.backend.repository.MaterialOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MaterialOrderService {
    private final MaterialOrderRepository materialOrderRepository;
    private final MaterialOrderMapper materialOrderMapper;

    public List<MaterialOrderDTO> getAllOrders() {
        List<MaterialOrder> orders = materialOrderRepository.findAll();
        return materialOrderMapper.toDTOList(orders);
    }

    public MaterialOrderDTO addOrder(MaterialOrderDTO materialOrderDTO) {
        MaterialOrder materialOrder = materialOrderMapper.toEntity(materialOrderDTO);
        MaterialOrder savedOrder = materialOrderRepository.save(materialOrder);
        return materialOrderMapper.toDTO(savedOrder);
    }
}
