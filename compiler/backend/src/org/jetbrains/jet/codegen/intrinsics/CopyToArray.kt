/*
 * Copyright 2010-2014 JetBrains s.r.o.
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

package org.jetbrains.jet.codegen.intrinsics

import com.intellij.psi.PsiElement
import org.jetbrains.org.objectweb.asm.Type
import org.jetbrains.org.objectweb.asm.commons.InstructionAdapter
import org.jetbrains.jet.codegen.ExpressionCodegen
import org.jetbrains.jet.codegen.StackValue
import org.jetbrains.jet.lang.psi.JetExpression

import org.jetbrains.jet.lang.resolve.java.AsmTypeConstants.getType
import org.jetbrains.jet.codegen.CallableMethod
import com.sun.org.apache.xpath.internal.operations.UnaryOperation
import org.jetbrains.jet.codegen.state.GenerationState
import org.jetbrains.jet.lang.descriptors.FunctionDescriptor
import org.jetbrains.jet.lang.descriptors.DeclarationDescriptor
import org.jetbrains.jet.codegen.context.CodegenContext
import org.jetbrains.jet.codegen.ExtendedCallable
import org.jetbrains.jet.lang.types.JetType

public class CopyToArray : IntrinsicMethod() {
    override fun generateImpl(codegen: ExpressionCodegen, v: InstructionAdapter, returnType: Type, element: PsiElement?, arguments: List<JetExpression>?, receiver: StackValue?): Type {
        assert(receiver != null)
        receiver!!.put(receiver.`type`, v)
        v.dup()
        v.invokeinterface("java/util/Collection", "size", "()I")

        v.newarray(returnType.getElementType())
        v.invokeinterface("java/util/Collection", "toArray", "([Ljava/lang/Object;)[Ljava/lang/Object;")

        return getType(javaClass<Array<Any>>())
    }


    override fun supportCallable(): Boolean {
        return true
    }


    override fun toCallable(state: GenerationState, fd: FunctionDescriptor, isSuperCall: Boolean, context: CodegenContext<out DeclarationDescriptor?>): ExtendedCallable {
        val callableMethod = state.getTypeMapper().mapToCallableMethod(fd, isSuperCall, context)
        return UnaryIntrinsic(callableMethod, getType(javaClass<Array<Any>>())) {
            assert(calcReceiverType() != null)

            it.dup()
            it.invokeinterface("java/util/Collection", "size", "()I")

            it.newarray(state.getTypeMapper().mapType(fd.getReturnType()!!).getElementType())
            it.invokeinterface("java/util/Collection", "toArray", "([Ljava/lang/Object;)[Ljava/lang/Object;")
        }
    }
}
