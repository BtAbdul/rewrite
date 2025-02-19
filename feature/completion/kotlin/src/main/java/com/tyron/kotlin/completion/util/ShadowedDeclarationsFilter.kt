/*
 * This file is part of Cosmic IDE.
 * Cosmic IDE is a free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 * Cosmic IDE is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License along with Foobar. If not, see <https://www.gnu.org/licenses/>.
 */

/*
 *  This file is part of CodeAssist.
 *
 *  CodeAssist is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  CodeAssist is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with CodeAssist.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.tyron.kotlin.completion.util

import com.intellij.psi.PsiElement
import com.tyron.kotlin.completion.resolve.ResolutionFacade
import com.tyron.kotlin.completion.resolve.frontendService
import com.tyron.kotlin.completion.resolve.getDataFlowValueFactory
import com.tyron.kotlin.completion.resolve.getLanguageVersionSettings
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ConstructorDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.SimpleFunctionDescriptor
import org.jetbrains.kotlin.descriptors.TypeParameterDescriptor
import org.jetbrains.kotlin.descriptors.VariableDescriptor
import org.jetbrains.kotlin.idea.FrontendInternals
import org.jetbrains.kotlin.psi.Call
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtTypeProjection
import org.jetbrains.kotlin.psi.LambdaArgument
import org.jetbrains.kotlin.psi.ValueArgument
import org.jetbrains.kotlin.psi.ValueArgumentName
import org.jetbrains.kotlin.psi.createExpressionByPattern
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.BindingTraceFilter.Companion.NO_DIAGNOSTICS
import org.jetbrains.kotlin.resolve.DelegatingBindingTrace
import org.jetbrains.kotlin.resolve.DescriptorEquivalenceForOverrides
import org.jetbrains.kotlin.resolve.bindingContextUtil.getDataFlowInfoBefore
import org.jetbrains.kotlin.resolve.calls.CallResolver
import org.jetbrains.kotlin.resolve.calls.context.BasicCallResolutionContext
import org.jetbrains.kotlin.resolve.calls.context.CheckArgumentTypesMode
import org.jetbrains.kotlin.resolve.calls.context.ContextDependency
import org.jetbrains.kotlin.resolve.scopes.receivers.ExpressionReceiver
import org.jetbrains.kotlin.resolve.scopes.receivers.ReceiverValue
import org.jetbrains.kotlin.resolve.scopes.utils.addImportingScope
import org.jetbrains.kotlin.types.TypeConstructor
import org.jetbrains.kotlin.types.TypeUtils
import org.jetbrains.kotlin.types.checker.KotlinTypeChecker
import org.jetbrains.kotlin.types.checker.KotlinTypeCheckerImpl
import org.jetbrains.kotlin.types.typeUtil.equalTypesOrNulls

class ShadowedDeclarationsFilter(
    private val bindingContext: BindingContext,
    private val resolutionFacade: ResolutionFacade,
    private val context: PsiElement,
    private val explicitReceiverValue: ReceiverValue?
) {
    companion object {
        fun create(
            bindingContext: BindingContext,
            resolutionFacade: ResolutionFacade,
            context: PsiElement,
            callTypeAndReceiver: CallTypeAndReceiver<*, *>
        ): ShadowedDeclarationsFilter? {
            val receiverExpression = when (callTypeAndReceiver) {
                is CallTypeAndReceiver.DEFAULT -> null
                is CallTypeAndReceiver.DOT -> callTypeAndReceiver.receiver
                is CallTypeAndReceiver.SAFE -> callTypeAndReceiver.receiver
                is CallTypeAndReceiver.SUPER_MEMBERS -> callTypeAndReceiver.receiver
                is CallTypeAndReceiver.INFIX -> callTypeAndReceiver.receiver
                is CallTypeAndReceiver.TYPE, is CallTypeAndReceiver.ANNOTATION -> null // need filtering of classes with the same FQ-name
                else -> return null // TODO: support shadowed declarations filtering for callable references
            }

            val explicitReceiverValue = receiverExpression?.let {
                val type = bindingContext.getType(it) ?: return null
                ExpressionReceiver.create(it, type, bindingContext)
            }
            return ShadowedDeclarationsFilter(
                bindingContext,
                resolutionFacade,
                context,
                explicitReceiverValue
            )
        }
    }

    private val psiFactory = KtPsiFactory(resolutionFacade.project)
    private val dummyExpressionFactory = DummyExpressionFactory(psiFactory)

    fun <TDescriptor : DeclarationDescriptor> filter(declarations: Collection<TDescriptor>): Collection<TDescriptor> =
        declarations.groupBy { signature(it) }.values.flatMap { group ->
            filterEqualSignatureGroup(
                group
            )
        }

    private fun signature(descriptor: DeclarationDescriptor): Any = when (descriptor) {
        is SimpleFunctionDescriptor -> FunctionSignature(descriptor)
        is VariableDescriptor -> descriptor.name
        is ClassDescriptor -> descriptor.importableFqName ?: descriptor
        else -> descriptor
    }

    private fun <TDescriptor : DeclarationDescriptor> filterEqualSignatureGroup(
        descriptors: Collection<TDescriptor>,
        descriptorsToImport: Collection<TDescriptor> = emptyList()
    ): Collection<TDescriptor> {
        if (descriptors.size == 1) return descriptors

        val first = descriptors.firstOrNull {
            it is ClassDescriptor || it is ConstructorDescriptor || it is CallableDescriptor && !it.name.isSpecial
        } ?: return descriptors

        if (first is ClassDescriptor) { // for classes with the same FQ-name we simply take the first one
            return listOf(first)
        }

        // Optimization: if the descriptors are structurally equivalent then there is no need to run resolve.
        // This can happen when the classpath contains multiple copies of the same library.
        if (descriptors.all {
                DescriptorEquivalenceForOverrides.areEquivalent(
                    first,
                    it,
                    allowCopiesFromTheSameDeclaration = true
                )
            }) {
            return listOf(first)
        }

        val isFunction = first is FunctionDescriptor
        val name = when (first) {
            is ConstructorDescriptor -> first.constructedClass.name
            else -> first.name
        }
        val parameters = (first as CallableDescriptor).valueParameters

        val dummyArgumentExpressions =
            dummyExpressionFactory.createDummyExpressions(parameters.size)

        val bindingTrace = DelegatingBindingTrace(
            bindingContext, "Temporary trace for filtering shadowed declarations",
            filter = NO_DIAGNOSTICS
        )
        for ((expression, parameter) in dummyArgumentExpressions.zip(parameters)) {
            bindingTrace.recordType(expression, parameter.varargElementType ?: parameter.type)
            bindingTrace.record(BindingContext.PROCESSED, expression, true)
        }

        val firstVarargIndex =
            parameters.withIndex().firstOrNull { it.value.varargElementType != null }?.index
        val useNamedFromIndex =
            if (firstVarargIndex != null && firstVarargIndex != parameters.lastIndex) firstVarargIndex else parameters.size

        class DummyArgument(val index: Int) : ValueArgument {
            private val expression = dummyArgumentExpressions[index]

            private val argumentName: ValueArgumentName? = if (isNamed()) {
                object : ValueArgumentName {
                    override val asName = parameters[index].name
                    override val referenceExpression = null
                }
            } else {
                null
            }

            override fun getArgumentExpression() = expression
            override fun isNamed() = index >= useNamedFromIndex
            override fun getArgumentName() = argumentName
            override fun asElement() = expression
            override fun getSpreadElement() = null
            override fun isExternal() = false
        }

        val arguments = ArrayList<DummyArgument>()
        for (i in parameters.indices) {
            arguments.add(DummyArgument(i))
        }

        val newCall = object : Call {
            //TODO: compiler crash (KT-8011)
            //val arguments = parameters.indices.map { DummyArgument(it) }
            val callee = psiFactory.createExpressionByPattern("$0", name, reformat = false)

            override fun getCalleeExpression() = callee

            override fun getValueArgumentList() = null

            override fun getValueArguments(): List<ValueArgument> = arguments

            override fun getFunctionLiteralArguments() = emptyList<LambdaArgument>()

            override fun getTypeArguments() = emptyList<KtTypeProjection>()

            override fun getTypeArgumentList() = null

            override fun getDispatchReceiver() = null

            override fun getCallOperationNode() = null

            override fun getExplicitReceiver() = explicitReceiverValue

            override fun getCallElement() = callee

            override fun getCallType() = Call.CallType.DEFAULT
        }

        var scope = context.getResolutionScope(bindingContext, resolutionFacade)

        if (descriptorsToImport.isNotEmpty()) {
            scope = scope.addImportingScope(ExplicitImportsScope(descriptorsToImport))
        }

        val dataFlowInfo = bindingContext.getDataFlowInfoBefore(context)
        val context = BasicCallResolutionContext.create(
            bindingTrace, scope, newCall, TypeUtils.NO_EXPECTED_TYPE, dataFlowInfo,
            ContextDependency.INDEPENDENT, CheckArgumentTypesMode.CHECK_VALUE_ARGUMENTS,
            false, resolutionFacade.getLanguageVersionSettings(),
            resolutionFacade.getDataFlowValueFactory()
        )

        @OptIn(FrontendInternals::class)
        val callResolver = resolutionFacade.frontendService<CallResolver>()
        val results =
            if (isFunction) callResolver.resolveFunctionCall(context) else callResolver.resolveSimpleProperty(
                context
            )
        val resultingDescriptors = results.resultingCalls.map { it.resultingDescriptor }
        val resultingOriginals =
            resultingDescriptors.mapTo(HashSet<DeclarationDescriptor>()) { it.original }
        val filtered = descriptors.filter { candidateDescriptor ->
            candidateDescriptor.original in resultingOriginals /* optimization */ && resultingDescriptors.any {
                descriptorsEqualWithSubstitution(
                    it,
                    candidateDescriptor
                )
            }
        }
        return if (filtered.isNotEmpty()) filtered else descriptors /* something went wrong, none of our declarations among resolve candidates, let's not filter anything */
    }

    private class DummyExpressionFactory(val factory: KtPsiFactory) {
        private val expressions = ArrayList<KtExpression>()

        fun createDummyExpressions(count: Int): List<KtExpression> {
            while (expressions.size < count) {
                expressions.add(factory.createExpression("dummy"))
            }
            return expressions.take(count)
        }
    }

    private class FunctionSignature(val function: FunctionDescriptor) {
        override fun equals(other: Any?): Boolean {
            if (other === this) return true
            if (other !is FunctionSignature) return false
            if (function.name != other.function.name) return false
            val parameters1 = function.valueParameters
            val parameters2 = other.function.valueParameters
            if (parameters1.size != parameters2.size) return false
            for (i in parameters1.indices) {
                val p1 = parameters1[i]
                val p2 = parameters2[i]
                if (p1.varargElementType != p2.varargElementType) return false // both should be vararg or or both not
                if (p1.type != p2.type) return false
            }

            val typeParameters1 = function.typeParameters
            val typeParameters2 = other.function.typeParameters
            if (typeParameters1.size != typeParameters2.size) return false
            for (i in typeParameters1.indices) {
                val t1 = typeParameters1[i]
                val t2 = typeParameters2[i]
                if (t1.upperBounds != t2.upperBounds) return false
            }
            return true
        }

        override fun hashCode() = function.name.hashCode() * 17 + function.valueParameters.size
    }
}

