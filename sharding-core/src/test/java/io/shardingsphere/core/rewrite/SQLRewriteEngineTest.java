/*
 * Copyright 2016-2018 shardingsphere.io.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * </p>
 */

package io.shardingsphere.core.rewrite;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import io.shardingsphere.core.constant.DatabaseType;
import io.shardingsphere.core.constant.OrderDirection;
import io.shardingsphere.core.optimizer.condition.ShardingCondition;
import io.shardingsphere.core.optimizer.condition.ShardingConditions;
import io.shardingsphere.core.optimizer.insert.InsertShardingCondition;
import io.shardingsphere.core.parsing.parser.context.OrderItem;
import io.shardingsphere.core.parsing.parser.context.limit.Limit;
import io.shardingsphere.core.parsing.parser.context.limit.LimitValue;
import io.shardingsphere.core.parsing.parser.context.table.Table;
import io.shardingsphere.core.parsing.parser.sql.dal.DALStatement;
import io.shardingsphere.core.parsing.parser.sql.dml.DMLStatement;
import io.shardingsphere.core.parsing.parser.sql.dml.insert.InsertStatement;
import io.shardingsphere.core.parsing.parser.sql.dql.select.SelectStatement;
import io.shardingsphere.core.parsing.parser.token.IndexToken;
import io.shardingsphere.core.parsing.parser.token.InsertColumnToken;
import io.shardingsphere.core.parsing.parser.token.InsertValuesToken;
import io.shardingsphere.core.parsing.parser.token.ItemsToken;
import io.shardingsphere.core.parsing.parser.token.OffsetToken;
import io.shardingsphere.core.parsing.parser.token.OrderByToken;
import io.shardingsphere.core.parsing.parser.token.RowCountToken;
import io.shardingsphere.core.parsing.parser.token.SchemaToken;
import io.shardingsphere.core.parsing.parser.token.TableToken;
import io.shardingsphere.core.property.DataSourcePropertyManager;
import io.shardingsphere.core.routing.type.RoutingTable;
import io.shardingsphere.core.routing.type.TableUnit;
import io.shardingsphere.core.rule.DataNode;
import io.shardingsphere.core.rule.ShardingRule;
import io.shardingsphere.core.yaml.sharding.YamlShardingConfiguration;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

public final class SQLRewriteEngineTest {
    
    private ShardingRule shardingRule;
    
    private SelectStatement selectStatement;
    
    private InsertStatement insertStatement;
    
    private DALStatement showTablesStatement;
    
    private DMLStatement dmlStatement;
    
    private Map<String, String> tableTokens;
    
    private DataSourcePropertyManager dataSourcePropertyManager;
    
    @Before
    public void setUp() throws IOException {
        URL url = SQLRewriteEngineTest.class.getClassLoader().getResource("yaml/rewrite-rule.yaml");
        Preconditions.checkNotNull(url, "Cannot found rewrite rule yaml configuration.");
        YamlShardingConfiguration yamlShardingConfig = YamlShardingConfiguration.unmarshal(new File(url.getFile()));
        shardingRule = yamlShardingConfig.getShardingRule(yamlShardingConfig.getDataSources().keySet());
        selectStatement = new SelectStatement();
        insertStatement = new InsertStatement();
        showTablesStatement = new DALStatement();
        dmlStatement = new DMLStatement();
        tableTokens = new HashMap<>(1, 1);
        tableTokens.put("table_x", "table_1");
        dataSourcePropertyManager = Mockito.mock(DataSourcePropertyManager.class);
        Mockito.when(dataSourcePropertyManager.getActualSchemaName(Mockito.anyString())).thenReturn("actual_db");
        
    }
    
    @Test
    public void assertRewriteWithoutChange() {
        SQLRewriteEngine rewriteEngine = new SQLRewriteEngine(shardingRule, "SELECT table_y.id FROM table_y WHERE table_y.id=?",
                DatabaseType.MySQL, selectStatement, null, Collections.<Object>singletonList(1));
        assertThat(rewriteEngine.rewrite(true).toSQL(null, tableTokens, null, dataSourcePropertyManager).getSql(), is("SELECT table_y.id FROM table_y WHERE table_y.id=?"));
    }
    
    @Test
    public void assertRewriteForTableName() {
        List<Object> parameters = new ArrayList<>(2);
        parameters.add(1);
        parameters.add("x");
        selectStatement.getSqlTokens().add(new TableToken(7, 0, "table_x"));
        selectStatement.getSqlTokens().add(new TableToken(31, 0, "table_x"));
        selectStatement.getSqlTokens().add(new TableToken(47, 0, "table_x"));
        SQLRewriteEngine rewriteEngine = new SQLRewriteEngine(shardingRule, "SELECT table_x.id, x.name FROM table_x x WHERE table_x.id=? AND x.name=?", DatabaseType.MySQL, selectStatement, null, parameters);
        assertThat(rewriteEngine.rewrite(true).toSQL(null, tableTokens, null, dataSourcePropertyManager).getSql(), is("SELECT table_1.id, x.name FROM table_1 x WHERE table_1.id=? AND x.name=?"));
    }
    
