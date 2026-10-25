package life.work.IntFit.backend.reporting;

public interface ReportingSender {
    /** No retries. A transport error or unrecognizable success means UNCERTAIN, never safe to resend. */
    Result send(String generatedPayload);
    default Result send(ReportingModel.Outbound outbound) { return send(outbound.payload()); }
    record Result(String state, String metaId) { }
}
