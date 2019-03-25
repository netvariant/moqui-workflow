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
package org.moqui.workflow.util;

import org.moqui.context.ExecutionContext;
import org.moqui.util.ServerUtil;

/**
 * Utility class that offers common workflow functions.
 */
public class WorkflowUtil {

    /**
     * Creates a new workflow event.
     *
     * @param ec Execution context
     * @param instanceId Workflow instance ID
     * @param event Workflow event type
     * @param description Event description
     * @param wasError Error indicator
     */
    public static void createWorkflowEvent(ExecutionContext ec, String instanceId, WorkflowEventType event, String description, boolean wasError) {
        ec.getService().sync().name("create#moqui.workflow.WorkflowInstanceEvent")
                .parameter("instanceId", instanceId)
                .parameter("eventTypeEnumId", event.name())
                .parameter("sourceName", ServerUtil.getServerName())
                .parameter("description", description)
                .parameter("wasError", wasError ? "Y" : "N")
                .call();
    }
}
