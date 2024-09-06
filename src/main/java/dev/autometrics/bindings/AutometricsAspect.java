package dev.autometrics.bindings;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

@Aspect
@Component
public class AutometricsAspect {

    private final Log log = LogFactory.getLog(AutometricsAspect.class);

    private final MeterRegistry registry;

    private final Map<String, AtomicInteger> concurrentCallsForAllFunctions = new HashMap<>();

    public AutometricsAspect(MeterRegistry registry, Environment environment) {
        Properties properties = new Properties();
        try {
            properties.load(getClass().getClassLoader().getResourceAsStream("git.properties"));
        } catch (Exception e) {
            log.warn("Could not load git.properties");
        }

        Optional<String> gitCommitId = getCommit(properties);
        Optional<String> gitBranch = getBranch(properties);
        Optional<String> version = getVersion(environment);
        Optional<String> serviceName = getServiceName(environment);

        this.registry = registry;
        Gauge.builder("build_info", () -> 1.0)
            .tags("version", version.orElse("unknown"))
            .tags("commit", gitCommitId.orElse("unknown"))
            .tags("branch", gitBranch.orElse("unknown"))
            .tags("service.name", serviceName.orElse("unknown"))
            .register(registry);
    }

    private Optional<String> getServiceName(Environment environment) {
        return Optional.ofNullable(environment.getProperty("spring.application.name"));
    }

    private Optional<String> getCommit(Properties properties) {
        return Optional.ofNullable(properties.getProperty("git.commit.id.full"))
            .or(() -> Optional.ofNullable(properties.getProperty("git.commit.id")));
    }

    private Optional<String> getBranch(Properties properties) {
        return Optional.ofNullable(properties.getProperty("git.branch"));
    }

    private Optional<String> getVersion(Environment environment) {
        return Optional.ofNullable(environment.getProperty("app.version"));
    }

    @Around("@annotation(dev.autometrics.bindings.Autometrics)")
    public Object methodCallDuration(ProceedingJoinPoint joinPoint) throws Throwable {
        String function = joinPoint.getSignature().getName();
        String className = joinPoint.getSignature().getDeclaringType().getSimpleName();
        String module = joinPoint.getSignature().getDeclaringType().getPackageName();
        String fullFunctionName = className == null ? function : className + "." + function;

        var concurrentRequests = findConcurrentRequestsForFunction(fullFunctionName, module);
        concurrentRequests.incrementAndGet();

        var sample = Timer.start(registry);

        try {
            Object proceed = joinPoint.proceed();
            registry.counter("function.calls", "function", fullFunctionName, "module", module, "result", "ok").increment();
            return proceed;
        } catch (Throwable throwable) {
            registry.counter("function.calls", "function", fullFunctionName, "module", module, "result", "error").increment();
            log.warn("Error creating metrics for function " + fullFunctionName, throwable);

            throw throwable;
        } finally {
            concurrentRequests.decrementAndGet();

            sample.stop(
                Timer.builder("function.calls.duration")
                    .sla()
                    .tag("function", fullFunctionName)
                    .tag("module", module)
                    .publishPercentileHistogram()
                    .publishPercentiles()
                    .register(registry)
            );
        }
    }

    public AtomicInteger findConcurrentRequestsForFunction(String function, String module) {
        var name = module + "." + function;
        var concurrentRequests = concurrentCallsForAllFunctions.get(name);
        if (concurrentRequests != null) {
            return concurrentRequests;
        } else {
            var newConcurrentCalls = new AtomicInteger(0);
            Gauge.builder("function.calls.concurrent", () -> newConcurrentCalls)
                .tags("function", function)
                .tags("module", module)
                .register(registry);
            this.concurrentCallsForAllFunctions.put(name, newConcurrentCalls);
            return newConcurrentCalls;
        }
    }
}