    @Test
    public void assertRewriteForOrderByAndGroupByDerivedColumns() {
        selectStatement.getSqlTokens().add(new TableToken(18, 0, "table_x"));
        ItemsToken itemsToken = new ItemsToken(12);
        itemsToken.getItems().addAll(Arrays.asList("x.id as ORDER_BY_DERIVED_0", "x.name as GROUP_BY_DERIVED_0"));
        selectStatement.getSqlTokens().add(itemsToken);
        SQLRewriteEngine rewriteEngine = new SQLRewriteEngine(shardingRule, "SELECT x.age FROM table_x x GROUP BY x.id ORDER BY x.name", DatabaseType.MySQL, selectStatement, null, Collections.emptyList());
        assertThat(rewriteEngine.rewrite(true).toSQL(null, tableTokens, null, dataSourcePropertyManager).getSql(), is("SELECT x.age, x.id as ORDER_BY_DERIVED_0, x.name as GROUP_BY_DERIVED_0 FROM table_1 x GROUP BY x.id ORDER BY x.name"));
    }
    
    @Test
    public void assertRewriteForAggregationDerivedColumns() {
        selectStatement.getSqlTokens().add(new TableToken(23, 0, "table_x"));
        ItemsToken itemsToken = new ItemsToken(17);
        itemsToken.getItems().addAll(Arrays.asList("COUNT(x.age) as AVG_DERIVED_COUNT_0", "SUM(x.age) as AVG_DERIVED_SUM_0"));
        selectStatement.getSqlTokens().add(itemsToken);
        SQLRewriteEngine rewriteEngine = new SQLRewriteEngine(shardingRule, "SELECT AVG(x.age) FROM table_x x", DatabaseType.MySQL, selectStatement, null, Collections.emptyList());
        assertThat(rewriteEngine.rewrite(true).toSQL(null, tableTokens, null, dataSourcePropertyManager).getSql(), is("SELECT AVG(x.age), COUNT(x.age) as AVG_DERIVED_COUNT_0, SUM(x.age) as AVG_DERIVED_SUM_0 FROM table_1 x"));
    }
    
    @Test
    public void assertRewriteForAutoGeneratedKeyColumn() {
        List<Object> parameters = new ArrayList<>(2);
        parameters.add("x");
        parameters.add(1);
        insertStatement.setParametersIndex(2);
        insertStatement.setInsertValuesListLastPosition(45);
        insertStatement.getSqlTokens().add(new TableToken(12, 0, "table_x"));
        ItemsToken itemsToken = new ItemsToken(30);
        itemsToken.getItems().add("id");
        insertStatement.getSqlTokens().add(itemsToken);
        insertStatement.getSqlTokens().add(new InsertValuesToken(39, "table_x"));
        InsertShardingCondition shardingCondition = new InsertShardingCondition("(?, ?, ?)", parameters);
        shardingCondition.getDataNodes().add(new DataNode("db0.table_x"));
        TableUnit tableUnit = new TableUnit("db0");
        tableUnit.getRoutingTables().add(new RoutingTable("table_x", "table_x"));
        SQLRewriteEngine rewriteEngine = new SQLRewriteEngine(
                shardingRule, "INSERT INTO table_x (name, age) VALUES (?, ?)", DatabaseType.MySQL, insertStatement, new ShardingConditions(Collections.<ShardingCondition>singletonList(shardingCondition)), parameters);
        assertThat(rewriteEngine.rewrite(true).toSQL(tableUnit, tableTokens, null, dataSourcePropertyManager).getSql(), is("INSERT INTO table_1 (name, age, id) VALUES (?, ?, ?)"));
    }
    
    @Test
    public void assertRewriteForAutoGeneratedKeyColumnWithoutColumnsWithParameter() {
        List<Object> parameters = new ArrayList<>();
        parameters.add("Bill");
        insertStatement.setParametersIndex(1);
        insertStatement.getSqlTokens().add(new TableToken(12, 0, "`table_x`"));
        insertStatement.setGenerateKeyColumnIndex(0);
        insertStatement.setInsertValuesListLastPosition(32);
        insertStatement.getSqlTokens().add(new InsertColumnToken(21, "("));
        ItemsToken itemsToken = new ItemsToken(21);
        itemsToken.setFirstOfItemsSpecial(true);
        itemsToken.getItems().add("name");
        itemsToken.getItems().add("id");
        insertStatement.getSqlTokens().add(itemsToken);
        insertStatement.getSqlTokens().add(new InsertColumnToken(21, ")"));
        insertStatement.getSqlTokens().add(new InsertValuesToken(29, "table_x"));
        InsertShardingCondition shardingCondition = new InsertShardingCondition("(?, ?)", parameters);
        shardingCondition.getDataNodes().add(new DataNode("db0.table_x"));
        TableUnit tableUnit = new TableUnit("db0");
        tableUnit.getRoutingTables().add(new RoutingTable("table_x", "table_x"));
        SQLRewriteEngine rewriteEngine = new SQLRewriteEngine(
                shardingRule, "INSERT INTO `table_x` VALUES (?)", DatabaseType.MySQL, insertStatement, new ShardingConditions(Collections.<ShardingCondition>singletonList(shardingCondition)), parameters);
        assertThat(rewriteEngine.rewrite(true).toSQL(tableUnit, tableTokens, null, dataSourcePropertyManager).getSql(), is("INSERT INTO table_1(name, id) VALUES (?, ?)"));
    }
    
