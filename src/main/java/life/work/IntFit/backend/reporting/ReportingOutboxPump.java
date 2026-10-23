package life.work.IntFit.backend.reporting;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Dispatches existing durable intents only. Does NOT create requests or implement a daily schedule. */
@Component
public class ReportingOutboxPump {
    private static final Logger log=LoggerFactory.getLogger(ReportingOutboxPump.class);
    private final ReportingSettings settings;
    private final ReportingService service;
    private final Supplier<ScheduledExecutorService> schedulerFactory;
    private ScheduledExecutorService executor;
    @Autowired public ReportingOutboxPump(ReportingSettings settings,ReportingService service) {
        this(settings,service,() -> Executors.newSingleThreadScheduledExecutor(r -> { Thread t=new Thread(r,"whatsapp-reporting-outbox"); t.setDaemon(true); return t; }));
    }
    ReportingOutboxPump(ReportingSettings settings,ReportingService service,Supplier<ScheduledExecutorService> schedulerFactory) {
        this.settings=settings; this.service=service; this.schedulerFactory=schedulerFactory;
    }
    @EventListener(ApplicationReadyEvent.class) public synchronized void start() {
        if (!settings.sendingReady() || executor!=null) return;
        executor=schedulerFactory.get();
        executor.scheduleWithFixedDelay(this::runOnce,5,2,TimeUnit.SECONDS);
        log.info("whatsapp_reporting outbox_processor=enabled daily_request_dispatcher=absent");
    }
    void runOnce() {
        try { service.drainOne(); }
        catch (RuntimeException ignored) { log.warn("whatsapp_reporting outbox_pass=failed inspect_persisted_state=true"); }
    }
    @PreDestroy public synchronized void stop() {
        if (executor!=null) {
            executor.shutdownNow();
            // A claimed/in-flight send is never reset to PENDING, even if shutdown interrupts it.
            executor=null;
        }
    }
}
