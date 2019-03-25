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

import java.sql.Timestamp;

/**
 * Utility class that offers a few additional Timestamps operations.
 */
@SuppressWarnings("unused")
public class TimestampUtil {

    /**
     * Checks if today's date falls within the specified range.
     *
     * @param fromDate The range start
	 * @param toDate The range end
     * @return {@code true} if today's date falls within the specified range
     */
	public static boolean isWithinRange(Timestamp fromDate, Timestamp toDate) {
		long now = System.currentTimeMillis();
		long from = fromDate==null ? now : fromDate.getTime();
		long to = toDate==null ? now : toDate.getTime();
		return now>=from && now<=to;
	}

	/**
	 * Gets a new timestamp with the current date.
	 *
	 * @return Current date timestamp
	 */
	public static Timestamp now() {
		return new Timestamp(System.currentTimeMillis());
	}
}
