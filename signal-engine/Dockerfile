FROM gcr.io/dataflow-templates-base/java21-template-launcher-base:20260901-RC00

ARG JAR_FILE=target/signal-engine-1.0.0-SNAPSHOT.jar
COPY ${JAR_FILE} /template/signal-engine.jar

ENV FLEX_TEMPLATE_JAVA_MAIN_CLASS=com.bizlama.signal.SignalEnginePipeline
ENV FLEX_TEMPLATE_JAVA_CLASSPATH=/template/signal-engine.jar
