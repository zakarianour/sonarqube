/*
 * SonarQube
 * Copyright (C) 2009-2024 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.server.platform.db.migration.version.v107;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sonar.api.utils.System2;
import org.sonar.core.util.SequenceUuidFactory;
import org.sonar.db.MigrationDbTester;
import org.sonar.server.platform.db.migration.step.DataChange;

import static java.lang.String.format;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;

class MigrateBranchesLiveMeasuresToMeasuresIT {

  private static final String MEASURES_MIGRATED_COLUMN = "measures_migrated";
  public static final String SELECT_MEASURE = "select component_uuid, branch_uuid, json_value, json_value_hash, created_at, updated_at " +
    "from measures where component_uuid = '%s'";

  @RegisterExtension
  public final MigrationDbTester db = MigrationDbTester.createForMigrationStep(MigrateBranchesLiveMeasuresToMeasures.class);

  private final SequenceUuidFactory uuidFactory = new SequenceUuidFactory();
  private final System2 system2 = mock();
  private final DataChange underTest = new MigrateBranchesLiveMeasuresToMeasures(db.database(), system2);

  @Test
  void shall_complete_when_tables_are_empty() throws SQLException {
    underTest.execute();

    assertThat(db.countRowsOfTable("measures")).isZero();
  }

  @Test
  void shall_not_migrate_when_branch_is_already_flagged() throws SQLException {
    String nclocMetricUuid = insertMetric("ncloc", "INT");
    String qgStatusMetricUuid = insertMetric("quality_gate_status", "STRING");
    String metricWithDataUuid = insertMetric("metric_with_data", "DATA");
    String branch1 = "branch_1";
    insertMigratedBranch(branch1);
    insertMeasure(branch1, nclocMetricUuid, Map.of("value", 120));
    insertMeasure(branch1, qgStatusMetricUuid, Map.of("text_value", "ok"));
    insertMeasure(branch1, metricWithDataUuid, Map.of("measure_data", "some data".getBytes(StandardCharsets.UTF_8)));

    insertMigratedBranch("branch_2");
    insertMeasure("branch_2", nclocMetricUuid, Map.of("value", 14220));

    underTest.execute();

    assertThat(db.countRowsOfTable("measures")).isZero();
  }

  @Test
  void should_flag_branch_with_no_measures() throws SQLException {
    String branch = "branch_3";
    insertNotMigratedBranch(branch);

    underTest.execute();

    assertBranchMigrated(branch);
    assertThat(db.countRowsOfTable("measures")).isZero();
  }

  @Test
  void should_migrate_branch_with_measures() throws SQLException {
    String nclocMetricUuid = insertMetric("ncloc", "INT");
    String qgStatusMetricUuid = insertMetric("quality_gate_status", "STRING");
    String metricWithDataUuid = insertMetric("metric_with_data", "DATA");

    String branch1 = "branch_4";
    insertNotMigratedBranch(branch1);
    String component1 = uuidFactory.create();
    String component2 = uuidFactory.create();
    insertMeasure(branch1, component1, nclocMetricUuid, Map.of("value", 120));
    insertMeasure(branch1, component1, qgStatusMetricUuid, Map.of("text_value", "ok"));
    insertMeasure(branch1, component2, metricWithDataUuid, Map.of("measure_data", "some data".getBytes(StandardCharsets.UTF_8)));

    String branch2 = "branch_5";
    insertNotMigratedBranch(branch2);
    insertMeasure(branch2, nclocMetricUuid, Map.of("value", 64));

    String migratedBranch = "branch_6";
    insertMigratedBranch(migratedBranch);
    insertMeasure(migratedBranch, nclocMetricUuid, Map.of("value", 3684));

    underTest.execute();

    assertBranchMigrated(branch1);
    assertBranchMigrated(branch2);
    assertThat(db.countRowsOfTable("measures")).isEqualTo(3);

    assertThat(db.select(format(SELECT_MEASURE, component1)))
      .hasSize(1)
      .extracting(t -> t.get("component_uuid"), t -> t.get("branch_uuid"), t -> t.get("json_value"), t -> t.get("json_value_hash"))
      .containsOnly(tuple(component1, branch1, "{\"ncloc\":120.0,\"quality_gate_status\":\"ok\"}", 6033012287291512746L));

    assertThat(db.select(format(SELECT_MEASURE, component2)))
      .hasSize(1)
      .extracting(t -> t.get("component_uuid"), t -> t.get("branch_uuid"), t -> t.get("json_value"), t -> t.get("json_value_hash"))
      .containsOnly(tuple(component2, branch1, "{\"metric_with_data\":\"some data\"}", -4524184678167636687L));
  }

  private void assertBranchMigrated(String branch) {
    List<Map<String, Object>> result = db.select(format("select %s as \"MIGRATED\" from project_branches where uuid = '%s'", MEASURES_MIGRATED_COLUMN, branch));
    assertThat(result)
      .hasSize(1)
      .extracting(t -> t.get("MIGRATED"))
      .containsOnly(true);
  }

  private String insertMetric(String metricName, String valueType) {
    String metricUuid = uuidFactory.create();
    db.executeInsert("metrics",
      "uuid", metricUuid,
      "name", metricName,
      "val_type", valueType);
    return metricUuid;
  }

  private void insertMeasure(String branchUuid, String metricUuid, Map<String, Object> data) {
    insertMeasure(branchUuid, uuidFactory.create(), metricUuid, data);
  }

  private void insertMeasure(String branchUuid, String componentUuid, String metricUuid, Map<String, Object> data) {
    Map<String, Object> dataMap = new HashMap<>(data);
    dataMap.put("uuid", uuidFactory.create());
    dataMap.put("component_uuid", componentUuid);
    dataMap.put("project_uuid", branchUuid);
    dataMap.put("metric_uuid", metricUuid);
    dataMap.put("created_at", 12L);
    dataMap.put("updated_at", 12L);

    db.executeInsert("live_measures", dataMap);
  }

  private void insertNotMigratedBranch(String branchUuid) {
    insertBranch(branchUuid, false);
  }

  private void insertMigratedBranch(String branchUuid) {
    insertBranch(branchUuid, true);
  }

  private void insertBranch(String branchUuid, boolean migrated) {
    db.executeInsert("project_branches",
      "uuid", branchUuid,
      "kee", branchUuid,
      "branch_type", "LONG",
      "project_uuid", uuidFactory.create(),
      MEASURES_MIGRATED_COLUMN, migrated,
      "need_issue_sync", false,
      "is_main", true,
      "created_at", 12L,
      "updated_at", 12L
    );
  }


}
