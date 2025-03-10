package datadog.trace.common.metrics

import datadog.trace.api.WellKnownTags
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString
import datadog.trace.core.CoreSpan
import datadog.trace.test.util.DDSpecification
import spock.lang.Requires
import spock.lang.Shared

import java.util.concurrent.CountDownLatch

import static datadog.trace.api.Platform.isJavaVersionAtLeast
import static java.util.concurrent.TimeUnit.MILLISECONDS
import static java.util.concurrent.TimeUnit.SECONDS

@Requires({
  isJavaVersionAtLeast(8)
})
class ConflatingMetricAggregatorTest extends DDSpecification {

  static Set<String> empty = new HashSet<>()

  @Shared
  long reportingInterval = 10
  @Shared
  int queueSize = 256

  def "should ignore traces with no measured spans"() {
    setup:
    Sink sink = Mock(Sink)
    sink.validate() >> true
    WellKnownTags wellKnownTags = new WellKnownTags("runtimeid", "hostname", "env", "service", "version")
    ConflatingMetricsAggregator aggregator = new ConflatingMetricsAggregator(
      wellKnownTags,
      empty,
      sink,
      10,
      queueSize,
      1,
      MILLISECONDS
      )
    aggregator.start()

    when:
    aggregator.publish([new SimpleSpan("", "", "", "", false, false, false, 0, 0)])
    reportAndWaitUntilEmpty(aggregator)
    then:
    0 * sink._

    cleanup:
    aggregator.close()
  }

  def "should ignore traces with ignored resource names"() {
    setup:
    String ignoredResourceName = "foo"
    Sink sink = Mock(Sink)
    sink.validate() >> true
    WellKnownTags wellKnownTags = new WellKnownTags("runtimeid", "hostname", "env", "service", "version")
    ConflatingMetricsAggregator aggregator = new ConflatingMetricsAggregator(
      wellKnownTags,
      [ignoredResourceName].toSet(),
      sink,
      10,
      queueSize,
      1,
      MILLISECONDS
      )
    aggregator.start()

    when: "publish ignored resource names"
    aggregator.publish([new SimpleSpan("", "", ignoredResourceName, "", true, true, false, 0, 0)])
    aggregator.publish([new SimpleSpan("", "", UTF8BytesString.create(ignoredResourceName), "", true, true, false, 0, 0)])
    aggregator.publish([
      new SimpleSpan("", "", ignoredResourceName, "", true, true, false, 0, 0),
      new SimpleSpan("", "",
      "measured, not ignored, but child of ignored, so should be ignored", "", true, true, false, 0, 0)
    ])
    reportAndWaitUntilEmpty(aggregator)
    then:
    0 * sink._

    cleanup:
    aggregator.close()
  }

  def "unmeasured top level spans have metrics computed"() {
    setup:
    MetricWriter writer = Mock(MetricWriter)
    Sink sink = Stub(Sink)
    sink.validate() >> true
    ConflatingMetricsAggregator aggregator = new ConflatingMetricsAggregator(empty,
      sink, writer, 10, queueSize, reportingInterval, SECONDS)
    aggregator.start()

    when:
    CountDownLatch latch = new CountDownLatch(1)
    aggregator.publish([new SimpleSpan("service", "operation", "resource", "type", false, true, false, 0, 100)])
    aggregator.report()
    latch.await(2, SECONDS)

    then:
    1 * writer.startBucket(1, _, _)
    1 * writer.add(new MetricKey("resource", "service", "operation", "type", 0), _) >> { MetricKey key, AggregateMetric value ->
      value.getHitCount() == 1 && value.getTopLevelCount() == 1 && value.getDuration() == 100
    }
    1 * writer.finishBucket() >> { latch.countDown() }

    cleanup:
    aggregator.close()
  }

