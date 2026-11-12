package com.japaneselearning.jwks.metrics;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetricsListenerAddressTest {
    @ParameterizedTest
    @ValueSource(strings = {"0.0.0.0", "127.0.0.1"})
    void acceptsSupportedListenerAddress(String address) {
        var connector = new MetricsEndpointConfiguration()
                .metricsConnector(9091, address);

        assertThat(connector.getProperty("address").toString()).endsWith(address);
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid-address"})
    void rejectsUnsupportedListenerAddress(String address) {
        var configuration = new MetricsEndpointConfiguration();

        assertThatThrownBy(() -> configuration.metricsConnector(9091, address))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
