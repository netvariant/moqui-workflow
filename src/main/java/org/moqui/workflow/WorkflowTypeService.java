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

import org.apache.commons.lang3.time.StopWatch;
import org.moqui.context.ExecutionContext;
import org.moqui.context.MessageFacade;
import org.moqui.context.UserFacade;
import org.moqui.entity.EntityFacade;
import org.moqui.entity.EntityList;
import org.moqui.entity.EntityValue;
import org.moqui.service.ServiceFacade;
import org.moqui.util.ContextStack;
import org.moqui.util.ContextUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Service to retrieve workflow types.
 */
@SuppressWarnings("unused")
public class WorkflowTypeService {

    /**
     * Class logger.
     */
    private final Logger logger = LoggerFactory.getLogger(getClass());

    /**
     * Finds workflows.
     *
     * @param ec Execution context
     * @return Output parameter map
     */
    public Map<String, Object> findWorkflowTypes(ExecutionContext ec) {

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
        String orderByField = (String) cs.getOrDefault("orderByField", "typeId");
        String filter = (String) cs.getOrDefault("filter", null);

        // generate a new log ID
        String logId = ContextUtil.getLogId(ec);
        logger.debug(String.format("[%s] Finding workflow types ...", logId));
        logger.debug(String.format("[%s] Param pageIndex=%s", logId, pageIndex));
        logger.debug(String.format("[%s] Param pageSize=%s", logId, pageSize));
        logger.debug(String.format("[%s] Param orderByField=%s", logId, orderByField));

        // find
        ArrayList<Map<String, Object>> workflowTypeList = new ArrayList<>();
        EntityList workflowTypes = ef.find("moqui.workflow.WorkflowType")
                .offset(pageIndex, pageSize)
                .limit(pageSize)
                .orderBy(orderByField)
                .searchFormMap(cs, null, null, null, false)
                .list();
        for (EntityValue workflowType : workflowTypes) {
            workflowTypeList.add(workflowType.getMap());
        }

        // count
        long totalRows = ef.find("moqui.workflow.WorkflowType")
                .searchFormMap(cs, null, null, null, false)
                .count();

        // log the processing time
        stopWatch.stop();
        logger.debug(String.format("[%s] Found %d workflow types in %d milliseconds", logId, workflowTypeList.size(), stopWatch.getTime()));

        // return the output parameters
        HashMap<String, Object> outParams = new HashMap<>();
        outParams.put("totalRows", totalRows);
        outParams.put("workflowTypeList", workflowTypeList);
        return outParams;
    }
}