    @Test
    public void assertRewriteForAutoGeneratedKeyColumnWithoutColumnsWithoutParameter() {
        insertStatement.getSqlTokens().add(new TableToken(12, 0, "`table_x`"));
        insertStatement.setGenerateKeyColumnIndex(0);
        insertStatement.setInsertValuesListLastPosition(33);
        insertStatement.getSqlTokens().add(new InsertColumnToken(21, "("));
        ItemsToken itemsToken = new ItemsToken(21);
        itemsToken.setFirstOfItemsSpecial(true);
        itemsToken.getItems().add("name");
        itemsToken.getItems().add("id");
        insertStatement.getSqlTokens().add(itemsToken);
        insertStatement.getSqlTokens().add(new InsertColumnToken(21, ")"));
        insertStatement.getSqlTokens().add(new InsertValuesToken(29, "table_x"));
        InsertShardingCondition shardingCondition = new InsertShardingCondition("(10, 1)", Collections.emptyList());
        shardingCondition.getDataNodes().add(new DataNode("db0.table_x"));
        TableUnit tableUnit = new TableUnit("db0");
        tableUnit.getRoutingTables().add(new RoutingTable("table_x", "table_x"));
        SQLRewriteEngine rewriteEngine = new SQLRewriteEngine(
                shardingRule, "INSERT INTO `table_x` VALUES (10)", DatabaseType.MySQL, insertStatement, new ShardingConditions(Collections.<ShardingCondition>singletonList(shardingCondition)), Collections.emptyList());
        assertThat(rewriteEngine.rewrite(true).toSQL(tableUnit, tableTokens, null, dataSourcePropertyManager).getSql(), is("INSERT INTO table_1(name, id) VALUES (10, 1)"));
    }
    
    @Test
    public void assertRewriteColumnWithoutColumnsWithoutParameter() {
        List<Object> parameters = new ArrayList<>(2);
        parameters.add("x");
        parameters.add(1);
        insertStatement.getSqlTokens().add(new TableToken(12, 0, "`table_x`"));
        insertStatement.setGenerateKeyColumnIndex(0);
        insertStatement.setInsertValuesListLastPosition(36);
        insertStatement.getSqlTokens().add(new InsertColumnToken(21, "("));
        ItemsToken itemsToken = new ItemsToken(21);
        itemsToken.setFirstOfItemsSpecial(true);
        itemsToken.getItems().add("name");
        itemsToken.getItems().add("id");
        insertStatement.getSqlTokens().add(itemsToken);
        insertStatement.getSqlTokens().add(new InsertColumnToken(21, ")"));
        insertStatement.getSqlTokens().add(new InsertValuesToken(29, "table_x"));
        InsertShardingCondition shardingCondition = new InsertShardingCondition("(10, 1)", parameters);
        shardingCondition.getDataNodes().add(new DataNode("db0.table_x"));
        TableUnit tableUnit = new TableUnit("db0");
        tableUnit.getRoutingTables().add(new RoutingTable("table_x", "table_x"));
        SQLRewriteEngine rewriteEngine = new SQLRewriteEngine(
                shardingRule, "INSERT INTO `table_x` VALUES (10, 1)", DatabaseType.MySQL, insertStatement, new ShardingConditions(Collections.<ShardingCondition>singletonList(shardingCondition)), parameters);
        assertThat(rewriteEngine.rewrite(true).toSQL(tableUnit, tableTokens, null, dataSourcePropertyManager).getSql(), is("INSERT INTO table_1(name, id) VALUES (10, 1)"));
    }
    
    @Test
    public void assertRewriteColumnWithoutColumnsWithParameter() {
        List<Object> parameters = new ArrayList<>(2);
        parameters.add("x");
        parameters.add(1);
        insertStatement.getSqlTokens().add(new TableToken(12, 0, "`table_x`"));
        insertStatement.setGenerateKeyColumnIndex(0);
        insertStatement.setInsertValuesListLastPosition(35);
        insertStatement.getSqlTokens().add(new InsertColumnToken(21, "("));
        ItemsToken itemsToken = new ItemsToken(21);
        itemsToken.setFirstOfItemsSpecial(true);
        itemsToken.getItems().add("name");
        itemsToken.getItems().add("id");
        insertStatement.getSqlTokens().add(itemsToken);
        insertStatement.getSqlTokens().add(new InsertColumnToken(21, ")"));
        insertStatement.getSqlTokens().add(new InsertValuesToken(29, "table_x"));
        InsertShardingCondition shardingCondition = new InsertShardingCondition("(?, ?)", parameters);
        shardingCondition.getDataNodes().add(new DataNode("db0.table_x"));
        TableUnit tableUnit = new TableUnit("db0");
        tableUnit.getRoutingTables().add(new RoutingTable("table_x", "table_x"));
        SQLRewriteEngine rewriteEngine = new SQLRewriteEngine(
                shardingRule, "INSERT INTO `table_x` VALUES (?, ?)", DatabaseType.MySQL, insertStatement, new ShardingConditions(Collections.<ShardingCondition>singletonList(shardingCondition)), parameters);
        assertThat(rewriteEngine.rewrite(true).toSQL(tableUnit, tableTokens, null, dataSourcePropertyManager).getSql(), is("INSERT INTO table_1(name, id) VALUES (?, ?)"));
    }
    
