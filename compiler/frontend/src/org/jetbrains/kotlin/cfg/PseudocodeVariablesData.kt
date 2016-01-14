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

import com.google.common.collect.Maps
import com.google.common.collect.Sets
import org.jetbrains.kotlin.cfg.pseudocode.Pseudocode
import org.jetbrains.kotlin.cfg.pseudocode.PseudocodeUtil
import org.jetbrains.kotlin.cfg.pseudocode.instructions.Instruction
import org.jetbrains.kotlin.cfg.pseudocode.instructions.eval.MagicInstruction
import org.jetbrains.kotlin.cfg.pseudocode.instructions.eval.MagicKind
import org.jetbrains.kotlin.cfg.pseudocode.instructions.eval.ReadValueInstruction
import org.jetbrains.kotlin.cfg.pseudocode.instructions.eval.WriteValueInstruction
import org.jetbrains.kotlin.cfg.pseudocode.instructions.special.VariableDeclarationInstruction
import org.jetbrains.kotlin.cfg.pseudocodeTraverser.Edges
import org.jetbrains.kotlin.cfg.pseudocodeTraverser.TraversalOrder
import org.jetbrains.kotlin.descriptors.VariableDescriptor
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.resolve.BindingContext
import java.util.Collections

class PseudocodeVariablesData(val pseudocode: Pseudocode, private val bindingContext: BindingContext) {
    private val pseudocodeVariableDataCollector: PseudocodeVariableDataCollector

    private val declaredVariablesForDeclaration = Maps.newHashMap<Pseudocode, Set<VariableDescriptor>>()

    val variableInitializers: MutableMap<Instruction, Edges<MutableMap<VariableDescriptor, VariableControlFlowState>>> by lazy {
        computeVariableInitializers()
    }

    init {
        this.pseudocodeVariableDataCollector = PseudocodeVariableDataCollector(bindingContext, pseudocode)
    }

    val lexicalScopeVariableInfo: LexicalScopeVariableInfo
        get() = pseudocodeVariableDataCollector.lexicalScopeVariableInfo

    fun getDeclaredVariables(pseudocode: Pseudocode, includeInsideLocalDeclarations: Boolean): Set<VariableDescriptor> {
        if (!includeInsideLocalDeclarations) {
            return getUpperLevelDeclaredVariables(pseudocode)
        }
        val declaredVariables = Sets.newHashSet<VariableDescriptor>()
        declaredVariables.addAll(getUpperLevelDeclaredVariables(pseudocode))

        for (localFunctionDeclarationInstruction in pseudocode.localDeclarations) {
            val localPseudocode = localFunctionDeclarationInstruction.body
            declaredVariables.addAll(getUpperLevelDeclaredVariables(localPseudocode))
        }
        return declaredVariables
    }

    private fun getUpperLevelDeclaredVariables(pseudocode: Pseudocode): Set<VariableDescriptor> {
        var declaredVariables: Set<VariableDescriptor>? = declaredVariablesForDeclaration[pseudocode]
        if (declaredVariables == null) {
            declaredVariables = computeDeclaredVariablesForPseudocode(pseudocode)
            declaredVariablesForDeclaration.put(pseudocode, declaredVariables)
        }
        return declaredVariables
    }

    private fun computeDeclaredVariablesForPseudocode(pseudocode: Pseudocode): Set<VariableDescriptor> {
        val declaredVariables = Sets.newHashSet<VariableDescriptor>()
        for (instruction in pseudocode.instructions) {
            if (instruction is VariableDeclarationInstruction) {
                val variableDeclarationElement = instruction.variableDeclarationElement
                val descriptor = bindingContext.get(BindingContext.DECLARATION_TO_DESCRIPTOR, variableDeclarationElement)
                if (descriptor != null) {
                    assert(descriptor is VariableDescriptor)
                    declaredVariables.add(descriptor as VariableDescriptor?)
                }
            }
        }
        return Collections.unmodifiableSet(declaredVariables)
    }

    // variable initializers

    private fun computeVariableInitializers(): MutableMap<Instruction, Edges<MutableMap<VariableDescriptor, VariableControlFlowState>>> {

        val lexicalScopeVariableInfo = pseudocodeVariableDataCollector.lexicalScopeVariableInfo

        return pseudocodeVariableDataCollector.collectData(
                TraversalOrder.FORWARD, /*mergeDataWithLocalDeclarations=*/ true,
                object : InstructionDataMergeStrategy<VariableControlFlowState> {
                    override operator fun invoke(
                            instruction: Instruction,
                            incomingEdgesData: Collection<MutableMap<VariableDescriptor, VariableControlFlowState>>
                    ): Edges<MutableMap<VariableDescriptor, VariableControlFlowState>> {

                        val enterInstructionData = mergeIncomingEdgesDataForInitializers(incomingEdgesData)
                        val exitInstructionData = addVariableInitStateFromCurrentInstructionIfAny(
                                instruction, enterInstructionData, lexicalScopeVariableInfo)
                        return Edges(enterInstructionData, exitInstructionData)
                    }
                })
    }

