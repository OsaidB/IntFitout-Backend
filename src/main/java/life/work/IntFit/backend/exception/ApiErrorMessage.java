package life.work.IntFit.backend.exception;

//import com.javatab.exception.validators.Violation;
import life.work.IntFit.backend.exception.validators.Violation;
import lombok.Builder;
import lombok.Data;

import java.util.Date;
import java.util.List;

@Data
@Builder
public class ApiErrorMessage {
    private int statusCode;
    private Date timestamp;
    private String message;
    private String description;
    private List<Violation> causes;

}
