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
package org.moqui.basic;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.StopWatch;
import org.moqui.context.ExecutionContext;
import org.moqui.context.MessageFacade;
import org.moqui.context.UserFacade;
import org.moqui.entity.*;
import org.moqui.service.ServiceFacade;
import org.moqui.util.ContextStack;
import org.moqui.util.ContextUtil;
import org.moqui.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Service to retrieve status flows.
 */
@SuppressWarnings("unused")
public class StatusFlowService {

    /**
     * Class logger.
     */
    private final Logger logger = LoggerFactory.getLogger(getClass());

    /**
     * Finds status flows.
     *
     * @param ec Execution context
     * @return Output parameter map
     */
    public Map<String, Object> findStatusFlows(ExecutionContext ec) {

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
        String orderByField = (String) cs.getOrDefault("orderByField", "statusFlowId");
        String filter = (String) cs.getOrDefault("filter", null);

        // generate a new log ID
        String logId = ContextUtil.getLogId(ec);
        logger.debug(String.format("[%s] Finding status flows ...", logId));
        logger.debug(String.format("[%s] Param pageIndex=%s", logId, pageIndex));
        logger.debug(String.format("[%s] Param pageSize=%s", logId, pageSize));
        logger.debug(String.format("[%s] Param orderByField=%s", logId, orderByField));
        logger.debug(String.format("[%s] Param filter=%s", logId, filter));

        // prepare the conditions
        EntityConditionFactory ecf = ef.getConditionFactory();
        EntityCondition findCondition = ecf.getTrueCondition();

        // add the filter
        if(StringUtil.isValidElasticsearchQuery(filter)) {
            Map<String, Object> resp = sf.sync().name("org.moqui.search.SearchServices.search#DataDocuments")
                    .parameter("indexName", "workflow")
                    .parameter("documentType", "MoquiStatusFlow")
                    .parameter("queryString", filter)
                    .call();

            Set<String> idSet = new HashSet<>();
            if(resp!=null && resp.containsKey("documentList")) {
                List documentList = (List) resp.get("documentList");
                for (Object documentObj : documentList) {
                    if(documentObj instanceof Map) {
                        idSet.add((String) ((Map) documentObj).get("statusFlowId"));
                    }
                }
            }

            findCondition = ecf.makeCondition(
                    findCondition,
                    EntityCondition.JoinOperator.AND,
                    ecf.makeCondition("statusFlowId", EntityCondition.ComparisonOperator.IN, idSet)
            );
        }

        // find
        String userId = uf.getUserId();
        ArrayList<Map<String, Object>> statusFlowList = new ArrayList<>();
        EntityList statusFlows = ef.find("moqui.basic.StatusFlow")
                .condition(findCondition)
                .searchFormMap(cs, null, null, null, false)
                .offset(pageIndex, pageSize)
                .limit(pageSize)
                .orderBy(orderByField)
                .list();
        for (EntityValue statusFlow : statusFlows) {
            statusFlowList.add(statusFlow.getMap());
        }

        // count
        long totalRows = ef.find("moqui.basic.StatusFlow")
                .condition(findCondition)
                .searchFormMap(cs, null, null, null, false)
                .count();

        // log the processing time
        stopWatch.stop();
        logger.debug(String.format("[%s] Found %d flows in %d milliseconds", logId, statusFlowList.size(), stopWatch.getTime()));

        // return the output parameters
        HashMap<String, Object> outParams = new HashMap<>();
        outParams.put("totalRows", totalRows);
        outParams.put("statusFlowList", statusFlowList);
        return outParams;
    }

