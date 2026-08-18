package ch.ahdis.matchbox.validation.matchspark;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * A POJO class used to unserialize most of the error responses from LLM providers.
 * It's a best-effort attempt to extract the error message itself from the whole payload.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class LLMErrorMessage {

    // Common top-level message some providers return
    private String message;

    // Some providers return an "error" object instead of a top-level message
    private ErrorObject error;

    // Auxiliary common fields
    private String type;
    private String code;
    private String status;

    @JsonProperty("documentation_url")
    private String documentationUrl;

    @JsonProperty("request_id")
    private String requestId;

    // Any other vendor-specific fields can be captured here
    private Map<String, Object> details;

    public LLMErrorMessage() {
    }

    // Getters / setters
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public ErrorObject getError() { return error; }
    public void setError(ErrorObject error) { this.error = error; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getDocumentationUrl() { return documentationUrl; }
    public void setDocumentationUrl(String documentationUrl) { this.documentationUrl = documentationUrl; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public Map<String, Object> getDetails() { return details; }
    public void setDetails(Map<String, Object> details) { this.details = details; }

    @Override
    public String toString() {
        return "LLMErrorMessage{" +
                "message='" + message + '\'' +
                ", error=" + error +
                ", type='" + type + '\'' +
                ", code=" + code +
                ", status='" + status + '\'' +
                ", documentationUrl='" + documentationUrl + '\'' +
                ", requestId='" + requestId + '\'' +
                ", details=" + details +
                '}';
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ErrorObject {
        private String message;
        private String type;
        private String code;

        // Some providers include a list of field-specific errors
        private List<FieldError> errors;

        // Additional arbitrary fields
        private Map<String, Object> info;

        
        public ErrorObject() { }

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }

        public List<FieldError> getErrors() { return errors; }
        public void setErrors(List<FieldError> errors) { this.errors = errors; }

        public Map<String, Object> getInfo() { return info; }
        public void setInfo(Map<String, Object> info) { this.info = info; }

        @Override
        public String toString() {
            return "ErrorObject{" +
                    "message='" + message + '\'' +
                    ", type='" + type + '\'' +
                    ", code=" + code +
                    ", errors=" + errors +
                    ", info=" + info +
                    '}';
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FieldError {
        private String message;
        private String param;
        private String code;

        public FieldError() { }

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }

        public String getParam() { return param; }
        public void setParam(String param) { this.param = param; }

        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }

        @Override
        public String toString() {
            return "FieldError{" +
                    "message='" + message + '\'' +
                    ", param='" + param + '\'' +
                    ", code='" + code + '\'' +
                    '}';
        }
    }
}
