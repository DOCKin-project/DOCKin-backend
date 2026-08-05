package com.DOCKin.rag.service;

import com.DOCKin.global.error.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EmbeddingClientTest {

    @Test
    @DisplayName("벡터를 바이트로 눕혔다가 복원하면 원본과 같다")
    void 왕복_변환() {
        float[] original = {0.1f, -0.25f, 3.5f, 0f, -1f};

        byte[] bytes = EmbeddingClient.toBytes(original);
        float[] restored = EmbeddingClient.toFloats(bytes);

        assertArrayEquals(original, restored);
    }

    @Test
    @DisplayName("384차원 벡터는 1536바이트가 된다")
    void 바이트_길이() {
        float[] vector = new float[384];

        byte[] bytes = EmbeddingClient.toBytes(vector);

        // VARBINARY(4096) / BYTEA 시절 컬럼 크기를 잡은 근거였던 계산이다.
        // 지금 운영 컬럼은 pgvector vector(384)이며, 이 변환은 Phase 1 기준선 벤치마크만 쓴다.
        assertEquals(1536, bytes.length);
    }

    @Test
    @DisplayName("float 경계에 맞지 않는 바이트는 차원 불일치로 거른다")
    void 잘못된_바이트_길이() {
        byte[] broken = new byte[7]; // 4의 배수가 아님

        assertThrows(BusinessException.class, () -> EmbeddingClient.toFloats(broken));
    }
}
