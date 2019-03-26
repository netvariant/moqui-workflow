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
import org.moqui.context.*;
import org.moqui.entity.*;
import org.moqui.service.ServiceFacade;
import org.moqui.util.ContextStack;
import org.moqui.util.ContextUtil;
import org.moqui.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Service to login and retrieve users.
 */
@SuppressWarnings("unused")
public class UserService {

    /**
     * Class logger.
     */
    private final Logger logger = LoggerFactory.getLogger(getClass());

    /**
     * Finds users.
     *
     * @param ec Execution context
     * @return Output parameter map
     */
    public Map<String, Object> findUsers(ExecutionContext ec) {

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
        String orderByField = (String) cs.getOrDefault("orderByField", "userId");
        String filter = (String) cs.getOrDefault("filter", null);

        // generate a new log ID
        String logId = ContextUtil.getLogId(ec);
        logger.debug(String.format("[%s] Finding users ...", logId));
        logger.debug(String.format("[%s] Param pageIndex=%s", logId, pageIndex));
        logger.debug(String.format("[%s] Param pageSize=%s", logId, pageSize));
        logger.debug(String.format("[%s] Param orderByField=%s", logId, orderByField));
        logger.debug(String.format("[%s] Param filter=%s", logId, filter));

        // prepare the conditions
        EntityConditionFactory ecf = ef.getConditionFactory();
        EntityCondition findCondition = ecf.makeCondition("disabled", EntityCondition.ComparisonOperator.EQUALS, "N");

        // add the filter
        if (StringUtil.isValidElasticsearchQuery(filter)) {
            Map<String, Object> resp = sf.sync().name("org.moqui.search.SearchServices.search#DataDocuments")
                    .parameter("indexName", "workflow")
                    .parameter("documentType", "MoquiUser")
                    .parameter("queryString", filter)
                    .call();

            Set<String> idSet = new HashSet<>();
            if (resp != null && resp.containsKey("documentList")) {
                List documentList = (List) resp.get("documentList");
                for (Object documentObj : documentList) {
                    if (documentObj instanceof Map) {
                        idSet.add((String) ((Map) documentObj).get("userId"));
                    }
                }
            }

            findCondition = ecf.makeCondition(
                    findCondition,
                    EntityCondition.JoinOperator.AND,
                    ecf.makeCondition("userId", EntityCondition.ComparisonOperator.IN, idSet)
            );
        }

        // find users
        ArrayList<Map<String, Object>> userList = new ArrayList<>();
        EntityList users = ef.find("moqui.security.UserAccount")
                .condition(findCondition)
                .searchFormMap(cs, null, null, null, false)
                .offset(pageIndex, pageSize)
                .limit(pageSize)
                .orderBy(orderByField)
                .selectFields(Arrays.asList("userId", "username", "userFullName", "emailAddress", "externalUserId", "creationDate"))
                .list();
        for (EntityValue user : users) {
            userList.add(user.getMap());
        }

        // count users
        long totalRows = ef.find("moqui.security.UserAccount")
                .condition(findCondition)
                .searchFormMap(cs, null, null, null, false)
                .count();

        // log the processing time
        stopWatch.stop();
        logger.debug(String.format("[%s] Found %d users in %d milliseconds", logId, userList.size(), stopWatch.getTime()));

        // return the output parameters
        HashMap<String, Object> outParams = new HashMap<>();
        outParams.put("totalRows", totalRows);
        outParams.put("userList", userList);
        return outParams;
    }

    /**
     * Logs in a user.
     *
     * @param ec Execution context
     * @return Output parameter map
     */
    public Map<String, Object> loginUser(ExecutionContext ec) {

        // start the stop watch
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        // shortcuts for convenience
        ContextStack cs = ec.getContext();
        L10nFacade lf = ec.getL10n();
        MessageFacade mf = ec.getMessage();
        EntityFacade ef = ec.getEntity();
        UserFacade uf = ec.getUser();
        WebFacade wf = ec.getWeb();

        // generate a new log ID
        String logId = ContextUtil.getLogId(ec);
        logger.debug(String.format("[%s] Logging in user ...", logId));

        // verify the user account
        String userId = uf.getUserId();
        EntityValue userAccount = uf.getUserAccount();
        if (userAccount == null) {
            mf.addError(lf.localize("USER_INVALID_CREDENTIALS"));
            logger.error(String.format("[%s] Invalid username or password", logId));
            return new HashMap<>();
        }

        // log the processing time
        stopWatch.stop();
        logger.debug(String.format("[%s] User %s logged in in %d milliseconds", logId, userId, stopWatch.getTime()));

        // return the output parameters
        HashMap<String, Object> outParams = new HashMap<>();
        outParams.put("apiKey", uf.getLoginKey());
        outParams.put("sessionToken", wf == null ? null : wf.getSessionToken());
        outParams.put("userId", uf.getUserId());
        outParams.put("userFullName", userAccount.getString("userFullName"));
        return outParams;
    }

    /**
     * Logs out the session user.
     *
     * @param ec Execution context
     */
    public Map<String, Object> logoutUser(ExecutionContext ec) {

        // start the stop watch
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        // shortcuts for convenience
        ContextStack cs = ec.getContext();
        MessageFacade mf = ec.getMessage();
        EntityFacade ef = ec.getEntity();
        UserFacade uf = ec.getUser();
        WebFacade wf = ec.getWeb();

        // generate a new log ID
        String logId = ContextUtil.getLogId(ec);
        logger.debug(String.format("[%s] Logging out user ...", logId));

        // logout user
        String userId = uf.getUserId();
        uf.logoutUser();

        // log the processing time
        stopWatch.stop();
        logger.debug(String.format("[%s] User %s logged out in %d milliseconds", logId, userId, stopWatch.getTime()));

        // return the output parameters
        HashMap<String, Object> outParams = new HashMap<>();
        outParams.put("userId", userId);
        return outParams;
    }
}
