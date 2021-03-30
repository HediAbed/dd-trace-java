package datadog.trace.bootstrap.instrumentation.decorator;

import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.Function;
import datadog.trace.api.Pair;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;

public abstract class ServerDecorator extends BaseDecorator {

  @Override
  public AgentSpan afterStart(final AgentSpan span) {
    span.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_SERVER);
    span.setTag(DDTags.LANGUAGE_TAG_KEY, DDTags.LANGUAGE_TAG_VALUE);
    return super.afterStart(span);
  }

  private static final Function<Pair<CharSequence, CharSequence>, CharSequence>
      RESOURCE_NAME_JOINER =
          new Function<Pair<CharSequence, CharSequence>, CharSequence>() {
            @Override
            public CharSequence apply(Pair<CharSequence, CharSequence> input) {
              return UTF8BytesString.create(input.getLeft() + " " + input.getRight());
            }
          };
  private static final DDCache<Pair<CharSequence, CharSequence>, CharSequence> RESOURCE_NAME_CACHE =
      DDCaches.newFixedSizeCache(64);

  public final AgentSpan withRoute(
      final AgentSpan span, final CharSequence method, final CharSequence route) {
    span.setTag(Tags.HTTP_ROUTE, route);
    if (Config.get().isHttpServerRouteBasedNaming()) {
      final CharSequence resourceName =
          RESOURCE_NAME_CACHE.computeIfAbsent(Pair.of(method, route), RESOURCE_NAME_JOINER);
      span.setResourceName(resourceName);
    }
    return span;
  }
}
