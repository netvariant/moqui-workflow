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
package org.moqui.workflow;

import org.moqui.util.ContextUtil;
import org.moqui.util.StringUtil;
import org.moqui.util.TimeFrequency;
import org.moqui.util.TimestampUtil;
import org.apache.commons.lang3.EnumUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.StopWatch;
import org.json.JSONArray;
import org.json.JSONObject;
import org.moqui.context.ExecutionContext;
import org.moqui.context.L10nFacade;
import org.moqui.context.MessageFacade;
import org.moqui.context.UserFacade;
import org.moqui.entity.*;
import org.moqui.service.ServiceFacade;
import org.moqui.util.*;
import org.moqui.workflow.activity.*;
import org.moqui.workflow.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.sql.Timestamp;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Service to manage workflows and execute workflow instances.
 */
@SuppressWarnings("unused")
public class WorkflowService {

    /**
     * Class logger.
     */
    private final Logger logger = LoggerFactory.getLogger(getClass());

    /**
     * Gets a set of workflow IDs the user has access to.
     *
     * @param ec Execution context
     * @return Workflow ID set
     */
    private static Set<String> getUserWorkflowIdSet(ExecutionContext ec) {

        // shortcuts for convenience
        EntityFacade ef = ec.getEntity();
        UserFacade uf = ec.getUser();

        // get the workflows created by the user
        HashSet<String> idSet = new HashSet<>();
        EntityList ownedWorkflows = ef.find("moqui.workflow.Workflow")
                .condition("inputUserId", uf.getUserId())
                .list();
        for (EntityValue workflow : ownedWorkflows) {
            idSet.add(workflow.getString("workflowId"));
        }

        // get the workflow initiators
        EntityList initiators = ef.find("moqui.workflow.WorkflowInitiator")
                .condition("userGroupId", EntityCondition.ComparisonOperator.IN, uf.getUserGroupIdSet())
                .list();
        for (EntityValue initiator : initiators) {

            // skip expired
            Timestamp fromDate = initiator.getTimestamp("fromDate");
            Timestamp toDate = initiator.getTimestamp("toDate");
            if (!TimestampUtil.isWithinRange(fromDate, toDate)) {
                continue;
            }

            idSet.add(initiator.getString("workflowId"));
        }

        // return the ID set
        return idSet;
    }

    /***
     * Synchronizes the workflow objects with the design model.
     *
     * @param ec Execution context
     * @param workflow Workflow
     */
    private void syncWorkflowWithModel(ExecutionContext ec, EntityValue workflow) {

        // start the stop watch
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        // shortcuts for convenience
        ContextStack cs = ec.getContext();
        MessageFacade mf = ec.getMessage();
        EntityFacade ef = ec.getEntity();
        ServiceFacade sf = ec.getService();
        UserFacade uf = ec.getUser();

        // get workflow attributes
        String workflowId = workflow.getString("workflowId");
        String modelData = workflow.getString("modelData");

        // generate a new log ID
        String logId = ContextUtil.getLogId(ec);
        logger.debug(String.format("[%s] Syncing workflow with model ...", logId));
        logger.debug(String.format("[%s] Param workflowId=%s", logId, workflowId));
        logger.debug(String.format("[%s] Param modelData=%s", logId, modelData));

        // skip empty models
        if (StringUtils.isBlank(modelData)) {
            stopWatch.stop();
            logger.debug(String.format("[%s] Model data is blank", logId));
            return;
        }

        // init activity ID set
        Set<String> activityIdSet = new HashSet<>();
        EntityList activityList = ef.find("moqui.workflow.WorkflowActivity")
                .condition("workflowId", workflowId)
                .list();
        for (EntityValue activity : activityList) {
            activityIdSet.add(activity.getString("activityId"));
        }

        // init transition ID set
        Set<String> transitionIdSet = new HashSet<>();
        EntityList transitionList = ef.find("moqui.workflow.WorkflowTransition")
                .condition("workflowId", workflowId)
                .list();
        for (EntityValue transition : transitionList) {
            transitionIdSet.add(transition.getString("transitionId"));
        }

        // traverse and sync
        JSONArray nodeArray = new JSONArray(modelData);
        for (Object nodeObj : nodeArray) {

            // get node properties
            JSONObject node = (JSONObject) nodeObj;
            String nodeType = node.getString("type");
            String nodeId = node.getString("id");
            JSONObject nodeData = node.getJSONObject("userData");

            // handle node types
            if (nodeType.equals("draw2d.Connection")) {

                // check from activity
                JSONObject source = node.getJSONObject("source");
                String fromNodeId = source.getString("node");
                long fromActivityCount = ef.find("moqui.workflow.WorkflowActivity")
                        .condition("workflowId", workflowId)
                        .condition("nodeId", fromNodeId)
                        .count();
                if (fromActivityCount == 0) {
                    logger.warn(String.format("[%s] Skipping transition from unrecognized node: %s", logId, fromNodeId));
                    continue;
                }

                // check to activity
                JSONObject target = node.getJSONObject("target");
                String toNodeId = target.getString("node");
                long toActivityCount = ef.find("moqui.workflow.WorkflowActivity")
                        .condition("workflowId", workflowId)
                        .condition("nodeId", toNodeId)
                        .count();
                if (toActivityCount == 0) {
                    logger.warn(String.format("[%s] Skipping transition to unrecognized node: %s", logId, toNodeId));
                    continue;
                }

                // get from port type
                String fromPortName = source.getString("port");
                WorkflowPortType fromPortTypeEnumId;
                try {
                    fromPortTypeEnumId = WorkflowPortType.valueOf("WF_PORT_" + fromPortName);
                } catch (IllegalArgumentException e) {
                    logger.warn(String.format("[%s] Skipping transition from unrecognized port: %s", logId, fromPortName));
                    continue;
                }

                // get to port type
                String toPortName = target.getString("port");
                WorkflowPortType toPortTypeEnumId;
                try {
                    toPortTypeEnumId = WorkflowPortType.valueOf("WF_PORT_" + toPortName);
                } catch (IllegalArgumentException e) {
                    logger.warn(String.format("[%s] Skipping transition to unrecognized port: %s", logId, toPortName));
                    continue;
                }

                // get activities
                EntityValue fromActivity = ef.find("moqui.workflow.WorkflowActivity")
                        .condition("workflowId", workflowId)
                        .condition("nodeId", fromNodeId)
                        .list()
                        .getFirst();
                EntityValue toActivity = ef.find("moqui.workflow.WorkflowActivity")
                        .condition("workflowId", workflowId)
                        .condition("nodeId", toNodeId)
                        .list()
                        .getFirst();

                // create or update transition
                long transitionCount = ef.find("moqui.workflow.WorkflowTransition")
                        .condition("workflowId", workflowId)
                        .condition("nodeId", nodeId)
                        .count();
                if (transitionCount == 1) {
                    EntityValue transition = ef.find("moqui.workflow.WorkflowTransition")
                            .condition("workflowId", workflowId)
                            .condition("nodeId", nodeId)
                            .limit(1)
                            .list()
                            .getFirst();
                    String transitionId = transition.getString("transitionId");
                    sf.sync().name("update#moqui.workflow.WorkflowTransition")
                            .parameter("transitionId", transitionId)
                            .parameter("nodeData", nodeData)
                            .parameter("updateUserId", uf.getUserId())
                            .call();
                    transitionIdSet.remove(transitionId);
                    logger.debug(String.format("[%s] Transition %s updated for node %s", logId, transitionId, nodeId));
                } else {

                    // remove old transitions
                    EntityList oldTransitions = ef.find("moqui.workflow.WorkflowTransition")
                            .condition("workflowId", workflowId)
                            .condition("fromActivityId", fromActivity.getString("activityId"))
                            .condition("fromPortTypeEnumId", fromPortTypeEnumId)
                            .condition("toActivityId", toActivity.getString("activityId"))
                            .condition("toPortTypeEnumId", toPortTypeEnumId)
                            .list();
                    for (EntityValue oldTransition : oldTransitions) {
                        String oldTransitionId = oldTransition.getString("transitionId");
                        sf.sync().name("delete#moqui.workflow.WorkflowTransition").parameter("transitionId", oldTransitionId).call();
                        transitionIdSet.remove(oldTransitionId);
                    }

                    // create new transition
                    Map<String, Object> resp = sf.sync().name("create#moqui.workflow.WorkflowTransition")
                            .parameter("workflowId", workflowId)
                            .parameter("fromActivityId", fromActivity.getString("activityId"))
                            .parameter("fromPortTypeEnumId", fromPortTypeEnumId)
                            .parameter("toActivityId", toActivity.getString("activityId"))
                            .parameter("toPortTypeEnumId", toPortTypeEnumId)
                            .parameter("nodeId", nodeId)
                            .parameter("nodeData", nodeData)
                            .call();
                    String transitionId = (String) resp.get("transitionId");
                    logger.debug(String.format("[%s] Transition %s created for node %s", logId, transitionId, nodeId));
                }
            } else {

                // get activity type
                WorkflowActivityType activityTypeEnumId = WorkflowActivityType.fromNodeType(nodeType);
                if (activityTypeEnumId == null) {
                    logger.warn(String.format("[%s] Skipping unrecognized node: %s", logId, nodeType));
                    continue;
                }

                // get the timeout values
                int timeoutInterval = 0;
                String timeoutUomId = null;
                if (nodeData.has("timeoutInterval")) {
                    timeoutInterval = nodeData.getInt("timeoutInterval");
                }
                if (nodeData.has("timeoutUomId")) {
                    timeoutUomId = nodeData.getString("timeoutUomId");
                }

                // create or update activity
                long activityCount = ef.find("moqui.workflow.WorkflowActivity")
                        .condition("workflowId", workflowId)
                        .condition("nodeId", nodeId)
                        .count();
                if (activityCount == 1) {
                    EntityValue activity = ef.find("moqui.workflow.WorkflowActivity")
                            .condition("workflowId", workflowId)
                            .condition("nodeId", nodeId)
                            .limit(1)
                            .list()
                            .getFirst();
                    String activityId = activity.getString("activityId");
                    sf.sync().name("update#moqui.workflow.WorkflowActivity")
                            .parameter("activityId", activityId)
                            .parameter("nodeData", nodeData)
                            .parameter("timeoutInterval", timeoutInterval)
                            .parameter("timeoutUomId", timeoutUomId)
                            .parameter("updateUserId", uf.getUserId())
                            .call();
                    activityIdSet.remove(activityId);
                    logger.debug(String.format("[%s] Activity %s updated for node %s", logId, activityId, nodeId));
                } else {
                    Map<String, Object> resp = sf.sync().name("create#moqui.workflow.WorkflowActivity")
                            .parameter("workflowId", workflowId)
                            .parameter("activityTypeEnumId", activityTypeEnumId)
                            .parameter("nodeId", nodeId)
                            .parameter("nodeData", nodeData)
                            .parameter("timeoutInterval", timeoutInterval)
                            .parameter("timeoutUomId", timeoutUomId)
                            .call();
                    String activityId = (String) resp.get("activityId");
                    logger.debug(String.format("[%s] Activity %s created for node %s", logId, activityId, nodeId));
                }
            }
        }

        // delete obsolete transitions
        for (String transitionId : transitionIdSet) {
            sf.sync().name("delete#moqui.workflow.WorkflowTransition").parameter("transitionId", transitionId).call();
            logger.debug(String.format("[%s] Deleted obsolete transition %s", logId, transitionId));
        }

        // delete obsolete activities
        for (String activityId : activityIdSet) {
            sf.sync().name("delete#moqui.workflow.WorkflowActivity").parameter("activityId", activityId).call();
            logger.debug(String.format("[%s] Deleted obsolete activity %s", logId, activityId));
        }

        // log the processing time
        stopWatch.stop();
        logger.debug(String.format("[%s] Workflow %s synced in %d milliseconds", logId, workflowId, stopWatch.getTime()));
    }

