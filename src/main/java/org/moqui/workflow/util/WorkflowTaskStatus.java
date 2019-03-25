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
 * Known workflow task statuses.
 */
public enum WorkflowTaskStatus {
    WF_TASK_STAT_PEND,
    WF_TASK_STAT_PROGRESS,
    WF_TASK_STAT_DONE,
    WF_TASK_STAT_APPROVE,
    WF_TASK_STAT_REJECT,
    WF_TASK_STAT_OBSOLETE
}
