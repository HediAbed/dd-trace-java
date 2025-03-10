package datadog.trace.instrumentation.play23;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.UndeclaredThrowableException;
import play.api.mvc.Request;
import play.api.mvc.Result;
import scala.Option;

public class PlayHttpServerDecorator extends HttpServerDecorator<Request, Request, Result> {
  public static final CharSequence PLAY_REQUEST = UTF8BytesString.create("play.request");
  public static final CharSequence PLAY_ACTION = UTF8BytesString.create("play-action");
  public static final PlayHttpServerDecorator DECORATE = new PlayHttpServerDecorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"play"};
  }

  @Override
  protected CharSequence component() {
    return PLAY_ACTION;
  }

  @Override
  protected String method(final Request httpRequest) {
    return httpRequest.method();
  }

  @Override
  protected URIDataAdapter url(final Request request) {
    return new RequestURIDataAdapter(request);
  }

  @Override
  protected String peerHostIP(final Request request) {
    return request.remoteAddress();
  }

  @Override
  protected int peerPort(final Request request) {
    return 0;
  }

  @Override
  protected int status(final Result httpResponse) {
    return httpResponse.header().status();
  }

  @Override
  public AgentSpan onRequest(
      final AgentSpan span,
      final Request connection,
      final Request request,
      AgentSpan.Context.Extracted context) {
    super.onRequest(span, connection, request, context);
    if (request != null) {
      // more about routes here:
      // https://github.com/playframework/playframework/blob/master/documentation/manual/releases/release26/migration26/Migration26.md#router-tags-are-now-attributes
      final Option pathOption = request.tags().get("ROUTE_PATTERN");
      if (!pathOption.isEmpty()) {
        final String path = (String) pathOption.get();
        span.setResourceName(request.method() + " " + path);
      }
    }
    return span;
  }

  @Override
  public AgentSpan onError(final AgentSpan span, Throwable throwable) {
    span.setTag(Tags.HTTP_STATUS, _500);
    if (throwable != null
        // This can be moved to instanceof check when using Java 8.
        && throwable.getClass().getName().equals("java.util.concurrent.CompletionException")
        && throwable.getCause() != null) {
      throwable = throwable.getCause();
    }
    while ((throwable instanceof InvocationTargetException
            || throwable instanceof UndeclaredThrowableException)
        && throwable.getCause() != null) {
      throwable = throwable.getCause();
    }
    return super.onError(span, throwable);
  }
}
