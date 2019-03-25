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

import org.json.JSONObject;

/**
 * Utility class that facilities dealing with JSON objects.
 */
public class JsonUtil {

    /**
     * Gets the JSON value denoted by key.
     *
     * @param json JSON object
     * @param key Field key
     * @return Field value as {@code String} or {@code null} if the key doesn't exist
     */
    public static String getStringValue(JSONObject json, String key) {
        return json.has(key) ? (json.get(key).equals(JSONObject.NULL) ? null : json.getString(key)) : null;
    }
}