    @Test
    public void assertRewriteForLimit() {
        selectStatement.setLimit(new Limit(DatabaseType.MySQL));
        selectStatement.getLimit().setOffset(new LimitValue(2, -1, true));
        selectStatement.getLimit().setRowCount(new LimitValue(2, -1, false));
        selectStatement.getSqlTokens().add(new TableToken(17, 0, "table_x"));
        selectStatement.getSqlTokens().add(new OffsetToken(33, 2));
        selectStatement.getSqlTokens().add(new RowCountToken(36, 2));
        SQLRewriteEngine rewriteEngine = new SQLRewriteEngine(shardingRule, "SELECT x.id FROM table_x x LIMIT 2, 2", DatabaseType.MySQL, selectStatement, null, Collections.emptyList());
        assertThat(rewriteEngine.rewrite(true).toSQL(null, tableTokens, null, dataSourcePropertyManager).getSql(), is("SELECT x.id FROM table_1 x LIMIT 0, 4"));
    }
    
    @Test
    public void assertRewriteForRowNum() {
        selectStatement.setLimit(new Limit(DatabaseType.Oracle));
        selectStatement.getLimit().setOffset(new LimitValue(2, -1, true));
        selectStatement.getLimit().setRowCount(new LimitValue(4, -1, false));
        selectStatement.getSqlTokens().add(new TableToken(68, 0, "table_x"));
        selectStatement.getSqlTokens().add(new OffsetToken(119, 2));
        selectStatement.getSqlTokens().add(new RowCountToken(98, 4));
        SQLRewriteEngine rewriteEngine = new SQLRewriteEngine(shardingRule,
                "SELECT * FROM (SELECT row_.*, rownum rownum_ FROM (SELECT x.id FROM table_x x) row_ WHERE rownum<=4) t WHERE t.rownum_>2", DatabaseType.Oracle, selectStatement, null, Collections.emptyList());
        assertThat(rewriteEngine.rewrite(true).toSQL(null, tableTokens, null, dataSourcePropertyManager).getSql(),
                is("SELECT * FROM (SELECT row_.*, rownum rownum_ FROM (SELECT x.id FROM table_1 x) row_ WHERE rownum<=4) t WHERE t.rownum_>0"));
    }
    
    @Test
    public void assertRewriteForTopAndRowNumber() {
        selectStatement.setLimit(new Limit(DatabaseType.SQLServer));
        selectStatement.getLimit().setOffset(new LimitValue(2, -1, true));
        selectStatement.getLimit().setRowCount(new LimitValue(4, -1, false));
        selectStatement.getSqlTokens().add(new TableToken(85, 0, "table_x"));
        selectStatement.getSqlTokens().add(new OffsetToken(123, 2));
        selectStatement.getSqlTokens().add(new RowCountToken(26, 4));
        SQLRewriteEngine rewriteEngine = new SQLRewriteEngine(shardingRule,
                "SELECT * FROM (SELECT TOP(4) row_number() OVER (ORDER BY x.id) AS rownum_, x.id FROM table_x x) AS row_ WHERE row_.rownum_>2", DatabaseType.SQLServer, selectStatement, null, Collections.emptyList());
        assertThat(rewriteEngine.rewrite(true).toSQL(null, tableTokens, null, dataSourcePropertyManager).getSql(),
                is("SELECT * FROM (SELECT TOP(4) row_number() OVER (ORDER BY x.id) AS rownum_, x.id FROM table_1 x) AS row_ WHERE row_.rownum_>0"));
    }
    
    @Test
    public void assertRewriteForLimitForMemoryGroupBy() {
        selectStatement.setLimit(new Limit(DatabaseType.MySQL));
        selectStatement.getLimit().setOffset(new LimitValue(2, -1, true));
        selectStatement.getLimit().setRowCount(new LimitValue(2, -1, false));
        selectStatement.getOrderByItems().add(new OrderItem("x", "id", OrderDirection.ASC, OrderDirection.ASC, Optional.<String>absent()));
        selectStatement.getGroupByItems().add(new OrderItem("x", "id", OrderDirection.DESC, OrderDirection.ASC, Optional.<String>absent()));
        selectStatement.getSqlTokens().add(new TableToken(17, 0, "table_x"));
        selectStatement.getSqlTokens().add(new OffsetToken(33, 2));
        selectStatement.getSqlTokens().add(new RowCountToken(36, 2));
        SQLRewriteEngine rewriteEngine = new SQLRewriteEngine(shardingRule, "SELECT x.id FROM table_x x LIMIT 2, 2", DatabaseType.MySQL, selectStatement, null, Collections.emptyList());
        assertThat(rewriteEngine.rewrite(true).toSQL(null, tableTokens, null, dataSourcePropertyManager).getSql(), is("SELECT x.id FROM table_1 x LIMIT 0, 2147483647"));
    }
    