    private fun addVariableInitStateFromCurrentInstructionIfAny(
            instruction: Instruction,
            enterInstructionData: MutableMap<VariableDescriptor, VariableControlFlowState>,
            lexicalScopeVariableInfo: LexicalScopeVariableInfo
    ): MutableMap<VariableDescriptor, VariableControlFlowState> {
        if (instruction is MagicInstruction) {
            if (instruction.kind === MagicKind.EXHAUSTIVE_WHEN_ELSE) {
                val exitInstructionData = Maps.newHashMap(enterInstructionData)
                for (entry in enterInstructionData.entries) {
                    if (!entry.value.definitelyInitialized()) {
                        exitInstructionData.put(entry.key,
                                                VariableControlFlowState.createInitializedExhaustively(entry.value.isDeclared))
                    }
                }
                return exitInstructionData
            }
        }
        if (instruction !is WriteValueInstruction && instruction !is VariableDeclarationInstruction) {
            return enterInstructionData
        }
        val variable = PseudocodeUtil.extractVariableDescriptorIfAny(instruction, false, bindingContext) ?: return enterInstructionData
        val exitInstructionData = Maps.newHashMap(enterInstructionData)
        if (instruction is WriteValueInstruction) {
            // if writing to already initialized object
            if (!PseudocodeUtil.isThisOrNoDispatchReceiver(instruction, bindingContext)) {
                return enterInstructionData
            }

            val enterInitState = enterInstructionData[variable]
            val initializationAtThisElement = VariableControlFlowState.create(instruction.element is KtProperty, enterInitState)
            exitInstructionData.put(variable, initializationAtThisElement)
        }
        else {
            // instruction instanceof VariableDeclarationInstruction
            var enterInitState: VariableControlFlowState? = enterInstructionData[variable]
            if (enterInitState == null) {
                enterInitState = getDefaultValueForInitializers(variable, instruction, lexicalScopeVariableInfo)
            }
            if (!enterInitState.mayBeInitialized() || !enterInitState.isDeclared) {
                val isInitialized = enterInitState.mayBeInitialized()
                val variableDeclarationInfo = VariableControlFlowState.create(isInitialized, true)
                exitInstructionData.put(variable, variableDeclarationInfo)
            }
        }
        return exitInstructionData
    }

    // variable use

    /*mergeDataWithLocalDeclarations=*///instruction instanceof WriteValueInstruction
    val variableUseStatusData: MutableMap<Instruction, Edges<MutableMap<VariableDescriptor, VariableUseState>>>
        get() = pseudocodeVariableDataCollector.collectData(
                TraversalOrder.BACKWARD, true,
                object : InstructionDataMergeStrategy<VariableUseState> {
                    override operator fun invoke(
                            instruction: Instruction,
                            incomingEdgesData: Collection<MutableMap<VariableDescriptor, VariableUseState>>
                    ): Edges<MutableMap<VariableDescriptor, VariableUseState>> {

                        val enterResult = Maps.newHashMap<VariableDescriptor, VariableUseState>()
                        for (edgeData in incomingEdgesData) {
                            for (entry in edgeData.entries) {
                                val variableDescriptor = entry.key
                                val variableUseState = entry.value
                                enterResult.put(variableDescriptor, variableUseState.merge(enterResult[variableDescriptor]))
                            }
                        }
                        val variableDescriptor = PseudocodeUtil.extractVariableDescriptorIfAny(
                                instruction, true, bindingContext)
                        if (variableDescriptor == null || instruction !is ReadValueInstruction && instruction !is WriteValueInstruction) {
                            return Edges(enterResult, enterResult)
                        }
                        val exitResult = Maps.newHashMap(enterResult)
                        if (instruction is ReadValueInstruction) {
                            exitResult.put(variableDescriptor, VariableUseState.READ)
                        }
                        else {
                            var variableUseState: VariableUseState? = enterResult[variableDescriptor]
                            if (variableUseState == null) {
                                variableUseState = VariableUseState.UNUSED
                            }
                            when (variableUseState) {
                                VariableUseState.UNUSED, VariableUseState.ONLY_WRITTEN_NEVER_READ ->
                                    exitResult.put(variableDescriptor, VariableUseState.ONLY_WRITTEN_NEVER_READ)
                                VariableUseState.WRITTEN_AFTER_READ, VariableUseState.READ ->
                                    exitResult.put(variableDescriptor, VariableUseState.WRITTEN_AFTER_READ)
                            }
                        }
                        return Edges(enterResult, exitResult)
                    }
                })

    companion object {

        @JvmStatic
        fun getDefaultValueForInitializers(
                variable: VariableDescriptor,
                instruction: Instruction,
                lexicalScopeVariableInfo: LexicalScopeVariableInfo): VariableControlFlowState {
            //todo: think of replacing it with "MapWithDefaultValue"
            val declaredIn = lexicalScopeVariableInfo.declaredIn[variable]
            val declaredOutsideThisDeclaration = declaredIn == null //declared outside this pseudocode
                                                 || declaredIn.lexicalScopeForContainingDeclaration != instruction.lexicalScope.lexicalScopeForContainingDeclaration
            return VariableControlFlowState.create(/*initState=*/declaredOutsideThisDeclaration)
        }

        private fun mergeIncomingEdgesDataForInitializers(
                incomingEdgesData: Collection<MutableMap<VariableDescriptor, VariableControlFlowState>>
        ): MutableMap<VariableDescriptor, VariableControlFlowState> {
            val variablesInScope = Sets.newHashSet<VariableDescriptor>()
            for (edgeData in incomingEdgesData) {
                variablesInScope.addAll(edgeData.keys)
            }

            val enterInstructionData = Maps.newHashMap<VariableDescriptor, VariableControlFlowState>()
            for (variable in variablesInScope) {
                var initState: InitState? = null
                var isDeclared = true
                for (edgeData in incomingEdgesData) {
                    val varControlFlowState = edgeData[variable]
                    if (varControlFlowState != null) {
                        initState = if (initState != null) initState.merge(varControlFlowState.initState) else varControlFlowState.initState
                        if (!varControlFlowState.isDeclared) {
                            isDeclared = false
                        }
                    }
                }
                if (initState == null) {
                    throw AssertionError("An empty set of incoming edges data")
                }
                enterInstructionData.put(variable, VariableControlFlowState.create(initState, isDeclared))
            }
            return enterInstructionData
        }
    }
}
