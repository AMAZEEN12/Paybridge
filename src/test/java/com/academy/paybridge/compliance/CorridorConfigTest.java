package com.academy.paybridge.compliance;

import com.academy.paybridge.compliance.api.CorridorApi;
import com.academy.paybridge.compliance.service.CorridorProperties;
import com.academy.paybridge.support.TestDb;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The shipped paybridge.corridors block loads and behaves as R-COR-3 says. */
@SpringBootTest
@ActiveProfiles("test")
class CorridorConfigTest {

    @Autowired
    CorridorProperties props;

    @Autowired
    CorridorApi corridors;

    @BeforeAll
    static void needDatabase() {
        assumeTrue(TestDb.available(), "No test database. Create one to run these tests.");
    }

    @Test
    void theShippedRulesLoadInOrderWithNigeriaOutboundOn() {
        assertThat(props.rules()).hasSize(3);
        assertThat(props.rules().get(0)).isEqualTo(new CorridorProperties.Rule("NG", "*", true));
        assertThat(props.rules().get(1)).isEqualTo(new CorridorProperties.Rule("*", "NG", true));
        assertThat(props.rules().get(2)).isEqualTo(new CorridorProperties.Rule("*", "*", true));
    }

    @Test
    void theBeanAnswersFromTheShippedTable() {
        assertThat(corridors.check("NG", "GB").allowed()).isTrue();
        assertThat(corridors.check("GB", "NG").allowed()).isTrue();
        assertThat(corridors.check("NG", null).allowed()).isFalse();
    }
}