package com.bizlama.api.receipts;

import com.bizlama.api.config.WorkspaceProperties;
import com.bizlama.api.domain.ReceiptImport;
import com.bizlama.api.stock.ExpiryProvenance;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReceiptQueryService {

    private final JdbcClient jdbc;
    private final WorkspaceProperties workspace;

    public ReceiptQueryService(
            JdbcClient jdbc,
            WorkspaceProperties workspace
    ) {
        this.jdbc = jdbc;
        this.workspace = workspace;
    }

    public List<ReceiptView> list() {
        return jdbc.sql("""
                        SELECT id, original_filename, object_uri, status,
                               merchant, purchase_date, total, created_at,
                               version, failure_code, failure_message, confirmed_at
                        FROM receipt_imports
                        WHERE kitchen_id = :kitchen
                          AND location_id = :location
                        ORDER BY created_at DESC
                        """)
                .param("kitchen", workspace.kitchenId())
                .param("location", workspace.locationId())
                .query(this::map)
                .list();
    }

    public Optional<ReceiptView> find(String id) {
        return jdbc.sql("""
                        SELECT id, original_filename, object_uri, status,
                               merchant, purchase_date, total, created_at,
                               version, failure_code, failure_message, confirmed_at
                        FROM receipt_imports
                        WHERE id = :id
                          AND kitchen_id = :kitchen
                          AND location_id = :location
                        """)
                .param("id", id)
                .param("kitchen", workspace.kitchenId())
                .param("location", workspace.locationId())
                .query(this::map)
                .optional();
    }

    public ReceiptView require(String id) {
        return find(id).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Receipt not found."
        ));
    }

    private ReceiptView map(ResultSet receipt, int row) throws SQLException {
        String id = receipt.getString("id");
        List<ReceiptView.Line> items = jdbc.sql("""
                        SELECT id, raw_name, ingredient_id, canonical_name,
                               quantity, unit, source_quantity, source_unit,
                               unit_price, confidence, selected, expires_at,
                               expiry_provenance, review_status
                        FROM receipt_items item
                        WHERE item.receipt_id = :receipt
                          AND EXISTS (
                            SELECT 1
                            FROM receipt_imports receipt
                            WHERE receipt.id = item.receipt_id
                              AND receipt.kitchen_id = :kitchen
                              AND receipt.location_id = :location)
                        ORDER BY item.id
                        """)
                .param("receipt", id)
                .param("kitchen", workspace.kitchenId())
                .param("location", workspace.locationId())
                .query((line, lineNumber) -> new ReceiptView.Line(
                        line.getString("id"),
                        line.getString("raw_name"),
                        line.getString("ingredient_id"),
                        line.getString("canonical_name"),
                        line.getBigDecimal("quantity"),
                        line.getString("unit"),
                        line.getBigDecimal("source_quantity"),
                        line.getString("source_unit"),
                        line.getBigDecimal("unit_price"),
                        line.getBigDecimal("confidence"),
                        line.getBoolean("selected"),
                        line.getObject("expires_at", LocalDate.class),
                        ExpiryProvenance.valueOf(
                                line.getString("expiry_provenance")
                        ),
                        ReceiptView.ReviewStatus.valueOf(
                                line.getString("review_status")
                        )
                ))
                .list();

        OffsetDateTime confirmed = receipt.getObject(
                "confirmed_at",
                OffsetDateTime.class
        );
        return new ReceiptView(
                id,
                receipt.getString("original_filename"),
                receipt.getString("object_uri"),
                ReceiptImport.Status.valueOf(receipt.getString("status")),
                receipt.getString("merchant"),
                receipt.getObject("purchase_date", LocalDate.class),
                receipt.getBigDecimal("total"),
                receipt.getObject("created_at", OffsetDateTime.class).toInstant(),
                receipt.getInt("version"),
                receipt.getString("failure_code"),
                receipt.getString("failure_message"),
                confirmed == null ? null : confirmed.toInstant(),
                items
        );
    }
}
