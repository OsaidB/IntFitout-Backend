package life.work.IntFit.backend.controller.criteria;

// InvoiceSpecs.java
import life.work.IntFit.backend.model.entity.Invoice;
import org.springframework.data.jpa.domain.Specification;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;

import java.time.LocalDateTime;

public class InvoiceSpecs {

    public static Specification<Invoice> dateGte(LocalDateTime from) {
        return (root, q, cb) -> cb.greaterThanOrEqualTo(root.get("date"), from);
    }

    public static Specification<Invoice> dateLt(LocalDateTime toExcl) {
        return (root, q, cb) -> cb.lessThan(root.get("date"), toExcl);
    }

    public static Specification<Invoice> worksiteIdEq(Long worksiteId) {
        return (root, q, cb) -> cb.equal(root.get("worksite").get("id"), worksiteId);
    }

    public static Specification<Invoice> masterWorksiteIdEq(Long masterId) {
        return (root, q, cb) -> {
            Join<Object, Object> ws = root.join("worksite", JoinType.LEFT);
            return cb.equal(ws.get("masterWorksiteId"), masterId);
        };
    }

    public static Specification<Invoice> freeText(String raw) {
        final String like = "%" + raw.trim().toLowerCase() + "%";
        return (root, q, cb) -> {
            Join<Object, Object> ws = root.join("worksite", JoinType.LEFT);
            return cb.or(
                    cb.like(cb.lower(root.get("pdfUrl")), like),
                    cb.like(cb.lower(ws.get("name")), like),
                    // search by id as text too
                    cb.like(cb.lower(cb.concat("", root.get("id").as(String.class))), like)
            );
        };
    }
}
