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

import org.moqui.util.ContextUtil;
import org.moqui.workflow.util.WorkflowAdjustmentType;
import org.moqui.workflow.util.WorkflowEventType;
import org.moqui.workflow.util.WorkflowUtil;
import org.apache.commons.lang3.EnumUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.StopWatch;
import org.json.JSONObject;
import org.moqui.context.ExecutionContext;
import org.moqui.entity.EntityFacade;
import org.moqui.entity.EntityValue;
import org.moqui.service.ServiceFacade;

/**
 * Workflow activity used to adjustment payload.
 */
public class WorkflowAdjustmentActivity extends AbstractWorkflowActivity {

    /**
     * Creates a new activity.
     *
     * @param activity Activity entity
     */
    public WorkflowAdjustmentActivity(EntityValue activity) {
        this.activity = activity;
    }

    @Override
    public boolean execute(ExecutionContext ec, EntityValue instance) {

        // start the stop watch
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        // shortcuts for convenience
        EntityFacade ef = ec.getEntity();
        ServiceFacade sf = ec.getService();

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
        WorkflowAdjustmentType adjustmentType = nodeData.has("adjustmentTypeEnumId") ? EnumUtils.getEnum(WorkflowAdjustmentType.class, nodeData.getString("adjustmentTypeEnumId")) : null;
        String statusId = nodeData.has("statusId") ? nodeData.getString("statusId") : null;
        String variableId = nodeData.has("variableId") ? nodeData.getString("variableId") : null;
        String definedValue = nodeData.has("definedValue") ? nodeData.getString("definedValue") : null;

        // perform adjustment
        if (adjustmentType == WorkflowAdjustmentType.WF_ADJUST_STATUS && StringUtils.isNotBlank(statusId)) {

            // get the workflow
            EntityValue workflow = ef.find("moqui.workflow.WorkflowDetail")
                    .condition("workflowId", instance.getString("workflowId"))
                    .one();

            // get the entity
            String primaryEntityName = workflow.getString("primaryEntityName");
            String primaryKeyField = workflow.getString("primaryKeyField");
            String primaryKeyValue = instance.getString("primaryKeyValue");

            // update the status
            try {
                logger.debug(String.format("[%s] Changing status to: %s", logId, statusId));
                sf.sync().name(String.format("update#%s", primaryEntityName))
                        .parameter(primaryKeyField, primaryKeyValue)
                        .parameter("statusId", statusId)
                        .call();
            } catch (Exception e) {
                stopWatch.stop();
                logger.error(String.format("[%s] An error occurred while updating entity status: %s", logId, e.getMessage()), e);
                WorkflowUtil.createWorkflowEvent(
                        ec,
                        instanceId,
                        WorkflowEventType.WF_EVENT_ACTIVITY,
                        String.format("Failed to execute %s activity (%s) due to error: %s", activityTypeDescription, activityId, e.getMessage()),
                        true
                );
                return false;
            }
        } else if (adjustmentType == WorkflowAdjustmentType.WF_ADJUST_VARIABLE && StringUtils.isNotBlank(variableId)) {

            // update the variable
            try {
                logger.debug(String.format("[%s] Updating variable %s to: %s", logId, variableId, definedValue));
                sf.sync().name("moqui.workflow.WorkflowServices.update#WorkflowInstanceVariable")
                        .parameter("instanceId", instanceId)
                        .parameter("variableId", variableId)
                        .parameter("valueExpression", definedValue)
                        .call();
            } catch (Exception e) {
                stopWatch.stop();
                logger.error(String.format("[%s] An error occurred while updating workflow instance variable: %s", logId, e.getMessage()), e);
                WorkflowUtil.createWorkflowEvent(
                        ec,
                        instanceId,
                        WorkflowEventType.WF_EVENT_ACTIVITY,
                        String.format("Failed to execute %s activity (%s) due to error: %s", activityTypeDescription, activityId, e.getMessage()),
                        true
                );
                return false;
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
        return true;
    }
}
