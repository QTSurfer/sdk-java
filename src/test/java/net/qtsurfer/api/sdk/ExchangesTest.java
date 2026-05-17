package net.qtsurfer.api.sdk;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExchangesTest {

  @Test
  void exchangesMethodReturnsListType() throws NoSuchMethodException {
    var method = QTSurfer.class.getMethod("exchanges");
    assertEquals(List.class, method.getReturnType());
  }

  @Test
  void instrumentsMethodReturnsListType() throws NoSuchMethodException {
    var method = QTSurfer.class.getMethod("instruments", String.class);
    assertEquals(List.class, method.getReturnType());
  }

  @Test
  void instrumentsRejectsNullExchangeId() {
    QTSurfer qts = QTSurfer.builder()
        .baseUrl("https://api.qtsurfer.net/v1")
        .token("test-token")
        .build();
    assertThrows(NullPointerException.class, () -> qts.instruments(null));
  }
}
