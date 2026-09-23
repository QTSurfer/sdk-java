# Account limits and storage usage

Create an authenticated client before using either account method:

```java
import com.qtsurfer.api.sdk.QTSurfer;

QTSurfer qts = QTSurfer.builder()
        .baseUrl("https://api.qtsurfer.com/v1")
        .token(System.getenv("QTSURFER_TOKEN"))
        .build();
```

## Read tier limits

Both `QTSurfer` and API-key-authenticated `AuthenticatedClient` expose these operations. Use the
authenticated client when the application already exchanges an API key and needs automatic token
refresh; both surfaces return the same generated response types.

`getAccount()` returns the caller id, tier, and the limits that apply to the account.

```java
import com.qtsurfer.api.client.model.Account;

Account account = qts.getAccount();
System.out.printf("%s permits %d datasets and %d bytes total%n",
        account.getTier(), account.getMaxDatasets(), account.getMaxTotalStorageBytes());
```

## Read current usage

`getAccountUsage()` returns the current dataset, strategy, signal, and aggregate storage use.

```java
import com.qtsurfer.api.client.model.AccountUsage;

AccountUsage usage = qts.getAccountUsage();
System.out.printf("Signals use %d bytes; total use is %d bytes%n",
        usage.getSignalBytesUsed(), usage.getStorageBytesUsed());
```

Datasets, registered strategies, and retained execution signals share one storage pool. Check usage
before enabling relay for a high-volume live run; use `relay=false` when no consumer needs live
signals or retained history. See [live.md](live.md) for the relay and signal-history lifecycle.
