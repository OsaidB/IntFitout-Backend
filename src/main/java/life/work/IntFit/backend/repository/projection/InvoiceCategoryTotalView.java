package life.work.IntFit.backend.repository.projection;

import life.work.IntFit.backend.model.enums.MaterialCategory;

public interface InvoiceCategoryTotalView {
    MaterialCategory getCategory(); // can be null (material/category missing)
    Double getTotal();              // sum of item totals (InvoiceItem uses Double)
}
