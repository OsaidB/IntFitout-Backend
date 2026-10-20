package life.work.IntFit.backend.service;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** In-memory, one-shot prototype. Overlapping instances can each send; there is no durable deduplication. */
@Component
public class WhatsAppScheduledTest {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppScheduledTest.class);
    private static final Duration MAX_START_DELAY = Duration.ofSeconds(60);
    private static final Pattern MESSAGE_ID = Pattern.compile("wamid\\.[A-Za-z0-9._:+/=-]{1,512}");
    private static final Set<String> FAILURE_OUTCOMES = Set.of("unavailable", "timeout", "transport_error", "provider_error");

    private final Clock clock;
    private final ThreadPoolTaskScheduler scheduler;
    private final BooleanSupplier senderReady;
    private final Supplier<WhatsAppTestSendService.SendResult> send;
    private final boolean enabled;
    private final String configuredAt;
    private final AtomicBoolean attempted = new AtomicBoolean();
    private ScheduledFuture<?> pending;
    private boolean considered;
    private boolean initialized;
    private boolean registered;
    private boolean closed;

    @Autowired
    public WhatsAppScheduledTest(WhatsAppTestSendService sender, Clock clock) {
        this(clock, dedicatedScheduler(), sender::readyForScheduledTest, sender::sendScheduledTest, System::getenv);
    }

    // Test collaborators use no executor threads, HTTP transport or real environment values.
    WhatsAppScheduledTest(Clock clock, ThreadPoolTaskScheduler scheduler, BooleanSupplier senderReady,
                         Supplier<WhatsAppTestSendService.SendResult> send, Function<String, String> environment) {
        this.clock = clock;
        this.scheduler = scheduler;
        this.scheduler.setClock(clock);
        this.senderReady = senderReady;
        this.send = send;
        this.enabled = "true".equalsIgnoreCase(environment.apply("WHATSAPP_SCHEDULED_TEST_ENABLED"));
        this.configuredAt = environment.apply("WHATSAPP_SCHEDULED_TEST_AT");
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void scheduleOnce() {
        if (closed || considered) return;
        considered = true;
        if (!enabled) {
            skipped("disabled");
            return;
        }
        if (configuredAt == null || configuredAt.isBlank()) {
            skipped("missing_timestamp");
            return;
        }
        final Instant at;
        try {
            // OffsetDateTime rejects local timestamps without an explicit offset; Z is accepted as UTC.
            at = OffsetDateTime.parse(configuredAt, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (DateTimeParseException e) {
            skipped("invalid_timestamp_requires_offset");
            return;
        }
        if (!at.isAfter(clock.instant())) {
            skipped("past_or_present_timestamp");
            return;
        }
        try {
            if (!senderReady.getAsBoolean()) {
                skipped("sender_unavailable");
                return;
            }
            scheduler.initialize();
            initialized = true;
            // Initialization or configuration checks may have taken us past the chosen timestamp.
            if (!at.isAfter(clock.instant())) {
                skipped("timestamp_elapsed_before_registration");
                stopScheduler();
                return;
            }
            pending = scheduler.schedule(() -> attemptOnce(at), at);
            if (pending == null) {
                skipped("scheduler_unavailable");
                stopScheduler();
                return;
            }
            registered = true;
            log.info("whatsapp_scheduled_test event=scheduled at={}", at);
        } catch (RuntimeException e) {
            // Never log an exception message/stack: collaborator failures could contain secrets.
            skipped("scheduler_or_sender_unavailable");
            stopScheduler();
        }
    }

    private void attemptOnce(Instant at) {
        synchronized (this) {
            if (closed || !registered || !attempted.compareAndSet(false, true)) return;
        }
        Instant now = clock.instant();
        if (now.isBefore(at)) {
            skipped("early_callback");
            return;
        }
        if (Duration.between(at, now).compareTo(MAX_START_DELAY) > 0) {
            skipped("missed_deadline");
            return;
        }
        try {
            if (!senderReady.getAsBoolean()) {
                skipped("sender_unavailable_at_execution");
                return;
            }
            log.info("whatsapp_scheduled_test event=attempt");
            WhatsAppTestSendService.SendResult result = send.get();
            if (result != null && result.httpStatus() == HttpStatus.ACCEPTED && "accepted".equals(result.status())
                    && result.outgoingMessageId() != null && MESSAGE_ID.matcher(result.outgoingMessageId()).matches()) {
                // The shared sender already rejects IDs containing configured credentials or the recipient.
                // Further mask long numeric runs before logging, and never log the free-text result message.
                String id = result.outgoingMessageId().replaceAll("[0-9]{7,}", "[redacted-number]");
                log.info("whatsapp_scheduled_test event=accepted message_id={}", id);
            } else {
                String outcome = result != null && result.status() != null && FAILURE_OUTCOMES.contains(result.status())
                        ? result.status() : "invalid_sender_result";
                log.warn("whatsapp_scheduled_test event=failure outcome={}", outcome);
            }
        } catch (RuntimeException e) {
            log.warn("whatsapp_scheduled_test event=failure outcome=unexpected_sender_failure");
        }
    }

    private static ThreadPoolTaskScheduler dedicatedScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("whatsapp-scheduled-test-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }

    private static void skipped(String reason) {
        log.info("whatsapp_scheduled_test event=skipped reason={}", reason);
    }

    private void stopScheduler() {
        if (!initialized) return;
        initialized = false;
        try {
            scheduler.shutdown();
        } catch (RuntimeException e) {
            log.warn("whatsapp_scheduled_test event=shutdown outcome=scheduler_cleanup_failed");
        }
    }

    @PreDestroy
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (pending != null) {
            boolean cancelled = pending.cancel(false);
            log.info("whatsapp_scheduled_test event=shutdown pending_task_cancelled={}", cancelled);
        }
        stopScheduler();
    }
}
