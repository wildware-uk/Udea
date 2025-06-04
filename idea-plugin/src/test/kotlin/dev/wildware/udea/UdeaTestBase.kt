package dev.wildware.udea

import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.pom.java.LanguageLevel
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import java.io.File

abstract class UdeaTestBase : LightJavaCodeInsightFixtureTestCase() {

    override fun setUp() {
        super.setUp()
        val libPath = File("../common/build/classes/kotlin/main").canonicalPath
        PsiTestUtil.addLibrary(module, "common-lib", libPath, "")
    }

    /**
     * Returns a descriptor with a real JDK defined by the JAVA_HOME environment variable.
     */
    fun getJdkHome(): LightProjectDescriptor {
        return object : ProjectDescriptor(LanguageLevel.JDK_17) {
            override fun getSdk(): Sdk? {
                return JavaSdk.getInstance().createJdk("Real JDK", System.getenv("JAVA_HOME"), false)
            }
        }
    }

    override fun getTestDataPath(): String? {
        return "src/test/testData"
    }

    override fun getProjectDescriptor(): LightProjectDescriptor {
        return getJdkHome()
    }
}