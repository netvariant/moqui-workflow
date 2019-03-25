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

import org.moqui.util.NumberComparisonOperator;
import org.moqui.context.ExecutionContext;
import org.moqui.entity.EntityValue;

/**
 * Number condition.
 */
public class NumberCondition implements WorkflowCondition {

    /**
     * Left operand.
     */
    private long leftOperand;
    /**
     * Right operand.
     */
    private long rightOperand;
    /**
     * Comparison operator.
     */
    private NumberComparisonOperator operator;

    /**
     * Creates a new condition.
     *
     * @param leftOperand Left operand
     * @param operator Comparison operator
     * @param rightOperand Right operand
     */
    public NumberCondition(long leftOperand, NumberComparisonOperator operator, long rightOperand) {
        this.leftOperand = leftOperand;
        this.operator = operator;
        this.rightOperand = rightOperand;
    }

    @Override
    public boolean evaluate(ExecutionContext ec, EntityValue instance) throws Exception {
        switch(operator) {
            case NUM_LESS_THAN:
                return leftOperand < rightOperand;
            case NUM_LESS_THAN_EQUALS:
                return leftOperand <= rightOperand;
            case NUM_GREATER_THAN:
                return leftOperand > rightOperand;
            case NUM_GREATER_THAN_EQUALS:
                return leftOperand >= rightOperand;
            case NUM_EQUALS:
                return leftOperand == rightOperand;
            case NUM_NOT_EQUALS:
                return leftOperand != rightOperand;
            default:
                return false;
        }
    }

    @Override
    public String toString() {
        return String.format("%s %s %s", leftOperand, operator, rightOperand);
    }
}
