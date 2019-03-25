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

import org.moqui.util.TextComparisonOperator;
import org.apache.commons.lang3.StringUtils;
import org.moqui.context.ExecutionContext;
import org.moqui.entity.EntityValue;

/**
 * Text condition.
 */
public class TextCondition implements WorkflowCondition {

    /**
     * Left operand.
     */
    private String leftOperand;
    /**
     * Right operand.
     */
    private String rightOperand;
    /**
     * Comparison operator.
     */
    private TextComparisonOperator operator;

    /**
     * Creates a new condition.
     *
     * @param leftOperand Left operand
     * @param operator Comparison operator
     * @param rightOperand Right operand
     */
    public TextCondition(String leftOperand, TextComparisonOperator operator, String rightOperand) {
        this.leftOperand = leftOperand;
        this.operator = operator;
        this.rightOperand = rightOperand;
    }

    @Override
    public boolean evaluate(ExecutionContext ec, EntityValue instance) throws Exception {
        switch(operator) {
            case TXT_STARTS_WITH:
                return StringUtils.startsWith(leftOperand, rightOperand);
            case TXT_ENDS_WITH:
                return StringUtils.endsWith(leftOperand, rightOperand);
            case TXT_CONTAINS:
                return StringUtils.contains(leftOperand, rightOperand);
            case TXT_NOT_CONTAINS:
                return !StringUtils.contains(leftOperand, rightOperand);
            case TXT_EQUALS:
                return StringUtils.equals(leftOperand, rightOperand);
            case TXT_NOT_EQUALS:
                return !StringUtils.equals(leftOperand, rightOperand);
            case TXT_EMPTY:
                return StringUtils.isEmpty(leftOperand);
            case TXT_NOT_EMPTY:
                return StringUtils.isNotEmpty(leftOperand);
            default:
                return false;
        }
    }

    @Override
    public String toString() {
        return String.format("%s %s %s", leftOperand, operator, rightOperand);
    }
}
