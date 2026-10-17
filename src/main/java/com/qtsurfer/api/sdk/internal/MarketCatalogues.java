package com.qtsurfer.api.sdk.internal;

import com.qtsurfer.api.client.model.InstrumentCatalogueResponse;
import com.qtsurfer.api.client.model.InstrumentDetail;
import com.qtsurfer.api.client.model.InstrumentListResponse;
import com.qtsurfer.api.client.model.PoolDetail;
import com.qtsurfer.api.client.model.PoolListResponse;
import com.qtsurfer.api.sdk.errors.QTSError;

import java.util.List;

/** Preserves each venue's typed catalogue instead of coercing pool data into CEX instruments. */
public final class MarketCatalogues {
    private MarketCatalogues() {}

    /** @return CEX instruments, rejecting a pool catalogue with actionable recovery advice */
    public static List<InstrumentDetail> instruments(InstrumentCatalogueResponse response) {
        if (response.getActualInstance() instanceof InstrumentListResponse instruments) {
            return instruments.getData();
        }
        throw new QTSError("This venue returns DEX pools; use getPools(exchangeId, segment)");
    }

    /** @return DEX pools, rejecting a CEX catalogue */
    public static List<PoolDetail> pools(InstrumentCatalogueResponse response) {
        if (response.getActualInstance() instanceof PoolListResponse pools) {
            return pools.getData();
        }
        throw new QTSError("This venue returns CEX instruments; use getInstruments(exchangeId, segment)");
    }
}
