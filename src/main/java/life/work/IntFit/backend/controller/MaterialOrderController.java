package life.work.IntFit.backend.controller;

import life.work.IntFit.backend.dto.MaterialOrderDTO;
import life.work.IntFit.backend.service.MaterialOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/material-orders")
@RequiredArgsConstructor
@CrossOrigin("*")
public class MaterialOrderController {
    private final MaterialOrderService materialOrderService;

    @GetMapping
    public List<MaterialOrderDTO> getAllOrders() {
        return materialOrderService.getAllOrders();
    }

    @PostMapping
    public MaterialOrderDTO addOrder(@RequestBody MaterialOrderDTO materialOrderDTO) {
        return materialOrderService.addOrder(materialOrderDTO);
    }
}
