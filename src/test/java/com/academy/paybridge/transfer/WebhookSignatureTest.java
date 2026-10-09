package com.academy.paybridge.transfer;

import com.academy.paybridge.transfer.gateway.PaystackProperties;
import com.academy.paybridge.transfer.web.PaystackWebhookController;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookSignatureTest {

    private static String sign(String secret, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
        return HexFormat.of().formatHex(mac.doFinal(body));
    }

    @Test
    void acceptsOnlyTheCorrectSignatureOfTheExactBody() throws Exception {
        PaystackWebhookController controller = new PaystackWebhookController(
                new PaystackProperties("https://example.test", "sk_test_secret", 1000, 1000), null, null, null);
        byte[] body = "{\"event\":\"transfer.success\",\"data\":{\"reference\":\"trf_abc\"}}".getBytes(StandardCharsets.UTF_8);

        assertThat(controller.validSignature(body, sign("sk_test_secret", body))).isTrue();
        assertThat(controller.validSignature(body, sign("a_different_secret", body))).isFalse();
        assertThat(controller.validSignature("{}".getBytes(StandardCharsets.UTF_8), sign("sk_test_secret", body))).isFalse();
        assertThat(controller.validSignature(body, null)).isFalse();
        assertThat(controller.validSignature(body, "")).isFalse();
    }

    @Test
    void rejectsEverythingWhenNoSecretIsConfigured() throws Exception {
        PaystackWebhookController controller = new PaystackWebhookController(
                new PaystackProperties("https://example.test", "", 1000, 1000), null, null, null);
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        assertThat(controller.validSignature(body, "0123abcd")).isFalse();
    }
}
