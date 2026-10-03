package fnord.dedup.web

import org.springframework.http.HttpStatus

class ApiFailure extends RuntimeException {
    final String code
    final HttpStatus status

    ApiFailure(String code, HttpStatus status, String message, Throwable cause = null) {
        super(message, cause)
        this.code = code
        this.status = status
    }
}