    /***
     * Validates the workflow design model.
     *
     * @param ec Execution context
     * @param workflow Workflow
     */
    private boolean validateWorkflowModel(ExecutionContext ec, EntityValue workflow) {

        // start the stop watch
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        // shortcuts for convenience
        ContextStack cs = ec.getContext();
        MessageFacade mf = ec.getMessage();
        EntityFacade ef = ec.getEntity();
        ServiceFacade sf = ec.getService();
        UserFacade uf = ec.getUser();

        // get workflow attributes
        String workflowId = workflow.getString("workflowId");

        // generate a new log ID
        String logId = ContextUtil.getLogId(ec);
        logger.debug(String.format("[%s] Validating workflow ...", logId));
        logger.debug(String.format("[%s] Param workflowId=%s", logId, workflowId));

        // get activities
        EntityList activities = ef.find("moqui.workflow.WorkflowActivityDetail")
                .condition("workflowId", workflowId)
                .list();

        // validate activities
        if (activities.isEmpty()) {
            logger.error(String.format("[%s] Workflow %s has no entry activity", logId, workflowId));
            return false;
        }
        for (EntityValue activity : activities) {
            String activityId = activity.getString("activityId");
            String activityTypeDescription = activity.getString("activityTypeDescription");
            WorkflowActivityType activityType = WorkflowActivityType.valueOf(activity.getString("activityTypeEnumId"));
            switch (activityType) {
                case WF_ACTIVITY_ENTER: {
                    long successCount = ef.find("moqui.workflow.WorkflowTransition")
                            .condition("workflowId", workflowId)
                            .condition("fromActivityId", activityId)
                            .condition("fromPortTypeEnumId", WorkflowPortType.WF_PORT_SUCCESS.name())
                            .count();
                    if (successCount != 1) {
                        stopWatch.stop();
                        mf.addError(String.format("%s activity must have only one transition from port %s.", activityTypeDescription, WorkflowPortType.WF_PORT_SUCCESS.name()));
                        logger.error(String.format("[%s] Activity %s must have only one transition from port %s", logId, activityId, WorkflowPortType.WF_PORT_SUCCESS.name()));
                        return false;
                    }
                    break;
                }
                case WF_ACTIVITY_EXIT: {
                    long inputCount = ef.find("moqui.workflow.WorkflowTransition")
                            .condition("workflowId", workflowId)
                            .condition("toActivityId", activityId)
                            .condition("toPortTypeEnumId", WorkflowPortType.WF_PORT_INPUT.name())
                            .count();
                    if (inputCount == 0) {
                        stopWatch.stop();
                        mf.addError(String.format("%s activity must have at least one transition to port %s.", activityTypeDescription, WorkflowPortType.WF_PORT_INPUT.name()));
                        logger.error(String.format("[%s] Activity %s must have at least one transition to port %s", logId, activityId, WorkflowPortType.WF_PORT_INPUT.name()));
                        return false;
                    }
                    break;
                }
                case WF_ACTIVITY_CONDITION: {
                    long inputCount = ef.find("moqui.workflow.WorkflowTransition")
                            .condition("workflowId", workflowId)
                            .condition("toActivityId", activityId)
                            .condition("toPortTypeEnumId", WorkflowPortType.WF_PORT_INPUT.name())
                            .count();
                    if (inputCount == 0) {
                        stopWatch.stop();
                        mf.addError(String.format("%s activity must have at least one transition to port %s.", activityTypeDescription, WorkflowPortType.WF_PORT_INPUT.name()));
                        logger.error(String.format("[%s] Activity %s must have at least one transition to port %s", logId, activityId, WorkflowPortType.WF_PORT_INPUT.name()));
                        return false;
                    }

                    long successCount = ef.find("moqui.workflow.WorkflowTransition")
                            .condition("workflowId", workflowId)
                            .condition("fromActivityId", activityId)
                            .condition("fromPortTypeEnumId", WorkflowPortType.WF_PORT_SUCCESS.name())
                            .count();
                    if (successCount != 1) {
                        stopWatch.stop();
                        mf.addError(String.format("%s activity must have only one transition from port %s.", activityTypeDescription, WorkflowPortType.WF_PORT_SUCCESS.name()));
                        logger.error(String.format("[%s] Activity %s must have only one transition from port %s", logId, activityId, WorkflowPortType.WF_PORT_SUCCESS.name()));
                        return false;
                    }

                    long failureCount = ef.find("moqui.workflow.WorkflowTransition")
                            .condition("workflowId", workflowId)
                            .condition("fromActivityId", activityId)
                            .condition("fromPortTypeEnumId", WorkflowPortType.WF_PORT_FAILURE.name())
                            .count();
                    if (failureCount != 1) {
                        stopWatch.stop();
                        mf.addError(String.format("%s activity must have only one transition from port %s.", activityTypeDescription, WorkflowPortType.WF_PORT_FAILURE.name()));
                        logger.error(String.format("[%s] Activity %s must have only one transition from port %s", logId, activityId, WorkflowPortType.WF_PORT_FAILURE.name()));
                        return false;
                    }
                    break;
                }
                case WF_ACTIVITY_USER: {
                    long inputCount = ef.find("moqui.workflow.WorkflowTransition")
                            .condition("workflowId", workflowId)
                            .condition("toActivityId", activityId)
                            .condition("toPortTypeEnumId", WorkflowPortType.WF_PORT_INPUT.name())
                            .count();
                    if (inputCount == 0) {
                        stopWatch.stop();
                        mf.addError(String.format("%s activity must have at least one transition to port %s.", activityTypeDescription, WorkflowPortType.WF_PORT_INPUT.name()));
                        logger.error(String.format("[%s] Activity %s must have at least one transition to port %s", logId, activityId, WorkflowPortType.WF_PORT_INPUT.name()));
                        return false;
                    }

                    long successCount = ef.find("moqui.workflow.WorkflowTransition")
                            .condition("workflowId", workflowId)
                            .condition("fromActivityId", activityId)
                            .condition("fromPortTypeEnumId", WorkflowPortType.WF_PORT_SUCCESS.name())
                            .count();
                    if (successCount != 1) {
                        stopWatch.stop();
                        mf.addError(String.format("%s activity must have only one transition from port %s.", activityTypeDescription, WorkflowPortType.WF_PORT_SUCCESS.name()));
                        logger.error(String.format("[%s] Activity %s must have only one transition from port %s", logId, activityId, WorkflowPortType.WF_PORT_SUCCESS.name()));
                        return false;
                    }

                    long failureCount = ef.find("moqui.workflow.WorkflowTransition")
                            .condition("workflowId", workflowId)
                            .condition("fromActivityId", activityId)
                            .condition("fromPortTypeEnumId", WorkflowPortType.WF_PORT_FAILURE.name())
                            .count();
                    if (failureCount != 1) {
                        stopWatch.stop();
                        mf.addError(String.format("%s activity must have only one transition from port %s.", activityTypeDescription, WorkflowPortType.WF_PORT_FAILURE.name()));
                        logger.error(String.format("[%s] Activity %s must have only one transition from port %s", logId, activityId, WorkflowPortType.WF_PORT_FAILURE.name()));
                        return false;
                    }

                    long timeoutCount = ef.find("moqui.workflow.WorkflowTransition")
                            .condition("workflowId", workflowId)
                            .condition("fromActivityId", activityId)
                            .condition("fromPortTypeEnumId", WorkflowPortType.WF_PORT_TIMEOUT.name())
                            .count();
                    if (timeoutCount != 1) {
                        stopWatch.stop();
                        mf.addError(String.format("%s activity must have only one transition from port %s.", activityTypeDescription, WorkflowPortType.WF_PORT_TIMEOUT.name()));
                        logger.error(String.format("[%s] Activity %s must have only one transition from port %s", logId, activityId, WorkflowPortType.WF_PORT_TIMEOUT.name()));
                        return false;
                    }
                    break;
                }
                default: {
                    long inputCount = ef.find("moqui.workflow.WorkflowTransition")
                            .condition("workflowId", workflowId)
                            .condition("toActivityId", activityId)
                            .condition("toPortTypeEnumId", WorkflowPortType.WF_PORT_INPUT.name())
                            .count();
                    if (inputCount == 0) {
                        stopWatch.stop();
                        mf.addError(String.format("%s activity must have at least one transition to port %s.", activityTypeDescription, WorkflowPortType.WF_PORT_INPUT.name()));
                        logger.error(String.format("[%s] Activity %s must have at least one transition to port %s", logId, activityId, WorkflowPortType.WF_PORT_INPUT.name()));
                        return false;
                    }

                    long successCount = ef.find("moqui.workflow.WorkflowTransition")
                            .condition("workflowId", workflowId)
                            .condition("fromActivityId", activityId)
                            .condition("fromPortTypeEnumId", WorkflowPortType.WF_PORT_SUCCESS.name())
                            .count();
                    if (successCount != 1) {
                        stopWatch.stop();
                        mf.addError(String.format("%s activity must have only one transition from port %s.", activityTypeDescription, WorkflowPortType.WF_PORT_SUCCESS.name()));
                        logger.error(String.format("[%s] Activity %s must have only one transition from port %s", logId, activityId, WorkflowPortType.WF_PORT_SUCCESS.name()));
                        return false;
                    }

                    long failureCount = ef.find("moqui.workflow.WorkflowTransition")
                            .condition("workflowId", workflowId)
                            .condition("fromActivityId", activityId)
                            .condition("fromPortTypeEnumId", WorkflowPortType.WF_PORT_FAILURE.name())
                            .count();
                    if (failureCount != 1) {
                        stopWatch.stop();
                        mf.addError(String.format("%s activity must have only one transition from port %s.", activityTypeDescription, WorkflowPortType.WF_PORT_FAILURE.name()));
                        logger.error(String.format("[%s] Activity %s must have only one transition from port %s", logId, activityId, WorkflowPortType.WF_PORT_FAILURE.name()));
                        return false;
                    }
                    break;
                }
            }
        }

        // log the processing time
        stopWatch.stop();
        logger.debug(String.format("[%s] Workflow %s validated in %d milliseconds", logId, workflowId, stopWatch.getTime()));

        // validation success
        return true;
    }

    /**
     * Finds workflows.
     *
     * @param ec Execution context
     * @return Output parameter map
     */
    public Map<String, Object> findWorkflows(ExecutionContext ec) {

        // start the stop watch
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        // shortcuts for convenience
        ContextStack cs = ec.getContext();
        MessageFacade mf = ec.getMessage();
        EntityFacade ef = ec.getEntity();
        ServiceFacade sf = ec.getService();
        UserFacade uf = ec.getUser();

        // get the parameters
        int pageIndex = (Integer) cs.getOrDefault("pageIndex", 0);
        int pageSize = (Integer) cs.getOrDefault("pageSize", 10);
        String orderByField = (String) cs.getOrDefault("orderByField", "workflowId");
        String filter = (String) cs.getOrDefault("filter", null);

        // generate a new log ID
        String logId = ContextUtil.getLogId(ec);
        logger.debug(String.format("[%s] Finding workflows ...", logId));
        logger.debug(String.format("[%s] Param pageIndex=%s", logId, pageIndex));
        logger.debug(String.format("[%s] Param pageSize=%s", logId, pageSize));
        logger.debug(String.format("[%s] Param orderByField=%s", logId, orderByField));
        logger.debug(String.format("[%s] Param filter=%s", logId, filter));

        // prepare the conditions
        EntityConditionFactory ecf = ef.getConditionFactory();
        EntityCondition findCondition = ecf.makeCondition("workflowId", EntityCondition.ComparisonOperator.IN, getUserWorkflowIdSet(ec));

        // add the filter
        if (StringUtil.isValidElasticsearchQuery(filter)) {
            Map<String, Object> resp = sf.sync().name("org.moqui.search.SearchServices.search#DataDocuments")
                    .parameter("indexName", "workflow")
                    .parameter("documentType", "MoquiWorkflow")
                    .parameter("queryString", filter)
                    .call();

            Set<String> idSet = new HashSet<>();
            if (resp != null && resp.containsKey("documentList")) {
                List documentList = (List) resp.get("documentList");
                for (Object documentObj : documentList) {
                    if (documentObj instanceof Map) {
                        idSet.add((String) ((Map) documentObj).get("workflowId"));
                    }
                }
            }

            findCondition = ecf.makeCondition(
                    findCondition,
                    EntityCondition.JoinOperator.AND,
                    ecf.makeCondition("workflowId", EntityCondition.ComparisonOperator.IN, idSet)
            );
        }

        // find
        String userId = uf.getUserId();
        ArrayList<Map<String, Object>> workflowList = new ArrayList<>();
        EntityList workflows = ef.find("moqui.workflow.WorkflowDetail")
                .condition(findCondition)
                .searchFormMap(cs, null, null, null, false)
                .offset(pageIndex, pageSize)
                .limit(pageSize)
                .orderBy(orderByField)
                .list();
        for (EntityValue workflow : workflows) {
            workflowList.add(workflow.getMap());
        }

        // count
        long totalRows = ef.find("moqui.workflow.WorkflowDetail")
                .condition(findCondition)
                .searchFormMap(cs, null, null, null, false)
                .count();

        // log the processing time
        stopWatch.stop();
        logger.debug(String.format("[%s] Found %d workflows in %d milliseconds", logId, workflowList.size(), stopWatch.getTime()));

        // return the output parameters
        HashMap<String, Object> outParams = new HashMap<>();
        outParams.put("totalRows", totalRows);
        outParams.put("workflowList", workflowList);
        return outParams;
    }