  def "measured spans do not contribute to top level count"() {
    setup:
    MetricWriter writer = Mock(MetricWriter)
    Sink sink = Stub(Sink)
    sink.validate() >> true
    ConflatingMetricsAggregator aggregator = new ConflatingMetricsAggregator(empty,
      sink, writer, 10, queueSize, reportingInterval, SECONDS)
    aggregator.start()

    when:
    CountDownLatch latch = new CountDownLatch(1)
    aggregator.publish([
      new SimpleSpan("service", "operation", "resource", "type", measured, topLevel, false, 0, 100)
    ])
    aggregator.report()
    latch.await(2, SECONDS)

    then:
    1 * writer.startBucket(1, _, _)
    1 * writer.add(new MetricKey("resource", "service", "operation", "type", 0), _) >> { MetricKey key, AggregateMetric value ->
      value.getHitCount() == 1 && value.getTopLevelCount() == topLevelCount && value.getDuration() == 100
    }
    1 * writer.finishBucket() >> { latch.countDown() }

    cleanup:
    aggregator.close()

    where:
    measured | topLevel | topLevelCount
    true     | false    | 0
    true     | true     | 1
    false    | true     | 1
  }

  def "aggregate repetitive spans"() {
    setup:
    MetricWriter writer = Mock(MetricWriter)
    Sink sink = Stub(Sink)
    sink.validate() >> true
    ConflatingMetricsAggregator aggregator = new ConflatingMetricsAggregator(empty,
      sink, writer, 10, queueSize, reportingInterval, SECONDS)
    long duration = 100
    List<CoreSpan> trace = [
      new SimpleSpan("service", "operation", "resource", "type", true, false, false, 0, duration),
      new SimpleSpan("service1", "operation1", "resource1", "type", false, false, false, 0, 0),
      new SimpleSpan("service2", "operation2", "resource2", "type", true, false, false, 0, duration * 2)
    ]
    aggregator.start()


    when:
    CountDownLatch latch = new CountDownLatch(1)
    for (int i = 0; i < count; ++i) {
      aggregator.publish(trace)
    }
    aggregator.report()
    latch.await(2, SECONDS)

    then: "metrics should be conflated"
    1 * writer.finishBucket() >> { latch.countDown() }
    1 * writer.startBucket(2, _, SECONDS.toNanos(reportingInterval))
    1 * writer.add(new MetricKey("resource", "service", "operation", "type", 0), _) >> { MetricKey key, AggregateMetric value ->
      value.getHitCount() == count && value.getDuration() == count * duration
    }
    1 * writer.add(new MetricKey("resource2", "service2", "operation2", "type", 0), _) >> { MetricKey key, AggregateMetric value ->
      value.getHitCount() == count && value.getDuration() == count * duration * 2
    }

    cleanup:
    aggregator.close()

    where:
    count << [10, 100]
  }

  def "test least recently written to aggregate flushed when size limit exceeded"() {
    setup:
    int maxAggregates = 10
    MetricWriter writer = Mock(MetricWriter)
    Sink sink = Stub(Sink)
    sink.validate() >> true
    ConflatingMetricsAggregator aggregator = new ConflatingMetricsAggregator(empty,
      sink, writer, maxAggregates, queueSize, reportingInterval, SECONDS)
    long duration = 100
    aggregator.start()

    when:
    CountDownLatch latch = new CountDownLatch(1)
    for (int i = 0; i < 11; ++i) {
      aggregator.publish([
        new SimpleSpan("service" + i, "operation", "resource", "type", false, true, false, 0, duration)
      ])
    }
    aggregator.report()
    latch.await(2, SECONDS)

    then: "the first aggregate should be dropped but the rest reported"
    1 * writer.startBucket(10, _, SECONDS.toNanos(reportingInterval))
    for (int i = 1; i < 11; ++i) {
      1 * writer.add(new MetricKey("resource", "service" + i, "operation", "type", 0), _) >> { MetricKey key, AggregateMetric value ->
        value.getHitCount() == 1 && value.getDuration() == duration
      }
    }
    0 * writer.add(new MetricKey("resource", "service0", "operation", "type", 0), _)
    1 * writer.finishBucket() >> { latch.countDown() }

    cleanup:
    aggregator.close()
  }

