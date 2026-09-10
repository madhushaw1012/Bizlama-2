package com.bizlama.api.stock;

import java.time.LocalDate;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Rejects inventory that is asserted to have been purchased after it occurred. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public final class FutureDatedPurchaseException extends IllegalArgumentException {

    public FutureDatedPurchaseException(
            LocalDate purchasedAt,
            LocalDate occurrenceDate
    ) {
        super("Purchase date " + purchasedAt
                + " cannot be after the kitchen-local occurrence date "
                + occurrenceDate + ".");
    }
}
