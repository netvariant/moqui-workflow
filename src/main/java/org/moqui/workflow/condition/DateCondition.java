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

import org.moqui.util.DateComparisonOperator;
import org.apache.commons.lang3.time.DateUtils;
import org.moqui.context.ExecutionContext;
import org.moqui.entity.EntityValue;

import java.util.Date;

/**
 * Date condition.
 */
public class DateCondition implements WorkflowCondition {

    /**
     * Left operand.
     */
    private Date leftOperand;
    /**
     * Right operand.
     */
    private Date rightOperand;
    /**
     * Comparison operator.
     */
    private DateComparisonOperator operator;

    /**
     * Creates a new condition.
     *
     * @param leftOperand Left operand
     * @param operator Comparison operator
     * @param rightOperand Right operand
     */
    public DateCondition(Date leftOperand, DateComparisonOperator operator, Date rightOperand) {
        this.leftOperand = leftOperand;
        this.operator = operator;
        this.rightOperand = rightOperand;
    }

    @Override
    public boolean evaluate(ExecutionContext ec, EntityValue instance) throws Exception {
        switch(operator) {
            case DATE_BEFORE:
                return leftOperand.before(rightOperand);
            case DATE_AFTER:
                return leftOperand.after(rightOperand);
            case DATE_EQUALS:
                return DateUtils.isSameDay(leftOperand, rightOperand);
            case DATE_NOT_EQUALS:
                return !DateUtils.isSameDay(leftOperand, rightOperand);
            default:
                return false;
        }
    }

    @Override
    public String toString() {
        return String.format("%s %s %s", leftOperand, operator, rightOperand);
    }
}
