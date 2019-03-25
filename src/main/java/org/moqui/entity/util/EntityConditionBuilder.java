/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 *
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 *
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org.moqui.entity.util;

import org.moqui.util.BooleanComparisonOperator;
import org.moqui.util.DateComparisonOperator;
import org.moqui.util.NumberComparisonOperator;
import org.moqui.util.TextComparisonOperator;
import org.apache.commons.lang3.EnumUtils;
import org.apache.commons.lang3.time.DateUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.moqui.context.ExecutionContext;
import org.moqui.entity.EntityCondition;
import org.moqui.entity.EntityConditionFactory;

import java.sql.Timestamp;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;

/**
 * Utility class to help constructing entity conditions.
 */
public class EntityConditionBuilder {

    /**
     * Execution context.
     */
    private ExecutionContext ec;
    /**
     * Entity condition factory.
     */
    private EntityConditionFactory ecf;
    /**
     * Condition.
     */
    private EntityCondition condition;

    /**
     * Creates a new {@code EntityConditionBuilder}.
     *
     * @param ec Execution context
     */
    public EntityConditionBuilder(ExecutionContext ec) {
        this(ec, null);
    }

    /**
     * Creates a new {@code EntityConditionBuilder}.
     *
     * @param ec Execution context
     * @param baseCondition Base condition
     */
    public EntityConditionBuilder(ExecutionContext ec, EntityCondition baseCondition) {
        this.ec = ec;
        this.ecf = ec.getEntity().getConditionFactory();
        this.condition = baseCondition==null ? ecf.getTrueCondition() : baseCondition;
    }

    /**
     * Add a text condition.
     *
     * @param fieldName Entity field name
     * @param operator Comparison operator
     * @param value Comparison value
     * @return New condition
     */
    public EntityCondition textCondition(String fieldName, TextComparisonOperator operator, String value) {
        switch(operator) {
            case TXT_STARTS_WITH:
                condition = ecf.makeCondition(
                        condition,
                        EntityCondition.JoinOperator.AND,
                        ecf.makeCondition(fieldName, EntityCondition.ComparisonOperator.LIKE, value + "%")
                );
                break;
            case TXT_ENDS_WITH:
                condition = ecf.makeCondition(
                        condition,
                        EntityCondition.JoinOperator.AND,
                        ecf.makeCondition(fieldName, EntityCondition.ComparisonOperator.LIKE, "%" + value)
                );
                break;
            case TXT_CONTAINS:
                condition = ecf.makeCondition(
                        condition,
                        EntityCondition.JoinOperator.AND,
                        ecf.makeCondition(fieldName, EntityCondition.ComparisonOperator.LIKE, "%" + value + "%")
                );
                break;
            case TXT_NOT_CONTAINS:
                condition = ecf.makeCondition(
                        condition,
                        EntityCondition.JoinOperator.AND,
                        ecf.makeCondition(fieldName, EntityCondition.ComparisonOperator.NOT_LIKE, "%" + value + "%")
                );
                break;
            case TXT_EQUALS:
                condition = ecf.makeCondition(
                        condition,
                        EntityCondition.JoinOperator.AND,
                        ecf.makeCondition(fieldName, EntityCondition.ComparisonOperator.EQUALS, value)
                );
                break;
            case TXT_NOT_EQUALS:
                condition = ecf.makeCondition(
                        condition,
                        EntityCondition.JoinOperator.AND,
                        ecf.makeCondition(fieldName, EntityCondition.ComparisonOperator.NOT_EQUAL, value)
                );
                break;
            case TXT_EMPTY:
                condition = ecf.makeCondition(
                        condition,
                        EntityCondition.JoinOperator.AND,
                        ecf.makeCondition(fieldName, EntityCondition.ComparisonOperator.EQUALS, "")
                );
                break;
            case TXT_NOT_EMPTY:
                condition = ecf.makeCondition(
                        condition,
                        EntityCondition.JoinOperator.AND,
                        ecf.makeCondition(fieldName, EntityCondition.ComparisonOperator.NOT_EQUAL, "")
                );
                break;
            default:
                break;
        }

        // return condition for cascading
        return condition;
    }

    /**
     * Add a number condition.
     *
     * @param fieldName Entity field name
     * @param operator Comparison operator
     * @param value Comparison value
     * @return New condition
     */
    public EntityCondition numberCondition(String fieldName, NumberComparisonOperator operator, double value) {
        switch(operator) {
            case NUM_LESS_THAN:
                condition = ecf.makeCondition(
                        condition,
                        EntityCondition.JoinOperator.AND,
                        ecf.makeCondition(fieldName, EntityCondition.ComparisonOperator.LESS_THAN, value)
                );
                break;
            case NUM_LESS_THAN_EQUALS:
                condition = ecf.makeCondition(
                        condition,
                        EntityCondition.JoinOperator.AND,
                        ecf.makeCondition(fieldName, EntityCondition.ComparisonOperator.LESS_THAN_EQUAL_TO, value)
                );
                break;
            case NUM_GREATER_THAN:
                condition = ecf.makeCondition(
                        condition,
                        EntityCondition.JoinOperator.AND,
                        ecf.makeCondition(fieldName, EntityCondition.ComparisonOperator.GREATER_THAN, value)
                );
                break;
            case NUM_GREATER_THAN_EQUALS:
                condition = ecf.makeCondition(
                        condition,
                        EntityCondition.JoinOperator.AND,
                        ecf.makeCondition(fieldName, EntityCondition.ComparisonOperator.GREATER_THAN_EQUAL_TO, value)
                );
                break;
            case NUM_EQUALS:
                condition = ecf.makeCondition(
                        condition,
                        EntityCondition.JoinOperator.AND,
                        ecf.makeCondition(fieldName, EntityCondition.ComparisonOperator.EQUALS, value)
                );
                break;
            case NUM_NOT_EQUALS:
                condition = ecf.makeCondition(
                        condition,
                        EntityCondition.JoinOperator.AND,
                        ecf.makeCondition(fieldName, EntityCondition.ComparisonOperator.NOT_EQUAL, value)
                );
                break;
            default:
                break;
        }

        // return condition for cascading
        return condition;
    }

