# Strategies and validation

This guide covers registering and validating source through the SDK. For the Java strategy itself —
indicators, `emitBuy`/`emitSell`, information signals, order configuration, and chart metadata —
use the API's [Coding Java strategies](https://qtsurfer.github.io/docs/strategy_coding.html) guide.
For agent-assisted authoring, install the maintained
[`qtsurfer-java-strategy`](https://github.com/QTSurfer/strategy-skills) skill.

## Compile and validate

`compile` registers the supplied strategy source and returns a `Strategy`. Validate it before
spending a backtest: validation instantiates the compiled strategy and drives a bounded synthetic
series.

```java
import com.qtsurfer.api.sdk.ValidationOutcome;

var strategy = qts.compile(source).join();
ValidationOutcome outcome = qts.validateStrategy(strategy.id());

if (outcome instanceof ValidationOutcome.NotQueued existing) {
    System.out.println(existing.state().getValidation());
}
```

Validation is idempotent. `Queued` means this call started work; `NotQueued` only means that no new
work was required. It is not a passed verdict: read `strategyState(strategyId)` and poll on your own
schedule while its validation is `PENDING`. A check can stall, so callers should use a deadline.

```java
import com.qtsurfer.api.client.model.StrategyState;

StrategyState state = qts.strategyState(strategy.id());
switch (state.getValidation()) {
    case PASSED -> System.out.println("Validation passed");
    case FAILED -> System.out.println(state.getDetail());
    case PENDING, NOT_VALIDATED -> System.out.println("No final verdict yet");
}
```

`PASSED` means only that the class loaded and survived the bounded check; it is not a guarantee of
trading performance or safety. Recompiling supersedes a prior verdict.

## Manage registered strategies

```java
var mine = qts.listStrategies();       // Empty when none are registered.
String source = qts.getStrategyCode(strategyId);
qts.deleteStrategy(strategyId);
```

Listing is intentionally compact and omits each strategy's validation state. `getStrategyCode` and
`deleteStrategy` return `404` for a strategy not registered by the caller. Deleting a registration
does not alter historical backtests; recompiling the same source creates a new strategy id.
