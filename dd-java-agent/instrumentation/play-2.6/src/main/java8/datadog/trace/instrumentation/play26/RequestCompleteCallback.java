package datadog.trace.instrumentation.play26;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static datadog.trace.instrumentation.play26.PlayHttpServerDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.context.TraceScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.api.mvc.Result;
import scala.util.Try;

public class RequestCompleteCallback extends scala.runtime.AbstractFunction1<Try<Result>, Object> {

  private static final Logger log = LoggerFactory.getLogger(RequestCompleteCallback.class);

  private final AgentSpan span;

  public RequestCompleteCallback(final AgentSpan span) {
    this.span = span;
  }

  @Override
  public Object apply(final Try<Result> result) {
    try {
      if (result.isFailure()) {
        DECORATE.onError(span, result.failed().get());
      } else {
        DECORATE.onResponse(span, result.get());
      }
      DECORATE.beforeFinish(span);
      final TraceScope scope = activeScope();
      if (scope != null) {
        scope.setAsyncPropagation(false);
      }
    } catch (final Throwable t) {
      log.debug("error in play instrumentation", t);
    } finally {
      span.finish();
    }
    return null;
  }
}
