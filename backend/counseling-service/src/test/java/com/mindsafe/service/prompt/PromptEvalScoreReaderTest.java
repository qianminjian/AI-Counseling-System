package com.mindsafe.service.prompt;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mindsafe.domain.entity.CounselingSession;
import com.mindsafe.domain.entity.QualityScore;
import com.mindsafe.domain.mapper.CounselingSessionMapper;
import com.mindsafe.domain.mapper.QualityScoreMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

/**
 * PromptEvalScoreReader 单元测试（fix-gate：eval 分数从库读数，拒绝自报）
 */
@ExtendWith(MockitoExtension.class)
class PromptEvalScoreReaderTest {

    @Mock
    private CounselingSessionMapper sessionMapper;

    @Mock
    private QualityScoreMapper qualityScoreMapper;

    @InjectMocks
    private PromptEvalScoreReader reader;

    private CounselingSession session(UUID id) {
        CounselingSession s = new CounselingSession();
        s.setSessionId(id);
        s.setPromptVersion("SYS_001:v2:control");
        return s;
    }

    private QualityScore score(UUID sessionId, double empathy, double cbt, double safety, double engagement) {
        QualityScore q = new QualityScore();
        q.setSessionId(sessionId);
        q.setEmpathyScore(BigDecimal.valueOf(empathy));
        q.setCbtCompletion(BigDecimal.valueOf(cbt));
        q.setSafetyCompliance(BigDecimal.valueOf(safety));
        q.setEngagementScore(BigDecimal.valueOf(engagement));
        return q;
    }

    @Test
    @DisplayName("无该版本会话 → (0, 0, 0.0)，不再查评分表")
    void noSessions_emptyStat() {
        when(sessionMapper.selectPage(any(), any())).thenReturn(new Page<CounselingSession>().setRecords(List.of()));

        PromptEvalScoreReader.EvalStat stat = reader.read("SYS_001:v2:control");

        assertEquals(0, stat.sessionCount());
        assertEquals(0, stat.scoredCount());
        assertEquals(0.0, stat.overallScore());
        verify(qualityScoreMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("有会话但无评分 → scoredCount=0（门禁视为样本不足）")
    void sessionsWithoutScores() {
        when(sessionMapper.selectPage(any(), any())).thenReturn(new Page<CounselingSession>().setRecords(List.of(session(UUID.randomUUID()))));
        when(qualityScoreMapper.selectList(any())).thenReturn(List.of());

        PromptEvalScoreReader.EvalStat stat = reader.read("SYS_001:v2:control");

        assertEquals(1, stat.sessionCount());
        assertEquals(0, stat.scoredCount());
    }

    @Test
    @DisplayName("overallScore = 四维均值再平均（与 ab-comparison 口径一致）")
    void overallIsMeanOfFourDimensions() {
        UUID sid = UUID.randomUUID();
        when(sessionMapper.selectPage(any(), any())).thenReturn(new Page<CounselingSession>().setRecords(List.of(session(sid))));
        // 四维：0.8 / 0.6 / 1.0 / 0.8 → 均值 0.8
        when(qualityScoreMapper.selectList(any())).thenReturn(
                List.of(score(sid, 0.8, 0.6, 1.0, 0.8)));

        PromptEvalScoreReader.EvalStat stat = reader.read("SYS_001:v2:control");

        assertEquals(1, stat.sessionCount());
        assertEquals(1, stat.scoredCount());
        assertEquals(0.8, stat.overallScore(), 1e-9);
    }

    @Test
    @DisplayName("多条评分取均值；null 维度跳过不拉低")
    void multipleScores_averaged() {
        UUID sid = UUID.randomUUID();
        when(sessionMapper.selectPage(any(), any())).thenReturn(new Page<CounselingSession>().setRecords(List.of(session(sid))));
        QualityScore q1 = score(sid, 0.8, 0.8, 0.8, 0.8);
        QualityScore q2 = score(sid, 1.0, 1.0, 1.0, 1.0);
        q2.setEmpathyScore(null); // null 维度跳过
        when(qualityScoreMapper.selectList(any())).thenReturn(List.of(q1, q2));

        PromptEvalScoreReader.EvalStat stat = reader.read("SYS_001:v2:control");

        assertEquals(2, stat.scoredCount());
        // empathy 均值 0.8（仅 q1），其余三维 0.9 → overall=(0.8+0.9+0.9+0.9)/4=0.875
        assertEquals(0.875, stat.overallScore(), 1e-9);
    }
}
