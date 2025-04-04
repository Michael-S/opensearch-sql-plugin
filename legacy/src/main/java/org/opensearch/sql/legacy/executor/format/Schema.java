/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.legacy.executor.format;

import static java.util.Collections.unmodifiableList;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.opensearch.sql.legacy.domain.IndexStatement;

public class Schema implements Iterable<Schema.Column> {

  private String indexName;
  private List<Column> columns;

  private static final Set<String> types;

  static {
    types = getTypes();
  }

  public Schema(String indexName, List<Column> columns) {
    this.indexName = indexName;
    this.columns = columns;
  }

  public Schema(IndexStatement statement, List<Column> columns) {
    this.indexName = statement.getIndexPattern();
    this.columns = columns;
  }

  public Schema(List<Column> columns) {
    this.columns = columns;
  }

  public String getIndexName() {
    return indexName;
  }

  public List<String> getHeaders() {
    return columns.stream().map(column -> column.getName()).collect(Collectors.toList());
  }

  public List<Column> getColumns() {
    return unmodifiableList(columns);
  }

  private static Set<String> getTypes() {
    HashSet<String> types = new HashSet<>();
    for (Type type : Type.values()) {
      types.add(type.name());
    }

    return types;
  }

  // A method for efficiently checking if a Type exists
  public static boolean hasType(String type) {
    return types.contains(type);
  }

  // Iterator method for Schema
  @Override
  public Iterator<Column> iterator() {
    return new Iterator<Column>() {
      private final Iterator<Column> iter = columns.iterator();

      @Override
      public boolean hasNext() {
        return iter.hasNext();
      }

      @Override
      public Column next() {
        return iter.next();
      }

      @Override
      public void remove() {
        throw new UnsupportedOperationException("No changes allowed to Schema columns");
      }
    };
  }

  // Only core OpenSearch datatypes currently supported
  public enum Type {
    TEXT,
    KEYWORD,
    IP, // String types
    LONG,
    INTEGER,
    SHORT,
    BYTE,
    DOUBLE,
    FLOAT,
    HALF_FLOAT,
    SCALED_FLOAT, // Numeric types
    DATE, // Date types
    BOOLEAN, // Boolean types
    BINARY, // Binary types
    OBJECT,
    NESTED,
    INTEGER_RANGE,
    FLOAT_RANGE,
    LONG_RANGE,
    DOUBLE_RANGE,
    DATE_RANGE; // Range types

    public String nameLowerCase() {
      return name().toLowerCase();
    }
  }

  // Inner class for Column object
  public static class Column {

    private final String name;
    private final String alias;
    private final Type type;

    private final boolean identifiedByAlias;

    public Column(String name, String alias, Type type, boolean identifiedByAlias) {
      this.name = name;
      this.alias = alias;
      this.type = type;
      this.identifiedByAlias = identifiedByAlias;
    }

    public Column(String name, String alias, Type type) {
      this(name, alias, type, false);
    }

    public String getName() {
      return name;
    }

    public String getAlias() {
      return alias;
    }

    public String getType() {
      return type.nameLowerCase();
    }

    /*
     * Some query types (like JOIN) label the data in SearchHit using alias instead of field name if it's given.
     *
     * This method returns the alias as the identifier if the identifiedByAlias flag is set for such cases so that
     * the correct identifier is used to access related data in DataRows.
     */
    public String getIdentifier() {
      if (identifiedByAlias && alias != null) {
        return alias;
      } else {
        return name;
      }
    }

    public Type getEnumType() {
      return type;
    }
  }
}
