package datadog.trace.instrumentation.jdbc;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Config;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class ConnectionInstrumentation extends AbstractConnectionInstrumentation {

  private static final String[] CONCRETE_TYPES = {
    // redshift
    "com.amazon.redshift.jdbc.RedshiftConnectionImpl",
    // jt400
    "com.ibm.as400.access.AS400JDBCConnection",
    // possibly need more coverage
    "com.microsoft.sqlserver.jdbc.SQLServerConnection",
    // should cover mysql
    "com.mysql.jdbc.Connection",
    "com.mysql.jdbc.jdbc1.Connection",
    "com.mysql.jdbc.jdbc2.Connection",
    "com.mysql.jdbc.ConnectionImpl",
    "com.mysql.jdbc.JDBC4Connection",
    "com.mysql.cj.jdbc.ConnectionImpl",
    // should cover Oracle
    "oracle.jdbc.driver.PhysicalConnection",
    // should cover derby
    "org.apache.derby.impl.jdbc.EmbedConnection",
    "org.apache.hive.jdbc.HiveConnection",
    "org.apache.phoenix.jdbc.PhoenixConnection",
    "org.apache.pinot.client.PinotConnection",
    // complete
    "org.h2.jdbc.JdbcConnection",
    // complete
    "org.hsqldb.jdbc.JDBCConnection",
    "org.hsqldb.jdbc.jdbcConnection",
    // complete
    "org.mariadb.jdbc.MariaDbConnection",
    "org.mariadb.jdbc.MySQLConnection",

    // postgresql seems to be complete
    "org.postgresql.jdbc.PgConnection",
    "org.postgresql.jdbc1.Connection",
    "org.postgresql.jdbc1.Jdbc1Connection",
    "org.postgresql.jdbc2.Connection",
    "org.postgresql.jdbc2.Jdbc2Connection",
    "org.postgresql.jdbc3.Jdbc3Connection",
    "org.postgresql.jdbc3g.Jdbc3gConnection",
    "org.postgresql.jdbc4.Jdbc4Connection",
    "postgresql.Connection",
    // sqlite seems to be complete
    "org.sqlite.Conn",
    "org.sqlite.jdbc3.JDBC3Connection",
    "org.sqlite.jdbc4.JDBC4Connection",
    // for testing purposes
    "test.TestConnection",
    // this won't match any class unless the property is set
    Config.get().getJdbcConnectionClassName()
  };

  public ConnectionInstrumentation() {
    super("jdbc");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return namedOneOf(CONCRETE_TYPES);
  }
}
