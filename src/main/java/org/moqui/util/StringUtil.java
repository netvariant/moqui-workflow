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

import org.apache.lucene.queryparser.flexible.standard.parser.StandardSyntaxParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Utility class that offers some additional string operations.
 */
public class StringUtil {

    /**
     * Shuffles the characters of a given string.
     *
     * @param input Input string
     * @return Randomly shuffled string
     */
    public static String shuffle(String input) {
        List<Character> chars = new ArrayList<>();
        for(char c:input.toCharArray()){
            chars.add(c);
        }
        Collections.shuffle(chars);
        StringBuilder sb = new StringBuilder();
        for(Character ch: chars) {
            sb.append(ch);
        }
        return sb.toString();
    }

    /**
     * Checks if the specified query is valid in Elasticsearch.
     *
     * @param query Query string
     * @return {@code true} if the query is properly formatted and {@code false} otherwise
     */
    public static boolean isValidElasticsearchQuery(String query) {
        if(query==null) {
            return false;
        }
        try {
            new StandardSyntaxParser().parse(query, null);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
