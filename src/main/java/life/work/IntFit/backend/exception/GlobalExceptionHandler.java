package life.work.IntFit.backend.exception;

import life.work.IntFit.backend.exception.error.AllocationValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidationExceptions(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach((error) -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });
        return new ResponseEntity<>(errors, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<String> handleResourceNotFoundException(ResourceNotFoundException ex) {
        return new ResponseEntity<>(ex.getMessage(), HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(AllocationValidationException.class)
    public ResponseEntity<Map<String, Object>> handleAllocationValidation(AllocationValidationException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", HttpStatus.UNPROCESSABLE_ENTITY.value());
        body.put("error", "ALLOCATION_VALIDATION_FAILED");
        body.put("message", ex.getMessage());
        body.put("errors", ex.getErrors());
        return new ResponseEntity<>(body, HttpStatus.UNPROCESSABLE_ENTITY);
    }

    // Add other exception handlers if needed
}
