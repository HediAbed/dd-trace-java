package datadog.trace.instrumentation.logback;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.hasClassesNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.log.UnionMap;
import datadog.trace.api.Config;
import datadog.trace.api.CorrelationIdentifier;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class LoggingEventInstrumentation extends Instrumenter.Tracing {
  public LoggingEventInstrumentation() {
    super("logback");
  }

  @Override
  protected boolean defaultEnabled() {
    return Config.get().isLogsInjectionEnabled();
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    // Optimization for expensive typeMatcher.
    return hasClassesNamed("ch.qos.logback.classic.spi.ILoggingEvent");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return implementsInterface(named("ch.qos.logback.classic.spi.ILoggingEvent"));
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap(
        "ch.qos.logback.classic.spi.ILoggingEvent", AgentSpan.Context.class.getName());
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        isMethod().and(named("getMDCPropertyMap").or(named("getMdc"))).and(takesArguments(0)),
        LoggingEventInstrumentation.class.getName() + "$GetMdcAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.agent.tooling.log.UnionMap",
      "datadog.trace.agent.tooling.log.UnionMap$ConcatenatedSet",
      "datadog.trace.agent.tooling.log.UnionMap$ConcatenatedSet$ConcatenatedSetIterator",
    };
  }

  public static class GetMdcAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.This ILoggingEvent event,
        @Advice.Return(typing = Assigner.Typing.DYNAMIC, readOnly = false)
            Map<String, String> mdc) {

      if (mdc instanceof UnionMap) {
        return;
      }

      AgentSpan.Context context =
          InstrumentationContext.get(ILoggingEvent.class, AgentSpan.Context.class).get(event);
      boolean mdcTagsInjectionEnabled = Config.get().isLogsMDCTagsInjectionEnabled();

      // Nothing to add so return early
      if (context == null && !mdcTagsInjectionEnabled) {
        return;
      }

      Map<String, String> correlationValues = new HashMap<>();

      if (context != null) {
        correlationValues.put(
            CorrelationIdentifier.getTraceIdKey(), context.getTraceId().toString());
        correlationValues.put(CorrelationIdentifier.getSpanIdKey(), context.getSpanId().toString());
      }

      if (mdcTagsInjectionEnabled) {
        correlationValues.put(Tags.DD_SERVICE, Config.get().getServiceName());
        correlationValues.put(Tags.DD_ENV, Config.get().getEnv());
        correlationValues.put(Tags.DD_VERSION, Config.get().getVersion());
      }

      if (mdc == null) {
        mdc = correlationValues;
      } else {
        mdc = new UnionMap<>(mdc, correlationValues);
      }
    }
  }
}
