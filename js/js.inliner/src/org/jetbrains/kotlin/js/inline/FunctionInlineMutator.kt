/*
 * Copyright 2008 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.jetbrains.kotlin.js.inline

import com.google.dart.compiler.backend.js.ast.*
import com.intellij.util.containers.ContainerUtil
import org.jetbrains.kotlin.js.inline.clean.removeDefaultInitializers
import org.jetbrains.kotlin.js.inline.context.InliningContext
import org.jetbrains.kotlin.js.inline.context.NamingContext
import org.jetbrains.kotlin.js.inline.util.*
import kotlin.platform.platformStatic

class FunctionInlineMutator private (private val call: JsInvocation, private val inliningContext: InliningContext) {
    private val invokedFunction: JsFunction
    private val isResultNeeded: Boolean
    private val namingContext: NamingContext
    private val body: JsBlock
    private var resultExpr: JsExpression? = null
    private var breakLabel: JsLabel? = null

    init {

        val functionContext = inliningContext.functionContext
        invokedFunction = functionContext.getFunctionDefinition(call)
        body = invokedFunction.getBody().deepCopy()
        isResultNeeded = isResultNeeded(call)
        namingContext = inliningContext.newNamingContext()
    }

    private fun process() {
        val arguments = getArguments()
        val parameters = getParameters()

        replaceThis()
        removeDefaultInitializers(arguments, parameters, body)
        aliasArgumentsIfNeeded(namingContext, arguments, parameters)
        renameLocalNames(namingContext, invokedFunction)
        removeStatementsAfterTopReturn()

        if (isResultNeeded && canBeExpression(body)) {
            resultExpr = asExpression(body)
            body.getStatements().clear()

            /** JsExpression can be immutable, so need to reassign  */
            resultExpr = namingContext.applyRenameTo(resultExpr!!) as JsExpression
        }
        else {
            processReturns()
            namingContext.applyRenameTo(body)
        }
    }

    private fun replaceThis() {
        if (!hasThisReference(body)) return

        var thisReplacement = getThisReplacement(call)
        if (thisReplacement == null) return

        if (thisReplacement!!.needToAlias()) {
            val thisName = namingContext.getFreshName(getThisAlias())
            namingContext.newVar(thisName, thisReplacement)
            thisReplacement = thisName.makeRef()
        }

        replaceThisReference(body, thisReplacement!!)
    }

    private fun removeStatementsAfterTopReturn() {
        val statements = body.getStatements()

        val statementsSize = statements.size()
        for (i in 0..statementsSize - 1) {
            val statement = statements.get(i)

            if (statement is JsReturn) {
                statements.subList(i + 1, statementsSize).clear()
                break
            }
        }
    }

    private fun processReturns() {
        val returnCount = collectInstances(javaClass<JsReturn>(), body).size()
        if (returnCount == 0) {
            // TODO return Unit (KT-5647)
            resultExpr = JsLiteral.UNDEFINED
        }
        else {
            doReplaceReturns(returnCount)
        }
    }

    private fun doReplaceReturns(returnCount: Int) {
        val returnOnTop = ContainerUtil.findInstance(body.getStatements(), javaClass<JsReturn>())
        val hasReturnOnTopLevel = returnOnTop != null

        if (isResultNeeded) {
            val resultName = namingContext.getFreshName(getResultLabel())
            namingContext.newVar(resultName, null)
            resultExpr = resultName.makeRef()
        }

        val needBreakLabel = !(returnCount == 1 && hasReturnOnTopLevel)
        var breakLabelRef: JsNameRef? = null

        if (needBreakLabel) {
            val breakName = namingContext.getFreshName(getBreakLabel())
            breakLabelRef = breakName.makeRef()
            breakLabel = JsLabel(breakName)
        }

        assert(resultExpr == null || resultExpr is JsNameRef)
        replaceReturns(body, resultExpr as? JsNameRef, breakLabelRef)
    }

    private fun getArguments(): List<JsExpression> {
        val arguments = call.getArguments()
        if (isCallInvocation(call)) {
            return arguments.subList(1, arguments.size())
        }

        return arguments
    }

    private fun isResultNeeded(call: JsInvocation): Boolean {
        val statementContext = inliningContext.statementContext
        val currentStatement = statementContext.getCurrentNode()
        return currentStatement !is JsExpressionStatement || call != currentStatement.getExpression()
    }

    private fun getParameters(): List<JsParameter> {
        return invokedFunction.getParameters()
    }

    private fun getResultLabel(): String {
        return getLabelPrefix() + "result"
    }

    private fun getBreakLabel(): String {
        return getLabelPrefix() + "break"
    }

    private fun getThisAlias(): String {
        return "\$this"
    }

    fun getLabelPrefix(): String {
        val ident = getSimpleIdent(call)
        val labelPrefix = ident ?: "inline$"

        if (labelPrefix.endsWith("$")) {
            return labelPrefix
        }

        return labelPrefix + "$"
    }

    companion object {

        platformStatic
        public fun getInlineableCallReplacement(call: JsInvocation, inliningContext: InliningContext): InlineableResult {
            val mutator = FunctionInlineMutator(call, inliningContext)
            mutator.process()

            var inlineableBody: JsStatement = mutator.body
            val breakLabel = mutator.breakLabel
            if (breakLabel != null) {
                breakLabel.setStatement(inlineableBody)
                inlineableBody = breakLabel
            }

            var resultExpression: JsExpression? = null
            if (mutator.isResultNeeded) {
                resultExpression = mutator.resultExpr
            }

            return InlineableResult(inlineableBody, resultExpression)
        }

        platformStatic
        private fun getThisReplacement(call: JsInvocation): JsExpression? {
            if (isCallInvocation(call)) {
                return call.getArguments().get(0)
            }

            if (hasCallerQualifier(call)) {
                return getCallerQualifier(call)
            }

            return null
        }

        private fun hasThisReference(body: JsBlock): Boolean {
            val thisRefs = collectInstances(javaClass<JsLiteral.JsThisRef>(), body)
            return !thisRefs.isEmpty()
        }

        platformStatic
        public fun canBeExpression(function: JsFunction): Boolean {
            return canBeExpression(function.getBody())
        }

        private fun canBeExpression(body: JsBlock): Boolean {
            val statements = body.getStatements()
            return statements.size() == 1 && statements.get(0) is JsReturn
        }

        private fun asExpression(body: JsBlock): JsExpression {
            assert(canBeExpression(body))

            val statements = body.getStatements()
            val returnStatement = statements.get(0) as JsReturn
            return returnStatement.getExpression()
        }
    }
}
