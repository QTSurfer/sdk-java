package com.qtsurfer.api.sdk.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qtsurfer.api.client.model.ResultMap;
import com.qtsurfer.api.client.model.ScalarStrategyParamValue;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * The generated client reads {@code ScalarStrategyParamValue} (number, string or boolean) by
 * requiring exactly one member to accept the JSON value. With scalar coercion on, a number also
 * fits a string and a boolean, and a run given a numeric or boolean {@code params} value could
 * not be read back.
 */
class ApiClientsTest {

    private final ObjectMapper mapper = ApiClients.create().getObjectMapper();

    private Object read(String json) throws Exception {
        return mapper.readValue(json, ScalarStrategyParamValue.class).getActualInstance();
    }

    @Test
    void aWholeNumberIsANumber() throws Exception {
        BigDecimal value = assertInstanceOf(BigDecimal.class, read("20"));
        assertEquals(0, new BigDecimal("20").compareTo(value));
    }

    @Test
    void aDecimalIsANumber() throws Exception {
        BigDecimal value = assertInstanceOf(BigDecimal.class, read("20.5"));
        assertEquals(0, new BigDecimal("20.5").compareTo(value));
    }

    @Test
    void aBooleanIsABoolean() throws Exception {
        assertEquals(Boolean.TRUE, assertInstanceOf(Boolean.class, read("true")));
        assertEquals(Boolean.FALSE, assertInstanceOf(Boolean.class, read("false")));
    }

    @Test
    void aStringIsAStringEvenWhenItLooksLikeANumberOrABoolean() throws Exception {
        assertEquals("abc", assertInstanceOf(String.class, read("\"abc\"")));
        assertEquals("20", assertInstanceOf(String.class, read("\"20\"")));
        assertEquals("true", assertInstanceOf(String.class, read("\"true\"")));
    }

    @Test
    void aResultThatEchoesMixedParamsIsReadable() throws Exception {
        ResultMap result = mapper.readValue("""
                {"instrument":"BTC/USDT","totalTrades":72,
                 "params":{"cycle.seconds":20,"threshold":0.5,"enabled":true,"label":"x"}}""", ResultMap.class);

        var params = result.getParams();
        assertEquals(4, params.size());
        assertInstanceOf(BigDecimal.class, params.get("cycle.seconds").getActualInstance());
        assertInstanceOf(BigDecimal.class, params.get("threshold").getActualInstance());
        assertEquals(Boolean.TRUE, params.get("enabled").getActualInstance());
        assertEquals("x", params.get("label").getActualInstance());
    }
}
