# 64 - ENCRYPTION_KEY 系统级启用开关设计

> 文档状态：**已实施（🟩 代码+测试+配置，2026-08-06）｜决策：不启用（2026-08-06）** | 创建：2026-08-06 | 关联：COMP-005（字段加密）、design/07 §5（数据加密与等保）
>
> 实施记录：FieldEncryptionService 构造器新增 `encryptionEnabled` 开关（默认 false）+ encrypt/decrypt 透传；FieldEncryptionServiceTest 适配新签名并新增开关用例（V1~V4 密钥矩阵）；application.yml/prod + 3 份 compose + .env.example 同步 `ENCRYPTION_ENABLED`；counseling-service 全量测试全绿（含 796 用例基线）。
>
> 需求来源：ENCRYPTION_KEY 专题提升——增加系统级启用开关；**默认不启用加密**（不配置即可运行，不做加解密）；启用时服务启动必须配置密钥并执行加解密。
>
> ⚠️ **决策（2026-08-06 项目负责人）：数据加密视为尚未接线，`ENCRYPTION_ENABLED=false` 为终态**。未主动提出该专题前，不再纳入任何分析、待办或迁移规划（含商业化阶段启用假设，与 frozen/60 的关联解除）。

---

## 1. 背景与问题

### 1.1 现状：加密行为与 profile 耦合

当前 `FieldEncryptionService` 的密钥配置行为（`backend/counseling-service/.../FieldEncryptionService.java` 45-66 行）：

| 场景 | 现状行为 |
|------|---------|
| prod profile + 密钥未配置 | **fail-fast** 抛 `IllegalStateException [FATAL]` 拒绝启动 |
| 非 prod + 密钥未配置 | WARN 降级明文透传 |
| 密钥已配置（任何 profile） | 执行 AES-256-GCM 加解密 |

问题：
1. **"是否加密"由 profile 隐式决定，无显式开关**——部署者无法表达"我要明文"或"我要加密"，只能通过 profile 间接控制
2. **prod 强制加密导致部署僵局**：新套 deploy 缺 ENCRYPTION_KEY → backend 崩溃循环 492 次（fail-fast 设计内行为，但缺少"现阶段不启用"的表达途径）
3. 试运行阶段（小规模试点）数据敏感性与商业化阶段不同，**需要分阶段策略**：试运行明文、商业化加密

### 1.2 用户需求（明确约束）

1. 增加**系统级启用开关**：`ENCRYPTION_ENABLED`
2. **不启用（默认）**：可以不配置 ENCRYPTION_KEY，服务不做加密也不做解密（纯透传）
3. **启用时**：服务启动**必须**配置密钥，并执行加解密（保持现有 fail-fast 语义）
4. **当前默认不启用**；启用加密与商业化阶段一并考虑（同步更新冻结商业化要求）

---

## 2. 设计方案

### 2.1 配置项（核心）

```yaml
# application.yml / application-prod.yml 同步
mindsafe:
  encryption:
    enabled: ${ENCRYPTION_ENABLED:false}   # 系统级开关，默认关闭
    key: ${MINDSAFE_ENCRYPTION_KEY:}        # 启用时必填：Base64 32 字节
    key-version: ${MINDSAFE_ENCRYPTION_KEY_VERSION:1}
    previous-keys: ${MINDSAFE_ENCRYPTION_PREVIOUS_KEYS:}  # 轮换期历史密钥
```

```bash
# .env / .env.example
# 数据字段加密开关：false=不启用（默认，不配置密钥即可运行，不做加解密）
# true=启用（启动必须配置 ENCRYPTION_KEY，否则拒绝启动）
# ⚠️ 启用加密属于商业化阶段要求（frozen/60 COMP-008），试运行期保持默认关闭
ENCRYPTION_ENABLED=false
ENCRYPTION_KEY=
ENCRYPTION_KEY_VERSION=1
ENCRYPTION_PREVIOUS_KEYS=
```

### 2.2 开关语义（唯一裁决者）

`mindsafe.encryption.enabled`（env `ENCRYPTION_ENABLED`）是**唯一开关**，与 profile 解耦：

