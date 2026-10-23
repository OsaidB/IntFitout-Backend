package life.work.IntFit.backend.reporting;

/** Only fixed, non-provider messages may be exposed to administration callers. */
public final class ReportingProblem extends RuntimeException {
    final int status;
    public ReportingProblem(int status, String message) { super(message); this.status = status; }
}
