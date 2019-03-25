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
package org.moqui.util;

import org.apache.commons.lang3.RandomStringUtils;
import org.moqui.context.ExecutionContext;
import org.moqui.util.ContextStack;

import java.util.Map;

/**
 * Utility class that facilitates dealing with the Execution Context.
 */
public class ContextUtil {

    /**
     * Key used to store log ID in context.
     */
    public static final String CONTEXT_LOG_ID = "LOG_ID";

    /**
     * Gets the context log ID. If the context log ID is not defined
     * then a new value is generated and returned.
     *
     * @param ec Execution context
     * @return Context log ID
     */
    public static String getLogId(ExecutionContext ec) {
        ContextStack cs = ec.getContext();
        Map<String, Object> shared = cs.getSharedMap();
        if(!shared.containsKey(CONTEXT_LOG_ID)) {
            String logId = RandomStringUtils.randomAlphanumeric(6);
            shared.put(CONTEXT_LOG_ID, logId);
        }
        return (String) shared.get(CONTEXT_LOG_ID);
    }
}
