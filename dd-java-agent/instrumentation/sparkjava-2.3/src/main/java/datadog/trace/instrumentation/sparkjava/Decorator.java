package datadog.trace.instrumentation.sparkjava;

import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.decorator.ServerDecorator;

public class Decorator extends ServerDecorator {
  public static final Decorator DECORATE = new Decorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"sparkjava", "sparkjava-2.4"};
  }

  @Override
  protected CharSequence spanType() {
    return InternalSpanTypes.HTTP_SERVER;
  }

  @Override
  protected CharSequence component() {
    return "sparkjava";
  }
}
