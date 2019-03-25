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

import org.apache.commons.lang3.StringUtils;

/**
 * Known workflow activity types.
 */
public enum WorkflowActivityType {
    WF_ACTIVITY_ENTER,
    WF_ACTIVITY_EXIT,
    WF_ACTIVITY_CONDITION,
    WF_ACTIVITY_USER,
    WF_ACTIVITY_ADJUST,
    WF_ACTIVITY_SERVICE,
    WF_ACTIVITY_NOTIFY;

    /**
     * Gets the activity type from the specified node type.
     *
     * @param nodeType Node type
     * @return Activity type
     */
    public static WorkflowActivityType fromNodeType(String nodeType) {
        if(StringUtils.equals(nodeType, "EnterActivity")) {
            return WF_ACTIVITY_ENTER;
        } else if(StringUtils.equals(nodeType, "ExitActivity")) {
            return WF_ACTIVITY_EXIT;
        } else if(StringUtils.equals(nodeType, "ConditionActivity")) {
            return WF_ACTIVITY_CONDITION;
        } else if(StringUtils.equals(nodeType, "UserActivity")) {
            return WF_ACTIVITY_USER;
        } else if(StringUtils.equals(nodeType, "AdjustmentActivity")) {
            return WF_ACTIVITY_ADJUST;
        } else if(StringUtils.equals(nodeType, "ServiceActivity")) {
            return WF_ACTIVITY_SERVICE;
        } else if(StringUtils.equals(nodeType, "NotificationActivity")) {
            return WF_ACTIVITY_NOTIFY;
        } else {
            return null;
        }
    }
}