    /**
     * Creates a new workflow.
     *
     * @param ec Execution context
     * @return Output parameter map
     */
    public Map<String, Object> createWorkflow(ExecutionContext ec) {

        // start the stop watch
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        // shortcuts for convenience
        ContextStack cs = ec.getContext();
        MessageFacade mf = ec.getMessage();
        EntityFacade ef = ec.getEntity();
        ServiceFacade sf = ec.getService();

        // get the parameters
        String launchTypeEnumId = (String) cs.getOrDefault("launchTypeEnumId", WorkflowLaunchType.WF_LAUNCH_MANUAL.name());
        String workflowTypeId = (String) cs.getOrDefault("workflowTypeId", null);
        String statusFlowId = (String) cs.getOrDefault("statusFlowId", null);
        String workflowName = (String) cs.getOrDefault("workflowName", null);
        String description = (String) cs.getOrDefault("description", null);
        String disabled = (String) cs.getOrDefault("disabled", "N");
        int reminderInterval = (Integer) cs.getOrDefault("reminderInterval", 0);
        String reminderIntervalUomId = (String) cs.getOrDefault("reminderIntervalUomId", TimeFrequency.TF_hr.name());
        int reminderLimit = (Integer) cs.getOrDefault("reminderLimit", 0);

        // generate a new log ID
        String logId = ContextUtil.getLogId(ec);
        logger.debug(String.format("[%s] Creating workflow ...", logId));
        logger.debug(String.format("[%s] Param workflowTypeId=%s", logId, workflowTypeId));
        logger.debug(String.format("[%s] Param statusFlowId=%s", logId, statusFlowId));
        logger.debug(String.format("[%s] Param launchTypeEnumId=%s", logId, launchTypeEnumId));
        logger.debug(String.format("[%s] Param workflowName=%s", logId, workflowName));
        logger.debug(String.format("[%s] Param description=%s", logId, description));
        logger.debug(String.format("[%s] Param disabled=%s", logId, disabled));
        logger.debug(String.format("[%s] Param reminderInterval=%s", logId, reminderInterval));
        logger.debug(String.format("[%s] Param reminderIntervalUomId=%s", logId, reminderIntervalUomId));
        logger.debug(String.format("[%s] Param reminderLimit=%s", logId, reminderLimit));

        // validate the parameters
        if (StringUtils.isBlank(workflowTypeId)) {
            stopWatch.stop();
            mf.addError("Workflow type is required.");
            logger.error(String.format("[%s] Workflow type is blank", logId));
            return new HashMap<>();
        } else if (StringUtils.isBlank(statusFlowId)) {
            stopWatch.stop();
            mf.addError("Status flow is required.");
            logger.error(String.format("[%s] Status flow is blank", logId));
            return new HashMap<>();
        } else if (StringUtils.isBlank(workflowName)) {
            stopWatch.stop();
            mf.addError("Workflow name is required.");
            logger.error(String.format("[%s] Workflow name is blank", logId));
            return new HashMap<>();
        }

        // create
        Map<String, Object> resp = sf.sync().name("create#moqui.workflow.Workflow")
                .parameter("workflowTypeId", workflowTypeId)
                .parameter("statusFlowId", statusFlowId)
                .parameter("launchTypeEnumId", launchTypeEnumId)
                .parameter("workflowName", workflowName)
                .parameter("description", description)
                .parameter("reminderInterval", reminderInterval)
                .parameter("reminderIntervalUomId", reminderIntervalUomId)
                .parameter("reminderLimit", reminderLimit)
                .parameter("disabled", disabled)
                .call();
        String workflowId = (String) resp.get("workflowId");

        // log the processing time
        stopWatch.stop();
        logger.debug(String.format("[%s] Workflow %s created in %d milliseconds", logId, workflowId, stopWatch.getTime()));
        mf.addMessage("Workflow created successfully.");

        // return the output parameters
        HashMap<String, Object> outParams = new HashMap<>();
        outParams.put("workflowId", workflowId);
        return outParams;
    }

    /**
     * Update an existing workflow.
     *
     * @param ec Execution context
     * @return Output parameter map
     */
    public Map<String, Object> updateWorkflow(ExecutionContext ec) {

        // start the stop watch
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        // shortcuts for convenience
        ContextStack cs = ec.getContext();
        MessageFacade mf = ec.getMessage();
        L10nFacade lf = ec.getL10n();
        EntityFacade ef = ec.getEntity();
        UserFacade uf = ec.getUser();
        ServiceFacade sf = ec.getService();

        // get the parameters
        String workflowId = (String) cs.getOrDefault("workflowId", null);
        String workflowName = (String) cs.getOrDefault("workflowName", null);
        String description = (String) cs.getOrDefault("description", null);
        String disabled = (String) cs.getOrDefault("disabled", "N");
        int reminderInterval = (Integer) cs.getOrDefault("reminderInterval", 0);
        String reminderIntervalUomId = (String) cs.getOrDefault("reminderIntervalUomId", TimeFrequency.TF_hr.name());
        int reminderLimit = (Integer) cs.getOrDefault("reminderLimit", 0);

        // generate a new log ID
        String logId = ContextUtil.getLogId(ec);
        logger.debug(String.format("[%s] Updating workflow ...", logId));
        logger.debug(String.format("[%s] Param workflowId=%s", logId, workflowId));
        logger.debug(String.format("[%s] Param workflowName=%s", logId, workflowName));
        logger.debug(String.format("[%s] Param description=%s", logId, description));
        logger.debug(String.format("[%s] Param disabled=%s", logId, disabled));
        logger.debug(String.format("[%s] Param reminderInterval=%s", logId, reminderInterval));
        logger.debug(String.format("[%s] Param reminderIntervalUomId=%s", logId, reminderIntervalUomId));
        logger.debug(String.format("[%s] Param reminderLimit=%s", logId, reminderLimit));

        // validate the parameters
        if (StringUtils.isBlank(workflowName)) {
            stopWatch.stop();
            mf.addError("Workflow name is required.");
            logger.error(String.format("[%s] Workflow name is blank", logId));
            return new HashMap<>();
        }

        // validate the workflow
        EntityValue workflow = ef.find("moqui.workflow.Workflow")
                .condition("workflowId", workflowId)
                .one();
        if (workflow == null) {
            stopWatch.stop();
            mf.addError(lf.localize("WORKFLOW_NOT_FOUND"));
            logger.error(String.format("[%s] Workflow with ID %s was not found", logId, workflowId));
            return new HashMap<>();
        }

        // update
        sf.sync().name("update#moqui.workflow.Workflow")
                .parameter("workflowId", workflowId)
                .parameter("workflowName", workflowName)
                .parameter("description", description)
                .parameter("reminderInterval", reminderInterval)
                .parameter("reminderIntervalUomId", reminderIntervalUomId)
                .parameter("reminderLimit", reminderLimit)
                .parameter("disabled", disabled)
                .parameter("updateUserId", uf.getUserId())
                .call();

        // log the processing time
        stopWatch.stop();
        logger.debug(String.format("[%s] Workflow %s updated in %d milliseconds", logId, workflowId, stopWatch.getTime()));
        mf.addMessage("Workflow updated successfully.");

        // return the output parameters
        HashMap<String, Object> outParams = new HashMap<>();
        outParams.put("workflowId", workflowId);
        return outParams;
    }

    /**
     * Disables or enables an existing workflow.
     *
     * @param ec Execution context
     * @return Output parameter map
     */
    public Map<String, Object> disableWorkflow(ExecutionContext ec) {

        // start the stop watch
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        // shortcuts for convenience
        ContextStack cs = ec.getContext();
        MessageFacade mf = ec.getMessage();
        L10nFacade lf = ec.getL10n();
        EntityFacade ef = ec.getEntity();
        UserFacade uf = ec.getUser();
        ServiceFacade sf = ec.getService();

        // get the parameters
        String workflowId = (String) cs.getOrDefault("workflowId", null);
        String disabled = (String) cs.getOrDefault("disabled", "N");

        // generate a new log ID
        String logId = ContextUtil.getLogId(ec);
        logger.debug(String.format("[%s] Disabling workflow ...", logId));
        logger.debug(String.format("[%s] Param workflowId=%s", logId, workflowId));
        logger.debug(String.format("[%s] Param disabled=%s", logId, disabled));

        // validate the workflow
        EntityValue workflow = ef.find("moqui.workflow.Workflow")
                .condition("workflowId", workflowId)
                .one();
        if (workflow == null) {
            stopWatch.stop();
            mf.addError(lf.localize("WORKFLOW_NOT_FOUND"));
            logger.error(String.format("[%s] Workflow with ID %s was not found", logId, workflowId));
            return new HashMap<>();
        }

        // update
        sf.sync().name("update#moqui.workflow.Workflow")
                .parameter("workflowId", workflowId)
                .parameter("disabled", disabled)
                .parameter("updateUserId", uf.getUserId())
                .call();

        // log the processing time
        stopWatch.stop();
        logger.debug(String.format("[%s] Workflow %s updated in %d milliseconds", logId, workflowId, stopWatch.getTime()));
        mf.addMessage("Workflow updated successfully.");

        // return the output parameters
        HashMap<String, Object> outParams = new HashMap<>();
        outParams.put("workflowId", workflowId);
        return outParams;
    }

    /**
     * Designs an existing workflow using Draw2D.
     *
     * @param ec Execution context
     * @return Output parameter map
     */
    public Map<String, Object> designWorkflow(ExecutionContext ec) {

        // start the stop watch
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        // shortcuts for convenience
        ContextStack cs = ec.getContext();
        MessageFacade mf = ec.getMessage();
        L10nFacade lf = ec.getL10n();
        EntityFacade ef = ec.getEntity();
        UserFacade uf = ec.getUser();
        ServiceFacade sf = ec.getService();

        // get the parameters
        String workflowId = (String) cs.getOrDefault("workflowId", null);
        String modelData = (String) cs.getOrDefault("modelData", null);

        // generate a new log ID
        String logId = ContextUtil.getLogId(ec);
        logger.debug(String.format("[%s] Designing workflow ...", logId));
        logger.debug(String.format("[%s] Param workflowId=%s", logId, workflowId));
        logger.debug(String.format("[%s] Param modelData=%s", logId, modelData));

        // validate the workflow
        EntityValue workflow = ef.find("moqui.workflow.Workflow")
                .condition("workflowId", workflowId)
                .one();
        if (workflow == null) {
            stopWatch.stop();
            mf.addError(lf.localize("WORKFLOW_NOT_FOUND"));
            logger.error(String.format("[%s] Workflow with ID %s was not found", logId, workflowId));
            return new HashMap<>();
        }

        // update
        sf.sync().name("update#moqui.workflow.Workflow")
                .parameter("workflowId", workflowId)
                .parameter("modelData", modelData)
                .parameter("updateUserId", uf.getUserId())
                .call();
        workflow.refresh();

        // sync the workflow
        syncWorkflowWithModel(ec, workflow);

        // validate the workflow model
        if (!validateWorkflowModel(ec, workflow)) {
            stopWatch.stop();
            String error = mf.getErrorsString();
            mf.clearAll();
            mf.addError(String.format("Workflow model validation failed: %s", error));
            logger.error(String.format("[%s] Workflow model validation failed", logId));
            return new HashMap<>();
        }


        // log the processing time
        stopWatch.stop();
        logger.debug(String.format("[%s] Workflow %s designed in %d milliseconds", logId, workflowId, stopWatch.getTime()));
        mf.addMessage("Workflow designed successfully.");

        // return the output parameters
        HashMap<String, Object> outParams = new HashMap<>();
        outParams.put("workflowId", workflowId);
        return outParams;
    }

