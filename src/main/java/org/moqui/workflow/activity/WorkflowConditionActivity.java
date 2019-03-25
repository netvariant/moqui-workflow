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
package org.moqui.workflow.activity;

import org.moqui.entity.util.EntityFieldType;
import org.moqui.workflow.condition.*;
import org.moqui.workflow.util.WorkflowConditionType;
import org.moqui.workflow.util.WorkflowEventType;
import org.moqui.workflow.util.WorkflowUtil;
import org.moqui.workflow.util.WorkflowVariableType;
import org.apache.commons.lang3.EnumUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.StopWatch;
import org.json.JSONArray;
import org.json.JSONObject;
import org.moqui.context.ExecutionContext;
import org.moqui.entity.EntityCondition;
import org.moqui.entity.EntityFacade;
import org.moqui.entity.EntityList;
import org.moqui.entity.EntityValue;
import org.moqui.util.*;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;

/**
 * Workflow activity used to evaluate conditions.
 */
public class WorkflowConditionActivity extends AbstractWorkflowActivity {

    /**
     * Creates a new activity.
     *
     * @param activity Activity entity
     */
    public WorkflowConditionActivity(EntityValue activity) {
        this.activity = activity;
    }

    @Override
    public boolean execute(ExecutionContext ec, EntityValue instance) {

        // start the stop watch
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        // shortcuts for convenience
        EntityFacade ef = ec.getEntity();

        // get attributes
        String activityId = activity.getString("activityId");
        String activityTypeEnumId = activity.getString("activityTypeEnumId");
        String activityTypeDescription = activity.getString("activityTypeDescription");
        String instanceId = instance.getString("instanceId");

        // generate a new log ID
        String logId = ContextUtil.getLogId(ec);
        logger.debug(String.format("[%s] Executing %s activity (%s) ...", logId, activityTypeEnumId, activityId));

        // get attributes
        JSONObject nodeData = new JSONObject(activity.getString("nodeData"));
        WorkflowConditionType conditionType = nodeData.has("conditionTypeEnumId") ? EnumUtils.getEnum(WorkflowConditionType.class, nodeData.getString("conditionTypeEnumId")) : null;
        EntityCondition.JoinOperator joinOperator = nodeData.has("joinOperator") ? EnumUtils.getEnum(EntityCondition.JoinOperator.class, nodeData.getString("joinOperator")) : null;
        JSONArray conditions = nodeData.has("conditions") ? nodeData.getJSONArray("conditions") : new JSONArray();

        // handle condition type
        ArrayList<WorkflowCondition> workflowConditions = new ArrayList<>();
        if (conditionType == WorkflowConditionType.WF_CONDITION_FIELD) {

            // get the workflow
            EntityValue workflow = ef.find("moqui.workflow.WorkflowDetail")
                    .condition("workflowId", instance.getString("workflowId"))
                    .one();

            // get the entity
            String primaryViewEntityName = workflow.getString("primaryViewEntityName");
            String primaryKeyField = workflow.getString("primaryKeyField");
            String primaryKeyValue = instance.getString("primaryKeyValue");
            EntityValue entity = ef.find(primaryViewEntityName)
                    .condition(primaryKeyField, primaryKeyValue)
                    .one();

            // convert and add conditions
            for (int i=0; i<conditions.length(); i++) {
                JSONObject condition = conditions.getJSONObject(i);
                String fieldName = condition.has("fieldName") ? condition.getString("fieldName") : null;
                String operator = condition.has("operator") ? condition.getString("operator") : null;
                String value = condition.has("value") ? condition.getString("value") : null;

                // skip condition if field name or operator are not defined
                if (fieldName == null || operator == null) {
                    logger.warn(String.format("[%s] Blank field or comparison operator in condition %d, skipping", logId, i));
                    continue;
                }

                // get the field
                EntityList fields = ef.find("moqui.entity.EntityField")
                        .condition("fieldName", fieldName)
                        .condition("entityName", primaryViewEntityName)
                        .limit(1)
                        .list();
                if (fields.isEmpty()) {
                    logger.warn(String.format("[%s] Unknown field '%s' in condition %d, skipping", logId, fieldName, i));
                    continue;
                }
                EntityValue field = fields.getFirst();

                // verify field type
                String fieldTypeEnumId = field.getString("fieldTypeEnumId");
                if (!EnumUtils.isValidEnum(EntityFieldType.class, fieldTypeEnumId)) {
                    logger.warn(String.format("[%s] Unknown field type '%s' in condition %d, skipping", logId, fieldTypeEnumId, i));
                    continue;
                }

                // handle field type
                EntityFieldType fieldType = EntityFieldType.valueOf(fieldTypeEnumId);
                switch (fieldType) {
                    case ENTITY_FLD_BOOLEAN: {

                        // verify comparison operator
                        if (!EnumUtils.isValidEnum(BooleanComparisonOperator.class, operator)) {
                            logger.warn(String.format("[%s] Wrong operator '%s' in condition %d, skipping", logId, operator, i));
                            continue;
                        }

                        // parse the value
                        boolean fieldValue = StringUtils.equals(entity.getString(fieldName), "Y");

                        // add condition
                        workflowConditions.add(new BooleanCondition(
                                fieldValue,
                                BooleanComparisonOperator.valueOf(operator))
                        );
                        break;
                    }
                    case ENTITY_FLD_DATE: {

                        // verify comparison operator
                        if (!EnumUtils.isValidEnum(DateComparisonOperator.class, operator)) {
                            logger.warn(String.format("[%s] Wrong operator '%s' in condition %d, skipping", logId, operator, i));
                            continue;
                        }

                        // parse the value
                        Date conditionValue;
                        DateFormat df = new SimpleDateFormat("yyyy-MM-dd");
                        try {
                            conditionValue = df.parse(value);
                        } catch (Exception e) {
                            logger.warn(String.format("[%s] Failed to parse date '%s' in condition %d, skipping", logId, value, i));
                            continue;
                        }

                        // get the field value
                        Date fieldValue;
                        try {
                            fieldValue = entity.getTimestamp(fieldName);
                        } catch (Exception e) {
                            logger.warn(String.format("[%s] Failed to retrieve date value from field '%s' in condition %d, skipping", logId, fieldName, i));
                            continue;
                        }

                        // add condition
                        workflowConditions.add(new DateCondition(
                                fieldValue,
                                DateComparisonOperator.valueOf(operator),
                                conditionValue)
                        );
                        break;
                    }
                    case ENTITY_FLD_NUMBER: {

                        // verify comparison operator
                        if (!EnumUtils.isValidEnum(NumberComparisonOperator.class, operator)) {
                            logger.warn(String.format("[%s] Wrong operator '%s' in condition %d, skipping", logId, operator, i));
                            continue;
                        }

                        // parse the value
                        long conditionValue;
                        try {
                            conditionValue = value == null ? 0 : Long.parseLong(value);
                        } catch (NumberFormatException e) {
                            logger.warn(String.format("[%s] Failed to parse number '%s' in condition %d, skipping", logId, value, i));
                            continue;
                        }

                        // get the field value
                        long fieldValue;
                        try {
                            fieldValue = entity.getLong(fieldName);
                        } catch (Exception e) {
                            logger.warn(String.format("[%s] Failed to retrieve long value from field '%s' in condition %d, skipping", logId, fieldName, i));
                            continue;
                        }

                        // add condition
                        workflowConditions.add(new NumberCondition(
                                fieldValue,
                                NumberComparisonOperator.valueOf(operator),
                                conditionValue)
                        );
                        break;
                    }
                    case ENTITY_FLD_TEXT: {

                        // verify comparison operator
                        if (!EnumUtils.isValidEnum(TextComparisonOperator.class, operator)) {
                            logger.warn(String.format("[%s] Wrong operator '%s' in condition %d, skipping", logId, operator, i));
                            continue;
                        }

                        // add condition
                        String fieldValue = entity.getString(fieldName);
                        workflowConditions.add(new TextCondition(
                                fieldValue,
                                TextComparisonOperator.valueOf(operator),
                                value)
                        );
                        break;
                    }
                    default: {

                    }
                }
            }
        } else if (conditionType == WorkflowConditionType.WF_CONDITION_VARIABLE) {

            // convert and add conditions
            for (int i=0; i<conditions.length(); i++) {
                JSONObject condition = conditions.getJSONObject(i);
                String variableName = condition.has("variableName") ? condition.getString("variableName") : null;
                String operator = condition.has("operator") ? condition.getString("operator") : null;
                String value = condition.has("value") ? condition.getString("value") : null;

                // skip condition if field name or operator are not defined
                if (variableName==null || operator==null) {
                    logger.warn(String.format("[%s] Blank variable or comparison operator in condition %d, skipping", logId, i));
                    continue;
                }

                // get the variable
                EntityList variables = ef.find("moqui.workflow.WorkflowInstanceVariableDetail")
                        .condition("instanceId", instanceId)
                        .condition("variableName", variableName)
                        .limit(1)
                        .list();
                if (variables.isEmpty()) {
                    logger.warn(String.format("[%s] Unknown variable '%s' in condition %d, skipping", logId, variableName, i));
                    continue;
                }
                EntityValue variable = variables.getFirst();

                // verify variable type
                String variableTypeEnumId = variable.getString("variableTypeEnumId");
                if (!EnumUtils.isValidEnum(WorkflowVariableType.class, variableTypeEnumId)) {
                    logger.warn(String.format("[%s] Unknown variable type '%s' in condition %d, skipping", logId, variableTypeEnumId, i));
                    continue;
                }

                // handle field type
                WorkflowVariableType variableType = WorkflowVariableType.valueOf(variableTypeEnumId);
                switch (variableType) {
                    case WF_VAR_NUMBER: {

                        // verify comparison operator
                        if (!EnumUtils.isValidEnum(NumberComparisonOperator.class, operator)) {
                            logger.warn(String.format("[%s] Wrong operator '%s' in condition %d, skipping", logId, operator, i));
                            continue;
                        }

                        // parse the value
                        long conditionValue;
                        try {
                            conditionValue = value==null ? 0 : Long.parseLong(value);
                        } catch (NumberFormatException e) {
                            logger.warn(String.format("[%s] Failed to parse number '%s' in condition %d, skipping", logId, value, i));
                            continue;
                        }

                        // get the defined value
                        long definedValue;
                        try {
                            definedValue = Long.parseLong(variable.getString("definedValue"));
                        } catch (Exception e) {
                            logger.warn(String.format("[%s] Failed to retrieve long value from variable '%s' in condition %d, skipping", logId, variableName, i));
                            continue;
                        }

                        // add condition
                        workflowConditions.add(new NumberCondition(
                                definedValue,
                                NumberComparisonOperator.valueOf(operator),
                                conditionValue)
                        );
                        break;
                    }
                    case WF_VAR_TEXT: {

                        // verify comparison operator
                        if (!EnumUtils.isValidEnum(TextComparisonOperator.class, operator)) {
                            logger.warn(String.format("[%s] Wrong operator '%s' in condition %d, skipping", logId, operator, i));
                            continue;
                        }

                        // add condition
                        String definedValue = variable.getString("definedValue");
                        workflowConditions.add(new TextCondition(
                                definedValue,
                                TextComparisonOperator.valueOf(operator),
                                value)
                        );
                        break;
                    }
                }
            }
        } else if (conditionType == WorkflowConditionType.WF_CONDITION_SCRIPT) {

            // convert and add conditions
            for (int i=0; i<conditions.length(); i++) {
                JSONObject condition = conditions.getJSONObject(i);
                String script = condition.has("script") ? condition.getString("script") : null;

                // add condition
                workflowConditions.add(new ScriptCondition(script));
            }
        }

        // evaluate conditions
        boolean conditionsMet = joinOperator == EntityCondition.JoinOperator.AND;
        for (WorkflowCondition condition : workflowConditions) {

            // evaluate
            logger.debug(String.format("[%s] Evaluating condition: %s", logId, condition.toString()));
            boolean success;
            try {
                success = condition.evaluate(ec, instance);
            } catch (Exception e) {
                logger.error(String.format("[%s] An error occurred while evaluating condition: %s", logId, e.getMessage()), e);
                continue;
            }

            // join the result
            logger.debug(String.format("[%s] Condition evaluates to %s", logId, success));
            if (joinOperator == EntityCondition.JoinOperator.OR && success) {
                conditionsMet = true;
                break;
            } else if (joinOperator == EntityCondition.JoinOperator.AND && !success) {
                conditionsMet = false;
                break;
            }
        }

        // create event
        WorkflowUtil.createWorkflowEvent(
                ec,
                instanceId,
                WorkflowEventType.WF_EVENT_ACTIVITY,
                String.format("Executed %s activity (%s)", activityTypeDescription, activityId),
                false
        );

        // log the processing time
        stopWatch.stop();
        logger.debug(String.format("[%s] %s activity (%s) executed in %d milliseconds", logId, activityTypeEnumId, activityId, stopWatch.getTime()));

        // activity executed successfully
        return conditionsMet;
    }
}
