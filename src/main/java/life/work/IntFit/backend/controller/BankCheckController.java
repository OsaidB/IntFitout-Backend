package life.work.IntFit.backend.controller;

import life.work.IntFit.backend.dto.BankCheckDTO;
import life.work.IntFit.backend.service.BankCheckService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/bank-checks")
@RequiredArgsConstructor
@CrossOrigin("*")
public class BankCheckController {

    private final BankCheckService service;

    @GetMapping
    public List<BankCheckDTO> getAll() {
        return service.getAll();
    }

    @PostMapping
    public BankCheckDTO save(@RequestBody BankCheckDTO dto) {
        return service.save(dto);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
