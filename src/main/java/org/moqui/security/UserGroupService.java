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
package org.moqui.security;

import org.apache.commons.lang3.time.StopWatch;
import org.moqui.context.ExecutionContext;
import org.moqui.entity.*;
import org.moqui.service.ServiceFacade;
import org.moqui.util.ContextStack;
import org.moqui.util.ContextUtil;
import org.moqui.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Service to retrieve user groups.
 */
@SuppressWarnings("unused")
public class UserGroupService {

/**
 * Class logger.
 */
private final Logger logger = LoggerFactory.getLogger(getClass());

    /**
     * Finds user groups.
     *
     * @param ec Execution context
     * @return Output parameter map
     */
    public Map<String, Object> findUserGroups(ExecutionContext ec) {

        // start the stop watch
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        // shortcuts for convenience
        ContextStack cs = ec.getContext();
        EntityFacade ef = ec.getEntity();
        ServiceFacade sf = ec.getService();

        // get the parameters
        int pageIndex = (Integer) cs.getOrDefault("pageIndex", 0);
        int pageSize = (Integer) cs.getOrDefault("pageSize", 10);
        String orderByField = (String) cs.getOrDefault("orderByField", "userGroupId");
        String filter = (String) cs.getOrDefault("filter", null);

        // generate a new log ID
        String logId = ContextUtil.getLogId(ec);
        logger.debug(String.format("[%s] Finding user groups ...", logId));
        logger.debug(String.format("[%s] Param pageIndex=%s", logId, pageIndex));
        logger.debug(String.format("[%s] Param pageSize=%s", logId, pageSize));
        logger.debug(String.format("[%s] Param orderByField=%s", logId, orderByField));
        logger.debug(String.format("[%s] Param filter=%s", logId, filter));

        // prepare the conditions
        EntityConditionFactory ecf = ef.getConditionFactory();
        EntityCondition findCondition = ecf.makeCondition("groupTypeEnumId", EntityCondition.ComparisonOperator.LIKE, "HL_%");

        // add the filter
        if (StringUtil.isValidElasticsearchQuery(filter)) {
            Map<String, Object> resp = sf.sync().name("org.moqui.search.SearchServices.search#DataDocuments")
                    .parameter("indexName", "workflow")
                    .parameter("documentType", "UserGroup")
                    .parameter("queryString", filter)
                    .call();

            Set<String> idSet = new HashSet<>();
            if (resp != null && resp.containsKey("documentList")) {
                List documentList = (List) resp.get("documentList");
                for (Object documentObj : documentList) {
                    if (documentObj instanceof Map) {
                        idSet.add((String) ((Map) documentObj).get("userGroupId"));
                    }
                }
            }

            findCondition = ecf.makeCondition(
                    findCondition,
                    EntityCondition.JoinOperator.AND,
                    ecf.makeCondition("userGroupId", EntityCondition.ComparisonOperator.IN, idSet)
            );
        }

        // find user groups
        ArrayList<Map<String, Object>> userGroupList = new ArrayList<>();
        EntityList userGroups = ef.find("moqui.security.UserGroup")
                .condition(findCondition)
                .searchFormMap(cs, null, null, null, false)
                .offset(pageIndex, pageSize)
                .limit(pageSize)
                .orderBy(orderByField)
                .list();
        for (EntityValue userGroup : userGroups) {
            userGroupList.add(userGroup.getMap());
        }

        // count user groups
        long totalRows = ef.find("moqui.security.UserGroup")
                .condition(findCondition)
                .searchFormMap(cs, null, null, null, false)
                .count();

        // log the processing time
        stopWatch.stop();
        logger.debug(String.format("[%s] Found %d user groups in %d milliseconds", logId, userGroupList.size(), stopWatch.getTime()));

        // return the output parameters
        HashMap<String, Object> outParams = new HashMap<>();
        outParams.put("totalRows", totalRows);
        outParams.put("userGroupList", userGroupList);
        return outParams;
    }
}