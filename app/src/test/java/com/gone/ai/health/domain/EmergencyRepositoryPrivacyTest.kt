package com.gone.ai.health.domain

import com.gone.ai.health.data.*
import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class EmergencyRepositoryPrivacyTest {
    private inline fun <reified T> stub(noinline answer: (String) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ -> answer(method.name) } as T

    @Test fun `emergency payload uses wearable only query and never infers a fall from motion`() = runTest {
        val reading = VitalsReadingEntity(patientId="test",timestamp=1700000000000,heartRate=80,source="BLE",motionMagnitudeG=7f)
        val repo = EmergencyRepository(
            stub<EmergencyDao> { error("Unexpected profile access") },
            stub<VitalsDao> { method -> assertEquals("latestWearable",method); reading },
            stub<AnomalyDao> { error("Unverified stored anomaly must not become public clinical status") }, "test"
        )
        val p = EmergencyProfileEntity(patientId="test",emergencyId="test",createdAt=1,updatedAt=1)
        val payload=repo.buildPayload(p,"Test",30)
        assertEquals(80,payload.heartRate)
        assertEquals(reading.timestamp,payload.readingTimestamp)
        assertNull(payload.motionStatus)
        assertNull(payload.riskStatus)
        val privatePayload=repo.buildPayload(p.copy(shareLiveVitals=false,shareAllergies=false,allergies="Private"),"Test",30)
        assertNull(privatePayload.heartRate)
        assertNull(privatePayload.readingTimestamp)
        assertNull(privatePayload.allergies)
    }
    @Test fun `no wearable data yields no fabricated vital signs`() = runTest {
        val repo=EmergencyRepository(stub<EmergencyDao>{null},stub<VitalsDao>{null},stub<AnomalyDao>{null},"test")
        val payload=repo.buildPayload(EmergencyProfileEntity(patientId="test",emergencyId="test",createdAt=1,updatedAt=1),null,null)
        assertNull(payload.heartRate); assertNull(payload.spo2); assertNull(payload.readingTimestamp)
        assertNull(payload.riskStatus); assertNull(payload.motionStatus)
    }
}
