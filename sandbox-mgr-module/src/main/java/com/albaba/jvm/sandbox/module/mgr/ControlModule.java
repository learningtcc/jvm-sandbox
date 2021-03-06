package com.albaba.jvm.sandbox.module.mgr;

import com.alibaba.jvm.sandbox.api.Information;
import com.alibaba.jvm.sandbox.api.Module;
import com.alibaba.jvm.sandbox.api.ModuleException;
import com.alibaba.jvm.sandbox.api.http.Http;
import com.alibaba.jvm.sandbox.api.resource.ModuleManager;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

@Information(id = "control", version = "0.0.1", author = "luanjia@taobao.com")
public class ControlModule implements Module {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Resource
    private ModuleManager moduleManager;

    // 清道夫，清理SandboxClassLoader和Spy中对Method的引用
    private void cleanRef(final Class<?> classOfAgentLauncher) throws IllegalAccessException, ClassNotFoundException {

        // 清理AgentLauncher.sandboxClassLoader
        FieldUtils.writeDeclaredStaticField(
                classOfAgentLauncher,
                "sandboxClassLoader",
                null,
                true
        );
        logger.info("clean jvm-sandbox ClassLoader success, for jvm-sandbox shutdown.");

        // 清理Spy中的所有方法
        final Class<?> classOfSpy = getClass().getClassLoader()
                .loadClass("java.com.alibaba.jvm.sandbox.spy.Spy");
        for (final Field waitingCleanField : classOfSpy.getDeclaredFields()) {
            if (Method.class.isAssignableFrom(waitingCleanField.getType())) {
                FieldUtils.writeDeclaredStaticField(
                        classOfSpy,
                        waitingCleanField.getName(),
                        null,
                        true
                );
            }
        }
        logger.info("clean Spy's method success, for jvm-sandbox shutdown.");
    }

    // 卸载所有模块
    // 从这里开始只允许调用JDK自带的反射方法，因为ControlModule已经完成卸载，你找不到apache的包了
    private void unloadModules() throws ModuleException, IOException {

        for (final Module module : moduleManager.list()) {
            final Information information = module.getClass().getAnnotation(Information.class);
            if (null == information
                    || StringUtils.isBlank(information.id())) {
                continue;
            }
            // 如果遇到自己，需要最后才卸载
            if (module == this) {
                continue;
            }
            moduleManager.unload(information.id());
            logger.info("unload module={} success, for shutdown jvm-sandbox.", information.id());
        }

    }

    // 卸载自己
    private void unloadSelf() throws ModuleException {
        // 卸载自己
        final String self = getClass().getAnnotation(Information.class).id();
        moduleManager.unload(self);
        logger.info("unload module={} success, for shutdown jvm-sandbox.", self);
    }

    // 关闭HTTP服务器
    private void shutdownServer(final ClassLoader sandboxClassLoader)
            throws ClassNotFoundException, NoSuchMethodException, InvocationTargetException, IllegalAccessException {

        final Class<?> classOfJettyCoreServer = sandboxClassLoader
                .loadClass("com.alibaba.jvm.sandbox.core.server.jetty.JettyCoreServer");
        final Object objectOfJettyCoreServer = classOfJettyCoreServer.getMethod("getInstance").invoke(null);
        final Method methodOfDestroy = classOfJettyCoreServer.getMethod("destroy");
        methodOfDestroy.invoke(objectOfJettyCoreServer, null);
        logger.info("shutdown http-server success, for shutdown jvm-sandbox.");
    }

    @Http("/shutdown")
    public void shutdown(final HttpServletResponse resp) throws Exception {

        logger.info("prepare to shutdown jvm-sandbox.");

        final Class<?> classOfAgentLauncher = getClass().getClassLoader()
                .loadClass("com.alibaba.jvm.sandbox.agent.AgentLauncher");

        final ClassLoader sandboxClassLoader = (ClassLoader) FieldUtils.getDeclaredField(
                classOfAgentLauncher,
                "sandboxClassLoader",
                true
        ).get(null);

        // 清理引用
        cleanRef(classOfAgentLauncher);

        // 卸载模块
        unloadModules();

        // 关闭HTTP服务器
        final Thread shutdownJvmSandboxHook = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    unloadSelf();
                    shutdownServer(sandboxClassLoader);
                    logger.info("shutdown jvm-sandbox finished.");
                } catch (Throwable cause) {
                    logger.warn("shutdown jvm-sandbox failed.", cause);
                }
            }
        }, "shutdown-jvm-sandbox-hook");
        shutdownJvmSandboxHook.setDaemon(true);

        // 在卸载自己之前，先向这个世界发出最后的呐喊吧！
        resp.getWriter().println("jvm-sandbox shutdown finished.");
        resp.getWriter().flush();
        resp.getWriter().close();

        shutdownJvmSandboxHook.start();

    }

}