  def "aggregate not updated in reporting interval not reported"() {
    setup:
    int maxAggregates = 10
    MetricWriter writer = Mock(MetricWriter)
    Sink sink = Stub(Sink)
    sink.validate() >> true
    ConflatingMetricsAggregator aggregator = new ConflatingMetricsAggregator(empty,
      sink, writer, maxAggregates, queueSize, reportingInterval, SECONDS)
    long duration = 100
    aggregator.start()

    when:
    CountDownLatch latch = new CountDownLatch(1)
    for (int i = 0; i < 5; ++i) {
      aggregator.publish([
        new SimpleSpan("service" + i, "operation", "resource", "type", false, true, false, 0, duration)
      ])
    }
    aggregator.report()
    latch.await(2, SECONDS)

    then: "all aggregates should be reported"
    1 * writer.startBucket(5, _, SECONDS.toNanos(reportingInterval))
    for (int i = 0; i < 5; ++i) {
      1 * writer.add(new MetricKey("resource", "service" + i, "operation", "type", 0), _) >> { MetricKey key, AggregateMetric value ->
        value.getHitCount() == 1 && value.getDuration() == duration
      }
    }
    1 * writer.finishBucket() >> { latch.countDown() }

    when:
    latch = new CountDownLatch(1)
    for (int i = 1; i < 5; ++i) {
      aggregator.publish([
        new SimpleSpan("service" + i, "operation", "resource", "type", false, true, false, 0, duration)
      ])
    }
    aggregator.report()
    latch.await(2, SECONDS)

    then: "aggregate not updated in cycle is not reported"
    1 * writer.startBucket(4, _, SECONDS.toNanos(reportingInterval))
    for (int i = 1; i < 5; ++i) {
      1 * writer.add(new MetricKey("resource", "service" + i, "operation", "type", 0), _) >> { MetricKey key, AggregateMetric value ->
        value.getHitCount() == 1 && value.getDuration() == duration
      }
    }
    0 * writer.add(new MetricKey("resource", "service0", "operation", "type", 0), _)
    1 * writer.finishBucket() >> { latch.countDown() }

    cleanup:
    aggregator.close()
  }

  def "when no aggregate is updated in reporting interval nothing is reported"() {
    setup:
    int maxAggregates = 10
    MetricWriter writer = Mock(MetricWriter)
    Sink sink = Stub(Sink)
    sink.validate() >> true
    ConflatingMetricsAggregator aggregator = new ConflatingMetricsAggregator(empty,
      sink, writer, maxAggregates, queueSize, reportingInterval, SECONDS)
    long duration = 100
    aggregator.start()

    when:
    CountDownLatch latch = new CountDownLatch(1)
    for (int i = 0; i < 5; ++i) {
      aggregator.publish([
        new SimpleSpan("service" + i, "operation", "resource", "type", false, true, false, 0, duration)
      ])
    }
    aggregator.report()
    latch.await(2, SECONDS)

    then: "all aggregates should be reported"
    1 * writer.startBucket(5, _, SECONDS.toNanos(reportingInterval))
    for (int i = 0; i < 5; ++i) {
      1 * writer.add(new MetricKey("resource", "service" + i, "operation", "type", 0), _) >> { MetricKey key, AggregateMetric value ->
        value.getHitCount() == 1 && value.getDuration() == duration
      }
    }
    1 * writer.finishBucket()

    when:
    reportAndWaitUntilEmpty(aggregator)

    then: "aggregate not updated in cycle is not reported"
    0 * writer.finishBucket()
    0 * writer.startBucket(_, _, _)
    0 * writer.add(_, _)

    cleanup:
    aggregator.close()
  }

