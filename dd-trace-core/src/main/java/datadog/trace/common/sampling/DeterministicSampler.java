package datadog.trace.common.sampling;

import datadog.trace.core.CoreSpan;
import datadog.trace.core.CoreTracer;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This implements the deterministic sampling algorithm used by the Datadog Agent as well as the
 * tracers for other languages
 */
public class DeterministicSampler<T extends CoreSpan<T>> implements RateSampler<T> {

  private static final Logger log = LoggerFactory.getLogger(DeterministicSampler.class);
  private static final long KNUTH_FACTOR = 1111111111111111111L;

  private final long cutoff; // pre-calculated for the unsigned 64 bit comparison
  private final double rate;

  public DeterministicSampler(final double rate) {
    this.rate = rate;
    cutoff =
        new BigDecimal(rate)
                .multiply(new BigDecimal(CoreTracer.TRACE_ID_MAX))
                .toBigInteger()
                .longValue()
            + Long.MIN_VALUE;

    log.debug("Initializing the RateSampler, sampleRate: {} %", rate * 100);
  }

  @Override
  public boolean sample(final T span) {
    boolean sampled = false;
    if (rate >= 1) {
      sampled = true;
    } else if (rate > 0) {
      long mod = span.getTraceId().toLong() * KNUTH_FACTOR;
      // unsigned 64 bit comparison with pre-calculated cutoff
      if (mod + Long.MIN_VALUE < cutoff) {
        sampled = true;
      }
    }

    return sampled;
  }

  @Override
  public double getSampleRate() {
    return rate;
  }
}