    /**
     * Finds initiators of a workflow.
     *
     * @param ec Execution context
     * @return Output parameter map
     */
    public Map<String, Object> findWorkflowInitiators(ExecutionContext ec) {

        // start the stop watch
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        // shortcuts for convenience
        ContextStack cs = ec.getContext();
        MessageFacade mf = ec.getMessage();
        L10nFacade lf = ec.getL10n();
        EntityFacade ef = ec.getEntity();
        ServiceFacade sf = ec.getService();
        UserFacade uf = ec.getUser();

        // get the parameters
        String workflowId = (String) cs.getOrDefault("workflowId", null);
        int pageIndex = (Integer) cs.getOrDefault("pageIndex", 0);
        int pageSize = (Integer) cs.getOrDefault("pageSize", 10);
        String orderByField = (String) cs.getOrDefault("orderByField", "initiatorId");
        String filter = (String) cs.getOrDefault("filter", null);

        // generate a new log ID
        String logId = ContextUtil.getLogId(ec);
        logger.debug(String.format("[%s] Finding workflow initiators ...", logId));
        logger.debug(String.format("[%s] Param workflowId=%s", logId, workflowId));
        logger.debug(String.format("[%s] Param pageIndex=%s", logId, pageIndex));
        logger.debug(String.format("[%s] Param pageSize=%s", logId, pageSize));
        logger.debug(String.format("[%s] Param orderByField=%s", logId, orderByField));
        logger.debug(String.format("[%s] Param filter=%s", logId, filter));

        // validate the workflow
        EntityValue workflow = ef.find("moqui.workflow.Workflow")
                .condition("workflowId", workflowId)
                .one();
        if (workflow == null) {
            stopWatch.stop();
            mf.addError(lf.localize("WORKFLOW_NOT_FOUND"));
            logger.error(String.format("[%s] Workflow with ID %s was not found", logId, workflowId));
            return new HashMap<>();
        }

        // prepare the conditions
        EntityConditionFactory ecf = ef.getConditionFactory();
        EntityCondition findCondition = ecf.makeCondition("workflowId", EntityCondition.ComparisonOperator.EQUALS, workflowId);

        // add the filter
        if (StringUtil.isValidElasticsearchQuery(filter)) {
            Map<String, Object> resp = sf.sync().name("org.moqui.search.SearchServices.search#DataDocuments")
                    .parameter("indexName", "workflow")
                    .parameter("documentType", "MoquiWorkflowInitiator")
                    .parameter("queryString", filter)
                    .call();

            Set<String> idSet = new HashSet<>();
            if (resp != null && resp.containsKey("documentList")) {
                List documentList = (List) resp.get("documentList");
                for (Object documentObj : documentList) {
                    if (documentObj instanceof Map) {
                        idSet.add((String) ((Map) documentObj).get("initiatorId"));
                    }
                }
            }

            findCondition = ecf.makeCondition(
                    findCondition,
                    EntityCondition.JoinOperator.AND,
                    ecf.makeCondition("initiatorId", EntityCondition.ComparisonOperator.IN, idSet)
            );
        }

        // find
        ArrayList<Map<String, Object>> workflowInitiatorList = new ArrayList<>();
        EntityList workflowInitiators = ef.find("moqui.workflow.WorkflowInitiatorDetail")
                .condition(findCondition)
                .offset(pageIndex, pageSize)
                .limit(pageSize)
                .orderBy(orderByField)
                .list();
        for (EntityValue workflowInitiator : workflowInitiators) {
            Map<String, Object> map = workflowInitiator.getMap();
            workflowInitiatorList.add(map);
        }

        // count
        long totalRows = ef.find("moqui.workflow.WorkflowInitiatorDetail")
                .condition(findCondition)
                .count();

        // log the processing time
        stopWatch.stop();
        logger.debug(String.format("[%s] Found %d workflow initiators in %d milliseconds", logId, workflowInitiatorList.size(), stopWatch.getTime()));

        // return the output parameters
        HashMap<String, Object> outParams = new HashMap<>();
        outParams.put("totalRows", totalRows);
        outParams.put("workflowInitiatorList", workflowInitiatorList);
        return outParams;
    }

    /**
     * Creates a workflow initiator.
     *
     * @param ec Execution context
     * @return Output parameter map
     */
    public Map<String, Object> createWorkflowInitiator(ExecutionContext ec) {

        // start the stop watch
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        // shortcuts for convenience
        ContextStack cs = ec.getContext();
        MessageFacade mf = ec.getMessage();
        L10nFacade lf = ec.getL10n();
        EntityFacade ef = ec.getEntity();
        ServiceFacade sf = ec.getService();

        // get the parameters
        String workflowId = (String) cs.getOrDefault("workflowId", null);
        String userGroupId = (String) cs.getOrDefault("userGroupId", null);
        String fromDate = (String) cs.getOrDefault("fromDate", null);
        String toDate = (String) cs.getOrDefault("toDate", null);

        // generate a new log ID
        String logId = ContextUtil.getLogId(ec);
        logger.debug(String.format("[%s] Creating workflow initiator ...", logId));
        logger.debug(String.format("[%s] Param workflowId=%s", logId, workflowId));
        logger.debug(String.format("[%s] Param userGroupId=%s", logId, userGroupId));
        logger.debug(String.format("[%s] Param fromDate=%s", logId, fromDate));
        logger.debug(String.format("[%s] Param toDate=%s", logId, toDate));

        // validate the parameters
        if (StringUtils.isBlank(userGroupId)) {
            stopWatch.stop();
            mf.addError("User group ID is required.");
            logger.error(String.format("[%s] User group ID is blank", logId));
            return new HashMap<>();
        }

        // validate the workflow
        EntityValue workflow = ef.find("moqui.workflow.Workflow")
                .condition("workflowId", workflowId)
                .one();
        if (workflow == null) {
            stopWatch.stop();
            mf.addError(lf.localize("WORKFLOW_NOT_FOUND"));
            logger.error(String.format("[%s] Workflow with ID %s was not found", logId, workflowId));
            return new HashMap<>();
        }

        // parse the from date
        Timestamp fromDateTs = TimestampUtil.now();
        try {
            if (StringUtils.isNumeric(fromDate)) {
                fromDateTs = new Timestamp(Long.parseLong(fromDate));
            } else if (StringUtils.isNotBlank(fromDate)) {
                DateFormat df = new SimpleDateFormat("yyyy-MM-dd");
                fromDateTs = new Timestamp(df.parse(fromDate).getTime());
            }
        } catch (ParseException e) {
            logger.error(String.format("[%s] An error occurred while parsing from date: %s", logId, e.getMessage()), e);
        }

        // parse the to date
        Timestamp toDateTs = null;
        try {
            if (StringUtils.isNumeric(toDate)) {
                toDateTs = new Timestamp(Long.parseLong(toDate));
            } else if (StringUtils.isNotBlank(toDate)) {
                DateFormat df = new SimpleDateFormat("yyyy-MM-dd");
                toDateTs = new Timestamp(df.parse(toDate).getTime());
            }
        } catch (ParseException e) {
            logger.error(String.format("[%s] An error occurred while parsing to date: %s", logId, e.getMessage()), e);
        }

        // create
        Map<String, Object> resp = sf.sync().name("create#moqui.workflow.WorkflowInitiator")
                .parameter("workflowId", workflowId)
                .parameter("userGroupId", userGroupId)
                .parameter("fromDate", fromDateTs)
                .parameter("toDate", toDateTs)
                .call();
        String initiatorId = (String) resp.get("initiatorId");

        // log the processing time
        stopWatch.stop();
        logger.debug(String.format("[%s] Workflow initiator %s created in %d milliseconds", logId, initiatorId, stopWatch.getTime()));
        mf.addMessage("Workflow initiator created successfully.");

        // return the output parameters
        HashMap<String, Object> outParams = new HashMap<>();
        outParams.put("workflowId", workflowId);
        outParams.put("initiatorId", initiatorId);
        return outParams;
    }

    /**
     * Expire a workflow initiator.
     *
     * @param ec Execution context
     * @return Output parameter map
     */
    public Map<String, Object> expireWorkflowInitiator(ExecutionContext ec) {

        // start the stop watch
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        // shortcuts for convenience
        ContextStack cs = ec.getContext();
        MessageFacade mf = ec.getMessage();
        EntityFacade ef = ec.getEntity();
        UserFacade uf = ec.getUser();
        ServiceFacade sf = ec.getService();

        // get the parameters
        String initiatorId = (String) cs.getOrDefault("initiatorId", null);

        // generate a new log ID
        String logId = ContextUtil.getLogId(ec);
        logger.debug(String.format("[%s] Expiring workflow initiator ...", logId));
        logger.debug(String.format("[%s] Param initiatorId=%s", logId, initiatorId));

        // validate the parameters
        if (StringUtils.isBlank(initiatorId)) {
            stopWatch.stop();
            mf.addError("Initiator ID is required.");
            logger.error(String.format("[%s] Workflow ID is blank", logId));
            return new HashMap<>();
        }

        // get the initiator
        EntityValue initiator = ef.find("moqui.workflow.WorkflowInitiator")
                .condition("initiatorId", initiatorId)
                .one();

        // validate the workflow
        if (initiator == null) {
            stopWatch.stop();
            mf.addError("Initiator not found.");
            logger.error(String.format("[%s] Initiator with ID %s was not found", logId, initiatorId));
            return new HashMap<>();
        }

        // update
        Timestamp toDateTs = initiator.getTimestamp("toDate");
        if (toDateTs == null || toDateTs.after(TimestampUtil.now())) {
            sf.sync().name("update#moqui.workflow.WorkflowInitiator")
                    .parameter("initiatorId", initiatorId)
                    .parameter("toDate", TimestampUtil.now())
                    .parameter("updateUserId", uf.getUserId())
                    .call();
        }

        // log the processing time
        stopWatch.stop();
        logger.debug(String.format("[%s] Workflow initiator %s expired in %d milliseconds", logId, initiatorId, stopWatch.getTime()));
        mf.addMessage("Workflow initiator expired successfully.");

        // return the output parameters
        return new HashMap<>();
    }

    /**
     * Finds workflow variables.
     *
     * @param ec Execution context
     * @return Output parameter map
     */
    public Map<String, Object> findWorkflowVariables(ExecutionContext ec) {

        // start the stop watch
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        // shortcuts for convenience
        ContextStack cs = ec.getContext();
        EntityFacade ef = ec.getEntity();
        ServiceFacade sf = ec.getService();
        MessageFacade mf = ec.getMessage();
        L10nFacade lf = ec.getL10n();

        // get the parameters
        String workflowId = (String) cs.getOrDefault("workflowId", null);
        int pageIndex = (Integer) cs.getOrDefault("pageIndex", 0);
        int pageSize = (Integer) cs.getOrDefault("pageSize", 10);
        String orderByField = (String) cs.getOrDefault("orderByField", "variableName");
        String filter = (String) cs.getOrDefault("filter", null);

        // generate a new log ID
        String logId = ContextUtil.getLogId(ec);
        logger.debug(String.format("[%s] Finding workflow variables ...", logId));
        logger.debug(String.format("[%s] Param workflowId=%s", logId, workflowId));
        logger.debug(String.format("[%s] Param pageIndex=%s", logId, pageIndex));
        logger.debug(String.format("[%s] Param pageSize=%s", logId, pageSize));
        logger.debug(String.format("[%s] Param orderByField=%s", logId, orderByField));
        logger.debug(String.format("[%s] Param filter=%s", logId, filter));

        // validate the workflow
        EntityValue workflow = ef.find("moqui.workflow.Workflow")
                .condition("workflowId", workflowId)
                .one();
        if (workflow == null) {
            stopWatch.stop();
            mf.addError(lf.localize("WORKFLOW_NOT_FOUND"));
            logger.error(String.format("[%s] Workflow with ID %s was not found", logId, workflowId));
            return new HashMap<>();
        }

        // prepare the conditions
        EntityConditionFactory ecf = ef.getConditionFactory();
        EntityCondition findCondition = ecf.makeCondition("workflowId", EntityCondition.ComparisonOperator.EQUALS, workflowId);

        // add the filter
        if (StringUtil.isValidElasticsearchQuery(filter)) {
            Map<String, Object> resp = sf.sync().name("org.moqui.search.SearchServices.search#DataDocuments")
                    .parameter("indexName", "workflow")
                    .parameter("documentType", "MoquiWorkflowVariable")
                    .parameter("queryString", String.format("workflowId:%s AND %s", workflowId, filter))
                    .call();

            Set<String> idSet = new HashSet<>();
            if (resp != null && resp.containsKey("documentList")) {
                List documentList = (List) resp.get("documentList");
                for (Object documentObj : documentList) {
                    if (documentObj instanceof Map) {
                        idSet.add((String) ((Map) documentObj).get("variableId"));
                    }
                }
            }

            findCondition = ecf.makeCondition(
                    findCondition,
                    EntityCondition.JoinOperator.AND,
                    ecf.makeCondition("variableId", EntityCondition.ComparisonOperator.IN, idSet)
            );
        }

        // find
        ArrayList<Map<String, Object>> workflowVariableList = new ArrayList<>();
        EntityList workflowVariables = ef.find("moqui.workflow.WorkflowVariableDetail")
                .condition(findCondition)
                .searchFormMap(cs, null, null, null, false)
                .offset(pageIndex, pageSize)
                .limit(pageSize)
                .orderBy(orderByField)
                .list();
        for (EntityValue workflowVariable : workflowVariables) {
            workflowVariableList.add(workflowVariable.getMap());
        }

        // count
        long totalRows = ef.find("moqui.workflow.WorkflowVariableDetail")
                .condition(findCondition)
                .searchFormMap(cs, null, null, null, false)
                .count();

        // log the processing time
        stopWatch.stop();
        logger.debug(String.format("[%s] Found %d workflow variables in %d milliseconds", logId, workflowVariableList.size(), stopWatch.getTime()));

        // return the output parameters
        HashMap<String, Object> outParams = new HashMap<>();
        outParams.put("totalRows", totalRows);
        outParams.put("workflowVariableList", workflowVariableList);
        return outParams;
    }

