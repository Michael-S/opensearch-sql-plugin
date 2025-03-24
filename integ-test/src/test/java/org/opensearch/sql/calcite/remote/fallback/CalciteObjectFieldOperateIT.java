/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.calcite.remote.fallback;

import org.junit.Ignore;
import org.opensearch.sql.ppl.ObjectFieldOperateIT;

@Ignore("https://github.com/opensearch-project/sql/issues/3452")
public class CalciteObjectFieldOperateIT extends ObjectFieldOperateIT {
  @Override
  public void init() throws Exception {
    super.init();
    enableCalcite();
  }
}
