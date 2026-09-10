package com.bizlama.signal;

import com.bizlama.signal.EventModels.BrokerEvent;
import com.bizlama.signal.EventModels.ProposalCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.services.bigquery.model.TableRow;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO.Write.Method;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubIO;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubMessage;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.MapElements;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionTuple;
import org.apache.beam.sdk.values.TypeDescriptor;

public final class SignalEnginePipeline {

    private SignalEnginePipeline() {
    }

    public static void main(String[] args) {
        SignalEngineOptions options = PipelineOptionsFactory.fromArgs(args)
                .withValidation()
                .as(SignalEngineOptions.class);
        options.setStreaming(true);
        run(options);
    }

    public static Pipeline run(SignalEngineOptions options) {
        validateDurations(options);
        Pipeline pipeline = Pipeline.create(options);

        PCollection<BrokerEvent> input = pipeline
                .apply(
                        "Read dedicated event subscription",
                        PubsubIO.readMessagesWithAttributesAndMessageId()
                                .withTimestampAttribute(
                                        options.getTimestampAttribute())
                                .fromSubscription(options.getInputSubscription())
                )
                .apply(
                        "Decode UTF-8 event bodies",
                        MapElements.into(TypeDescriptor.of(BrokerEvent.class))
                                .via(message -> new BrokerEvent(
                                        new String(message.getPayload(),
                                                StandardCharsets.UTF_8),
                                        message.getAttributeMap()))
                );

        SignalEngineTransform.Config config = new SignalEngineTransform.Config(
                Duration.ofMinutes(options.getAllowedLatenessMinutes()),
                Duration.ofHours(options.getDeduplicationHorizonHours()),
                false,
                options.getProposalTopic(),
                true
        );
        PCollectionTuple output = input.apply(
                "Compute multi-kitchen signals",
                new SignalEngineTransform(config)
        );

        writeBigQuery(
                output.get(SignalEngineTransform.RAW_VALID)
                        .apply(
                                "Map raw rows",
                                MapElements.into(TypeDescriptor.of(TableRow.class))
                                        .via(BigQueryMappings::rawRow)
                        ),
                options.getRawTable(),
                BigQueryMappings.rawSchema()
        );
        writeBigQuery(
                output.get(SignalEngineTransform.FACTS)
                        .apply(
                                "Map fact rows",
                                MapElements.into(TypeDescriptor.of(TableRow.class))
                                        .via(BigQueryMappings::factRow)
                        ),
                options.getFactTable(),
                BigQueryMappings.factSchema()
        );
        writeBigQuery(
                output.get(SignalEngineTransform.FEATURES)
                        .apply(
                                "Map feature rows",
                                MapElements.into(TypeDescriptor.of(TableRow.class))
                                        .via(BigQueryMappings::featureRow)
                        ),
                options.getFeatureTable(),
                BigQueryMappings.featureSchema()
        );
        writeBigQuery(
                output.get(SignalEngineTransform.ERRORS)
                        .apply(
                                "Map error rows",
                                MapElements.into(TypeDescriptor.of(TableRow.class))
                                        .via(BigQueryMappings::errorRow)
                        ),
                options.getErrorTable(),
                BigQueryMappings.errorSchema()
        );

        output.get(SignalEngineTransform.PROPOSALS)
                .apply(
                        "Encode governed proposal commands",
                        ParDo.of(new ProposalMessageFn())
                )
                .apply(
                        "Publish proposal commands only",
                        PubsubIO.writeMessages()
                                .to(options.getProposalTopic())
                                .withIdAttribute("proposalId")
                                .withTimestampAttribute("signalTime")
                );

        pipeline.run();
        return pipeline;
    }

    private static void writeBigQuery(
            PCollection<TableRow> rows,
            String table,
            com.google.api.services.bigquery.model.TableSchema schema
    ) {
        rows.apply(
                "Write " + table,
                BigQueryIO.writeTableRows()
                        .to(table)
                        .withSchema(schema)
                        .withCreateDisposition(
                                BigQueryIO.Write.CreateDisposition.CREATE_IF_NEEDED)
                        .withWriteDisposition(
                                BigQueryIO.Write.WriteDisposition.WRITE_APPEND)
                        .withMethod(Method.STORAGE_WRITE_API)
                        .withAutoSharding()
                        .withTriggeringFrequency(
                                org.joda.time.Duration.standardSeconds(5))
        );
    }

    private static void validateDurations(SignalEngineOptions options) {
        if (options.getAllowedLatenessMinutes() == null
                || options.getAllowedLatenessMinutes() <= 0) {
            throw new IllegalArgumentException(
                    "allowedLatenessMinutes must be positive.");
        }
        if (options.getDeduplicationHorizonHours() == null
                || options.getDeduplicationHorizonHours() <= 0) {
            throw new IllegalArgumentException(
                    "deduplicationHorizonHours must be positive.");
        }
        if (!"eventId".equals(options.getIdAttribute())) {
            throw new IllegalArgumentException(
                    "idAttribute must match canonical producer attribute eventId.");
        }
        if (!"occurredAt".equals(options.getTimestampAttribute())) {
            throw new IllegalArgumentException(
                    "timestampAttribute must match canonical producer attribute occurredAt.");
        }
    }

    private static final class ProposalMessageFn
            extends DoFn<ProposalCommand, PubsubMessage> {

        private transient ObjectMapper mapper;

        @Setup
        public void setup() {
            mapper = new ObjectMapper();
        }

        @ProcessElement
        public void process(ProcessContext context) throws Exception {
            ProposalCommand proposal = context.element();
            context.output(new PubsubMessage(
                    mapper.writeValueAsBytes(proposal),
                    Map.of(
                            "proposalId", proposal.proposalId(),
                            "signalTime", Instant.ofEpochMilli(
                                    proposal.windowEndMillis()).toString(),
                            "schemaVersion", Integer.toString(
                                    proposal.schemaVersion()),
                            "commandType", proposal.commandType(),
                            "kitchenId", proposal.kitchenId(),
                            "locationId", proposal.locationId()
                    )
            ));
        }
    }
}
