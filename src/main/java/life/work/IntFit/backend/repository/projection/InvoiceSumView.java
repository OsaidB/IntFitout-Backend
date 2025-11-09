// File: src/main/java/life/work/IntFit/backend/repository/projection/InvoiceSumView.java
package life.work.IntFit.backend.repository.projection;

import java.math.BigDecimal;

public interface InvoiceSumView {
    Long getCount();
    BigDecimal getTotal();
}
