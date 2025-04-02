/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.opensearch.executor;

import static org.opensearch.sql.calcite.utils.OpenSearchTypeFactory.convertRelDataTypeToExprType;

import java.security.AccessController;
import java.security.PrivilegedAction;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.opensearch.sql.calcite.CalcitePlanContext;
import org.opensearch.sql.calcite.utils.CalciteToolsHelper.OpenSearchRelRunners;
import org.opensearch.sql.common.response.ResponseListener;
import org.opensearch.sql.data.model.ExprTupleValue;
import org.opensearch.sql.data.model.ExprValue;
import org.opensearch.sql.data.type.ExprType;
import org.opensearch.sql.executor.ExecutionContext;
import org.opensearch.sql.executor.ExecutionEngine;
import org.opensearch.sql.executor.ExecutionEngine.Schema.Column;
import org.opensearch.sql.executor.Explain;
import org.opensearch.sql.executor.pagination.PlanSerializer;
import org.opensearch.sql.opensearch.client.OpenSearchClient;
import org.opensearch.sql.opensearch.executor.protector.ExecutionProtector;
import org.opensearch.sql.opensearch.util.JdbcOpenSearchDataTypeConvertor;
import org.opensearch.sql.planner.physical.PhysicalPlan;
import org.opensearch.sql.storage.TableScanOperator;

/** OpenSearch execution engine implementation. */
@RequiredArgsConstructor
public class OpenSearchExecutionEngine implements ExecutionEngine {

  private final OpenSearchClient client;

  private final ExecutionProtector executionProtector;
  private final PlanSerializer planSerializer;

  @Override
  public void execute(PhysicalPlan physicalPlan, ResponseListener<QueryResponse> listener) {
    execute(physicalPlan, ExecutionContext.emptyExecutionContext(), listener);
  }

  @Override
  public void execute(
      PhysicalPlan physicalPlan,
      ExecutionContext context,
      ResponseListener<QueryResponse> listener) {
    PhysicalPlan plan = executionProtector.protect(physicalPlan);
    client.schedule(
        () -> {
          try {
            List<ExprValue> result = new ArrayList<>();

            context.getSplit().ifPresent(plan::add);
            plan.open();

            while (plan.hasNext()) {
              result.add(plan.next());
            }

            QueryResponse response =
                new QueryResponse(
                    physicalPlan.schema(), result, planSerializer.convertToCursor(plan));
            listener.onResponse(response);
          } catch (Exception e) {
            listener.onFailure(e);
          } finally {
            plan.close();
          }
        });
  }

  @Override
  public void explain(PhysicalPlan plan, ResponseListener<ExplainResponse> listener) {
    client.schedule(
        () -> {
          try {
            Explain openSearchExplain =
                new Explain() {
                  @Override
                  public ExplainResponseNode visitTableScan(
                      TableScanOperator node, Object context) {
                    return explain(
                        node,
                        context,
                        explainNode -> {
                          explainNode.setDescription(Map.of("request", node.explain()));
                        });
                  }
                };

            listener.onResponse(openSearchExplain.apply(plan));
          } catch (Exception e) {
            listener.onFailure(e);
          }
        });
  }

  @Override
  public void execute(
      RelNode rel, CalcitePlanContext context, ResponseListener<QueryResponse> listener) {
    client.schedule(
        () ->
            AccessController.doPrivileged(
                (PrivilegedAction<Void>)
                    () -> {
                      try (PreparedStatement statement = OpenSearchRelRunners.run(context, rel)) {
                        ResultSet result = statement.executeQuery();
                        buildResultSet(result, rel.getRowType(), listener);
                      } catch (SQLException e) {
                        listener.onFailure(e);
                      }
                      return null;
                    }));
  }

  private void buildResultSet(
      ResultSet resultSet, RelDataType rowTypes, ResponseListener<QueryResponse> listener)
      throws SQLException {
    // Get the ResultSet metadata to know about columns
    ResultSetMetaData metaData = resultSet.getMetaData();
    int columnCount = metaData.getColumnCount();
    List<RelDataType> fieldTypes =
        rowTypes.getFieldList().stream().map(RelDataTypeField::getType).toList();
    List<ExprValue> values = new ArrayList<>();
    // Iterate through the ResultSet
    while (resultSet.next()) {
      Map<String, ExprValue> row = new LinkedHashMap<String, ExprValue>();
      // Loop through each column
      for (int i = 1; i <= columnCount; i++) {
        String columnName = metaData.getColumnName(i);
        int sqlType = metaData.getColumnType(i);
        RelDataType fieldType = fieldTypes.get(i - 1);
        ExprValue exprValue =
            JdbcOpenSearchDataTypeConvertor.getExprValueFromSqlType(
                resultSet, i, sqlType, fieldType);
        row.put(columnName, exprValue);
      }
      values.add(ExprTupleValue.fromExprValueMap(row));
    }

    List<Column> columns = new ArrayList<>(metaData.getColumnCount());
    for (int i = 1; i <= columnCount; ++i) {
      String columnName = metaData.getColumnName(i);
      RelDataType fieldType = fieldTypes.get(i - 1);
      ExprType exprType = convertRelDataTypeToExprType(fieldType);
      columns.add(new Column(columnName, null, exprType));
    }
    Schema schema = new Schema(columns);
    QueryResponse response = new QueryResponse(schema, values, null);
    listener.onResponse(response);
  }
}
