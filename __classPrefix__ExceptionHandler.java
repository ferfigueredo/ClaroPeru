package ${package}.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.Builder;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import com.claro.common.claro.exceptions.dto.ErrorDTO;
#if( $includeRestClient == 'true' )
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import ${package}.dto.response.ErrorResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
#end
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Locale;

@Slf4j
@ControllerAdvice
public class ${classPrefix}ExceptionHandler {
    public static final String ERROR = "ERROR";
    public static final String SERVICE_ERROR = "${classPrefix}_SERVICE_ERROR";

#if( $includeRestClient == 'true' )
    @ExceptionHandler(RestClientResponseException.class)
    public ResponseEntity<ErrorResponse> restClientError(RestClientResponseException ex) {
        var errorResponse = this.errorRestClientResponse(ex);
        log.error("ExceptionHandler restClientError: {} ", errorResponse);
        return ResponseEntity.status(ex.getStatusCode()).body(errorResponse);
    }

    private ErrorResponse errorRestClientResponse(RestClientResponseException restClientResponseException) {
        try {
            var responseBody = restClientResponseException.getResponseBodyAsString();
            var mapper = new ObjectMapper();
            return mapper.readValue(responseBody, ErrorResponse.class);
        } catch (JsonProcessingException ex) {
            log.error("ExceptionHandler errorRestClientResponse: {}", ex.getMessage());
            return ErrorResponse.builder()
                    .message(restClientResponseException.getStatusText())
                    .code(restClientResponseException.getStatusCode().value())
                    .build();
        }
    }
    
    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<ErrorResponse> resourceAccessError(ResourceAccessException ex) {
        var errorResponse = ErrorResponse.builder()
                .message(ex.getMessage())
                .code(HttpStatus.INTERNAL_SERVER_ERROR.value())
                .build();
        log.error("ExceptionHandler resourceAccessError: {} ", ex.getMessage());
        return ResponseEntity.status(errorResponse.getCode()).body(errorResponse);
    }


    private ErrorDTO parseDownstreamError(RestClientResponseException ex) {
        try {
            var json = ex.getResponseBodyAsString();
            return new ObjectMapper().readValue(json, ErrorDTO.class);
        } catch (JsonProcessingException e) {
            return ErrorDTO.builder().code(ex.getStatusCode().value()).status(HttpStatus.valueOf(ex.getStatusCode().value()).name())
                    .message(SERVICE_ERROR).detail(ex.getStatusText()).type(ex.getClass().getSimpleName()).subType("DOWNSTREAM_HTTP").build();

        }
    }


    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<ErrorDTO> resourceAccessError(ResourceAccessException ex) {
        log.error("Resource access error", ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "IO_TIMEOUT", "I/O error while calling downstream service: " + ex.getMessage(), ex.getClass().getSimpleName());
    }
#end

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorDTO> handleBodyValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        var items = ex.getBindingResult().getFieldErrors().stream().map(fe -> FieldErrorItem.builder().field(fe.getField()).code(mapCode(fe.getCode())).message(fe.getDefaultMessage()).build()).toList();

        boolean hasMandatory = ex.getBindingResult().getFieldErrors().stream().anyMatch(fe -> "NotNull".equals(fe.getCode()) || "NotBlank".equals(fe.getCode()));