    /**
     * Creates a workflow variable.
     *
     * @param ec Execution context
     * @return Output parameter map
     */
    public Map<String, Object> createWorkflowVariable(ExecutionContext ec) {

        // start the stop watch
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        // shortcuts for convenience
        ContextStack cs = ec.getContext();
        MessageFacade mf = ec.getMessage();
        L10nFacade lf = ec.getL10n();
        EntityFacade ef = ec.getEntity();
        UserFacade uf = ec.getUser();
        ServiceFacade sf = ec.getService();

        // get the parameters
        String workflowId = (String) cs.getOrDefault("workflowId", null);
        String variableTypeEnumId = (String) cs.getOrDefault("variableTypeEnumId", WorkflowVariableType.WF_VAR_TEXT.name());
        String variableName = (String) cs.getOrDefault("variableName", null);
        String description = (String) cs.getOrDefault("description", null);
        String defaultValue = (String) cs.getOrDefault("defaultValue", null);

        // generate a new log ID
        String logId = ContextUtil.getLogId(ec);
        logger.debug(String.format("[%s] Creating workflow variable ...", logId));
        logger.debug(String.format("[%s] Param workflowId=%s", logId, workflowId));
        logger.debug(String.format("[%s] Param variableTypeEnumId=%s", logId, variableTypeEnumId));
        logger.debug(String.format("[%s] Param variableName=%s", logId, variableName));
        logger.debug(String.format("[%s] Param description=%s", logId, description));
        logger.debug(String.format("[%s] Param defaultValue=%s", logId, defaultValue));

        // validate the parameters
        if (StringUtils.isBlank(variableName)) {
            stopWatch.stop();
            mf.addError("Variable name is required.");
            logger.error(String.format("[%s] Variable name is blank", logId));
            return new HashMap<>();
        }

        // validate the workflow
        EntityValue workflow = ef.find("moqui.workflow.Workflow")
                .condition("workflowId", workflowId)
                .one();
        if (workflow == null) {
            stopWatch.stop();
            mf.addError(lf.localize("WORKFLOW_NOT_FOUND"));
            logger.error(String.format("[%s] Workflow with ID %s was not found", logId, workflowId));
            return new HashMap<>();
        }

        // create
        Map<String, Object> resp = sf.sync().name("create#moqui.workflow.WorkflowVariable")
                .parameter("workflowId", workflowId)
                .parameter("variableTypeEnumId", variableTypeEnumId)
                .parameter("variableName", variableName)
                .parameter("description", description)
                .parameter("defaultValue", defaultValue)
                .call();
        String variableId = (String) resp.get("variableId");

        // log the processing time
        stopWatch.stop();
        logger.debug(String.format("[%s] Workflow variable %s created in %d milliseconds", logId, variableId, stopWatch.getTime()));
        mf.addMessage("Workflow variable created successfully.");

        // return the output parameters
        HashMap<String, Object> outParams = new HashMap<>();
        outParams.put("variableId", variableId);
        return outParams;
    }

    /**
     * Creates a new workflow instance.
     *
     * @param ec Execution context
     * @return Output parameter map
     */
    public Map<String, Object> createWorkflowInstance(ExecutionContext ec) {

        // start the stop watch
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        // shortcuts for convenience
        ContextStack cs = ec.getContext();
        MessageFacade mf = ec.getMessage();
        L10nFacade lf = ec.getL10n();
        EntityFacade ef = ec.getEntity();
        EntityConditionFactory ecf = ef.getConditionFactory();
        UserFacade uf = ec.getUser();
        ServiceFacade sf = ec.getService();

        // get the parameters
        String workflowId = (String) cs.getOrDefault("workflowId", null);
        String primaryKeyValue = (String) cs.getOrDefault("primaryKeyValue", null);
        String actionTypeEnumId = (String) cs.getOrDefault("actionTypeEnumId", null);

        // generate a new log ID
        String logId = ContextUtil.getLogId(ec);
        logger.debug(String.format("[%s] Creating workflow instance ...", logId));
        logger.debug(String.format("[%s] Param workflowId=%s", logId, workflowId));
        logger.debug(String.format("[%s] Param primaryKeyValue=%s", logId, primaryKeyValue));
        logger.debug(String.format("[%s] Param actionTypeEnumId=%s", logId, actionTypeEnumId));

        // validate the parameters
        if (StringUtils.isBlank(primaryKeyValue)) {
            stopWatch.stop();
            mf.addError("Primary key value is required.");
            logger.error(String.format("[%s] Primary key value is blank", logId));
            return new HashMap<>();
        }

        // validate the workflow
        EntityValue workflow = ef.find("moqui.workflow.WorkflowDetail")
                .condition("workflowId", workflowId)
                .one();
        if (workflow == null) {
            stopWatch.stop();
            mf.addError(lf.localize("WORKFLOW_NOT_FOUND"));
            logger.error(String.format("[%s] Workflow with ID %s was not found", logId, workflowId));
            return new HashMap<>();
        } else if (workflow.getString("disabled").equals("Y")) {
            stopWatch.stop();
            mf.addError(lf.localize("WORKFLOW_DISABLED"));
            logger.error(String.format("[%s] Workflow is disabled", logId));
            return new HashMap<>();
        }

        // make sure the entity exists
        String primaryEntityName = workflow.getString("primaryEntityName");
        String primaryKeyField = workflow.getString("primaryKeyField");
        long entityCount = ef.find(primaryEntityName)
                .condition(primaryKeyField, primaryKeyValue)
                .count();
        if (entityCount != 1) {
            stopWatch.stop();
            mf.addError("No matching entity found.");
            logger.error(String.format("[%s] No matching entity found", logId));
            return new HashMap<>();
        }

        // make sure no instance is already running
        long instanceCount = ef.find("moqui.workflow.WorkflowInstance")
                .condition("workflowId", workflowId)
                .condition("primaryKeyValue", primaryKeyValue)
                .condition(ecf.makeCondition(
                        Arrays.asList(
                                ecf.makeCondition("statusId", EntityCondition.ComparisonOperator.EQUALS, WorkflowInstanceStatus.WF_INST_STAT_PEND.name()),
                                ecf.makeCondition("statusId", EntityCondition.ComparisonOperator.EQUALS, WorkflowInstanceStatus.WF_INST_STAT_ACTIVE.name()),
                                ecf.makeCondition("statusId", EntityCondition.ComparisonOperator.EQUALS, WorkflowInstanceStatus.WF_INST_STAT_SUSPEND.name())
                        ),
                        EntityCondition.JoinOperator.OR
                ))
                .condition("statusId", "N")
                .count();
        if (instanceCount > 0) {
            stopWatch.stop();
            mf.addError("Instance for entity already exists.");
            logger.error(String.format("[%s] Instance for entity already exists", logId));
            return new HashMap<>();
        }

        // create instance
        String workflowName = workflow.getString("workflowName");
        logger.debug(String.format("[%s] Workflow %s (%s) will be instantiated", logId, workflowId, workflowName));
        Map<String, Object> resp = sf.sync().name("create#moqui.workflow.WorkflowInstance")
                .parameter("workflowId", workflowId)
                .parameter("primaryKeyValue", primaryKeyValue)
                .parameter("actionTypeEnumId", actionTypeEnumId)
                .parameter("statusId", WorkflowInstanceStatus.WF_INST_STAT_PEND)
                .call();
        String instanceId = (String) resp.get("instanceId");

        // create instance variables
        EntityList variables = ef.find("moqui.workflow.WorkflowVariable")
                .condition("workflowId", workflowId)
                .list();
        for (EntityValue variable : variables) {
            sf.sync().name("create#moqui.workflow.WorkflowInstanceVariable")
                    .parameter("instanceId", instanceId)
                    .parameter("variableId", variable.get("variableId"))
                    .parameter("definedValue", variable.get("defaultValue"))
                    .call();
        }

        // log the processing time
        stopWatch.stop();
        logger.debug(String.format("[%s] Instance %s created in %d milliseconds", logId, instanceId, stopWatch.getTime()));
        mf.addMessage("Instance created successfully.");

        // return the output parameters
        HashMap<String, Object> outParams = new HashMap<>();
        outParams.put("instanceId", instanceId);
        return outParams;
    }

