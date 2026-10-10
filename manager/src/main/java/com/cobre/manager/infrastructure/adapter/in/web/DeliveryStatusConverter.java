package com.cobre.manager.infrastructure.adapter.in.web;

import com.cobre.manager.domain.model.DeliveryStatus;
import java.util.Locale;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/** Case-insensitive binding of delivery_status; Spring's default enum converter is not. */
@Component
public class DeliveryStatusConverter implements Converter<String, DeliveryStatus> {

  @Override
  public DeliveryStatus convert(String source) {
    String value = source.strip();
    return value.isEmpty() ? null : DeliveryStatus.valueOf(value.toUpperCase(Locale.ROOT));
  }
}
