package com.bizlama.api.signals;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

final class SignalProposalExceptions {
    private SignalProposalExceptions() {
    }
}

@ResponseStatus(HttpStatus.NOT_FOUND)
class SignalProposalNotFoundException extends RuntimeException {
    SignalProposalNotFoundException(String detail) {
        super(detail);
    }
}

@ResponseStatus(HttpStatus.CONFLICT)
class SignalProposalConflictException extends RuntimeException {
    SignalProposalConflictException(String detail) {
        super(detail);
    }
}

@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
class SignalProposalRequestException extends RuntimeException {
    SignalProposalRequestException(String detail) {
        super(detail);
    }
}
