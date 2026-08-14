package com.mindsafe.api.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 设备上报签名校验注解（99-6，2026-08-14）——替代手写三 header + 序列化样板。
 * <p>
 * 标注在需要签名校验的 {@code @RequestBody} 参数上（替代 @RequestBody 语义）：
 * 由 {@link DeviceSignatureArgumentResolver} 读取原始请求体（canonical body）执行
 * HMAC 校验（X-Device-Signature/Timestamp/Nonce，规范 frozen/73 §九），再反序列化为
 * 参数类型返回。签名模式（OFF/LOG/ENFORCE）由 mindsafe.device.signature-mode 控制。
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface DeviceSignature {
}
