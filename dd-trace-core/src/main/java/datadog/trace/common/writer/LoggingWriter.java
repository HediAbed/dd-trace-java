package datadog.trace.common.writer;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.trace.core.DDSpan;
import datadog.trace.core.processor.TraceProcessor;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LoggingWriter implements Writer {

  private static final Logger log = LoggerFactory.getLogger(LoggingWriter.class);
  private static final JsonAdapter<List<DDSpan>> TRACE_ADAPTER =
      new Moshi.Builder()
          .add(DDSpanJsonAdapter.buildFactory(false))
          .build()
          .adapter(Types.newParameterizedType(List.class, DDSpan.class));

  private final TraceProcessor processor = new TraceProcessor();

  @Override
  public void write(final List<DDSpan> trace) {
    final List<DDSpan> processedTrace = processor.onTraceComplete(trace);
    try {
      log.info("write(trace): {}", TRACE_ADAPTER.toJson(processedTrace));
    } catch (final Exception e) {
      log.error("error writing(trace): {}", processedTrace, e);
    }
  }

  @Override
  public void start() {
    log.info("start()");
  }

  @Override
  public boolean flush() {
    log.info("flush()");
    return true;
  }

  @Override
  public void close() {
    log.info("close()");
  }

  @Override
  public void incrementDropCounts(int spanCount) {}

  @Override
  public String toString() {
    return "LoggingWriter { }";
  }
}
