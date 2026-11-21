package com.aid.assistant.skill;

import cn.hutool.crypto.SecureUtil;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.assistant.budget.AssistantBudgetOperationScope;
import com.aid.billing.model.BillingSnapshot;
import com.aid.common.exception.ServiceException;
import com.aid.skill.domain.AidSkillRun;
import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Actual provider-boundary scope derivation using persisted task/config naming semantics. */
class AssistantSkillProviderScopeRegressionTest {
    static class Fixture {
        final AssistantSkillSubmissionAdmissionHandler handler =
                new AssistantSkillSubmissionAdmissionHandler(null,null,null,null,null,null,null,null,null);
        final AssistantSkillBudgetEnvelope envelope = new AssistantSkillBudgetEnvelope();
        final AidMediaTask task = new AidMediaTask();
        final AidSkillRun run = new AidSkillRun();
        final BillingSnapshot snapshot = new BillingSnapshot();
        Fixture() {
            envelope.setAssistantRuntimeRunId(77L); envelope.setAssistantParentStepId(152L);
            envelope.setAssistantRuntimeRunFencingToken(1L); envelope.setAssistantParentStepFencingToken(1L);
            envelope.setAssistantOuterStableStepKey("run_method"); envelope.setAssistantAttemptNo(0);
            envelope.setBillingAuthorizationId(45L); envelope.setBillingAuthorizationRevision(1L);
            envelope.setPayerUserId(48L); envelope.setApprovalId(119L); envelope.setSkillVersionId(73L);
            envelope.setPackageDigest("a".repeat(64)); envelope.setApprovedScopeDigest("b".repeat(64));
            envelope.setApprovedOperationScopeJson("{\"operationKind\":\"SKILL_TEXT\",\"quotedUpperBound\":10,\"targetRefs\":[{\"kind\":\"FREE_CANVAS\",\"workspaceId\":230,\"objectId\":\"node_source\",\"version\":1}]}");
            envelope.setApprovedOperationScopeDigest(SecureUtil.sha256(envelope.getApprovedOperationScopeJson()));
            task.setMediaType("TEXT"); task.setModelName("deepseek-flash"); run.setModelCode("deepseek-flash");
            snapshot.setModelType("text"); snapshot.setModelName("DeepSeek V4.1 Flash");
            snapshot.setAuthorizedChargeCap(new BigDecimal("8.0911"));
        }
        AssistantBudgetOperationScope scope() throws Exception {
            var method = handler.getClass().getDeclaredMethod("providerScope", AssistantSkillBudgetEnvelope.class,
                    AidMediaTask.class, AidSkillRun.class, BillingSnapshot.class);
            method.setAccessible(true);
            try { return (AssistantBudgetOperationScope) method.invoke(handler,envelope,task,run,snapshot); }
            catch (InvocationTargetException error) {
                if (error.getCause() instanceof RuntimeException cause) throw cause;
                throw error;
            }
        }
    }
    @Test void lowercaseBillingTypeAndDisplayNameUsePinnedTaskModelCode() throws Exception {
        var f=new Fixture(); var scope=f.scope();
        assertEquals("deepseek-flash",scope.getModelCode());
        assertNotEquals(f.snapshot.getModelName(),scope.getModelCode());
    }
    @Test void uppercaseLegacyBillingTypeRemainsValid() throws Exception {
        var f=new Fixture();f.snapshot.setModelType("TEXT");assertEquals("deepseek-flash",f.scope().getModelCode());
    }
    @Test void differentTaskOrMissingPinnedModelRemainRejected() throws Exception {
        for(String code: new String[]{"other-model",null," "}) {
            var f=new Fixture();f.task.setModelName(code);assertThrows(ServiceException.class,f::scope);
        }
        var f=new Fixture();f.run.setModelCode("other-model");assertThrows(ServiceException.class,f::scope);
    }
    @Test void nonTextTaskOrBillingSnapshotRemainRejected() throws Exception {
        for(String type: new String[]{"IMAGE","VIDEO",null," text "}) {
            var f=new Fixture();f.snapshot.setModelType(type);assertThrows(ServiceException.class,f::scope);
        }
        var f=new Fixture();f.task.setMediaType("IMAGE");assertThrows(ServiceException.class,f::scope);
    }
    @Test void absentOrNegativeCapAndInvalidEnvelopeRemainRejected() throws Exception {
        for(BigDecimal cap:new BigDecimal[]{null,new BigDecimal("-0.0001")}) {
            var f=new Fixture();f.snapshot.setAuthorizedChargeCap(cap);assertThrows(ServiceException.class,f::scope);
        }
        var f=new Fixture();f.envelope.setApprovedOperationScopeDigest("c".repeat(64));assertThrows(ServiceException.class,f::scope);
    }
    @Test void exactAmountAndOuterTargetsRemainBoundWithoutExtraIteration() throws Exception {
        var f=new Fixture();var scope=f.scope();
        assertEquals(new BigDecimal("8.0911"),scope.getQuotedUpperBound());
        assertEquals(1,scope.getTargetRefs().size());
        assertEquals("FREE_CANVAS",scope.getTargetRefs().get(0).getKind());
        assertEquals(230L,scope.getTargetRefs().get(0).getWorkspaceId());
        assertEquals("node_source",scope.getTargetRefs().get(0).getObjectId());
        assertEquals(List.of("TEXT"),scope.getMediaTypes());
        assertEquals(List.of("skill.invoke","text.chat"),scope.getCapabilityCodes());
        assertEquals("SKILL_TEXT",scope.getOperationKind());assertFalse(scope.isCountsAsIteration());
    }
}
