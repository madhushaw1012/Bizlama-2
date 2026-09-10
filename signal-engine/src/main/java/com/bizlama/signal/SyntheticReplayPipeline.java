package com.bizlama.signal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.bizlama.signal.EventModels.BrokerEvent;
import com.google.api.services.bigquery.model.TableRow;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO;
import org.apache.beam.sdk.io.TextIO;
import org.apache.beam.sdk.options.Default;
import org.apache.beam.sdk.options.Description;
import org.apache.beam.runners.dataflow.options.DataflowPipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.options.Validation;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.MapElements;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionTuple;
import org.apache.beam.sdk.values.TypeDescriptor;

public final class SyntheticReplayPipeline {

    private SyntheticReplayPipeline() {
    }

    public interface Options extends DataflowPipelineOptions {
        @Description("Synthetic JSONL input path")
        @Validation.Required
        String getInputFile();
        void setInputFile(String value);

        @Description("Output file prefix")
        @Validation.Required
        String getOutputPrefix();
        void setOutputPrefix(String value);

        @Description("Run exact event-ID reconciliation instead of bounded online dedup")
        @Default.Boolean(true)
        Boolean getReconciliationMode();
        void setReconciliationMode(Boolean value);

        @Description("Optional exact-replay fact staging table")
        @Default.String("")
        String getFactStagingTable();
        void setFactStagingTable(String value);

        @Description("Optional exact-replay feature staging table")
        @Default.String("")
        String getFeatureStagingTable();
        void setFeatureStagingTable(String value);
    }

    public static void main(String[] args) {
        Options options = PipelineOptionsFactory.fromArgs(args)
                .withValidation()
                .as(Options.class);
        options.setStreaming(false);
        Pipeline pipeline = Pipeline.create(options);
        PCollection<BrokerEvent> input = pipeline.apply(
                "Read clearly labelled synthetic replay",
                TextIO.read().from(options.getInputFile())
        ).apply("Wrap replay envelopes without broker attributes",
                MapElements.into(TypeDescriptor.of(BrokerEvent.class))
                        .via(BrokerEvent::replay));
        SignalEngineTransform.Config config =
                Boolean.TRUE.equals(options.getReconciliationMode())
                        ? SignalEngineTransform.Config.reconciliationDefaults()
                        : SignalEngineTransform.Config.streamingDefaults();
        PCollectionTuple output = input.apply(
                "Replay signal engine",
                new SignalEngineTransform(config)
        );

        stageBigQueryIfConfigured(output, options);

        writeJson(
                output.get(SignalEngineTransform.RAW_VALID),
                options.getOutputPrefix() + "-raw");
        writeJson(
                output.get(SignalEngineTransform.FACTS),
                options.getOutputPrefix() + "-facts");
        writeJson(
                output.get(SignalEngineTransform.FEATURES),
                options.getOutputPrefix() + "-features");
        writeJson(
                output.get(SignalEngineTransform.ERRORS),
                options.getOutputPrefix() + "-errors");
        writeJson(
                output.get(SignalEngineTransform.PROPOSALS),
                options.getOutputPrefix() + "-proposals");
        pipeline.run().waitUntilFinish();
    }

    private static void stageBigQueryIfConfigured(
            PCollectionTuple output,
            Options options
    ) {
        boolean factsConfigured = !options.getFactStagingTable().isBlank();
        boolean featuresConfigured = !options.getFeatureStagingTable().isBlank();
        if (factsConfigured != featuresConfigured) {
            throw new IllegalArgumentException(
                    "Both factStagingTable and featureStagingTable are required together.");
        }
        if (!factsConfigured) {
            return;
        }
        output.get(SignalEngineTransform.FACTS)
                .apply("Map exact replay fact staging rows",
                        MapElements.into(TypeDescriptor.of(TableRow.class))
                                .via(BigQueryMappings::factRow))
                .apply("Replace exact replay fact staging",
                        BigQueryIO.writeTableRows()
                                .to(options.getFactStagingTable())
                                .withSchema(BigQueryMappings.factSchema())
                                .withCreateDisposition(
                                        BigQueryIO.Write.CreateDisposition.CREATE_IF_NEEDED)
                                .withWriteDisposition(
                                        BigQueryIO.Write.WriteDisposition.WRITE_TRUNCATE));
        output.get(SignalEngineTransform.FEATURES)
                .apply("Map exact replay feature staging rows",
                        MapElements.into(TypeDescriptor.of(TableRow.class))
                                .via(BigQueryMappings::featureRow))
                .apply("Replace exact replay feature staging",
                        BigQueryIO.writeTableRows()
                                .to(options.getFeatureStagingTable())
                                .withSchema(BigQueryMappings.featureSchema())
                                .withCreateDisposition(
                                        BigQueryIO.Write.CreateDisposition.CREATE_IF_NEEDED)
                                .withWriteDisposition(
                                        BigQueryIO.Write.WriteDisposition.WRITE_TRUNCATE));
    }

    private static <T> void writeJson(
            PCollection<T> input,
            String prefix
    ) {
        input.apply(
                "Serialize " + prefix,
                ParDo.of(new JsonFn<>()))
                .apply(
                        "Write " + prefix,
                        TextIO.write()
                                .to(prefix)
                                .withSuffix(".jsonl")
                                .withWindowedWrites()
                                .withNumShards(1)
                );
    }

    private static final class JsonFn<T> extends DoFn<T, String> {
        private transient ObjectMapper mapper;

        @Setup
        public void setup() {
            mapper = new ObjectMapper();
        }

        @ProcessElement
        public void process(ProcessContext context) throws Exception {
            context.output(mapper.writeValueAsString(context.element()));
        }
    }
}