    /**
     * Starts a workflow instance.
     *
     * @param ec Execution context
     * @return Output parameter map
     */
    public Map<String, Object> startWorkflowInstance(ExecutionContext ec) {

        // start the stop watch
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        // shortcuts for convenience
        ContextStack cs = ec.getContext();
        MessageFacade mf = ec.getMessage();
        L10nFacade lf = ec.getL10n();
        EntityFacade ef = ec.getEntity();
        EntityConditionFactory ecf = ef.getConditionFactory();
        UserFacade uf = ec.getUser();
        ServiceFacade sf = ec.getService();

        // get the parameters
        String instanceId = (String) cs.getOrDefault("instanceId", null);

        // generate a new log ID
        String logId = ContextUtil.getLogId(ec);
        logger.debug(String.format("[%s] Executing workflow instance ...", logId));
        logger.debug(String.format("[%s] Param instanceId=%s", logId, instanceId));

        // validate the instance
        EntityValue instance = ef.find("moqui.workflow.WorkflowInstance")
                .condition("instanceId", instanceId)
                .one();
        if (instance == null) {
            stopWatch.stop();
            mf.addError(lf.localize("WORKFLOW_INSTANCE_NOT_FOUND"));
            logger.error(String.format("[%s] Instance with ID %s not found", logId, instanceId));
            return new HashMap<>();
        } else if (instance.getString("statusId").equals(WorkflowInstanceStatus.WF_INST_STAT_COMPLETE.name()) || instance.getString("statusId").equals(WorkflowInstanceStatus.WF_INST_STAT_ABORT.name())) {
            stopWatch.stop();
            mf.addError(lf.localize("WORKFLOW_INSTANCE_NOT_OPERABLE"));
            logger.error(String.format("[%s] Instance not in operable state", logId));
            return new HashMap<>();
        }

        // validate the workflow
        String workflowId = instance.getString("workflowId");
        EntityValue workflow = ef.find("moqui.workflow.WorkflowDetail")
                .condition("workflowId", workflowId)
                .one();
        if (workflow == null) {
            stopWatch.stop();
            mf.addError(lf.localize("WORKFLOW_NOT_FOUND"));
            logger.error(String.format("[%s] Workflow with ID %s was not found", logId, workflowId));
            return new HashMap<>();
        } else if (workflow.getString("disabled").equals("Y")) {
            stopWatch.stop();
            mf.addError(lf.localize("WORKFLOW_DISABLED"));
            logger.error(String.format("[%s] Workflow is disabled", logId));
            return new HashMap<>();
        }

        // lock the instance
        String serverName = ServerUtil.getServerName();
        sf.sync().name("update#moqui.workflow.WorkflowInstance")
                .parameter("instanceId", instanceId)
                .parameter("semaphore", serverName)
                .call();
        instance.refresh();

        // proceed only if instance is locked by this server
        String semaphore = instance.getString("semaphore");
        if (semaphore.equals(serverName)) {

            // if current activity not set, then set activity to WF_ACTIVITY_ENTER or fail
            if (StringUtils.isBlank(instance.getString("activityId"))) {
                EntityList activities = ef.find("moqui.workflow.WorkflowActivityDetail")
                        .condition("workflowId", workflow.getString("workflowId"))
                        .condition("activityTypeEnumId", WorkflowActivityType.WF_ACTIVITY_ENTER.name())
                        .list();
                if (activities.isEmpty()) {
                    stopWatch.stop();
                    mf.addError(lf.localize("WORKFLOW_INSTANCE_NO_ENTRY_ACTIVITY"));
                    logger.error(String.format("[%s] Instance has no entry activity", logId));
                    return new HashMap<>();
                }

                EntityValue activity = activities.getFirst();
                sf.sync().name("update#moqui.workflow.WorkflowInstance")
                        .parameter("instanceId", instanceId)
                        .parameter("statusId", WorkflowInstanceStatus.WF_INST_STAT_ACTIVE)
                        .parameter("activityId", activity.getString("activityId"))
                        .parameter("activityExecuted", "N")
                        .parameter("lastUpdateDate", TimestampUtil.now())
                        .call();
                instance.refresh();
            } else {
                sf.sync().name("update#moqui.workflow.WorkflowInstance")
                        .parameter("instanceId", instanceId)
                        .parameter("statusId", WorkflowInstanceStatus.WF_INST_STAT_ACTIVE)
                        .parameter("lastUpdateDate", TimestampUtil.now())
                        .call();
            }

            // advance the workflow
            boolean workflowAdvanced = true;
            while (workflowAdvanced) {

                // get current activity
                EntityValue currentActivity = ef.find("moqui.workflow.WorkflowActivityDetail")
                        .condition("activityId", instance.getString("activityId"))
                        .one();
                String currentActivityId = currentActivity.getString("activityId");
                WorkflowActivityType currentActivityType = WorkflowActivityType.valueOf(currentActivity.getString("activityTypeEnumId"));
                logger.debug(String.format("[%s] Instance is currently in %s activity (%s)", logId, currentActivityType.name(), currentActivityId));

                // execute the activity if not executed yet
                Boolean activitySuccess = null;
                if (instance.getString("activityExecuted").equals("N")) {

                    // get the workflow activity handler
                    WorkflowActivity activity;
                    switch (currentActivityType) {
                        case WF_ACTIVITY_ENTER:
                            activity = new WorkflowEnterActivity(currentActivity);
                            break;
                        case WF_ACTIVITY_EXIT:
                            activity = new WorkflowExitActivity(currentActivity);
                            break;
                        case WF_ACTIVITY_ADJUST:
                            activity = new WorkflowAdjustmentActivity(currentActivity);
                            break;
                        case WF_ACTIVITY_CONDITION:
                            activity = new WorkflowConditionActivity(currentActivity);
                            break;
                        case WF_ACTIVITY_USER:
                            activity = new WorkflowUserActivity(currentActivity);
                            break;
                        case WF_ACTIVITY_SERVICE:
                            activity = new WorkflowServiceActivity(currentActivity);
                            break;
                        case WF_ACTIVITY_NOTIFY:
                            activity = new WorkflowNotificationActivity(currentActivity);
                            break;
                        default:
                            activity = null;
                            break;
                    }

                    // execute the activity
                    activitySuccess = activity.execute(ec, instance);
                    sf.sync().name("update#moqui.workflow.WorkflowInstance")
                            .parameter("instanceId", instanceId)
                            .parameter("activityExecuted", "Y")
                            .parameter("lastUpdateDate", TimestampUtil.now())
                            .call();
                    instance.refresh();
                }

                // find next transition
                EntityValue nextTransition = null;
                WorkflowPortType outgoingPortType = null;
                if (currentActivityType == WorkflowActivityType.WF_ACTIVITY_USER) {

                    // get the task type
                    JSONObject nodeData = new JSONObject(currentActivity.getString("nodeData"));
                    WorkflowTaskType taskType = nodeData.has("taskTypeEnumId") ? EnumUtils.getEnum(WorkflowTaskType.class, nodeData.getString("taskTypeEnumId")) : null;

                    // check if activity has timed out
                    Timestamp timeoutDate = instance.getTimestamp("timeoutDate");
                    if (timeoutDate != null && timeoutDate.before(TimestampUtil.now())) {
                        outgoingPortType = WorkflowPortType.WF_PORT_TIMEOUT;
                        nextTransition = ef.find("moqui.workflow.WorkflowTransitionDetail")
                                .condition("fromActivityId", currentActivityId)
                                .condition("fromPortTypeEnumId", outgoingPortType.name())
                                .list()
                                .getFirst();
                    } else if (taskType == WorkflowTaskType.WF_TASK_APPROVAL) {

                        // evaluate crowds
                        EntityCondition.JoinOperator joinOperator = nodeData.has("joinOperator") ? EnumUtils.getEnum(EntityCondition.JoinOperator.class, nodeData.getString("joinOperator")) : null;
                        JSONArray crowds = nodeData.has("crowds") ? nodeData.getJSONArray("crowds") : new JSONArray();
                        boolean conditionsMet = joinOperator == EntityCondition.JoinOperator.AND;
                        for (int i=0; i<crowds.length(); i++) {
                            JSONObject crowd = crowds.getJSONObject(i);
                            WorkflowCrowdType crowdType = crowd.has("crowdTypeEnumId") ? EnumUtils.getEnum(WorkflowCrowdType.class, crowd.getString("crowdTypeEnumId")) : null;
                            String userId = crowd.has("userId") ? crowd.getString("userId") : null;
                            String userGroupId = crowd.has("userGroupId") ? crowd.getString("userGroupId") : null;
                            long minApprovals = crowd.has("minApprovals") ? crowd.getLong("minApprovals") : 0;
                            long minRejections = crowd.has("minRejections") ? crowd.getLong("minRejections") : 0;

                            // get user ID set
                            Set<String> userIdSet = new HashSet<>();
                            if (crowdType == WorkflowCrowdType.WF_CROWD_USER && StringUtils.isNotBlank(userId)) {
                                EntityValue userAccount = ef.find("moqui.security.UserAccount")
                                        .condition("userId", userId)
                                        .one();
                                if (userAccount!=null) {
                                    userIdSet.add(userAccount.getString("userId"));
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
                                        userIdSet.add(userAccount.getString("userId"));
                                    }
                                }
                            } else if (crowdType == WorkflowCrowdType.WF_CROWD_INITIATOR) {
                                EntityValue userAccount = ef.find("moqui.security.UserAccount")
                                        .condition("userId", instance.getString("inputUserId"))
                                        .one();
                                if (userAccount != null) {
                                    userIdSet.add(userAccount.getString("userId"));
                                }
                            }

                            // count approvals
                            long approvals = ef.find("moqui.workflow.WorkflowInstanceTask")
                                    .condition("instanceId", instanceId)
                                    .condition("activityId", currentActivityId)
                                    .condition("assignedUserId", EntityCondition.ComparisonOperator.IN, userIdSet)
                                    .condition("statusId", WorkflowTaskStatus.WF_TASK_STAT_APPROVE)
                                    .count();
                            long rejections = ef.find("moqui.workflow.WorkflowInstanceTask")
                                    .condition("instanceId", instanceId)
                                    .condition("activityId", currentActivityId)
                                    .condition("assignedUserId", EntityCondition.ComparisonOperator.IN, userIdSet)
                                    .condition("statusId", WorkflowTaskStatus.WF_TASK_STAT_REJECT)
                                    .count();

                            // determine outgoing port type
                            if (rejections >= minRejections) {
                                outgoingPortType = WorkflowPortType.WF_PORT_FAILURE;
                                break;
                            } else if (approvals >= minApprovals) {
                                outgoingPortType = WorkflowPortType.WF_PORT_SUCCESS;
                                if(joinOperator == EntityCondition.JoinOperator.OR) {
                                    break;
                                }
                            } else {
                                outgoingPortType = null;
                                if(joinOperator == EntityCondition.JoinOperator.AND) {
                                    break;
                                }
                            }
                        }

                        // lookup next transition
                        if (outgoingPortType != null) {
                            nextTransition = ef.find("moqui.workflow.WorkflowTransitionDetail")
                                    .condition("fromActivityId", currentActivityId)
                                    .condition("fromPortTypeEnumId", outgoingPortType.name())
                                    .list()
                                    .getFirst();
                        }
                    } else if (taskType == WorkflowTaskType.WF_TASK_MANUAL || taskType == WorkflowTaskType.WF_TASK_VARIABLE) {

                        // count incomplete
                        long incomplete = ef.find("moqui.workflow.WorkflowInstanceTask")
                                .condition("instanceId", instanceId)
                                .condition("activityId", currentActivityId)
                                .condition(ecf.makeCondition(
                                        Arrays.asList(
                                                ecf.makeCondition("statusId", EntityCondition.ComparisonOperator.EQUALS, WorkflowTaskStatus.WF_TASK_STAT_PEND.name()),
                                                ecf.makeCondition("statusId", EntityCondition.ComparisonOperator.EQUALS, WorkflowTaskStatus.WF_TASK_STAT_PROGRESS.name())
                                        ),
                                        EntityCondition.JoinOperator.OR
                                ))
                                .count();

                        // determine port type
                        if (incomplete == 0) {
                            outgoingPortType = WorkflowPortType.WF_PORT_SUCCESS;
                        }

                        // lookup next transition
                        if (outgoingPortType != null) {
                            nextTransition = ef.find("moqui.workflow.WorkflowTransitionDetail")
                                    .condition("fromActivityId", currentActivityId)
                                    .condition("fromPortTypeEnumId", outgoingPortType.name())
                                    .list()
                                    .getFirst();
                        }
                    }
                } else if (currentActivityType == WorkflowActivityType.WF_ACTIVITY_EXIT) {
                    logger.debug(String.format("[%s] Instance reached the exit activity", logId));
                    break;
                } else if (activitySuccess != null) {

                    // determine port type
                    outgoingPortType = activitySuccess ? WorkflowPortType.WF_PORT_SUCCESS : WorkflowPortType.WF_PORT_FAILURE;

                    // lookup next transition
                    nextTransition = ef.find("moqui.workflow.WorkflowTransitionDetail")
                            .condition("fromActivityId", currentActivityId)
                            .condition("fromPortTypeEnumId", outgoingPortType.name())
                            .list()
                            .getFirst();
                } else {
                    // This case should never occur. It means that the workflow instance is currently on a non-user activity that hasn't been executed
                    logger.error(String.format("[%s] Instance may be stuck, contact your administrator!", logId));
                    break;
                }

                // follow next transition
                if (nextTransition != null) {

                    // mark incomplete tasks as obsolete
                    if (currentActivityType == WorkflowActivityType.WF_ACTIVITY_USER) {
                        EntityList tasks = ef.find("moqui.workflow.WorkflowInstanceTask")
                                .condition("instanceId", instanceId)
                                .condition("activityId", currentActivityId)
                                .condition(ecf.makeCondition(
                                        Arrays.asList(
                                                ecf.makeCondition("statusId", EntityCondition.ComparisonOperator.EQUALS, WorkflowTaskStatus.WF_TASK_STAT_PEND.name()),
                                                ecf.makeCondition("statusId", EntityCondition.ComparisonOperator.EQUALS, WorkflowTaskStatus.WF_TASK_STAT_PROGRESS.name())
                                        ),
                                        EntityCondition.JoinOperator.OR
                                ))
                                .list();
                        for (EntityValue task : tasks) {
                            sf.sync().name("update#moqui.workflow.WorkflowInstanceTask")
                                    .parameter("taskId", task.getString("taskId"))
                                    .parameter("statusId", WorkflowTaskStatus.WF_TASK_STAT_OBSOLETE)
                                    .call();
                        }
                    }

                    // update instance activity
                    String transitionId = nextTransition.getString("transitionId");
                    String fromActivityTypeDescription = nextTransition.getString("fromActivityTypeDescription");
                    String fromPortTypeDescription = WorkflowPortType.portTypeDescription(outgoingPortType);
                    String toActivityId = nextTransition.getString("toActivityId");
                    String toActivityTypeDescription = nextTransition.getString("toActivityTypeDescription");
                    logger.debug(String.format("[%s] Advanced to %s activity (%s) via %s port and transition %s",
                            logId,
                            toActivityTypeDescription,
                            toActivityId,
                            fromPortTypeDescription,
                            transitionId)
                    );
                    sf.sync().name("update#moqui.workflow.WorkflowInstance")
                            .parameter("instanceId", instanceId)
                            .parameter("activityId", toActivityId)
                            .parameter("activityExecuted", "N")
                            .parameter("lastUpdateDate", TimestampUtil.now())
                            .call();
                    instance.refresh();

                    // create event
                    WorkflowUtil.createWorkflowEvent(
                            ec,
                            instanceId,
                            WorkflowEventType.WF_EVENT_TRANSITION,
                            String.format("Advanced from %s activity (%s) to %s activity (%s) via %s port and transition %s",
                                    fromActivityTypeDescription,
                                    currentActivityId,
                                    toActivityTypeDescription,
                                    toActivityId,
                                    fromPortTypeDescription,
                                    transitionId
                            ),
                            false
                    );
                } else {
                    workflowAdvanced = false;
                }
            }

            // release the instance
            sf.sync().name("update#moqui.workflow.WorkflowInstance")
                    .parameter("instanceId", instanceId)
                    .parameter("semaphore", null)
                    .call();
            instance.refresh();
        } else {
            logger.debug(String.format("[%s] Instance locked by server %s, not executing", logId, semaphore));
        }

        // log the processing time
        stopWatch.stop();
        logger.debug(String.format("[%s] Instance %s started in %d milliseconds", logId, instanceId, stopWatch.getTime()));
        mf.addMessage("Instance started successfully.");

