package life.work.IntFit.backend.service;

import life.work.IntFit.backend.dto.DailyLogDTO;
import life.work.IntFit.backend.mapper.DailyLogMapper;
import life.work.IntFit.backend.model.entity.DailyLog;
import life.work.IntFit.backend.repository.DailyLogRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class DailyLogService {
    private final DailyLogRepository dailyLogRepository;
    private final DailyLogMapper dailyLogMapper;

    public DailyLogService(DailyLogRepository dailyLogRepository, DailyLogMapper dailyLogMapper) {
        this.dailyLogRepository = dailyLogRepository;
        this.dailyLogMapper = dailyLogMapper;
    }

    public List<DailyLogDTO> getAllDailyLogs() {
        List<DailyLog> dailyLogs = dailyLogRepository.findAll();
        return dailyLogMapper.toDTOList(dailyLogs);
    }

    public Optional<DailyLogDTO> getDailyLogById(Long id) {
        return dailyLogRepository.findById(id)
                .map(dailyLogMapper::toDTO);
    }

    public DailyLogDTO addDailyLog(DailyLogDTO dailyLogDTO) {
        DailyLog dailyLog = dailyLogMapper.toEntity(dailyLogDTO);
        dailyLog.getWorkSessions().forEach(session -> session.setDailyLog(dailyLog));
        DailyLog savedDailyLog = dailyLogRepository.save(dailyLog);
        return dailyLogMapper.toDTO(savedDailyLog);
    }


    public void deleteDailyLog(Long id) {
        dailyLogRepository.deleteById(id);
    }
}
