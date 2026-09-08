package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import junit.framework.TestCase

/** Headless coverage for Kotlin PSI metadata accessed through reflection. */
class KotlinClassMetadataUnitTest : TestCase() {

    fun testReflectiveKotlinClassKindUsesSemanticFlags() {
        assertEquals("CLASS", classify(ReflectiveKtClassFake()))
        assertEquals("INTERFACE", classify(ReflectiveKtClassFake(isInterface = true)))
        assertEquals("ENUM", classify(ReflectiveKtClassFake(isEnum = true)))
        assertEquals("ANNOTATION", classify(ReflectiveKtClassFake(isAnnotation = true)))
        assertEquals(
            "Annotation must win if a PSI implementation also presents as an interface",
            "ANNOTATION",
            classify(ReflectiveKtClassFake(isInterface = true, isAnnotation = true))
        )
        assertEquals("OBJECT", classify(ReflectiveKtObjectDeclarationFake()))
    }

    fun testReflectiveKotlinClassKindRejectsUnrelatedObjects() {
        assertNull(
            PsiUtils.reflectiveKotlinClassKind(
                element = Any(),
                ktClassType = ReflectiveKtClassFake::class.java,
                ktObjectDeclarationType = ReflectiveKtObjectDeclarationFake::class.java
            )
        )
    }

    fun testReflectiveQualifiedNameUsesKotlinGetFqName() {
        val declaration = ReflectiveKtNamedDeclarationFake(
            ReflectiveFqNameFake("com.example.payments.PaymentService")
        )

        assertEquals(
            "com.example.payments.PaymentService",
            PsiUtils.reflectiveQualifiedName(declaration)
        )
    }

    fun testReflectiveQualifiedNameTreatsMissingOrBlankKotlinFqNameAsAbsent() {
        assertNull(PsiUtils.reflectiveQualifiedName(ReflectiveKtNamedDeclarationFake(null)))
        assertNull(
            PsiUtils.reflectiveQualifiedName(
                ReflectiveKtNamedDeclarationFake(ReflectiveFqNameFake(""))
            )
        )
    }

    private fun classify(element: Any): String? =
        PsiUtils.reflectiveKotlinClassKind(
            element = element,
            ktClassType = ReflectiveKtClassFake::class.java,
            ktObjectDeclarationType = ReflectiveKtObjectDeclarationFake::class.java
        )
}

internal open class ReflectiveKtClassFake(
    private val isInterface: Boolean = false,
    private val isEnum: Boolean = false,
    private val isAnnotation: Boolean = false
) {
    fun isInterface(): Boolean = isInterface
    fun isEnum(): Boolean = isEnum
    fun isAnnotation(): Boolean = isAnnotation
}

internal class ReflectiveKtObjectDeclarationFake

internal class ReflectiveKtNamedDeclarationFake(private val fqName: Any?) {
    fun getFqName(): Any? = fqName
}

internal class ReflectiveFqNameFake(private val value: String) {
    override fun toString(): String = value
}
