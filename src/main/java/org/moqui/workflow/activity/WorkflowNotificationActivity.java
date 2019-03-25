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
import org.moqui.workflow.util.WorkflowCrowdType;
import org.moqui.workflow.util.WorkflowEventType;
import org.moqui.workflow.util.WorkflowNotificationType;
import org.moqui.workflow.util.WorkflowUtil;
import org.apache.commons.lang3.EnumUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.StopWatch;
import org.json.JSONObject;
import org.moqui.context.ExecutionContext;
import org.moqui.entity.EntityFacade;
import org.moqui.entity.EntityList;
import org.moqui.entity.EntityValue;
import org.moqui.service.ServiceFacade;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Workflow activity used to send out different types of notifications.
 */
public class WorkflowNotificationActivity extends AbstractWorkflowActivity {

    /**
     * Creates a new activity.
     *
     * @param activity Activity entity
     */
    public WorkflowNotificationActivity(EntityValue activity) {
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
        String inputUserId = instance.getString("inputUserId");

        // generate a new log ID
        String logId = ContextUtil.getLogId(ec);
        logger.debug(String.format("[%s] Executing %s activity (%s) ...", logId, activityTypeEnumId, activityId));

        // get attributes
        JSONObject nodeData = new JSONObject(activity.getString("nodeData"));
        WorkflowNotificationType notificationType = nodeData.has("notificationTypeEnumId") ? EnumUtils.getEnum(WorkflowNotificationType.class, nodeData.getString("notificationTypeEnumId")) : null;
        WorkflowCrowdType crowdType = nodeData.has("crowdTypeEnumId") ? EnumUtils.getEnum(WorkflowCrowdType.class, nodeData.getString("crowdTypeEnumId")) : null;
        String userId = nodeData.has("userId") ? nodeData.getString("userId") : null;
        String userGroupId = nodeData.has("userGroupId") ? nodeData.getString("userGroupId") : null;
        String message = nodeData.has("message") ? nodeData.getString("message") : null;

        // init body parameters
        Map<String, String> bodyParameters = new HashMap<>();
        bodyParameters.put("message", message);

        // get the user accounts
        ArrayList<EntityValue> userAccounts = new ArrayList<>();
        if (crowdType == WorkflowCrowdType.WF_CROWD_USER && StringUtils.isNotBlank(userId)) {
            EntityValue userAccount = ef.find("moqui.security.UserAccount")
                    .condition("userId", userId)
                    .one();
            if (userAccount!=null) {
                userAccounts.add(userAccount);
            }
        } else if (crowdType == WorkflowCrowdType.WF_CROWD_USER_GROUP && StringUtils.isNotBlank(userGroupId)) {
            EntityList groupMembers = ef.find("moqui.security.UserGroupMember")
                    .condition("userGroupId", userGroupId)
                    .conditionDate("fromDate", "thruDate", TimestampUtil.now())
                    .list();
            for (EntityValue groupMember : groupMembers) {
                EntityValue userAccount = ef.find("moqui.security.UserAccount")
                        .condition("userId", groupMember.getString("userId"))
                        .one();
                if (userAccount != null) {
                    userAccounts.add(userAccount);
                }
            }
        } else if (crowdType == WorkflowCrowdType.WF_CROWD_INITIATOR) {
            EntityValue userAccount = ef.find("moqui.security.UserAccount")
                    .condition("userId", inputUserId)
                    .one();
            if (userAccount != null) {
                userAccounts.add(userAccount);
            }
        }

        // send notification
        if (notificationType == WorkflowNotificationType.WF_NOTIFY_EMAIL) {
            for(EntityValue userAccount : userAccounts) {
                sf.async().name("org.moqui.impl.EmailServices.send#EmailTemplate")
                        .parameter("emailTemplateId", "PF_WF_EMAIL")
                        .parameter("toAddresses", userAccount.getString("emailAddress"))
                        .parameter("bodyParameters", bodyParameters)
                        .call();
            }
        } else if (notificationType == WorkflowNotificationType.WF_NOTIFY_SMS) {
            // TODO: Implement SMS gateway connect
        } else if (notificationType == WorkflowNotificationType.WF_NOTIFY_PUSH) {
            // TODO: Implement Push notification provider
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
