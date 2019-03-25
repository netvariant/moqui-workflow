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
package org.moqui.workflow.condition;

import org.moqui.context.ExecutionContext;
import org.moqui.entity.EntityValue;

/**
 * Interface that defined required workflow condition methods.
 */
public interface WorkflowCondition {

    /**
     * Evaluates the condition.
     *
     * @param ec Execution context
     * @param instance Workflow instance
     * @return Evaluation result
     * @throws Exception if an error occurs while evaluating condition
     */
    boolean evaluate(ExecutionContext ec, EntityValue instance) throws Exception;
}
