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
import org.moqui.util.TimestampUtil;
import org.moqui.workflow.util.WorkflowEventType;
import org.moqui.workflow.util.WorkflowInstanceStatus;
import org.moqui.workflow.util.WorkflowUtil;
import org.apache.commons.lang3.time.StopWatch;
import org.json.JSONObject;
import org.moqui.context.ExecutionContext;
import org.moqui.entity.EntityValue;
import org.moqui.service.ServiceFacade;

/**
 * Workflow activity used as an exit point to stop a workflow instance.
 */
public class WorkflowExitActivity extends AbstractWorkflowActivity {

    /**
     * Creates a new activity.
     *
     * @param activity Activity entity
     */
    public WorkflowExitActivity(EntityValue activity) {
        this.activity = activity;
    }

    @Override
    public boolean execute(ExecutionContext ec, EntityValue instance) {

        // start the stop watch
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        // shortcuts for convenience
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
        Integer resultCode = nodeData.has("resultCode") ? nodeData.getInt("resultCode") : null;

        // update workflow instance
        logger.debug(String.format("[%s] Exiting instance %s with result code %s", logId, instanceId, resultCode));
        sf.sync().name("update#moqui.workflow.WorkflowInstance")
                .parameter("instanceId", instanceId)
                .parameter("statusId", WorkflowInstanceStatus.WF_INST_STAT_COMPLETE)
                .parameter("resultCode", resultCode)
                .parameter("lastUpdateDate", TimestampUtil.now())
                .call();

        // create event
        WorkflowUtil.createWorkflowEvent(
                ec,
                instanceId,
                WorkflowEventType.WF_EVENT_ACTIVITY,
                String.format("Executed %s activity (%s)", activityTypeDescription, activityId),
                false
        );

        // create event
        WorkflowUtil.createWorkflowEvent(
                ec,
                instanceId,
                WorkflowEventType.WF_EVENT_FINISH,
                "Workflow finished",
                false
        );

        // log the processing time
        stopWatch.stop();
        logger.debug(String.format("[%s] %s activity (%s) executed in %d milliseconds", logId, activityTypeEnumId, activityId, stopWatch.getTime()));

        // activity executed successfully
        return true;
    }
}
