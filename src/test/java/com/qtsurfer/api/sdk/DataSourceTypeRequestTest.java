package com.qtsurfer.api.sdk;

import com.qtsurfer.api.client.model.DataSourceType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The data type a run reads: ticker unless a request says otherwise. */
class DataSourceTypeRequestTest {

    private static BacktestRequest.Builder backtest() {
        return BacktestRequest.builder().strategy("class S {}").exchangeId("binance")
                .instrument("BTC/USDT").from("2026-01-01").to("2026-01-02");
    }

    private static SweepRequest.Builder sweep() {
        return SweepRequest.builder().strategy("class S {}").exchangeId("binance")
                .instrument("BTC/USDT").from("2026-01-01").to("2026-01-02")
                .param("rsiPeriod", ParamAxis.range(7, 28, 1));
    }

    @Test
    void aBacktestWithoutATypeIsATickerRun() {
        assertEquals(DataSourceType.TICKER, backtest().build().type());
    }

    @Test
    void aBacktestKeepsTheTypeItIsGiven() {
        assertEquals(DataSourceType.KLINE, backtest().type(DataSourceType.KLINE).build().type());
    }

    @Test
    void aSweepWithoutATypeIsATickerSweep() {
        assertEquals(DataSourceType.TICKER, sweep().build().type());
    }

    @Test
    void aSweepKeepsTheTypeItIsGiven() {
        assertEquals(DataSourceType.KLINE, sweep().type(DataSourceType.KLINE).build().type());
    }

    @Test
    void theConstructorsThatPredateTheTypeStayTickerRuns() {
        BacktestRequest viaEleven = new BacktestRequest("class S {}", "binance", "BTC/USDT", "2026-01-01", "2026-01-02",
                null, null, null, null, null, Map.of());
        assertEquals(DataSourceType.TICKER, viaEleven.type());

        SweepRequest viaFifteen = new SweepRequest("class S {}", "binance", "BTC/USDT", "2026-01-01", "2026-01-02",
                Map.of("rsiPeriod", ParamAxis.range(7, 28, 1)), null, null, null, null, null, null, null, null, null);
        assertEquals(DataSourceType.TICKER, viaFifteen.type());
    }

    @Test
    void aNullTypeInTheCanonicalConstructorMeansTicker() {
        BacktestRequest request = new BacktestRequest("class S {}", "binance", "BTC/USDT", "2026-01-01", "2026-01-02",
                null, null, null, null, null, Map.of(), null);
        assertEquals(DataSourceType.TICKER, request.type());
    }
}
