package com.qtsurfer.api.sdk;

import com.qtsurfer.api.client.model.SweepAxis;
import com.qtsurfer.api.client.model.SweepAxisOneOf;
import com.qtsurfer.api.client.model.SweepAxisOneOf1;
import com.qtsurfer.api.client.model.SweepAxisOneOf1ValuesInner;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One strategy property and the values a sweep should try for it: either a
 * numeric {@link Range}, or an explicit {@link Values} list.
 *
 * <pre>{@code
 * Map<String, ParamAxis> params = Map.of(
 *     "rsiPeriod",      ParamAxis.range(7, 28, 1),
 *     "useTrendFilter", ParamAxis.of(true, false));
 * }</pre>
 *
 * <p>A sealed type rather than two builder methods, because the two shapes are
 * mutually exclusive on the wire and mixing them is a request the platform
 * rejects rather than reconciles.
 */
public sealed interface ParamAxis permits ParamAxis.Range, ParamAxis.Values {

    /**
     * A numeric range walked in fixed steps, {@code from} through {@code to}.
     *
     * @param from  first value
     * @param to    last value the walk may reach
     * @param step  increment; must be greater than zero
     */
    record Range(double from, double to, double step) implements ParamAxis {
        /** @throws IllegalArgumentException when {@code step} is not positive */
        public Range {
            if (!(step > 0)) {
                throw new IllegalArgumentException("step must be > 0, was " + step);
            }
        }
    }

    /**
     * An explicit list of values. Each entry is a number or a boolean — the
     * axis of a boolean flag is {@code [true, false]}, not a range.
     *
     * @param values the values to try; at least one
     */
    record Values(List<Object> values) implements ParamAxis {
        /** @throws IllegalArgumentException when the list is empty or holds an unsupported type */
        public Values {
            Objects.requireNonNull(values, "values");
            if (values.isEmpty()) {
                throw new IllegalArgumentException("values must not be empty");
            }
            for (Object v : values) {
                if (!(v instanceof Number) && !(v instanceof Boolean)) {
                    throw new IllegalArgumentException(
                            "values may only hold numbers and booleans, got "
                                    + (v == null ? "null" : v.getClass().getName()));
                }
            }
            values = List.copyOf(values);
        }
    }

    /**
     * A numeric range walked in fixed steps.
     *
     * @param from first value
     * @param to   last value the walk may reach
     * @param step increment; must be greater than zero
     * @return the axis
     */
    static ParamAxis range(double from, double to, double step) {
        return new Range(from, to, step);
    }

    /**
     * An explicit list of numeric values.
     *
     * @param values the values to try; at least one
     * @return the axis
     */
    static ParamAxis of(Number... values) {
        return new Values(List.of((Object[]) values));
    }

    /**
     * An explicit list of boolean values — typically {@code of(true, false)}
     * to sweep a flag both ways.
     *
     * @param values the values to try; at least one
     * @return the axis
     */
    static ParamAxis of(Boolean... values) {
        return new Values(List.of((Object[]) values));
    }

    /**
     * Internal: the api-client representation of this axis. Exposed so the
     * SDK's own helpers can pass it through without re-encoding.
     *
     * @return the generated {@code SweepAxis} for this axis
     */
    default SweepAxis wire() {
        if (this instanceof Range r) {
            return new SweepAxis(new SweepAxisOneOf()
                    .from(r.from())
                    .to(r.to())
                    .step(r.step()));
        }
        Values v = (Values) this;
        List<SweepAxisOneOf1ValuesInner> items = new ArrayList<>(v.values().size());
        for (Object value : v.values()) {
            items.add(value instanceof Boolean b
                    ? new SweepAxisOneOf1ValuesInner(b)
                    : new SweepAxisOneOf1ValuesInner(new BigDecimal(value.toString())));
        }
        return new SweepAxis(new SweepAxisOneOf1().values(items));
    }
}