    @Test
    public void assertRewriteForRowNumForMemoryGroupBy() {
        selectStatement.setLimit(new Limit(DatabaseType.Oracle));
        selectStatement.getLimit().setOffset(new LimitValue(2, -1, true));
        selectStatement.getLimit().setRowCount(new LimitValue(4, -1, false));
        selectStatement.getSqlTokens().add(new TableToken(68, 0, "table_x"));
        selectStatement.getSqlTokens().add(new OffsetToken(119, 2));
        selectStatement.getSqlTokens().add(new RowCountToken(98, 4));
        selectStatement.getOrderByItems().add(new OrderItem("x", "id", OrderDirection.ASC, OrderDirection.ASC, Optional.<String>absent()));
        selectStatement.getGroupByItems().add(new OrderItem("x", "id", OrderDirection.DESC, OrderDirection.ASC, Optional.<String>absent()));
        SQLRewriteEngine rewriteEngine = new SQLRewriteEngine(shardingRule,
                "SELECT * FROM (SELECT row_.*, rownum rownum_ FROM (SELECT x.id FROM table_x x) row_ WHERE rownum<=4) t WHERE t.rownum_>2", DatabaseType.Oracle, selectStatement, null, Collections.emptyList());
        assertThat(rewriteEngine.rewrite(true).toSQL(null, tableTokens, null, dataSourcePropertyManager).getSql(),
                is("SELECT * FROM (SELECT row_.*, rownum rownum_ FROM (SELECT x.id FROM table_1 x) row_ WHERE rownum<=2147483647) t WHERE t.rownum_>0"));
    }
    
    @Test
    public void assertRewriteForTopAndRowNumberForMemoryGroupBy() {
        selectStatement.setLimit(new Limit(DatabaseType.SQLServer));
        selectStatement.getLimit().setOffset(new LimitValue(2, -1, true));
        selectStatement.getLimit().setRowCount(new LimitValue(4, -1, false));
        selectStatement.getSqlTokens().add(new TableToken(85, 0, "table_x"));
        selectStatement.getSqlTokens().add(new OffsetToken(123, 2));
        selectStatement.getSqlTokens().add(new RowCountToken(26, 4));
        selectStatement.getOrderByItems().add(new OrderItem("x", "id", OrderDirection.ASC, OrderDirection.ASC, Optional.<String>absent()));
        selectStatement.getGroupByItems().add(new OrderItem("x", "id", OrderDirection.DESC, OrderDirection.ASC, Optional.<String>absent()));
        SQLRewriteEngine rewriteEngine = new SQLRewriteEngine(shardingRule,
                "SELECT * FROM (SELECT TOP(4) row_number() OVER (ORDER BY x.id) AS rownum_, x.id FROM table_x x) AS row_ WHERE row_.rownum_>2", DatabaseType.SQLServer, selectStatement, null, Collections.emptyList());
        assertThat(rewriteEngine.rewrite(true).toSQL(null, tableTokens, null, dataSourcePropertyManager).getSql(),
                is("SELECT * FROM (SELECT TOP(2147483647) row_number() OVER (ORDER BY x.id) AS rownum_, x.id FROM table_1 x) AS row_ WHERE row_.rownum_>0"));
    }
    
    @Test
    public void assertRewriteForLimitForNotRewriteLimit() {
        selectStatement.setLimit(new Limit(DatabaseType.MySQL));
        selectStatement.getLimit().setOffset(new LimitValue(2, -1, true));
        selectStatement.getLimit().setRowCount(new LimitValue(2, -1, false));
        selectStatement.getSqlTokens().add(new TableToken(17, 0, "table_x"));
        selectStatement.getSqlTokens().add(new OffsetToken(33, 2));
        selectStatement.getSqlTokens().add(new RowCountToken(36, 2));
        SQLRewriteEngine rewriteEngine = new SQLRewriteEngine(shardingRule, "SELECT x.id FROM table_x x LIMIT 2, 2", DatabaseType.MySQL, selectStatement, null, Collections.emptyList());
        assertThat(rewriteEngine.rewrite(false).toSQL(null, tableTokens, null, dataSourcePropertyManager).getSql(), is("SELECT x.id FROM table_1 x LIMIT 2, 2"));
    }
    
    @Test
    public void assertRewriteForRowNumForNotRewriteLimit() {
        selectStatement.setLimit(new Limit(DatabaseType.Oracle));
        selectStatement.getLimit().setOffset(new LimitValue(2, -1, true));
        selectStatement.getLimit().setRowCount(new LimitValue(4, -1, false));
        selectStatement.getSqlTokens().add(new TableToken(68, 0, "table_x"));
        selectStatement.getSqlTokens().add(new OffsetToken(119, 2));
        selectStatement.getSqlTokens().add(new RowCountToken(98, 4));
        SQLRewriteEngine rewriteEngine = new SQLRewriteEngine(shardingRule,
                "SELECT * FROM (SELECT row_.*, rownum rownum_ FROM (SELECT x.id FROM table_x x) row_ WHERE rownum<=4) t WHERE t.rownum_>2", DatabaseType.Oracle, selectStatement, null, Collections.emptyList());
        assertThat(rewriteEngine.rewrite(false).toSQL(null, tableTokens, null, dataSourcePropertyManager).getSql(),
                is("SELECT * FROM (SELECT row_.*, rownum rownum_ FROM (SELECT x.id FROM table_1 x) row_ WHERE rownum<=4) t WHERE t.rownum_>2"));
    }
    
