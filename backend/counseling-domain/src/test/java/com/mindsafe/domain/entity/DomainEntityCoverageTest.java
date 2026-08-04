package com.mindsafe.domain.entity;

import com.mindsafe.domain.handler.UuidTypeHandler;
import com.mindsafe.domain.typehandler.JsonbTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 领域实体与 MyBatis 类型处理器覆盖率补缺（counseling-domain 冲刺 80%）
 * <p>
 * 1. 反射遍历全部 21 个实体的 setter/getter 往返读写
 * 2. UuidTypeHandler / JsonbTypeHandler 全路径（set/get/columnIndex/columnName/CallableStatement）
 */
class DomainEntityCoverageTest {

    // ===== 实体 setter/getter 反射往返 =====

    private static final Map<Class<?>, Object> VALUES = new HashMap<>();

    static {
        VALUES.put(String.class, "v");
        VALUES.put(UUID.class, UUID.randomUUID());
        VALUES.put(Instant.class, Instant.now());
        VALUES.put(Integer.class, 1);
        VALUES.put(Long.class, 1L);
        VALUES.put(Boolean.class, true);
        VALUES.put(BigDecimal.class, BigDecimal.ONE);
        VALUES.put(Float.class, 1.0f);
        VALUES.put(LocalDate.class, LocalDate.now());
    }

    @Test
    @DisplayName("全部 21 个实体 setter→getter 往返一致")
    void allEntities_roundTrip() throws Exception {
        List<Class<?>> entities = List.of(
                User.class, RiskEvent.class, Notification.class, MessageSummary.class,
                CounselingSession.class, TeacherNote.class, AuditLog.class, EmotionDiary.class,
                LongTermMemory.class, ModelCallLog.class, PromptVersion.class, ConsentRecord.class,
                RelaxationSession.class, TrialInviteCode.class, StudentProfile.class, QualityScore.class,
                School.class, ParentAccount.class, Tenant.class, ParentStudentLink.class,
                VoiceprintEmbedding.class);

        int checked = 0;
        for (Class<?> clazz : entities) {
            Object bean = clazz.getDeclaredConstructor().newInstance();
            for (Method setter : clazz.getMethods()) {
                String name = setter.getName();
                if (!name.startsWith("set") || setter.getParameterCount() != 1) {
                    continue;
                }
                Class<?> type = setter.getParameterTypes()[0];
                Object value = VALUES.get(type);
                if (value == null) {
                    continue;
                }
                setter.invoke(bean, value);
                Method getter = findGetter(clazz, name.substring(3));
                if (getter != null) {
                    assertEquals(value, getter.invoke(bean), clazz.getSimpleName() + "." + name + " 往返不一致");
                    checked++;
                }
            }
        }
        // 至少覆盖 150 个属性对，防止未来实体新增字段后此测试静默缩水
        assertTrue(checked >= 150, "实体属性覆盖对过少: " + checked);
    }

    private Method findGetter(Class<?> clazz, String prop) {
        try {
            return clazz.getMethod("get" + prop);
        } catch (NoSuchMethodException e) {
            try {
                return clazz.getMethod("is" + prop);
            } catch (NoSuchMethodException e2) {
                return null;
            }
        }
    }

    // ===== JDBC 接口动态代理（零 Mockito 依赖） =====

    /**
     * 构造 JDBC 接口代理：getXxx 从 reads 按参数取值；void 调用记录到 calls
     */
    private static Object jdbcProxy(Class<?> iface, Map<Object, Object> reads, List<Object[]> calls) {
        return java.lang.reflect.Proxy.newProxyInstance(
                iface.getClassLoader(), new Class<?>[]{iface},
                (proxy, method, args) -> {
                    if (method.getReturnType() == void.class) {
                        if (calls != null) {
                            calls.add(new Object[]{method.getName(), args});
                        }
                        return null;
                    }
                    if (args != null && args.length == 1 && reads.containsKey(args[0])) {
                        return reads.get(args[0]);
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == long.class) return 0L;
                    return null;
                });
    }

    // ===== UuidTypeHandler =====

    @Test
    @DisplayName("UuidTypeHandler: set + 三条 get 路径 + null + 字符串转换")
    void uuidHandler_allPaths() throws Exception {
        UuidTypeHandler h = new UuidTypeHandler();
        UUID uuid = UUID.randomUUID();
        List<Object[]> calls = new ArrayList<>();

        PreparedStatement ps = (PreparedStatement) jdbcProxy(PreparedStatement.class, Map.of(), calls);
        h.setNonNullParameter(ps, 1, uuid, JdbcType.OTHER);
        assertEquals(1, calls.size());
        assertEquals("setObject", calls.get(0)[0]);
        assertEquals(1, ((Object[]) calls.get(0)[1])[0]);
        assertEquals(uuid, ((Object[]) calls.get(0)[1])[1]);

        // 按列名读取：返回 UUID 直通
        ResultSet rsName = (ResultSet) jdbcProxy(ResultSet.class, Map.of("id", uuid), null);
        assertEquals(uuid, h.getNullableResult(rsName, "id"));

        // 按下标读取：String 转 UUID
        ResultSet rsIndex = (ResultSet) jdbcProxy(ResultSet.class, Map.of(1, uuid.toString()), null);
        assertEquals(uuid, h.getNullableResult(rsIndex, 1));

        // 返回 null
        Map<Object, Object> nullRead = new HashMap<>();
        nullRead.put(1, null);
        ResultSet rsNull = (ResultSet) jdbcProxy(ResultSet.class, nullRead, null);
        assertNull(h.getNullableResult(rsNull, 1));

        // CallableStatement 路径
        CallableStatement cs = (CallableStatement) jdbcProxy(CallableStatement.class, Map.of(2, uuid), null);
        assertEquals(uuid, h.getNullableResult(cs, 2));
    }

    // ===== JsonbTypeHandler =====

    @Test
    @DisplayName("JsonbTypeHandler: set 包装 PGobject + 三条 get 路径")
    void jsonbHandler_allPaths() throws Exception {
        JsonbTypeHandler h = new JsonbTypeHandler();
        String json = "{\"a\":1}";
        List<Object[]> calls = new ArrayList<>();

        PreparedStatement ps = (PreparedStatement) jdbcProxy(PreparedStatement.class, Map.of(), calls);
        h.setNonNullParameter(ps, 1, json, JdbcType.OTHER);
        Object[] setArgs = (Object[]) calls.get(0)[1];
        assertEquals("setObject", calls.get(0)[0]);
        assertTrue(setArgs[1] instanceof PGobject, "应包装为 PGobject");
        PGobject pg = (PGobject) setArgs[1];
        assertEquals("jsonb", pg.getType());
        assertEquals(json, pg.getValue());

        // 按列名读取
        ResultSet rsName = (ResultSet) jdbcProxy(ResultSet.class, Map.of("meta", json), null);
        assertEquals(json, h.getNullableResult(rsName, "meta"));

        // 按下标读取 + null
        ResultSet rsIndex = (ResultSet) jdbcProxy(ResultSet.class, Map.of(1, "{\"b\":2}"), null);
        assertEquals("{\"b\":2}", h.getNullableResult(rsIndex, 1));
        Map<Object, Object> nullRead = new HashMap<>();
        nullRead.put(1, null);
        ResultSet rsNull = (ResultSet) jdbcProxy(ResultSet.class, nullRead, null);
        assertNull(h.getNullableResult(rsNull, 1));
        CallableStatement cs = (CallableStatement) jdbcProxy(CallableStatement.class, Map.of(2, json), null);
        assertEquals(json, h.getNullableResult(cs, 2));
    }
}
