package life.work.IntFit.backend.supplier;

/**
 * Vocabulary for the AL-Eatimad supplier-payment tracker.
 *
 * Cheques are treated as guaranteed, so the tracker models only what it needs:
 *
 *   issuer   - who wrote the cheque (a client, or us)
 *   custody  - who holds it, kept as handover history rather than as a balance input
 *
 * Bank clearance is deliberately absent. A handed-over cheque is paid, like cash, and the
 * rare bounce is handled outside the app.
 */
public final class SupplierEnums {
    private SupplierEnums() { }

    public enum Method {
        /** Real money moved from us to the supplier. */
        CASH, BANK,
        /** An existing cheque written by a client, handed on to the supplier. */
        CLIENT_CHEQUE,
        /** A cheque we wrote ourselves. */
        OWN_CHEQUE
    }

    /** NULL on legacy rows. Never inferred from fromWhom text; an administrator confirms it. */
    public enum IssuerType { CLIENT, OWN }

    /**
     * Who holds the cheque. NULL on legacy rows means "not known", which is different from
     * "we hold it". Used for availability and handover history, never to adjust a balance.
     */
    public enum Holder { US, SUPPLIER, CLIENT, OTHER }
}
