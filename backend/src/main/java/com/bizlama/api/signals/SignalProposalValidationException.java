package com.bizlama.api.signals;

final class SignalProposalValidationException extends RuntimeException {

    private final String code;
    private final String claimedProposalId;

    SignalProposalValidationException(String code, String detail) {
        this(code, detail, null, null);
    }

    SignalProposalValidationException(
            String code,
            String detail,
            String claimedProposalId
    ) {
        this(code, detail, claimedProposalId, null);
    }

    SignalProposalValidationException(
            String code,
            String detail,
            String claimedProposalId,
            Throwable cause
    ) {
        super(detail, cause);
        this.code = code;
        this.claimedProposalId = claimedProposalId;
    }

    String code() {
        return code;
    }

    String claimedProposalId() {
        return claimedProposalId;
    }
}