| enabled | 密钥要求 | 加解密行为 | fail-fast |
|---------|---------|-----------|-----------|
| `false`（缺省/空/false） | **不要求**（可不配置） | encrypt/decrypt 纯透传（明文） | 无（启动不校验密钥） |
| `true` | **必须**：非空 + Base64 解码恰好 32 字节 | AES-256-GCM 加密 / 按版本解密 | 有：密钥缺失或非法 → 拒绝启动 |

**防呆**：enabled 缺省（false）但 key 已配置 → 启动 WARN 提示"密钥已配置但加密未启用（ENCRYPTION_ENABLED=false），数据以明文落库"——防止误配后静默明文。

### 2.3 行为变更矩阵（与现状对比）

| 场景 | 现状（改造前） | 改造后 |
|------|---------------|--------|
| prod + 无 key | fail-fast 崩溃 | **正常启动（明文）**——有意的阶段性决策 |
| 非 prod + 无 key | WARN 明文 | 正常启动（明文），行为一致 |
| enabled=true + 无 key（任何 profile） | （无此表达） | **fail-fast 拒绝启动** |
| enabled=true + key 非法（非 32 字节） | 启动抛 IllegalArgumentException | 同左（fail-fast） |
| enabled=true + 存量明文数据 | — | decrypt 无 `v` 前缀透传返回，历史数据可读，**加密仅对新写入生效**（现有逻辑天然支持） |
| enabled=true + 加密异常 | fail-fast 拒绝明文落库 | 不变（105-107 行） |

> 兼容性说明：加密字段数据格式（`v{version}:<base64>`）**不变**；密钥版本化/轮换机制（previous-keys）**不变**；服务间调用接口（encrypt/decrypt/isEncrypted/reEncrypt）**签名不变**，仅行为受开关控制。

### 2.4 代码改造

**2.4.1 FieldEncryptionService 构造器**（45-66 行重写）

```java
public FieldEncryptionService(
        @Value("${mindsafe.encryption.enabled:false}") boolean enabled,
        @Value("${mindsafe.encryption.key:}") String currentKey,
        @Value("${mindsafe.encryption.key-version:1}") int keyVersion,
        @Value("${mindsafe.encryption.previous-keys:}") String previousKeys,
        Environment environment) {

    this.enabled = enabled;
    this.activeKeyVersion = keyVersion;

    if (!enabled) {
        // 未启用：不校验密钥，加解密透传；密钥已配置则防呆告警
        if (currentKey != null && !currentKey.isBlank()) {
            log.warn("加密未启用（mindsafe.encryption.enabled=false）但检测到密钥已配置，数据将以明文落库。"
                    + "如需加密请设置 ENCRYPTION_ENABLED=true（商业化阶段要求，见 frozen/60 COMP-008）");
        }
        log.info("字段加密服务初始化: 未启用（明文模式）");
        return;
    }

    // 启用：fail-fast 校验密钥
    if (currentKey == null || currentKey.isBlank()) {
        throw new IllegalStateException(
            "[FATAL] 字段加密已启用（ENCRYPTION_ENABLED=true）但未配置 MINDSAFE_ENCRYPTION_KEY，"
            + "敏感字段将明文存储。请配置 Base64 编码的 32 字节密钥后重启。");
    }
    keyRegistry.put(keyVersion, buildKey(currentKey));
    log.info("字段加密服务初始化: 已启用, activeKeyVersion={}", keyVersion);

    // 注册历史密钥（不变）
    ...
}
```

**2.4.2 encrypt / decrypt 透传分支**

```java
public String encrypt(String plaintext) {
    if (!enabled || plaintext == null || plaintext.isBlank()) return plaintext;
    ...  // 原有 AES-GCM 逻辑不变
}

public String decrypt(String ciphertext) {
    if (!enabled || ciphertext == null || ciphertext.isBlank()) return ciphertext;
    ...  // 原有逻辑不变
}
```

**2.4.3 测试适配**（FieldEncryptionServiceTest 4 处构造调用）

```java
// 旧签名 → 新签名（enabled 显式传参）
new FieldEncryptionService(true, TEST_KEY_BASE64, 1, "", new StandardEnvironment());  // 加密场景
new FieldEncryptionService(false, "", 1, "", new StandardEnvironment());              // 明文场景
```

新增用例：enabled=true + key 空 → 断言 IllegalStateException；enabled=false + key 已配置 → 断言 WARN 且透传。

