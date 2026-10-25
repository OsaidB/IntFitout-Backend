package life.work.IntFit.backend.reporting;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

class ReportingLoggingTest {
    @Test void providerExceptionDetailsAndPersonalDataNeverReachReportingLogs() {
        var env=ReportingServiceTest.environment();var store=new MemoryReportingStore();
        store.workers.put(7L,new ReportingModel.Worker(7,"اسم شخصي تجريبي",true,"970599000007"));store.choices.put(11L,new ReportingModel.Choice(11,"موقع"));store.selectable.add(11L);
        store.activity.add(new MemoryReportingStore.Activity(7,11,LocalDate.of(2026,10,22)));
        Logger logger=(Logger)LoggerFactory.getLogger(ReportingService.class);ListAppender<ILoggingEvent> appender=new ListAppender<>();appender.start();logger.addAppender(appender);
        try {
            var service=new ReportingService(store,new ReportingSettings(env::get),p -> {throw new IllegalStateException(env.get("WHATSAPP_ACCESS_TOKEN")+" 970599000007 اسم شخصي تجريبي raw provider response");},new ReportingServiceTest.MutableClock(),new ObjectMapper());
            var r=service.initiate(LocalDate.of(2026,10,22));var result=service.dispatch(r.outbound().getFirst().id());assertEquals("UNCERTAIN",result.state());
            String logs=appender.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("",String::concat);
            assertTrue(logs.contains("UNCERTAIN"));assertFalse(logs.contains(env.get("WHATSAPP_ACCESS_TOKEN")));assertFalse(logs.contains("970599000007"));assertFalse(logs.contains("اسم شخصي"));assertFalse(logs.contains("raw provider"));
        } finally {logger.detachAppender(appender);appender.stop();}
    }
    @Test void labelsDropControlCharactersAndPhoneLikeContent() {
        String label=ReportingFlow.label("زميل\n\r\u202E +970599000007",255);
        assertFalse(label.contains("\n"));assertFalse(label.contains("\r"));assertFalse(label.contains("\u202E"));assertFalse(label.contains("970599000007"));
        assertEquals(24,ReportingFlow.label("م".repeat(50),24).codePointCount(0,24));
    }
}
