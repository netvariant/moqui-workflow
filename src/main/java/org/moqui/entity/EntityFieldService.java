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
package org.moqui.entity;

import org.moqui.entity.util.EntityFieldType;
import org.moqui.util.ContextUtil;
import org.apache.commons.lang3.time.StopWatch;
import org.moqui.context.ExecutionContext;
import org.moqui.context.L10nFacade;
import org.moqui.context.MessageFacade;
import org.moqui.util.ContextStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Service to retrieve field comparison operators.
 */
@SuppressWarnings("unused")
public class EntityFieldService {

    /**
     * Class logger.
     */
    private final Logger logger = LoggerFactory.getLogger(getClass());

    /**
     * Finds field comparison operators.
     *
     * @param ec Execution context
     * @return Output parameter map
     */
    public Map<String, Object> findFieldComparisonOperators(ExecutionContext ec) {

        // start the stop watch
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        // shortcuts for convenience
        ContextStack cs = ec.getContext();
        MessageFacade mf = ec.getMessage();
        L10nFacade lf = ec.getL10n();
        EntityFacade ef = ec.getEntity();

        // get the parameters
        String fieldId = (String) cs.getOrDefault("fieldId", null);

        // generate a new log ID
        String logId = ContextUtil.getLogId(ec);
        logger.debug(String.format("[%s] Finding field comparison operators ...", logId));
        logger.debug(String.format("[%s] Param fieldId=%s", logId, fieldId));

        // validate the field
        EntityValue field = ef.find("moqui.entity.EntityField")
                .condition("fieldId", fieldId)
                .one();
        if(field==null) {
            stopWatch.stop();
            mf.addError(lf.localize("ENTITY_FIELD_NOT_FOUND"));
            logger.error(String.format("[%s] Field with ID %s was not found", logId, fieldId));
            return new HashMap<>();
        }

        // get field type
        EntityFieldType fieldType;
        try {
            fieldType = EntityFieldType.valueOf(field.getString("fieldTypeEnumId"));
        } catch (IllegalArgumentException e) {
            fieldType = EntityFieldType.ENTITY_FLD_TEXT;
        }

        // get enum type
        String enumTypeId;
        switch(fieldType) {
            case ENTITY_FLD_BOOLEAN:
                enumTypeId = "BooleanComparisonOperator";
                break;
            case ENTITY_FLD_DATE:
                enumTypeId = "DateComparisonOperator";
                break;
            case ENTITY_FLD_TEXT:
                enumTypeId = "TextComparisonOperator";
                break;
            case ENTITY_FLD_NUMBER:
                enumTypeId = "NumberComparisonOperator";
                break;
            default:
                enumTypeId = "_NA_";
        }

        // find
        ArrayList<Map<String, Object>> operatorList = new ArrayList<>();
        EntityList operators = ef.find("moqui.basic.Enumeration")
                .condition("enumTypeId", enumTypeId)
                .list();
        for (EntityValue operator : operators) {
            operatorList.add(operator.getMap());
        }

        // log the processing time
        stopWatch.stop();
        logger.debug(String.format("[%s] Found %d comparison operators in %d milliseconds", logId, operatorList.size(), stopWatch.getTime()));

        // return the output parameters
        HashMap<String, Object> outParams = new HashMap<>();
        outParams.put("operatorList", operatorList);
        return outParams;
    }
}