### 2.5 改动文件清单

| 文件 | 改动 |
|------|------|
| `backend/counseling-service/.../security/FieldEncryptionService.java` | 构造器加 enabled 开关；encrypt/decrypt 透传分支；fail-fast 由"prod 判断"改为"enabled 判断"；防呆 WARN |
| `backend/counseling-app/src/main/resources/application.yml` | mindsafe.encryption.enabled: ${ENCRYPTION_ENABLED:false} |
| `backend/counseling-app/src/main/resources/application-prod.yml` | 同上 |
| `deploy/docker-compose.prod.yml` | backend env 透传 `ENCRYPTION_ENABLED: ${ENCRYPTION_ENABLED:-false}` |
| `deploy/.env.example` | 新增 ENCRYPTION_ENABLED=false 条目 + 商业化说明注释 |
| `backend/counseling-service/src/test/.../FieldEncryptionServiceTest.java` | 构造调用适配 + 新增开关用例 |
| 文档：`design/frozen/60`、`design/07` §5 | 见 §4 |

---

## 3. Spec（验收标准，EARS 风格）

| ID | 类型 | 验收标准 |
|----|------|---------|
| ENC-001 | 必须 | `ENCRYPTION_ENABLED` 缺省/空/`false` 时（任何 profile），服务正常启动，不要求配置 `ENCRYPTION_KEY`，encrypt/decrypt 对明文透传（不产生 `v` 前缀密文，解密无前缀输入原样返回） |
| ENC-002 | 必须 | `ENCRYPTION_ENABLED=true` 且密钥未配置或非空但 Base64 解码非 32 字节时，服务启动抛 `IllegalStateException [FATAL]` 拒绝启动，错误信息指明需配置 `MINDSAFE_ENCRYPTION_KEY` |
| ENC-003 | 必须 | `ENCRYPTION_ENABLED=true` 且密钥合法时，写路径产出 `v{version}:<base64>` 密文（AES-256-GCM，随机 IV），读路径按密文版本前缀选密钥解密，行为与改造前完全一致 |
| ENC-004 | 应该 | `ENCRYPTION_ENABLED` 缺省（false）但 `ENCRYPTION_KEY` 已配置时，启动输出 WARN 日志提示"密钥已配置但加密未启用，数据明文落库" |
| ENC-005 | 必须 | `ENCRYPTION_ENABLED=true` 切换前已落库的明文数据（无 `v` 前缀）decrypt 原样返回，不报错不丢数据；加密仅对切换后新写入生效 |
| ENC-006 | 必须 | `FieldEncryptionServiceTest` 全量适配新构造签名并新增 ENC-001/002/004 对应用例；`mvn test`（counseling-service 模块）全绿 |
| ENC-007 | 必须 | 密钥版本化与轮换机制（`ENCRYPTION_KEY_VERSION` / `ENCRYPTION_PREVIOUS_KEYS`）在 enabled=true 下功能不变（v2 密文可解、reEncrypt 可轮换） |
| ENC-008 | 应该 | `docker-compose.prod.yml` 透传 `ENCRYPTION_ENABLED`；`.env.example` 提供开关条目与商业化阶段说明注释，默认 `false` |

---

## 4. 商业化阶段联动（同步冻结要求）

### 4.1 登记：frozen/60 商用发布合规与备案

冻结跟踪表新增：

```
| COMP-008 | **数据加密启用**（ENCRYPTION_ENABLED=true + 密钥治理：CSPRNG 生成/离线备份/轮换演练/.env 权限） | 项目负责人 | 商用发布决策；代码开关已就绪（doing/64） | doing/64、design/07 §5 | 🔒 冻结（试运行期默认明文，商业化发布时解锁） |
```

解冻触发：并入 frozen/60 §3.1 触发链第④步"商用发布决策"后执行，纳入发布 checklist。

### 4.2 更新：design/07 商业化实施合规 §5 合规硬约束映射

"数据加密与等保"行追加：

```
| 数据加密与等保 | FieldEncryptionService（AES-256-GCM 服务层）覆盖 message_summaries/teacher_notes；student_profiles/long_term_memories 待加密；**加密启用开关 ENCRYPTION_ENABLED 默认关闭（试运行期明文），商业化发布时开启（frozen/60 COMP-008）** | 🟫 |
```