    @Test
    public void assertRewriteForTopAndRowNumberForNotRewriteLimit() {
        selectStatement.setLimit(new Limit(DatabaseType.SQLServer));
        selectStatement.getLimit().setOffset(new LimitValue(2, -1, true));
        selectStatement.getLimit().setRowCount(new LimitValue(4, -1, false));
        selectStatement.getSqlTokens().add(new TableToken(85, 0, "table_x"));
        selectStatement.getSqlTokens().add(new OffsetToken(123, 2));
        selectStatement.getSqlTokens().add(new RowCountToken(26, 4));
        SQLRewriteEngine rewriteEngine = new SQLRewriteEngine(shardingRule,
                "SELECT * FROM (SELECT TOP(4) row_number() OVER (ORDER BY x.id) AS rownum_, x.id FROM table_x x) AS row_ WHERE row_.rownum_>2", DatabaseType.SQLServer, selectStatement, null, Collections.emptyList());
        assertThat(rewriteEngine.rewrite(false).toSQL(null, tableTokens, null, dataSourcePropertyManager).getSql(),
                is("SELECT * FROM (SELECT TOP(4) row_number() OVER (ORDER BY x.id) AS rownum_, x.id FROM table_1 x) AS row_ WHERE row_.rownum_>2"));
    }
    
    @Test
    public void assertRewriteForDerivedOrderBy() {
        selectStatement.setGroupByLastPosition(61);
        selectStatement.getOrderByItems().add(new OrderItem("x", "id", OrderDirection.ASC, OrderDirection.ASC, Optional.<String>absent()));
        selectStatement.getOrderByItems().add(new OrderItem("x", "name", OrderDirection.DESC, OrderDirection.ASC, Optional.<String>absent()));
        selectStatement.getSqlTokens().add(new TableToken(25, 0, "table_x"));
        selectStatement.getSqlTokens().add(new OrderByToken(61));
        SQLRewriteEngine rewriteEngine = new SQLRewriteEngine(shardingRule, "SELECT x.id, x.name FROM table_x x GROUP BY x.id, x.name DESC", DatabaseType.MySQL, selectStatement, null, Collections.emptyList());
        assertThat(rewriteEngine.rewrite(true).toSQL(null, tableTokens, null, dataSourcePropertyManager).getSql(), is("SELECT x.id, x.name FROM table_1 x GROUP BY x.id, x.name DESC ORDER BY id ASC,name DESC "));
    }
    
    @Test
    public void assertGenerateSQL() {
        List<Object> parameters = new ArrayList<>(2);
        parameters.add(1);
        parameters.add("x");
        selectStatement.getSqlTokens().add(new TableToken(7, 0, "table_x"));
        selectStatement.getSqlTokens().add(new TableToken(31, 0, "table_x"));
        selectStatement.getSqlTokens().add(new TableToken(58, 0, "table_x"));
        selectStatement.getTables().add(new Table("table_x", Optional.of("x")));
        selectStatement.getTables().add(new Table("table_y", Optional.of("y")));
        SQLRewriteEngine sqlRewriteEngine =
                new SQLRewriteEngine(shardingRule, "SELECT table_x.id, x.name FROM table_x x, table_y y WHERE table_x.id=? AND x.name=?", DatabaseType.MySQL, selectStatement, null, parameters);
        SQLBuilder sqlBuilder = sqlRewriteEngine.rewrite(true);
        TableUnit tableUnit = new TableUnit("db0");
        tableUnit.getRoutingTables().add(new RoutingTable("table_x", "table_x"));
        assertThat(sqlRewriteEngine.generateSQL(tableUnit, sqlBuilder, dataSourcePropertyManager).getSql(), is("SELECT table_x.id, x.name FROM table_x x, table_y y WHERE table_x.id=? AND x.name=?"));
    }
    
    @Test
    public void assertSchemaTokenRewriteForTableName() {
        tableTokens = new HashMap<>(1, 1);
        tableTokens.put("table_x", "table_y");
        selectStatement.getSqlTokens().add(new TableToken(18, 0, "table_x"));
        selectStatement.getSqlTokens().add(new SchemaToken(29, "table_x", "table_x"));
        SQLRewriteEngine rewriteEngine = new SQLRewriteEngine(shardingRule, "SHOW CREATE TABLE table_x ON table_x", DatabaseType.MySQL, selectStatement, null, Collections.emptyList());
        assertThat(rewriteEngine.rewrite(true).toSQL(null, tableTokens, shardingRule, dataSourcePropertyManager).getSql(), is("SHOW CREATE TABLE table_y ON actual_db"));
    }
    
    @Test
    public void assertIndexTokenForIndexNameTableName() {
        selectStatement.getSqlTokens().add(new IndexToken(13, "index_name", "table_x"));
        selectStatement.getSqlTokens().add(new TableToken(27, 0, "table_x"));
        SQLRewriteEngine rewriteEngine = new SQLRewriteEngine(shardingRule, "CREATE INDEX index_name ON table_x ('column')", DatabaseType.MySQL, selectStatement, null, Collections.emptyList());
        assertThat(rewriteEngine.rewrite(true).toSQL(null, tableTokens, shardingRule, dataSourcePropertyManager).getSql(), is("CREATE INDEX index_name_table_1 ON table_1 ('column')"));
    }
    