    /**
     * Add a date condition.
     *
     * @param fieldName Entity field name
     * @param operator Comparison operator
     * @param value Comparison value
     * @return New condition
     */
    public EntityCondition dateCondition(String fieldName, DateComparisonOperator operator, Timestamp value) {
        switch(operator) {
            case DATE_BEFORE:
                condition = ecf.makeCondition(
                        condition,
                        EntityCondition.JoinOperator.AND,
                        ecf.makeCondition(fieldName, EntityCondition.ComparisonOperator.LESS_THAN, value)
                );
                break;
            case DATE_AFTER:
                condition = ecf.makeCondition(
                        condition,
                        EntityCondition.JoinOperator.AND,
                        ecf.makeCondition(fieldName, EntityCondition.ComparisonOperator.GREATER_THAN, value)
                );
                break;
            case DATE_EQUALS:
                condition = ecf.makeCondition(
                        Arrays.asList(
                                condition,
                                ecf.makeCondition(fieldName, EntityCondition.ComparisonOperator.GREATER_THAN, value),
                                ecf.makeCondition(fieldName, EntityCondition.ComparisonOperator.LESS_THAN, new Timestamp(DateUtils.addDays(value, 1).getTime()))
                        ),
                        EntityCondition.JoinOperator.AND
                );
                break;
            case DATE_NOT_EQUALS:
                condition = ecf.makeCondition(
                        Arrays.asList(
                                condition,
                                ecf.makeCondition(
                                        ecf.makeCondition(fieldName, EntityCondition.ComparisonOperator.LESS_THAN, value),
                                        EntityCondition.JoinOperator.OR,
                                        ecf.makeCondition(fieldName, EntityCondition.ComparisonOperator.GREATER_THAN, new Timestamp(DateUtils.addDays(value, 1).getTime()))
                                )
                        ),
                        EntityCondition.JoinOperator.AND
                );
                break;
            default:
                break;
        }

        // return condition for cascading
        return condition;
    }

    /**
     * Add a boolean condition.
     *
     * @param fieldName Entity field name
     * @param operator Comparison operator
     * @return New condition
     */
    public EntityCondition booleanCondition(String fieldName, BooleanComparisonOperator operator) {
        switch(operator) {
            case BOOL_TRUE:
                condition = ecf.makeCondition(
                        condition,
                        EntityCondition.JoinOperator.AND,
                        ecf.makeCondition(fieldName, EntityCondition.ComparisonOperator.EQUALS, "Y")
                );
                break;
            case BOOL_FALSE:
                condition = ecf.makeCondition(
                        condition,
                        EntityCondition.JoinOperator.AND,
                        ecf.makeCondition(fieldName, EntityCondition.ComparisonOperator.EQUALS, "N")
                );
                break;
            default:
                break;
        }

        // return condition for cascading
        return condition;
    }

    /***
     * Builds a compound condition from a JSON array.
     *
     * @param conditionArray JSON array
     * @return New condition
     */
    public EntityCondition build(JSONArray conditionArray) {
        DateFormat df = new SimpleDateFormat("yyyy-MM-dd");
        for (Object obj : conditionArray) {
            JSONObject conditionObj = (JSONObject) obj;
            String fieldName = (String) conditionObj.get("fieldName");
            String operator = (String) conditionObj.get("operator");
            String value = (String) conditionObj.get("value");

            if (EnumUtils.isValidEnum(TextComparisonOperator.class, operator)) {
                condition = textCondition(fieldName, TextComparisonOperator.valueOf(operator), value);
            } else if (EnumUtils.isValidEnum(NumberComparisonOperator.class, operator)) {
                try {
                    condition = numberCondition(fieldName, NumberComparisonOperator.valueOf(operator), Double.parseDouble(value));
                } catch (NumberFormatException e) {
                    // ignore
                }
            } else if (EnumUtils.isValidEnum(DateComparisonOperator.class, operator)) {
                try {
                    Timestamp valueDate = new Timestamp(df.parse(value).getTime());
                    condition = dateCondition(fieldName, DateComparisonOperator.valueOf(operator), valueDate);
                } catch (ParseException e) {
                    // ignore
                }
            } else if (EnumUtils.isValidEnum(BooleanComparisonOperator.class, operator)) {
                condition = booleanCondition(fieldName, BooleanComparisonOperator.valueOf(operator));
            }
        }

        // return condition for cascading
        return condition;
    }
}
