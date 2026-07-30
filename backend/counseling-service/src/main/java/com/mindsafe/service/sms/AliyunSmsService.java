package com.mindsafe.service.sms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 阿里云短信服务实现（生产环境）
 * <p>
 * 激活条件：mindsafe.sms.provider=aliyun
 * 使用阿里云 Dysmsapi 2017-05-25 版本，V3 签名（ACS3-HMAC-SHA256）。
 * 无额外 SDK 依赖，基于 Java 21 HttpClient。
 */
@Service
@ConditionalOnProperty(name = "mindsafe.sms.provider", havingValue = "aliyun")
public class AliyunSmsService implements SmsService {

    private static final Logger log = LoggerFactory.getLogger(AliyunSmsService.class);
    private static final String ENDPOINT = "https://dysmsapi.aliyuncs.com";
    private static final DateTimeFormatter ISO8601 = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

    @Value("${mindsafe.sms.aliyun.access-key-id}")
    private String accessKeyId;

    @Value("${mindsafe.sms.aliyun.access-key-secret}")
    private String accessKeySecret;

    @Value("${mindsafe.sms.aliyun.sign-name}")
    private String signName;

    @Value("${mindsafe.sms.aliyun.template-code}")
    private String templateCode;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * 启动校验（R-04）：选定 aliyun 提供商时四项凭证必须非空，否则 fail-fast。
     * <p>避免生产环境因凭证缺失导致短信静默发送失败（家长无法验证身份）。
     */
    @PostConstruct
    void validateCredentials() {
        StringBuilder missing = new StringBuilder();
        if (isBlank(accessKeyId)) { missing.append(" access-key-id"); }
        if (isBlank(accessKeySecret)) { missing.append(" access-key-secret"); }
        if (isBlank(signName)) { missing.append(" sign-name"); }
        if (isBlank(templateCode)) { missing.append(" template-code"); }
        if (missing.length() > 0) {
            throw new IllegalStateException(
                    "[FATAL] 短信提供商为 aliyun 但以下凭证未配置：" + missing.toString().trim()
                    + "。请配置 MINDSAFE_SMS_ALIYUN_* 环境变量，或将 MINDSAFE_SMS_PROVIDER 改为 logging。");
        }
        log.info("[SMS] 阿里云短信服务已启用 | signName={} | templateCode={}", signName, templateCode);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    @Override
    public boolean sendVerificationCode(String phone, String code, String purpose) {
        try {
            String body = buildRequestBody(phone, code);
            String date = ZonedDateTime.now(ZoneOffset.UTC).format(ISO8601);
            String nonce = UUID.randomUUID().toString();

            String hashedBody = sha256Hex(body);
            String canonicalRequest = "POST\n/\n\ncontent-type:application/json\nhost:dysmsapi.aliyuncs.com\n"
                    + "x-acs-action:SendSms\nx-acs-content-sha256:" + hashedBody + "\n"
                    + "x-acs-date:" + date + "\nx-acs-signature-nonce:" + nonce + "\nx-acs-version:2017-05-25\n\n"
                    + "content-type;host;x-acs-action;x-acs-content-sha256;x-acs-date;x-acs-signature-nonce;x-acs-version\n"
                    + hashedBody;

            String stringToSign = "ACS3-HMAC-SHA256\n" + sha256Hex(canonicalRequest);
            String signature = hmacSha256Hex(accessKeySecret, stringToSign);

            String authorization = "ACS3-HMAC-SHA256 Credential=" + accessKeyId
                    + ",SignedHeaders=content-type;host;x-acs-action;x-acs-content-sha256;x-acs-date;x-acs-signature-nonce;x-acs-version"
                    + ",Signature=" + signature;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT))
                    .header("Content-Type", "application/json")
                    .header("Host", "dysmsapi.aliyuncs.com")
                    .header("x-acs-action", "SendSms")
                    .header("x-acs-version", "2017-05-25")
                    .header("x-acs-date", date)
                    .header("x-acs-signature-nonce", nonce)
                    .header("x-acs-content-sha256", hashedBody)
                    .header("Authorization", authorization)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200 && response.body().contains("\"OK\"")) {
                log.info("[SMS] 验证码发送成功 | phone={}", maskPhone(phone));
                return true;
            } else {
                log.error("[SMS] 发送失败 | phone={} | status={} | body={}", maskPhone(phone), response.statusCode(), response.body());
                return false;
            }
        } catch (Exception e) {
            log.error("[SMS] 发送异常 | phone={}", maskPhone(phone), e);
            return false;
        }
    }

    private String buildRequestBody(String phone, String code) {
        // 手动构建 JSON（避免引入额外依赖）
        return "{\"PhoneNumbers\":\"" + phone + "\","
                + "\"SignName\":\"" + signName + "\","
                + "\"TemplateCode\":\"" + templateCode + "\","
                + "\"TemplateParam\":\"{\\\"code\\\":\\\"" + code + "\\\"}\"}";
    }

    private String sha256Hex(String data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(data.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }

    private String hmacSha256Hex(String key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) return "***";
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
