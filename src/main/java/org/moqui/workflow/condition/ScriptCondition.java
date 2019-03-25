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

import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.moqui.context.ExecutionContext;
import org.moqui.entity.EntityFacade;
import org.moqui.entity.EntityList;
import org.moqui.entity.EntityValue;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;

/**
 * Script condition.
 */
public class ScriptCondition implements WorkflowCondition {

    /**
     * Script code.
     */
    private String script;

    /**
     * Creates a new condition.
     *
     * @param script Script code
     */
    public ScriptCondition(String script) {
        this.script = script;
    }

    @Override
    public boolean evaluate(ExecutionContext ec, EntityValue instance) throws Exception {

        // shortcuts for convenience
        EntityFacade ef = ec.getEntity();

        // init script engine
        ScriptEngineManager manager = new ScriptEngineManager();
        ScriptEngine engine = manager.getEngineByName("JavaScript");

        // replace properties
        script = script.replaceAll(" ", "");
        EntityList variables = ef.find("moqui.workflow.WorkflowInstanceVariableDetail")
                .condition("instanceId", instance.get("instanceId"))
                .list();
        for (EntityValue variable : variables) {
            String variableName = variable.getString("variableName");
            Object definedValue = variable.get("definedValue");
            String tempVariableName = RandomStringUtils.randomAlphabetic(4);
            script = StringUtils.replace(script, String.format("{{%s}}", variableName), tempVariableName);
            engine.put(tempVariableName, definedValue);
        }

        // evaluate script
        Object result = engine.eval(script);

        // process result
        if (result instanceof Boolean) {
            return ((Boolean) result);
        } else if (result instanceof Number) {
            return ((Number) result).longValue() > 0;
        } else {
            return false;
        }
    }

    @Override
    public String toString() {
        return script;
    }
}
