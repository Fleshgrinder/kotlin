/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.java.scopes

import org.jetbrains.kotlin.builtins.jvm.JavaToKotlinClassMap
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.java.toNotNullConeKotlinType
import org.jetbrains.kotlin.fir.scopes.FirScope
import org.jetbrains.kotlin.fir.scopes.ProcessorAction
import org.jetbrains.kotlin.fir.scopes.impl.FirAbstractProviderBasedScope
import org.jetbrains.kotlin.fir.scopes.impl.FirClassDeclaredMemberScope
import org.jetbrains.kotlin.fir.symbols.ConeCallableSymbol
import org.jetbrains.kotlin.fir.symbols.ConeFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.ConePropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.typeContext
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.name.Name

class JavaClassUseSiteScope(
    klass: FirRegularClass,
    session: FirSession,
    internal val superTypesScope: FirScope,
    private val declaredMemberScope: FirClassDeclaredMemberScope
) : FirAbstractProviderBasedScope(session, lookupInFir = true) {
    internal val symbol = klass.symbol

    //base symbol as key, overridden as value
    private val overriddenByBase = mutableMapOf<ConeFunctionSymbol, ConeFunctionSymbol?>()

    private val context: ConeTypeContext = session.typeContext

    private fun isEqualTypes(a: ConeKotlinType, b: ConeKotlinType): Boolean {
        if (a is ConeFlexibleType) return isEqualTypes(a.lowerBound, b)
        if (b is ConeFlexibleType) return isEqualTypes(a, b.lowerBound)
        with(context) {
            if (a is ConeClassLikeType && b is ConeClassLikeType) {
                val aId = a.lookupTag.classId
                val bId = b.lookupTag.classId
                val aMapped = JavaToKotlinClassMap.mapJavaToKotlin(aId.asSingleFqName()) ?: aId
                val bMapped = JavaToKotlinClassMap.mapJavaToKotlin(bId.asSingleFqName()) ?: bId
                return aMapped == bMapped
            }
            return isEqualTypeConstructors(a.typeConstructor(), b.typeConstructor())
        }
    }

    private fun isEqualTypes(a: FirTypeRef, b: FirTypeRef) =
        isEqualTypes(a.toNotNullConeKotlinType(session), b.toNotNullConeKotlinType(session))

    private fun isOverriddenFunCheck(member: FirNamedFunction, self: FirNamedFunction): Boolean {
        return member.valueParameters.size == self.valueParameters.size &&
                member.valueParameters.zip(self.valueParameters).all { (memberParam, selfParam) ->
                    isEqualTypes(memberParam.returnTypeRef, selfParam.returnTypeRef)
                }
    }

    internal fun ConeFunctionSymbol.getOverridden(candidates: Set<ConeFunctionSymbol>): ConeCallableSymbol? {
        if (overriddenByBase.containsKey(this)) return overriddenByBase[this]

        fun sameReceivers(memberTypeRef: FirTypeRef?, selfTypeRef: FirTypeRef?): Boolean {
            return when {
                memberTypeRef != null && selfTypeRef != null -> isEqualTypes(memberTypeRef, selfTypeRef)
                else -> memberTypeRef == null && selfTypeRef == null
            }
        }

        val self = (this as FirFunctionSymbol).fir as FirNamedFunction
        val overriding = candidates.firstOrNull {
            val member = (it as FirFunctionSymbol).fir as FirNamedFunction
            self.modality != Modality.FINAL
                    && sameReceivers(member.receiverTypeRef, self.receiverTypeRef)
                    && isOverriddenFunCheck(member, self)
        } // TODO: two or more overrides for one fun?
        overriddenByBase[this] = overriding
        return overriding
    }

    override fun processFunctionsByName(name: Name, processor: (ConeFunctionSymbol) -> ProcessorAction): ProcessorAction {
        val overrideCandidates = mutableSetOf<ConeFunctionSymbol>()
        if (!declaredMemberScope.processFunctionsByName(name) {
                overrideCandidates += it
                processor(it)
            }
        ) return ProcessorAction.STOP

        return superTypesScope.processFunctionsByName(name) {

            val overriddenBy = it.getOverridden(overrideCandidates)
            if (overriddenBy == null) {
                processor(it)
            } else {
                ProcessorAction.NEXT
            }
        }
    }

    override fun processPropertiesByName(name: Name, processor: (ConePropertySymbol) -> ProcessorAction): ProcessorAction {
        return declaredMemberScope.processPropertiesByName(name) { processor(it) }
    }
}
