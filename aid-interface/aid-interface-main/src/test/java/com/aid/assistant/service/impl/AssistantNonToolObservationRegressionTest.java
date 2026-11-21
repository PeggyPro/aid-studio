package com.aid.assistant.service.impl;

import com.aid.assistant.creative.observation.CreativeUntrustedTextObservationProjector;
import com.aid.assistant.creative.tool.CreativeRuntimeToolProvider;
import com.aid.assistant.service.AssistantToolObservationProjector;
import com.aid.assistant.error.AssistantServiceException;
import com.aid.runtime.domain.*;
import com.alibaba.fastjson2.JSONObject;
import java.lang.reflect.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssistantNonToolObservationRegressionTest {
    final AssistantLoopContextAssemblerImpl service=new AssistantLoopContextAssemblerImpl();
    final AssistantToolObservationProjector projector=mock(AssistantToolObservationProjector.class);
    Object observation(String kind,String code,String status,String json,boolean artifacts)throws Exception {
        Field f=service.getClass().getDeclaredField("observationProjectors");f.setAccessible(true);f.set(service,List.of(projector));
        var step=new AidRuntimeRunStep();step.setId(143L);step.setStepKind(kind);step.setToolCode(code);step.setStatus(status);step.setOutputJson(json);
        Method m=service.getClass().getDeclaredMethod("safeObservation",Long.class,AidRuntimeRun.class,AidRuntimeRunStep.class,boolean.class,int.class);m.setAccessible(true);
        try{return m.invoke(service,48L,new AidRuntimeRun(),step,artifacts,10000);}catch(InvocationTargetException error){if(error.getCause() instanceof RuntimeException cause)throw cause;throw error;}
    }
    @Test void missingToolCodeIsNotSupported(){var p=new CreativeUntrustedTextObservationProjector();assertFalse(p.supports(null));assertFalse(p.supports(""));assertFalse(p.supports("unknown"));assertTrue(p.supports(CreativeRuntimeToolProvider.SOURCE_CHUNK_READ));}
    @Test void failedSkillRestoresSafeObservationWithoutToolDispatch()throws Exception {var result=(JSONObject)observation("SKILL",null,"FAILED","{\"status\":\"FAILED\",\"content\":\"method failed\"}",false);assertEquals("FAILED",result.getString("status"));verifyNoInteractions(projector);}
    @Test void mainAgentIsNotMistakenForTool()throws Exception {assertNotNull(observation("MAIN_AGENT",null,"SUCCEEDED","{\"action\":\"PLAN\"}",false));verifyNoInteractions(projector);}
    @Test void nonToolCannotSpoofTypedTextTool()throws Exception {when(projector.supports(anyString())).thenReturn(true);assertNotNull(observation("SKILL",CreativeRuntimeToolProvider.SOURCE_CHUNK_READ,"SUCCEEDED","{}",false));verifyNoInteractions(projector);}
    @Test void safeSkillOutputStillRedactsExternalUrls()throws Exception {var result=(JSONObject)observation("SKILL",null,"SUCCEEDED","{\"content\":\"see https://example.invalid/private\",\"apiKey\":\"never-observe\"}",false);assertFalse(result.toJSONString().contains("https://"));assertFalse(result.containsKey("apiKey"));verifyNoInteractions(projector);}
    @Test void typedToolSuccessStillRequiresItsOutput()throws Exception {when(projector.supports(CreativeRuntimeToolProvider.SOURCE_CHUNK_READ)).thenReturn(true);assertEquals("ASSISTANT_RESULT_OBSERVATION_INVALID",assertThrows(AssistantServiceException.class,()->observation("TOOL",CreativeRuntimeToolProvider.SOURCE_CHUNK_READ,"SUCCEEDED",null,false)).getErrorCode());verify(projector).supports(CreativeRuntimeToolProvider.SOURCE_CHUNK_READ);}
    @Test void malformedOutputStillFailsAndArtifactsAreNotReinjected()throws Exception {assertEquals("ASSISTANT_RESULT_OBSERVATION_INVALID",assertThrows(AssistantServiceException.class,()->observation("SKILL",null,"FAILED","{invalid",false)).getErrorCode());assertNull(observation("SKILL",null,"SUCCEEDED","{\"content\":\"already stored\"}",true));}
}
