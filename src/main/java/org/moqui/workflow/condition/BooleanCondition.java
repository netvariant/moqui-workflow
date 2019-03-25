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

import org.moqui.util.BooleanComparisonOperator;
import org.moqui.context.ExecutionContext;
import org.moqui.entity.EntityValue;

/**
 * Boolean condition.
 */
public class BooleanCondition implements WorkflowCondition {

    /**
     * Check value.
     */
    private boolean value;
    /**
     * Comparison operator.
     */
    private BooleanComparisonOperator operator;

    /**
     * Creates a new condition.
     *
     * @param value Field value
     * @param operator Comparison operator
     */
    public BooleanCondition(Boolean value, BooleanComparisonOperator operator) {
        this.value = value;
        this.operator = operator;
    }

    @Override
    public boolean evaluate(ExecutionContext ec, EntityValue instance) throws Exception {
        switch(operator) {
            case BOOL_TRUE:
                return value;
            case BOOL_FALSE:
                return !value;
            default:
                return false;
        }
    }

    @Override
    public String toString() {
        return String.format("%s %s", value, operator);
    }
}
