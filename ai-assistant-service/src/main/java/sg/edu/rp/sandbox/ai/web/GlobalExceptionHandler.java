package sg.edu.rp.sandbox.ai.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import sg.edu.rp.sandbox.common.error.ApiError;

import java.time.Instant;

/**
 * Maps request-body validation failures to a 400 with the standard error shape
 * {@code { timestamp, status, error, message, path }}, consistent with the other
 * services.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex,
                                                      HttpServletRequest req) {
        String detail = ex.getBindingResult().getAllErrors().stream()
                .findFirst()
                .map(e -> e.getDefaultMessage())
                .orElse("Request validation failed.");
        return badRequest("VALIDATION_FAILED", detail, req);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex,
                                                     HttpServletRequest req) {
        return badRequest("MALFORMED_REQUEST", "The request body could not be read.", req);
    }

    private ResponseEntity<ApiError> badRequest(String code, String message, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError(
                Instant.now(), HttpStatus.BAD_REQUEST.value(), code, message, req.getRequestURI()));
    }
}
