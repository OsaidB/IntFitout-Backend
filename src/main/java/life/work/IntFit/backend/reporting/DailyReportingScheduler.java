package life.work.IntFit.backend.reporting;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Clock/date decisions live in DailyReportingService. No fixed UTC cron, startup send or missed-day loop. */
@Component
public class DailyReportingScheduler {
    private static final Logger log=LoggerFactory.getLogger(DailyReportingScheduler.class);
    private final ReportingSettings settings;
    private final DailyReportingService service;
    private final Supplier<ScheduledExecutorService> factory;
    private ScheduledExecutorService executor;
    @Autowired public DailyReportingScheduler(ReportingSettings settings,DailyReportingService service) {
        this(settings,service,() -> Executors.newSingleThreadScheduledExecutor(r -> {Thread t=new Thread(r,"whatsapp-reporting-daily");t.setDaemon(true);return t;}));
    }
    DailyReportingScheduler(ReportingSettings settings,DailyReportingService service,Supplier<ScheduledExecutorService> factory) {this.settings=settings;this.service=service;this.factory=factory;}
    @EventListener(ApplicationReadyEvent.class) public synchronized void start() {
        if(!settings.dailyReady() || executor!=null)return;
        executor=factory.get();executor.scheduleWithFixedDelay(this::tick,30,15,TimeUnit.SECONDS);
    }
    void tick() {try{service.tick();}catch(RuntimeException ignored){log.warn("whatsapp_reporting daily_tick=failed inspect_persisted_run=true");}}
    @PreDestroy public synchronized void stop() {if(executor!=null){executor.shutdownNow();executor=null;}}
}
