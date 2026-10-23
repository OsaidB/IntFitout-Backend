package life.work.IntFit.backend.reporting;

public interface ReportingSender {
    /** No retries. A transport error or unrecognizable success means UNCERTAIN, never safe to resend. */
    Result send(String generatedPayload);
    record Result(String state, String metaId) { }
}
