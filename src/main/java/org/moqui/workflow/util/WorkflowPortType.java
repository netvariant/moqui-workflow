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

/**
 * Known workflow port types.
 */
public enum WorkflowPortType {
    WF_PORT_INPUT,
    WF_PORT_SUCCESS,
    WF_PORT_FAILURE,
    WF_PORT_TIMEOUT;

    /**
     * Gets the description of the specified port type.
     *
     * @param portType Port type
     * @return Port description
     */
    public static String portTypeDescription(WorkflowPortType portType) {
        switch (portType) {
            case WF_PORT_FAILURE:
                return "Failure";
            case WF_PORT_INPUT:
                return "Input";
            case WF_PORT_SUCCESS:
                return "Success";
            case WF_PORT_TIMEOUT:
                return "Timeout";
            default:
                return "Unknown";
        }
    }
}
