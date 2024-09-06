package dev.autometrics.bindings;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class AutometricsAspectTest {
    private MeterRegistry meterRegistry;

    private ProceedingJoinPoint proceedingJoinPoint;

    private Signature signature;

    private AutometricsAspect sampleAspect;

    @BeforeEach
    public void beforeEach() {
        this.meterRegistry = new SimpleMeterRegistry();
        this.proceedingJoinPoint = mock(ProceedingJoinPoint.class);

        this.signature = mock(Signature.class);
        Environment environment = mock(Environment.class);

        when(environment.getProperty(any(String.class))).thenReturn(null);

        when(proceedingJoinPoint.getSignature()).thenReturn(signature);

        this.sampleAspect = new AutometricsAspect(meterRegistry, environment);
    }

    @Test
    public void shouldReturnResponseOfProceed() throws Throwable {
        var expectedResponse = "Hello world response";
        when(signature.getName()).thenReturn("functionName");
        when(proceedingJoinPoint.proceed()).thenReturn(expectedResponse);
        when(signature.getDeclaringType()).thenReturn(Object.class);

        var result = sampleAspect.methodCallDuration(proceedingJoinPoint);

        verify(proceedingJoinPoint, times(1)).proceed();

        assertEquals(expectedResponse, result);
    }

    @Test
    public void shouldThrowOriginalExceptionThrownByJoinPoint() throws Throwable {
        when(signature.getName()).thenReturn("functionName");
        when(proceedingJoinPoint.proceed()).thenThrow(new IllegalStateException());
        when(signature.getDeclaringType()).thenReturn(Object.class);

        assertThrows(IllegalStateException.class, () -> sampleAspect.methodCallDuration(proceedingJoinPoint));
    }

    @Test
    public void shouldThrowOriginalErrorThrownByJoinPoint() throws Throwable {
        when(signature.getName()).thenReturn("functionName");
        when(proceedingJoinPoint.proceed()).thenThrow(new AssertionError());
        when(signature.getDeclaringType()).thenReturn(Object.class);

        assertThrows(AssertionError.class, () -> sampleAspect.methodCallDuration(proceedingJoinPoint));
    }

    @Test
    public void shouldRecordCorrectMetrics() throws Throwable {
        when(signature.getName()).thenReturn("functionName");
        when(signature.getDeclaringType()).thenReturn(Object.class);

        when(proceedingJoinPoint.proceed()).thenReturn(
            "success", "success", "success"
        );

        sampleAspect.methodCallDuration(proceedingJoinPoint);
        sampleAspect.methodCallDuration(proceedingJoinPoint);
        sampleAspect.methodCallDuration(proceedingJoinPoint);

        when(proceedingJoinPoint.proceed()).thenThrow(new IllegalStateException(), new AssertionError());

        assertThrows(
            IllegalStateException.class, () -> sampleAspect.methodCallDuration(proceedingJoinPoint)
        );

        assertThrows(
            AssertionError.class, () -> sampleAspect.methodCallDuration(proceedingJoinPoint)
        );

        var successCounter = meterRegistry.find("function.calls").tag("result", "ok").counter();
        var errorCounter = meterRegistry.find("function.calls").tag("result", "error").counter();

        assertNotNull(successCounter);
        assertNotNull(errorCounter);
        assertEquals(3, successCounter.count());
        assertEquals(2, errorCounter.count());

        var functionCallDurationTimer = meterRegistry.find("function.calls.duration").timer();

        assertNotNull(functionCallDurationTimer);
        assertEquals(5, functionCallDurationTimer.count());
    }

}
