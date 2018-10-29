package io.ktor.tests.server.jetty

import io.ktor.server.engine.*
import io.ktor.server.jetty.*
import io.ktor.server.servlet.*
import io.ktor.server.testing.*
import org.eclipse.jetty.server.*
import org.eclipse.jetty.server.handler.*
import org.eclipse.jetty.servlet.*
import org.junit.*
import java.security.*
import java.util.concurrent.locks.*
import javax.servlet.*
import javax.servlet.http.*
import kotlin.concurrent.*

class JettyAsyncServletContainerEngineTest :
    EngineTestSuite<JettyApplicationEngineBase, JettyApplicationEngineBase.Configuration>(Servlet(async = true))

class JettyBlockingServletContainerEngineTest :
    EngineTestSuite<JettyApplicationEngineBase, JettyApplicationEngineBase.Configuration>(Servlet(async = false)) {
    @Ignore
    override fun testUpgrade() {
    }
}

// the factory and engine are only suitable for testing
// you shouldn't use it for production code

private class Servlet(private val async: Boolean) :
    ApplicationEngineFactory<JettyServletApplicationEngine, JettyApplicationEngineBase.Configuration> {
    override fun create(
        environment: ApplicationEngineEnvironment,
        configure: JettyApplicationEngineBase.Configuration.() -> Unit
    ): JettyServletApplicationEngine {
        return JettyServletApplicationEngine(environment, configure, async)
    }
}

@UseExperimental(EngineAPI::class)
private class JettyServletApplicationEngine(
    environment: ApplicationEngineEnvironment,
    configure: JettyApplicationEngineBase.Configuration.() -> Unit,
    async: Boolean
) : JettyApplicationEngineBase(environment, configure) {
    init {
        val servletHandler = ServletContextHandler().apply {
            classLoader = environment.classLoader
            setAttribute(ServletApplicationEngine.ApplicationEngineEnvironmentAttributeKey, environment)

            insertHandler(
                ServletHandler().apply {
                    val h = ServletHolder("ktor-servlet", ServletApplicationEngine::class.java).apply {
                        isAsyncSupported = async
                        registration.setLoadOnStartup(1)
                        registration.setMultipartConfig(MultipartConfigElement(System.getProperty("java.io.tmpdir")))
                        registration.setAsyncSupported(async)
                    }

                    addServlet(h)
                    addServletMapping(ServletMapping().apply {
                        pathSpecs = arrayOf("*.", "/*")
                        servletName = "ktor-servlet"
                    })
                })
        }

        if (async) {
            server.handler = servletHandler
        } else {
            server.handler = JavaSecurityHandler().apply {
                handler = servletHandler
            }
        }
    }
}

private class JavaSecurityHandler : HandlerWrapper() {
    private val securityManager = RestrictThreadCreationSecurityManager(null)

    override fun handle(
        target: String?,
        baseRequest: Request?,
        request: HttpServletRequest?,
        response: HttpServletResponse?
    ) {
        securityManager.enter()
        try {
            super.handle(target, baseRequest, request, response)
        } finally {
            securityManager.leave()
        }
    }
}

private class RestrictThreadCreationSecurityManager(val delegate: SecurityManager?) : SecurityManager() {
    private val lock = ReentrantLock()
    private var refCount = 0

    internal fun enter() {
        lock.withLock {
            refCount++
            if (refCount == 1) {
                System.setSecurityManager(this)
            }
        }
    }

    internal fun leave() {
        lock.withLock {
            if (refCount == 0) throw IllegalStateException("enter/leave balance violation")
            refCount--
            if (refCount == 0) {
                System.setSecurityManager(null)
            }
        }
    }

    override fun checkPermission(perm: Permission?) {
        if (perm is RuntimePermission && perm.name == "modifyThreadGroup") {
            if (inJavaSecurityHandler()) {
                throw SecurityException("Thread modifications are not allowed")
            }
        }
        if (perm is RuntimePermission && perm.name == "setSecurityManager") {
            if (!isCalledByMe()) {
                throw SecurityException("SecurityManager change is not allowed")
            }
            return
        }

        delegate?.checkPermission(perm)
    }

    private fun inJavaSecurityHandler(): Boolean {
        return JavaSecurityHandler::class.java in classContext
    }

    private fun isCalledByMe(): Boolean {
        return javaClass in classContext
    }

    private var rootGroup: ThreadGroup? = null

    override fun getThreadGroup(): ThreadGroup? {
        if (rootGroup == null) {
            rootGroup = findRootGroup()
        }
        return rootGroup
    }

    private fun findRootGroup(): ThreadGroup {
        var root = Thread.currentThread().threadGroup
        while (root.parent != null) {
            root = root.parent
        }
        return root
    }
}

