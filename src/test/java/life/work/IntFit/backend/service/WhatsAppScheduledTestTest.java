package life.work.IntFit.backend.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Delayed;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Controlled clock, in-memory scheduler and fake sender: no Spring context, threads, HTTP or database. */
class WhatsAppScheduledTestTest {

    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");
    private static final Instant DUE = Instant.parse("2026-10-20T12:15:00Z");
    private static final String PRIVATE_DETAIL = "synthetic-secret 15555550123\nforged-log";
    private final MutableClock clock = new MutableClock();
    private final StubScheduler scheduler = new StubScheduler();
    private final Map<String, String> environment = new HashMap<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(WhatsAppScheduledTest.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private WhatsAppScheduledTest scheduledTest;
    private boolean senderReady = true;
    private int readinessChecks;
    private int sends;
    private RuntimeException senderFailure;
    private WhatsAppTestSendService.SendResult result = new WhatsAppTestSendService.SendResult(
            HttpStatus.ACCEPTED, "accepted", "wamid.scheduled-example", "Never log this result message: " + PRIVATE_DETAIL);
    private Level previousLevel;
    private boolean previousAdditive;

    @BeforeEach
    void setUp() {
        environment.put("WHATSAPP_SCHEDULED_TEST_ENABLED", "true");
        environment.put("WHATSAPP_SCHEDULED_TEST_AT", "2026-10-20T15:15:00+03:00");
        previousLevel = logger.getLevel();
        previousAdditive = logger.isAdditive();
        logger.setLevel(Level.INFO);
        logger.setAdditive(false);
        appender.start();
        logger.addAppender(appender);
        rebuild();
    }

    @AfterEach
    void cleanUp() {
        if (scheduledTest != null) scheduledTest.close();
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(previousLevel);
        logger.setAdditive(previousAdditive);
        assertThat(logs()).doesNotContain("synthetic-secret", "15555550123", "forged-log", "Never log this result message");
    }

    private void rebuild() {
        scheduledTest = new WhatsAppScheduledTest(clock, scheduler, () -> {
            readinessChecks++;
            return senderReady;
        }, () -> {
            sends++;
            if (senderFailure != null) throw senderFailure;
            return result;
        }, environment::get);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"false", "yes", " true ", "0"})
    void defaultsDisabledAndDoesNotInitializeAnExecutor(String value) {
        environment.put("WHATSAPP_SCHEDULED_TEST_ENABLED", value);
        rebuild();
        scheduledTest.scheduleOnce();
        assertNothingRegistered();
        assertThat(readinessChecks).isZero();
        assertThat(logs()).contains("reason=disabled");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void missingTimestampDoesNotAffectStartup(String value) {
        environment.put("WHATSAPP_SCHEDULED_TEST_AT", value);
        rebuild();
        scheduledTest.scheduleOnce();
        assertNothingRegistered();
        assertThat(logs()).contains("reason=missing_timestamp");
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-time", "2026-10-20T15:15:00", "2026-10-20", "2026-10-20T15:15:00[Asia/Hebron]",
            "2026-10-20T15:15:00+99:00", "synthetic-secret 15555550123\nforged-log"})
    void malformedOrOffsetFreeTimestampIsSkippedWithoutLoggingItsValue(String value) {
        environment.put("WHATSAPP_SCHEDULED_TEST_AT", value);
        rebuild();
        scheduledTest.scheduleOnce();
        assertNothingRegistered();
        assertThat(logs()).contains("reason=invalid_timestamp_requires_offset");
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-10-20T11:59:59Z", "2026-10-20T12:00:00Z", "2026-10-20T15:00:00+03:00"})
    void pastOrPresentTimestampNeverCatchesUp(String value) {
        environment.put("WHATSAPP_SCHEDULED_TEST_AT", value);
        rebuild();
        scheduledTest.scheduleOnce();
        assertNothingRegistered();
        assertThat(logs()).contains("reason=past_or_present_timestamp");
    }

    @Test
    void unavailableSenderSkipsRegistration() {
        senderReady = false;
        scheduledTest.scheduleOnce();
        assertNothingRegistered();
        assertThat(logs()).contains("reason=sender_unavailable");
    }

    @Test
    void futureOffsetTimestampRegistersOnceAndDoesNotSendAtStartup() {
        scheduledTest.scheduleOnce();
        scheduledTest.scheduleOnce();
        assertThat(scheduler.initializations).isEqualTo(1);
        assertThat(scheduler.registrations).isEqualTo(1);
        assertThat(scheduler.at).isEqualTo(DUE);
        assertThat(sends).isZero();
        assertThat(logs()).contains("event=scheduled at=2026-10-20T12:15:00Z");
        clock.current = DUE;
        scheduler.task.run();
        scheduler.task.run();
        assertThat(sends).isEqualTo(1);
        assertThat(logs()).contains("event=attempt", "event=accepted message_id=wamid.scheduled-example");
    }

    @Test
    void utcZuluTimestampIsAnExplicitOffset() {
        environment.put("WHATSAPP_SCHEDULED_TEST_AT", DUE.toString());
        rebuild();
        scheduledTest.scheduleOnce();
        assertThat(scheduler.at).isEqualTo(DUE);
        assertThat(sends).isZero();
    }

    @Test
    void rechecksClockAfterInitializationBeforeRegistering() {
        scheduler.onInitialize = () -> clock.current = DUE;
        scheduledTest.scheduleOnce();
        assertThat(scheduler.initializations).isEqualTo(1);
        assertThat(scheduler.registrations).isZero();
        assertThat(scheduler.shutdowns).isEqualTo(1);
        assertThat(sends).isZero();
        assertThat(logs()).contains("reason=timestamp_elapsed_before_registration");
    }

    @Test
    void inlineCallbackDuringRegistrationCannotSendAsAStartupAction() {
        scheduler.inlineCallback = true;
        scheduledTest.scheduleOnce();
        assertThat(sends).isZero();
    }

    @Test
    void earlyCallbackIsNotSentOrRetried() {
        scheduledTest.scheduleOnce();
        scheduler.task.run();
        clock.current = DUE;
        scheduler.task.run();
        assertThat(sends).isZero();
        assertThat(logs()).contains("reason=early_callback");
    }

    @Test
    void callbackMoreThanSixtySecondsLateIsNotCaughtUp() {
        scheduledTest.scheduleOnce();
        clock.current = DUE.plusSeconds(61);
        scheduler.task.run();
        assertThat(sends).isZero();
        assertThat(logs()).contains("reason=missed_deadline");
    }

    @Test
    void ordinaryDispatchLatencyWithinSixtySecondsStillAllowsOneAttempt() {
        scheduledTest.scheduleOnce();
        clock.current = DUE.plusSeconds(60);
        scheduler.task.run();
        assertThat(sends).isEqualTo(1);
    }

    @Test
    void senderGateIsCheckedAgainAtExecution() {
        scheduledTest.scheduleOnce();
        senderReady = false;
        clock.current = DUE;
        scheduler.task.run();
        senderReady = true;
        scheduler.task.run();
        assertThat(sends).isZero();
        assertThat(logs()).contains("reason=sender_unavailable_at_execution");
    }

    @ParameterizedTest
    @ValueSource(strings = {"unavailable", "provider_error", "transport_error", "timeout", "synthetic-secret 15555550123\nforged-log"})
    void senderFailureHasSafeOutcomeAndNoRetry(String outcome) {
        result = new WhatsAppTestSendService.SendResult(HttpStatus.BAD_GATEWAY, outcome, null, PRIVATE_DETAIL);
        scheduledTest.scheduleOnce();
        clock.current = DUE;
        scheduler.task.run();
        scheduler.task.run();
        assertThat(sends).isEqualTo(1);
        assertThat(scheduler.registrations).isEqualTo(1);
        assertThat(logs()).contains("event=failure outcome=");
    }

    @Test
    void unexpectedSenderExceptionIsNotLoggedOrRetried() {
        senderFailure = new IllegalStateException(PRIVATE_DETAIL);
        scheduledTest.scheduleOnce();
        clock.current = DUE;
        scheduler.task.run();
        scheduler.task.run();
        assertThat(sends).isEqualTo(1);
        assertThat(logs()).contains("outcome=unexpected_sender_failure");
    }

    @Test
    void nullSenderResultIsSafe() {
        result = null;
        scheduledTest.scheduleOnce();
        clock.current = DUE;
        scheduler.task.run();
        assertThat(logs()).contains("outcome=invalid_sender_result");
    }

    @Test
    void returnedMessageIdMustBeBoundedAndCannotInjectALogLine() {
        result = new WhatsAppTestSendService.SendResult(HttpStatus.ACCEPTED, "accepted", "wamid.\nforged-log", PRIVATE_DETAIL);
        scheduledTest.scheduleOnce();
        clock.current = DUE;
        scheduler.task.run();
        assertThat(logs()).contains("outcome=invalid_sender_result");
    }

    @Test
    void numericRunsInMessageIdsAreMaskedBeforeLogging() {
        result = new WhatsAppTestSendService.SendResult(HttpStatus.ACCEPTED, "accepted", "wamid.15555550123", PRIVATE_DETAIL);
        scheduledTest.scheduleOnce();
        clock.current = DUE;
        scheduler.task.run();
        assertThat(logs()).contains("message_id=wamid.[redacted-number]");
    }

    @Test
    void schedulerRejectionDoesNotAffectStartupAndCleansUp() {
        scheduler.reject = true;
        scheduledTest.scheduleOnce();
        assertThat(scheduler.shutdowns).isEqualTo(1);
        assertThat(sends).isZero();
        assertThat(logs()).contains("reason=scheduler_or_sender_unavailable");
    }

    @Test
    void shutdownCancelsPendingWorkAndClosesExecutorOnce() {
        scheduledTest.scheduleOnce();
        scheduledTest.close();
        scheduledTest.close();
        assertThat(scheduler.future.isCancelled()).isTrue();
        assertThat(scheduler.shutdowns).isEqualTo(1);
        clock.current = DUE;
        scheduler.task.run();
        scheduledTest.scheduleOnce();
        assertThat(sends).isZero();
        assertThat(scheduler.registrations).isEqualTo(1);
    }

    @Test
    void shutdownBeforeReadyNeverRegistersWork() {
        scheduledTest.close();
        scheduledTest.scheduleOnce();
        assertNothingRegistered();
    }

    private void assertNothingRegistered() {
        assertThat(scheduler.initializations).isZero();
        assertThat(scheduler.registrations).isZero();
        assertThat(sends).isZero();
    }

    private String logs() {
        return String.join("\n", appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
    }

    private static final class MutableClock extends Clock {
        private Instant current = NOW;
        @Override public ZoneId getZone() { return ZoneId.of("Asia/Hebron"); }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(current, zone); }
        @Override public Instant instant() { return current; }
    }

    private static final class StubScheduler extends ThreadPoolTaskScheduler {
        private int initializations;
        private int registrations;
        private int shutdowns;
        private Instant at;
        private Runnable task;
        private Runnable onInitialize = () -> { };
        private boolean inlineCallback;
        private boolean reject;
        private final StubFuture future = new StubFuture();

        @Override public void initialize() {
            initializations++;
            onInitialize.run();
        }
        @Override public ScheduledFuture<?> schedule(Runnable task, Instant at) {
            registrations++;
            this.task = task;
            this.at = at;
            if (reject) throw new IllegalStateException(PRIVATE_DETAIL);
            if (inlineCallback) task.run();
            return future;
        }
        @Override public void shutdown() { shutdowns++; }
    }

    private static final class StubFuture extends FutureTask<Void> implements ScheduledFuture<Void> {
        private StubFuture() { super(() -> null); }
        @Override public long getDelay(TimeUnit unit) { return unit.convert(Duration.ofMinutes(15).toMillis(), TimeUnit.MILLISECONDS); }
        @Override public int compareTo(Delayed other) { return 0; }
    }
}
