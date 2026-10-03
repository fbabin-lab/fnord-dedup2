package fnord.dedup.web

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class ApiErrors {
    private static final log = LoggerFactory.getLogger(ApiErrors)

    @ExceptionHandler(ApiFailure)
    ResponseEntity<Map<String, Object>> known(ApiFailure error) {
        if (error.status == HttpStatus.LOCKED) log.info('Scanner database lock is held')
        else log.warn('{}: {}', error.code, error.message)
        ResponseEntity.status(error.status).body([code: error.code, message: error.message, details: null])
    }

    @ExceptionHandler(HttpMessageNotReadableException)
    ResponseEntity<Map<String, Object>> malformed(HttpMessageNotReadableException error) {
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body([
            code: 'INVALID_REQUEST', message: 'The JSON request body is not valid.', details: null
        ])
    }

    @ExceptionHandler(Exception)
    ResponseEntity<Map<String, Object>> unexpected(Exception error) {
        log.error('Unexpected API failure', error)
        ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body([
            code: 'INTERNAL_ERROR', message: 'The request could not be completed.', details: null
        ])
    }
}