        String detail = "Validation failed for request body: %s".formatted(items);
        return hasMandatory ? mandatory("REQUEST_BODY", detail, ex.getClass().getSimpleName()) : invalid("REQUEST_BODY", detail, ex.getClass().getSimpleName());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorDTO> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest req) {
        var items = ex.getConstraintViolations().stream().map(this::toFieldError).toList();

        boolean hasMandatory = ex.getConstraintViolations().stream().anyMatch(v -> {
            String ann = v.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName();
            return "NotNull".equals(ann) || "NotBlank".equals(ann);
        });

        String detail = "Validation failed for request parameters: " + items;
        return hasMandatory ? mandatory("REQUEST_PARAMS", detail, ex.getClass().getSimpleName()) : invalid("REQUEST_PARAMS", detail, ex.getClass().getSimpleName());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorDTO> handleMissingParam(MissingServletRequestParameterException ex, HttpServletRequest req) {
        return mandatory("IDF1", "Missing required parameter: " + ex.getParameterName(), ex.getClass().getSimpleName());
    }

    @ExceptionHandler(MissingPathVariableException.class)
    public ResponseEntity<ErrorDTO> handleMissingPathVar(MissingPathVariableException ex, HttpServletRequest req) {
        return mandatory("MISSING_PATH_VARIABLE", "Missing path variable: " + ex.getVariableName(), ex.getClass().getSimpleName());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorDTO> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest req) {
        String required = ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "expected type";
        return invalid("IDF1", "Parameter '%s' must be of type %s".formatted(ex.getName(), required), ex.getClass().getSimpleName());
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<ErrorDTO> handleBindException(BindException ex, HttpServletRequest req) {
        var items = ex.getBindingResult().getFieldErrors().stream().map(fe -> FieldErrorItem.builder().field(fe.getField()).code(mapCode(fe.getCode())).message(fe.getDefaultMessage()).build()).toList();
        return invalid("BIND_ERROR", "Parameter binding failed: " + items, ex.getClass().getSimpleName());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorDTO> handleNotReadable(HttpMessageNotReadableException ex, HttpServletRequest req) {
        return invalid("REQUEST_BODY", "Malformed JSON request body", ex.getClass().getSimpleName());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorDTO> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex, HttpServletRequest req) {
        return error(HttpStatus.METHOD_NOT_ALLOWED, "HTTP", ex.getMessage(), ex.getClass().getSimpleName());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorDTO> handleUnsupportedMedia(HttpMediaTypeNotSupportedException ex, HttpServletRequest req) {
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "HTTP", ex.getMessage(), ex.getClass().getSimpleName());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorDTO> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest req) {
        String detail = ex.getMessage();
        if (detail != null && detail.startsWith("No enum constant")) {
            String[] parts = detail.split("\\.");
            String badValue = parts[parts.length - 1];
            detail = "Invalid enum value '" + badValue + "'. Please use a valid option.";
        }
        return invalid("IDF1", detail != null ? detail : "Invalid argument", ex.getClass().getSimpleName());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorDTO> handleGeneric(Exception ex, HttpServletRequest req) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.INTERNAL_SERVER_ERROR.name(), "internal", "An unexpected error occurred");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorDTO> handleNoResource(NoResourceFoundException ex, HttpServletRequest req) {
        var body = ErrorDTO.builder().code(HttpStatus.NOT_FOUND.value()).status(HttpStatus.NOT_FOUND.name()).message("RESOURCE_NOT_FOUND").detail("No static resource for %s %s".formatted(ex.getHttpMethod(), ex.getResourcePath())).build();
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }


    /* ===================== helpers ===================== */

    private ResponseEntity<ErrorDTO> error(HttpStatus http, String subType, String detail, String type) {
        return ResponseEntity.status(http).body(ErrorDTO.builder().code(http.value()).status(http.name()).message(SERVICE_ERROR).detail(detail).type(type).subType(subType).build());
    }

    private ResponseEntity<ErrorDTO> invalid(String subType, String detail, String type) {
        return error(HttpStatus.BAD_REQUEST, subType, detail, type);
    }

    private ResponseEntity<ErrorDTO> mandatory(String subType, String detail, String type) {
        return error(HttpStatus.BAD_REQUEST, subType, detail, type);
    }


    private FieldErrorItem toFieldError(ConstraintViolation<?> v) {
        String path = v.getPropertyPath() != null ? v.getPropertyPath().toString() : "";
        String field = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
        return FieldErrorItem.builder().field(field).code(mapCode(v.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName())).message(v.getMessage()).build();
    }

    private String mapCode(String raw) {
        if (raw == null) {
            return "INVALID";
        }
        return switch (raw) {
            case "NotNull", "NotBlank" -> "REQUIRED";
            case "Min" -> "MIN";
            case "Max" -> "MAX";
            case "Size" -> "SIZE";
            case "Pattern" -> "PATTERN";
            default -> raw.toUpperCase(Locale.ROOT);
        };
    }

    @Value
    @Builder
    public static class FieldErrorItem {
        String field;
        String code;
        String message;
    }
}