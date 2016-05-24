/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.cfg

import org.jetbrains.kotlin.cfg.pseudocode.PseudocodeUtil
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.cfg.pseudocode.instructions.eval.MagicInstruction
import org.jetbrains.kotlin.cfg.pseudocode.instructions.eval.MagicKind
import org.jetbrains.kotlin.cfg.pseudocode.instructions.eval.ReadValueInstruction
import org.jetbrains.kotlin.cfg.pseudocodeTraverser.TraversalOrder
import org.jetbrains.kotlin.cfg.pseudocodeTraverser.traverse
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.descriptors.isFinalOrEnum
import org.jetbrains.kotlin.descriptors.isOverridable
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.BindingTrace
import org.jetbrains.kotlin.types.expressions.OperatorConventions

sealed class LeakingThisDescriptor {
    class PropertyIsNull(val property: PropertyDescriptor) : LeakingThisDescriptor()

    class NonFinalClass(val klass: ClassDescriptor): LeakingThisDescriptor()

    class NonFinalProperty(val property: PropertyDescriptor): LeakingThisDescriptor()
}

class ConstructorConsistencyChecker private constructor(declaration: KtDeclaration, private val trace: BindingTrace) {

    private val classOrObject = declaration as? KtClassOrObject ?: (declaration as KtConstructor<*>).getContainingClassOrObject()

    private val classDescriptor = trace.get(BindingContext.CLASS, classOrObject)

    private val finalClass = classDescriptor?.isFinalOrEnum ?: true

    private val pseudocode = PseudocodeUtil.generatePseudocode(declaration, trace.bindingContext)

    private val variablesData = PseudocodeVariablesData(pseudocode, trace.bindingContext)

    private fun insideLValue(reference: KtReferenceExpression): Boolean {
        val binary = reference.getStrictParentOfType<KtBinaryExpression>() ?: return false
        if (binary.operationToken in KtTokens.ALL_ASSIGNMENTS) {
            val binaryLeft = binary.left
            var current: PsiElement = reference
            while (current !== binaryLeft && current !== binary) {
                current = current.parent ?: return false
            }
            return current === binaryLeft
        }
        return false
    }

    private fun checkReferenceSafety(reference: KtReferenceExpression): Boolean {
        val descriptor = trace.get(BindingContext.REFERENCE_TARGET, reference)
        if (descriptor is PropertyDescriptor) {
            if (!finalClass && descriptor.isOverridable) {
                trace.record(BindingContext.LEAKING_THIS, reference, LeakingThisDescriptor.NonFinalProperty(descriptor));
                return true
            }
            if (descriptor.containingDeclaration != classDescriptor) return true
            if (insideLValue(reference)) return descriptor.setter?.isDefault != false else return descriptor.getter?.isDefault != false
        }
        return true
    }

    private fun safeThisUsage(expression: KtThisExpression): Boolean {
        val referenceDescriptor = trace.get(BindingContext.REFERENCE_TARGET, expression.instanceReference)
        if (referenceDescriptor != classDescriptor) return true
        val parent = expression.parent
        return when (parent) {
            is KtQualifiedExpression -> (parent.selectorExpression as? KtSimpleNameExpression)?.let { checkReferenceSafety(it) } ?: false
            is KtBinaryExpression -> OperatorConventions.EQUALS_OPERATIONS.contains(parent.operationToken) ||
                                     OperatorConventions.IDENTITY_EQUALS_OPERATIONS.contains(parent.operationToken)
            else -> false
        }
    }

    private fun safeCallUsage(expression: KtCallExpression): Boolean {
        val callee = expression.calleeExpression
        if (callee is KtReferenceExpression) {
            val descriptor = trace.get(BindingContext.REFERENCE_TARGET, callee)
            if (descriptor is FunctionDescriptor) {
                val containingDescriptor = descriptor.containingDeclaration
                if (containingDescriptor != classDescriptor) return true
            }
        }
        return false
    }

    fun check() {
        // List of properties to initialize
        val propertyDescriptors = variablesData.getDeclaredVariables(pseudocode, false)
                .filterIsInstance<PropertyDescriptor>()
                .filter { trace.get(BindingContext.BACKING_FIELD_REQUIRED, it) == true }
        pseudocode.traverse(
                TraversalOrder.FORWARD, variablesData.variableInitializers, { instruction, enterData, exitData ->

            fun firstUninitializedNotNullProperty() = propertyDescriptors.firstOrNull {
                !it.type.isMarkedNullable && !(enterData[it]?.definitelyInitialized() ?: false)
            }

            fun handleLeakingThis(expression: KtExpression) {
                val uninitializedProperty = firstUninitializedNotNullProperty()
                if (uninitializedProperty != null) {
                    trace.record(BindingContext.LEAKING_THIS, expression, LeakingThisDescriptor.PropertyIsNull(uninitializedProperty))
                }
                else if (!finalClass && classDescriptor != null) {
                    trace.record(BindingContext.LEAKING_THIS, expression, LeakingThisDescriptor.NonFinalClass(classDescriptor))
                }
            }

            if (instruction.owner != pseudocode) {
                // We should miss *some* of this local declarations, but not all
                return@traverse
            }

            when (instruction) {
                is ReadValueInstruction ->
                        if (instruction.element is KtThisExpression) {
                            if (!safeThisUsage(instruction.element)) {
                                handleLeakingThis(instruction.element)
                            }
                        }
                is MagicInstruction ->
                        if (instruction.kind == MagicKind.IMPLICIT_RECEIVER) {
                            if (instruction.element is KtCallExpression) {
                                if (!safeCallUsage(instruction.element)) {
                                    handleLeakingThis(instruction.element)
                                }
                            }
                            else if (instruction.element is KtReferenceExpression) {
                                if (!checkReferenceSafety(instruction.element)) {
                                    handleLeakingThis(instruction.element)
                                }
                            }
                        }
            }
        })
    }

    companion object {
        fun check(constructor: KtConstructor<*>, trace: BindingTrace) {
            ConstructorConsistencyChecker(constructor, trace).check()
        }

        fun check(classOrObject: KtClassOrObject, trace: BindingTrace) {
            ConstructorConsistencyChecker(classOrObject, trace).check()
        }
    }
}