  def "should report periodically"() {
    setup:
    int maxAggregates = 10
    MetricWriter writer = Mock(MetricWriter)
    Sink sink = Stub(Sink)
    sink.validate() >> true
    ConflatingMetricsAggregator aggregator = new ConflatingMetricsAggregator(empty,
      sink, writer, maxAggregates, queueSize, 1, SECONDS)
    long duration = 100
    aggregator.start()

    when:
    CountDownLatch latch = new CountDownLatch(1)
    for (int i = 0; i < 5; ++i) {
      aggregator.publish([
        new SimpleSpan("service" + i, "operation", "resource", "type", false, true, false, 0, duration)
      ])
    }
    latch.await(2, SECONDS)

    then: "all aggregates should be reported"
    1 * writer.startBucket(5, _, SECONDS.toNanos(1))
    for (int i = 0; i < 5; ++i) {
      1 * writer.add(new MetricKey("resource", "service" + i, "operation", "type", 0), _) >> { MetricKey key, AggregateMetric value ->
        value.getHitCount() == 1 && value.getDuration() == duration
      }
    }
    1 * writer.finishBucket() >> { latch.countDown() }

    cleanup:
    aggregator.close()
  }

  def "aggregator should force keep the first of each key it sees"() {
    setup:
    int maxAggregates = 10
    MetricWriter writer = Mock(MetricWriter)
    Sink sink = Stub(Sink)
    sink.validate() >> true
    ConflatingMetricsAggregator aggregator = new ConflatingMetricsAggregator(empty,
      sink, writer, maxAggregates, queueSize, 1, SECONDS)
    long duration = 100
    aggregator.start()

    when:
    def overrides = new boolean[10]
    for (int i = 0; i < 5; ++i) {
      overrides[i] = aggregator.publish([
        new SimpleSpan("service" + i, "operation", "resource", "type", false, true, false, 0, duration)
      ])
    }
    for (int i = 0; i < 5; ++i) {
      overrides[i + 5] = aggregator.publish([
        new SimpleSpan("service" + i, "operation", "resource", "type", false, true, false, 0, duration)
      ])
    }

    then: "override only the first of each point in the interval"
    for (int i = 0; i < 5; ++i) {
      assert overrides[i]
    }
    // these were all repeats, so should be ignored
    for (int i = 5; i < 10; ++i) {
      assert !overrides[i]
    }

    cleanup:
    aggregator.close()
  }

  def "should be resilient to serialization errors"() {
    setup:
    int maxAggregates = 10
    MetricWriter writer = Mock(MetricWriter)
    Sink sink = Stub(Sink)
    sink.validate() >> true
    ConflatingMetricsAggregator aggregator = new ConflatingMetricsAggregator(empty,
      sink, writer, maxAggregates, queueSize, 1, SECONDS)
    long duration = 100
    aggregator.start()

    when:
    CountDownLatch latch = new CountDownLatch(1)
    for (int i = 0; i < 5; ++i) {
      aggregator.publish([
        new SimpleSpan("service" + i, "operation", "resource", "type", false, true, false, 0, duration)
      ])
    }
    latch.await(2, SECONDS)

    then: "writer should be reset if reporting fails"
    1 * writer.startBucket(_, _, _) >> {
      throw new IllegalArgumentException("something went wrong")
    }
    1 * writer.reset() >> { latch.countDown() }

    cleanup:
    aggregator.close()
  }

  def reportAndWaitUntilEmpty(ConflatingMetricsAggregator aggregator) {
    waitUntilEmpty(aggregator)
    aggregator.report()
    waitUntilEmpty(aggregator)
  }


  def waitUntilEmpty(ConflatingMetricsAggregator aggregator) {
    int i = 0
    while (!aggregator.inbox.isEmpty() && i++ < 100) {
      Thread.sleep(10)
    }
  }
}
