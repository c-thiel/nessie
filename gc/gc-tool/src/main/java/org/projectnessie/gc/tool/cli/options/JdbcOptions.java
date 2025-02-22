/*
 * Copyright (C) 2022 Dremio
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.projectnessie.gc.tool.cli.options;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.projectnessie.gc.contents.jdbc.AgroalJdbcDataSourceProvider;
import picocli.CommandLine;

public class JdbcOptions {

  @CommandLine.Option(
      names = "--jdbc-url",
      description = "JDBC URL of the database to connect to.",
      required = true)
  String url;

  @CommandLine.ArgGroup(exclusive = false)
  JdbcUserPassword userPassword;

  @CommandLine.Option(
      names = "--jdbc-properties",
      description = "JDBC parameters.",
      arity = "0..*",
      split = ",")
  Map<String, String> properties = new HashMap<>();

  public DataSource createDataSource() throws SQLException {
    AgroalJdbcDataSourceProvider.Builder jdbcDsBuilder =
        AgroalJdbcDataSourceProvider.builder().jdbcUrl(url);
    if (userPassword != null) {
      jdbcDsBuilder.usernamePasswordCredentials(userPassword.user, userPassword.password);
    }
    properties.forEach(jdbcDsBuilder::putJdbcProperties);
    AgroalJdbcDataSourceProvider dataSourceProvider = jdbcDsBuilder.build();
    return dataSourceProvider.dataSource();
  }

  static class JdbcUserPassword {

    @CommandLine.Option(
        names = "--jdbc-user",
        description = "JDBC user name used to authenticate the database access.",
        required = true)
    String user;

    @CommandLine.Option(
        names = "--jdbc-password",
        description = "JDBC password used to authenticate the database access.",
        required = true)
    String password;
  }
}