        // return the output parameters
        HashMap<String, Object> outParams = new HashMap<>();
        outParams.put("instanceId", instanceId);
        return outParams;
    }

    /**
     * Starts elapsed workflow instances.
     *
     * @param ec Execution context
     * @return Output parameter map
     */
    public Map<String, Object> startElapsedWorkflowInstances(ExecutionContext ec) {

        // start the stop watch
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        // shortcuts for convenience
        ContextStack cs = ec.getContext();
        MessageFacade mf = ec.getMessage();
        EntityFacade ef = ec.getEntity();
        ServiceFacade sf = ec.getService();

        // generate a new log ID
        String logId = ContextUtil.getLogId(ec);
        logger.debug(String.format("[%s] Executing elapsed workflow instances ...", logId));

        // find the documents that should expire
        Timestamp now = TimestampUtil.now();
        EntityConditionFactory ecf = ef.getConditionFactory();
        EntityList instances = ef.find("moqui.workflow.WorkflowInstance")
                .condition(ecf.makeCondition(
                        Arrays.asList(
                                ecf.makeCondition("statusId", EntityCondition.ComparisonOperator.EQUALS, WorkflowInstanceStatus.WF_INST_STAT_PEND.name()),
                                ecf.makeCondition("statusId", EntityCondition.ComparisonOperator.EQUALS, WorkflowInstanceStatus.WF_INST_STAT_ACTIVE.name()),
                                ecf.makeCondition("statusId", EntityCondition.ComparisonOperator.EQUALS, WorkflowInstanceStatus.WF_INST_STAT_SUSPEND.name())
                        ),
                        EntityCondition.JoinOperator.OR
                ))
                .condition(ecf.makeCondition(
                        Arrays.asList(
                                ecf.makeCondition("timeoutDate", EntityCondition.ComparisonOperator.IS_NOT_NULL, null),
                                ecf.makeCondition("timeoutDate", EntityCondition.ComparisonOperator.LESS_THAN, now)
                        ),
                        EntityCondition.JoinOperator.AND
                ))
                .list();
        for (EntityValue instance : instances) {
            sf.sync().name("moqui.workflow.WorkflowServices.start#WorkflowInstance")
                    .parameter("instanceId", instance.getString("instanceId"))
                    .requireNewTransaction(true)
                    .ignorePreviousError(true)
                    .disableAuthz()
                    .call();
        }

        // log the processing time
        stopWatch.stop();
        logger.debug(String.format("[%s] Started %d workflow instances %d milliseconds", logId, instances.size(), stopWatch.getTime()));

        // return the output parameters
        return new HashMap<>();
    }

    /**
     * Suspends a workflow instance.
     *
     * @param ec Execution context
     * @return Output parameter map
     */
    public Map<String, Object> suspendWorkflowInstance(ExecutionContext ec) {

        // start the stop watch
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        // shortcuts for convenience
        ContextStack cs = ec.getContext();
        MessageFacade mf = ec.getMessage();
        L10nFacade lf = ec.getL10n();
        EntityFacade ef = ec.getEntity();
        UserFacade uf = ec.getUser();
        ServiceFacade sf = ec.getService();

        // get the parameters
        String instanceId = (String) cs.getOrDefault("instanceId", null);

        // generate a new log ID
        String logId = ContextUtil.getLogId(ec);
        logger.debug(String.format("[%s] Suspending workflow instance ...", logId));
        logger.debug(String.format("[%s] Param instanceId=%s", logId, instanceId));

        // validate the instance
        EntityValue instance = ef.find("moqui.workflow.WorkflowInstance")
                .condition("instanceId", instanceId)
                .one();
        if (instance == null) {
            stopWatch.stop();
            mf.addError(lf.localize("WORKFLOW_INSTANCE_NOT_FOUND"));
            logger.error(String.format("[%s] Instance with ID %s not found", logId, instanceId));
            return new HashMap<>();
        } else if (instance.getString("statusId").equals(WorkflowInstanceStatus.WF_INST_STAT_COMPLETE.name()) || instance.getString("statusId").equals(WorkflowInstanceStatus.WF_INST_STAT_ABORT.name())) {
            stopWatch.stop();
            mf.addError(lf.localize("WORKFLOW_INSTANCE_NOT_OPERABLE"));
            logger.error(String.format("[%s] Instance not in operable state", logId));
            return new HashMap<>();
        }

        // validate the workflow
        String workflowId = instance.getString("workflowId");
        EntityValue workflow = ef.find("moqui.workflow.WorkflowDetail")
                .condition("workflowId", workflowId)
                .one();
        if (workflow == null) {
            stopWatch.stop();
            mf.addError(lf.localize("WORKFLOW_NOT_FOUND"));
            logger.error(String.format("[%s] Workflow with ID %s was not found", logId, workflowId));
            return new HashMap<>();
        } else if (workflow.getString("disabled").equals("Y")) {
            stopWatch.stop();
            mf.addError(lf.localize("WORKFLOW_DISABLED"));
            logger.error(String.format("[%s] Workflow is disabled", logId));
            return new HashMap<>();
        }

        // lock the instance
        String serverName = ServerUtil.getServerName();
        sf.sync().name("update#moqui.workflow.WorkflowInstance")
                .parameter("instanceId", instanceId)
                .parameter("semaphore", serverName)
                .call();
        instance.refresh();

        // proceed only if instance is locked by this server
        String semaphore = instance.getString("semaphore");
        if (instance.getString("semaphore").equals(serverName)) {

            // exit workflow
            sf.sync().name("update#moqui.workflow.WorkflowInstance")
                    .parameter("instanceId", instanceId)
                    .parameter("statusId", WorkflowInstanceStatus.WF_INST_STAT_SUSPEND)
                    .parameter("lastUpdateDate", TimestampUtil.now())
                    .call();

            // create event
            WorkflowUtil.createWorkflowEvent(
                    ec,
                    instanceId,
                    WorkflowEventType.WF_EVENT_SUSPEND,
                    "Workflow suspended",
                    false
            );

            // release the instance
            sf.sync().name("update#moqui.workflow.WorkflowInstance")
                    .parameter("instanceId", instanceId)
                    .parameter("semaphore", null)
                    .call();
            instance.refresh();
        } else {
            logger.debug(String.format("[%s] Instance locked by server %s, not executing", logId, semaphore));
        }

        // log the processing time
        stopWatch.stop();
        logger.debug(String.format("[%s] Instance %s suspended in %d milliseconds", logId, instanceId, stopWatch.getTime()));
        mf.addMessage("Instance suspended successfully.");

        // return the output parameters
        HashMap<String, Object> outParams = new HashMap<>();
        outParams.put("instanceId", instanceId);
        return outParams;
    }

    /**
     * Resumes a workflow instance.
     *
     * @param ec Execution context
     * @return Output parameter map
     */
    public Map<String, Object> resumeWorkflowInstance(ExecutionContext ec) {

        // start the stop watch
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        // shortcuts for convenience
        ContextStack cs = ec.getContext();
        MessageFacade mf = ec.getMessage();
        L10nFacade lf = ec.getL10n();
        EntityFacade ef = ec.getEntity();
        UserFacade uf = ec.getUser();
        ServiceFacade sf = ec.getService();

        // get the parameters
        String instanceId = (String) cs.getOrDefault("instanceId", null);

        // generate a new log ID
        String logId = ContextUtil.getLogId(ec);
        logger.debug(String.format("[%s] Resuming workflow instance ...", logId));
        logger.debug(String.format("[%s] Param instanceId=%s", logId, instanceId));

        // validate the instance
        EntityValue instance = ef.find("moqui.workflow.WorkflowInstance")
                .condition("instanceId", instanceId)
                .one();
        if (instance == null) {
            stopWatch.stop();
            mf.addError(lf.localize("WORKFLOW_INSTANCE_NOT_FOUND"));
            logger.error(String.format("[%s] Instance with ID %s not found", logId, instanceId));
            return new HashMap<>();
        } else if (instance.getString("statusId").equals(WorkflowInstanceStatus.WF_INST_STAT_COMPLETE.name()) || instance.getString("statusId").equals(WorkflowInstanceStatus.WF_INST_STAT_ABORT.name())) {
            stopWatch.stop();
            mf.addError(lf.localize("WORKFLOW_INSTANCE_NOT_OPERABLE"));
            logger.error(String.format("[%s] Instance not in operable state", logId));
            return new HashMap<>();
        }

        // validate the workflow
        String workflowId = instance.getString("workflowId");
        EntityValue workflow = ef.find("moqui.workflow.WorkflowDetail")
                .condition("workflowId", workflowId)
                .one();
        if (workflow == null) {
            stopWatch.stop();
            mf.addError(lf.localize("WORKFLOW_NOT_FOUND"));
            logger.error(String.format("[%s] Workflow with ID %s was not found", logId, workflowId));
            return new HashMap<>();
        } else if (workflow.getString("disabled").equals("Y")) {
            stopWatch.stop();
            mf.addError(lf.localize("WORKFLOW_DISABLED"));
            logger.error(String.format("[%s] Workflow is disabled", logId));
            return new HashMap<>();
        }

        // lock the instance
        String serverName = ServerUtil.getServerName();
        sf.sync().name("update#moqui.workflow.WorkflowInstance")
                .parameter("instanceId", instanceId)
                .parameter("semaphore", serverName)
                .call();
        instance.refresh();

        // proceed only if instance is locked by this server
        String semaphore = instance.getString("semaphore");
        if (instance.getString("semaphore").equals(serverName)) {

            // exit workflow
            sf.sync().name("update#moqui.workflow.WorkflowInstance")
                    .parameter("instanceId", instanceId)
                    .parameter("statusId", WorkflowInstanceStatus.WF_INST_STAT_ACTIVE)
                    .parameter("lastUpdateDate", TimestampUtil.now())
                    .call();

            // create event
            WorkflowUtil.createWorkflowEvent(
                    ec,
                    instanceId,
                    WorkflowEventType.WF_EVENT_RESUME,
                    "Workflow resumed",
                    false
            );

            // release the instance
            sf.sync().name("update#moqui.workflow.WorkflowInstance")
                    .parameter("instanceId", instanceId)
                    .parameter("semaphore", null)
                    .call();
            instance.refresh();
        } else {
            logger.debug(String.format("[%s] Instance locked by server %s, not executing", logId, semaphore));
        }

        // log the processing time
        stopWatch.stop();
        logger.debug(String.format("[%s] Instance %s resumed in %d milliseconds", logId, instanceId, stopWatch.getTime()));
        mf.addMessage(lf.localize("WORKFLOW_INSTANCE_RESUMED_SUCCESSFULLY"));

        // return the output parameters
        HashMap<String, Object> outParams = new HashMap<>();
        outParams.put("instanceId", instanceId);
        return outParams;
    }

    /**
     * Aborts a workflow instance.
     *
     * @param ec Execution context
     * @return Output parameter map
     */
    public Map<String, Object> abortWorkflowInstance(ExecutionContext ec) {

        // start the stop watch
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        // shortcuts for convenience
        ContextStack cs = ec.getContext();
        MessageFacade mf = ec.getMessage();
        L10nFacade lf = ec.getL10n();
        EntityFacade ef = ec.getEntity();
        UserFacade uf = ec.getUser();
        ServiceFacade sf = ec.getService();

        // get the parameters
        String instanceId = (String) cs.getOrDefault("instanceId", null);

        // generate a new log ID
        String logId = ContextUtil.getLogId(ec);
        logger.debug(String.format("[%s] Aborting workflow instance ...", logId));
        logger.debug(String.format("[%s] Param instanceId=%s", logId, instanceId));

        // validate the instance
        EntityValue instance = ef.find("moqui.workflow.WorkflowInstance")
                .condition("instanceId", instanceId)
                .one();
        if (instance == null) {
            stopWatch.stop();
            mf.addError(lf.localize("WORKFLOW_INSTANCE_NOT_FOUND"));
            logger.error(String.format("[%s] Instance with ID %s not found", logId, instanceId));
            return new HashMap<>();
        } else if (instance.getString("statusId").equals(WorkflowInstanceStatus.WF_INST_STAT_COMPLETE.name()) || instance.getString("statusId").equals(WorkflowInstanceStatus.WF_INST_STAT_ABORT.name())) {
            stopWatch.stop();
            mf.addError(lf.localize("WORKFLOW_INSTANCE_NOT_OPERABLE"));
            logger.error(String.format("[%s] Instance not in operable state", logId));
            return new HashMap<>();
        }

        // validate the workflow
        String workflowId = instance.getString("workflowId");
        EntityValue workflow = ef.find("moqui.workflow.WorkflowDetail")
                .condition("workflowId", workflowId)
                .one();
        if (workflow == null) {
            stopWatch.stop();
            mf.addError(lf.localize("WORKFLOW_NOT_FOUND"));
            logger.error(String.format("[%s] Workflow with ID %s was not found", logId, workflowId));
            return new HashMap<>();
        } else if (workflow.getString("disabled").equals("Y")) {
            stopWatch.stop();
            mf.addError(lf.localize("WORKFLOW_DISABLED"));
            logger.error(String.format("[%s] Workflow is disabled", logId));
            return new HashMap<>();
        }

        // lock the instance
        String serverName = ServerUtil.getServerName();
        sf.sync().name("update#moqui.workflow.WorkflowInstance")
                .parameter("instanceId", instanceId)
                .parameter("semaphore", serverName)
                .call();
        instance.refresh();

        // proceed only if instance is locked by this server
        String semaphore = instance.getString("semaphore");
        if (instance.getString("semaphore").equals(serverName)) {

            // exit workflow
            sf.sync().name("update#moqui.workflow.WorkflowInstance")
                    .parameter("instanceId", instanceId)
                    .parameter("statusId", WorkflowInstanceStatus.WF_INST_STAT_ABORT)
                    .parameter("lastUpdateDate", TimestampUtil.now())
                    .call();

            // create event
            WorkflowUtil.createWorkflowEvent(
                    ec,
                    instanceId,
                    WorkflowEventType.WF_EVENT_FINISH,
                    "Workflow aborted",
                    false
            );

            // release the instance
            sf.sync().name("update#moqui.workflow.WorkflowInstance")
                    .parameter("instanceId", instanceId)
                    .parameter("semaphore", null)
                    .call();
            instance.refresh();
        } else {
            logger.debug(String.format("[%s] Instance locked by server %s, not executing", logId, semaphore));
        }

        // log the processing time
        stopWatch.stop();
        logger.debug(String.format("[%s] Instance %s aborted in %d milliseconds", logId, instanceId, stopWatch.getTime()));
        mf.addMessage(lf.localize("WORKFLOW_INSTANCE_ABORTED_SUCCESSFULLY"));

        // return the output parameters
        HashMap<String, Object> outParams = new HashMap<>();
        outParams.put("instanceId", instanceId);
        return outParams;
    }

    /**
     * Updates a workflow instance variable.
     *
     * @param ec Execution context
     * @return Output parameter map
     */
    public Map<String, Object> updateWorkflowInstanceVariable(ExecutionContext ec) {

        // start the stop watch
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        // shortcuts for convenience
        ContextStack cs = ec.getContext();
        MessageFacade mf = ec.getMessage();
        EntityFacade ef = ec.getEntity();
        UserFacade uf = ec.getUser();
        ServiceFacade sf = ec.getService();

        // get the parameters
        String instanceId = (String) cs.getOrDefault("instanceId", null);
        String variableId = (String) cs.getOrDefault("variableId", null);
        String valueExpression = (String) cs.getOrDefault("valueExpression", null);

        // generate a new log ID
        String logId = ContextUtil.getLogId(ec);
        logger.debug(String.format("[%s] Updating workflow instance variable ...", logId));
        logger.debug(String.format("[%s] Param instanceId=%s", logId, instanceId));
        logger.debug(String.format("[%s] Param variableId=%s", logId, variableId));
        logger.debug(String.format("[%s] Param valueExpression=%s", logId, valueExpression));

        // validate the parameters
        if (StringUtils.isBlank(instanceId)) {
            stopWatch.stop();
            mf.addError("Instance ID is required.");
            logger.error(String.format("[%s] Instance ID is blank", logId));
            return new HashMap<>();
        } else if (StringUtils.isBlank(variableId)) {
            stopWatch.stop();
            mf.addError("Variable ID is required.");
            logger.error(String.format("[%s] Variable ID is blank", logId));
            return new HashMap<>();
        } else if (StringUtils.isBlank(valueExpression)) {
            stopWatch.stop();
            mf.addError("Value expression is required.");
            logger.error(String.format("[%s] Value expression is blank", logId));
            return new HashMap<>();
        }

        // init script engine
        ScriptEngineManager manager = new ScriptEngineManager();
        ScriptEngine engine = manager.getEngineByName("JavaScript");

        // replace properties
        valueExpression = valueExpression.replaceAll(" ", "");
        EntityList variables = ef.find("moqui.workflow.WorkflowInstanceVariableDetail")
                .condition("instanceId", instanceId)
                .list();
        for (EntityValue variable : variables) {
            String variableName = variable.getString("variableName");
            Object definedValue = variable.get("definedValue");
            String tempVariableName = RandomStringUtils.randomAlphabetic(4);
            valueExpression = StringUtils.replace(valueExpression, String.format("{{%s}}", variableName), tempVariableName);
            engine.put(tempVariableName, definedValue);
        }

        // evaluate expression
        Object definedValue;
        try {
            logger.debug(String.format("[%s] Evaluating value expression: %s", logId, valueExpression));
            definedValue = engine.eval(valueExpression);
        } catch (ScriptException e) {
            stopWatch.stop();
            logger.error(String.format("[%s] An error occurred while evaluating value expression: %s", logId, e.getMessage()), e);
            mf.addError("Error evaluating value expression.");
            return new HashMap<>();
        }

        // update defined value
        sf.sync().name("update#moqui.workflow.WorkflowInstanceVariable")
                .parameter("instanceId", instanceId)
                .parameter("variableId", variableId)
                .parameter("definedValue", definedValue)
                .call();

        // log the processing time
        stopWatch.stop();
        logger.debug(String.format("[%s] Instance variable %s updated in %d milliseconds", logId, variableId, stopWatch.getTime()));
        mf.addMessage("Instance variable updated successfully.");

        // return the output parameters
        HashMap<String, Object> outParams = new HashMap<>();
        outParams.put("instanceId", instanceId);
        outParams.put("variableId", variableId);
        outParams.put("definedValue", definedValue);
        return outParams;
    }

    /**
     * Finds workflow instance tasks.
     *
     * @param ec Execution context
     * @return Output parameter map
     */
    public Map<String, Object> findWorkflowInstanceTasks(ExecutionContext ec) {

        // start the stop watch
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        // shortcuts for convenience
        ContextStack cs = ec.getContext();
        MessageFacade mf = ec.getMessage();
        EntityFacade ef = ec.getEntity();
        ServiceFacade sf = ec.getService();
        UserFacade uf = ec.getUser();

        // get the parameters
        int pageIndex = (Integer) cs.getOrDefault("pageIndex", 0);
        int pageSize = (Integer) cs.getOrDefault("pageSize", 10);
        String orderByField = (String) cs.getOrDefault("orderByField", "taskId");
        String filter = (String) cs.getOrDefault("filter", null);

        // generate a new log ID
        String logId = ContextUtil.getLogId(ec);
        logger.debug(String.format("[%s] Finding workflow instance tasks ...", logId));
        logger.debug(String.format("[%s] Param pageIndex=%s", logId, pageIndex));
        logger.debug(String.format("[%s] Param pageSize=%s", logId, pageSize));
        logger.debug(String.format("[%s] Param orderByField=%s", logId, orderByField));
        logger.debug(String.format("[%s] Param filter=%s", logId, filter));

        // prepare the conditions
        EntityConditionFactory ecf = ef.getConditionFactory();
        EntityCondition findCondition = ecf.makeCondition("assignedUserId", EntityCondition.ComparisonOperator.EQUALS, uf.getUserId());

        // add the filter
        if (StringUtil.isValidElasticsearchQuery(filter)) {
            Map<String, Object> resp = sf.sync().name("org.moqui.search.SearchServices.search#DataDocuments")
                    .parameter("indexName", "workflow")
                    .parameter("documentType", "MoquiWorkflowInstanceTask")
                    .parameter("queryString", filter)
                    .call();

            Set<String> idSet = new HashSet<>();
            if (resp != null && resp.containsKey("documentList")) {
                List documentList = (List) resp.get("documentList");
                for (Object documentObj : documentList) {
                    if (documentObj instanceof Map) {
                        idSet.add((String) ((Map) documentObj).get("taskId"));
                    }
                }
            }

            findCondition = ecf.makeCondition(
                    findCondition,
                    EntityCondition.JoinOperator.AND,
                    ecf.makeCondition("taskId", EntityCondition.ComparisonOperator.IN, idSet)
            );
        }

        // find
        ArrayList<Map<String, Object>> taskList = new ArrayList<>();
        EntityList tasks = ef.find("moqui.workflow.WorkflowInstanceTaskDetail")
                .condition(findCondition)
                .searchFormMap(cs, null, null, null, false)
                .offset(pageIndex, pageSize)
                .limit(pageSize)
                .orderBy(orderByField)
                .list();
        for (EntityValue task : tasks) {
            taskList.add(task.getMap());
        }

        // count
        long totalRows = ef.find("moqui.workflow.WorkflowInstanceTaskDetail")
                .condition(findCondition)
                .searchFormMap(cs, null, null, null, false)
                .count();

        // log the processing time
        stopWatch.stop();
        logger.debug(String.format("[%s] Found %d workflow instance tasks in %d milliseconds", logId, taskList.size(), stopWatch.getTime()));

        // return the output parameters
        HashMap<String, Object> outParams = new HashMap<>();
        outParams.put("totalRows", totalRows);
        outParams.put("taskList", taskList);
        return outParams;
    }

    /**
     * Counts workflow instance tasks.
     *
     * @param ec Execution context
     * @return Output parameter map
     */
    public Map<String, Object> countWorkflowInstanceTasks(ExecutionContext ec) {

        // start the stop watch
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        // shortcuts for convenience
        ContextStack cs = ec.getContext();
        MessageFacade mf = ec.getMessage();
        EntityFacade ef = ec.getEntity();
        ServiceFacade sf = ec.getService();
        UserFacade uf = ec.getUser();

        // get the parameters
        String filter = (String) cs.getOrDefault("filter", null);

        // generate a new log ID
        String logId = ContextUtil.getLogId(ec);
        logger.debug(String.format("[%s] Counting workflow instance tasks ...", logId));
        logger.debug(String.format("[%s] Param filter=%s", logId, filter));

        // prepare the conditions
        EntityConditionFactory ecf = ef.getConditionFactory();
        EntityCondition findCondition = ecf.makeCondition("assignedUserId", EntityCondition.ComparisonOperator.EQUALS, uf.getUserId());

        // add the filter
        if (StringUtil.isValidElasticsearchQuery(filter)) {
            Map<String, Object> resp = sf.sync().name("org.moqui.search.SearchServices.search#DataDocuments")
                    .parameter("indexName", "workflow")
                    .parameter("documentType", "MoquiWorkflowInstanceTask")
                    .parameter("queryString", filter)
                    .call();

            Set<String> idSet = new HashSet<>();
            if (resp != null && resp.containsKey("documentList")) {
                List documentList = (List) resp.get("documentList");
                for (Object documentObj : documentList) {
                    if (documentObj instanceof Map) {
                        idSet.add((String) ((Map) documentObj).get("taskId"));
                    }
                }
            }

            findCondition = ecf.makeCondition(
                    findCondition,
                    EntityCondition.JoinOperator.AND,
                    ecf.makeCondition("taskId", EntityCondition.ComparisonOperator.IN, idSet)
            );
        }

        // count
        long totalRows = ef.find("moqui.workflow.WorkflowInstanceTaskDetail")
                .condition(findCondition)
                .searchFormMap(cs, null, null, null, false)
                .count();

        // log the processing time
        stopWatch.stop();
        logger.debug(String.format("[%s] Counted %d workflow instance tasks in %d milliseconds", logId, totalRows, stopWatch.getTime()));

        // return the output parameters
        HashMap<String, Object> outParams = new HashMap<>();
        outParams.put("totalRows", totalRows);
        return outParams;
    }

    /**
     * Update a workflow instance task.
     *
     * @param ec Execution context
     * @return Output parameter map
     */
    public Map<String, Object> updateWorkflowInstanceTask(ExecutionContext ec) {

        // start the stop watch
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        // shortcuts for convenience
        ContextStack cs = ec.getContext();
        MessageFacade mf = ec.getMessage();
        EntityFacade ef = ec.getEntity();
        UserFacade uf = ec.getUser();
        ServiceFacade sf = ec.getService();

        // get the parameters
        String taskId = (String) cs.getOrDefault("taskId", null);
        String statusId = (String) cs.getOrDefault("statusId", null);
        String definedValue = (String) cs.getOrDefault("definedValue", null);
        String remark = (String) cs.getOrDefault("remark", null);

        // generate a new log ID
        String logId = ContextUtil.getLogId(ec);
        logger.debug(String.format("[%s] Updating workflow instance task ...", logId));
        logger.debug(String.format("[%s] Param taskId=%s", logId, taskId));
        logger.debug(String.format("[%s] Param statusId=%s", logId, statusId));
        logger.debug(String.format("[%s] Param definedValue=%s", logId, definedValue));
        logger.debug(String.format("[%s] Param remark=%s", logId, remark));

        // validate the parameters
        if (StringUtils.isBlank(taskId)) {
            stopWatch.stop();
            mf.addError("Task ID is required.");
            logger.error(String.format("[%s] Task ID is blank", logId));
            return new HashMap<>();
        }

        // get the task
        EntityValue task = ef.find("moqui.workflow.WorkflowInstanceTask")
                .condition("taskId", taskId)
                .one();

        // validate the task
        if (task == null) {
            stopWatch.stop();
            mf.addError("Task not found.");
            logger.error(String.format("[%s] Task with ID %s was not found", logId, taskId));
            return new HashMap<>();
        } else if (!task.getString("assignedUserId").equals(uf.getUserId())) {
            stopWatch.stop();
            mf.addError("Access to task denied.");
            logger.error(String.format("[%s] Access to task denied", logId));
            return new HashMap<>();
        }

        // init the completion date
        Timestamp completionDate = null;
        WorkflowTaskStatus status = EnumUtils.getEnum(WorkflowTaskStatus.class, statusId);
        if (status == WorkflowTaskStatus.WF_TASK_STAT_DONE || status == WorkflowTaskStatus.WF_TASK_STAT_APPROVE || status == WorkflowTaskStatus.WF_TASK_STAT_REJECT) {
            completionDate = TimestampUtil.now();
        }

        // update task
        sf.sync().name("update#moqui.workflow.WorkflowInstanceTask")
                .parameter("taskId", taskId)
                .parameter("statusId", statusId)
                .parameter("remark", remark)
                .parameter("completionDate", completionDate)
                .call();

        // update instance variable
        if (StringUtils.isNotBlank(definedValue)) {
            sf.sync().name("update#moqui.workflow.WorkflowInstanceVariable")
                    .parameter("instanceId", task.get("instanceId"))
                    .parameter("variableId", task.get("variableId"))
                    .parameter("definedValue", definedValue)
                    .call();
        }

        // log the processing time
        stopWatch.stop();
        logger.debug(String.format("[%s] Workflow instance task %s updated in %d milliseconds", logId, taskId, stopWatch.getTime()));
        mf.addMessage("Workflow instance task updated successfully.");

        // return the output parameters
        HashMap<String, Object> outParams = new HashMap<>();
        outParams.put("taskId", taskId);
        return outParams;
    }
}