    /**
     * Finds status flow items.
     *
     * @param ec Execution context
     * @return Output parameter map
     */
    public Map<String, Object> findStatusFlowItems(ExecutionContext ec) {

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
        String statusFlowId = (String) cs.getOrDefault("statusFlowId", null);
        int pageIndex = (Integer) cs.getOrDefault("pageIndex", 0);
        int pageSize = (Integer) cs.getOrDefault("pageSize", 10);
        String orderByField = (String) cs.getOrDefault("orderByField", "statusId");

        // generate a new log ID
        String logId = ContextUtil.getLogId(ec);
        logger.debug(String.format("[%s] Finding status flow items ...", logId));
        logger.debug(String.format("[%s] Param statusFlowId=%s", logId, statusFlowId));
        logger.debug(String.format("[%s] Param pageIndex=%s", logId, pageIndex));
        logger.debug(String.format("[%s] Param pageSize=%s", logId, pageSize));
        logger.debug(String.format("[%s] Param orderByField=%s", logId, orderByField));

        // validate the parameters
        if (StringUtils.isBlank(statusFlowId)) {
            stopWatch.stop();
            mf.addError("Status flow ID is required.");
            logger.error(String.format("[%s] Status flow ID is blank", logId));
            return new HashMap<>();
        }

        // get the status flow
        EntityValue statusFlow = ef.find("moqui.basic.StatusFlow")
                .condition("statusFlowId", statusFlowId)
                .one();

        // validate the status flow
        if (statusFlow==null) {
            stopWatch.stop();
            mf.addError("Status flow not found.");
            logger.error(String.format("[%s] Status flow with ID %s was not found", logId, statusFlowId));
            return new HashMap<>();
        }

        // prepare the conditions
        EntityConditionFactory ecf = ef.getConditionFactory();
        EntityCondition findCondition = ecf.makeCondition("statusFlowId", EntityCondition.ComparisonOperator.EQUALS, statusFlowId);

        // find
        String userId = uf.getUserId();
        ArrayList<Map<String, Object>> statusItemList = new ArrayList<>();
        EntityList statusItems = ef.find("moqui.basic.StatusFlowItemDetail")
                .searchFormMap(cs, null, null, null, false)
                .offset(pageIndex, pageSize)
                .limit(pageSize)
                .orderBy(orderByField)
                .list();
        for (EntityValue statusItem : statusItems) {
            statusItemList.add(statusItem.getMap());
        }

        // count
        long totalRows = ef.find("moqui.basic.StatusFlowItemDetail")
                .searchFormMap(cs, null, null, null, false)
                .count();

        // log the processing time
        stopWatch.stop();
        logger.debug(String.format("[%s] Found %d items in %d milliseconds", logId, statusItemList.size(), stopWatch.getTime()));

        // return the output parameters
        HashMap<String, Object> outParams = new HashMap<>();
        outParams.put("totalRows", totalRows);
        outParams.put("statusItemList", statusItemList);
        return outParams;
    }

    /**
     * Finds status flow transitions.
     *
     * @param ec Execution context
     * @return Output parameter map
     */
    public Map<String, Object> findStatusFlowTransitions(ExecutionContext ec) {

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
        String statusFlowId = (String) cs.getOrDefault("statusFlowId", null);
        int pageIndex = (Integer) cs.getOrDefault("pageIndex", 0);
        int pageSize = (Integer) cs.getOrDefault("pageSize", 10);
        String orderByField = (String) cs.getOrDefault("orderByField", "transitionSequence");

        // generate a new log ID
        String logId = ContextUtil.getLogId(ec);
        logger.debug(String.format("[%s] Finding status flow transitions ...", logId));
        logger.debug(String.format("[%s] Param statusFlowId=%s", logId, statusFlowId));
        logger.debug(String.format("[%s] Param pageIndex=%s", logId, pageIndex));
        logger.debug(String.format("[%s] Param pageSize=%s", logId, pageSize));
        logger.debug(String.format("[%s] Param orderByField=%s", logId, orderByField));

        // validate the parameters
        if (StringUtils.isBlank(statusFlowId)) {
            stopWatch.stop();
            mf.addError("Status flow ID is required.");
            logger.error(String.format("[%s] Status flow ID is blank", logId));
            return new HashMap<>();
        }

        // get the status flow
        EntityValue statusFlow = ef.find("moqui.basic.StatusFlow")
                .condition("statusFlowId", statusFlowId)
                .one();

        // validate the status flow
        if (statusFlow==null) {
            stopWatch.stop();
            mf.addError("Status flow not found.");
            logger.error(String.format("[%s] Status flow with ID %s was not found", logId, statusFlowId));
            return new HashMap<>();
        }

        // prepare the conditions
        EntityConditionFactory ecf = ef.getConditionFactory();
        EntityCondition findCondition = ecf.makeCondition("statusFlowId", EntityCondition.ComparisonOperator.EQUALS, statusFlowId);

        // find
        String userId = uf.getUserId();
        ArrayList<Map<String, Object>> transitionList = new ArrayList<>();
        EntityList transitions = ef.find("moqui.basic.StatusFlowTransition")
                .searchFormMap(cs, null, null, null, false)
                .offset(pageIndex, pageSize)
                .limit(pageSize)
                .orderBy(orderByField)
                .list();
        for (EntityValue transition : transitions) {
            transitionList.add(transition.getMap());
        }

        // count
        long totalRows = ef.find("moqui.basic.StatusFlowTransition")
                .searchFormMap(cs, null, null, null, false)
                .count();

        // log the processing time
        stopWatch.stop();
        logger.debug(String.format("[%s] Found %d transition in %d milliseconds", logId, transitionList.size(), stopWatch.getTime()));

        // return the output parameters
        HashMap<String, Object> outParams = new HashMap<>();
        outParams.put("totalRows", totalRows);
        outParams.put("transitionList", transitionList);
        return outParams;
    }
}
