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
 * Service to retrieve status items.
 */
@SuppressWarnings("unused")
public class StatusItemService {

    /**
     * Class logger.
     */
    private final Logger logger = LoggerFactory.getLogger(getClass());

    /**
     * Finds status items.
     *
     * @param ec Execution context
     * @return Output parameter map
     */
    public Map<String, Object> findStatusItems(ExecutionContext ec) {

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
        String orderByField = (String) cs.getOrDefault("orderByField", "statusId");
        String filter = (String) cs.getOrDefault("filter", null);

        // generate a new log ID
        String logId = ContextUtil.getLogId(ec);
        logger.debug(String.format("[%s] Finding status items ...", logId));
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
                    .parameter("documentType", "MoquiStatusItem")
                    .parameter("queryString", filter)
                    .call();

            Set<String> idSet = new HashSet<>();
            if(resp!=null && resp.containsKey("documentList")) {
                List documentList = (List) resp.get("documentList");
                for (Object documentObj : documentList) {
                    if(documentObj instanceof Map) {
                        idSet.add((String) ((Map) documentObj).get("statusId"));
                    }
                }
            }

            findCondition = ecf.makeCondition(
                    findCondition,
                    EntityCondition.JoinOperator.AND,
                    ecf.makeCondition("statusId", EntityCondition.ComparisonOperator.IN, idSet)
            );
        }

        // find
        String userId = uf.getUserId();
        ArrayList<Map<String, Object>> statusItemList = new ArrayList<>();
        EntityList statusItems = ef.find("moqui.basic.StatusItem")
                .condition(findCondition)
                .searchFormMap(cs, null, null, null, false)
                .offset(pageIndex, pageSize)
                .limit(pageSize)
                .orderBy(orderByField)
                .list();
        for (EntityValue statusItem : statusItems) {
            statusItemList.add(statusItem.getMap());
        }

        // count
        long totalRows = ef.find("moqui.basic.StatusItem")
                .condition(findCondition)
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
}
