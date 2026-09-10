package com.bizlama.signal;

import org.apache.beam.runners.dataflow.options.DataflowPipelineOptions;
import org.apache.beam.sdk.options.Default;
import org.apache.beam.sdk.options.Description;
import org.apache.beam.sdk.options.Validation;

public interface SignalEngineOptions extends DataflowPipelineOptions {

    @Description("Dedicated Pub/Sub subscription for the Dataflow consumer")
    @Validation.Required
    String getInputSubscription();
    void setInputSubscription(String value);

    @Description("Immutable valid raw-event BigQuery table spec")
    @Validation.Required
    String getRawTable();
    void setRawTable(String value);

    @Description("Canonical fact BigQuery table spec")
    @Validation.Required
    String getFactTable();
    void setFactTable(String value);

    @Description("Windowed feature BigQuery table spec")
    @Validation.Required
    String getFeatureTable();
    void setFeatureTable(String value);

    @Description("Invalid/late event BigQuery table spec")
    @Validation.Required
    String getErrorTable();
    void setErrorTable(String value);

    @Description("Pub/Sub topic receiving governed proposal commands")
    @Validation.Required
    String getProposalTopic();
    void setProposalTopic(String value);

    @Description("Canonical Pub/Sub occurredAt attribute; must remain occurredAt")
    @Default.String("occurredAt")
    String getTimestampAttribute();
    void setTimestampAttribute(String value);

    @Description("Canonical Pub/Sub event ID attribute; must remain eventId")
    @Default.String("eventId")
    String getIdAttribute();
    void setIdAttribute(String value);

    @Description("Accepted event-time lateness in minutes")
    @Default.Integer(120)
    Integer getAllowedLatenessMinutes();
    void setAllowedLatenessMinutes(Integer value);

    @Description("Best-effort online event-ID deduplication horizon in hours")
    @Default.Integer(24)
    Integer getDeduplicationHorizonHours();
    void setDeduplicationHorizonHours(Integer value);
}
