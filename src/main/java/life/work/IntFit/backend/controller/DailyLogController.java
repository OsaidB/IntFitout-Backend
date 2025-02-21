package life.work.IntFit.backend.controller;

import life.work.IntFit.backend.dto.DailyLogDTO;
import life.work.IntFit.backend.service.DailyLogService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/daily-logs")
@CrossOrigin("*")
public class DailyLogController {
    private final DailyLogService dailyLogService;

    public DailyLogController(DailyLogService dailyLogService) {
        this.dailyLogService = dailyLogService;
    }

    @GetMapping
    public List<DailyLogDTO> getAllDailyLogs() {
        return dailyLogService.getAllDailyLogs();
    }

    @GetMapping("/{id}")
    public Optional<DailyLogDTO> getDailyLogById(@PathVariable Long id) {
        return dailyLogService.getDailyLogById(id);
    }

    @PostMapping
    public DailyLogDTO addDailyLog(@RequestBody DailyLogDTO dailyLogDTO) {
        return dailyLogService.addDailyLog(dailyLogDTO);
    }


    @DeleteMapping("/{id}")
    public void deleteDailyLog(@PathVariable Long id) {
        dailyLogService.deleteDailyLog(id);
    }
}
