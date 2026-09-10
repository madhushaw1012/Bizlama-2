package com.bizlama.api.events;

import com.bizlama.api.config.JdbcTimestamp;
import com.bizlama.api.config.WorkspaceProperties;
import com.bizlama.api.domain.ReceiptImport;
import com.bizlama.api.store.OperationalRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class KitchenEventProposalService {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final OperationalRepository repository;
    private final KitchenEventApplicationService application;
    private final Duration lifetime;
    private final TransactionTemplate transactions;
    private final WorkspaceProperties workspace;

    public KitchenEventProposalService(
            JdbcClient jdbc,
            ObjectMapper mapper,
            OperationalRepository repository,
            KitchenEventApplicationService application,
            PlatformTransactionManager transactionManager,
            WorkspaceProperties workspace,
            @Value("${bizlama.events.proposal-minutes:15}") long proposalMinutes
    ) {
        if (proposalMinutes <= 0 || proposalMinutes > 1440) {
            throw new IllegalArgumentException(
                    "Kitchen event proposal lifetime must be 1 to 1440 minutes."
            );
        }
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.repository = repository;
        this.application = application;
        this.lifetime = Duration.ofMinutes(proposalMinutes);
        this.transactions = new TransactionTemplate(transactionManager);
        this.workspace = workspace;
    }

    @Transactional
    public Proposal create(
            String originalInput,
            List<ParsedKitchenEvent> events
    ) {
        if (originalInput == null || originalInput.isBlank()) {
            throw invalid("Original kitchen statement is required.");
        }
        if (events == null || events.isEmpty()) {
            throw invalid("At least one parsed kitchen event is required.");
        }

        String id = "KEP-" + UUID.randomUUID()
                .toString()
                .substring(0, 12)
                .toUpperCase();
        Instant createdAt = Instant.now();
        Instant expiresAt = createdAt.plus(lifetime);
        String json = serialize(events);

        jdbc.sql("""
                        INSERT INTO kitchen_event_proposals
                        (id, kitchen_id, location_id, version,
                         original_input, events_json, risk_tier, status,
                         expires_at, created_at)
                        VALUES
                        (:id, :kitchen, :location, 1, :input, :events,
                         'HIGH', 'PENDING', :expiresAt, :createdAt)
                        """)
                .param("id", id)
                .param("kitchen", workspace.kitchenId())
                .param("location", workspace.locationId())
                .param("input", originalInput)
                .param("events", json)
                .param("expiresAt", JdbcTimestamp.utc(expiresAt))
                .param("createdAt", JdbcTimestamp.utc(createdAt))
                .update();

        for (ParsedKitchenEvent event : events) {
            repository.auditAiAction(
                    event.type().name(),
                    originalInput,
                    event.itemId(),
                    event.confidence(),
                    "AWAITING_CONFIRMATION",
                    event.decisionReason()
            );
        }

        return new Proposal(
                id,
                1,
                "HIGH",
                "PENDING",
                expiresAt,
                List.copyOf(events)
        );
    }

    public ConfirmationResult confirm(
            String proposalId,
            int expectedVersion,
            String idempotencyKey,
            String actor
    ) {
        requireText(idempotencyKey, "Idempotency key");
        requireText(actor, "Actor");
        if (idempotencyKey.length() > 200) {
            throw invalid("Idempotency key is too long.");
        }

        return transactions.execute(status -> {
            LockedProposal proposal = lock(proposalId);
            if ("APPLIED".equals(proposal.status())) {
                if (idempotencyKey.equals(
                        proposal.confirmationIdempotencyKey()
                )) {
                    return new ConfirmationResult(
                            proposal.id(),
                            proposal.version(),
                            true,
                            false
                    );
                }
                throw conflict(
                        "Kitchen event proposal was already applied by another request."
                );
            }
            if (!"PENDING".equals(proposal.status())) {
                throw conflict(
                        "Kitchen event proposal is no longer pending."
                );
            }
            if (proposal.version() != expectedVersion) {
                throw conflict(
                        "Kitchen event proposal changed; parse it again."
                );
            }

            Instant now = Instant.now();
            if (!proposal.expiresAt().isAfter(now)) {
                scopedSql("""
                                UPDATE kitchen_event_proposals
                                SET status = 'EXPIRED',
                                    version = version + 1
                                WHERE id = :id
                                  AND kitchen_id = :workspaceKitchen
                                  AND location_id = :workspaceLocation
                                  AND version = :version
                                  AND status = 'PENDING'
                                """)
                        .param("id", proposal.id())
                        .param("version", proposal.version())
                        .update();
                return new ConfirmationResult(
                        proposal.id(),
                        proposal.version() + 1,
                        false,
                        true
                );
            }

            String existing = scopedSql("""
                            SELECT id
                            FROM kitchen_event_proposals
                            WHERE confirmation_idempotency_key = :key
                              AND kitchen_id = :workspaceKitchen
                              AND location_id = :workspaceLocation
                              AND id <> :id
                            """)
                    .param("key", idempotencyKey)
                    .param("id", proposal.id())
                    .query(String.class)
                    .optional()
                    .orElse(null);
            if (existing != null) {
                throw conflict(
                        "Idempotency key is already used by another proposal."
                );
            }

            List<ParsedKitchenEvent> events = deserialize(
                    proposal.eventsJson()
            );
            application.applyAll(events, proposal.id(), now);

            int changed = scopedSql("""
                            UPDATE kitchen_event_proposals
                            SET status = 'APPLIED',
                                applied_at = :now,
                                actor = :actor,
                                confirmation_idempotency_key = :key,
                                version = version + 1
                            WHERE id = :id
                              AND kitchen_id = :workspaceKitchen
                              AND location_id = :workspaceLocation
                              AND version = :version
                              AND status = 'PENDING'
                            """)
                    .param("now", JdbcTimestamp.utc(now))
                    .param("actor", actor)
                    .param("key", idempotencyKey)
                    .param("id", proposal.id())
                    .param("version", proposal.version())
                    .update();
            if (changed != 1) {
                throw conflict(
                        "Kitchen event proposal changed; no action was committed."
                );
            }

            for (ParsedKitchenEvent event : events) {
                repository.auditAiAction(
                        event.type().name(),
                        proposal.originalInput(),
                        event.itemId(),
                        event.confidence(),
                        "OWNER_CONFIRMED",
                        event.decisionReason()
                );
            }

            return new ConfirmationResult(
                    proposal.id(),
                    proposal.version() + 1,
                    false,
                    false
            );
        });
    }

    @Transactional
    public void supersede(
            String proposalId,
            int expectedVersion,
            String actor
    ) {
        requireText(actor, "Actor");
        LockedProposal proposal = lock(proposalId);
        if (!"PENDING".equals(proposal.status())
                || proposal.version() != expectedVersion) {
            throw conflict("Only the current pending proposal can be superseded.");
        }
        int changed = scopedSql("""
                        UPDATE kitchen_event_proposals
                        SET status = 'SUPERSEDED',
                            actor = :actor,
                            version = version + 1
                        WHERE id = :id
                          AND kitchen_id = :workspaceKitchen
                          AND location_id = :workspaceLocation
                          AND version = :version
                          AND status = 'PENDING'
                        """)
                .param("actor", actor)
                .param("id", proposal.id())
                .param("version", proposal.version())
                .update();
        if (changed != 1) {
            throw conflict("Kitchen event proposal changed.");
        }
    }

    private LockedProposal lock(String id) {
        return scopedSql("""
                        SELECT id, version, original_input, events_json,
                               status, expires_at,
                               confirmation_idempotency_key
                        FROM kitchen_event_proposals
                        WHERE id = :id
                          AND kitchen_id = :workspaceKitchen
                          AND location_id = :workspaceLocation
                        FOR UPDATE
                        """)
                .param("id", id)
                .query((rs, row) -> new LockedProposal(
                        rs.getString("id"),
                        rs.getInt("version"),
                        rs.getString("original_input"),
                        rs.getString("events_json"),
                        rs.getString("status"),
                        rs.getObject("expires_at", OffsetDateTime.class)
                                .toInstant(),
                        rs.getString("confirmation_idempotency_key")
                ))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Kitchen event proposal not found."
                ));
    }

    private JdbcClient.StatementSpec scopedSql(String sql) {
        return jdbc.sql(sql)
                .param("workspaceKitchen", workspace.kitchenId())
                .param("workspaceLocation", workspace.locationId());
    }

    private String serialize(List<ParsedKitchenEvent> events) {
        try {
            return mapper.writeValueAsString(events);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException(
                    "Kitchen event proposal could not be serialized.",
                    error
            );
        }
    }

    private List<ParsedKitchenEvent> deserialize(String json) {
        try {
            List<ParsedKitchenEvent> events = mapper.readValue(
                    json,
                    new TypeReference<List<ParsedKitchenEvent>>() {
                    }
            );
            if (events.isEmpty()) {
                throw invalid("Persisted kitchen event proposal is empty.");
            }
            return events;
        } catch (JsonProcessingException error) {
            throw new IllegalStateException(
                    "Persisted kitchen event proposal is invalid.",
                    error
            );
        }
    }

    private ResponseStatusException invalid(String message) {
        return new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                message
        );
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required.");
        }
    }

    public record Proposal(
            String id,
            int version,
            String riskTier,
            String status,
            Instant expiresAt,
            List<ParsedKitchenEvent> events
    ) {
    }

    public record ConfirmationResult(
            String proposalId,
            int version,
            boolean idempotentReplay,
            boolean expired
    ) {
    }

    private record LockedProposal(
            String id,
            int version,
            String originalInput,
            String eventsJson,
            String status,
            Instant expiresAt,
            String confirmationIdempotencyKey
    ) {
    }
}