    @Test
    public void assertIndexTokenForIndexNameTableNameWithoutLogicTableName() {
        selectStatement.getSqlTokens().add(new IndexToken(13, "logic_index", ""));
        selectStatement.getSqlTokens().add(new TableToken(28, 0, "table_x"));
        SQLRewriteEngine rewriteEngine = new SQLRewriteEngine(shardingRule, "CREATE INDEX index_names ON table_x ('column')", DatabaseType.MySQL, selectStatement, null, Collections.emptyList());
        assertThat(rewriteEngine.rewrite(true).toSQL(null, tableTokens, shardingRule, dataSourcePropertyManager).getSql(), is("CREATE INDEX logic_index_table_1 ON table_1 ('column')"));
    }
    
    @Test
    public void assertTableTokenWithoutBackQuoteForShow() {
        showTablesStatement.getSqlTokens().add(new TableToken(18, 0, "table_x"));
        SQLRewriteEngine rewriteEngine = new SQLRewriteEngine(shardingRule, "SHOW COLUMNS FROM table_x", DatabaseType.MySQL, showTablesStatement, null, Collections.emptyList());
        assertThat(rewriteEngine.rewrite(false).toSQL(null, tableTokens, shardingRule, dataSourcePropertyManager).getSql(), is("SHOW COLUMNS FROM table_1"));
    }
    
    @Test
    public void assertTableTokenWithoutBackQuoteFromSchemaForShow() {
        showTablesStatement.getSqlTokens().add(new TableToken(18, 0, "table_x"));
        showTablesStatement.getSqlTokens().add(new SchemaToken(31, "'sharding_db'", "table_x"));
        SQLRewriteEngine rewriteEngine = new SQLRewriteEngine(shardingRule, "SHOW COLUMNS FROM table_x FROM 'sharding_db'", DatabaseType.MySQL, showTablesStatement, null, Collections.emptyList());
        assertThat(rewriteEngine.rewrite(false).toSQL(null, new LinkedHashMap<String, String>(){{put("table_x", "table_x");}}, shardingRule, dataSourcePropertyManager).getSql(), is("SHOW COLUMNS FROM table_x FROM actual_db"));
    }
    
    @Test
    public void assertTableTokenWithBackQuoteForShow() {
        showTablesStatement.getSqlTokens().add(new TableToken(18, 0, "`table_x`"));
        SQLRewriteEngine rewriteEngine = new SQLRewriteEngine(shardingRule, "SHOW COLUMNS FROM `table_x`", DatabaseType.MySQL, showTablesStatement, null, Collections.emptyList());
        assertThat(rewriteEngine.rewrite(false).toSQL(null, tableTokens, shardingRule, dataSourcePropertyManager).getSql(), is("SHOW COLUMNS FROM table_1"));
    }
    
    @Test
    public void assertTableTokenWithBackQuoteFromSchemaForShow() {
        showTablesStatement.getSqlTokens().add(new TableToken(18, 0, "`table_x`"));
        SQLRewriteEngine rewriteEngine = new SQLRewriteEngine(shardingRule, "SHOW COLUMNS FROM `table_x` FROM 'sharding_db'", DatabaseType.MySQL, showTablesStatement, null, Collections.emptyList());
        assertThat(rewriteEngine.rewrite(false).toSQL(null, tableTokens, shardingRule, dataSourcePropertyManager).getSql(), is("SHOW COLUMNS FROM table_1 FROM 'sharding_db'"));
    }
    
    @Test
    public void assertTableTokenWithSchemaForShow() {
        showTablesStatement.getSqlTokens().add(new TableToken(18, "sharding_db".length() + 1, "table_x"));
        SQLRewriteEngine rewriteEngine = new SQLRewriteEngine(shardingRule, "SHOW COLUMNS FROM sharding_db.table_x", DatabaseType.MySQL, showTablesStatement, null, Collections.emptyList());
        assertThat(rewriteEngine.rewrite(false).toSQL(null, tableTokens, shardingRule, dataSourcePropertyManager).getSql(), is("SHOW COLUMNS FROM table_1"));
    }
    
    @Test
    public void assertTableTokenWithSchemaFromSchemaForShow() {
        showTablesStatement.getSqlTokens().add(new TableToken(18, "sharding_db".length() + 1, "table_x"));
        SQLRewriteEngine rewriteEngine = new SQLRewriteEngine(shardingRule, "SHOW COLUMNS FROM sharding_db.table_x FROM sharding_db", DatabaseType.MySQL, showTablesStatement, null, Collections.emptyList());
        assertThat(rewriteEngine.rewrite(false).toSQL(null, tableTokens, shardingRule, dataSourcePropertyManager).getSql(), is("SHOW COLUMNS FROM table_1 FROM sharding_db"));
    }
    