### 4.3 商业化解锁时执行清单（预登记）

1. `ENCRYPTION_ENABLED=true` 写入生产 .env
2. `openssl rand -base64 32` 生成密钥 → 写入 `ENCRYPTION_KEY` → **离线备份**（密钥丢失 = 历史密文不可恢复）
3. 密钥轮换演练：`ENCRYPTION_PREVIOUS_KEYS` 装载旧密钥 → 重启 → reEncrypt 存量数据 → 移除旧版本
4. 服务器 `.env` 权限 `chmod 600`；密钥访问审计
5. 存量明文数据兼容验证（ENC-005）：启用后历史数据可读、增量加密

---

## 5. 回归验证

1. `mvn test`（counseling-service 模块，FieldEncryptionServiceTest + ConversationServiceImplTest 等加密消费点回归）
2. 明文模式冒烟：`ENCRYPTION_ENABLED=false` 启动 → 对话摘要/老师备注落库可见明文（无 `v` 前缀）
3. 加密模式冒烟：`ENCRYPTION_ENABLED=true` + 合法 key 启动 → 落库 `v1:` 前缀密文 → 读路径正常解密
4. fail-fast 验证：enabled=true + key 空 → 启动拒绝；enabled=true + key 非 32 字节 → 启动拒绝
5. 存量兼容验证：明文库数据在 enabled=true 下可正常读取
6. 部署侧：`docker compose config` 校验透传不报错

---

## 6. 待确认决策点

| # | 决策点 | 建议 | 说明 |
|---|--------|------|------|
| D1 | enabled 缺省语义（含 prod profile）= false | ✅ 采纳（用户已明确） | 试运行期明文是有意的阶段性决策；旧"prod 强制加密"行为废除，由显式开关替代 |
| D2 | 防呆 WARN（key 配置了但未启用） | 保留 | 防止误配静默明文，零成本 |
| D3 | 构造器签名变更（加 enabled 参数） | 采纳（测试同步适配） | 仅 FieldEncryptionServiceTest 4 处调用，无其他生产代码构造 |
| D4 | 商业化解锁由 frozen/60 COMP-008 登记跟踪 | 采纳 | 与既有冻结治理体系一致 |

### 6.1 登记：版本化变量纳入（2026-08-06 用户确认，待集中处理）

`ENCRYPTION_KEY_VERSION` / `ENCRYPTION_PREVIOUS_KEYS` 统一纳入本专题，与 64 实施**集中处理**（不单独改动）。分析结论与待办 4 项：

| # | 待办 | 涉及位置 |
|---|------|---------|
| V1 | enabled=false 时两个变量**忽略语义显式化**（不解析/不校验/不注册，防部署者困惑"配了为什么没生效"） | §2.2 开关语义表补一行 |
| V2 | enabled=true 时 previous-keys 非法条目（非 `version:base64key` 或 key 非 32 字节）**fail-fast 保留**并写入 spec | §3 ENC-007 细化 |
| V3 | **版本冲突覆盖顺序**注明：previous-keys 不得包含与 key-version 相同版本号（现状静默覆盖，不改变逻辑仅文档警示） | §2.4.1 注释 + §3 |
| V4 | 测试适配补全：v2 轮换用例（v2Service）传 enabled 参数 + 新增 `enabled=false + previous-keys 非法` → 不抛错用例 | §2.4.3 |

顺带完善（非必须，集中处理时一并）：`.env.example` 中 VERSION/PREVIOUS_KEYS 注释补"仅 ENCRYPTION_ENABLED=true 时生效"；§4.3 商业化解锁清单交叉引用 ENC-007。

> 明确不做：不新增开关/变量、不改密文格式与 reEncrypt 轮换机制、不为 enabled=false 做版本化校验（符合"不启用不做加解密"原则）。

---

## 7. 参考

- COMP-005 字段加密原始设计（AES-256-GCM + 密钥版本化）
- `backend/counseling-service/src/main/java/com/mindsafe/service/security/FieldEncryptionService.java`
- `design/frozen/60_商用发布合规与备案.md`（冻结跟踪载体）
- `design/07_商业化实施合规专题方案.md` §5（合规硬约束映射）