fun descriptorsEqualWithSubstitution(
    descriptor1: DeclarationDescriptor?,
    descriptor2: DeclarationDescriptor?,
    checkOriginals: Boolean = true
): Boolean {
    if (descriptor1 == descriptor2) return true
    if (descriptor1 == null || descriptor2 == null) return false
    if (checkOriginals && descriptor1.original != descriptor2.original) return false
    if (descriptor1 !is CallableDescriptor) return true
    descriptor2 as CallableDescriptor

    val typeChecker =
        KotlinTypeCheckerImpl.withAxioms(object : KotlinTypeChecker.TypeConstructorEquality {
            override fun equals(a: TypeConstructor, b: TypeConstructor): Boolean {
                val typeParam1 = a.declarationDescriptor as? TypeParameterDescriptor
                val typeParam2 = b.declarationDescriptor as? TypeParameterDescriptor
                if (typeParam1 != null
                    && typeParam2 != null
                    && typeParam1.containingDeclaration == descriptor1
                    && typeParam2.containingDeclaration == descriptor2
                ) {
                    return typeParam1.index == typeParam2.index
                }

                return a == b
            }
        })

    if (!typeChecker.equalTypesOrNulls(descriptor1.returnType, descriptor2.returnType)) return false

    val parameters1 = descriptor1.valueParameters
    val parameters2 = descriptor2.valueParameters
    if (parameters1.size != parameters2.size) return false
    for ((param1, param2) in parameters1.zip(parameters2)) {
        if (!typeChecker.equalTypes(param1.type, param2.type)) return false
    }
    return true
}
