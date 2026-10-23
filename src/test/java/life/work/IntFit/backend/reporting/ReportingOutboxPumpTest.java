package life.work.IntFit.backend.reporting;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ReportingOutboxPumpTest {
    @Test void disabledPumpDoesNotCreateSchedulerOrReadStore() {
        var settings=new ReportingSettings(k -> null);
        var pump=new ReportingOutboxPump(settings,null,() -> {fail("No scheduler while disabled");return null;});
        pump.start();pump.stop();
    }
    @Test void controlledPumpProcessesExistingIntentsOnceAndCancelsOnShutdown() {
        var settings=new ReportingSettings(ReportingServiceTest.environment()::get);
        var store=new MemoryReportingStore();store.workers.put(7L,new ReportingModel.Worker(7,"عامل",true,"970599000007"));
        store.choices.put(11L,new ReportingModel.Choice(11,"موقع"));store.selectable.add(11L);
        AtomicInteger sends=new AtomicInteger();
        var service=new ReportingService(store,settings,p -> {sends.incrementAndGet();return new ReportingSender.Result("REJECTED",null);},new ReportingServiceTest.MutableClock(),new ObjectMapper());
        FakeScheduler scheduler=new FakeScheduler();AtomicInteger factories=new AtomicInteger();
        var pump=new ReportingOutboxPump(settings,service,() -> {factories.incrementAndGet();return scheduler;});
        pump.start();pump.start();assertEquals(1,factories.get());assertEquals(5,scheduler.delay);assertEquals(2,scheduler.interval);assertEquals(0,sends.get());
        scheduler.task.run();assertEquals(0,sends.get());assertTrue(store.requests.isEmpty()); // never creates a daily request
        service.initiate(LocalDate.of(2026,10,22));scheduler.task.run();scheduler.task.run();assertEquals(1,sends.get());
        pump.stop();assertTrue(scheduler.stopped);pump.stop();
    }
    static final class FakeScheduler extends AbstractExecutorService implements ScheduledExecutorService {
        Runnable task;long delay,interval;boolean stopped;
        @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task,long initial,long interval,TimeUnit unit){this.task=task;delay=unit.toSeconds(initial);this.interval=unit.toSeconds(interval);return null;}
        @Override public void shutdown(){stopped=true;}@Override public List<Runnable> shutdownNow(){stopped=true;return List.of();}
        @Override public boolean isShutdown(){return stopped;}@Override public boolean isTerminated(){return stopped;}@Override public boolean awaitTermination(long timeout,TimeUnit unit){return stopped;}
        @Override public void execute(Runnable task){throw new AssertionError("No threads in tests");}
        @Override public ScheduledFuture<?> schedule(Runnable r,long d,TimeUnit u){throw new AssertionError();}
        @Override public <V>ScheduledFuture<V> schedule(Callable<V> c,long d,TimeUnit u){throw new AssertionError();}
        @Override public ScheduledFuture<?> scheduleAtFixedRate(Runnable r,long d,long p,TimeUnit u){throw new AssertionError();}
    }
}