    @Test
    public void assertTableTokenWithBackQuoteWithSchemaForShow() {
        showTablesStatement.getSqlTokens().add(new TableToken(18, "sharding_db".length() + 1, "`table_x`"));
        SQLRewriteEngine rewriteEngine = new SQLRewriteEngine(shardingRule, "SHOW COLUMNS FROM sharding_db.`table_x`", DatabaseType.MySQL, showTablesStatement, null, Collections.emptyList());
        assertThat(rewriteEngine.rewrite(false).toSQL(null, tableTokens, shardingRule, dataSourcePropertyManager).getSql(), is("SHOW COLUMNS FROM table_1"));
    }
    
    @Test
    public void assertTableTokenWithBackQuoteWithSchemaFromSchemaForShow() {
        showTablesStatement.getSqlTokens().add(new TableToken(18, "sharding_db".length() + 1, "`table_x`"));
        SQLRewriteEngine rewriteEngine = new SQLRewriteEngine(shardingRule, "SHOW COLUMNS FROM sharding_db.`table_x` FROM sharding_db", DatabaseType.MySQL, showTablesStatement, null, Collections.emptyList());
        assertThat(rewriteEngine.rewrite(false).toSQL(null, tableTokens, shardingRule, dataSourcePropertyManager).getSql(), is("SHOW COLUMNS FROM table_1 FROM sharding_db"));
    }
    
    @Test
    public void assertTableTokenWithSchemaWithBackQuoteForShow() {
        showTablesStatement.getSqlTokens().add(new TableToken(18, "`sharding_db`".length() + 1, "`table_x`"));
        SQLRewriteEngine rewriteEngine = new SQLRewriteEngine(shardingRule, "SHOW COLUMNS FROM `sharding_db`.`table_x`", DatabaseType.MySQL, showTablesStatement, null, Collections.emptyList());
        assertThat(rewriteEngine.rewrite(false).toSQL(null, tableTokens, shardingRule, dataSourcePropertyManager).getSql(), is("SHOW COLUMNS FROM table_1"));
    }
    
    @Test
    public void assertTableTokenWithSchemaWithBackQuoteFromSchemaForShow() {
        showTablesStatement.getSqlTokens().add(new TableToken(18, "`sharding_db`".length() + 1, "`table_x`"));
        SQLRewriteEngine rewriteEngine = new SQLRewriteEngine(shardingRule, "SHOW COLUMNS FROM `sharding_db`.`table_x` FROM sharding_db", DatabaseType.MySQL, showTablesStatement, null, Collections.emptyList());
        assertThat(rewriteEngine.rewrite(false).toSQL(null, tableTokens, shardingRule, dataSourcePropertyManager).getSql(), is("SHOW COLUMNS FROM table_1 FROM sharding_db"));
    }
    
    @Test
    public void assertTableTokenWithSchemaForSelect() {
        selectStatement.getSqlTokens().add(new TableToken(14, "sharding_db".length() + 1, "table_x"));
        SQLRewriteEngine rewriteEngine = new SQLRewriteEngine(shardingRule, "SELECT * FROM sharding_db.table_x", DatabaseType.MySQL, selectStatement, null, Collections.emptyList());
        assertThat(rewriteEngine.rewrite(false).toSQL(null, tableTokens, shardingRule, dataSourcePropertyManager).getSql(), is("SELECT * FROM table_1"));
    }
    
    @Test
    public void assertTableTokenWithSchemaForInsert() {
        insertStatement.getSqlTokens().add(new TableToken(12, "sharding_db".length() + 1, "table_x"));
        SQLRewriteEngine rewriteEngine = new SQLRewriteEngine(shardingRule, "INSERT INTO sharding_db.table_x (order_id, user_id, status) values (1, 1, 'OK')", DatabaseType.MySQL, insertStatement, null, Collections.emptyList());
        assertThat(rewriteEngine.rewrite(false).toSQL(null, tableTokens, shardingRule, dataSourcePropertyManager).getSql(), is("INSERT INTO table_1 (order_id, user_id, status) values (1, 1, 'OK')"));
    }
    
    @Test
    public void assertTableTokenWithSchemaForUpdate() {
        dmlStatement.getSqlTokens().add(new TableToken(7, "`sharding_db`".length() + 1, "table_x"));
        SQLRewriteEngine rewriteEngine = new SQLRewriteEngine(shardingRule, "UPDATE `sharding_db`.table_x SET user_id=1 WHERE order_id=1", DatabaseType.MySQL, dmlStatement, null, Collections.emptyList());
        assertThat(rewriteEngine.rewrite(false).toSQL(null, tableTokens, shardingRule, dataSourcePropertyManager).getSql(), is("UPDATE table_1 SET user_id=1 WHERE order_id=1"));
    }
    
    @Test
    public void assertTableTokenWithSchemaForDelete() {
        dmlStatement.getSqlTokens().add(new TableToken(12, "`sharding_db`".length() + 1, "`table_x`"));
        SQLRewriteEngine rewriteEngine = new SQLRewriteEngine(shardingRule, "DELETE FROM `sharding_db`.`table_x` WHERE user_id=1", DatabaseType.MySQL, dmlStatement, null, Collections.emptyList());
        assertThat(rewriteEngine.rewrite(false).toSQL(null, tableTokens, shardingRule, dataSourcePropertyManager).getSql(), is("DELETE FROM table_1 WHERE user_id=1"));
    }
}