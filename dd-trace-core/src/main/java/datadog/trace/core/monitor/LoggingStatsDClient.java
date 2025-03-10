package datadog.trace.core.monitor;

import static datadog.trace.core.monitor.DDAgentStatsDClient.serviceCheckStatus;
import static datadog.trace.core.monitor.DDAgentStatsDConnection.statsDAddress;

import datadog.trace.api.Function;
import datadog.trace.api.StatsDClient;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LoggingStatsDClient implements StatsDClient {
  private static final Logger log = LoggerFactory.getLogger(LoggingStatsDClient.class);

  // logging format is based on the StatsD datagram format
  private static final String COUNT_FORMAT = "{} - {}:{}|c{}";
  private static final String GAUGE_FORMAT = "{} - {}:{}|g{}";
  private static final String HISTOGRAM_FORMAT = "{} - {}:{}|h{}";
  private static final String SERVICE_CHECK_FORMAT = "{} - _sc|{}|{}{}{}";

  private static final DecimalFormat DECIMAL_FORMAT;

  static {
    DECIMAL_FORMAT = new DecimalFormat("0", DecimalFormatSymbols.getInstance(Locale.ENGLISH));
    DECIMAL_FORMAT.setMaximumFractionDigits(6);
  }

  private final String statsDAddress;
  private final Function<String, String> nameMapping;
  private final Function<String[], String[]> tagMapping;

  public LoggingStatsDClient(
      final String host,
      final int port,
      final Function<String, String> nameMapping,
      final Function<String[], String[]> tagMapping) {
    this.statsDAddress = statsDAddress(host, port);
    this.nameMapping = nameMapping;
    this.tagMapping = tagMapping;
  }

  @Override
  public void incrementCounter(final String metricName, final String... tags) {
    log.info(
        COUNT_FORMAT,
        statsDAddress,
        nameMapping.apply(metricName),
        1,
        join(tagMapping.apply(tags)));
  }

  @Override
  public void count(final String metricName, final long delta, final String... tags) {
    log.info(
        COUNT_FORMAT,
        statsDAddress,
        nameMapping.apply(metricName),
        delta,
        join(tagMapping.apply(tags)));
  }

  @Override
  public void gauge(final String metricName, final long value, final String... tags) {
    log.info(
        GAUGE_FORMAT,
        statsDAddress,
        nameMapping.apply(metricName),
        value,
        join(tagMapping.apply(tags)));
  }

  @Override
  public void gauge(final String metricName, final double value, final String... tags) {
    log.info(
        GAUGE_FORMAT,
        statsDAddress,
        nameMapping.apply(metricName),
        DECIMAL_FORMAT.format(value),
        join(tagMapping.apply(tags)));
  }

  @Override
  public void histogram(final String metricName, final long value, final String... tags) {
    log.info(
        HISTOGRAM_FORMAT,
        statsDAddress,
        nameMapping.apply(metricName),
        value,
        join(tagMapping.apply(tags)));
  }

  @Override
  public void histogram(final String metricName, final double value, final String... tags) {
    log.info(
        HISTOGRAM_FORMAT,
        statsDAddress,
        nameMapping.apply(metricName),
        DECIMAL_FORMAT.format(value),
        join(tagMapping.apply(tags)));
  }

  @Override
  public void serviceCheck(
      final String serviceCheckName,
      final String status,
      final String message,
      final String... tags) {
    log.info(
        SERVICE_CHECK_FORMAT,
        statsDAddress,
        nameMapping.apply(serviceCheckName),
        serviceCheckStatus(status).ordinal(),
        join(tagMapping.apply(tags)),
        null != message ? "|m:" + message : "");
  }

  @Override
  public void close() {}

  private static String join(final String... tags) {
    if (null == tags || tags.length == 0) {
      return "";
    }
    StringBuilder buf = new StringBuilder("|#").append(tags[0]);
    for (int i = 1; i < tags.length; i++) {
      buf.append(',').append(tags[i]);
    }
    return buf.toString();
  }
}
